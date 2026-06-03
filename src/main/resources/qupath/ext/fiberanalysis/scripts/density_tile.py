"""
Appose task: compute per-window density-map scalars on a single tile.

Lean cousin of run_fiber_analysis.py -- runs the same per-window
computations the regular analysis does (coverage, ridge count, skeleton
length, branch points, mean orientation, order parameter) but skips the
zone dilation, the per-annotation scalars, every PNG overlay, and the
sidecar JSONs. The caller (FiberDensityMapWorkflow) tile-streams the
slide; this script is invoked once per tile and writes its outputs as
a small npz file the Java side reads back to accumulate into the
slide-wide density grid.

Inputs (Appose 0.10+ injected as variables):

  Image region
    region_image_path : str   tile PNG written by Java
    region_w/h        : int   tile pixel dimensions

  Segmentation
    seg_channel              : str   'Value (HSV)', 'Raw intensity', etc.
    threshold_method         : str   'otsu' | 'triangle' | 'manual' | 'project_otsu'
    manual_threshold         : int   0-255 (used only when method == 'manual')
    project_threshold_norm   : float|None   threshold in normalised [0,1] space
                                              for the project_otsu method
    ridge_filter             : str   'none' | 'frangi' | 'sato' | 'meijering'
    sigma_min/_max/_step     : float (pixels)
    min_fiber_area_px        : int
    invert_intensity         : bool
    rolling_ball_radius      : int  (pixels; 0 disables)

  Window grid
    pixel_size_um            : float
    window_size_um           : float
    window_overlap_percent   : float

  Output
    output_npz_path          : str   where to write the per-window arrays

Outputs:
    task.outputs['ok'] = '1' on success; writes a .npz with these arrays:
      fiber_coverage_percent[Hw, Ww]  (float32, NaN where no fibers in window)
      hdm[Hw, Ww]                      (float32)
      ridge_count[Hw, Ww]              (float32)
      skeleton_length_um[Hw, Ww]       (float32)
      branch_points[Hw, Ww]            (float32)
      mean_angle_deg[Hw, Ww]           (float32, NaN where ill-defined)
      order_parameter[Hw, Ww]          (float32, NaN where ill-defined)
    plus metadata:
      window_px (int), stride_px (int), grid_shape (int[2]),
      tile_w (int), tile_h (int)

The set of computed channels is fixed for v1 -- a follow-up iteration
will let the user pick which channels to write per project. Keeping the
set small here also keeps the sidecar a manageable size.
"""
import logging
import os

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.density_tile")


def opt_get(name, default):
    return globals().get(name, default)


