"""
Grouped cross-validation splits and the pre-training viability check.

Overlapping windows are not independent observations. At 75% overlap a window
shares most of its pixels with its neighbour; every window inside one
annotation shares a single labelling decision by one person about one
structure; and every field on one slide shares section thickness, staining
batch and illumination. A random split over windows therefore reports a number
that means nothing, and it reports a high one.

So the split unit here is the SLIDE, and a random split is not offered. The
only claim anyone will make from this model is "it works on a slide it has not
seen", and slide-level grouping is the only scheme that estimates it.

Deliberately numpy-only, no scikit-learn. These splitters are a few lines each,
and keeping the validity layer dependency-free means it runs in CI in
milliseconds against a synthetic table with no images and no Python
environment to install. scikit-learn arrives with the model itself, later.
"""
import logging

import numpy as np

logger = logging.getLogger("fiber.wsplits")

#: Verdicts from `check_viability`, worst first.
BLOCK = "BLOCK"
WARN = "WARN"
OK = "OK"

#: A class needs this many windows before it can be modelled at all.
MIN_WINDOWS_PER_CLASS = 30
#: ...and this many separate annotations, or it describes one hand-drawn region.
MIN_ANNOTATIONS_PER_CLASS = 3
#: ...and must appear on this many slides, or it cannot be validated at slide level.
MIN_GROUPS_PER_CLASS = 2
#: Fewer groups than this and grouped cross-validation is not possible.
MIN_GROUPS = 3
#: Above this NaN rate a feature is excluded rather than imputed.
MAX_NAN_FRACTION = 0.2
#: Above this ratio the headline metric is forced to balanced accuracy.
MAX_IMBALANCE_RATIO = 20.0


def leave_one_group_out(groups):
    """Yield ``(train_idx, test_idx)`` holding out one group at a time.

    Args:
        groups: (n,) array of group ids, one per example.

    Returns:
        list of index-array pairs, one fold per distinct group, in sorted
        group order so a run is reproducible.
    """
    groups = np.asarray(groups)
    folds = []
    for g in np.unique(groups):
        test = np.flatnonzero(groups == g)
        train = np.flatnonzero(groups != g)
        folds.append((train, test))
    return folds


def grouped_k_fold(groups, n_splits=5, seed=0):
    """Yield ``(train_idx, test_idx)`` with every group wholly in one fold.

    Groups are assigned largest-first to whichever fold currently holds the
    fewest examples, which keeps fold sizes close without ever splitting a
    group. Used for the inner loop of nested model selection.

    Args:
        groups:    (n,) array of group ids.
        n_splits:  number of folds; clamped to the number of distinct groups.
        seed:      tie-break seed, so equal-sized groups are assigned stably.

    Returns:
        list of index-array pairs.
    """
    groups = np.asarray(groups)
    uniq, counts = np.unique(groups, return_counts=True)
    n_splits = int(max(2, min(n_splits, len(uniq))))
    rng = np.random.default_rng(seed)
    order = np.lexsort((rng.random(len(uniq)), -counts))

    fold_of = {}
    load = np.zeros(n_splits, dtype=np.int64)
    for i in order:
        f = int(np.argmin(load))
        fold_of[uniq[i]] = f
        load[f] += counts[i]

    assign = np.array([fold_of[g] for g in groups])
    return [(np.flatnonzero(assign != f), np.flatnonzero(assign == f)) for f in range(n_splits)]


def random_window_split(n, n_splits=5, seed=0):
    """Ungrouped k-fold over individual windows. **Leaks. Diagnostic only.**

    This exists for exactly one purpose: running it beside the grouped scheme
    produces the leakage gap, which is the most persuasive single number in
    the validation report -- it states, in the units of the metric itself, the
    optimism a random split would have reported on this data.

    Never use it to report performance. Anything derived from it must be
    stamped as such.
    """
    rng = np.random.default_rng(seed)
    perm = rng.permutation(int(n))
    fold = np.empty(int(n), dtype=np.int64)
    fold[perm] = np.arange(int(n)) % int(n_splits)
    return [(np.flatnonzero(fold != f), np.flatnonzero(fold == f)) for f in range(int(n_splits))]


class ViabilityReport:
    """Whether this training set can support a model, and what is wrong.

    Every finding names the number, the threshold it failed, and what to do.
    "Insufficient data" on its own tells a pathologist nothing actionable.
    """

    def __init__(self):
        self.blocks = []
        self.warnings = []
        self.excluded_features = {}
        self.dropped_classes = []

    @property
    def verdict(self):
        if self.blocks:
            return BLOCK
        return WARN if (self.warnings or self.excluded_features) else OK

    def block(self, msg):
        self.blocks.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)

    def exclude(self, feature, reason):
        self.excluded_features[feature] = reason

    def to_dict(self):
        return {
            "verdict": self.verdict,
            "blocks": list(self.blocks),
            "warnings": list(self.warnings),
            "excluded_features": dict(self.excluded_features),
            "dropped_classes": list(self.dropped_classes),
        }

    def summary(self):
        """@return a human-readable multi-line report, always non-empty."""
        lines = [f"Verdict: {self.verdict}"]
        for b in self.blocks:
            lines.append(f"  BLOCK: {b}")
        for w in self.warnings:
            lines.append(f"  WARN:  {w}")
        for f, r in sorted(self.excluded_features.items()):
            lines.append(f"  EXCLUDED {f}: {r}")
        if self.verdict == OK:
            lines.append("  Nothing to report.")
        return "\n".join(lines)


