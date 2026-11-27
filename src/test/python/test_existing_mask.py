"""A whole-image mask file cut to one region, at any uniform scale of the image."""

import os
import sys

import numpy as np
import pytest
from PIL import Image

sys.path.insert(
    0,
    os.path.join(os.path.dirname(__file__), "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"),
)

from fiberlib import segmentation as seg  # noqa: E402

FULL_W, FULL_H = 400, 300


def _full_mask():
    m = np.zeros((FULL_H, FULL_W), dtype=np.uint8)
    m[:, 100:200] = 255  # fiber stripe, full-resolution x in [100, 200)
    m[250:, :] = 255  # and a band at the bottom
    return m


def _expected(ox, oy, ds, rw, rh):
    xs = ox + (np.arange(rw) + 0.5) * ds
    ys = oy + (np.arange(rh) + 0.5) * ds
    col = (xs >= 100) & (xs < 200)
    row = ys >= 250
    return col[None, :] | row[:, None]


@pytest.mark.parametrize("scale", [1, 2, 4])
@pytest.mark.parametrize("ext", [".png", ".tif", ".npy"])
def test_whole_image_mask_is_cut_to_the_region(tmp_path, scale, ext):
    full = _full_mask()
    small = full[scale // 2 :: scale, scale // 2 :: scale]
    path = str(tmp_path / ("mask" + ext))
    if ext == ".npy":
        np.save(path, small)
    else:
        Image.fromarray(small).save(path)
    ox, oy, ds, rw, rh = 48, 200, 2.0, 60, 40
    crop = {"full_w": FULL_W, "full_h": FULL_H, "offset_x": ox, "offset_y": oy, "downsample": ds}
    got = seg.load_existing_mask(path, (rh, rw), crop=crop)
    assert got.shape == (rh, rw)
    want = _expected(ox, oy, ds, rw, rh)
    # A coarser mask can move an edge by one of its own pixels.
    tol = 0 if scale == 1 else 2 * (rw + rh)
    assert np.count_nonzero(got != want) <= tol
    assert got.any() and not got.all()


def test_region_sized_mask_without_crop(tmp_path):
    m = np.zeros((10, 12), dtype=np.uint8)
    m[2:5, 3:7] = 255
    path = str(tmp_path / "region.png")
    Image.fromarray(m).save(path)
    got = seg.load_existing_mask(path, (10, 12))
    assert got.sum() == 12


def test_mask_that_is_not_a_scale_of_the_image_is_refused(tmp_path):
    path = str(tmp_path / "wrong.npy")
    np.save(path, np.zeros((100, 100), dtype=np.uint8))
    crop = {"full_w": FULL_W, "full_h": FULL_H, "offset_x": 0, "offset_y": 0, "downsample": 1.0}
    with pytest.raises(ValueError, match="not a uniform scale"):
        seg.load_existing_mask(path, (10, 10), crop=crop)


def test_analyze_reads_a_whole_image_mask_file(tmp_path):
    from fiberlib import pipeline

    path = str(tmp_path / "mask.npy")
    np.save(path, _full_mask())
    ox, oy, ds, rw, rh = 40, 20, 2.0, 100, 60
    image = np.zeros((rh, rw, 3), dtype=np.uint8)
    out = pipeline.analyze(
        image,
        seg_source="existing",
        existing_mask_path=path,
        existing_mask_full_w=FULL_W,
        existing_mask_full_h=FULL_H,
        analysis_downsample=ds,
        region_offset_x=ox,
        region_offset_y=oy,
        zone_mode="whole",
        window_enabled=False,
        straightness_enabled=False,
        morph_enabled=False,
        texture_enabled=False,
    )
    assert np.array_equal(out["fiber_mask"], _expected(ox, oy, ds, rw, rh))


def test_analyze_names_a_missing_mask():
    from fiberlib import pipeline

    with pytest.raises(FileNotFoundError, match="no mask was given"):
        pipeline.analyze(np.zeros((8, 8, 3), dtype=np.uint8), seg_source="existing", existing_mask_path="")
