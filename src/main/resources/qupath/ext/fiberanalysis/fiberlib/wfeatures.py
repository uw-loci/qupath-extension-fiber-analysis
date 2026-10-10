"""
Poolable per-window fiber features for the region classifier.

A feature is *poolable* when it is a fixed function of additive per-pixel
accumulators over the window. For those, the value at any window size and any
stride is four lookups into a summed-area table, so the whole bank costs
``O(tile_pixels)`` -- independent of stride, and independent of how many window
sizes are wanted. That is what makes a fine-stride classifier map affordable:
halving the stride multiplies the number of windows by four and the work by
nothing.

Not everything is poolable. GLCM and Radon are not, medians are not. Those stay
on the existing per-window path, computed on a coarser lattice.

Why summed-area tables rather than ``scipy.ndimage.uniform_filter``: a SAT gives
exact box sums with no ambiguity about where the filter sits, and
``compute_windows`` anchors its windows at the top-left corner
(``iy * stride_px``) while ``uniform_filter`` centres them. Getting that origin
wrong shifts the whole output map by half a window against the measurements that
produced it, silently. The SAT is also less work: it is evaluated only at the
Hw * Ww window corners instead of at every pixel and then subsampled. One table
is built at a time and freed, so peak memory is one float64 plane, not one per
feature.

Accumulate in float64. A float32 running sum over 1.6e7 ones has an eps of 2 at
the top of its range, so a count plane pooled in float32 is wrong by more than
the thing being counted.
"""
import logging

import numpy as np
from scipy import ndimage
from skimage import morphology as skmorph

from . import straightness as straight

logger = logging.getLogger("fiber.wfeatures")


class BoxPooler:
    """Exact box sums over a window lattice, for one region.

    The lattice matches ``windows.compute_windows``: window ``(iy, ix)`` spans
    rows ``[iy*stride, iy*stride + window_px)`` and the same in x. Windows that
    would hang off the region are clamped to the edge and flagged partial --
    they are fine to predict on and must not be trained on, because every
    area-normalised feature is biased by the smaller denominator.
    """

    def __init__(self, shape, window_px, stride_px):
        h, w = int(shape[0]), int(shape[1])
        window_px = int(window_px)
        stride_px = int(stride_px)
        if window_px < 2:
            raise ValueError(f"window_px must be >= 2 (got {window_px})")
        if stride_px < 1:
            raise ValueError(f"stride_px must be >= 1 (got {stride_px})")
        if stride_px > window_px:
            raise ValueError(
                f"stride_px must not exceed window_px, or the window cores leave gaps "
                f"(window {window_px}, stride {stride_px})"
            )
        self.h = h
        self.w = w
        self.window_px = window_px
        self.stride_px = stride_px

        self.ys0 = self._origins(h, window_px, stride_px)
        self.xs0 = self._origins(w, window_px, stride_px)
        self.ys1 = np.minimum(self.ys0 + window_px, h)
        self.xs1 = np.minimum(self.xs0 + window_px, w)
        self.grid_shape = (len(self.ys0), len(self.xs0))

        # True where the window was clamped, i.e. its support is truncated.
        py = (self.ys0 + window_px) > h
        px = (self.xs0 + window_px) > w
        self.partial = py[:, None] | px[None, :]

        # Actual support of each window, which is the denominator for every
        # area-normalised feature. Equals window_px**2 except at the edges.
        self.area_px = ((self.ys1 - self.ys0)[:, None] * (self.xs1 - self.xs0)[None, :]).astype(np.float64)

    @staticmethod
    def _origins(extent, window_px, stride_px):
        """Window origins along one axis: the whole ones, plus one clamped.

        ``compute_windows`` uses floor division and so leaves up to
        ``window_px - 1`` pixels at the far edge in no window at all. On a
        classifier raster that is an unclassified border on every slide, so one
        extra clamped window is appended when anything is left over.
        """
        if extent <= 0:
            return np.zeros(0, dtype=np.int64)
        if extent < window_px:
            return np.zeros(1, dtype=np.int64)
        n_whole = (extent - window_px) // stride_px + 1
        origins = np.arange(n_whole, dtype=np.int64) * stride_px
        covered = (n_whole - 1) * stride_px + window_px
        if covered < extent:
            origins = np.append(origins, extent - window_px + ((extent - window_px) % 1))
            origins[-1] = n_whole * stride_px
        return origins

    def pool(self, plane):
        """Sum ``plane`` over every window. Returns (Hw, Ww) float64."""
        plane = np.asarray(plane)
        if plane.shape != (self.h, self.w):
            raise ValueError(f"plane shape {plane.shape} != region {(self.h, self.w)}")
        sat = np.zeros((self.h + 1, self.w + 1), dtype=np.float64)
        np.cumsum(plane, axis=0, dtype=np.float64, out=sat[1:, 1:])
        np.cumsum(sat[1:, 1:], axis=1, out=sat[1:, 1:])
        iy1, ix1 = np.ix_(self.ys1, self.xs1)
        iy0, ix0 = np.ix_(self.ys0, self.xs0)
        return (
            sat[iy1, ix1]
            - sat[np.ix_(self.ys0, self.xs1)]
            - sat[np.ix_(self.ys1, self.xs0)]
            + sat[iy0, ix0]
        )