def check_viability(x, y, groups, feature_names, class_names, annotations=None):
    """Decide whether a model may be fitted at all, before fitting one.

    Args:
        x:             (n, d) feature matrix, NaN allowed.
        y:             (n,) integer class index per example.
        groups:        (n,) slide id per example -- the cross-validation unit.
        feature_names: d names, in column order.
        class_names:   names by class index.
        annotations:   optional (n,) annotation id per example, so a class
                       drawn as one enormous region can be distinguished from
                       one drawn as several.

    Returns:
        a :class:`ViabilityReport`.
    """
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y)
    groups = np.asarray(groups)
    rep = ViabilityReport()

    n_groups = len(np.unique(groups))
    if n_groups < MIN_GROUPS:
        rep.block(
            f"only {n_groups} slide(s) in the training set; grouped cross-validation needs at least "
            f"{MIN_GROUPS}. Annotate regions on more slides -- adding more regions to the same slide "
            f"will not help, because they are not independent."
        )

    for ci, cname in enumerate(class_names):
        sel = y == ci
        n_win = int(sel.sum())
        n_grp = len(np.unique(groups[sel])) if n_win else 0
        n_ann = len(np.unique(annotations[sel])) if (annotations is not None and n_win) else None

        if n_win < MIN_WINDOWS_PER_CLASS:
            rep.block(
                f"class '{cname}' has {n_win} labelled window(s), below the minimum of "
                f"{MIN_WINDOWS_PER_CLASS}. Draw more or larger regions for it, or drop the class."
            )
        if n_grp < MIN_GROUPS_PER_CLASS:
            rep.block(
                f"class '{cname}' appears on {n_grp} slide(s), below the minimum of "
                f"{MIN_GROUPS_PER_CLASS}. It cannot be validated at slide level: every fold that "
                f"held out its only slide would have no examples of it to test."
            )
        if n_ann is not None and n_ann < MIN_ANNOTATIONS_PER_CLASS:
            rep.warn(
                f"class '{cname}' comes from {n_ann} annotation(s), below {MIN_ANNOTATIONS_PER_CLASS}. "
                f"Its windows mostly describe one hand-drawn region, so the model may learn that "
                f"region rather than the class."
            )

    counts = np.array([int((y == ci).sum()) for ci in range(len(class_names))], dtype=np.float64)
    live = counts[counts > 0]
    if live.size >= 2:
        ratio = float(live.max() / live.min())
        if ratio > MAX_IMBALANCE_RATIO:
            rep.warn(
                f"class counts are imbalanced {ratio:.0f}:1 (largest {int(live.max())}, smallest "
                f"{int(live.min())}). Accuracy will look good by predicting the majority; the headline "
                f"metric is balanced accuracy for that reason."
            )
    if live.size < 2:
        rep.block("fewer than two classes have any labelled windows; there is nothing to separate.")

    _check_features(x, feature_names, rep)
    return rep


def _check_features(x, feature_names, rep):
    """Exclude features that cannot inform a model, naming each one."""
    n = x.shape[0]
    if n == 0:
        rep.block("no labelled windows at all.")
        return

    seen = {}
    for j, name in enumerate(feature_names):
        col = x[:, j]
        finite = np.isfinite(col)
        nan_frac = 1.0 - (finite.sum() / float(n))

        if nan_frac >= 1.0:
            rep.exclude(name, "every value is missing")
            continue
        if nan_frac > MAX_NAN_FRACTION:
            # Never impute silently. On the MH_Colon run tortuosity_median was
            # null in 7,891 of 7,935 scored windows; imputing it would have
            # produced a confident number from almost no data.
            rep.exclude(name, f"missing in {100 * nan_frac:.1f}% of windows (limit {100 * MAX_NAN_FRACTION:.0f}%)")
            continue
        vals = col[finite]
        if vals.size and np.ptp(vals) == 0:
            rep.exclude(name, f"constant at {vals[0]:.6g}")
            continue

        # Exact duplicates. hdm is fiber_coverage_percent/100 in every sidecar
        # written to date, and a model splits arbitrarily between the two.
        key = _column_key(col)
        if key in seen:
            rep.exclude(
                name,
                f"carries the same information as '{seen[key]}'; the model's choice between them "
                f"would be arbitrary, and both would read as half as important as the pair is",
            )
            continue
        seen[key] = name


def _column_key(col):
    """A hash that matches for columns identical up to an affine rescale."""
    finite = np.isfinite(col)
    if not finite.any():
        return None
    v = col[finite].astype(np.float64)
    spread = np.ptp(v)
    if spread == 0:
        return None
    v = (v - v.min()) / spread
    return (finite.tobytes(), np.round(v, 12).tobytes())
