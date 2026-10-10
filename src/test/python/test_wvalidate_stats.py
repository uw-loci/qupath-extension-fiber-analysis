"""
The validity layer, tested against synthetic tables with no images at all.

Every test here builds a feature table whose answer is known by construction:
a signal case where the classes genuinely differ, a null case where they do
not, and a leak case where the only signal is slide identity. That makes the
splitters, the cluster bootstrap, the permutation null and the viability gate
testable in milliseconds in CI, with no QuPath, no phantom rendering and no
Python environment to install.

It is worth being precise about why the leak case matters. A classifier on
overlapping windows can score beautifully while having learned nothing about
collagen, because windows from the same slide resemble each other. The
machinery in `wvalidate` exists to catch exactly that, so the tests must
include a dataset where it is the only thing to find.
"""

import os
import sys

import numpy as np
import pytest

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis")
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import wsplits, wvalidate  # noqa: E402


# ---- fixtures ---------------------------------------------------------------


def nearest_centroid():
    """A trivial estimator, so the statistics are tested and not a model."""

    def fit_predict(x_tr, y_tr, x_te):
        classes = np.unique(y_tr)
        cents = np.stack([np.nanmean(x_tr[y_tr == c], axis=0) for c in classes])
        cents = np.nan_to_num(cents)
        xt = np.nan_to_num(x_te)
        d = ((xt[:, None, :] - cents[None, :, :]) ** 2).sum(axis=2)
        return classes[np.argmin(d, axis=1)]

    return fit_predict


def nearest_neighbour():
    """1-NN. Memorises, which is exactly what exploits a leak.

    A pooled-centroid estimator cannot: when one class is spread over several
    slides at very different offsets, its centroid sits near none of them. A
    forest memorises much like 1-NN does, so this is the honest stand-in when
    testing whether a split scheme blocks leakage.
    """

    def fit_predict(x_tr, y_tr, x_te):
        a = np.nan_to_num(x_te)
        b = np.nan_to_num(x_tr)
        d = ((a[:, None, :] - b[None, :, :]) ** 2).sum(axis=2)
        return y_tr[np.argmin(d, axis=1)]

    return fit_predict