try:
    from fiberlib import (
        segmentation as seg,
        windows as win,
        straightness as straight,
        morphometrics as morph,
    )

    region_path = region_image_path  # required
    rw = int(region_w)
    rh = int(region_h)

    px_um = float(pixel_size_um)
    win_um = float(window_size_um)
    win_overlap_pct = float(window_overlap_percent)

    seg_chan = str(seg_channel)
    thr_method = str(threshold_method).lower()
    manual_thr = int(manual_threshold)
    rf = str(ridge_filter).lower()
    smin = float(sigma_min)
    smax = float(sigma_max)
    sstep = float(sigma_step)
    min_area = int(min_fiber_area_px)
    invert = bool(opt_get("invert_intensity", False))
    rolling_ball = int(opt_get("rolling_ball_radius", 0))
    project_thr_norm = opt_get("project_threshold_norm", None)
    if project_thr_norm is not None:
        try:
            project_thr_norm = float(project_thr_norm)
        except (TypeError, ValueError):
            project_thr_norm = None

    out_npz = str(output_npz_path)

    # ---- Load tile ----
    pil = Image.open(region_path)
    image = np.asarray(pil)
    if image.ndim == 2:
        image_rgb = np.stack([image, image, image], axis=-1)
    elif image.ndim == 3 and image.shape[2] >= 3:
        image_rgb = image[..., :3]
    else:
        raise ValueError(f"Unexpected tile shape: {image.shape}")

    # ---- Segmentation (whole-tile; no zone dilation in density mode) ----
    fiber_mask = seg.segment_internal(
        image=image_rgb,
        channel=seg_chan,
        threshold_method=thr_method,
        manual_threshold=manual_thr,
        ridge_filter=rf,
        sigma_min=smin,
        sigma_max=smax,
        sigma_step=sstep,
        min_fiber_area_px=min_area,
        invert_intensity=invert,
        rolling_ball_radius=rolling_ball,
        project_threshold_norm=project_thr_norm,
    )

    # ---- Skeleton + per-pixel orientation ----
    # analysis_mask is the entire fiber mask (no zone gating in density mode).
    skeleton, fiber_angles = straight.skeletonize_and_tangents(fiber_mask)

    # ---- Window grid ----
    window_px = max(2, int(round(win_um / px_um)))
    overlap = max(0.0, min(0.95, win_overlap_pct / 100.0))
    stride_px = max(1, int(round(window_px * (1.0 - overlap))))
    grid = win.compute_windows(fiber_angles, fiber_mask, window_px, stride_px=stride_px)
    Hw, Ww = grid["grid_shape"]

    # ---- Derive per-window quantities ----
    n_pix = grid["n_pixels"]
    window_area_px = float(window_px * window_px)

    fiber_coverage_percent = (
        np.where(n_pix > 0, 100.0 * n_pix.astype(np.float64) / window_area_px, np.nan)
        if window_area_px > 0 else np.full((Hw, Ww), np.nan, dtype=np.float64)
    )
    hdm = (
        n_pix.astype(np.float64) / window_area_px
        if window_area_px > 0 else np.full((Hw, Ww), np.nan, dtype=np.float64)
    )

    # Tortuosity (also yields ridge count per window).
    tort = straight.compute_skeleton_tortuosity(
        fiber_mask,
        window_grid=grid,
        min_branch_px=max(1, int(round(2.0 / px_um))),
    )
    ridge_count = tort.get("per_window_n_fibers")
    if ridge_count is None:
        ridge_count = np.full((Hw, Ww), 0.0, dtype=np.float64)

    # Skeleton length + branch points via the per-window morph aggregator.
    pw_morph = morph.per_window_morphometrics(
        skeleton=skeleton,
        fiber_mask=fiber_mask,
        window_px=window_px,
        stride_px=stride_px,
        grid_shape=(Hw, Ww),
        flags={"length": True, "branch": True},
    )
    total_length_px = pw_morph.get("total_length_px")
    branch_points = pw_morph.get("branch_points")
    skeleton_length_um = (
        total_length_px * px_um if total_length_px is not None else np.zeros((Hw, Ww), dtype=np.float64)
    )

    mean_angle_deg = grid.get("mean_angle_deg")
    if mean_angle_deg is None:
        mean_angle_deg = np.full((Hw, Ww), np.nan, dtype=np.float64)
    order_parameter = grid.get("order_parameter")
    if order_parameter is None:
        order_parameter = np.full((Hw, Ww), np.nan, dtype=np.float64)

    # ---- Save npz ----
    np.savez_compressed(
        out_npz,
        fiber_coverage_percent=fiber_coverage_percent.astype(np.float32),
        hdm=hdm.astype(np.float32),
        ridge_count=ridge_count.astype(np.float32) if ridge_count is not None else np.zeros((Hw, Ww), dtype=np.float32),
        skeleton_length_um=skeleton_length_um.astype(np.float32),
        branch_points=(branch_points.astype(np.float32) if branch_points is not None else np.zeros((Hw, Ww), dtype=np.float32)),
        mean_angle_deg=mean_angle_deg.astype(np.float32),
        order_parameter=order_parameter.astype(np.float32),
        window_px=np.int32(window_px),
        stride_px=np.int32(stride_px),
        grid_shape=np.array([Hw, Ww], dtype=np.int32),
        tile_w=np.int32(rw),
        tile_h=np.int32(rh),
    )
    logger.info("density_tile wrote %s (grid=%dx%d, fiber=%d px)", out_npz, Hw, Ww, int(fiber_mask.sum()))

    task.outputs["ok"] = "1"
    task.outputs["grid_h"] = str(int(Hw))
    task.outputs["grid_w"] = str(int(Ww))
    task.outputs["window_px"] = str(int(window_px))
    task.outputs["stride_px"] = str(int(stride_px))

except Exception as e:
    logger.error("density_tile failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
