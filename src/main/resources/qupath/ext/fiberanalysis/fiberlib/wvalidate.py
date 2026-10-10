"""
Honest cross-validation for the window region classifier.

Three things in here exist because the obvious version of each is wrong on
this data.

**The confidence interval resamples slides, not windows.** Resampling
individual (actual, predicted) pairs treats 7,935 overlapping windows from 10
slides as 7,935 independent observations. The resulting interval is roughly
sqrt(7935/10) = 28x too narrow. A cluster bootstrap over slides gives a wide
interval, and wide is the correct answer at n = 10.

**The permutation null shuffles labels within slides, at annotation level.**
Shuffling individual window labels destroys the group structure as well as the
class signal, so the null is far too easy to beat and almost anything looks
significant. Permuting whole annotations inside their own slide keeps both the
spatial correlation and the slide composition, and asks only the question that
matters: is there class signal beyond what slide identity alone supplies.

**The leakage gap is reported, not hidden.** Running the identical model under
grouped and random-window splits and printing both states, in the units of the
metric itself, exactly how much optimism a random split would have bought.

This module takes a ``fit_predict`` callable rather than a model, so the
statistics are testable against a trivial estimator with no scikit-learn and
no images. Metrics beyond balanced accuracy are deliberately NOT computed
here: Python emits confusion counts and the Java side turns them into the full
battery via the confusion-matrix extension, so there is one implementation of
kappa rather than two that have to be kept agreeing.
"""
import logging

import numpy as np

from . import wsplits

logger = logging.getLogger("fiber.wvalidate")


def confusion_counts(y_true, y_pred, n_classes):
    """(C, C) integer matrix, rows actual and columns predicted."""
    y_true = np.asarray(y_true, dtype=np.int64)
    y_pred = np.asarray(y_pred, dtype=np.int64)
    n = int(n_classes)
    flat = np.bincount(y_true * n + y_pred, minlength=n * n)
    return flat.reshape(n, n).astype(np.int64)


def balanced_accuracy(counts):
    """Mean per-class recall, ignoring classes with no actual examples.

    The headline metric. Plain accuracy is reported too but never led with:
    on an imbalanced set it is maximised by predicting the majority class and
    learning nothing.
    """
    counts = np.asarray(counts, dtype=np.float64)
    support = counts.sum(axis=1)
    live = support > 0
    if not live.any():
        return float("nan")
    recall = np.divide(np.diag(counts), support, out=np.zeros_like(support), where=live)
    return float(recall[live].mean())


def accuracy(counts):
    """Overall fraction correct. Reported, never the headline."""
    counts = np.asarray(counts, dtype=np.float64)
    total = counts.sum()
    return float(np.trace(counts) / total) if total > 0 else float("nan")


def cross_validate(x, y, groups, n_classes, fit_predict, folds=None):
    """Run grouped cross-validation and pool the out-of-fold predictions.

    Args:
        x:           (n, d) feature matrix.
        y:           (n,) class index.
        groups:      (n,) slide id -- the split unit.
        n_classes:   number of classes.
        fit_predict: ``f(x_train, y_train, x_test) -> y_pred``.
        folds:       pre-built folds; defaults to leave-one-slide-out.

    Returns:
        dict with pooled ``y_pred``, ``counts``, ``balanced_accuracy``,
        ``accuracy``, and ``per_group`` -- the per-held-out-slide table, which
        is mandatory in any report. Collapsing it to a mean hides the case
        where eight slides score 0.9 and two score 0.4.
    """
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.int64)
    groups = np.asarray(groups)
    if folds is None:
        folds = wsplits.leave_one_group_out(groups)

    pooled = np.full(len(y), -1, dtype=np.int64)
    per_group = []
    for train_idx, test_idx in folds:
        if len(train_idx) == 0 or len(test_idx) == 0:
            continue
        pred = np.asarray(fit_predict(x[train_idx], y[train_idx], x[test_idx]), dtype=np.int64)
        pooled[test_idx] = pred
        c = confusion_counts(y[test_idx], pred, n_classes)
        held = np.unique(groups[test_idx])
        per_group.append(
            {
                "group": held[0].item() if len(held) == 1 else [g.item() for g in held],
                "n": int(len(test_idx)),
                "balanced_accuracy": balanced_accuracy(c),
                "accuracy": accuracy(c),
                "classes_present": int((c.sum(axis=1) > 0).sum()),
            }
        )

    scored = pooled >= 0
    counts = confusion_counts(y[scored], pooled[scored], n_classes)
    return {
        "y_pred": pooled,
        "scored": scored,
        "counts": counts,
        "balanced_accuracy": balanced_accuracy(counts),
        "accuracy": accuracy(counts),
        "per_group": per_group,
        "n_windows": int(scored.sum()),
        "n_groups": int(len(np.unique(groups))),
    }


