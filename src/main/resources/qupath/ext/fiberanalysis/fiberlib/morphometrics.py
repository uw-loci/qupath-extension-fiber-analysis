"""
TWOMBLI-derived morphometric metrics, natively re-implemented (no FIJI
plugin port).

Reference for the method panel (HDM, branch/endpoint counts, total length,
curvature, lacunarity, fractal dimension, gap analysis):

    Wershof, E. et al. (2021). A FIJI macro for quantifying pattern in
    extracellular matrix. Molecular Systems Biology (TWOMBLI).

This is a clean-room re-implementation from the published method, not a
port of the unlicensed .ijm macro. Numerical agreement with TWOMBLI is
expected only to the level of "same order of magnitude" -- different
implementations of fractal dimension and lacunarity differ at the 1-5%
level depending on box-size choice and exact regression strategy.
"""
import logging
import math

import numpy as np
from scipy import ndimage
from skimage import morphology as skmorph

logger = logging.getLogger("fiber.morph")


# ---- HDM -------------------------------------------------------------------

def hdm(fiber_mask, zone_mask):
    """High-Density-Matrix coverage: fiber pixels / zone pixels in [0, 1]."""
    z = int(zone_mask.sum())
    if z == 0:
        return float("nan")
    return float((fiber_mask & zone_mask).sum()) / float(z)


# ---- length, branch / endpoint counts --------------------------------------

def total_length_px(skeleton):
    """Pixel count of the skeleton."""
    return int(np.count_nonzero(skeleton))


def branch_endpoint_counts(skeleton):
    """Return (branch_count, endpoint_count) from 8-connected neighbour counts.

    A skeleton pixel is an endpoint iff it has exactly 1 neighbour,
    a branch point iff it has >= 3 neighbours.
    """
    sk = skeleton.astype(bool)
    if not sk.any():
        return 0, 0
    # neighbour count = convolution with 3x3 ones minus self
    kernel = np.ones((3, 3), dtype=np.int32)
    nb = ndimage.convolve(sk.astype(np.int32), kernel, mode="constant", cval=0)
    nb_only = nb - sk.astype(np.int32)  # exclude self
    endpoints = int(((nb_only == 1) & sk).sum())
    branches = int(((nb_only >= 3) & sk).sum())
    return branches, endpoints


# ---- curvature -------------------------------------------------------------

def mean_curvature(skeleton, segment_len_px=4):
    """Mean absolute tangent rotation along skeleton segments.

    For each maximal skeleton segment (endpoint-to-endpoint or
    endpoint-to-branch), compute the tangent at the segment start (averaged
    over the first ``segment_len_px`` pixels) and at the segment end
    (averaged over the last ``segment_len_px`` pixels), take their absolute
    angular difference (wrapped into [0, pi/2] to handle the 180-flip
    ambiguity), divide by the arc length of the segment. Average across
    segments.

    Returns a single scalar (radians per pixel). The caller can divide by
    pixel_size_um if a per-um value is desired.

    Scientist-tester top-questionable: the v0.1.0 implementation computed a
    per-pixel gradient-difference proxy that ignored ``segment_len_px``
    entirely and did not match the docstring claim of "per-segment tangent
    rotation". This rewrite walks the skeleton path proper.
    """
    sk = skeleton.astype(bool)
    if not sk.any():
        return float("nan")

    # Lazy import to avoid a circular import (straightness imports
    # morphometrics in test scripts).
    from . import straightness as _straight  # noqa: WPS433 -- local import is intentional

    seg_len = max(2, int(segment_len_px))

    rotations_per_unit = []
    for path in _straight.walk_segments(sk):
        n = len(path)
        if n < 2:
            continue
        pts = np.asarray(path, dtype=np.float64)
        # Tangent at start: vector from pts[0] -> pts[min(seg_len, n-1)].
        end_start_idx = min(seg_len, n - 1)
        v_start = pts[end_start_idx] - pts[0]
        # Tangent at end: vector from pts[max(0, n-1-seg_len)] -> pts[-1].
        start_end_idx = max(0, n - 1 - seg_len)
        v_end = pts[-1] - pts[start_end_idx]
        if np.linalg.norm(v_start) == 0 or np.linalg.norm(v_end) == 0:
            continue
        ang_start = math.atan2(v_start[1], v_start[0])
        ang_end = math.atan2(v_end[1], v_end[0])
        dtheta = abs(ang_end - ang_start)
        # Wrap into [0, pi/2] for the 180-flip ambiguity (skeleton tangent
        # is direction-agnostic).
        dtheta = min(dtheta, 2 * math.pi - dtheta)
        dtheta = min(dtheta, math.pi - dtheta)
        # Arc length: sum of per-step Euclidean distances.
        diffs = np.diff(pts, axis=0)
        arc = float(np.sum(np.linalg.norm(diffs, axis=1)))
        if arc <= 0:
            continue
        rotations_per_unit.append(dtheta / arc)

    if not rotations_per_unit:
        return float("nan")
    return float(np.mean(rotations_per_unit))


