"""
fiberlib 0.4.3: work shared between steps gives the same numbers as before, and
the per-distance GLCM values agree with the average over distances.
"""

import os
import sys

import numpy as np
from skimage.color import rgb2hsv

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis")
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import morphometrics as morph  # noqa: E402
from fiberlib import segmentation as seg  # noqa: E402
from fiberlib import straightness as straight  # noqa: E402
from fiberlib import texture as tex  # noqa: E402


def _fibers(seed=3, n=160):
    rng = np.random.default_rng(seed)
    img = np.zeros((n, n), dtype=np.float32)
    for _ in range(14):
        y0, x0 = rng.integers(0, n, 2)
        ang = rng.uniform(0, np.pi)
        t = np.arange(-60, 60)
        ys = np.clip((y0 + t * np.sin(ang)).astype(int), 0, n - 1)
        xs = np.clip((x0 + t * np.cos(ang)).astype(int), 0, n - 1)
        img[ys, xs] = rng.uniform(0.4, 1.0)
        img[np.clip(ys + 1, 0, n - 1), xs] = img[ys, xs]
    return img


def test_value_channel_is_the_hsv_value():
    rng = np.random.default_rng(0)
    for dtype, top in ((np.uint16, 65535), (np.uint8, 255)):
        rgb = rng.integers(0, top + 1, size=(40, 50, 3)).astype(dtype)
        got = seg.pick_channel(rgb, "Raw intensity")
        want = rgb2hsv(seg.scale_to_unit(rgb)).astype(np.float32)[..., 2]
        assert got.dtype == np.float32
        np.testing.assert_array_equal(got, want)


def test_shared_gap_distance_gives_the_same_gaps():
    fm = _fibers() > 0
    zone = np.zeros_like(fm)
    zone[20:140, 10:150] = True
    dist = morph.gap_distance(fm)
    for z in (None, zone):
        np.testing.assert_array_equal(morph.gap_peaks(fm, z, dist=dist), morph.gap_peaks(fm, z))
        assert morph.gap_analysis(fm, z, dist=dist) == morph.gap_analysis(fm, z)


def test_tortuosity_with_a_given_skeleton_matches():
    fm = _fibers() > 0
    sk, _ = straight.skeletonize_and_tangents(fm)
    a = straight.compute_skeleton_tortuosity(fm, min_branch_px=3)
    b = straight.compute_skeleton_tortuosity(fm, min_branch_px=3, skeleton=sk)
    assert a.keys() == b.keys()
    for k in a:
        assert a[k] == b[k] or (a[k] != a[k] and b[k] != b[k]), k


def test_glcm_each_distance_and_their_average():
    img = _fibers()
    fm = img > 0
    grid = {"grid_shape": (2, 2), "window_px": 80, "stride_px": 80}
    props = ["contrast", "correlation", "energy", "homogeneity", "entropy", "dissimilarity"]
    assert "per_window_by_distance" not in tex.compute_glcm_window(img, fm, grid, 16, [2, 5, 9], props)
    out = tex.compute_glcm_window(img, fm, grid, 16, [2, 5, 9], props, per_distance=True)
    by_d = out["per_window_by_distance"]
    assert sorted(by_d) == [2, 5, 9]
    for p in props:
        avg = out["per_window"][p]
        stack = np.stack([by_d[d][p] for d in (2, 5, 9)])
        # Every distance has pairs in these windows, so the average over
        # (distance, angle) equals the mean of the per-distance means.
        np.testing.assert_allclose(np.nanmean(stack, axis=0), avg, rtol=1e-5, atol=1e-6)
        # A single distance is the same as asking for that distance alone.
        alone = tex.compute_glcm_window(img, fm, grid, 16, [5], props, per_distance=True)
        np.testing.assert_allclose(by_d[5][p], alone["per_window"][p], rtol=1e-6, atol=1e-7)
        assert "per_window_by_distance" not in alone
