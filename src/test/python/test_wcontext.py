"""Neighbourhood context on the window grid.

The tests that matter here are the invariance ones. The whole reason the
context layer exists in this form -- rather than as "the mean angle of the
neighbours" -- is that absolute orientation is a property of how the section
landed on the slide. If a test suite does not check that, nothing stops the
rotation sensitivity creeping back in.
"""

import os
import sys

import numpy as np
import pytest

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import wcontext  # noqa: E402


def _grid(values):
    return np.asarray(values, dtype=np.float64)


# --------------------------------------------------------------------------
# NaN-aware neighbourhood statistics
# --------------------------------------------------------------------------


def test_a_constant_grid_has_no_spread_and_no_local_contrast():
    g = np.full((7, 7), 3.5)
    mean, sd, count = wcontext.nan_box_stats(g, 1)
    assert np.allclose(mean, 3.5)
    assert np.allclose(sd, 0.0)
    # Edge cells see fewer neighbours, which is correct -- what must not
    # happen is their becoming NaN and dropping a border of windows.
    assert count[0, 0] == 4
    assert count[3, 3] == 9
    assert np.isfinite(mean).all()


def test_one_undefined_window_does_not_poison_its_neighbourhood():
    g = np.ones((5, 5))
    g[2, 2] = np.nan
    mean, _sd, count = wcontext.nan_box_stats(g, 1)
    assert np.isfinite(mean).all()
    assert np.allclose(mean, 1.0)
    # The hole is simply not counted.
    assert count[2, 2] == 8
    assert count[1, 1] == 8


def test_a_wholly_undefined_neighbourhood_stays_undefined():
    g = np.full((5, 5), np.nan)
    mean, sd, count = wcontext.nan_box_stats(g, 1)
    assert np.isnan(mean).all()
    assert np.isnan(sd).all()
    assert (count == 0).all()


def test_the_spread_matches_the_population_standard_deviation():
    rng = np.random.default_rng(0)
    g = rng.normal(size=(9, 9))
    _mean, sd, _count = wcontext.nan_box_stats(g, 1)
    assert sd[4, 4] == pytest.approx(g[3:6, 3:6].std(), abs=1e-12)


# --------------------------------------------------------------------------
# Orientation context: the rotation-invariance contract
# --------------------------------------------------------------------------


def test_orientation_context_is_unchanged_by_rotating_the_whole_slide():
    # Turn the coverslip 37 degrees. The tissue has not changed, so no
    # feature the model sees may change either.
    rng = np.random.default_rng(1)
    angles = rng.uniform(0, 180, size=(8, 8))
    weight = rng.uniform(1, 100, size=(8, 8))

    disp_a, dev_a = wcontext.orientation_context(angles, weight, 1)
    disp_b, dev_b = wcontext.orientation_context((angles + 37.0) % 180.0, weight, 1)

    assert np.allclose(disp_a, disp_b, atol=1e-10)
    assert np.allclose(dev_a, dev_b, atol=1e-9)


def test_a_coaligned_neighbourhood_has_no_dispersion_and_an_isotropic_one_has_most():
    weight = np.ones((7, 7))
    aligned = np.full((7, 7), 20.0)
    disp, dev = wcontext.orientation_context(aligned, weight, 1)
    assert np.allclose(disp, 0.0, atol=1e-12)
    assert np.allclose(dev, 0.0, atol=1e-9)

    # Axial angles 0 and 90 are maximally opposed: their 2-theta vectors
    # point in opposite directions and cancel. A 3x3 box over a checkerboard
    # holds five of one and four of the other, so one vector survives out of
    # nine -- dispersion 8/9, the most an odd-sized box can reach.
    checker = np.indices((8, 8)).sum(axis=0) % 2 * 90.0
    disp2, _dev2 = wcontext.orientation_context(checker, np.ones((8, 8)), 1)
    assert disp2[4, 4] == pytest.approx(8.0 / 9.0, abs=1e-12)