def cluster_bootstrap(y_true, y_pred, groups, n_classes, n_boot=2000, seed=0, alpha=0.05):
    """Percentile interval for balanced accuracy, resampling SLIDES.

    Resampling windows would treat near-duplicate overlapping windows as
    independent and give an interval roughly sqrt(windows per slide) times too
    narrow.

    Returns:
        dict with ``point``, ``lo``, ``hi``, ``n_boot`` and the confidence
        level actually used.
    """
    y_true = np.asarray(y_true, dtype=np.int64)
    y_pred = np.asarray(y_pred, dtype=np.int64)
    groups = np.asarray(groups)
    uniq = np.unique(groups)
    idx_by_group = {g: np.flatnonzero(groups == g) for g in uniq}

    rng = np.random.default_rng(seed)
    stats = np.empty(int(n_boot), dtype=np.float64)
    for b in range(int(n_boot)):
        picked = rng.choice(len(uniq), size=len(uniq), replace=True)
        sel = np.concatenate([idx_by_group[uniq[i]] for i in picked])
        stats[b] = balanced_accuracy(confusion_counts(y_true[sel], y_pred[sel], n_classes))

    good = stats[np.isfinite(stats)]
    point = balanced_accuracy(confusion_counts(y_true, y_pred, n_classes))
    if good.size == 0:
        return {"point": point, "lo": float("nan"), "hi": float("nan"), "n_boot": 0, "level": 1 - alpha}
    return {
        "point": point,
        "lo": float(np.percentile(good, 100 * alpha / 2)),
        "hi": float(np.percentile(good, 100 * (1 - alpha / 2))),
        "n_boot": int(good.size),
        "level": 1 - alpha,
    }


def permutation_null(x, y, groups, n_classes, fit_predict, annotations=None, n_perm=200, seed=0):
    """Empirical p for balanced accuracy against a label-permuted null.

    Labels are permuted **within each slide**, and by annotation when
    annotation ids are supplied. That preserves the spatial correlation and
    each slide's class composition, so what is being tested is whether there
    is class signal beyond slide identity -- not merely whether the data has
    any structure at all, which a window-level shuffle would answer yes to
    almost regardless.

    Returns:
        dict with ``observed``, ``null`` (the sampled statistics), ``p``, and
        ``n_perm``. ``p`` is ``(1 + #{null >= observed}) / (1 + n_perm)``, so
        it can never be reported as exactly zero.
    """
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.int64)
    groups = np.asarray(groups)
    units = np.asarray(annotations) if annotations is not None else np.arange(len(y))

    observed = cross_validate(x, y, groups, n_classes, fit_predict)["balanced_accuracy"]

    rng = np.random.default_rng(seed)
    null = np.empty(int(n_perm), dtype=np.float64)
    for p in range(int(n_perm)):
        y_perm = y.copy()
        for g in np.unique(groups):
            in_g = np.flatnonzero(groups == g)
            u = units[in_g]
            uniq_u = np.unique(u)
            if len(uniq_u) < 2:
                continue
            # One label per unit, shuffled among the units of this slide.
            lab = np.array([y[in_g[u == uu]][0] for uu in uniq_u])
            lab = lab[rng.permutation(len(lab))]
            for uu, new in zip(uniq_u, lab):
                y_perm[in_g[u == uu]] = new
        null[p] = cross_validate(x, y_perm, groups, n_classes, fit_predict)["balanced_accuracy"]

    good = null[np.isfinite(null)]
    n_ge = int((good >= observed).sum())
    return {
        "observed": observed,
        "null": good,
        "null_mean": float(good.mean()) if good.size else float("nan"),
        "p": (1.0 + n_ge) / (1.0 + good.size) if good.size else float("nan"),
        "n_perm": int(good.size),
    }


def leakage_gap(x, y, groups, n_classes, fit_predict, n_splits=5, seed=0):
    """Grouped score minus random-window score, on the same model and data.

    The difference is the optimism a random split would have reported. It is
    the single most persuasive number in the validation report, and it costs
    one extra cross-validation run.
    """
    grouped = cross_validate(x, y, groups, n_classes, fit_predict)
    leaky_folds = wsplits.random_window_split(len(y), n_splits=n_splits, seed=seed)
    leaky = cross_validate(x, y, groups, n_classes, fit_predict, folds=leaky_folds)
    return {
        "grouped_balanced_accuracy": grouped["balanced_accuracy"],
        "random_split_balanced_accuracy": leaky["balanced_accuracy"],
        "gap": leaky["balanced_accuracy"] - grouped["balanced_accuracy"],
        "note": "the random-split figure LEAKS and is reported only to quantify that optimism",
    }


def majority_baseline(y, groups, n_classes):
    """Always predict the training folds' most common class."""

    def fp(x_tr, y_tr, x_te):
        counts = np.bincount(y_tr, minlength=n_classes)
        return np.full(len(x_te), int(np.argmax(counts)), dtype=np.int64)

    return fp


