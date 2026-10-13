"""
Neighbourhood context on the assembled window grid.

This is the part that answers "a simple pixel classifier cannot see context".
A window's own features describe the tissue inside it; what distinguishes one
kind of collagen from another is often how that tissue sits relative to its
surroundings -- dense against loose, aligned inside a disordered field, a
sharp coverage boundary rather than a gradual one. None of that exists at the
scale of a filter kernel, and all of it is cheap once the windows are on a
grid.

For each base feature and each radius r (in WINDOWS, not pixels) this adds:

    <f>_mean_r<r>   the neighbourhood mean
    <f>_sd_r<r>     the neighbourhood spread
    <f>_dev_r<r>    f - mean, i.e. local contrast

plus three orientation terms with no per-window equivalent:

    angular_dispersion_r<r>  1 - |<exp(2i.theta)>|, 0 when the neighbourhood
                             is perfectly co-aligned, 1 when it is isotropic
    angle_dev_r<r>           this window's axial angle against the
                             neighbourhood mean, wrapped to [0, 90]
    coverage_grad_r<r>       magnitude of the coverage gradient at scale r

**Computed on the slide-wide grid, never per tile.** A per-tile neighbourhood
would be truncated at every seam, and since the truncation is a function of
position the model would learn the tiling. The whole point of the tile overlap
in the feature extractor is undone by getting this wrong.

**The orientation terms are rotation-invariant and the raw angle is not.**
``mean_angle_deg`` is measured against the image axes, which are set by how
the section landed on the slide: rotate the coverslip and every value shifts
while the tissue is unchanged. A tree model will memorise it, cross-validate
beautifully and fail on the next slide. The dispersion and the deviation are
differences between angles, so they survive the rotation; they are the reason
``mean_angle_deg`` is computed at all.

Every mean here is NaN-aware: a window with no defined value contributes
nothing rather than poisoning its whole neighbourhood, and a cell whose
neighbourhood is entirely undefined comes out NaN.
"""

import logging

import numpy as np
from scipy import ndimage

logger = logging.getLogger("fiber.wcontext")

#: Neighbourhood radii, in windows. 1 is the immediate ring, 3 reaches about
#: one window-width out at 75% overlap.
DEFAULT_RADII = (1, 3)

#: Features whose neighbourhood statistics are not computed, with the reason.
#: Mirrors wfeatures.EXCLUDED_FROM_MODEL and adds nothing of its own: a
#: context feature built on an excluded base inherits the same defect.
SKIP_CONTEXT_FOR = {
    "mean_angle_deg": "absolute orientation; the rotation-invariant terms below replace it",
    "n_fiber_px": "raw count; coverage_fraction carries the same information area-normalised",
}


def _box(radius):
    """Side of the square neighbourhood for a radius, in windows."""
    return 2 * int(radius) + 1


def nan_box_stats(grid, radius):
    """Neighbourhood mean and standard deviation, ignoring NaN.

    Args:
        grid:   (Hw, Ww) values, NaN where undefined.
        radius: neighbourhood radius in windows.

    Returns:
        ``(mean, sd, count)``, each (Hw, Ww) float64. ``mean`` and ``sd`` are
        NaN where the neighbourhood holds no defined value at all; ``count``
        is how many defined values went into each cell.
    """
    g = np.asarray(grid, dtype=np.float64)
    size = _box(radius)
    valid = np.isfinite(g)

    # Shifted-data variance. E[x^2] - E[x]^2 on raw values subtracts two
    # nearly equal numbers, and on a feature like n_fiber_px (order 1e6) the
    # cancellation eats most of the precision -- a constant neighbourhood
    # came out with a spread of 7e-8 instead of zero. Subtracting one global
    # constant first makes the squares order-variance rather than order-mean
    # squared, costs nothing, and leaves the mean exact after adding it back.
    shift = float(np.mean(g[valid])) if valid.any() else 0.0
    filled = np.where(valid, g - shift, 0.0)

    # uniform_filter is a mean, so multiply back up to sums. Done this way
    # rather than with a summed-area table because the grid is small (one
    # cell per window, not per pixel) and uniform_filter handles the edges.
    count = ndimage.uniform_filter(
        valid.astype(np.float64), size=size, mode="constant", cval=0.0
    ) * (size * size)
    s1 = ndimage.uniform_filter(filled, size=size, mode="constant", cval=0.0) * (
        size * size
    )
    s2 = ndimage.uniform_filter(
        filled * filled, size=size, mode="constant", cval=0.0
    ) * (size * size)

    ok = count > 0
    shifted_mean = np.full(g.shape, np.nan)
    np.divide(s1, count, out=shifted_mean, where=ok)
    var = np.full(g.shape, np.nan)
    np.divide(s2, count, out=var, where=ok)
    var = var - shifted_mean * shifted_mean
    # Can still land a hair below zero on a constant neighbourhood.
    var = np.where(np.isfinite(var), np.maximum(var, 0.0), np.nan)
    return shifted_mean + shift, np.sqrt(var), count


