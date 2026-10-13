"""
Appose task: train a window classifier, and decide whether it may be used.

The fit is the short part. Most of this script is the validity layer, because
overlapping windows from a handful of slides will produce a confident-looking
number under almost any protocol, and the only thing standing between that
number and a pathologist is what happens here.

What it does, in order:

  1. Viability (wsplits.check_viability) -- refuses before fitting anything
     when no slide-level estimate can exist, and names the excluded columns.
  2. Grouped cross-validation, leaving out one SLIDE at a time. Never a
     random split over windows: neighbouring windows share most of their
     pixels, so a random split reports the model's ability to interpolate
     between near-duplicates.
  3. Slide-level cluster bootstrap for the interval -- resampling slides, not
     windows, because the windows are not independent draws.
  4. Label-permutation null, permuting at ANNOTATION level within slides, so
     a score driven by slide identity cannot survive it.
  5. Leakage gap: the same data scored under a random window split, reported
     beside the honest number as "the optimism a random split would have
     reported". The most persuasive single number in the report.
  6. Three baselines -- majority, stratified, and the best single feature
     threshold chosen inside the training folds. A thirty-feature model that
     does not beat one threshold is not earning its complexity.
  7. Calibration (ECE) and abstention thresholds chosen out of fold.
  8. Grouped permutation importance, out of fold.
  9. A verdict. A refusal still produces the full report: refusing to predict
     is not refusing to inform.

Inputs (Appose injected as variables):

  features      : NDArray float32 (n, d)
  labels        : NDArray int32   (n,) class index
  groups        : NDArray int32   (n,) slide id -- the cross-validation unit
  annotations   : NDArray int32   (n,) annotation id, for the null
  feature_names : str, comma-joined, column order
  class_names   : str, comma-joined, indexed by label
  model_dir     : str, where to write model.joblib + metadata.json + report
  geometry_json : str, the geometry block recorded in the metadata and
                  re-checked at apply time
  model_kind    : str, 'hist_gradient_boosting' (default) or 'random_forest'
  seed          : int
  n_perm        : int, permutation-null repeats (default 200)
  n_boot        : int, cluster-bootstrap resamples (default 2000)
  imp_repeats   : int, permutation-importance repeats (default 5)
  target_accuracy : float, the accuracy abstention aims for (default 0.9)

Outputs:
    task.outputs['ok'], 'verdict' ('OK' | 'WARN' | 'REFUSE' | 'BLOCK'),
    'balanced_accuracy', 'leakage_gap', 'permutation_p', 'ece',
    'report_path', 'summary' (multi-line, for the dialog).
"""

import csv
import json
import logging
import os

import numpy as np

logger = logging.getLogger("fiber.train_window_classifier")


def opt_get(name, default):
    return globals().get(name, default)


def _write_confusion(path, counts, class_names):
    with open(path, "w", encoding="ascii", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["true\\predicted"] + list(class_names))
        for i, name in enumerate(class_names):
            w.writerow([name] + [int(v) for v in counts[i]])


def _pooled_proba(x, y, groups, n_classes, fit_proba, leave_one_group_out):
    """Out-of-fold probabilities, pooled.

    `wvalidate.cross_validate` pools predictions into a 1-D integer array, so
    it cannot carry a probability per class. Same folds and the same
    leave-one-slide-out rule; only the shape of what is pooled differs.
    """
    out = np.full((len(y), n_classes), np.nan, dtype=np.float64)
    for train_idx, test_idx in leave_one_group_out(groups):
        if len(train_idx) == 0 or len(test_idx) == 0:
            continue
        out[test_idx] = fit_proba(x[train_idx], y[train_idx], x[test_idx])
    return out, np.isfinite(out).all(axis=1)


def _write_importance(path, importance):
    """`importance` is wvalidate's list of records, already sorted."""
    with open(path, "w", encoding="ascii", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["feature", "mean_drop_in_balanced_accuracy", "sd"])
        for rec in importance:
            w.writerow(
                [
                    rec["feature"],
                    float(rec["importance"]),
                    float(rec.get("sd", float("nan"))),
                ]
            )