# ---- fractal dimension (box-counting) --------------------------------------

def fractal_dimension(skeleton, box_sizes_px):
    """Box-counting fractal dimension of the skeleton.

    Fits log(N(eps)) = -D * log(eps) + c by least squares.
    Returns D (expected in (1.0, 2.0) for collagen networks).
    """
    sk = skeleton.astype(bool)
    if not sk.any():
        return float("nan")
    sizes = sorted({int(s) for s in box_sizes_px if int(s) >= 1})
    h, w = sk.shape
    log_eps = []
    log_n = []
    for s in sizes:
        if s > min(h, w):
            continue
        # Number of boxes covering the skeleton: reshape into s-blocks.
        h_use = (h // s) * s
        w_use = (w // s) * s
        if h_use == 0 or w_use == 0:
            continue
        blk = sk[:h_use, :w_use].reshape(h_use // s, s, w_use // s, s)
        per_block_any = blk.any(axis=(1, 3))
        n = int(per_block_any.sum())
        if n == 0:
            continue
        log_eps.append(math.log(1.0 / s))
        log_n.append(math.log(n))
    if len(log_eps) < 2:
        return float("nan")
    slope, _ = np.polyfit(log_eps, log_n, 1)
    return float(slope)


# ---- lacunarity (gliding-box, Allain-Cloitre) ------------------------------

def lacunarity(fiber_mask, box_sizes_px):
    """Gliding-box lacunarity at multiple box sizes.

    Returns a dict {box_size: lacunarity_value}. Lacunarity L(r) = 1 + Var(M)/Mean(M)^2
    where M is the number of foreground pixels in an r-by-r gliding box.
    """
    out = {}
    arr = fiber_mask.astype(np.float32)
    h, w = arr.shape
    for s in sorted({int(b) for b in box_sizes_px if int(b) >= 1}):
        if s > min(h, w):
            continue
        # Sum within each gliding box via a uniform_filter trick.
        # uniform_filter returns the *mean*; multiply by area for the sum.
        means = ndimage.uniform_filter(arr, size=s, mode="constant", cval=0.0)
        sums = means * (s * s)
        # Only valid (fully-inside) box positions:
        valid = sums[s - 1 : h, s - 1 : w]
        m = valid.mean()
        if m <= 0:
            out[s] = float("nan")
            continue
        v = valid.var()
        out[s] = float(1.0 + v / (m * m))
    return out


# ---- gap analysis ----------------------------------------------------------

def gap_analysis(fiber_mask):
    """Distance-transform-based gap distribution.

    Returns a dict with ``mean_gap_px``, ``max_gap_px``, and ``n_gaps``
    where a "gap" is a local maximum of the distance transform of the
    fiber-free area. This is a fast proxy for "largest inscribed circle in
    fiber-free regions" without bringing in OpenCV.
    """
    dist = ndimage.distance_transform_edt(~fiber_mask.astype(bool))
    if not (dist > 0).any():
        return {"mean_gap_px": 0.0, "max_gap_px": 0.0, "n_gaps": 0}
    # 3x3 local maxima
    nb_max = ndimage.maximum_filter(dist, size=3, mode="constant", cval=0.0)
    peaks = (dist == nb_max) & (dist > 1.0)
    vals = dist[peaks]
    if vals.size == 0:
        return {"mean_gap_px": 0.0, "max_gap_px": 0.0, "n_gaps": 0}
    return {
        "mean_gap_px": float(np.mean(vals)),
        "max_gap_px": float(np.max(vals)),
        "n_gaps": int(vals.size),
    }


# ---- convenience wrapper (used nowhere internal but handy for tests) -------

def build_skeleton(fiber_mask):
    """Convenience: return the skeletonised fiber mask."""
    return skmorph.skeletonize(fiber_mask.astype(bool))


# ---- per-window morphometric arrays ----------------------------------------

def per_window_morphometrics(
    skeleton,
    fiber_mask,
    window_px,
    stride_px,
    grid_shape,
    lac_boxes=None,
    fd_boxes=None,
    segment_len_px=4,
    flags=None,
):
    """Compute morphometric measurements on a moving window grid.

    The user picks window size and overlap; we honour that choice and emit
    a per-window array for every morphometric measurement that *can* be
    expressed per window. Cells where the metric is mathematically
    undefined (e.g. fractal dimension on a window too small for two box
    sizes, lacunarity with zero foreground) come back as NaN; the
    coverage-gate downstream NaNs additional cells based on user
    threshold.

    Args:
        skeleton: 2-D bool array, fiber skeleton (output of
            skmorph.skeletonize). Used for length, branch / endpoint
            counts, curvature, fractal dimension.
        fiber_mask: 2-D bool array of the segmented fiber pixels
            (analysis_mask in the calling script -- fiber & zone). Used
            for lacunarity and the global distance transform that feeds
            gap statistics.
        window_px: int, side length of each window in pixels (matches
            windows.compute_windows).
        stride_px: int, window stride in pixels.
        grid_shape: (Hw, Ww) tuple from windows.compute_windows.
        lac_boxes: iterable of int box sizes (pixels) for lacunarity.
            Sub-window-sized values that are >= 1 are kept; box sizes
            larger than the window are skipped (the cell remains NaN
            for that box).
        fd_boxes: iterable of int box sizes (pixels) for fractal
            dimension. The same sub-window filter applies; a window
            needs at least two usable box sizes to fit a slope.
        segment_len_px: tangent-averaging window for mean_curvature.
        flags: dict of {name: bool} -- which metrics to compute. Recognised
            keys: "length", "branch", "endpoints", "curvature", "fd",
            "lac", "gaps". Missing / falsey entries skip the metric.

    Returns:
        Dict of {name: np.ndarray(Hw, Ww)} for every enabled metric. Per-box
        lacunarity values come back as ``lacunarity_box_<N>``. Per-px
        results are emitted in pixel units -- the caller can multiply by
        ``pixel_size_um`` to produce um-equivalent arrays.
    """
    flags = flags or {}
    Hw, Ww = int(grid_shape[0]), int(grid_shape[1])
    sk = np.asarray(skeleton, dtype=bool)
    fm = np.asarray(fiber_mask, dtype=bool)
    h, w = sk.shape

    # Pre-compute neighbour-based branch/endpoint masks globally -- a
    # per-window walk would re-do the same convolution Hw*Ww times.
    is_branch = None
    is_endpoint = None
    if flags.get("branch") or flags.get("endpoints"):
        kernel = np.ones((3, 3), dtype=np.int32)
        nb = ndimage.convolve(sk.astype(np.int32), kernel, mode="constant", cval=0)
        nb_only = nb - sk.astype(np.int32)
        is_branch = (nb_only >= 3) & sk
        is_endpoint = (nb_only == 1) & sk

    # Global distance transform for gap stats. Using the global transform
    # is the right physical quantity at window boundaries -- the "gap"
    # at a window edge is defined by distance to the *nearest* fiber in
    # the whole image, not by treating the window edge as fiber.
    peak_vals_full = None
    if flags.get("gaps"):
        dist = ndimage.distance_transform_edt(~fm)
        nb_max = ndimage.maximum_filter(dist, size=3, mode="constant", cval=0.0)
        peaks_mask = (dist == nb_max) & (dist > 1.0)
        peak_vals_full = np.where(peaks_mask, dist, 0.0)

    out = {}

    def _alloc():
        return np.full((Hw, Ww), np.nan, dtype=np.float64)

    if flags.get("length"):
        # Length and counts use 0 for empty windows (a real value -- "no
        # fiber here") rather than NaN.
        out["total_length_px"] = np.zeros((Hw, Ww), dtype=np.float64)
    if flags.get("branch"):
        out["branch_points"] = np.zeros((Hw, Ww), dtype=np.float64)
    if flags.get("endpoints"):
        out["endpoints"] = np.zeros((Hw, Ww), dtype=np.float64)
    if flags.get("curvature"):
        out["mean_curvature_per_px"] = _alloc()
    if flags.get("fd"):
        out["fractal_dimension"] = _alloc()
    lac_box_keys = []
    if flags.get("lac") and lac_boxes:
        lac_box_keys = sorted({int(b) for b in lac_boxes if int(b) >= 1})
        for b in lac_box_keys:
            out[f"lacunarity_box_{b}"] = _alloc()
        out["lacunarity_mean"] = _alloc()
    if flags.get("gaps"):
        out["gap_mean_px"] = _alloc()
        out["gap_max_px"] = _alloc()

    for iy in range(Hw):
        y0 = iy * stride_px
        y1 = min(y0 + window_px, h)
        for ix in range(Ww):
            x0 = ix * stride_px
            x1 = min(x0 + window_px, w)
            sk_win = sk[y0:y1, x0:x1]
            fm_win = fm[y0:y1, x0:x1]
            wy = y1 - y0
            wx = x1 - x0

            if "total_length_px" in out:
                out["total_length_px"][iy, ix] = float(sk_win.sum())
            if "branch_points" in out and is_branch is not None:
                out["branch_points"][iy, ix] = float(is_branch[y0:y1, x0:x1].sum())
            if "endpoints" in out and is_endpoint is not None:
                out["endpoints"][iy, ix] = float(is_endpoint[y0:y1, x0:x1].sum())
            if "mean_curvature_per_px" in out and sk_win.any():
                try:
                    c = mean_curvature(sk_win, segment_len_px=segment_len_px)
                    if c == c:  # not NaN
                        out["mean_curvature_per_px"][iy, ix] = float(c)
                except Exception as exc:
                    logger.debug("mean_curvature failed at (%d,%d): %s", iy, ix, exc)
            if "fractal_dimension" in out and fd_boxes and sk_win.any():
                usable = [int(b) for b in fd_boxes if 1 <= int(b) <= min(wy, wx)]
                if len(usable) >= 2:
                    try:
                        d = fractal_dimension(sk_win, usable)
                        if d == d:
                            out["fractal_dimension"][iy, ix] = float(d)
                    except Exception as exc:
                        logger.debug("fractal_dimension failed at (%d,%d): %s", iy, ix, exc)
            if lac_box_keys and fm_win.any():
                try:
                    lac_dict = lacunarity(fm_win, lac_box_keys)
                except Exception as exc:
                    logger.debug("lacunarity failed at (%d,%d): %s", iy, ix, exc)
                    lac_dict = {}
                vals_for_mean = []
                for b in lac_box_keys:
                    v = lac_dict.get(b)
                    if v is None:
                        continue
                    try:
                        fv = float(v)
                    except (TypeError, ValueError):
                        continue
                    if not (math.isnan(fv) or math.isinf(fv)):
                        out[f"lacunarity_box_{b}"][iy, ix] = fv
                        vals_for_mean.append(fv)
                if vals_for_mean:
                    out["lacunarity_mean"][iy, ix] = float(np.mean(vals_for_mean))
            if "gap_mean_px" in out and peak_vals_full is not None:
                pv = peak_vals_full[y0:y1, x0:x1]
                vals = pv[pv > 0]
                if vals.size > 0:
                    out["gap_mean_px"][iy, ix] = float(np.mean(vals))
                    out["gap_max_px"][iy, ix] = float(np.max(vals))

    return out
