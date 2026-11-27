"""
Which files a run writes, and what they show.

- The morph heatmaps follow the morphometrics and window settings, not the
  fiber-mask overlay checkbox.
- The minimum-coverage gate makes a window transparent in every heatmap,
  including the ones built from integer counts.
- The run script forwards only keys the pipeline accepts.
"""

import ast
import inspect
import os
import sys

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

from fiberlib import pipeline  # noqa: E402


def _run(tmp_path, **overrides):
    """80 x 80 region: the left half is all fiber, the right half one fiber column."""
    h, w = 80, 80
    image = np.zeros((h, w), dtype=np.uint8)
    fibers = np.zeros((h, w), dtype=bool)
    fibers[:, :40] = True
    fibers[:, 60] = True
    kwargs = dict(
        boundary_mask=np.ones((h, w), dtype=bool),
        existing_fiber_mask=fibers,
        seg_source="existing",
        zone_mode="whole",
        pixel_size_um=1.0,
        window_size_um=20.0,
        window_overlap_percent=0.0,
        straightness_enabled=True,
        radon_on=False,
        texture_enabled=False,
        morph_enabled=True,
        morph_lac=False,
        morph_fd=False,
        morph_gaps=False,
        output_dir=str(tmp_path),
        emit_fiber_mask_png=False,
    )
    kwargs.update(overrides)
    return pipeline.analyze(image, **kwargs)


def _alpha(path):
    return np.asarray(Image.open(path).convert("RGBA"))[..., 3]


def test_morph_heatmaps_do_not_need_the_fiber_mask_overlay(tmp_path):
    _run(tmp_path)
    assert not (tmp_path / "fiber_mask_overlay.png").exists()
    assert (tmp_path / "morph_fiber_coverage_percent_overlay.png").exists()
    assert (tmp_path / "morph_ridge_count_overlay.png").exists()
    assert (tmp_path / "morphometrics_overlay.png").exists()


def test_coverage_gate_hides_windows_in_integer_valued_heatmaps(tmp_path):
    # The right-hand column at x = 60 covers 20 of a window's 400 pixels, 5 %.
    _run(tmp_path, min_window_coverage_percent=10.0)
    for name in (
        "morph_fiber_coverage_percent_overlay.png",
        "morph_ridge_count_overlay.png",
        "morphometrics_overlay.png",
    ):
        alpha = _alpha(tmp_path / name)
        assert alpha[10, 10] > 0, name  # left half, full coverage
        assert alpha[10, 65] == 0, name  # 5 % window, below the gate


def test_without_a_gate_the_sparse_window_is_drawn(tmp_path):
    _run(tmp_path, min_window_coverage_percent=0.0)
    alpha = _alpha(tmp_path / "morph_fiber_coverage_percent_overlay.png")
    assert alpha[10, 65] > 0


def test_the_heatmap_property_is_gone():
    assert "heatmap_property" not in inspect.signature(pipeline.analyze).parameters
    with pytest.raises(TypeError):
        pipeline.analyze(np.zeros((8, 8), dtype=np.uint8), heatmap_property="contrast")


def test_the_run_script_forwards_only_keys_the_pipeline_accepts():
    script = os.path.join(PKG_ROOT, "scripts", "run_fiber_analysis.py")
    with open(script, encoding="utf-8") as fh:
        tree = ast.parse(fh.read())
    forward = None
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == "_FORWARD" for t in node.targets
        ):
            forward = ast.literal_eval(node.value)
    assert forward, "no _FORWARD in run_fiber_analysis.py"
    accepted = set(inspect.signature(pipeline.analyze).parameters)
    assert set(forward) - accepted == set()
