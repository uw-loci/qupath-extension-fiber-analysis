"""
What four measurements mean, pinned on fixtures with known answers.

Each of these changed meaning in fiberlib 0.4.0:

- a gap is reported as its WIDTH (the diameter of the largest empty circle),
  and only gap centres inside the analysis zone count;
- GLCM texture counts fiber-fiber pixel pairs only, never background;
- Otsu and triangle are fitted on the analysis zone, not the whole read
  region;
- per-window HDM is fiber in zone / zone pixels in the window, the same ratio
  as the annotation-level hdm_coverage.
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

from fiberlib import (
    morphometrics,
    pipeline,
    segmentation,
    texture,
    windows,
)  # noqa: E402


def _two_columns():
    """1 px fibers at x = 0 and x = 20 in a 40 x 40 image."""
    mask = np.zeros((40, 40), dtype=bool)
    mask[:, 0] = True
    mask[:, 20] = True
    return mask


def test_a_gap_is_reported_as_its_width():
    zone = np.zeros((40, 40), dtype=bool)
    zone[:, 1:20] = True
    gap = morphometrics.gap_analysis(_two_columns(), zone)
    # Fiber centres 20 px apart: the empty circle between them is 20 px across
    # (centre to centre), where 0.3.x reported its radius, 10.
    assert gap["max_gap_px"] == pytest.approx(20.0)
    assert gap["mean_gap_px"] == pytest.approx(20.0)


def test_a_gap_cut_by_the_edge_of_the_image_is_dropped():
    # The empty strip right of x = 20 runs to the image edge, so the fiber
    # bounding it, if any, is out of view: its width is unknown, and 0.3.x
    # reported it as a 38 px gap (19 px radius) peaking at the edge.
    assert morphometrics.gap_analysis(_two_columns())["max_gap_px"] == pytest.approx(
        20.0
    )


def test_gap_centres_outside_the_zone_do_not_count():
    mask = np.zeros((60, 60), dtype=bool)
    for x in (0, 20, 50):
        mask[:, x] = True
    # Two gaps, 20 px and 30 px across; only the first is in the zone.
    assert morphometrics.gap_analysis(mask)["max_gap_px"] == pytest.approx(30.0)
    zone = np.zeros_like(mask)
    zone[:, 1:20] = True
    assert morphometrics.gap_analysis(mask, zone)["max_gap_px"] == pytest.approx(20.0)


def test_no_fiber_means_no_gap():
    gap = morphometrics.gap_analysis(np.zeros((30, 30), dtype=bool))
    assert gap["n_gaps"] == 0
    assert np.isnan(gap["mean_gap_px"]) and np.isnan(gap["max_gap_px"])


def test_per_window_gaps_follow_the_same_rules():
    mask = _two_columns()
    zone = np.zeros_like(mask)
    zone[:, 1:20] = True
    out = morphometrics.per_window_morphometrics(
        skeleton=mask,
        fiber_mask=mask & zone,
        window_px=40,
        stride_px=40,
        grid_shape=(1, 1),
        flags={"gaps": True},
        zone_mask=zone,
        gap_fiber_mask=mask,
    )
    assert out["gap_max_px"][0, 0] == pytest.approx(20.0)


def test_glcm_texture_ignores_the_background():
    # Fibers of one uniform gray level: no texture at all, however much of
    # the window they cover. With background counted as level 0 (0.3.x), the
    # fiber/background edges gave contrast > 0, rising with coverage.
    scalar = np.full((32, 32), 0.6, dtype=np.float32)
    props = ["contrast", "energy", "homogeneity"]
    for width in (2, 8):
        mask = np.zeros((32, 32), dtype=bool)
        for x0 in range(0, 32, 16):
            mask[:, x0 : x0 + width] = True
        vals = texture.compute_glcm_window(scalar, mask, None, 16, [1], props)[
            "per_window"
        ]
        assert vals["contrast"][0, 0] == pytest.approx(0.0)
        assert vals["energy"][0, 0] == pytest.approx(1.0)
        assert vals["homogeneity"][0, 0] == pytest.approx(1.0)


def test_glcm_with_no_fiber_pairs_is_nan():
    scalar = np.full((8, 8), 0.5, dtype=np.float32)
    mask = np.zeros((8, 8), dtype=bool)
    mask[4, 4] = True  # one pixel: no fiber-fiber pair at any offset
    vals = texture.compute_glcm_window(scalar, mask, None, 16, [1], ["contrast"])[
        "per_window"
    ]
    assert np.isnan(vals["contrast"][0, 0])


def test_glcm_accepts_256_levels():
    rng = np.random.default_rng(0)
    scalar = rng.random((16, 16)).astype(np.float32)
    mask = np.ones((16, 16), dtype=bool)
    vals = texture.compute_glcm_window(scalar, mask, None, 256, [1], ["contrast"])[
        "per_window"
    ]
    assert np.isfinite(vals["contrast"][0, 0])


@pytest.mark.parametrize("method", ["otsu", "triangle"])
def test_threshold_is_fitted_inside_the_zone(method):
    # Inside the zone: background 0.2, fibers 0.4. Outside it (the read
    # margin): a bright 0.9 region that, fitted over everything, drags the cut
    # above the fibers so none of them survive.
    rng = np.random.default_rng(1)
    arr = np.full((64, 64), 0.2, dtype=np.float64)
    arr[:, ::4] = 0.4
    arr += rng.normal(0, 0.005, arr.shape)
    zone = np.zeros_like(arr, dtype=bool)
    zone[:, :32] = True
    arr[:, 32:] = 0.9
    whole = segmentation.threshold_scalar(arr, method, 0)
    fitted = segmentation.threshold_scalar(arr, method, 0, fit_mask=zone)
    fibers = np.zeros_like(zone)
    fibers[:, :32:4] = True
    assert whole[fibers].mean() < 0.05
    assert fitted[fibers].all()
    # Any cut in the empty gap between the two modes scores the same, and
    # skimage takes the lowest, at the edge of the background's noise.
    assert fitted[zone & ~fibers].mean() < 0.02


def test_window_counts_match_a_direct_sum():
    rng = np.random.default_rng(2)
    mask = rng.random((50, 70)) > 0.5
    counts = windows.window_counts(mask, 20, 15, (3, 4))
    for iy in range(3):
        for ix in range(4):
            y0, x0 = iy * 15, ix * 15
            assert counts[iy, ix] == mask[y0 : y0 + 20, x0 : x0 + 20].sum()


def test_window_hdm_is_fiber_over_zone_pixels():
    # Fibers everywhere; the zone is the left 30 px of a 60 x 60 region. A
    # window half in the zone has coverage 50% (half its area is out of
    # zone) but HDM 1.0 (all of its zone pixels are fiber).
    h = w = 60
    image = np.zeros((h, w, 3), dtype=np.uint8)
    fibers = np.ones((h, w), dtype=bool)
    boundary = np.zeros((h, w), dtype=bool)
    boundary[:, :30] = True
    out = pipeline.analyze(
        image,
        boundary_mask=boundary,
        existing_fiber_mask=fibers,
        seg_source="existing",
        zone_mode="whole",
        pixel_size_um=1.0,
        window_size_um=20.0,
        window_overlap_percent=50.0,
        straightness_enabled=False,
        morph_enabled=False,
        texture_enabled=False,
    )
    grid = out["window_grid"]
    hdm = grid["hdm"]
    n_pix = grid["n_pixels"]
    # Window column 2 starts at x = 20 and spans 20..39: 10 px in the zone.
    assert n_pix[0, 2] == 200
    assert hdm[0, 2] == pytest.approx(1.0)
    assert 100.0 * n_pix[0, 2] / 400.0 == pytest.approx(50.0)
    # Wholly inside the zone, HDM and coverage agree.
    assert hdm[0, 0] == pytest.approx(1.0)
    # Wholly outside it, there is no zone and no HDM.
    assert np.isnan(hdm[0, 4])


def test_a_supplied_zone_replaces_the_computed_one():
    # A tile sees only part of the outline, so the Java side supplies the zone.
    h = w = 40
    image = np.zeros((h, w, 3), dtype=np.uint8)
    fibers = np.ones((h, w), dtype=bool)
    boundary = np.zeros((h, w), dtype=bool)  # no outline visible in this tile
    zone = np.zeros((h, w), dtype=bool)
    zone[:, :10] = True
    out = pipeline.analyze(
        image,
        boundary_mask=boundary,
        zone_mask=zone,
        existing_fiber_mask=fibers,
        seg_source="existing",
        pixel_size_um=1.0,
        window_enabled=False,
        straightness_enabled=False,
        morph_enabled=False,
        texture_enabled=False,
    )
    assert out["result"]["zone_pixels"] == 400
    assert out["result"]["fiber_in_zone_pixels"] == 400
