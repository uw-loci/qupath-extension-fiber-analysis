"""
Appose task: compute density-map scalars on a single tile.

Lean cousin of run_fiber_analysis.py -- runs the same per-window
computations the regular analysis does (coverage, ridge count, skeleton
length, branch points, mean orientation, order parameter) but skips the
zone dilation, the per-annotation scalars, every PNG overlay, and the
sidecar JSONs. The caller (FiberDensityMapWorkflow) tile-streams the
slide; this script is invoked once per tile and writes its outputs as
a small npz file the Java side reads back to accumulate into the
slide-wide density grid.

Two density modes via ``density_mode``:

  ``window`` (default): per-window aggregation at the user's chosen
    window-size + overlap, output shape ``(Hw, Ww)``. Cheap, blocky.

  ``pixel``: a window centred on every pixel via ``uniform_filter``, then
    sampled every ``pixel_step`` pixels (the pixel at offset
    ``pixel_step // 2`` of each step block). Output shape
    ``(ceil(core_h / step), ceil(core_w / step))``. ``ridge_count`` is NaN in
    this mode (per-pixel "distinct component count" has no cheap closed
    form; ``skeleton_length_um`` carries the same fiber-density information).
    A window that hangs off the SLIDE is normalised by the part inside it,
    not padded with zeros.

Tiling contract: Java reads each tile with a halo of context on every side
(clamped at the slide edge) and says which box of the read is the tile's
core (``crop_*``). Every filter runs on the whole read; only the core is
reported. That keeps a Gaussian, a rolling ball or a centred window from
seeing a tile edge as the end of the tissue.

Inputs (Appose 0.10+ injected as variables):

  Image region
    region_image_path : str   tile PNG written by Java
    region_w/h        : int   tile pixel dimensions

  Segmentation
    seg_channel              : str   'Value (HSV)', 'Raw intensity', etc.
    threshold_method         : str   'otsu' | 'triangle' | 'manual' | 'project_otsu'
    manual_threshold         : int   cut-off in the source image's own gray
                                     levels (used only when method == 'manual')
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

  Density mode
    density_mode             : str  'window' | 'pixel'  (default 'window')
    pixel_step               : int  output step in pixel mode (default 1)

  Tiling
    crop_x/crop_y/crop_w/crop_h : int  the tile's core inside the read image
                                       (default: the whole read)

  Threshold-free channels
    grad_sigma               : float  pixels; smoothing before the gradient

  Output
    output_npz_path          : str   where to write the per-window arrays

Outputs:
    task.outputs['ok'] = '1' on success; writes a .npz with these arrays
    (window mode: (Hw, Ww); pixel mode: (tile_h, tile_w)):
      fiber_coverage_percent  (float32, NaN where no fibers)
      hdm                     (float32)
      ridge_count             (float32; NaN in pixel mode)
      skeleton_length_um      (float32)
      branch_points           (float32)
      mean_angle_deg          (float32, NaN where ill-defined)
      order_parameter         (float32, NaN where ill-defined)
      signal_density          (float32, fraction of full scale; no threshold)
      intensity_coherence     (float32, [0, 1]; no threshold; NaN where flat)
    plus metadata:
      window_px (int), stride_px (int), grid_shape (int[2]),
      tile_w (int), tile_h (int), density_mode (uint8 array)
"""

import logging
import os

import numpy as np
from PIL import Image
from scipy import ndimage

logger = logging.getLogger("fiber.density_tile")


def opt_get(name, default):
    return globals().get(name, default)