def test_the_angle_deviation_wraps_instead_of_reading_178_degrees():
    # 179 degrees and 1 degree are 2 degrees apart on an axial scale. A
    # linear difference would call them nearly perpendicular, which is the
    # classic way an orientation feature goes quietly wrong.
    angles = np.full((5, 5), 1.0)
    angles[2, 2] = 179.0
    _disp, dev = wcontext.orientation_context(angles, np.ones((5, 5)), 1)
    assert dev[2, 2] == pytest.approx(2.0, abs=0.5)
    assert (dev[np.isfinite(dev)] <= 90.0 + 1e-9).all()


def test_a_window_with_no_fiber_carries_no_orientation_weight():
    angles = np.full((5, 5), 10.0)
    angles[2, 2] = 100.0
    weight = np.ones((5, 5))
    weight[2, 2] = 0.0
    disp, _dev = wcontext.orientation_context(angles, weight, 1)
    # The outlier is weightless, so its neighbours stay perfectly aligned.
    assert disp[1, 1] == pytest.approx(0.0, abs=1e-12)


# --------------------------------------------------------------------------
# Coverage gradient
# --------------------------------------------------------------------------


def test_the_coverage_gradient_is_flat_on_flat_tissue_and_fires_at_a_boundary():
    flat = np.full((9, 9), 0.4)
    assert np.allclose(wcontext.coverage_gradient(flat, 1), 0.0, atol=1e-12)

    step = np.zeros((9, 9))
    step[:, 5:] = 0.8
    grad = wcontext.coverage_gradient(step, 1)
    assert grad[4, 5] > grad[4, 0]
    assert grad[4, 0] == pytest.approx(0.0, abs=1e-12)


# --------------------------------------------------------------------------
# Assembly
# --------------------------------------------------------------------------


def _base_features():
    rng = np.random.default_rng(2)
    return {
        "coverage_fraction": rng.uniform(0, 1, size=(6, 6)),
        "order_parameter": rng.uniform(0, 1, size=(6, 6)),
        "mean_angle_deg": rng.uniform(0, 180, size=(6, 6)),
        "n_fiber_px": rng.uniform(0, 1000, size=(6, 6)),
    }


def test_the_excluded_base_features_get_no_neighbourhood_statistics():
    out = wcontext.context_features(_base_features(), radii=(1,))
    assert "coverage_fraction_mean_r1" in out
    assert "order_parameter_sd_r1" in out
    # Absolute angle and the raw pixel count are excluded from the model, so
    # their neighbourhood statistics would inherit the same defect.
    assert "mean_angle_deg_mean_r1" not in out
    assert "n_fiber_px_mean_r1" not in out
    # ...but the rotation-invariant terms derived from the angle are there.
    assert "angular_dispersion_r1" in out
    assert "angle_dev_r1" in out
    assert "coverage_grad_r1" in out


def test_every_radius_produces_its_own_named_block():
    out = wcontext.context_features(_base_features(), radii=(1, 3))
    for r in (1, 3):
        assert f"coverage_fraction_mean_r{r}" in out
        assert f"angular_dispersion_r{r}" in out
    assert out["coverage_fraction_mean_r1"].shape == (6, 6)


def test_local_contrast_is_the_window_minus_its_neighbourhood():
    base = _base_features()
    out = wcontext.context_features(base, radii=(1,))
    expected = base["coverage_fraction"] - out["coverage_fraction_mean_r1"]
    assert np.allclose(out["coverage_fraction_dev_r1"], expected)


def test_building_context_does_not_mutate_the_base_features():
    base = _base_features()
    snapshot = {k: v.copy() for k, v in base.items()}
    out = wcontext.context_features(base, radii=(1,))
    assert set(base) == set(snapshot)
    for k in base:
        assert np.array_equal(base[k], snapshot[k])
    assert not set(out) & set(base)


def test_mismatched_grid_shapes_are_refused():
    bad = {"a": np.zeros((4, 4)), "b": np.zeros((4, 5))}
    with pytest.raises(ValueError, match="one grid shape"):
        wcontext.context_features(bad, radii=(1,))