try:
    from fiberlib import wmodel, wsplits, wvalidate

    names = [n for n in str(feature_names).split(",") if n]
    classes = [c for c in str(class_names).split(",") if c]
    out_dir = str(model_dir)
    kind = str(opt_get("model_kind", "hist_gradient_boosting"))
    rnd = int(opt_get("seed", 0))
    n_perm = int(opt_get("n_perm", 200))
    n_boot = int(opt_get("n_boot", 2000))
    imp_repeats = int(opt_get("imp_repeats", 5))
    target_acc = float(opt_get("target_accuracy", 0.9))
    geometry = json.loads(str(opt_get("geometry_json", "{}")))

    x = np.asarray(features.ndarray(), dtype=np.float64)
    y = np.asarray(labels.ndarray(), dtype=np.int64).reshape(-1)
    g = np.asarray(groups.ndarray(), dtype=np.int64).reshape(-1)
    ann = np.asarray(annotations.ndarray(), dtype=np.int64).reshape(-1)
    if x.ndim != 2 or x.shape[1] != len(names):
        raise ValueError(f"features shape {x.shape} does not match {len(names)} names")
    if not (len(y) == len(g) == len(ann) == x.shape[0]):
        raise ValueError(
            f"row counts disagree: features {x.shape[0]}, labels {len(y)}, "
            f"groups {len(g)}, annotations {len(ann)}"
        )

    os.makedirs(out_dir, exist_ok=True)
    report = {
        "n_windows": int(x.shape[0]),
        "n_features": len(names),
        "n_annotations": int(len(np.unique(ann))),
        "n_slides": int(len(np.unique(g))),
        "class_names": classes,
        "model_kind": kind,
        "seed": rnd,
        "geometry": geometry,
    }

    # ---- 1. Viability, before any fit ----
    via = wsplits.check_viability(x, y, g, names, classes, annotations=ann)
    report["viability"] = via.to_dict()
    if via.verdict == wsplits.BLOCK:
        report["verdict"] = "BLOCK"
        report["summary"] = via.summary()
        report_path = os.path.join(out_dir, "report.json")
        with open(report_path, "w", encoding="ascii") as fh:
            json.dump(report, fh, indent=1, sort_keys=True, default=float)
        task.outputs["ok"] = "1"
        task.outputs["verdict"] = "BLOCK"
        task.outputs["report_path"] = report_path
        task.outputs["summary"] = via.summary()
        logger.warning("Training blocked before fitting: %s", via.summary())
        raise SystemExit(0)

    # Columns the viability check excluded are dropped here, not imputed.
    excluded = set(via.to_dict().get("excluded_features", {}))
    keep = [j for j, n in enumerate(names) if n not in excluded]
    if len(keep) < len(names):
        logger.info(
            "Dropping %d excluded feature(s): %s",
            len(names) - len(keep),
            sorted(excluded),
        )
    names = [names[j] for j in keep]
    x = x[:, keep]
    report["feature_names"] = names

    n_classes = len(classes)
    fit_predict = wmodel.fit_predict_factory(kind=kind, n_classes=n_classes, seed=rnd)
    fit_proba = wmodel.fit_predict_proba_factory(
        kind=kind, n_classes=n_classes, seed=rnd
    )

    # ---- 2. Grouped cross-validation ----
    cv = wvalidate.cross_validate(x, y, g, n_classes, fit_predict)
    report["cv"] = {
        "balanced_accuracy": cv["balanced_accuracy"],
        "accuracy": cv["accuracy"],
        "n_windows": cv["n_windows"],
        "n_groups": cv["n_groups"],
        "per_group": cv["per_group"],
    }
    _write_confusion(
        os.path.join(out_dir, "confusion_matrix.csv"), cv["counts"], classes
    )

    # ---- 3. Slide-level interval ----
    boot = wvalidate.cluster_bootstrap(
        y[cv["scored"]],
        cv["y_pred"][cv["scored"]],
        g[cv["scored"]],
        n_classes,
        n_boot=n_boot,
        seed=rnd,
    )
    report["bootstrap"] = boot

    # ---- 4. Permutation null at annotation level ----
    null = wvalidate.permutation_null(
        x, y, g, n_classes, fit_predict, annotations=ann, n_perm=n_perm, seed=rnd
    )
    report["permutation_null"] = {k: v for k, v in null.items() if k != "null"}
    report["permutation_null"]["null_quantiles"] = np.percentile(
        null["null"], [5, 50, 95]
    ).tolist()

    # ---- 5. Leakage gap ----
    gap_rec = wvalidate.leakage_gap(x, y, g, n_classes, fit_predict, seed=rnd)
    gap = float(gap_rec["gap"])
    report["leakage_gap"] = gap_rec

    # ---- 6. Baselines ----
    baselines = {
        "majority": wvalidate.cross_validate(
            x, y, g, n_classes, wvalidate.majority_baseline(y, g, n_classes)
        )["balanced_accuracy"],
        "stratified": wvalidate.cross_validate(
            x, y, g, n_classes, wvalidate.stratified_baseline(n_classes, seed=rnd)
        )["balanced_accuracy"],
    }
    single = wvalidate.best_single_feature_baseline(n_classes, feature_names=names)
    baselines["best_single_feature"] = wvalidate.cross_validate(
        x, y, g, n_classes, single
    )["balanced_accuracy"]
    baselines["best_single_feature_name"] = getattr(single, "chosen", {}).get("name")
    report["baselines"] = baselines

    # ---- 7. Calibration + abstention, both out of fold ----
    proba, proba_scored = _pooled_proba(
        x, y, g, n_classes, fit_proba, wsplits.leave_one_group_out
    )
    cal = wmodel.expected_calibration_error(proba[proba_scored], y[proba_scored])
    ece = float(cal["ece"])
    thresholds = wmodel.choose_abstention_thresholds(
        proba[proba_scored], y[proba_scored], target_accuracy=target_acc
    )
    report["risk_coverage"] = wmodel.risk_coverage(proba[proba_scored], y[proba_scored])
    report["calibration"] = {**cal, "note": wmodel.calibration_note(ece)}
    report["abstention"] = thresholds

    # ---- 8. Importance ----
    importance = wvalidate.permutation_importance(
        x,
        y,
        g,
        n_classes,
        fit_predict,
        feature_names=names,
        n_repeats=imp_repeats,
        seed=rnd,
    )
    _write_importance(os.path.join(out_dir, "importance.csv"), importance)
    report["importance_top"] = [
        {"feature": rec["feature"], "mean_drop": float(rec["importance"])}
        for rec in importance[:10]
    ]

    # ---- 9. Verdict ----
    refusals = []
    warnings_extra = []
    ci_lo = float(boot.get("lo", float("nan")))
    ci_hi = float(boot.get("hi", float("nan")))
    if np.isfinite(ci_lo) and ci_lo <= baselines["majority"]:
        refusals.append(
            f"the balanced-accuracy interval ({ci_lo:.3f} to {ci_hi:.3f}) includes the majority "
            f"baseline ({baselines['majority']:.3f}): these classes are not separable at slide "
            f"level with these features."
        )
    # The smallest p an empirical null can report is 1/(n_perm + 1). Below
    # enough shuffles, p > 0.05 is arithmetic rather than evidence, and
    # refusing on it would be refusing a model the test never examined.
    # "Could not be evaluated" and "evaluated and failed" are different
    # findings and must not share a sentence.
    p_floor = 1.0 / (int(null.get("n_perm", 0)) + 1)
    if p_floor > 0.05:
        warnings_extra.append(
            f"the permutation null ran {null.get('n_perm', 0)} shuffles, so the smallest p it can "
            f"report is {p_floor:.3f} and it cannot reject at 0.05. Raise the shuffle count to at "
            f"least 20 for this check to mean anything."
        )
    elif null.get("p", 1.0) > 0.05:
        refusals.append(
            f"a permutation null is not rejected (p = {null['p']:.3f}): the score is within what "
            f"shuffled labels achieve, so it is not evidence of a real difference."
        )
    ci_width = ci_hi - ci_lo
    if (
        np.isfinite(ci_width)
        and cv["balanced_accuracy"] - baselines["best_single_feature"] < ci_width
    ):
        refusals.append(
            f"a single threshold on '{baselines['best_single_feature_name']}' scores "
            f"{baselines['best_single_feature']:.3f} against the model's {cv['balanced_accuracy']:.3f}, "
            f"a gap smaller than the interval width ({ci_width:.3f}). The {len(names)}-feature model is "
            f"not earning its complexity; consider using that threshold instead."
        )
    below_chance = [
        str(row["group"])
        for row in cv["per_group"]
        if row["balanced_accuracy"] < 1.0 / n_classes
    ]
    if below_chance:
        refusals.append(
            f"held-out slide(s) {', '.join(below_chance)} score below chance. A model that is wrong "
            f"on a whole slide is not merely noisy there."
        )
    if np.isfinite(ece) and ece > wmodel.MAX_TRUSTWORTHY_ECE:
        refusals.append(
            f"expected calibration error is {ece:.3f}, above {wmodel.MAX_TRUSTWORTHY_ECE}: the "
            f"probabilities are not trustworthy as probabilities. Read the map as tiers, not numbers."
        )

    if refusals:
        verdict = "REFUSE"
    elif via.verdict == wsplits.WARN or warnings_extra:
        verdict = "WARN"
    else:
        verdict = "OK"
    report["verdict"] = verdict
    report["refusals"] = refusals
    report["warnings"] = warnings_extra

    summary_lines = [
        f"Verdict: {verdict}",
        f"Balanced accuracy {cv['balanced_accuracy']:.3f} [{ci_lo:.3f}, {ci_hi:.3f}] "
        f"over {cv['n_windows']} windows / {report['n_annotations']} annotations / {cv['n_groups']} slides",
        f"Baselines: majority {baselines['majority']:.3f}, stratified {baselines['stratified']:.3f}, "
        f"best single feature {baselines['best_single_feature']:.3f} "
        f"({baselines['best_single_feature_name']})",
        f"Permutation null p = {null.get('p', float('nan')):.3f} over {null.get('n_perm', 0)} shuffles",
        f"Leakage gap {gap:+.3f} -- the optimism a random window split would have reported "
        f"({gap_rec['random_split_balanced_accuracy']:.3f} against the honest "
        f"{gap_rec['grouped_balanced_accuracy']:.3f})",
        f"Calibration ECE {ece:.3f}: {wmodel.calibration_note(ece)}",
    ]
    summary_lines.extend("REFUSE: " + r for r in refusals)
    summary_lines.extend("NOT EVALUATED: " + w for w in warnings_extra)
    summary_lines.extend(via.summary().splitlines())
    summary = "\n".join(summary_lines)
    report["summary"] = summary

    # ---- Fit the shipped model on everything, and save ----
    est = wmodel.build_estimator(kind=kind, n_classes=n_classes, seed=rnd)
    x_fit, impute_values = wmodel.prepare_matrix(x, kind)
    est.fit(x_fit, y)
    saved_names = (
        names if impute_values is None else names + wmodel.indicator_names(names)
    )
    wmodel.save_model(
        out_dir,
        est,
        saved_names,
        classes,
        geometry,
        kind=kind,
        impute_values=impute_values,
        training={
            "n_windows": int(x.shape[0]),
            "n_annotations": report["n_annotations"],
            "n_slides": report["n_slides"],
            "balanced_accuracy": cv["balanced_accuracy"],
            "balanced_accuracy_ci": [ci_lo, ci_hi],
            "permutation_p": null.get("p"),
            "leakage_gap": gap,
            "leakage_gap_detail": gap_rec,
            "baselines": baselines,
        },
        calibration={"ece": ece, "abstention": thresholds},
        verdict=verdict,
    )
    # The training distribution, for the novelty channel at apply time.
    np.savez_compressed(
        os.path.join(out_dir, "train_reference.npz"), x=x.astype(np.float32)
    )

    report_path = os.path.join(out_dir, "report.json")
    with open(report_path, "w", encoding="ascii") as fh:
        json.dump(report, fh, indent=1, sort_keys=True, default=float)

    logger.info("Trained %s: %s", kind, summary.replace("\n", " | "))
    task.outputs["ok"] = "1"
    task.outputs["verdict"] = verdict
    task.outputs["balanced_accuracy"] = str(cv["balanced_accuracy"])
    task.outputs["leakage_gap"] = str(gap)
    task.outputs["permutation_p"] = str(null.get("p", float("nan")))
    task.outputs["ece"] = str(ece)
    task.outputs["report_path"] = report_path
    task.outputs["summary"] = summary

except SystemExit:
    pass
except Exception as e:
    logger.error("train_window_classifier failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
