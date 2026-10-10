"""
The model layer: what it learns, what it refuses, and what it admits to.

Most of these tests are about refusal rather than accuracy. A classifier that
scores well is easy; one that declines to score data it cannot honestly score
is the thing that makes a probability map safe to put in front of a
pathologist.
"""

import json
import os
import sys

import numpy as np
import pytest

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis")
)
sys.path.insert(0, PKG_ROOT)

pytest.importorskip("sklearn", reason="scikit-learn is only in the Appose env and CI")

from fiberlib import wmodel, wvalidate  # noqa: E402

KINDS = ["hist_gradient_boosting", "random_forest"]


def make_data(n_slides=6, per_slide=40, seed=0, effect=3.0, n_feat=4):
    rng = np.random.default_rng(seed)
    xs, ys, gs = [], [], []
    for s in range(n_slides):
        for cls in (0, 1):
            k = per_slide // 2
            f = rng.normal(0, 1, size=(k, n_feat))
            f[:, 0] += effect * cls
            xs.append(f)
            ys.append(np.full(k, cls))
            gs.append(np.full(k, s))
    return np.vstack(xs), np.concatenate(ys), np.concatenate(gs)


FEATURES = ["signal", "noise1", "noise2", "noise3"]
GEOMETRY = {"window_um": 100.0, "pixel_size_um": 0.3464, "stride_px": 144, "segmentation_hash": "abc123"}


# ---- it learns -------------------------------------------------------------


@pytest.mark.parametrize("kind", KINDS)
def test_both_estimators_learn_a_real_signal_under_grouped_cv(kind):
    x, y, g = make_data()
    fp = wmodel.fit_predict_factory(kind, n_classes=2, seed=0)
    out = wvalidate.cross_validate(x, y, g, 2, fp)
    assert out["balanced_accuracy"] > 0.9


@pytest.mark.parametrize("kind", KINDS)
def test_probabilities_are_indexed_by_global_class_not_fold_class(kind):
    # A training fold missing a class would otherwise shift every column
    # after it, attributing probabilities to the wrong class with nothing
    # visible to show for it.
    x, y, g = make_data(n_slides=4, per_slide=40)
    y = y.copy()
    y[g == 0] = 0  # slide 0 has only class 0
    fp = wmodel.fit_predict_proba_factory(kind, n_classes=3, seed=0)
    proba = fp(x[g != 3], y[g != 3], x[g == 3])
    assert proba.shape == (int((g == 3).sum()), 3)
    # Class 2 never appears in training, so its column must be all zero and
    # the others must still sum to one.
    assert np.allclose(proba[:, 2], 0.0)
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-9)


def test_hist_gradient_boosting_handles_nan_without_imputation():
    # tortuosity_median was null in 7,891 of 7,935 real windows, so "this
    # measurement is undefined here" has to be a value the model can use.
    x, y, g = make_data()
    x = x.copy()
    x[::3, 1] = np.nan
    ready, imp = wmodel.prepare_matrix(x, "hist_gradient_boosting")
    assert imp is None
    assert ready.shape == x.shape
    assert np.isnan(ready).any(), "NaN must survive to the estimator, not be filled"
    fp = wmodel.fit_predict_factory("hist_gradient_boosting", n_classes=2)
    # Bar set below the clean-data case on purpose: a third of one column is
    # missing here, so some loss is expected and the claim under test is that
    # it still learns, not that NaN costs nothing. It lands at 0.900, which a
    # strict > 0.9 would have failed on a rounding edge.
    assert wvalidate.cross_validate(x, y, g, 2, fp)["balanced_accuracy"] >= 0.85


def test_random_forest_imputation_is_visible_not_silent():
    x, _y, _g = make_data()
    x = x.copy()
    x[::3, 1] = np.nan
    ready, imp = wmodel.prepare_matrix(x, "random_forest")
    assert imp is not None and len(imp) == x.shape[1]
    # Original columns plus one missingness indicator each.
    assert ready.shape == (x.shape[0], 2 * x.shape[1])
    assert np.isfinite(ready).all()
    assert ready[::3, x.shape[1] + 1].all(), "the indicator must mark the rows that were filled"
    assert wmodel.indicator_names(FEATURES)[1] == "noise1_isnan"


def test_imputation_values_come_from_training_only():
    # Recomputing the median on the test fold would leak its distribution
    # into the prediction.
    x_tr, _y, _g = make_data(seed=1)
    x_te, _y2, _g2 = make_data(seed=2)
    x_te = x_te + 100.0
    _ready_tr, imp = wmodel.prepare_matrix(x_tr, "random_forest")
    ready_te, imp_te = wmodel.prepare_matrix(x_te, "random_forest", impute_values=imp)
    assert np.allclose(imp_te, imp)
    assert ready_te.shape[1] == 2 * x_te.shape[1]


# ---- calibration -----------------------------------------------------------


