"""
Appose task script: run fiber analysis for a single annotation.

This is a THIN WRAPPER. All orchestration lives in the importable library
`fiberlib.pipeline.analyze(...)`; this script only (1) unpacks the Appose-injected
globals, (2) loads the region image and the optional polygon boundary mask from
the PNGs Java wrote, (3) calls `analyze(...)`, and (4) ships the summary dict
back over `task.outputs['result_json']`. Side files (overlays, windows.json,
results.json, optional .npz) are written under output_dir by the library.

Inputs (injected by Appose 0.10+ as Python variables) are unchanged -- see the
parameter list on fiberlib.pipeline.analyze; the names match one-to-one. Anything
not injected falls back to the library default.
"""

import json
import logging
import os

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.appose.run")


def opt_get(var_name, default):
    """Read an optional injected input; return default if unbound."""
    return globals().get(var_name, default)


# Every analyze() keyword that the Appose side may inject (names match exactly).
# Forwarded straight through when present; absent ones use the library default.
_FORWARD = [
    "pixel_size_um",
    "border_zone_width_um",
    "zone_mode",
    "seg_source",
    "seg_channel",
    "threshold_method",
    "manual_threshold",
    "ridge_filter",
    "sigma_min",
    "sigma_max",
    "sigma_step",
    "min_fiber_area_px",
    "invert_intensity",
    "rolling_ball_radius",
    "project_threshold_norm",
    "existing_mask_path",
    "window_enabled",
    "window_size_um",
    "window_overlap_percent",
    "min_window_coverage_percent",
    "straightness_enabled",
    "tortuosity_on",
    "radon_on",
    "min_branch_um",
    "morph_enabled",
    "morph_branch",
    "morph_endpoints",
    "morph_length",
    "morph_curvature",
    "morph_hdm",
    "morph_lac",
    "morph_fd",
    "morph_gaps",
    "lac_box_sizes_px",
    "fractal_box_sizes_px",
    "texture_enabled",
    "quant_levels",
    "glcm_distances_px",
    "texture_contrast",
    "texture_correlation",
    "texture_energy",
    "texture_homogeneity",
    "texture_entropy",
    "texture_dissimilarity",
    "heatmap_property",
    "bbox_x",
    "bbox_y",
    "bbox_w",
    "bbox_h",
    "region_offset_x",
    "region_offset_y",
    "extension_version",
    "output_dir",
    "emit_fiber_mask_png",
    "emit_straightness_png",
    "emit_glcm_png",
    "emit_morph_summary",
    "emit_json_sidecar",
    "emit_npz",
]


try:
    import fiberlib

    g = globals()
    kwargs = {name: g[name] for name in _FORWARD if name in g}

    # Region image (PNG written by Java) -> numpy array.
    # region_image_path / task are injected by Appose at runtime (hence noqa).
    image = np.asarray(Image.open(region_image_path))  # noqa: F821

    # Optional polygon boundary mask; fall back to the bbox handled by the
    # library (bbox_x/y/w/h are forwarded in kwargs when present).
    boundary_mask = None
    bmp = str(opt_get("boundary_mask_path", "") or "")
    if bmp and os.path.exists(bmp):
        try:
            mask_arr = np.asarray(Image.open(bmp))
            if mask_arr.ndim == 3:
                mask_arr = mask_arr[..., 0]
            rh = int(opt_get("region_h", mask_arr.shape[0]))
            rw = int(opt_get("region_w", mask_arr.shape[1]))
            if mask_arr.shape == (rh, rw):
                boundary_mask = mask_arr > 127
            else:
                logger.warning(
                    "boundary_mask shape %s != region (%d, %d); using bbox fallback",
                    mask_arr.shape,
                    rh,
                    rw,
                )
        except Exception as exc:
            logger.warning("Could not load boundary_mask_path %s: %s", bmp, exc)

    out = fiberlib.analyze(image=image, boundary_mask=boundary_mask, **kwargs)

    _payload = json.dumps(fiberlib.sanitize_json(out["result"]))
    task.outputs["result_json"] = _payload  # noqa: F821
    logger.info("Fiber analysis complete")

except Exception as e:
    logger.error("Fiber analysis failed: %s", e, exc_info=True)
    task.outputs["result_json"] = json.dumps({"error": str(e)})  # noqa: F821
