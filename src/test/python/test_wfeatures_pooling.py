"""
The pooled feature bank must agree exactly with the window loop it replaces.

This is the safety net for the whole fine-stride optimisation. `wfeatures`
computes a window's value from four corners of a summed-area table instead of
slicing the window out and reducing it, which is what makes the cost
independent of stride. If the lattice is off by one, every number is still
plausible and the output map sits shifted against the measurements that
produced it. Nothing else in the suite would notice.

`compute_windows` anchors a window at `iy * stride_px`; a centre-anchored
filter would be offset by half a window. That is the specific error these
tests exist to catch.
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

from fiberlib import straightness, wfeatures, windows  # noqa: E402


def _bars(angle_deg, n=192, pitch=12, width=3):
    """Parallel bars at `angle_deg` (0 = horizontal, 90 = vertical)."""
    yy, xx = np.mgrid[:n, :n]
    rad = np.deg2rad(angle_deg)
    normal = (xx - n / 2.0) * -np.sin(rad) + (yy - n / 2.0) * np.cos(rad)
    return np.mod(normal, float(pitch)) < float(width)


def _speckle(n=160, seed=5, frac=0.25):
    rng = np.random.default_rng(seed)
    return rng.random((n, n)) < frac


GEOMETRIES = [
    (64, 64),  # no overlap
    (64, 32),  # 50%
    (64, 16),  # 75%
    (48, 12),
    (65, 32),  # odd window
    (64, 33),  # odd stride
    (40, 1),  # stride of one pixel
]


@pytest.mark.parametrize("window_px,stride_px", GEOMETRIES)
def test_pooled_counts_match_the_window_loop(window_px, stride_px):
    mask = _bars(30.0, n=160) | _bars(100.0, n=160, pitch=17, width=2)
    _skel, angles = straightness.skeletonize_and_tangents(mask)
    ref = windows.compute_windows(angles, mask, window_px, stride_px=stride_px, min_pixels=1)
    got, meta = wfeatures.window_features(mask, window_px, stride_px, min_pixels=1)

    hw, ww = ref["grid_shape"]
    # The pooled grid may carry one extra clamped window per axis, which the
    # reference loop drops entirely. Compare over the shared whole windows.
    assert meta["grid_shape"][0] >= hw
    assert meta["grid_shape"][1] >= ww

    np.testing.assert_allclose(
        got["n_fiber_px"][:hw, :ww],
        ref["n_pixels"].astype(np.float64),
        rtol=0,
        atol=0,
        err_msg="pooled fiber-pixel counts must be exact, not close",
    )


@pytest.mark.parametrize("window_px,stride_px", GEOMETRIES)
def test_pooled_order_parameter_matches_the_window_loop(window_px, stride_px):
    mask = _bars(30.0, n=160) | _bars(100.0, n=160, pitch=17, width=2)
    _skel, angles = straightness.skeletonize_and_tangents(mask)
    # Hand the reference a float64 angle field so this test measures the
    # LATTICE and nothing else. skeletonize_and_tangents returns float32 and
    # compute_windows accumulates its means in that dtype, which costs about
    # 1e-7 -- see test_float32_reference_differs_only_at_its_own_precision.
    ref = windows.compute_windows(
        angles.astype(np.float64), mask, window_px, stride_px=stride_px, min_pixels=1
    )
    got, meta = wfeatures.window_features(mask, window_px, stride_px, min_pixels=1)
    hw, ww = ref["grid_shape"]

    np.testing.assert_allclose(
        got["order_parameter"][:hw, :ww], ref["order_parameter"], rtol=0, atol=1e-9
    )
    # Angles are 180-periodic, so compare as axial vectors rather than values:
    # 179.9999 and 0.0001 are the same orientation.
    a = np.deg2rad(2.0 * got["mean_angle_deg"][:hw, :ww])
    b = np.deg2rad(2.0 * ref["mean_angle_deg"])
    both = np.isfinite(a) & np.isfinite(b)
    np.testing.assert_allclose(np.cos(a)[both], np.cos(b)[both], rtol=0, atol=1e-9)
    np.testing.assert_allclose(np.sin(a)[both], np.sin(b)[both], rtol=0, atol=1e-9)


def test_pooling_is_exact_on_an_awkward_random_mask():
    # Bars are structured; a speckle mask makes an off-by-one obvious in a way
    # a smooth field can hide.
    mask = _speckle()
    for window_px, stride_px in [(32, 8), (31, 7), (16, 16)]:
        ref = windows.compute_windows(
            np.where(mask, 45.0, np.nan).astype(np.float32), mask, window_px,
            stride_px=stride_px, min_pixels=1,
        )
        pooler = wfeatures.BoxPooler(mask.shape, window_px, stride_px)
        pooled = pooler.pool(mask.astype(np.float64))
        hw, ww = ref["grid_shape"]
        np.testing.assert_array_equal(pooled[:hw, :ww], ref["n_pixels"].astype(np.float64))


def test_min_pixels_gate_matches():
    mask = _bars(0.0, n=160, pitch=20, width=2)
    _skel, angles = straightness.skeletonize_and_tangents(mask)
    for min_px in (1, 50, 400):
        ref = windows.compute_windows(angles, mask, 64, stride_px=32, min_pixels=min_px)
        got, _ = wfeatures.window_features(mask, 64, 32, min_pixels=min_px)
        hw, ww = ref["grid_shape"]
        np.testing.assert_array_equal(
            np.isnan(got["order_parameter"][:hw, :ww]), np.isnan(ref["order_parameter"])
        )


def test_an_offset_lattice_would_be_caught():
    # Guard the guard: if the pooler were centre-anchored instead of
    # top-left-anchored, this comparison must fail. Build the shifted answer
    # by hand and assert it does NOT match.
    mask = _bars(30.0, n=160)
    window_px, stride_px = 64, 16
    ref = windows.compute_windows(
        np.where(mask, 30.0, np.nan).astype(np.float32), mask, window_px,
        stride_px=stride_px, min_pixels=1,
    )
    pooler = wfeatures.BoxPooler(mask.shape, window_px, stride_px)
    correct = pooler.pool(mask.astype(np.float64))
    hw, ww = ref["grid_shape"]
    np.testing.assert_array_equal(correct[:hw, :ww], ref["n_pixels"].astype(np.float64))

    shifted = np.roll(correct, 1, axis=0)
    assert not np.array_equal(
        shifted[:hw, :ww], ref["n_pixels"].astype(np.float64)
    ), "the fixture is too uniform to detect a one-window lattice shift"


def test_partial_windows_are_emitted_and_flagged():
    # Floor division in compute_windows leaves a trailing strip in no window,
    # which on a classifier raster is an unclassified border on every slide.
    mask = _bars(0.0, n=100, pitch=10, width=3)
    window_px, stride_px = 32, 16
    ref = windows.compute_windows(
        np.where(mask, 0.0, np.nan).astype(np.float32), mask, window_px,
        stride_px=stride_px, min_pixels=1,
    )
    got, meta = wfeatures.window_features(mask, window_px, stride_px, min_pixels=1)

    hw, ww = ref["grid_shape"]
    # 100 px, window 32, stride 16 -> 5 whole windows reaching 96, 4 px left.
    assert meta["grid_shape"][0] == hw + 1
    assert meta["grid_shape"][1] == ww + 1
    assert not meta["partial"][:hw, :ww].any(), "whole windows must not be flagged partial"
    assert meta["partial"][hw, :].all()
    assert meta["partial"][:, ww].all()
    # A clamped window has a smaller support, and that is the denominator the
    # area-normalised features must use.
    assert meta["area_px"][hw, ww] < meta["area_px"][0, 0]
    assert np.isfinite(got["coverage_fraction"][hw, ww])


def test_coverage_never_exceeds_one_even_on_a_clamped_window():
    mask = np.ones((70, 70), dtype=bool)
    got, meta = wfeatures.window_features(mask, 32, 16, min_pixels=1)
    assert np.nanmax(got["coverage_fraction"]) <= 1.0 + 1e-12
    assert np.allclose(got["coverage_fraction"], 1.0)


def test_length_weighted_straightness_beats_the_centroid_binning_it_replaces():
    # The defect: compute_skeleton_tortuosity binned each fiber into exactly
    # one window by centroid, so at fine stride most windows had no fibers at
    # all. Painting the value onto every pixel of the fiber fixes that.
    mask = _bars(0.0, n=192, pitch=24, width=3)
    got, meta = wfeatures.window_features(mask, 64, 8, min_pixels=1)
    s = got["straightness_mean_lenweighted"]
    covered = np.isfinite(s)
    assert covered.mean() > 0.8, (
        f"only {100 * covered.mean():.0f}% of windows have a straightness value; "
        "the centroid-binning sparsity is still present"
    )
    # Straight bars: chord/arc near 1 everywhere it is defined.
    assert np.nanmin(s) > 0.9


def test_absolute_angle_is_excluded_from_the_model_feature_set():
    # Absolute axial orientation is a property of how the section was mounted.
    # A model that sees it will memorise it and fail on the next slide.
    mask = _bars(30.0, n=128)
    got, _ = wfeatures.window_features(mask, 64, 32, min_pixels=1)
    names = wfeatures.model_feature_names(got)
    assert "mean_angle_deg" in got
    assert "mean_angle_deg" not in names
    assert "n_fiber_px" not in names
    assert "order_parameter" in names
    assert "coverage_fraction" in names


def test_rejects_a_stride_wider_than_the_window():
    with pytest.raises(ValueError, match="must not exceed"):
        wfeatures.BoxPooler((100, 100), 32, 64)


def test_float32_reference_differs_only_at_its_own_precision():
    # The pooled bank accumulates in float64; compute_windows accumulates in
    # whatever dtype the angle field has, and skeletonize_and_tangents returns
    # float32. So the two disagree at about 1e-7 in production -- immaterial,
    # and the pooled one is the more accurate of the two. Pinned here so a
    # future real discrepancy is not waved away as "just float32".
    mask = _bars(30.0, n=160)
    _skel, angles = straightness.skeletonize_and_tangents(mask)
    assert angles.dtype == np.float32
    got, _ = wfeatures.window_features(mask, 64, 32, min_pixels=1)

    ref32 = windows.compute_windows(angles, mask, 64, stride_px=32, min_pixels=1)
    ref64 = windows.compute_windows(
        angles.astype(np.float64), mask, 64, stride_px=32, min_pixels=1
    )
    hw, ww = ref32["grid_shape"]
    pooled = got["order_parameter"][:hw, :ww]

    d32 = np.nanmax(np.abs(pooled - ref32["order_parameter"]))
    d64 = np.nanmax(np.abs(pooled - ref64["order_parameter"]))
    assert d32 < 1e-6, f"float32 gap {d32:.2e} is larger than float32 precision explains"
    assert d64 < 1e-12, f"float64 gap {d64:.2e} means the lattice is wrong, not the dtype"
    assert d64 < d32