def test_calibration_error_is_near_zero_on_honest_probabilities():
    rng = np.random.default_rng(0)
    p = rng.uniform(0.5, 1.0, size=4000)
    y = (rng.random(4000) < p).astype(np.int64)  # correct exactly p of the time
    proba = np.column_stack([1 - p, p])
    y_true = np.where(y == 1, 1, 0)
    out = wmodel.expected_calibration_error(proba, y_true, n_bins=10)
    assert out["ece"] < 0.05, f"honest probabilities scored ECE {out['ece']:.3f}"


def test_calibration_error_catches_overconfidence():
    rng = np.random.default_rng(0)
    # Claims 0.99 every time, right only 60% of the time.
    n = 2000
    proba = np.column_stack([np.full(n, 0.01), np.full(n, 0.99)])
    y_true = (rng.random(n) < 0.6).astype(np.int64)
    out = wmodel.expected_calibration_error(proba, y_true)
    assert out["ece"] > 0.3
    note = wmodel.calibration_note(out["ece"])
    assert "rank order only" in note


def test_calibration_note_always_mentions_the_training_prior():
    # The most likely way a probability map misleads: it is calibrated to the
    # class mix the pathologist happened to annotate, not to the tissue.
    note = wmodel.calibration_note(0.02)
    assert "TRAINING" in note or "training" in note


# ---- abstention ------------------------------------------------------------


def test_abstains_on_low_confidence_and_on_a_narrow_margin():
    proba = np.array([[0.9, 0.1], [0.55, 0.45], [0.2, 0.8]])
    assert wmodel.abstain_mask(proba, min_confidence=0.7).tolist() == [False, True, False]
    assert wmodel.abstain_mask(proba, min_margin=0.3).tolist() == [False, True, False]


def test_abstains_on_novelty_even_when_the_model_is_confident():
    # A closed-set softmax must put its mass somewhere, so it is confidently
    # wrong on tissue it has never seen. Novelty is the only signal that can
    # catch that, and it is not a posterior.
    proba = np.array([[0.99, 0.01], [0.99, 0.01]])
    novelty = np.array([0.5, 1e-9])
    assert wmodel.abstain_mask(proba, novelty_p=novelty, min_novelty_p=0.001).tolist() == [False, True]


def test_novelty_flags_a_point_far_outside_the_training_cloud():
    rng = np.random.default_rng(0)
    train = rng.normal(0, 1, size=(400, 3))
    query = np.vstack([rng.normal(0, 1, size=(5, 3)), np.full((1, 3), 25.0)])
    p = wmodel.novelty_scores(train, query)
    if np.isfinite(p).all():
        assert p[-1] < 0.001, "an obvious outlier was not flagged"
        assert (p[:-1] > 0.001).sum() >= 4


def test_novelty_declines_rather_than_guessing_from_too_few_rows():
    p = wmodel.novelty_scores(np.random.default_rng(0).normal(size=(4, 8)), np.zeros((3, 8)))
    assert np.isnan(p).all(), "a singular covariance must return NaN, not a confident number"


def test_risk_coverage_accuracy_improves_as_coverage_falls():
    rng = np.random.default_rng(0)
    n = 2000
    conf = rng.uniform(0.5, 1.0, n)
    correct = rng.random(n) < conf
    proba = np.column_stack([1 - conf, conf])
    y_true = np.where(correct, 1, 0)
    curve = wmodel.risk_coverage(proba, y_true)
    assert curve[0]["coverage"] == pytest.approx(1.0)
    assert curve[-1]["coverage"] < 0.1
    assert curve[-1]["accuracy"] > curve[0]["accuracy"]


def test_threshold_choice_reports_the_coverage_it_costs():
    rng = np.random.default_rng(0)
    n = 2000
    conf = rng.uniform(0.4, 1.0, n)
    proba = np.column_stack([1 - conf, conf])
    y_true = np.where(rng.random(n) < conf, 1, 0)
    out = wmodel.choose_abstention_thresholds(proba, y_true, target_accuracy=0.9)
    assert out["achieved"]
    assert 0.0 < out["coverage"] < 1.0, "a threshold without its coverage hides the price"
    assert out["accuracy"] >= 0.9


# ---- persistence and refusal ------------------------------------------------


def _train_and_save(tmp_path, kind="hist_gradient_boosting"):
    x, y, g = make_data()
    est = wmodel.build_estimator(kind, n_classes=2, seed=0)
    ready, imp = wmodel.prepare_matrix(x, kind)
    est.fit(ready, y)
    out_dir = str(tmp_path / "model")
    meta = wmodel.save_model(
        out_dir, est, FEATURES, ["A", "B"], GEOMETRY, kind=kind, impute_values=imp,
        training={"n_windows": int(len(y)), "n_slides": int(len(np.unique(g)))},
        calibration={"ece": 0.03}, verdict="OK",
    )
    return out_dir, meta


def test_round_trips_with_the_feature_order_and_geometry_intact(tmp_path):
    out_dir, meta = _train_and_save(tmp_path)
    assert meta["feature_names"] == FEATURES
    assert meta["geometry"]["pixel_size_um"] == pytest.approx(0.3464)
    est, loaded = wmodel.load_model(out_dir)
    assert loaded["class_names"] == ["A", "B"]
    assert loaded["verdict"] == "OK"
    assert hasattr(est, "predict")
    with open(os.path.join(out_dir, "metadata.json"), encoding="ascii") as fh:
        json.load(fh)  # must be plain ASCII JSON


