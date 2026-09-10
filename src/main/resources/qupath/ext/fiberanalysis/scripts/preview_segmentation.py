"""
Appose task: run only the segmentation half of the pipeline on a small region
and write the resulting fiber mask as a magenta-overlay PNG. Used by the
Section 2 "Preview segmentation..." button so users can tune sigma / threshold
/ invert / rolling-ball without waiting for a full per-annotation run.

Inputs (injected by Appose as Python variables):
    region_image_path     : str        absolute path to the small region PNG
                                        the Java side just wrote
    seg_channel           : str        same as the main run
    threshold_method      : str        'otsu' | 'triangle' | 'manual' | 'project_otsu'
    manual_threshold      : int        cut-off in the source image's own gray levels
                                        (only used when threshold_method == 'manual')
    project_threshold_norm: float|None [0,1] calibrated threshold (only used when
                                        threshold_method == 'project_otsu')
    ridge_filter          : str        'none' | 'frangi' | 'sato' | 'meijering'
    sigma_min, sigma_max, sigma_step  : float (PIXEL units -- Java converts um->px)
    min_fiber_area_px     : int        (PIXEL units -- Java converts um^2->px^2)
    invert_intensity      : bool
    rolling_ball_radius   : int        (PIXEL units -- Java converts um->px)
    output_overlay_path   : str        where to write the magenta-overlay PNG

Outputs:
    task.outputs['result_json'] -- JSON string:
      {
        "fiber_pixels": int,        # mask pixel count
        "total_pixels": int,        # region pixel count
        "coverage_percent": float,  # fiber_pixels / total_pixels * 100
        "ms_segment": float,
        "ms_render": float
      }
    or {"error": "..."} on failure.
"""
import json
import logging
import time
import traceback

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.appose.preview")


def _opt(name, default):
    return globals().get(name, default)


try:
    from fiberlib import segmentation as seg
    from fiberlib import render as rndr

    t0 = time.time()
    pil = Image.open(region_image_path)
    arr = np.asarray(pil)
    if arr.ndim == 2:
        image_rgb = np.stack([arr, arr, arr], axis=-1)
    elif arr.ndim == 3 and arr.shape[2] >= 3:
        image_rgb = arr[..., :3]
    else:
        raise ValueError(f"Unexpected image shape: {arr.shape}")

    invert = bool(_opt("invert_intensity", False))
    rb = int(_opt("rolling_ball_radius", 0))
    pthr = _opt("project_threshold_norm", None)
    if pthr is not None:
        try:
            pthr = float(pthr)
        except (TypeError, ValueError):
            pthr = None

    fiber_mask = seg.segment_internal(
        image=image_rgb,
        channel=str(seg_channel),
        threshold_method=str(threshold_method).lower(),
        manual_threshold=int(manual_threshold),
        ridge_filter=str(ridge_filter).lower(),
        sigma_min=float(sigma_min),
        sigma_max=float(sigma_max),
        sigma_step=float(sigma_step),
        min_fiber_area_px=int(min_fiber_area_px),
        invert_intensity=invert,
        rolling_ball_radius=rb,
        project_threshold_norm=pthr,
    )
    t_seg = (time.time() - t0) * 1000.0

    t1 = time.time()
    # Render the same magenta overlay the real run produces so the preview
    # visually matches a real "Show fiber mask" overlay.
    rndr.render_fiber_mask(fiber_mask, str(output_overlay_path))
    t_render = (time.time() - t1) * 1000.0

    n_fiber = int(fiber_mask.sum())
    n_total = int(fiber_mask.size)
    coverage = 100.0 * n_fiber / n_total if n_total > 0 else 0.0
    task.outputs["result_json"] = json.dumps({
        "fiber_pixels": n_fiber,
        "total_pixels": n_total,
        "coverage_percent": coverage,
        "ms_segment": t_seg,
        "ms_render": t_render,
    })
    logger.info(
        "Preview: mask=%d/%d (%.2f%%), segment=%.0f ms, render=%.0f ms",
        n_fiber, n_total, coverage, t_seg, t_render,
    )

except Exception as e:
    logger.error("Preview segmentation failed: %s\n%s", e, traceback.format_exc())
    task.outputs["result_json"] = json.dumps({"error": str(e)})