def _safe_div(num, den, where=None):
    """num / den, NaN where the denominator is zero or `where` is False."""
    out = np.full(np.broadcast(num, den).shape, np.nan, dtype=np.float64)
    ok = den > 0
    if where is not None:
        ok = ok & where
    np.divide(num, den, out=out, where=ok)
    return out


def build_planes(fiber_mask, image_scalar=None, pixel_size_um=1.0, tensor_sigma=4.0):
    """Per-pixel accumulator planes every poolable feature is summed from.

    Built once per region. Each plane is float64 so pooling is exact.

    Args:
        fiber_mask:     (H, W) bool-ish, segmented fiber pixels.
        image_scalar:   (H, W) optional intensity image for intensity features.
        pixel_size_um:  microns per pixel.
        tensor_sigma:   structure-tensor integration scale for orientation.

    Returns:
        ``(planes, extras)`` -- a dict of named float64 planes, and a dict with
        the skeleton and angle field for callers that need them.
    """
    mask = np.asarray(fiber_mask, dtype=bool)
    skel, angles = straight.skeletonize_and_tangents(mask, tensor_sigma=tensor_sigma)

    planes = {}
    planes["mask"] = mask.astype(np.float64)
    planes["skel"] = skel.astype(np.float64)

    # Axial orientation as vector components, which is what makes the order
    # parameter poolable at all: an angle is not additive, but cos(2t) and
    # sin(2t) are, and the order parameter is a fixed function of their means.
    valid = mask & np.isfinite(angles)
    two_theta = np.zeros(mask.shape, dtype=np.float64)
    two_theta[valid] = np.deg2rad(2.0 * angles[valid].astype(np.float64))
    planes["valid"] = valid.astype(np.float64)
    planes["cos2"] = np.where(valid, np.cos(two_theta), 0.0)
    planes["sin2"] = np.where(valid, np.sin(two_theta), 0.0)

    # Topology, with the crossing number for junctions and a neighbour count
    # for endpoints -- see straightness.crossing_numbers for why the two tests
    # are deliberately different.
    planes["junction"] = straight.junction_mask(skel).astype(np.float64)
    planes["endpoint"] = ((straight.neighbour_counts(skel) == 1) & skel).astype(np.float64)

    # Fiber width from the distance transform: 2 * EDT at a skeleton pixel is
    # the local fiber diameter. The transform is already computed for gaps.
    edt = ndimage.distance_transform_edt(mask)
    width_px = 2.0 * edt * skel
    planes["width"] = width_px * float(pixel_size_um)
    planes["width_sq"] = (width_px * float(pixel_size_um)) ** 2

    # Per-fiber quantities painted onto that fiber's pixels, so pooling gives a
    # LENGTH-WEIGHTED mean. This replaces binning each fiber into one window by
    # its centroid, which left most windows with no fibers at all once the
    # stride got small.
    straightness_plane = np.zeros(mask.shape, dtype=np.float64)
    curvature_plane = np.zeros(mask.shape, dtype=np.float64)
    seg_equiv_plane = np.zeros(mask.shape, dtype=np.float64)
    for path in straight.trace_fibers(skel):
        n = len(path)
        if n < 2:
            continue
        t = straight.segment_tortuosity(path)
        k = _path_curvature_per_px(path)
        ys = np.fromiter((p[0] for p in path), dtype=np.intp, count=n)
        xs = np.fromiter((p[1] for p in path), dtype=np.intp, count=n)
        if t == t:
            straightness_plane[ys, xs] = t
        if k == k:
            curvature_plane[ys, xs] = k
        # 1/n per pixel sums to 1 over a whole fiber, so a window that contains
        # half a fiber counts half of one. Fractional, and overlap-aware.
        seg_equiv_plane[ys, xs] = 1.0 / float(n)
    planes["straightness"] = straightness_plane * planes["skel"]
    planes["curvature"] = curvature_plane * planes["skel"]
    planes["seg_equiv"] = seg_equiv_plane

    if image_scalar is not None:
        img = np.asarray(image_scalar, dtype=np.float64)
        if img.shape != mask.shape:
            raise ValueError(f"image shape {img.shape} != mask {mask.shape}")
        planes["intensity"] = img * planes["mask"]
        planes["intensity_sq"] = (img**2) * planes["mask"]

    return planes, {"skeleton": skel, "angles": angles}


