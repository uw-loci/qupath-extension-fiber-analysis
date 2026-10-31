"""
GLCM / Haralick texture features per moving window.

References:
    Haralick, R. M. et al. (1973). Textural features for image classification.
        IEEE Trans. Syst. Man Cybern.
    Aerts, H. J. W. L. et al. (2014). Decoding tumour phenotype by
        noninvasive imaging using a quantitative radiomics approach.
        Nature Communications 5:4006.
    Depeursinge, A. et al. (2014). Three-dimensional solid texture analysis
        in biomedical imaging. Med Image Anal 18(1).

We use scikit-image's ``graycomatrix`` + ``graycoprops``. Fiber pixels are
quantised to ``quant_levels`` (default 16) gray levels; background is a
separate code that is dropped from the co-occurrence matrix, so only
fiber-fiber pixel pairs count. (Through fiberlib 0.3.11 background was gray
level 0 and its pairs dominated the matrix, so contrast and entropy tracked
fiber coverage more than texture.) Co-occurrence is computed at each
requested distance and averaged over four angles (0, 45, 90, 135 deg) to be
rotation-invariant, then ``graycoprops`` extracts the requested properties.
A distance/angle with no fiber-fiber pair is left out of the average; a
window with none at all is NaN. On request (``per_distance``) and with more than
one distance, each window also gets the value at each distance alone
(averaged over the four angles), next to the average over all of them.

A native ``entropy`` is added since scikit-image's ``graycoprops`` does
not include it.
"""

import logging
from collections import OrderedDict

import numpy as np
from skimage.feature import graycomatrix, graycoprops

logger = logging.getLogger("fiber.texture")

_ANGLES = [0.0, np.pi / 4.0, np.pi / 2.0, 3.0 * np.pi / 4.0]


def quantise(image_scalar, levels, value_range=(0.0, 1.0)):
    """Quantise a [0,1] scalar image into 0..levels-1 uint8.

    ``value_range`` is the part of [0,1] the levels span; values outside it
    clip to the end levels. The range is the caller's, fixed for a run: taking
    it from each window or tile would give every one its own meaning of a level.
    """
    lo, hi = value_range
    arr = np.clip((image_scalar.astype(np.float32) - lo) / (hi - lo), 0.0, 1.0)
    q = np.floor(arr * (levels - 1) + 0.5).astype(np.uint8)
    return q


def level_use(image_scalar, fiber_mask, levels, value_range, full_scale):
    """How well a gray range fits the fibers.

    Args:
        image_scalar: (H, W) [0,1] scalar.
        fiber_mask:   (H, W) bool.
        levels:       number of gray levels.
        value_range:  (lo, hi) on [0,1], as for :func:`quantise`.
        full_scale:   gray level of 1.0.

    Returns:
        dict; levels_holding_99pct counts the fullest levels that together hold
        99% of fiber pixels, fiber_gray_p1 / fiber_gray_p99 are the fibers' own
        range in gray levels, and clipped_percent is the share of fiber pixels
        outside ``value_range``. Empty when there are no fiber pixels.
    """
    v = np.asarray(image_scalar, dtype=np.float32)[np.asarray(fiber_mask, dtype=bool)]
    if v.size == 0:
        return {}
    counts = np.sort(np.bincount(quantise(v, levels, value_range), minlength=int(levels)))[::-1]
    p1, p99 = np.percentile(v, [1, 99])
    lo, hi = value_range
    return {
        "levels_holding_99pct": int((np.cumsum(counts) < 0.99 * v.size).sum()) + 1,
        "fiber_gray_p1": float(p1) * float(full_scale),
        "fiber_gray_p99": float(p99) * float(full_scale),
        "clipped_percent": 100.0 * float(np.mean((v < lo) | (v > hi))),
    }


#: How an automatic gray range is taken from fiber pixels. Mean +/- 3 SD follows
#: Collewet et al. 2004, doi:10.1016/j.mri.2003.09.001; the range of the region's
#: own values is the fixed-bin-number discretization of the IBSI, Zwanenburg et
#: al. 2020, doi:10.1148/radiol.2020191145.
RANGE_METHODS = ("percentile_1_99", "mean_3sd", "min_max")


def range_from_histogram(hist, method, full_scale):
    """A gray range from a histogram of fiber pixels.

    Args:
        hist:       counts in equal bins over [0, 1].
        method:     one of :data:`RANGE_METHODS`.
        full_scale: gray level of 1.0.

    Returns:
        (lo, hi) in gray levels, whole numbers inside 0..full_scale.

    Raises:
        ValueError: on an unknown method, or fibers with no spread to fit.
    """
    h = np.asarray(hist, dtype=np.float64)
    if method not in RANGE_METHODS:
        raise ValueError(f"Unknown GLCM range method {method!r}")
    total = h.sum()
    if total <= 0:
        raise ValueError("No fiber pixels to fit a GLCM gray range on")
    centers = (np.arange(h.size) + 0.5) / h.size
    if method == "percentile_1_99":
        cum = np.cumsum(h) / total
        lo, hi = centers[np.searchsorted(cum, 0.01)], centers[np.searchsorted(cum, 0.99)]
    elif method == "mean_3sd":
        mean = (centers * h).sum() / total
        sd = np.sqrt((h * (centers - mean) ** 2).sum() / total)
        lo, hi = mean - 3.0 * sd, mean + 3.0 * sd
    else:
        filled = np.flatnonzero(h)
        lo, hi = centers[filled[0]], centers[filled[-1]]
    gray_lo = int(np.floor(max(lo, 0.0) * full_scale))
    gray_hi = int(np.ceil(min(hi, 1.0) * full_scale))
    if gray_hi <= gray_lo:
        raise ValueError("Fiber pixels have no spread to fit a GLCM gray range on")
    return gray_lo, gray_hi


