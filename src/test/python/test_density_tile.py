"""density_tile.py end to end: the threshold-free channels, and the tiling contract.

The tiling tests are the ones that matter. A density map of a whole slide is
assembled from tiles, and the only acceptable answer is the one a single run
over the whole image would give. Each tile is read with a halo and told where
its core is; these tests cut a synthetic image the way the Java side does and
require the assembled tiles to match a whole-image run.
"""

import os
import sys
import types

import numpy as np
import pytest
from PIL import Image

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
sys.path.insert(0, PKG_ROOT)
SCRIPT = os.path.join(PKG_ROOT, "scripts", "density_tile.py")

from fiberlib import intensity  # noqa: E402

PX_UM = 0.5
WINDOW_UM = 16.0  # 32 px
WINDOW_PX = 32


def _phantom(h=192, w=256, seed=0):
    """Left half: parallel fibers at 30 deg. Right half: isotropic noise blobs."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    t = np.deg2rad(30.0)
    stripes = 0.5 + 0.5 * np.cos(2 * np.pi * (xx * np.sin(t) - yy * np.cos(t)) / 9.0)
    blobs = rng.random((h, w))
    from scipy import ndimage

    blobs = ndimage.gaussian_filter(blobs, 2.0)
    blobs = (blobs - blobs.min()) / (blobs.max() - blobs.min())
    img = np.where(xx < w // 2, stripes, blobs) * 200.0 + rng.normal(0, 3, (h, w))
    return np.clip(img, 0, 255).astype(np.uint8)


def _run(tmp_path, image, name, **extra):
    """Execute density_tile.py the way Appose does: inputs as globals."""
    png = tmp_path / f"{name}.png"
    Image.fromarray(image).save(png)
    npz = tmp_path / f"{name}.npz"
    task = types.SimpleNamespace(outputs={})
    g = {
        "__name__": "__appose__",
        "task": task,
        "region_image_path": str(png),
        "region_w": image.shape[1],
        "region_h": image.shape[0],
        "pixel_size_um": PX_UM,
        "window_size_um": WINDOW_UM,
        "window_overlap_percent": 50.0,
        "seg_channel": "Raw intensity",
        "threshold_method": "manual",
        "manual_threshold": 120,
        "ridge_filter": "none",
        "sigma_min": 1.0,
        "sigma_max": 2.0,
        "sigma_step": 1.0,
        # Small-object removal is not local: an object cut by the read edge is
        # smaller than it really is. Off here so the comparison is exact.
        "min_fiber_area_px": 0,
        "output_npz_path": str(npz),
    }
    g.update(extra)
    with open(SCRIPT) as fh:
        exec(compile(fh.read(), SCRIPT, "exec"), g)
    assert task.outputs.get("ok") == "1", task.outputs.get("error")
    return dict(np.load(npz)), task.outputs


# --------------------------------------------------------------------------
# The measures themselves
# --------------------------------------------------------------------------


def test_coherence_separates_aligned_from_isotropic_texture(tmp_path):
    img = _phantom()
    out, _ = _run(
        tmp_path, img, "whole", density_mode="window", window_overlap_percent=0.0
    )
    coh = out["intensity_coherence"]
    left = coh[:, : coh.shape[1] // 2 - 1]
    right = coh[:, coh.shape[1] // 2 + 1 :]
    assert np.nanmin(left) > 0.8
    assert np.nanmax(right) < 0.5


def test_signal_density_needs_no_threshold(tmp_path):
    # The same image under two very different thresholds: every mask-based
    # channel moves, and the signal channels must not.
    img = _phantom()
    lo, _ = _run(tmp_path, img, "lo", density_mode="window", manual_threshold=40)
    hi, _ = _run(tmp_path, img, "hi", density_mode="window", manual_threshold=200)
    assert not np.allclose(
        lo["fiber_coverage_percent"], hi["fiber_coverage_percent"], equal_nan=True
    )
    assert np.array_equal(lo["signal_density"], hi["signal_density"])
    assert np.array_equal(
        lo["intensity_coherence"], hi["intensity_coherence"], equal_nan=True
    )


def test_signal_density_is_on_the_absolute_scale_not_per_image(tmp_path):
    # Dimming an image by half must halve its signal. A per-image rescale
    # (divide by the image's own max) would hide the difference entirely.
    img = _phantom()
    full, _ = _run(tmp_path, img, "full", density_mode="window")
    dim, _ = _run(tmp_path, (img // 2).astype(np.uint8), "dim", density_mode="window")
    ratio = np.nanmean(dim["signal_density"]) / np.nanmean(full["signal_density"])
    assert ratio == pytest.approx(0.5, abs=0.01)


def test_window_signal_density_equals_the_window_mean(tmp_path):
    img = _phantom()
    out, _ = _run(
        tmp_path, img, "w", density_mode="window", window_overlap_percent=50.0
    )
    stride = WINDOW_PX // 2
    scalar = img.astype(np.float64) / 255.0
    iy, ix = 3, 5
    expect = scalar[
        iy * stride : iy * stride + WINDOW_PX, ix * stride : ix * stride + WINDOW_PX
    ].mean()
    assert out["signal_density"][iy, ix] == pytest.approx(expect, rel=1e-6)


def test_a_flat_region_has_no_coherence_rather_than_a_made_up_one():
    z = np.zeros(5)
    assert np.isnan(intensity.coherence(z, z, z)).all()


# --------------------------------------------------------------------------
# The tiling contract
# --------------------------------------------------------------------------


def _tiles(extent, core, halo):
    """(read_start, read_len, crop_offset, core_len) along one axis."""
    out = []
    for start in range(0, extent, core):
        n = min(core, extent - start)
        r0 = max(0, start - halo)
        r1 = min(extent, start + n + halo)
        out.append((r0, r1 - r0, start - r0, n))
    return out


THRESHOLD_FREE = ("signal_density", "intensity_coherence")


def test_pixel_mode_tiles_reassemble_into_the_whole_image(tmp_path):
    img = _phantom()
    step = 4
    whole, _ = _run(tmp_path, img, "whole", density_mode="pixel", pixel_step=step)

    halo = WINDOW_PX // 2 + 1 + intensity.halo_px()
    core = 64  # a multiple of the step, as the Java side guarantees
    assembled = {
        k: np.full_like(whole[k], np.nan)
        for k in THRESHOLD_FREE + ("fiber_coverage_percent",)
    }
    for ry, rh, oy, ch in _tiles(img.shape[0], core, halo):
        for rx, rw, ox, cw in _tiles(img.shape[1], core, halo):
            out, _ = _run(
                tmp_path,
                img[ry : ry + rh, rx : rx + rw],
                f"t{ry}_{rx}",
                density_mode="pixel",
                pixel_step=step,
                crop_x=ox,
                crop_y=oy,
                crop_w=cw,
                crop_h=ch,
            )
            gy, gx = (ry + oy) // step, (rx + ox) // step
            for k in assembled:
                h, w = out[k].shape
                assembled[k][gy : gy + h, gx : gx + w] = out[k]
    for k in assembled:
        np.testing.assert_allclose(
            assembled[k], whole[k], rtol=1e-5, atol=1e-6, equal_nan=True, err_msg=k
        )


def test_window_mode_tiles_reassemble_into_the_whole_image(tmp_path):
    img = _phantom()
    stride = WINDOW_PX // 2
    whole, _ = _run(tmp_path, img, "whole", density_mode="window")

    # Tiles step by a whole number of strides and read window - stride past
    # their step, so every window lies wholly inside exactly one tile's core.
    step = 4 * stride
    margin = WINDOW_PX - stride
    halo = intensity.halo_px()
    assembled = {k: np.full_like(whole[k], np.nan) for k in THRESHOLD_FREE}
    H, W = img.shape
    for y0 in range(0, H, step):
        for x0 in range(0, W, step):
            ch = min(step + margin, H - y0)
            cw = min(step + margin, W - x0)
            if ch < WINDOW_PX or cw < WINDOW_PX:
                continue
            ry, rx = max(0, y0 - halo), max(0, x0 - halo)
            ry1, rx1 = min(H, y0 + ch + halo), min(W, x0 + cw + halo)
            out, _ = _run(
                tmp_path,
                img[ry:ry1, rx:rx1],
                f"w{y0}_{x0}",
                density_mode="window",
                crop_x=x0 - rx,
                crop_y=y0 - ry,
                crop_w=cw,
                crop_h=ch,
            )
            gy, gx = y0 // stride, x0 // stride
            for k in assembled:
                h, w = out[k].shape
                assembled[k][gy : gy + h, gx : gx + w] = out[k]
    for k in assembled:
        np.testing.assert_allclose(
            assembled[k], whole[k], rtol=1e-9, atol=1e-12, equal_nan=True, err_msg=k
        )


def test_a_crop_outside_the_read_is_refused(tmp_path):
    img = _phantom(64, 64)
    with pytest.raises(AssertionError, match="outside"):
        _run(tmp_path, img, "bad", density_mode="window", crop_x=10, crop_w=64)
