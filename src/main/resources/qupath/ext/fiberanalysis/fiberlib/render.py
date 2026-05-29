"""
RGBA PNG renderers for fiber-analysis overlays.

Renderers:

    render_fiber_mask(mask, path)
        -- Greys colormap; alpha = mask.
    render_window_heatmap(per_window, window_grid, path, H, W, cmap_name)
        -- upsamples a (Hw, Ww) array to (H, W) at the window grid's stride,
           alpha-masks cells with NaN, writes RGBA PNG.
    render_morphometrics_overlay(window_grid, path, H, W)
        -- per-window HDM heatmap (viridis); the doc-promised
           ``morphometrics_overlay.png`` (Scientist-tester M2 schema reconcile).

Modelled on ppm_library's render_window_alignment_overlay layout
(surface_analysis.py:1054+) with simplified per-cell upsampling.
"""
import numpy as np
from matplotlib import cm
from PIL import Image


def render_fiber_mask(mask, path):
    """Save a binary fiber mask as a translucent magenta RGBA overlay.

    Colour choice: magenta is uncommon in standard biological staining
    (picrosirius reds, fluorescence green/red, birefringence rainbow), so it
    contrasts cleanly against every source image we ship for. v0.1 used a
    light grey at alpha 160 which was effectively invisible on bright
    fluorescence or birefringence images -- users could not tell the overlay
    had loaded at all ("Show fiber mask does nothing" -- 2026-05-27).
    """
    mask_bool = mask.astype(bool)
    rgba = np.zeros((mask_bool.shape[0], mask_bool.shape[1], 4), dtype=np.uint8)
    rgba[..., 0] = 255  # magenta
    rgba[..., 1] = 0
    rgba[..., 2] = 255
    rgba[..., 3] = np.where(mask_bool, 200, 0).astype(np.uint8)
    Image.fromarray(rgba, mode="RGBA").save(path)


def render_window_heatmap(per_window, window_grid, path, image_h, image_w, cmap_name="viridis"):
    """Upsample a per-window scalar grid into a heatmap PNG.

    Args:
        per_window:  (Hw, Ww) array of float metric values; NaN = transparent.
        window_grid: dict from windows.compute_windows (used for stride / size).
        path:        output PNG path.
        image_h/w:   output image dimensions (typically the region's H/W).
        cmap_name:   matplotlib colormap name (viridis / magma / etc.).
    """
    arr = np.asarray(per_window, dtype=np.float32)
    Hw, Ww = arr.shape
    window_px = int(window_grid["window_px"])
    stride_px = int(window_grid["stride_px"])

    # Normalise to [0, 1] for the colormap, ignoring NaN.
    valid = ~np.isnan(arr)
    if valid.any():
        vmin = float(np.nanmin(arr))
        vmax = float(np.nanmax(arr))
        rng = max(vmax - vmin, 1e-12)
        normed = (arr - vmin) / rng
    else:
        normed = np.zeros_like(arr)

    cmap = cm.get_cmap(cmap_name)
    rgba_grid = (cmap(normed) * 255.0).astype(np.uint8)
    rgba_grid[..., 3] = np.where(valid, 180, 0).astype(np.uint8)

    out = np.zeros((image_h, image_w, 4), dtype=np.uint8)
    for iy in range(Hw):
        y0 = iy * stride_px
        y1 = min(image_h, y0 + window_px)
        if y1 <= y0:
            continue
        for ix in range(Ww):
            x0 = ix * stride_px
            x1 = min(image_w, x0 + window_px)
            if x1 <= x0:
                continue
            out[y0:y1, x0:x1, :] = rgba_grid[iy, ix, :]

    Image.fromarray(out, mode="RGBA").save(path)


def render_morphometrics_overlay(window_grid, path, image_h, image_w, zone_area_px=None):
    """Render a per-window HDM heatmap and write to ``path``.

    HDM = ``n_pixels`` / window_area_px (or zone_area_px if supplied) per
    window. Viridis colormap, alpha-masked where ``n_pixels`` is missing.

    Provides the ``morphometrics_overlay.png`` the user guide promises but the
    v0.1.0 pipeline never wrote (Scientist-tester M2 schema reconcile).
    """
    n_pix = window_grid.get("n_pixels")
    if n_pix is None:
        # Nothing to render -- skip silently. Callers gate on this.
        return
    window_px = int(window_grid["window_px"])
    denom = float(zone_area_px) if zone_area_px and zone_area_px > 0 else float(window_px * window_px)
    if denom <= 0:
        return
    arr = np.asarray(n_pix, dtype=np.float32) / float(window_px * window_px)
    # Mask cells that contributed zero pixels (so blank windows are
    # transparent rather than 0-valued viridis dark-purple).
    arr = np.where(np.asarray(n_pix) > 0, arr, np.nan)
    render_window_heatmap(arr, window_grid, path, image_h, image_w, cmap_name="viridis")