def test_refuses_a_different_feature_set(tmp_path):
    _out, meta = _train_and_save(tmp_path)
    with pytest.raises(wmodel.ModelRefusal, match="feature set does not match"):
        wmodel.check_compatibility(meta, ["signal", "noise1", "noise2"], GEOMETRY)


def test_refuses_the_same_features_in_a_different_order(tmp_path):
    # A model scores columns by position, so a reorder reads one measurement
    # as another and nothing about the output would look wrong.
    _out, meta = _train_and_save(tmp_path)
    swapped = [FEATURES[1], FEATURES[0]] + FEATURES[2:]
    with pytest.raises(wmodel.ModelRefusal, match="different order"):
        wmodel.check_compatibility(meta, swapped, GEOMETRY)


def test_refuses_a_different_pixel_size_even_when_the_window_microns_match(tmp_path):
    # The subtle one. Lacunarity, gaps and texture use box sizes in PIXELS,
    # so a 100 um window means a different measurement at a different
    # resolution, under the same feature name.
    _out, meta = _train_and_save(tmp_path)
    other = dict(GEOMETRY, pixel_size_um=0.5)
    with pytest.raises(wmodel.ModelRefusal, match="pixel size"):
        wmodel.check_compatibility(meta, FEATURES, other)
    with pytest.raises(wmodel.ModelRefusal, match="box sizes in PIXELS"):
        wmodel.check_compatibility(meta, FEATURES, other)


def test_accepts_a_pixel_size_within_tolerance(tmp_path):
    _out, meta = _train_and_save(tmp_path)
    close = dict(GEOMETRY, pixel_size_um=0.3464 * 1.01)
    assert wmodel.check_compatibility(meta, FEATURES, close) == []


def test_refuses_different_segmentation_settings(tmp_path):
    _out, meta = _train_and_save(tmp_path)
    with pytest.raises(wmodel.ModelRefusal, match="segmentation settings differ"):
        wmodel.check_compatibility(meta, FEATURES, dict(GEOMETRY, segmentation_hash="deadbeef"))


def test_override_lets_geometry_through_but_records_it(tmp_path):
    _out, meta = _train_and_save(tmp_path)
    warn = wmodel.check_compatibility(
        meta, FEATURES, dict(GEOMETRY, pixel_size_um=0.5), allow_override=True
    )
    assert any(w.startswith("OVERRIDDEN:") for w in warn)


def test_override_never_lets_a_feature_mismatch_through(tmp_path):
    # Not a judgement call: scoring by position with the wrong columns is
    # meaningless, not merely uncertain.
    _out, meta = _train_and_save(tmp_path)
    with pytest.raises(wmodel.ModelRefusal):
        wmodel.check_compatibility(meta, FEATURES[:3], GEOMETRY, allow_override=True)


def test_refuses_a_newer_schema(tmp_path):
    out_dir, _meta = _train_and_save(tmp_path)
    path = os.path.join(out_dir, "metadata.json")
    with open(path, encoding="ascii") as fh:
        meta = json.load(fh)
    meta["schema_version"] = wmodel.SCHEMA_VERSION + 1
    with open(path, "w", encoding="ascii") as fh:
        json.dump(meta, fh)
    with pytest.raises(wmodel.ModelRefusal, match="schema version"):
        wmodel.load_model(out_dir)


def test_refuses_a_different_sklearn_version(tmp_path):
    # A joblib pickle is not portable across versions, and an unpickle that
    # merely does not raise is not evidence that it worked.
    out_dir, _meta = _train_and_save(tmp_path)
    path = os.path.join(out_dir, "metadata.json")
    with open(path, encoding="ascii") as fh:
        meta = json.load(fh)
    meta["versions"]["sklearn"] = "0.0.1-not-a-real-version"
    with open(path, "w", encoding="ascii") as fh:
        json.dump(meta, fh)
    with pytest.raises(wmodel.ModelRefusal, match="scikit-learn"):
        wmodel.load_model(out_dir)


def test_refuses_a_directory_that_is_not_a_model(tmp_path):
    with pytest.raises(wmodel.ModelRefusal, match="not a saved fiber classifier"):
        wmodel.load_model(str(tmp_path))


def test_the_verdict_travels_with_the_model(tmp_path):
    # A failed model must say so wherever it goes, not only in the dialog
    # that was dismissed.
    x, y, _g = make_data()
    est = wmodel.build_estimator("hist_gradient_boosting", n_classes=2)
    est.fit(x, y)
    out_dir = str(tmp_path / "bad")
    wmodel.save_model(
        out_dir, est, FEATURES, ["A", "B"], GEOMETRY,
        verdict="NOT SEPARABLE: balanced-accuracy CI includes the majority baseline",
    )
    _est, meta = wmodel.load_model(out_dir)
    assert meta["verdict"].startswith("NOT SEPARABLE")


def test_rejects_an_unknown_model_kind():
    with pytest.raises(ValueError, match="unknown model kind"):
        wmodel.build_estimator("deep_neural_network")
