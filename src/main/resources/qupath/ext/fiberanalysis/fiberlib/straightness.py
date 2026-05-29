"""
Fiber straightness metrics.

Two metrics:

  1. Per-window skeleton tortuosity (chord / arc) along skeletonised fibers.
     Convention follows CT-FIRE:
       Bredfeldt, J. S. et al. (2014). Computational segmentation of collagen
       fibers from second-harmonic generation images of breast cancer. JBO.

  2. Per-ROI Radon-transform scalar: peak-to-mean ratio (PMR), alignment
     index (AI), full-width at half-max (FWHM) at the dominant angle, and
     orientation entropy. Follows:
       Schaub, N. J. et al. (2011). Radon-transform-based image processing
       of collagen organisation. (and downstream literature -- the
       chord-length normalisation here is the standard correction for
       finite-image Radon bias.)

This module deliberately re-implements the skeleton walk with a small DFS
to avoid taking a dependency on ``skan``.
"""
import logging

import numpy as np
from scipy import ndimage
from skimage import morphology as skmorph
from skimage.transform import radon

logger = logging.getLogger("fiber.straightness")


# ---- skeletonise + per-pixel tangents --------------------------------------

def skeletonize_and_tangents(fiber_mask):
    """Skeletonise the fiber mask and estimate per-pixel skeleton tangents.

    Returns ``(skeleton, fiber_angles_deg)``:
      - ``skeleton`` : (H, W) bool, 1-pixel-wide medial axis.
      - ``fiber_angles_deg`` : (H, W) float, tangent angle in degrees [0, 180);
        NaN off-skeleton. Estimated from a small Sobel-like local gradient on
        the smoothed skeleton mask.
    """
    sk = skmorph.skeletonize(fiber_mask.astype(bool))
    if not sk.any():
        return sk, np.full(sk.shape, np.nan, dtype=np.float32)

    # Local gradients on a slightly-smoothed mask give a tangent direction.
    smooth = ndimage.gaussian_filter(sk.astype(np.float32), sigma=1.0)
    gy, gx = np.gradient(smooth)
    # Tangent is perpendicular to gradient; angle in [0, pi).
    tangent_rad = (np.arctan2(gx, -gy)) % np.pi
    angles = np.rad2deg(tangent_rad).astype(np.float32)
    angles[~sk] = np.nan
    return sk, angles


# ---- skeleton walk (small DFS, no skan dependency) -------------------------

# 4-connected (orthogonal) neighbours listed FIRST so the DFS walk prefers a
# straight continuation when both an orthogonal and a diagonal neighbour are
# available. Scientist-tester M3: the v0.1.0 order put diagonals first, which
# biased near-axis fibers into a zig-zag walk and inflated arc length (and
# therefore depressed the chord/arc tortuosity systematically).
_OFFS = [(0, 1), (1, 0), (0, -1), (-1, 0), (1, 1), (1, -1), (-1, 1), (-1, -1)]


def neighbor_count(skel, y, x):
    h, w = skel.shape
    n = 0
    for dy, dx in _OFFS:
        yy, xx = y + dy, x + dx
        if 0 <= yy < h and 0 <= xx < w and skel[yy, xx]:
            n += 1
    return n


def walk_segments(skel):
    """Yield each maximal segment of the skeleton as a list of (y, x)."""
    visited = np.zeros_like(skel, dtype=bool)
    h, w = skel.shape
    # endpoints first, then any unvisited pixel.
    coords = list(zip(*np.where(skel)))
    if not coords:
        return
    deg = {p: neighbor_count(skel, *p) for p in coords}
    starts = [p for p, d in deg.items() if d <= 1] + [p for p, d in deg.items() if d != 1]
    for start in starts:
        if visited[start]:
            continue
        if deg.get(start, 0) == 0:
            visited[start] = True
            yield [start]
            continue
        path = [start]
        visited[start] = True
        cur = start
        prev = None
        while True:
            nxt = None
            for dy, dx in _OFFS:
                yy, xx = cur[0] + dy, cur[1] + dx
                if 0 <= yy < h and 0 <= xx < w and skel[yy, xx] and not visited[(yy, xx)]:
                    nxt = (yy, xx)
                    break
            if nxt is None:
                break
            visited[nxt] = True
            path.append(nxt)
            prev, cur = cur, nxt
            if deg.get(cur, 0) >= 3:
                break
        if len(path) >= 2:
            yield path


