"""calibrate_threshold.py: the run-wide threshold every density-map tile uses.

It pools one histogram over sample tiles from every image and thresholds that,
so the answer must match thresholding the pooled pixels themselves, and must
resolve 16-bit data finely enough that the cut is not set by the binning.
"""

import os
import sys
import types

import numpy as np
import pytest
from PIL import Image
from skimage.filters import threshold_otsu, threshold_triangle

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
sys.path.insert(0, PKG_ROOT)
SCRIPT = os.path.join(PKG_ROOT, "scripts", "calibrate_threshold.py")


def _load():
    """The script's module namespace, without running the task body."""
    g = {"__name__": "calib_under_test"}
    src = open(SCRIPT).read()
    head = src[: src.index("\ntry:\n")]
    exec(compile(head, SCRIPT, "exec"), g)
    return g


def _run(tmp_path, images, **extra):
    paths = []
    for i, im in enumerate(images):
        p = tmp_path / f"r{i}.png"
        Image.fromarray(im).save(p)
        paths.append(str(p))
    task = types.SimpleNamespace(outputs={})
    g = {
        "__name__": "__appose__",
        "task": task,
        "region_paths": paths,
        "mask_paths": [],
        "seg_channel": "Raw intensity",
        "ridge_filter": "none",
        "invert_intensity": False,
        "rolling_ball_radius": 0,
    }
    g.update(extra)
    exec(compile(open(SCRIPT).read(), SCRIPT, "exec"), g)
    import json

    return json.loads(task.outputs["result_json"])


def _bimodal16(seed, n=(128, 128), lo=2000, hi=9000):
    rng = np.random.default_rng(seed)
    a = rng.normal(lo, 400, n)
    a[: n[0] // 3] = rng.normal(hi, 900, (n[0] // 3, n[1]))
    return np.clip(a, 0, 65535).astype(np.uint16)


def test_pooled_otsu_matches_otsu_on_the_pooled_pixels(tmp_path):
    ims = [_bimodal16(s) for s in range(3)]
    out = _run(tmp_path, ims)
    pooled = np.concatenate([im.ravel() for im in ims]).astype(np.float64) / 65535.0
    # Same answer to within one of the new bins, on 16-bit data in the low
    # range where 256 bins were too coarse.
    assert out["threshold_normalised"] == pytest.approx(
        threshold_otsu(pooled), abs=1.0 / 4096
    )
    assert out["histogram_bins"] == 4096
    assert len(out["histogram_counts"]) == 4096
    assert out["method"] == "otsu"


def test_the_binning_no_longer_sets_the_cut():
    g = _load()
    assert g["HIST_BINS"] >= 4096


def test_triangle_on_a_histogram_matches_skimage():
    g = _load()
    rng = np.random.default_rng(5)
    for skew in (False, True):
        data = np.concatenate(
            [rng.normal(0.2, 0.03, 20000), rng.uniform(0.25, 0.9, 3000)]
        )
        if skew:
            data = 1.0 - data
        data = np.clip(data, 0, 1)
        nbins = 256
        hist, edges = np.histogram(data, bins=nbins, range=(data.min(), data.max()))
        centers = (edges[:-1] + edges[1:]) / 2
        ours = g["triangle_on_histogram"](hist, centers)
        theirs = threshold_triangle(data, nbins=nbins)
        assert ours == pytest.approx(theirs, abs=(edges[1] - edges[0]) * 1.01), skew


def test_pooled_triangle_runs_and_is_reported(tmp_path):
    out = _run(tmp_path, [_bimodal16(9)], method="triangle")
    assert out["method"] == "triangle"
    assert 0.0 < out["threshold_normalised"] < 1.0


def test_an_unknown_method_is_refused(tmp_path):
    # Errors come back in the result, as the Java side reads them.
    out = _run(tmp_path, [_bimodal16(1)], method="mean")
    assert "otsu' or 'triangle" in out.get("error", "")


def test_micron_settings_are_converted_per_region(tmp_path):
    # Two regions at different pixel sizes, with a 4 um rolling ball: the
    # coarse one (2 um/px) must be flattened with a 2 px ball and the fine one
    # (0.5 um/px) with an 8 px ball, each on its own image.
    ims = [_bimodal16(1), _bimodal16(2)]
    per_region = _run(
        tmp_path,
        ims,
        region_pixel_um=[2.0, 0.5],
        sigma_min_um=1.0,
        sigma_max_um=4.0,
        sigma_step_um=1.0,
        rolling_ball_um=4.0,
    )
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    a = _run(tmp_path / "a", [ims[0]], rolling_ball_radius=2)
    b = _run(tmp_path / "b", [ims[1]], rolling_ball_radius=8)
    pooled = np.array(a["histogram_counts"]) + np.array(b["histogram_counts"])
    assert per_region["histogram_counts"] == pooled.tolist()
