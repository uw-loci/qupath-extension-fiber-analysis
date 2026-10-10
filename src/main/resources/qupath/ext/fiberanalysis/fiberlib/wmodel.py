"""
Train, persist, load and apply the window region classifier.

The only module in fiberlib that imports scikit-learn, so everything else --
the feature bank, the splitters, the whole validity layer -- stays runnable
without it.

Three things here are deliberate and worth stating.

**HistGradientBoosting by default.** It handles NaN natively, and "this
measurement is undefined in this window" is both common and informative: on
the real MH_Colon run tortuosity_median was null in 7,891 of 7,935 scored
windows. A RandomForest needs the gaps filled first, so when one is asked for
the median is imputed AND a `<feature>_isnan` indicator column is appended, so
the imputation stays visible to the model and to the reader rather than being
quietly papered over.

**A model refuses data it cannot honestly score.** The artifact records the
exact feature names and order, the window geometry, and the pixel size. The
last one is the subtle one. Matching the window size in MICRONS is not
enough: lacunarity, gap statistics and GLCM are computed over box and offset
sizes in PIXELS, so a 50 um window is 200 px at 0.25 um/px and 100 px at 0.5,
and the features are not comparable. Pixel size has to match too, and a
mismatch is refused rather than warned about.

**Probabilities are calibrated, and the calibration is reported.** An
uncalibrated softmax over the pathologist's own classes will be confidently
wrong on tissue it has never seen. Calibration is fitted on held-out slides,
never on the training folds, and the expected calibration error comes back
with the model so the caller can refuse to show numbers that do not deserve
to be read as probabilities.
"""
import json
import logging
import os
import platform
import sys
import time

import numpy as np

logger = logging.getLogger("fiber.wmodel")

#: Bump when the on-disk layout changes in a way a previous reader cannot cope
#: with. A model written by a newer schema is refused, not guessed at.
SCHEMA_VERSION = 1

#: Pixel size must agree this closely. Box-counting and co-occurrence offsets
#: are in pixels, so a different pixel size means different measurements under
#: the same name.
PIXEL_SIZE_TOLERANCE = 0.02

#: Above this expected calibration error the probabilities should not be shown
#: as probabilities.
MAX_TRUSTWORTHY_ECE = 0.15


class ModelRefusal(Exception):
    """Raised when a model must not be applied to the data it was handed."""


def _sklearn():
    try:
        import sklearn
    except ImportError as exc:  # pragma: no cover - exercised only without sklearn
        raise ModelRefusal(
            "scikit-learn is not installed in this environment, so no model can be trained or "
            "applied. The fiber-analysis Appose environment pins scikit-learn 1.9.*; if this is a "
            "hand-made environment, install it there."
        ) from exc
    return sklearn


def build_estimator(kind="hist_gradient_boosting", n_classes=2, seed=0, **kwargs):
    """Construct the estimator. Only two kinds are offered, both tree-based.

    Tree ensembles are chosen over anything else because exact TreeSHAP is
    available for them, which matters when explainability is the entire
    argument for this design over a convolutional network.
    """
    _sklearn()
    kind = str(kind)
    if kind == "hist_gradient_boosting":
        from sklearn.ensemble import HistGradientBoostingClassifier

        params = {
            "max_iter": 200,
            "learning_rate": 0.1,
            "max_leaf_nodes": 31,
            "l2_regularization": 1.0,
            # Off deliberately. HistGB's internal validation split is random
            # and ungrouped, so with overlapping windows it would stop on a
            # score inflated by near-duplicates of its own training rows.
            # The cost is that every fit runs the full max_iter: measured at
            # 1.66 s on 400 rows by 13 features, almost all fixed overhead,
            # which matters mainly to the permutation null where the fit
            # count is n_perm * n_folds.
            "early_stopping": False,
            "random_state": seed,
            # Pathologist-drawn training regions are never balanced.
            "class_weight": "balanced",
        }
        params.update(kwargs)
        return HistGradientBoostingClassifier(**params)
    if kind == "random_forest":
        from sklearn.ensemble import RandomForestClassifier

        params = {
            "n_estimators": 500,
            "min_samples_leaf": 2,
            "n_jobs": 1,  # joblib/loky fan-out can deadlock inside an Appose worker
            "random_state": seed,
            "class_weight": "balanced_subsample",
        }
        params.update(kwargs)
        return RandomForestClassifier(**params)
    raise ValueError(f"unknown model kind '{kind}'; expected hist_gradient_boosting or random_forest")


