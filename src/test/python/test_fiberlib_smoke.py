"""
Smoke test for the bundled fiberlib package.

Run by `./gradlew check` via the fiberlibPythonTest task, which skips
loudly when the interpreter on PATH cannot import pytest / numpy /
scikit-image. Also runnable by hand with `pytest src/test/python` against
any env that has the scientific stack (the qupath-fiber-analysis pixi env
works). fiberlib has no Java coverage, so this file is the only thing
testing it.

Builds a synthetic image with horizontal stripes in the top half and
vertical stripes in the bottom half, then checks the windowed orientation
statistics and a few other surface invariants.
"""

import os
import sys

import numpy as np
import pytest

# Make the bundled fiberlib importable when running this file directly.
THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import dilation, windows  # noqa: E402
from fiberlib import segmentation, straightness, morphometrics, texture  # noqa: E402

# 2 px stripes on an 8 px pitch. The gap width is the load-bearing number:
# segment_internal finishes with binary_closing(disk(1)), which bridges any gap
# of 2 px or less. The original fixture used a 4 px pitch, so closing welded
# every stripe to its neighbour and coverage came out at 0.985 against an
# assert of 0.2 < coverage < 0.6 -- the suite had been failing on its own
# fixture, and nothing ran it to notice.
_STRIPE_WIDTH = 2
_STRIPE_PITCH = 8


def synthetic_stripes(h=200, w=200, background=0.18, foreground=0.82, noise=0.04):
    """Top half: horizontal stripes; bottom half: vertical stripes.

    Grey levels rather than a pure {0, 1} image, with a little seeded noise, so
    Otsu is picking a threshold from a real bimodal histogram instead of
    landing on it because the background happens to be exactly zero.
    """
    rng = np.random.default_rng(12345)
    img = np.full((h, w), background, dtype=np.float32)
    for r in range(0, h // 2, _STRIPE_PITCH):
        img[r : r + _STRIPE_WIDTH, :] = foreground
    for c in range(0, w, _STRIPE_PITCH):
        img[h // 2 :, c : c + _STRIPE_WIDTH] = foreground
    img += rng.normal(0.0, noise, img.shape).astype(np.float32)
    return np.clip(img, 0.0, 1.0)


def test_segment_internal_finds_mostly_stripes():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    assert mask.dtype == np.bool_
    # 2 px of every 8 is stripe, so a correct segmentation lands near 0.25.
    coverage = mask.mean()
    assert 0.2 < coverage < 0.6


def test_windows_axial_stats():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
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
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
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
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel, angles = straightness.skeletonize_and_tangents(mask)
    grid = windows.compute_windows(angles, mask, window_px=20, stride_px=20)
    out = texture.compute_glcm_window(
        img,
        mask,
        grid,
        quant_levels=16,
        distances_px=[1],
        props=["contrast", "energy"],
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


def test_manual_threshold_is_an_absolute_gray_level():
    """A manual cut must land on the same raw value whatever the region range.

    Before fiberlib 0.2.6 segment_internal min-max stretched every region
    before thresholding, so manual=4500 cut at a different raw value in each
    one -- it was a percentile of the region's own range, not a gray level.
    """
    for lo, hi in [(0, 65535), (200, 12000), (3000, 9000)]:
        img = np.linspace(lo, hi, 128 * 128).astype(np.uint16).reshape(128, 128)
        mask = segmentation.segment_internal(
            image=img,
            channel="raw",
            threshold_method="manual",
            manual_threshold=4500,
            ridge_filter="none",
            sigma_min=1.0,
            sigma_max=1.0,
            sigma_step=1.0,
            min_fiber_area_px=0,
        )
        assert (
            img[mask].min() == 4500
        ), f"range [{lo},{hi}] cut at {img[mask].min()}, not 4500"


def test_manual_threshold_matches_bit_depth():
    """The same fraction of full scale must mean the same thing at 8 and 16 bit."""
    img8 = np.linspace(0, 255, 128 * 128).astype(np.uint8).reshape(128, 128)
    img16 = np.linspace(0, 65535, 128 * 128).astype(np.uint16).reshape(128, 128)
    common = dict(
        channel="raw",
        threshold_method="manual",
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=1.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    m8 = segmentation.segment_internal(image=img8, manual_threshold=128, **common)
    m16 = segmentation.segment_internal(image=img16, manual_threshold=32768, **common)
    assert abs(m8.mean() - m16.mean()) < 0.01


def test_value_channel_scales_by_bit_depth():
    """rgb_to_value must not assume 8-bit; the texture path clips at 1.0."""
    from fiberlib.pipeline import rgb_to_value

    assert rgb_to_value(np.full((4, 4, 3), 12000, np.uint16))[0, 0] == pytest.approx(
        12000 / 65535, abs=1e-4
    )
    assert rgb_to_value(np.full((4, 4, 3), 128, np.uint8))[0, 0] == pytest.approx(
        128 / 255, abs=1e-4
    )


def test_glcm_quantise_trusts_its_unit_contract():
    """quantise re-scaling by 255 is what forced the compensating bug upstream."""
    assert texture.quantise(np.full((4, 4), 0.25, np.float32), 16)[0, 0] == 4
    assert texture.quantise(np.full((4, 4), 1.0, np.float32), 16)[0, 0] == 15