def make_signal(n_slides=8, per_slide=42, seed=0, effect=3.0, ann_per_class=3):
    """Two classes that really differ, on every slide.

    `ann_per_class` matters for the permutation null: labels are permuted
    whole-annotation within a slide, so two annotations per slide gives only
    two possible permutations and a null too coarse to resolve a small p.
    """
    rng = np.random.default_rng(seed)
    xs, ys, gs, anns = [], [], [], []
    ann = 0
    k = max(1, per_slide // (2 * ann_per_class))
    for s in range(n_slides):
        for cls in (0, 1):
            for _ in range(ann_per_class):
                xs.append(rng.normal(effect * cls, 1.0, size=(k, 3)))
                ys.append(np.full(k, cls))
                gs.append(np.full(k, s))
                anns.append(np.full(k, ann))
                ann += 1
    return (
        np.vstack(xs),
        np.concatenate(ys),
        np.concatenate(gs),
        np.concatenate(anns),
    )


def make_heterogeneous(seed=3, per_slide=60):
    """Slides that separate to very different degrees.

    The realistic case, and the only one in which a cluster bootstrap can show
    its value: if every slide is drawn from one distribution there is no
    between-slide variance to account for, and resampling slides is not wider
    than resampling windows.
    """
    rng = np.random.default_rng(seed)
    effects = [0.1, 0.3, 0.6, 1.2, 3.0, 6.0]
    xs, ys, gs, anns = [], [], [], []
    for s, eff in enumerate(effects):
        for cls in (0, 1):
            k = per_slide // 2
            xs.append(rng.normal(eff * cls, 1.0, size=(k, 3)))
            ys.append(np.full(k, cls))
            gs.append(np.full(k, s))
            anns.append(np.full(k, 2 * s + cls))
    return np.vstack(xs), np.concatenate(ys), np.concatenate(gs), np.concatenate(anns)


def make_null(n_slides=8, per_slide=40, seed=1):
    """Labels assigned at random; the features know nothing about them."""
    x, y, g, a = make_signal(n_slides, per_slide, seed, effect=0.0)
    return x, y, g, a


def make_slide_leak(n_slides=8, per_slide=40, seed=2):
    """The only signal is slide identity, and class is confounded with it.

    Each slide carries one class and a slide-specific feature offset. A random
    window split scores perfectly; a slide-grouped split must not, because a
    held-out slide's offset was never seen.
    """
    rng = np.random.default_rng(seed)
    xs, ys, gs, anns = [], [], [], []
    for s in range(n_slides):
        cls = s % 2
        offset = rng.normal(0, 10.0, size=3)
        feat = offset + rng.normal(0, 0.05, size=(per_slide, 3))
        xs.append(feat)
        ys.append(np.full(per_slide, cls))
        gs.append(np.full(per_slide, s))
        anns.append(np.full(per_slide, s))
    return np.vstack(xs), np.concatenate(ys), np.concatenate(gs), np.concatenate(anns)


# ---- splitters --------------------------------------------------------------


def test_leave_one_group_out_holds_out_whole_slides():
    _x, _y, g, _a = make_signal(n_slides=5)
    folds = wsplits.leave_one_group_out(g)
    assert len(folds) == 5
    for train, test in folds:
        assert len(np.intersect1d(train, test)) == 0
        # The decisive property: no slide may appear on both sides.
        assert len(np.intersect1d(np.unique(g[train]), np.unique(g[test]))) == 0
    # Every example is tested exactly once.
    assert sorted(np.concatenate([t for _, t in folds])) == list(range(len(g)))


def test_grouped_k_fold_never_splits_a_group():
    _x, _y, g, _a = make_signal(n_slides=9, per_slide=20)
    folds = wsplits.grouped_k_fold(g, n_splits=3, seed=0)
    assert len(folds) == 3
    for train, test in folds:
        assert len(np.intersect1d(np.unique(g[train]), np.unique(g[test]))) == 0
    assert sorted(np.concatenate([t for _, t in folds])) == list(range(len(g)))


def test_grouped_k_fold_clamps_to_the_number_of_groups():
    _x, _y, g, _a = make_signal(n_slides=3, per_slide=10)
    assert len(wsplits.grouped_k_fold(g, n_splits=10)) == 3


# ---- the leak this all exists to catch --------------------------------------


def test_grouped_split_refuses_the_slide_confound_that_a_random_split_rewards():
    x, y, g, _a = make_slide_leak()
    fp = nearest_neighbour()
    grouped = wvalidate.cross_validate(x, y, g, 2, fp)
    leaky = wvalidate.cross_validate(
        x, y, g, 2, fp, folds=wsplits.random_window_split(len(y), n_splits=5, seed=0)
    )
    assert leaky["balanced_accuracy"] > 0.95, "the fixture does not actually leak"
    assert grouped["balanced_accuracy"] < 0.75, (
        f"slide-grouped CV scored {grouped['balanced_accuracy']:.2f} on data whose only signal is "
        "slide identity; the grouping is not working"
    )


def test_leakage_gap_is_positive_on_leaky_data_and_small_on_honest_data():
    fp = nearest_neighbour()
    x, y, g, _a = make_slide_leak()
    leaky = wvalidate.leakage_gap(x, y, g, 2, fp)
    assert leaky["gap"] > 0.2

    x, y, g, _a = make_signal()
    honest = wvalidate.leakage_gap(x, y, g, 2, nearest_centroid())
    assert abs(honest["gap"]) < 0.1, (
        "a genuinely generalising signal should score about the same either way"
    )


# ---- cross-validation reporting ---------------------------------------------


def test_cross_validate_reports_every_held_out_slide_separately():
    x, y, g, _a = make_signal(n_slides=6)
    out = wvalidate.cross_validate(x, y, g, 2, nearest_centroid())
    assert len(out["per_group"]) == 6
    assert out["n_groups"] == 6
    assert all(r["n"] > 0 for r in out["per_group"])
    assert out["balanced_accuracy"] > 0.9
    assert out["scored"].all()


def test_balanced_accuracy_is_not_fooled_by_imbalance():
    # 95 of class 0, 5 of class 1, predict all zeros.
    counts = np.zeros((2, 2), dtype=np.int64)
    counts[0, 0] = 95
    counts[1, 0] = 5
    assert wvalidate.accuracy(counts) == pytest.approx(0.95)
    assert wvalidate.balanced_accuracy(counts) == pytest.approx(0.5)


# ---- uncertainty ------------------------------------------------------------


def test_cluster_bootstrap_is_wider_than_resampling_windows():
    # The whole reason for the cluster bootstrap. Resampling windows treats
    # near-duplicate windows from one slide as independent, so the interval
    # ignores the variation that actually matters: how differently the model
    # does from one slide to the next. The fixture has to contain that
    # variation, or there is nothing for the cluster bootstrap to find -- on
    # slides drawn from one distribution it is legitimately the narrower of
    # the two.
    x, y, g, _a = make_heterogeneous()
    out = wvalidate.cross_validate(x, y, g, 2, nearest_centroid())
    accs = [r["balanced_accuracy"] for r in out["per_group"]]
    assert np.std(accs) > 0.1, "fixture is not heterogeneous enough to test this"
    yp = out["y_pred"]
    clustered = wvalidate.cluster_bootstrap(y, yp, g, 2, n_boot=400, seed=0)

    rng = np.random.default_rng(0)
    naive = []
    for _ in range(400):
        sel = rng.integers(0, len(y), len(y))
        naive.append(wvalidate.balanced_accuracy(wvalidate.confusion_counts(y[sel], yp[sel], 2)))
    naive_width = float(np.percentile(naive, 97.5) - np.percentile(naive, 2.5))
    clustered_width = clustered["hi"] - clustered["lo"]

    assert clustered_width > naive_width, (
        f"cluster bootstrap width {clustered_width:.4f} is not wider than the naive "
        f"window bootstrap {naive_width:.4f}; the grouping is being ignored"
    )
    assert clustered["lo"] <= clustered["point"] <= clustered["hi"]


# ---- the permutation null ----------------------------------------------------


def test_permutation_null_rejects_real_signal_and_spares_noise():
    fp = nearest_centroid()

    x, y, g, a = make_signal(n_slides=6, per_slide=30)
    sig = wvalidate.permutation_null(x, y, g, 2, fp, annotations=a, n_perm=60, seed=0)
    assert sig["p"] < 0.05, f"real signal was not significant (p={sig['p']:.3f})"
    assert sig["observed"] > sig["null_mean"]

    x, y, g, a = make_null(n_slides=6, per_slide=30)
    noise = wvalidate.permutation_null(x, y, g, 2, fp, annotations=a, n_perm=60, seed=0)
    assert noise["p"] > 0.05, f"pure noise was called significant (p={noise['p']:.3f})"


def test_permutation_p_can_never_be_exactly_zero():
    x, y, g, a = make_signal(n_slides=5, per_slide=30, effect=20.0)
    out = wvalidate.permutation_null(x, y, g, 2, nearest_centroid(), annotations=a, n_perm=20, seed=0)
    assert out["p"] > 0.0
    assert out["p"] >= 1.0 / (1.0 + out["n_perm"])


# ---- baselines ---------------------------------------------------------------


def test_majority_baseline_scores_half_on_two_balanced_classes():
    x, y, g, _a = make_signal(n_slides=4)
    out = wvalidate.cross_validate(x, y, g, 2, wvalidate.majority_baseline(y, g, 2))
    assert out["balanced_accuracy"] == pytest.approx(0.5, abs=1e-9)


def test_single_feature_baseline_finds_the_one_informative_feature():
    rng = np.random.default_rng(0)
    n = 240
    g = np.repeat(np.arange(6), n // 6)
    y = (np.arange(n) % 2).astype(np.int64)
    x = rng.normal(0, 1, size=(n, 4))
    x[:, 2] += 6.0 * y  # only column 2 knows anything
    fp = wvalidate.best_single_feature_baseline(2, feature_names=["a", "b", "signal", "d"])
    out = wvalidate.cross_validate(x, y, g, 2, fp)
    assert out["balanced_accuracy"] > 0.9
    assert fp.chosen["name"] == "signal"


# ---- the viability gate ------------------------------------------------------


def test_viability_blocks_when_there_are_too_few_slides():
    x, y, g, a = make_signal(n_slides=2, per_slide=60)
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2"], ["A", "B"], annotations=a)
    assert rep.verdict == wsplits.BLOCK
    assert any("grouped cross-validation needs at least" in b for b in rep.blocks)
    assert "2 slide(s)" in rep.summary()


def test_viability_blocks_a_class_confined_to_one_slide():
    x, y, g, a = make_signal(n_slides=6, per_slide=40)
    y = y.copy()
    g = g.copy()
    y[g != 0] = 0  # class B now exists only on slide 0
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2"], ["A", "B"], annotations=a)
    assert rep.verdict == wsplits.BLOCK
    assert any("appears on 1 slide(s)" in b for b in rep.blocks)


def test_viability_names_the_duplicate_column_rather_than_dropping_it_silently():
    # hdm is exactly fiber_coverage_percent/100 in every sidecar written to
    # date, and a model splits arbitrarily between the two.
    x, y, g, a = make_signal(n_slides=6)
    x = np.column_stack([x, x[:, 0] / 100.0])
    names = ["coverage", "f1", "f2", "hdm"]
    rep = wsplits.check_viability(x, y, g, names, ["A", "B"], annotations=a)
    assert "hdm" in rep.excluded_features
    assert "coverage" in rep.excluded_features["hdm"]
    assert "hdm" in rep.summary()


def test_viability_excludes_a_mostly_missing_feature_instead_of_imputing_it():
    # tortuosity_median was null in 7,891 of 7,935 scored windows on the real
    # MH_Colon run. Imputing that would produce a confident number from almost
    # no data.
    x, y, g, a = make_signal(n_slides=6)
    col = np.full(len(y), np.nan)
    col[:20] = 1.0
    x = np.column_stack([x, col])
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2", "sparse"], ["A", "B"], annotations=a)
    assert "sparse" in rep.excluded_features
    assert "missing in" in rep.excluded_features["sparse"]


def test_viability_excludes_a_constant_feature():
    x, y, g, a = make_signal(n_slides=6)
    x = np.column_stack([x, np.full(len(y), 7.0)])
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2", "flat"], ["A", "B"], annotations=a)
    assert "flat" in rep.excluded_features
    assert "constant" in rep.excluded_features["flat"]


def test_viability_warns_on_severe_imbalance_without_blocking():
    x, y, g, a = make_signal(n_slides=10, per_slide=180)
    y = y.copy()
    # Keep class B above the per-class minimum, but spread across slides --
    # take every Nth so it is not confined to one, which would block instead.
    minority = np.flatnonzero(y == 1)
    keep = minority[:: max(1, len(minority) // 40)][:40]
    y[:] = 0
    y[keep] = 1
    assert len(np.unique(g[y == 1])) >= wsplits.MIN_GROUPS_PER_CLASS
    ratio = (y == 0).sum() / (y == 1).sum()
    assert ratio > wsplits.MAX_IMBALANCE_RATIO, f"fixture ratio {ratio:.1f} is below the threshold"
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2"], ["A", "B"], annotations=a)
    assert any("imbalanced" in w for w in rep.warnings)
    assert not any("imbalanc" in b for b in rep.blocks), "imbalance should warn, not block"


def test_viability_passes_clean_data_and_says_so():
    x, y, g, a = make_signal(n_slides=8, per_slide=40)
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2"], ["A", "B"], annotations=a)
    assert rep.verdict == wsplits.OK
    assert "Nothing to report" in rep.summary()


def test_every_report_names_a_number_and_a_remedy():
    # A refusal that says only "insufficient data" is not actionable.
    x, y, g, a = make_signal(n_slides=2, per_slide=10)
    rep = wsplits.check_viability(x, y, g, ["f0", "f1", "f2"], ["A", "B"], annotations=a)
    assert rep.blocks
    for b in rep.blocks:
        assert any(ch.isdigit() for ch in b), f"block message states no number: {b}"
        assert len(b) > 60, f"block message is too terse to act on: {b}"