def stratified_baseline(n_classes, seed=0):
    """Guess at random, in proportion to the training folds' class mix."""
    rng = np.random.default_rng(seed)

    def fp(x_tr, y_tr, x_te):
        counts = np.bincount(y_tr, minlength=n_classes).astype(np.float64)
        total = counts.sum()
        p = counts / total if total > 0 else np.full(n_classes, 1.0 / n_classes)
        return rng.choice(n_classes, size=len(x_te), p=p).astype(np.int64)

    return fp


def best_single_feature_baseline(n_classes, feature_names=None):
    """A threshold on one feature, chosen inside the training folds.

    If the 30-feature model cannot beat this by more than the width of its own
    confidence interval, it is not earning its complexity, and the honest
    output is to say so and offer the one-line rule instead.

    The chosen feature and threshold are recorded on the returned callable as
    ``.chosen`` so the report can name them.
    """
    state = {"feature": None, "threshold": None, "name": None}

    def fp(x_tr, y_tr, x_te):
        best = (-1.0, 0, 0.0, 0, 0)
        for j in range(x_tr.shape[1]):
            col = x_tr[:, j]
            finite = np.isfinite(col)
            if finite.sum() < 2:
                continue
            vals = np.unique(np.quantile(col[finite], np.linspace(0.05, 0.95, 19)))
            for t in vals:
                below = col <= t
                for lo in range(n_classes):
                    for hi in range(n_classes):
                        if lo == hi:
                            continue
                        pred = np.where(below, lo, hi)
                        score = balanced_accuracy(confusion_counts(y_tr, pred, n_classes))
                        if score > best[0]:
                            best = (score, j, float(t), lo, hi)
        _score, j, t, lo, hi = best
        state["feature"] = j
        state["threshold"] = t
        state["name"] = feature_names[j] if feature_names is not None and j < len(feature_names) else f"feature[{j}]"
        col = x_te[:, j]
        return np.where(np.isfinite(col) & (col <= t), lo, hi).astype(np.int64)

    fp.chosen = state
    return fp


def permutation_importance(x, y, groups, n_classes, fit_predict, feature_names=None, n_repeats=5, seed=0):
    """Out-of-fold permutation importance: the drop when a feature is shuffled.

    Measured on held-out slides, not on the training data. Impurity-based
    importance is computed on the data the model was fitted to, is biased
    toward continuous features, and gives pure noise a non-zero score. The
    decisive problem on this feature set is collinearity: with two columns
    carrying the same information a tree splits arbitrarily between them, so
    each reads as about half as important as the pair really is and an
    unrelated feature can appear to outrank the most important measurement in
    the model.

    Permutation importance has the mirror-image failure, and the caller has to
    know it: shuffling one of two redundant features leaves the model a
    perfect substitute, so both read as unimportant. The fix is to permute
    correlated features jointly, which belongs with the rest of the
    explainability work; this is the single-feature version, and it is honest
    only when the duplicate-column check in wsplits has already run.

    Args:
        x, y, groups:  as for :func:`cross_validate`.
        n_classes:     number of classes.
        fit_predict:   ``f(x_train, y_train, x_test) -> y_pred``.
        feature_names: names in column order, for the returned records.
        n_repeats:     shuffles per feature per fold.
        seed:          shuffle seed.

    Returns:
        list of dicts sorted by importance, each with ``feature``, ``index``,
        ``importance`` (mean drop in balanced accuracy) and ``sd``.
    """
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.int64)
    groups = np.asarray(groups)
    folds = wsplits.leave_one_group_out(groups)
    rng = np.random.default_rng(seed)
    n_feat = x.shape[1]

    drops = [[] for _ in range(n_feat)]
    for train_idx, test_idx in folds:
        if len(train_idx) == 0 or len(test_idx) == 0:
            continue
        x_tr, y_tr = x[train_idx], y[train_idx]
        x_te, y_te = x[test_idx], y[test_idx]
        base = balanced_accuracy(
            confusion_counts(y_te, np.asarray(fit_predict(x_tr, y_tr, x_te), dtype=np.int64), n_classes)
        )
        for j in range(n_feat):
            for _ in range(int(n_repeats)):
                shuffled = x_te.copy()
                shuffled[:, j] = shuffled[rng.permutation(len(shuffled)), j]
                score = balanced_accuracy(
                    confusion_counts(
                        y_te, np.asarray(fit_predict(x_tr, y_tr, shuffled), dtype=np.int64), n_classes
                    )
                )
                drops[j].append(base - score)

    out = []
    for j in range(n_feat):
        d = np.asarray(drops[j], dtype=np.float64)
        d = d[np.isfinite(d)]
        out.append(
            {
                "feature": feature_names[j] if feature_names is not None and j < len(feature_names) else f"feature[{j}]",
                "index": j,
                "importance": float(d.mean()) if d.size else float("nan"),
                "sd": float(d.std()) if d.size else float("nan"),
            }
        )
    out.sort(key=lambda r: (-(r["importance"] if r["importance"] == r["importance"] else -np.inf)))
    return out
