"""
Appose task: score one image's windows with a saved window classifier.

Refuses before it scores. A model is a pickle fitted to one feature set
measured one way; handed a matrix whose columns mean something else it will
return confident numbers for every window rather than an error, and nothing
downstream could tell. `wmodel.check_compatibility` is therefore not advisory:
a feature-set mismatch raises, and the geometry mismatches raise unless the
caller has explicitly overridden them, in which case the override is recorded
in the output so it travels with the map.

Three outputs beyond the probabilities, because a closed-set softmax on its
own will label tissue the model has never seen:

  margin     top1 - top2. Separates "confident" from "cannot decide between
             two classes"; 0.45 vs 0.44 and 0.45 vs 0.05 look identical in
             the top-1 probability alone.
  novelty p  chi-square tail of the robust Mahalanobis distance to the
             training distribution. A p-value, not a posterior.
  abstained  1 where the window failed the thresholds the training run chose
             out of fold.

Inputs (Appose injected as variables):

  features        : NDArray float32 (n, d)
  feature_names   : str, comma-joined, column order
  model_dir       : str
  geometry_json   : str, the geometry THIS matrix was measured with
  allow_override  : bool, lets a geometry mismatch through, recorded
  min_confidence  : float, optional; defaults to the trained threshold
  min_margin      : float, optional (default 0.0)
  min_novelty_p   : float, optional (default 0.001)
  output_npz_path : str

Outputs:
    task.outputs['ok'], 'class_names', 'verdict' (the TRAINING verdict, so a
    provisional model stays provisional wherever it is applied), 'warnings'
    (newline-joined), 'n_abstained'. The npz holds, all length n:

      proba     float32 (n, n_classes)
      predicted int32   argmax class index
      max_proba float32
      margin    float32
      novelty_p float32 (NaN when no training reference was saved)
      abstained int32
"""

import json
import logging
import os

import numpy as np

logger = logging.getLogger("fiber.apply_window_classifier")


def opt_get(name, default):
    return globals().get(name, default)


try:
    from fiberlib import wmodel

    names = [n for n in str(feature_names).split(",") if n]
    mdir = str(model_dir)
    out_npz = str(output_npz_path)
    geometry = json.loads(str(opt_get("geometry_json", "{}")))
    allow_over = bool(opt_get("allow_override", False))

    x = np.asarray(features.ndarray(), dtype=np.float64)
    if x.ndim != 2 or x.shape[1] != len(names):
        raise ValueError(f"features shape {x.shape} does not match {len(names)} names")

    est, meta = wmodel.load_model(mdir)
    kind = str(meta.get("model_kind", "hist_gradient_boosting"))
    impute_values = meta.get("impute_values")
    classes = list(meta.get("class_names", []))
    n_classes = len(classes)

    # The metadata records the columns the ESTIMATOR sees, which for a forest
    # includes the appended missingness indicators. Compatibility is about
    # the measured features, so compare against those.
    trained = list(meta.get("feature_names", []))
    if impute_values is not None:
        trained = trained[: len(trained) // 2]
    warnings = wmodel.check_compatibility(
        {**meta, "feature_names": trained}, names, geometry, allow_override=allow_over
    )

    x_ready, _ = wmodel.prepare_matrix(
        x,
        kind,
        impute_values=(
            None
            if impute_values is None
            else np.asarray(impute_values, dtype=np.float64)
        ),
    )
    raw = est.predict_proba(x_ready)
    proba = np.zeros((x.shape[0], n_classes), dtype=np.float64)
    for col, cls in enumerate(est.classes_):
        proba[:, int(cls)] = raw[:, col]

    predicted = proba.argmax(axis=1).astype(np.int32)
    order = np.sort(proba, axis=1)
    max_proba = order[:, -1]
    margin = max_proba - order[:, -2] if n_classes > 1 else np.ones_like(max_proba)

    # Novelty needs the training distribution. Saved beside the model by the
    # training run; a model from an older build simply has no novelty channel
    # rather than a fabricated one.
    novelty = np.full(x.shape[0], np.nan, dtype=np.float64)
    ref_path = os.path.join(mdir, "train_reference.npz")
    if os.path.isfile(ref_path):
        try:
            ref = np.load(ref_path)["x"]
            novelty = np.asarray(wmodel.novelty_scores(ref, x), dtype=np.float64)
        except Exception as ex:  # noqa: BLE001 - a missing channel beats a wrong one
            logger.warning("Novelty unavailable (%s); that channel will be NaN", ex)
            warnings = list(warnings) + [f"novelty could not be computed: {ex}"]

    trained_thresholds = dict(meta.get("calibration", {}).get("abstention", {}) or {})
    min_conf = float(
        opt_get("min_confidence", trained_thresholds.get("min_confidence", 0.0))
    )
    min_marg = float(opt_get("min_margin", 0.0))
    min_nov = float(opt_get("min_novelty_p", 0.001))
    abstained = wmodel.abstain_mask(
        proba,
        novelty_p=None if np.all(np.isnan(novelty)) else novelty,
        min_confidence=min_conf,
        min_margin=min_marg,
        min_novelty_p=min_nov,
    ).astype(np.int32)

    np.savez_compressed(
        out_npz,
        proba=proba.astype(np.float32),
        predicted=predicted.astype(np.int32),
        max_proba=max_proba.astype(np.float32),
        margin=margin.astype(np.float32),
        novelty_p=novelty.astype(np.float32),
        abstained=abstained,
    )

    verdict = meta.get("verdict") or "UNKNOWN"
    logger.info(
        "apply_window_classifier scored %d windows into %d classes (verdict %s, %d abstained)",
        x.shape[0],
        n_classes,
        verdict,
        int(abstained.sum()),
    )

    task.outputs["ok"] = "1"
    task.outputs["class_names"] = ",".join(classes)
    task.outputs["verdict"] = str(verdict)
    task.outputs["warnings"] = "\n".join(warnings)
    task.outputs["n_abstained"] = str(int(abstained.sum()))
    task.outputs["min_confidence"] = str(min_conf)

except Exception as e:
    logger.error("apply_window_classifier failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