def _needs_imputation(kind):
    return str(kind) == "random_forest"


def prepare_matrix(x, kind, impute_values=None):
    """Return ``(x_ready, impute_values)`` for the estimator's NaN policy.

    HistGradientBoosting routes NaN itself, so the matrix passes through. A
    forest cannot, so each column's median is substituted and an indicator
    column appended per feature -- the model can then learn from the fact of
    absence, and a reader can see that it did.
    """
    x = np.asarray(x, dtype=np.float64)
    if not _needs_imputation(kind):
        return x, None
    if impute_values is None:
        with np.errstate(all="ignore"):
            impute_values = np.nanmedian(x, axis=0)
        impute_values = np.where(np.isfinite(impute_values), impute_values, 0.0)
    impute_values = np.asarray(impute_values, dtype=np.float64)
    missing = ~np.isfinite(x)
    filled = np.where(missing, impute_values[None, :], x)
    return np.hstack([filled, missing.astype(np.float64)]), impute_values


def indicator_names(feature_names):
    """Column names a forest's appended missingness indicators carry."""
    return [f"{n}_isnan" for n in feature_names]


def fit_predict_factory(kind="hist_gradient_boosting", n_classes=2, seed=0, **kwargs):
    """A ``fit_predict`` callable for the validity layer in `wvalidate`.

    Lets the whole cross-validation, bootstrap and permutation machinery run
    against the real estimator without knowing anything about it.
    """

    def fit_predict(x_tr, y_tr, x_te):
        est = build_estimator(kind, n_classes=n_classes, seed=seed, **kwargs)
        xt, imp = prepare_matrix(x_tr, kind)
        xe, _ = prepare_matrix(x_te, kind, impute_values=imp)
        est.fit(xt, y_tr)
        return est.predict(xe)

    return fit_predict


def fit_predict_proba_factory(kind="hist_gradient_boosting", n_classes=2, seed=0, **kwargs):
    """As :func:`fit_predict_factory`, but returning class probabilities.

    Columns are indexed by GLOBAL class index, not by the classes that
    happened to appear in a training fold. A fold missing a class would
    otherwise shift every column after it, and the resulting probabilities
    would be attributed to the wrong classes with nothing to show for it.
    """

    def fit_proba(x_tr, y_tr, x_te):
        est = build_estimator(kind, n_classes=n_classes, seed=seed, **kwargs)
        xt, imp = prepare_matrix(x_tr, kind)
        xe, _ = prepare_matrix(x_te, kind, impute_values=imp)
        est.fit(xt, y_tr)
        p = est.predict_proba(xe)
        out = np.zeros((len(xe), n_classes), dtype=np.float64)
        for col, cls in enumerate(est.classes_):
            out[:, int(cls)] = p[:, col]
        return out

    return fit_proba


# ---- calibration -----------------------------------------------------------