def segment_tortuosity(path):
    """Chord / arc for a single skeleton path."""
    if len(path) < 2:
        return float("nan")
    pts = np.array(path, dtype=np.float64)
    chord = float(np.linalg.norm(pts[-1] - pts[0]))
    diffs = np.diff(pts, axis=0)
    arc = float(np.sum(np.linalg.norm(diffs, axis=1)))
    if arc <= 0:
        return float("nan")
    return chord / arc  # 1.0 = perfectly straight; lower = wavier


def compute_skeleton_tortuosity(fiber_mask, window_grid=None, min_branch_px=2):
    """Compute per-skeleton-segment tortuosity (chord/arc), CT-FIRE convention.

    If ``window_grid`` is supplied (output of ``windows.compute_windows``),
    a per-window tortuosity array is added: each window's value is the
    median of all skeleton segments whose centroid falls inside that window.

    Returns a dict with ``mean_tortuosity``, ``n_segments``, and
    optionally ``per_window_tortuosity`` (np.ndarray, NaN where empty).
    """
    sk = skmorph.skeletonize(fiber_mask.astype(bool))
    segs = [p for p in walk_segments(sk) if len(p) >= int(min_branch_px)]
    tort = [segment_tortuosity(p) for p in segs]
    tort = [t for t in tort if t == t]  # drop NaNs
    out = {
        "mean_tortuosity": float(np.mean(tort)) if tort else float("nan"),
        "n_segments": len(tort),
    }
    if window_grid is None or not segs:
        return out

    grid_shape = window_grid["grid_shape"]
    window_px = window_grid["window_px"]
    stride_px = window_grid["stride_px"]
    per_win = np.full(grid_shape, np.nan, dtype=np.float32)
    n_fibers_per_win = np.zeros(grid_shape, dtype=np.int32)
    bins = [[] for _ in range(grid_shape[0] * grid_shape[1])]
    for seg, t in zip(segs, tort):
        if not (t == t):
            continue
        cy = float(np.mean([p[0] for p in seg]))
        cx = float(np.mean([p[1] for p in seg]))
        iy = int(cy // stride_px)
        ix = int(cx // stride_px)
        if 0 <= iy < grid_shape[0] and 0 <= ix < grid_shape[1]:
            bins[iy * grid_shape[1] + ix].append(t)
    for iy in range(grid_shape[0]):
        for ix in range(grid_shape[1]):
            vs = bins[iy * grid_shape[1] + ix]
            n_fibers_per_win[iy, ix] = len(vs)
            if vs:
                per_win[iy, ix] = float(np.median(vs))
    out["per_window_tortuosity"] = per_win
    # Scientist-tester minor: also expose the per-window contributing-segment
    # count so a pandas user can weight per-window tortuosity by segment count
    # when aggregating to a per-annotation number.
    out["per_window_n_fibers"] = n_fibers_per_win
    return out


# ---- Radon scalar metrics --------------------------------------------------

def compute_radon_scalar(image_scalar, fiber_mask, theta_step=1.0):
    """Per-ROI Radon transform with chord-length normalisation.

    Returns a dict with:
        pmr            : peak-to-mean ratio at the dominant angle.
        ai             : alignment index = sum(top 10% angles) / sum(all).
        fwhm_theta_deg : full-width-at-half-max of the angular profile (deg).
        fwhm_rho_um    : FWHM along rho (pixels here; caller scales if needed).
        entropy        : Shannon entropy of the normalised angular profile.
        theta_star_deg : dominant angle in degrees.

    Caller is responsible for choosing what scalar to feed in -- typically
    the HSV value channel.
    """
    img = image_scalar.astype(np.float32) * fiber_mask.astype(np.float32)
    if img.sum() <= 0:
        return {
            "pmr": float("nan"),
            "ai": float("nan"),
            "fwhm_theta_deg": float("nan"),
            "fwhm_rho_um": float("nan"),
            "entropy": float("nan"),
            "theta_star_deg": float("nan"),
        }
    # Inscribe-circle mask: zero anything outside the largest disk that fits
    # in the (possibly non-square) image, then run radon with ``circle=True``.
    # Without this step every ray that clips a corner of the bounding rectangle
    # contributes a longer chord than rays that pass through the centre, and
    # the angular profile shows a spurious 45-deg peak (the bias the
    # feasibility brief at `2026-05-15_ppm-radon-straightness-feasibility.md`
    # warned about). Scientist-tester M1.
    h, w = img.shape
    side = min(h, w)
    R = side // 2
    cy0 = (h - side) // 2
    cx0 = (w - side) // 2
    square = img[cy0:cy0 + side, cx0:cx0 + side]
    yy, xx = np.ogrid[:side, :side]
    disk = ((yy - R) ** 2 + (xx - R) ** 2) <= (R ** 2)
    square_masked = (square * disk).astype(np.float32)

    thetas = np.arange(0.0, 180.0, float(theta_step))
    sino = radon(square_masked, theta=thetas, circle=True)
    # Per-(rho, theta) chord-length normalisation: each ray through the
    # inscribed circle of radius R has chord length 2*sqrt(R^2 - rho^2) (the
    # geometric intercept). Divide each rho row by its chord so the
    # post-normalisation sinogram is bias-free against rho. Constant in theta,
    # so the array is broadcast along the angle axis.
    n_rho = sino.shape[0]
    rho_axis = np.arange(n_rho, dtype=np.float32) - (n_rho - 1) / 2.0
    # Clip rho to <= R (skimage may emit a slightly wider rho grid for even
    # side lengths; outside-disk rows get chord = 0 -> NaN after division).
    rho_inside = np.where(np.abs(rho_axis) < R, rho_axis, np.nan)
    chord_per_row = 2.0 * np.sqrt(np.maximum(R * R - rho_inside * rho_inside, 0.0))
    chord_col = chord_per_row[:, np.newaxis]  # (n_rho, 1) -- broadcasts to (n_rho, n_theta)
    sino_n = np.where(chord_col > 1e-9, sino / np.maximum(chord_col, 1e-9), 0.0).astype(np.float32)

    # Angular profile: total energy per angle.
    angular = sino_n.sum(axis=0)
    angular = angular / max(angular.sum(), 1e-12)
    theta_star_idx = int(np.argmax(angular))
    theta_star = float(thetas[theta_star_idx])

    peak = float(np.max(sino_n))
    mean_v = float(np.mean(sino_n))
    pmr = peak / max(mean_v, 1e-12)

    # Alignment index: top 10% angles vs all angles.
    n_top = max(1, int(round(0.1 * len(thetas))))
    top_idx = np.argsort(angular)[-n_top:]
    ai = float(angular[top_idx].sum())

    # FWHM in theta around the peak.
    half = angular[theta_star_idx] / 2.0
    above = angular >= half
    if above.any():
        idxs = np.where(above)[0]
        fwhm_theta = float((idxs.max() - idxs.min() + 1) * theta_step)
    else:
        fwhm_theta = 0.0

    # FWHM in rho at the dominant theta column.
    col = sino_n[:, theta_star_idx]
    col_max = float(col.max())
    if col_max > 0:
        above_rho = col >= col_max / 2.0
        if above_rho.any():
            idxs = np.where(above_rho)[0]
            fwhm_rho = float(idxs.max() - idxs.min() + 1)
        else:
            fwhm_rho = 0.0
    else:
        fwhm_rho = 0.0

    # Shannon entropy of the angular profile (bits).
    p = np.clip(angular, 1e-12, 1.0)
    entropy = float(-(p * np.log2(p)).sum())

    return {
        "pmr": pmr,
        "ai": ai,
        "fwhm_theta_deg": fwhm_theta,
        "fwhm_rho_um": fwhm_rho,
        "entropy": entropy,
        "theta_star_deg": theta_star,
    }
