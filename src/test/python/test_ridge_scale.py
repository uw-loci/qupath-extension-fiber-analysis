"""
Ridge responses on one absolute scale (fiberlib 0.4.0).

1.0 is the response of a full-contrast fiber, in every region. scikit-image's
Frangi and Meijering normalise per image, so a faint fiber's response used to
depend on the brightest structure in its region, and segment_internal then
stretched each region to its own range on top.
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

from fiberlib import segmentation as seg  # noqa: E402

FILTERS = ["frangi", "sato", "meijering"]


def _fibers(amplitudes, sigma=2.0, size=96):
    """Vertical Gaussian ridges of the given amplitudes, 24 px apart."""
    img = np.zeros((size, size), dtype=np.float32)
    x = np.arange(size, dtype=np.float32)
    for k, a in enumerate(amplitudes):
        img += a * np.exp(-((x - 12 - 24 * k) ** 2) / (2 * sigma * sigma))[None, :]
    return img


@pytest.mark.parametrize("name", FILTERS)
def test_a_full_contrast_fiber_responds_at_one(name):
    r = seg.apply_ridge_filter(_fibers([1.0]), name, 2, 4, 1)
    assert r[48, 12] == pytest.approx(1.0, abs=0.02)


@pytest.mark.parametrize("name", FILTERS)
def test_a_faint_fiber_responds_the_same_with_or_without_a_bright_one(name):
    alone = seg.apply_ridge_filter(_fibers([0.3]), name, 2, 4, 1)[48, 12]
    beside_bright = seg.apply_ridge_filter(_fibers([0.3, 1.0]), name, 2, 4, 1)[48, 12]
    assert alone > 0.05
    assert beside_bright == pytest.approx(alone, rel=0.02)


@pytest.mark.parametrize("name", FILTERS)
def test_a_dark_fiber_on_a_bright_background_is_segmented_not_the_background(name):
    # Invert intensity with a ridge filter: the filter looks for dark ridges,
    # so the response is already high on the fibers and must not be inverted
    # again (through 0.3.x it was, and the mask picked the background).
    img = 1.0 - _fibers([0.8, 0.8, 0.8])
    rgb = np.repeat((img * 65535).astype(np.uint16)[..., None], 3, axis=-1)
    mask = seg.segment_internal(
        rgb, "Raw intensity", "otsu", 0, name, 2, 4, 1, 0, invert_intensity=True
    )
    on_fibers = mask[:, [12, 36, 60]].mean()
    between = mask[:, [24, 48]].mean()
    assert on_fibers > 0.9
    assert between < 0.1


def test_a_manual_cut_with_a_ridge_filter_is_a_response_value():
    img = _fibers([1.0, 0.3])
    rgb = np.repeat((img * 65535).astype(np.uint16)[..., None], 3, axis=-1)
    kw = dict(manual_threshold=0, ridge_filter="sato", sigma_min=2, sigma_max=4)
    low = seg.segment_internal(
        rgb,
        "Raw intensity",
        "manual",
        sigma_step=1,
        min_fiber_area_px=0,
        manual_response_threshold=0.1,
        **kw,
    )
    high = seg.segment_internal(
        rgb,
        "Raw intensity",
        "manual",
        sigma_step=1,
        min_fiber_area_px=0,
        manual_response_threshold=0.5,
        **kw,
    )
    # 0.1 keeps both fibers (responses about 1.0 and 0.3); 0.5 only the bright one.
    assert low[48, 12] and low[48, 36]
    assert high[48, 12] and not high[48, 36]