def expected_calibration_error(proba, y_true, n_bins=10):
    """ECE and MCE of the top-class probability, with the per-bin table.

    Reported so a caller can refuse to present numbers that do not behave
    like probabilities, rather than letting a pathologist read a confident
    0.9 that is right 60% of the time.
    """
    proba = np.asarray(proba, dtype=np.float64)
    y_true = np.asarray(y_true, dtype=np.int64)
    if proba.size == 0:
        return {"ece": float("nan"), "mce": float("nan"), "bins": []}
    conf = proba.max(axis=1)
    pred = proba.argmax(axis=1)
    correct = (pred == y_true).astype(np.float64)

    edges = np.linspace(0.0, 1.0, int(n_bins) + 1)
    ece = 0.0
    mce = 0.0
    bins = []
    for b in range(int(n_bins)):
        lo, hi = edges[b], edges[b + 1]
        sel = (conf > lo) & (conf <= hi) if b > 0 else (conf >= lo) & (conf <= hi)
        n = int(sel.sum())
        if n == 0:
            continue
        acc = float(correct[sel].mean())
        avg_conf = float(conf[sel].mean())
        gap = abs(acc - avg_conf)
        ece += (n / len(conf)) * gap
        mce = max(mce, gap)
        bins.append({"lo": float(lo), "hi": float(hi), "n": n, "accuracy": acc, "confidence": avg_conf})
    return {"ece": float(ece), "mce": float(mce), "bins": bins}


def calibration_note(ece):
    """A sentence the UI can show beside any probability, or None."""
    if not np.isfinite(ece):
        return "Calibration could not be measured, so the probabilities are unverified."
    if ece > MAX_TRUSTWORTHY_ECE:
        return (
            f"Expected calibration error is {ece:.3f}, above {MAX_TRUSTWORTHY_ECE:.2f}. These numbers "
            f"do not behave like probabilities and should be read as a rank order only."
        )
    return (
        f"Expected calibration error {ece:.3f}. Note that probabilities are calibrated against the "
        f"class mix in the TRAINING annotations; if those were drawn in different proportions from "
        f"the tissue being scored, the prior is wrong even when the calibration is good."
    )


# ---- abstention ------------------------------------------------------------


def novelty_scores(x_train, x_query):
    """Chi-square p for Mahalanobis distance to the training distribution.

    Not a posterior and not comparable to one: it answers "does this window
    look like anything the model was trained on", which a closed-set softmax
    cannot, because it must put its mass on one of the classes it knows.
    """
    _sklearn()
    from scipy import stats
    from sklearn.covariance import MinCovDet

    xt = np.asarray(x_train, dtype=np.float64)
    xq = np.asarray(x_query, dtype=np.float64)
    good = np.isfinite(xt).all(axis=1)
    xt = xt[good]
    if len(xt) < 2 * xt.shape[1] or xt.shape[1] == 0:
        # Robust covariance needs more rows than columns by a comfortable
        # margin; saying so beats returning a confident number from a
        # singular estimate.
        return np.full(len(xq), np.nan)
    try:
        mcd = MinCovDet(support_fraction=0.9, random_state=0).fit(xt)
        d2 = mcd.mahalanobis(np.nan_to_num(xq, nan=np.nanmedian(xt)))
    except Exception as exc:  # pragma: no cover - degenerate covariance
        logger.warning("novelty estimate unavailable: %s", exc)
        return np.full(len(xq), np.nan)
    return np.asarray(stats.chi2.sf(d2, df=xt.shape[1]), dtype=np.float64)


def abstain_mask(proba, novelty_p=None, min_confidence=0.0, min_margin=0.0, min_novelty_p=0.001):
    """Which windows should carry no class at all.

    A window is abstained when the model is not confident, when the top two
    classes are too close to separate, or when it does not resemble the
    training data. Abstained windows must be rendered in a reserved colour,
    never as a pale shade of a class, which reads as a confident "other".
    """
    proba = np.asarray(proba, dtype=np.float64)
    if proba.size == 0:
        return np.zeros(0, dtype=bool)
    top = proba.max(axis=1)
    if proba.shape[1] >= 2:
        part = np.partition(proba, -2, axis=1)
        margin = part[:, -1] - part[:, -2]
    else:
        margin = top
    out = (top < float(min_confidence)) | (margin < float(min_margin))
    if novelty_p is not None:
        np_arr = np.asarray(novelty_p, dtype=np.float64)
        out = out | (np.isfinite(np_arr) & (np_arr < float(min_novelty_p)))
    return out