def _run_window_mode(
    out_npz,
    fiber_mask,
    skeleton,
    fiber_angles,
    signal,
    tensor,
    window_px,
    stride_px,
    px_um,
    rw,
    rh,
    win_mod,
    straight_mod,
    morph_mod,
    wfeat_mod,
    inten_mod,
):
    """Compute and write per-window density arrays. Returns (Hw, Ww)."""
    grid = win_mod.compute_windows(
        fiber_angles, fiber_mask, window_px, stride_px=stride_px
    )
    Hw, Ww = grid["grid_shape"]

    # Threshold-free channels, pooled on the same lattice. BoxPooler appends
    # one clamped window at the far edge; compute_windows does not, so slice.
    pooler = wfeat_mod.BoxPooler(fiber_mask.shape, window_px, stride_px)
    area = float(window_px * window_px)
    signal_density = (pooler.pool(signal) / area)[:Hw, :Ww]
    intensity_coherence = inten_mod.coherence(
        pooler.pool(tensor["jxx"])[:Hw, :Ww],
        pooler.pool(tensor["jyy"])[:Hw, :Ww],
        pooler.pool(tensor["jxy"])[:Hw, :Ww],
        n=area,
    )

    n_pix = grid["n_pixels"]
    window_area_px = float(window_px * window_px)
    if window_area_px > 0:
        fiber_coverage_percent = np.where(
            n_pix > 0, 100.0 * n_pix.astype(np.float64) / window_area_px, np.nan
        )
        hdm = n_pix.astype(np.float64) / window_area_px
    else:
        fiber_coverage_percent = np.full((Hw, Ww), np.nan, dtype=np.float64)
        hdm = np.full((Hw, Ww), np.nan, dtype=np.float64)

    tort = straight_mod.compute_skeleton_tortuosity(
        fiber_mask,
        window_grid=grid,
        min_branch_px=max(1, int(round(2.0 / px_um))),
    )
    ridge_count = tort.get("per_window_n_fibers")
    if ridge_count is None:
        ridge_count = np.full((Hw, Ww), 0.0, dtype=np.float64)

    pw_morph = morph_mod.per_window_morphometrics(
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
        total_length_px * px_um
        if total_length_px is not None
        else np.zeros((Hw, Ww), dtype=np.float64)
    )

    mean_angle_deg = grid.get("mean_angle_deg")
    if mean_angle_deg is None:
        mean_angle_deg = np.full((Hw, Ww), np.nan, dtype=np.float64)
    order_parameter = grid.get("order_parameter")
    if order_parameter is None:
        order_parameter = np.full((Hw, Ww), np.nan, dtype=np.float64)

    np.savez_compressed(
        out_npz,
        fiber_coverage_percent=fiber_coverage_percent.astype(np.float32),
        hdm=hdm.astype(np.float32),
        ridge_count=ridge_count.astype(np.float32),
        skeleton_length_um=skeleton_length_um.astype(np.float32),
        branch_points=(
            branch_points.astype(np.float32)
            if branch_points is not None
            else np.zeros((Hw, Ww), dtype=np.float32)
        ),
        mean_angle_deg=mean_angle_deg.astype(np.float32),
        order_parameter=order_parameter.astype(np.float32),
        signal_density=signal_density.astype(np.float32),
        intensity_coherence=intensity_coherence.astype(np.float32),
        window_px=np.int32(window_px),
        stride_px=np.int32(stride_px),
        grid_shape=np.array([Hw, Ww], dtype=np.int32),
        tile_w=np.int32(rw),
        tile_h=np.int32(rh),
        density_mode=np.array([ord(c) for c in "window"], dtype=np.uint8),
    )
    logger.info(
        "density_tile (window) wrote %s (grid=%dx%d, fiber=%d px)",
        out_npz,
        Hw,
        Ww,
        int(fiber_mask.sum()),
    )
    return Hw, Ww


def _box_mean(plane, size):
    """Mean over a centred box, over the part of the box inside the read."""
    plane = np.asarray(plane, dtype=np.float64)
    num = ndimage.uniform_filter(plane, size=size, mode="constant", cval=0.0)
    den = ndimage.uniform_filter(
        np.ones_like(plane), size=size, mode="constant", cval=0.0
    )
    return num / np.maximum(den, 1e-12)


