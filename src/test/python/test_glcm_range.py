"""
GLCM gray levels span a chosen range: on full scale a dim image's fibers land in
one level and every window reads as flat, on a range that fits them they do not.
The range is the same for every window, so a level keeps one meaning in a run.
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

from fiberlib import pipeline  # noqa: E402
from fiberlib import texture as tex  # noqa: E402

PROPS = ["contrast", "correlation", "energy", "homogeneity", "entropy", "dissimilarity"]


def _dim_image(n=300, seed=1):
    """Fibers at 200-470 counts of 65535, like a PolScope retardance image."""
    rng = np.random.default_rng(seed)
    img = np.full((n, n), 20, dtype=np.uint16)
    for y in range(10, n - 10, 12):
        img[y:y + 4, 5:-5] = rng.integers(200, 471, size=(4, n - 10))
    return img


def _analyze(img, tmp_path, **kw):
    return pipeline.analyze(
        img,
        pixel_size_um=1.0,
        zone_mode="whole",
        seg_source="internal",
        threshold_method="manual",
        manual_threshold=100,
        ridge_filter="none",
        min_fiber_area_px=4,
        window_enabled=True,
        window_size_um=100.0,
        window_overlap_percent=0.0,
        min_window_coverage_percent=1.0,
        straightness_enabled=False,
        morph_enabled=False,
        texture_enabled=True,
        quant_levels=16,
        glcm_distances_px="1,2",
        output_dir=str(tmp_path),
        **kw,
    )


def test_the_default_range_is_the_old_mapping():
    a = np.random.default_rng(0).random((64, 64), dtype=np.float32)
    want = np.floor(np.clip(a, 0.0, 1.0) * 15 + 0.5).astype(np.uint8)
    np.testing.assert_array_equal(tex.quantise(a, 16), want)


def test_values_outside_the_range_clip_to_the_end_levels():
    a = np.array([[0.1, 0.2, 0.3, 0.9]], dtype=np.float32)
    assert tex.quantise(a, 16, (0.2, 0.3)).tolist() == [[0, 0, 15, 15]]


def test_level_use_counts_the_levels_fibers_fill():
    v = np.linspace(0.0, 1.0, 100000, dtype=np.float32).reshape(100, 1000)
    mask = np.ones_like(v, dtype=bool)
    full = tex.level_use(v, mask, 16, (0.0, 1.0), 65535.0)
    assert full["levels_holding_99pct"] == 16
    assert full["clipped_percent"] == 0.0
    assert full["fiber_gray_p1"] == pytest.approx(0.01 * 65535, rel=1e-3)
    assert full["fiber_gray_p99"] == pytest.approx(0.99 * 65535, rel=1e-3)
    half = tex.level_use(v, mask, 16, (0.0, 0.5), 65535.0)
    assert half["clipped_percent"] == pytest.approx(50.0, abs=0.01)
    assert tex.level_use(v, np.zeros_like(mask), 16, (0.0, 1.0), 65535.0) == {}


def test_a_dim_image_collapses_on_full_scale_and_not_on_its_range(tmp_path):
    img = _dim_image()
    full = _analyze(img, tmp_path / "full")["result"]["texture"]
    assert full["levels_holding_99pct"] == 1
    assert full["gray_min"] == 0.0 and full["gray_max"] == 65535.0
    assert full["mean_per_prop"]["energy"] == pytest.approx(1.0)
    assert full["mean_per_prop"]["contrast"] == pytest.approx(0.0)
    assert full["fiber_gray_p1"] == pytest.approx(200, abs=5)
    assert full["fiber_gray_p99"] == pytest.approx(470, abs=5)

    fit = _analyze(img, tmp_path / "fit", glcm_gray_min=200, glcm_gray_max=470)["result"]["texture"]
    assert fit["levels_holding_99pct"] == 16
    assert fit["gray_min"] == 200.0 and fit["gray_max"] == 470.0
    assert fit["clipped_percent"] == pytest.approx(0.0, abs=0.01)
    assert fit["mean_per_prop"]["energy"] < 0.2
    assert fit["mean_per_prop"]["contrast"] > 1.0


@pytest.mark.parametrize("lo,hi", [(500, 400), (500, 500), (0, 70000), (-5, 100)])
def test_a_range_that_does_not_fit_is_refused(tmp_path, lo, hi):
    with pytest.raises(ValueError, match="GLCM gray range"):
        _analyze(_dim_image(), tmp_path, glcm_gray_min=lo, glcm_gray_max=hi)


def test_an_eight_bit_image_uses_its_own_gray_levels(tmp_path):
    img = (_dim_image() // 2).astype(np.uint8)
    t = _analyze(img, tmp_path, glcm_gray_min=100, glcm_gray_max=235)["result"]["texture"]
    assert t["gray_max"] == 235.0
    assert t["levels_holding_99pct"] == 16