def risk_coverage(proba, y_true, novelty_p=None, n_points=21):
    """Accuracy against the fraction of windows retained, as confidence rises.

    For a pathologist deciding whether to trust a map, this single curve does
    more work than any other artifact here: it says what the map is worth if
    the least certain tenth, or half, is set aside.
    """
    proba = np.asarray(proba, dtype=np.float64)
    y_true = np.asarray(y_true, dtype=np.int64)
    if proba.size == 0:
        return []
    conf = proba.max(axis=1)
    pred = proba.argmax(axis=1)
    correct = (pred == y_true).astype(np.float64)
    order = np.argsort(-conf)
    curve = []
    for frac in np.linspace(1.0, 0.05, int(n_points)):
        k = max(1, int(round(frac * len(order))))
        sel = order[:k]
        curve.append(
            {
                "coverage": float(k / len(order)),
                "accuracy": float(correct[sel].mean()),
                "min_confidence": float(conf[sel].min()),
            }
        )
    return curve


def choose_abstention_thresholds(proba, y_true, target_accuracy=0.9, n_points=41):
    """Pick a confidence floor that reaches the target on retained windows.

    Returns the threshold AND the coverage it costs, because a threshold
    without its coverage is a claim with the price hidden.
    """
    proba = np.asarray(proba, dtype=np.float64)
    y_true = np.asarray(y_true, dtype=np.int64)
    if proba.size == 0:
        return {"min_confidence": 0.0, "coverage": 0.0, "accuracy": float("nan"), "achieved": False}
    conf = proba.max(axis=1)
    correct = (proba.argmax(axis=1) == y_true).astype(np.float64)
    best = {"min_confidence": 0.0, "coverage": 1.0, "accuracy": float(correct.mean()), "achieved": False}
    for t in np.linspace(float(conf.min()), float(conf.max()), int(n_points)):
        sel = conf >= t
        if not sel.any():
            continue
        acc = float(correct[sel].mean())
        cov = float(sel.mean())
        if acc >= float(target_accuracy) and cov > (best["coverage"] if best["achieved"] else -1):
            best = {"min_confidence": float(t), "coverage": cov, "accuracy": acc, "achieved": True}
    return best


# ---- persistence -----------------------------------------------------------


def save_model(
    out_dir,
    estimator,
    feature_names,
    class_names,
    geometry,
    kind="hist_gradient_boosting",
    impute_values=None,
    training=None,
    calibration=None,
    verdict=None,
):
    """Write the model and everything needed to refuse it later.

    The metadata carries the feature names IN ORDER, the window geometry
    including pixel size, and the verdict from validation -- so a model that
    failed its own checks says so wherever it travels, not only in the dialog
    that was dismissed.
    """
    _sklearn()
    import joblib
    import sklearn

    os.makedirs(out_dir, exist_ok=True)
    joblib.dump(estimator, os.path.join(out_dir, "model.joblib"))

    meta = {
        "schema_version": SCHEMA_VERSION,
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "model_kind": str(kind),
        "class_names": list(class_names),
        "feature_names": list(feature_names),
        "impute_values": None if impute_values is None else [float(v) for v in impute_values],
        "geometry": dict(geometry),
        "versions": {
            "sklearn": sklearn.__version__,
            "numpy": np.__version__,
            "python": platform.python_version(),
        },
        "training": dict(training or {}),
        "calibration": dict(calibration or {}),
        "verdict": verdict,
    }
    with open(os.path.join(out_dir, "metadata.json"), "w", encoding="ascii") as fh:
        json.dump(meta, fh, indent=1, sort_keys=True)
    return meta


