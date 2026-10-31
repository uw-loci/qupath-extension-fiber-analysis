"""
Fit one GLCM gray range on sampled fiber pixels.

Each sample region is segmented exactly as a run would segment it, its analysis
zone applied, and the fiber pixels of the same scalar the texture step reads are
pooled into one histogram. The range comes from that histogram, once, so every
window and tile of the run shares it.

Inputs (Appose task.inputs, set as script globals):
    region_paths        list of region PNGs
    mask_paths          list of zone masks, index-aligned
    region_pixel_um     list of um/px, index-aligned
    method              a name from texture.RANGE_METHODS
    seg_channel, threshold_method, manual_threshold, manual_response_threshold,
    project_threshold_norm, ridge_filter, sigma_min_um, sigma_max_um,
    sigma_step_um, min_fiber_area_um2, invert_intensity, rolling_ball_um

Outputs:
    task.outputs['result_json'] -- JSON string:
      {"gray_min": int, "gray_max": int, "method": str, "full_scale": float,
       "n_regions_used": int, "n_fiber_pixels": int}
      or {"error": str}
"""

import json
import logging

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.glcm_range")

# One gray level of a 16-bit image, as in calibrate_threshold.
HIST_BINS = 65536


def _opt(name, default):
    return globals().get(name, default)


try:
    from fiberlib import pipeline
    from fiberlib import segmentation as seg
    from fiberlib import texture as tex

    hist = np.zeros(HIST_BINS, dtype=np.float64)
    n_used = 0
    scale = None
    paths = list(region_paths)  # noqa: F821
    masks = list(_opt("mask_paths", []) or [])
    pixel_um = list(region_pixel_um)  # noqa: F821
    pthr = _opt("project_threshold_norm", None)
    for i, p in enumerate(paths):
        arr = np.asarray(Image.open(p))
        rgb = np.stack([arr, arr, arr], axis=-1) if arr.ndim == 2 else arr[..., :3]
        px = float(pixel_um[i])
        mask = seg.segment_internal(
            image=rgb,
            channel=str(seg_channel),  # noqa: F821
            threshold_method=str(threshold_method).lower(),  # noqa: F821
            manual_threshold=int(manual_threshold),  # noqa: F821
            manual_response_threshold=float(_opt("manual_response_threshold", 0.1)),
            ridge_filter=str(ridge_filter).lower(),  # noqa: F821
            sigma_min=float(sigma_min_um) / px,  # noqa: F821
            sigma_max=float(sigma_max_um) / px,  # noqa: F821
            sigma_step=float(sigma_step_um) / px,  # noqa: F821
            min_fiber_area_px=int(round(float(min_fiber_area_um2) / (px * px))),  # noqa: F821
            invert_intensity=bool(_opt("invert_intensity", False)),
            rolling_ball_radius=int(round(float(_opt("rolling_ball_um", 0.0)) / px)),
            project_threshold_norm=None if pthr is None else float(pthr),
        )
        if i < len(masks) and masks[i]:
            zone = np.asarray(Image.open(masks[i]))
            zone = zone[..., 0] if zone.ndim == 3 else zone
            mask = mask & (zone > 127)
        scale = seg.full_scale(rgb)
        v = pipeline.rgb_to_value(rgb)[mask]
        if v.size:
            hist += np.histogram(v, bins=HIST_BINS, range=(0.0, 1.0))[0]
            n_used += 1
    lo, hi = tex.range_from_histogram(hist, str(method), scale)  # noqa: F821
    task.outputs["result_json"] = json.dumps(  # noqa: F821
        {
            "gray_min": lo,
            "gray_max": hi,
            "method": str(method),  # noqa: F821
            "full_scale": scale,
            "n_regions_used": n_used,
            "n_fiber_pixels": int(hist.sum()),
        }
    )
    logger.info("GLCM gray range %d to %d (%s), %d fiber px", lo, hi, method, int(hist.sum()))  # noqa: F821
except Exception as e:  # noqa: BLE001
    logger.exception("fit_glcm_range failed")
    task.outputs["result_json"] = json.dumps({"error": str(e)})  # noqa: F821
