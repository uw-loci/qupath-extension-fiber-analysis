"""
Appose task: compute the poolable window-feature bank on a single tile.

The window classifier's feature extractor, and the ONLY one. Training and
inference both come through here, because a separate training-time extractor
is the most likely way this feature silently produces a model that scores well
in the dialog and is wrong on the slide.

Cousin of density_tile.py: same segmentation, same lattice arithmetic, but it
writes the whole ``fiberlib.wfeatures`` bank as one (n_windows, n_features)
matrix instead of a handful of named grids. The caller tile-streams the slide
and assembles the per-tile matrices into a slide-wide one.

The cost of this script is O(tile pixels) and does NOT depend on the stride:
every feature in the bank is a fixed function of additive per-pixel
accumulators, so a lattice with four times as many windows costs the same as
the coarse one. That is what makes a fine classifier map affordable.

Inputs (Appose injected as variables):

  Image region
    region_image_path : str   tile PNG written by Java
    region_w/h        : int   tile pixel dimensions

  Segmentation (identical to density_tile.py)
    seg_channel, threshold_method, manual_threshold, project_threshold_norm,
    ridge_filter, sigma_min, sigma_max, sigma_step, min_fiber_area_px,
    invert_intensity, rolling_ball_radius

  Window grid
    pixel_size_um          : float
    window_size_um         : float
    window_overlap_percent : float
    tensor_sigma           : float, optional (default 4.0)
    grad_sigma             : float, optional; smoothing before the intensity
                             gradient, pixels

  Tiling
    crop_x/crop_y/crop_w/crop_h : int, optional. The tile's core inside the
                             read; the rest is halo, context for the filters
                             only. Default: the whole read.
    min_window_pixels      : int, optional; None uses 10% of the window area

  Intensity features
    include_intensity : bool, optional (default True). Uses the same channel
                        the segmentation was taken on, so the intensity
                        features describe whatever the user chose to segment
                        on rather than an arbitrary channel.

  Output
    output_npz_path : str

Outputs:
    task.outputs['ok'] = '1' on success, plus 'grid_h', 'grid_w', 'window_px',
    'stride_px', 'n_features' and 'feature_names' (comma-joined, column
    order). The npz holds:

      features     float32 (n_windows, n_features), windows in row-major
                   order (iy * grid_w + ix), NaN where undefined
      partial      int32   (n_windows,), 1 where the window was clamped to
                           the tile edge -- predict on these, never train
      area_px      float32 (n_windows,), the window's actual support
      grid_shape   int32   [grid_h, grid_w]
      window_px / stride_px / tile_w / tile_h : int32

    A sibling ``<npz>.names.json`` carries the column order too, so an npz
    left on disk after a failed run is still readable without the task.
"""

import json
import logging

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.window_features")


def opt_get(name, default):
    return globals().get(name, default)


try:
    from fiberlib import segmentation as seg, wfeatures as wf

    region_path = region_image_path  # required
    rw = int(region_w)
    rh = int(region_h)

    px_um = float(pixel_size_um)
    win_um = float(window_size_um)
    win_overlap_pct = float(window_overlap_percent)
    tensor_sig = float(opt_get("tensor_sigma", 4.0))
    min_win_px = opt_get("min_window_pixels", None)
    if min_win_px is not None:
        min_win_px = int(min_win_px)
    include_intensity = bool(opt_get("include_intensity", True))

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

    # ---- Segmentation ----
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

    # Intensity plane: the SAME channel the segmentation was taken on,
    # scaled to [0, 1], so "intensity_mean" describes what the user chose
    # to segment rather than a channel picked here. Deliberately the raw
    # channel, not the ridge-filtered or background-subtracted version --
    # those are shaped by the segmentation settings, so a model trained on
    # them would not transfer to a run with a different filter.
    image_scalar = None
    if include_intensity:
        try:
            image_scalar = seg.pick_channel(image_rgb, seg_chan)
        except Exception as ex:  # noqa: BLE001 - optional feature, never fatal
            logger.warning(
                "Intensity features unavailable (%s); continuing without them", ex
            )
            image_scalar = None

    window_px = max(2, int(round(win_um / px_um)))
    overlap = max(0.0, min(0.95, win_overlap_pct / 100.0))
    stride_px = max(1, int(round(window_px * (1.0 - overlap))))

    ih, iw = fiber_mask.shape
    cx = int(opt_get("crop_x", 0))
    cy = int(opt_get("crop_y", 0))
    crop = (cx, cy, int(opt_get("crop_w", iw - cx)), int(opt_get("crop_h", ih - cy)))
    grad_sig = float(opt_get("grad_sigma", 1.0))

    features, meta = wf.window_features(
        fiber_mask,
        window_px=window_px,
        stride_px=stride_px,
        image_scalar=image_scalar,
        pixel_size_um=px_um,
        min_pixels=min_win_px,
        tensor_sigma=tensor_sig,
        grad_sigma=grad_sig,
        crop=crop,
    )

    grid_h, grid_w = meta["grid_shape"]
    n_windows = int(grid_h) * int(grid_w)
    # Sorted so the column order is a property of the feature set, not of
    # dict insertion. A model's metadata records the order it was trained
    # with and refuses a matrix that does not match, but a stable order
    # means that check never fires for a trivial reason.
    names = sorted(features.keys())
    matrix = np.empty((n_windows, len(names)), dtype=np.float32)
    for j, name in enumerate(names):
        matrix[:, j] = np.asarray(features[name], dtype=np.float64).reshape(-1)

    partial = np.asarray(meta["partial"], dtype=bool).reshape(-1).astype(np.int32)
    area_px = (
        np.asarray(meta["area_px"], dtype=np.float64).reshape(-1).astype(np.float32)
    )

    np.savez_compressed(
        out_npz,
        features=matrix,
        partial=partial,
        area_px=area_px,
        grid_shape=np.array([int(grid_h), int(grid_w)], dtype=np.int32),
        window_px=np.int32(window_px),
        stride_px=np.int32(stride_px),
        tile_w=np.int32(crop[2]),
        tile_h=np.int32(crop[3]),
    )
    with open(out_npz + ".names.json", "w", encoding="ascii") as fh:
        json.dump(
            {"feature_names": names, "grid_shape": [int(grid_h), int(grid_w)]}, fh
        )

    logger.info(
        "window_features wrote %s (grid=%dx%d, %d features, %d partial windows, fiber=%d px)",
        out_npz,
        grid_h,
        grid_w,
        len(names),
        int(partial.sum()),
        int(fiber_mask.sum()),
    )

    task.outputs["ok"] = "1"
    task.outputs["grid_h"] = str(int(grid_h))
    task.outputs["grid_w"] = str(int(grid_w))
    task.outputs["window_px"] = str(int(window_px))
    task.outputs["stride_px"] = str(int(stride_px))
    task.outputs["n_features"] = str(len(names))
    task.outputs["feature_names"] = ",".join(names)

except Exception as e:
    logger.error("window_features failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