def load_model(model_dir):
    """Load a saved model. Returns ``(estimator, metadata)``."""
    _sklearn()
    import joblib

    meta_path = os.path.join(model_dir, "metadata.json")
    if not os.path.isfile(meta_path):
        raise ModelRefusal(f"no metadata.json in {model_dir}; this is not a saved fiber classifier")
    with open(meta_path, encoding="ascii") as fh:
        meta = json.load(fh)

    found = int(meta.get("schema_version", 0))
    if found > SCHEMA_VERSION:
        raise ModelRefusal(
            f"the model was written with schema version {found} and this extension understands "
            f"{SCHEMA_VERSION}. Update the extension rather than let an older reader guess at a "
            f"newer layout."
        )
    import sklearn

    want = str(meta.get("versions", {}).get("sklearn", ""))
    if want and want != sklearn.__version__:
        raise ModelRefusal(
            f"the model was trained with scikit-learn {want} and this environment has "
            f"{sklearn.__version__}. A saved model is a pickle and is not portable across "
            f"versions; retrain it rather than trust an unpickle that merely did not raise."
        )
    est = joblib.load(os.path.join(model_dir, "model.joblib"))
    return est, meta


def check_compatibility(meta, feature_names, geometry, allow_override=False):
    """Decide whether this model may score this data.

    Returns a list of warning strings; raises :class:`ModelRefusal` on
    anything that would make the output meaningless rather than merely
    uncertain.

    Args:
        meta:           metadata from :func:`load_model`.
        feature_names:  the columns about to be handed to the model.
        geometry:       the geometry those columns were measured with.
        allow_override: lets the geometry mismatches through, recorded. Never
                        lets a feature mismatch through -- that one is not a
                        judgement call.
    """
    warnings = []
    want = list(meta.get("feature_names", []))
    have = list(feature_names)
    if want != have:
        missing = [f for f in want if f not in have]
        extra = [f for f in have if f not in want]
        detail = []
        if missing:
            detail.append(f"missing {missing}")
        if extra:
            detail.append(f"unexpected {extra}")
        if not detail:
            detail.append("same features in a different order")
        raise ModelRefusal(
            "the feature set does not match the one the model was trained on: "
            + "; ".join(detail)
            + ". A model scores columns by position, so this would silently read one measurement "
            "as another."
        )

    g_want = dict(meta.get("geometry", {}))
    g_have = dict(geometry)
    hard = []

    px_w = float(g_want.get("pixel_size_um", 0) or 0)
    px_h = float(g_have.get("pixel_size_um", 0) or 0)
    if px_w > 0 and px_h > 0:
        rel = abs(px_h - px_w) / px_w
        if rel > PIXEL_SIZE_TOLERANCE:
            hard.append(
                f"pixel size {px_h:.4f} um differs from the trained {px_w:.4f} um by "
                f"{100 * rel:.1f}%. Matching the window size in microns is NOT sufficient: "
                f"lacunarity, gap statistics and texture are computed over box sizes in PIXELS, so "
                f"the same feature name means a different measurement at a different pixel size."
            )

    for key, label in (("window_um", "window size"), ("stride_px", "stride")):
        a = g_want.get(key)
        b = g_have.get(key)
        if a is not None and b is not None and float(a) != float(b):
            if key == "window_um":
                hard.append(f"{label} {b} differs from the trained {a}; the features describe a different area")
            else:
                warnings.append(f"{label} {b} differs from the trained {a}; the map resolution will differ")

    seg_w = g_want.get("segmentation_hash")
    seg_h = g_have.get("segmentation_hash")
    if seg_w and seg_h and seg_w != seg_h:
        hard.append(
            f"the segmentation settings differ from training (hash {seg_h} against {seg_w}). The "
            f"fiber mask defines every feature, so these numbers are not comparable."
        )

    if hard:
        if not allow_override:
            raise ModelRefusal(
                "this model cannot honestly score this data:\n  - " + "\n  - ".join(hard)
                + "\nRetrain at the matching settings, or override deliberately -- an override is "
                "recorded in the run provenance."
            )
        warnings.extend("OVERRIDDEN: " + h for h in hard)
    return warnings