def _run_pixel_mode(
    out_npz,
    fiber_mask,
    skeleton,
    fiber_angles,
    signal,
    tensor,
    window_px,
    px_um,
    crop,
    step,
    straight_mod,
    inten_mod,
):
    """Centred-window density on the whole read, reported on the core every `step` px."""
    cx, cy, cw, ch = crop
    box_area = float(window_px * window_px)
    rows = np.minimum(np.arange(0, ch, step) + step // 2, ch - 1) + cy
    cols = np.minimum(np.arange(0, cw, step) + step // 2, cw - 1) + cx

    def sample(a):
        return np.asarray(a)[np.ix_(rows, cols)]

    mask_f = fiber_mask.astype(np.float64)
    coverage_frac = sample(_box_mean(mask_f, window_px))
    fiber_coverage_percent = np.where(coverage_frac > 0, 100.0 * coverage_frac, np.nan)
    hdm = coverage_frac

    # Counts are scaled to a full window, so an edge window reads the same
    # density as an interior one rather than a smaller count.
    skeleton_length_um = (
        sample(_box_mean(skeleton.astype(np.float64), window_px)) * box_area * px_um
    )
    junctions = straight_mod.junction_mask(skeleton).astype(np.float64)
    branch_count = sample(_box_mean(junctions, window_px)) * box_area

    theta = np.where(np.isnan(fiber_angles), 0.0, np.deg2rad(fiber_angles))
    cos2 = np.where(mask_f > 0, np.cos(2.0 * theta), 0.0)
    sin2 = np.where(mask_f > 0, np.sin(2.0 * theta), 0.0)
    cos_mean = sample(_box_mean(cos2, window_px))
    sin_mean = sample(_box_mean(sin2, window_px))
    valid = coverage_frac > 0
    cos_norm = np.where(valid, cos_mean / np.maximum(coverage_frac, 1e-12), 0.0)
    sin_norm = np.where(valid, sin_mean / np.maximum(coverage_frac, 1e-12), 0.0)
    mean_angle_deg = np.where(
        valid, np.rad2deg(0.5 * np.arctan2(sin_norm, cos_norm)) % 180.0, np.nan
    )
    order_parameter = np.where(valid, np.hypot(cos_norm, sin_norm), np.nan)

    signal_density = sample(_box_mean(signal, window_px))
    intensity_coherence = inten_mod.coherence(
        sample(_box_mean(tensor["jxx"], window_px)),
        sample(_box_mean(tensor["jyy"], window_px)),
        sample(_box_mean(tensor["jxy"], window_px)),
    )

    out_h, out_w = len(rows), len(cols)
    ridge_count = np.full((out_h, out_w), np.nan, dtype=np.float32)

    np.savez_compressed(
        out_npz,
        fiber_coverage_percent=fiber_coverage_percent.astype(np.float32),
        hdm=hdm.astype(np.float32),
        ridge_count=ridge_count,
        skeleton_length_um=skeleton_length_um.astype(np.float32),
        branch_points=branch_count.astype(np.float32),
        mean_angle_deg=mean_angle_deg.astype(np.float32),
        order_parameter=order_parameter.astype(np.float32),
        signal_density=signal_density.astype(np.float32),
        intensity_coherence=intensity_coherence.astype(np.float32),
        window_px=np.int32(window_px),
        stride_px=np.int32(step),
        grid_shape=np.array([out_h, out_w], dtype=np.int32),
        tile_w=np.int32(cw),
        tile_h=np.int32(ch),
        density_mode=np.array([ord(c) for c in "pixel"], dtype=np.uint8),
    )
    logger.info(
        "density_tile (pixel) wrote %s (core=%dx%d, step=%d, window_px=%d, fiber=%d px)",
        out_npz,
        cw,
        ch,
        step,
        window_px,
        int(fiber_mask.sum()),
    )
    return out_h, out_w


try:
    from fiberlib import (
        segmentation as seg,
        windows as win,
        straightness as straight,
        morphometrics as morph,
        wfeatures as wfeat,
        intensity as inten,
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
    density_mode = str(opt_get("density_mode", "window")).lower()
    if density_mode not in ("window", "pixel"):
        raise ValueError(
            f"density_mode must be 'window' or 'pixel', got '{density_mode}'"
        )

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
    skeleton, fiber_angles = straight.skeletonize_and_tangents(fiber_mask)

    # ---- Threshold-free planes, from the same intensity segmentation saw ----
    grad_sigma = float(opt_get("grad_sigma", inten.DEFAULT_GRAD_SIGMA))
    signal = inten.signal_scalar(image_rgb, seg_chan, invert, rolling_ball)
    tensor = inten.tensor_planes(signal, grad_sigma)

    # ---- The tile's core inside the halo-padded read ----
    ih, iw = fiber_mask.shape
    cx = int(opt_get("crop_x", 0))
    cy = int(opt_get("crop_y", 0))
    cw = int(opt_get("crop_w", iw - cx))
    ch = int(opt_get("crop_h", ih - cy))
    if cx < 0 or cy < 0 or cw <= 0 or ch <= 0 or cx + cw > iw or cy + ch > ih:
        raise ValueError(f"crop ({cx},{cy},{cw},{ch}) lies outside the {iw}x{ih} read")

    # ---- Window grid ----
    window_px = max(2, int(round(win_um / px_um)))
    overlap = max(0.0, min(0.95, win_overlap_pct / 100.0))
    stride_px = max(1, int(round(window_px * (1.0 - overlap))))

    if density_mode == "pixel":
        step = max(1, int(opt_get("pixel_step", 1)))
        out_h, out_w = _run_pixel_mode(
            out_npz,
            fiber_mask,
            skeleton,
            fiber_angles,
            signal,
            tensor,
            window_px,
            px_um,
            (cx, cy, cw, ch),
            step,
            straight,
            inten,
        )
        out_stride = step
    else:
        core = (slice(cy, cy + ch), slice(cx, cx + cw))
        out_h, out_w = _run_window_mode(
            out_npz,
            fiber_mask[core],
            skeleton[core],
            fiber_angles[core],
            signal[core],
            {k: v[core] for k, v in tensor.items()},
            window_px,
            stride_px,
            px_um,
            cw,
            ch,
            win,
            straight,
            morph,
            wfeat,
            inten,
        )
        out_stride = stride_px

    task.outputs["ok"] = "1"
    task.outputs["grid_h"] = str(int(out_h))
    task.outputs["grid_w"] = str(int(out_w))
    task.outputs["window_px"] = str(int(window_px))
    task.outputs["stride_px"] = str(int(out_stride))
    task.outputs["density_mode"] = density_mode

except Exception as e:
    logger.error("density_tile failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