def _path_curvature_per_px(path):
    """Total turning of a traced fiber, per pixel of arc length."""
    n = len(path)
    if n < 3:
        return float("nan")
    pts = np.asarray(path, dtype=np.float64)
    seg = max(2, min(4, n - 1))
    v0 = pts[seg] - pts[0]
    v1 = pts[-1] - pts[max(0, n - 1 - seg)]
    n0 = np.linalg.norm(v0)
    n1 = np.linalg.norm(v1)
    if n0 == 0 or n1 == 0:
        return float("nan")
    cosang = float(np.clip(np.dot(v0, v1) / (n0 * n1), -1.0, 1.0))
    arc = float(np.sum(np.linalg.norm(np.diff(pts, axis=0), axis=1)))
    if arc <= 0:
        return float("nan")
    return float(np.arccos(cosang)) / arc


def window_features(
    fiber_mask,
    window_px,
    stride_px,
    image_scalar=None,
    pixel_size_um=1.0,
    min_pixels=None,
    tensor_sigma=4.0,
):
    """Pool the whole poolable bank onto a window lattice.

    Args:
        fiber_mask:    (H, W) bool-ish, segmented fiber pixels.
        window_px:     window side in pixels.
        stride_px:     distance between window origins.
        image_scalar:  optional intensity image for intensity features.
        pixel_size_um: microns per pixel.
        min_pixels:    minimum fiber pixels for the orientation statistics to
                       be defined. None uses the ``compute_windows`` default of
                       10% of the window area.
        tensor_sigma:  structure-tensor integration scale.

    Returns:
        ``(features, meta)``. ``features`` maps a name to a (Hw, Ww) float64
        array, NaN where undefined. ``meta`` carries ``grid_shape``,
        ``window_px``, ``stride_px``, ``partial`` and ``area_px``.
    """
    mask = np.asarray(fiber_mask, dtype=bool)
    planes, _extras = build_planes(mask, image_scalar, pixel_size_um, tensor_sigma)
    pooler = BoxPooler(mask.shape, window_px, stride_px)

    if min_pixels is None:
        min_pixels = max(1, int(0.1 * pooler.window_px * pooler.window_px))
    min_pixels = int(min_pixels)

    px_um = float(pixel_size_um)
    px_area_um2 = px_um * px_um

    n_fiber = pooler.pool(planes["mask"])
    n_valid = pooler.pool(planes["valid"])
    n_skel = pooler.pool(planes["skel"])
    area_um2 = pooler.area_px * px_area_um2

    f = {}
    f["n_fiber_px"] = n_fiber
    f["coverage_fraction"] = n_fiber / pooler.area_px

    # Axial circular statistics, identical in form to windows.compute_windows.
    c_bar = _safe_div(pooler.pool(planes["cos2"]), n_valid)
    s_bar = _safe_div(pooler.pool(planes["sin2"]), n_valid)
    enough = n_valid >= min_pixels
    order = np.hypot(c_bar, s_bar)
    f["order_parameter"] = np.where(enough, order, np.nan)
    f["mean_angle_deg"] = np.where(enough, np.rad2deg(0.5 * np.arctan2(s_bar, c_bar)) % 180.0, np.nan)

    # Densities, not raw counts: a raw count changes meaning the moment the
    # user changes the window size.
    f["length_um_per_um2"] = (n_skel * px_um) / area_um2
    f["branch_per_um2"] = pooler.pool(planes["junction"]) / area_um2
    f["endpoint_per_um2"] = pooler.pool(planes["endpoint"]) / area_um2
    f["segment_equivalents_per_um2"] = pooler.pool(planes["seg_equiv"]) / area_um2

    n_branch = pooler.pool(planes["junction"])
    n_end = pooler.pool(planes["endpoint"])
    f["branch_to_endpoint_ratio"] = _safe_div(n_branch, n_end)

    w_mean = _safe_div(pooler.pool(planes["width"]), n_skel)
    w_sq_mean = _safe_div(pooler.pool(planes["width_sq"]), n_skel)
    f["width_mean_um"] = w_mean
    f["width_sd_um"] = np.sqrt(np.maximum(w_sq_mean - w_mean**2, 0.0))

    # Length-weighted, because a 3 pixel stub and a 300 pixel fiber counted
    # equally in the old unweighted mean over segments.
    f["straightness_mean_lenweighted"] = _safe_div(pooler.pool(planes["straightness"]), n_skel)
    f["curvature_mean_per_um"] = _safe_div(pooler.pool(planes["curvature"]), n_skel) / px_um

    if "intensity" in planes:
        i_mean = _safe_div(pooler.pool(planes["intensity"]), n_fiber)
        i_sq = _safe_div(pooler.pool(planes["intensity_sq"]), n_fiber)
        f["intensity_mean"] = i_mean
        f["intensity_sd"] = np.sqrt(np.maximum(i_sq - i_mean**2, 0.0))

    meta = {
        "grid_shape": pooler.grid_shape,
        "window_px": pooler.window_px,
        "stride_px": pooler.stride_px,
        "partial": pooler.partial,
        "area_px": pooler.area_px,
        "min_pixels": min_pixels,
        "pixel_size_um": px_um,
    }
    return f, meta


#: Features that must never be handed to a model, with the reason.
#: ``mean_angle_deg`` is absolute axial orientation, which is a property of how
#: the section was mounted. A tree model will memorise it, cross-validate
#: beautifully and fail on the next slide. It is computed because the
#: rotation-invariant context features are derived from it, not for the model.
EXCLUDED_FROM_MODEL = {
    "mean_angle_deg": "absolute orientation is a slide-mounting property, not a tissue property",
    "n_fiber_px": "raw count; use coverage_fraction, which is area-normalised",
}


def model_feature_names(features):
    """@return the feature names fit to train on, in a stable order."""
    return [k for k in sorted(features) if k not in EXCLUDED_FROM_MODEL]
