"""
Moving-window grid + axial circular statistics on per-pixel fiber angles.

Lifted with light edits from
ppm_library/ppm_library/analysis/surface_analysis.py lines 953-1052
(compute_window_alignment). ppm_library is MIT-licensed (same lab,
https://github.com/uw-loci/ppm_library) -- this satisfies the
attribution requirement.

The function name was kept (``compute_windows``) to highlight that this
extension uses it as a generic moving-window aggregator -- straightness,
morphometric, and texture metrics all consume the same window grid for
their per-window arrays. The axial-stats outputs (mean_angle_deg,
order_parameter) are still produced so the user gets the PPM-comparable
alignment number for free.
"""
import numpy as np


def compute_windows(fiber_angles, fiber_mask, window_px, stride_px=None, min_pixels=None):
    """Aggregate per-pixel fiber orientations into a grid of windows.

    Each window summarises the fiber angles inside it using axial circular
    statistics (fibers are 180-periodic):

        c = mean(cos(2 * theta))
        s = mean(sin(2 * theta))
        OS  = sqrt(c^2 + s^2)            in [0, 1]
        theta_bar = 0.5 * atan2(s, c)    in [0, pi)

    Args:
        fiber_angles: (H, W) float, fiber orientations in degrees (0-180).
            NaN where invalid. Pass an all-NaN array if angles are unknown
            -- the n_pixels grid is still useful for the consumers.
        fiber_mask:  (H, W) bool, valid fiber pixels.
        window_px:   int, side length of each square window in pixels.
        stride_px:   int or None. None / equal to window_px = non-overlapping.
        min_pixels:  int or None. Minimum valid fiber pixels per window
                     to be considered non-empty. None = max(1, int(0.1*W^2)).

    Returns:
        dict with:
            'mean_angle_deg':  (Hw, Ww) float, dominant orientation 0..180
                               or NaN where the window had < min_pixels.
            'order_parameter': (Hw, Ww) float in [0, 1], NaN where empty.
            'n_pixels':        (Hw, Ww) int.
            'centers_px':      (Hw, Ww, 2) float, (x, y) centre of each
                               window in source image coordinates.
            'window_px':       int.
            'stride_px':       int.
            'min_pixels':      int.
            'grid_shape':      (Hw, Ww).
    """
    if window_px < 2:
        raise ValueError(f"window_px must be >= 2 (got {window_px})")
    if stride_px is None:
        stride_px = window_px
    if stride_px < 1:
        raise ValueError(f"stride_px must be >= 1 (got {stride_px})")
    if min_pixels is None:
        min_pixels = max(1, int(0.1 * window_px * window_px))

    h, w = fiber_angles.shape
    hw = max(0, (h - window_px) // stride_px + 1)
    ww = max(0, (w - window_px) // stride_px + 1)

    mean_angle = np.full((hw, ww), np.nan, dtype=np.float64)
    order_param = np.full((hw, ww), np.nan, dtype=np.float64)
    n_pixels = np.zeros((hw, ww), dtype=np.int32)
    centers = np.zeros((hw, ww, 2), dtype=np.float64)

    valid = np.asarray(fiber_mask, dtype=bool) & ~np.isnan(fiber_angles)
    two_theta = np.where(valid, np.deg2rad(2.0 * fiber_angles), 0.0)
    cos2 = np.where(valid, np.cos(two_theta), 0.0)
    sin2 = np.where(valid, np.sin(two_theta), 0.0)

    for iy in range(hw):
        y0 = iy * stride_px
        y1 = y0 + window_px
        for ix in range(ww):
            x0 = ix * stride_px
            x1 = x0 + window_px

            wv = valid[y0:y1, x0:x1]
            n = int(np.count_nonzero(wv))
            n_pixels[iy, ix] = n
            centers[iy, ix, 0] = x0 + window_px / 2.0
            centers[iy, ix, 1] = y0 + window_px / 2.0
            if n < min_pixels:
                continue

            c_bar = cos2[y0:y1, x0:x1][wv].mean()
            s_bar = sin2[y0:y1, x0:x1][wv].mean()
            order_param[iy, ix] = float(np.sqrt(c_bar * c_bar + s_bar * s_bar))
            mean_angle[iy, ix] = float(np.rad2deg(0.5 * np.arctan2(s_bar, c_bar)) % 180.0)

    return {
        "mean_angle_deg": mean_angle,
        "order_parameter": order_param,
        "n_pixels": n_pixels,
        "centers_px": centers,
        "window_px": int(window_px),
        "stride_px": int(stride_px),
        "min_pixels": int(min_pixels),
        "grid_shape": (hw, ww),
    }