def orientation_context(angle_deg, weight, radius):
    """Rotation-invariant orientation statistics over a neighbourhood.

    Args:
        angle_deg: (Hw, Ww) per-window axial angle in [0, 180), NaN where
                   undefined.
        weight:    (Hw, Ww) non-negative weight per window -- fiber pixel
                   count or coverage, so a window with three fibers in it
                   does not count as much as a window full of them. NaN and
                   negative values are treated as zero.
        radius:    neighbourhood radius in windows.

    Returns:
        ``(dispersion, deviation_deg)``. ``dispersion`` is
        ``1 - |<exp(2i.theta)>|`` in [0, 1]; ``deviation_deg`` is this
        window's angle against the neighbourhood mean axis, in [0, 90].
        Both NaN where the neighbourhood has no weight.
    """
    a = np.asarray(angle_deg, dtype=np.float64)
    w = np.asarray(weight, dtype=np.float64)
    w = np.where(np.isfinite(w) & (w > 0) & np.isfinite(a), w, 0.0)
    size = _box(radius)

    theta2 = 2.0 * np.deg2rad(np.where(np.isfinite(a), a, 0.0))
    cw = ndimage.uniform_filter(
        w * np.cos(theta2), size=size, mode="constant", cval=0.0
    )
    sw = ndimage.uniform_filter(
        w * np.sin(theta2), size=size, mode="constant", cval=0.0
    )
    tw = ndimage.uniform_filter(w, size=size, mode="constant", cval=0.0)

    ok = tw > 0
    cbar = np.full(a.shape, np.nan)
    sbar = np.full(a.shape, np.nan)
    np.divide(cw, tw, out=cbar, where=ok)
    np.divide(sw, tw, out=sbar, where=ok)

    r = np.sqrt(cbar * cbar + sbar * sbar)
    dispersion = 1.0 - np.clip(r, 0.0, 1.0)

    mean_angle = np.rad2deg(0.5 * np.arctan2(sbar, cbar)) % 180.0
    diff = np.abs(a - mean_angle) % 180.0
    deviation = np.minimum(diff, 180.0 - diff)
    deviation = np.where(np.isfinite(a) & ok, deviation, np.nan)
    return dispersion, deviation


def coverage_gradient(coverage, radius):
    """Magnitude of the coverage gradient at a given smoothing scale.

    A boundary between two kinds of collagen shows up here even when both
    sides have unremarkable coverage on their own.

    Args:
        coverage: (Hw, Ww) coverage fraction, NaN where undefined.
        radius:   smoothing radius in windows.

    Returns:
        (Hw, Ww) gradient magnitude per window, NaN where undefined.
    """
    mean, _sd, count = nan_box_stats(coverage, radius)
    # np.gradient cannot see NaN, so fill the holes with the neighbourhood
    # mean first and mark the result undefined wherever there was nothing.
    filled = np.where(np.isfinite(mean), mean, 0.0)
    if filled.shape[0] < 2 or filled.shape[1] < 2:
        return np.full(filled.shape, np.nan)
    gy, gx = np.gradient(filled)
    mag = np.sqrt(gy * gy + gx * gx)
    return np.where(count > 0, mag, np.nan)


def context_features(
    features,
    radii=DEFAULT_RADII,
    angle_name="mean_angle_deg",
    weight_name="n_fiber_px",
    coverage_name="coverage_fraction",
):
    """Build every context feature from a grid of base features.

    Args:
        features:      name -> (Hw, Ww) array, as ``wfeatures.window_features``
                       returns it.
        radii:         neighbourhood radii in windows.
        angle_name:    key holding the per-window axial angle.
        weight_name:   key holding the orientation weight.
        coverage_name: key holding the coverage fraction.

    Returns:
        A new dict of context features only. The caller concatenates it with
        the base features; nothing here mutates the input.
    """
    if not features:
        return {}
    shapes = {np.asarray(v).shape for v in features.values()}
    if len(shapes) != 1:
        raise ValueError(f"context needs one grid shape, got {sorted(shapes)}")

    out = {}
    base_names = sorted(n for n in features if n not in SKIP_CONTEXT_FOR)
    for r in radii:
        for name in base_names:
            mean, sd, _count = nan_box_stats(features[name], r)
            out[f"{name}_mean_r{r}"] = mean
            out[f"{name}_sd_r{r}"] = sd
            out[f"{name}_dev_r{r}"] = (
                np.asarray(features[name], dtype=np.float64) - mean
            )

        if angle_name in features:
            weight = features.get(weight_name)
            if weight is None:
                weight = np.ones_like(
                    np.asarray(features[angle_name], dtype=np.float64)
                )
            disp, dev = orientation_context(features[angle_name], weight, r)
            out[f"angular_dispersion_r{r}"] = disp
            out[f"angle_dev_r{r}"] = dev

        if coverage_name in features:
            out[f"coverage_grad_r{r}"] = coverage_gradient(features[coverage_name], r)

    logger.info(
        "Context: %d features from %d base features at radii %s",
        len(out),
        len(base_names),
        list(radii),
    )
    return out
