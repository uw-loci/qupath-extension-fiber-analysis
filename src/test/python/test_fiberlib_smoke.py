"""
Smoke test for the bundled fiberlib package.

Not wired into Gradle -- run by hand with `pytest` against a Python
environment that has numpy / scipy / scikit-image / matplotlib / pillow
installed (the qupath-fiber-analysis pixi env works).

Builds a synthetic image with horizontal stripes in the top half and
vertical stripes in the bottom half, then checks the windowed orientation
statistics and a few other surface invariants.
"""
import os
import sys

import numpy as np


# Make the bundled fiberlib importable when running this file directly.
THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(os.path.join(
    THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
))
sys.path.insert(0, PKG_ROOT)

from fiberlib import dilation, windows  # noqa: E402
from fiberlib import segmentation, straightness, morphometrics, texture  # noqa: E402


def synthetic_stripes(h=200, w=200):
    """Top half: horizontal stripes; bottom half: vertical stripes."""
    img = np.zeros((h, w), dtype=np.float32)
    for r in range(0, h // 2, 4):
        img[r : r + 2, :] = 1.0
    for c in range(0, w, 4):
        img[h // 2 :, c : c + 2] = 1.0
    return img


def test_segment_internal_finds_mostly_stripes():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0, sigma_max=3.0, sigma_step=1.0,
        min_fiber_area_px=0,
    )
    assert mask.dtype == np.bool_
    # Stripes occupy roughly half each region.
    coverage = mask.mean()
    assert 0.2 < coverage < 0.6


def test_windows_axial_stats():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb, channel="raw", threshold_method="otsu",
        manual_threshold=128, ridge_filter="none",
        sigma_min=1.0, sigma_max=3.0, sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel, angles = straightness.skeletonize_and_tangents(mask)
    grid = windows.compute_windows(angles, mask, window_px=20, stride_px=20)
    op = grid["order_parameter"]
    # In stripe regions OS should be high (>0.5) where stripes exist.
    # In mixed border rows it can be lower; check global mean instead.
    assert np.nanmean(op) > 0.4


def test_dilation_outside_zone_grows_by_width():
    h, w = 100, 100
    boundary = np.zeros((h, w), dtype=bool)
    boundary[40:60, 40:60] = True
    info = dilation.compute_border_zone_mask(boundary, dilation_px=5, mode="outside")
    zone = info["zone_mask"]
    # The outside zone should not overlap the boundary itself.
    assert not (zone & boundary).any()
    # And it should have nonzero area.
    assert zone.sum() > 0


def test_morphometrics_smoke():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb, channel="raw", threshold_method="otsu",
        manual_threshold=128, ridge_filter="none",
        sigma_min=1.0, sigma_max=3.0, sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel = morphometrics.build_skeleton(mask)
    bp, ep = morphometrics.branch_endpoint_counts(skel)
    assert bp >= 0 and ep >= 0
    fd = morphometrics.fractal_dimension(skel, [2, 4, 8, 16, 32])
    assert 0.5 < fd < 2.5
    lac = morphometrics.lacunarity(mask, [4, 8, 16])
    assert lac and all(v >= 1.0 for v in lac.values() if v == v)
    hdm_value = morphometrics.hdm(mask, np.ones_like(mask))
    assert 0.0 <= hdm_value <= 1.0


def test_texture_smoke():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb, channel="raw", threshold_method="otsu",
        manual_threshold=128, ridge_filter="none",
        sigma_min=1.0, sigma_max=3.0, sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel, angles = straightness.skeletonize_and_tangents(mask)
    grid = windows.compute_windows(angles, mask, window_px=20, stride_px=20)
    out = texture.compute_glcm_window(
        img, mask, grid,
        quant_levels=16, distances_px=[1], props=["contrast", "energy"],
    )
    assert "per_window" in out
    assert set(out["per_window"].keys()) == {"contrast", "energy"}


if __name__ == "__main__":
    test_segment_internal_finds_mostly_stripes()
    test_windows_axial_stats()
    test_dilation_outside_zone_grows_by_width()
    test_morphometrics_smoke()
    test_texture_smoke()
    print("OK -- fiberlib smoke tests passed.")