def code_fibers(q, fiber_mask):
    """Gray levels 0..L-1 on fiber pixels become codes 1..L; background is 0."""
    return np.where(fiber_mask, q.astype(np.uint16) + 1, 0).astype(np.uint16)


def glcm_props_for_patch(patch_codes, distances_px, levels, props, by_distance=False):
    """Compute a dict of {prop_name: scalar} for a single window patch.

    ``patch_codes`` is from :func:`code_fibers`: 0 = background, 1..levels =
    fiber gray levels. Pairs with a background pixel are not counted.

    With ``by_distance`` the result is ``(average, {distance_px: {prop: value}})``.
    """
    nan = {p: float("nan") for p in props}
    nan_by_d = {int(d): dict(nan) for d in distances_px}
    if patch_codes.size == 0:
        return (nan, nan_by_d) if by_distance else nan
    counts = graycomatrix(
        patch_codes,
        distances=list(distances_px),
        angles=_ANGLES,
        levels=int(levels) + 1,
        symmetric=True,
        normed=False,
    )[1:, 1:, :, :].astype(np.float64)
    totals = counts.sum(axis=(0, 1))  # (Nd, Na)
    used = totals > 0
    if not used.any():
        return (nan, nan_by_d) if by_distance else nan
    glcm = counts / np.where(used, totals, 1.0)
    out = {}
    by_d = {int(d): {} for d in distances_px}
    for p in props:
        if p == "entropy":
            # Native: sum(- P * log2(P)) averaged over distances/angles.
            P = glcm
            valid = P > 0
            with np.errstate(divide="ignore", invalid="ignore"):
                ent = -np.where(valid, P * np.log2(np.where(valid, P, 1)), 0.0)
            v = ent.sum(axis=(0, 1))  # (Nd, Na)
        else:
            try:
                v = graycoprops(glcm, p)  # (Nd, Na)
            except Exception as e:
                logger.warning("graycoprops(%s) failed: %s", p, e)
                v = None
        if v is None:
            out[p] = float("nan")
            for d in by_d:
                by_d[d][p] = float("nan")
            continue
        out[p] = float(np.mean(v[used]))
        for i, d in enumerate(distances_px):
            row = used[i]
            by_d[int(d)][p] = float(np.mean(v[i][row])) if row.any() else float("nan")
    return (out, by_d) if by_distance else out


def compute_glcm_window(
    image_scalar,
    fiber_mask,
    window_grid,
    quant_levels,
    distances_px,
    props,
    per_distance=False,
    value_range=(0.0, 1.0),
):
    """Per-window GLCM features.

    If ``window_grid`` is None, returns a single dict with one scalar per
    requested prop computed over the entire fiber-masked image. Otherwise
    returns a dict with ``per_window``: a dict {prop_name: (Hw, Ww) array}.
    With ``per_distance`` and two or more distances it also holds
    ``per_window_by_distance``: {distance_px: {prop_name: (Hw, Ww) array}}.
    ``value_range`` is passed to :func:`quantise`.
    """
    q = quantise(image_scalar, int(quant_levels), value_range)
    q_masked = code_fibers(q, fiber_mask)

    if window_grid is None:
        single = glcm_props_for_patch(q_masked, distances_px, quant_levels, props)
        return {
            "per_window": {
                p: np.array([[v]], dtype=np.float32) for p, v in single.items()
            }
        }

    grid_shape = window_grid["grid_shape"]
    window_px = int(window_grid["window_px"])
    stride_px = int(window_grid["stride_px"])
    per_window = OrderedDict()
    for p in props:
        per_window[p] = np.full(grid_shape, np.nan, dtype=np.float32)
    dists = [int(d) for d in distances_px]
    split = bool(per_distance) and len(dists) > 1
    per_distance = OrderedDict(
        (d, OrderedDict((p, np.full(grid_shape, np.nan, dtype=np.float32)) for p in props)) for d in dists
    ) if split else None

    for iy in range(grid_shape[0]):
        y0 = iy * stride_px
        y1 = y0 + window_px
        for ix in range(grid_shape[1]):
            x0 = ix * stride_px
            x1 = x0 + window_px
            patch = q_masked[y0:y1, x0:x1]
            if patch.size == 0 or not fiber_mask[y0:y1, x0:x1].any():
                continue
            vals = glcm_props_for_patch(patch, distances_px, quant_levels, props, by_distance=split)
            if split:
                vals, vals_by_d = vals
                for d, dv in vals_by_d.items():
                    for p, v in dv.items():
                        per_distance[d][p][iy, ix] = v
            for p, v in vals.items():
                per_window[p][iy, ix] = v
    out = {"per_window": per_window}
    if split:
        # {distance_px: {prop: (Hw, Ww)}}; never with a single distance,
        # where it would only repeat per_window.
        out["per_window_by_distance"] = per_distance
    return out
