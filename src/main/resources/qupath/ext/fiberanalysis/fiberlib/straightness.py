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
import math

import numpy as np
from scipy import ndimage
from skimage import filters as skfilters
from skimage import morphology as skmorph
from skimage.transform import radon

logger = logging.getLogger("fiber.straightness")


# ---- skeletonise + per-pixel tangents --------------------------------------

def skeletonize_and_tangents(fiber_mask, grad_sigma=1.0, tensor_sigma=4.0):
    """Skeletonise the fiber mask and estimate per-pixel fiber orientation.

    Returns ``(skeleton, fiber_angles_deg)``:
      - ``skeleton`` : (H, W) bool, 1-pixel-wide medial axis.
      - ``fiber_angles_deg`` : (H, W) float, fiber orientation in degrees
        [0, 180); NaN outside the fiber mask. 0 is horizontal, 90 vertical.

    The angle field is dense over the whole fiber mask, not just the
    skeleton. ``windows.compute_windows`` gates a window on having at least
    ``min_pixels`` angled pixels, defaulting to 10% of the window AREA -- a
    threshold inherited from ppm_library, where the orientation map is dense.
    A skeleton is one pixel wide, so a skeleton-only field could never reach
    10% of an area unless fibers were packed ten pixels apart: on the
    MH_Colon run that gate left 7,891 of 7,935 scored windows with a null
    mean_angle_deg and order_parameter.

    Orientation comes from the structure tensor of the smoothed fiber mask,
    integrated over ``tensor_sigma``, and NOT from the gradient at the
    skeleton pixel itself. The skeleton lies on the ridge crest of the
    smoothed mask, where the gradient vanishes by construction: measured on a
    clean phantom the gradient magnitude on-skeleton is 0.001 against 0.145
    one pixel away on the flank, so ``arctan2(gx, -gy)`` degenerated to
    ``arctan2(0, 0) == 0`` and every fiber read as horizontal. Integrating the
    tensor over a neighbourhood pulls in the flanks, where the gradient is
    both large and perpendicular to the fiber.

    Validated on the known-angle phantoms in
    ``tools/collagen-phantom-creation`` (oa-angle-000 .. -135): mean absolute
    error 0.07 deg, worst case 0.16 deg. The gradient operator is Scharr
    rather than a central difference because it is optimised for rotational
    symmetry -- ``np.gradient`` biases 30 and 60 deg toward 45 by about 2 deg.

    Args:
        fiber_mask:   (H, W) bool-ish, segmented fiber pixels.
        grad_sigma:   Gaussian smoothing applied before differentiating.
        tensor_sigma: integration scale of the structure tensor. Should be
                      comparable to the fiber spacing; too small re-exposes
                      the vanishing-gradient problem, too large blurs
                      neighbouring fibers of different orientation together.
    """
    mask = np.asarray(fiber_mask).astype(bool)
    sk = skmorph.skeletonize(mask)
    if not sk.any():
        return sk, np.full(sk.shape, np.nan, dtype=np.float32)

    smooth = ndimage.gaussian_filter(mask.astype(np.float32), sigma=float(grad_sigma))
    gy = skfilters.scharr_h(smooth)
    gx = skfilters.scharr_v(smooth)
    jxx = ndimage.gaussian_filter(gx * gx, float(tensor_sigma))
    jyy = ndimage.gaussian_filter(gy * gy, float(tensor_sigma))
    jxy = ndimage.gaussian_filter(gx * gy, float(tensor_sigma))

    # Dominant GRADIENT direction; the fiber runs perpendicular to it.
    theta_grad = 0.5 * np.arctan2(2.0 * jxy, jxx - jyy)
    angles = np.rad2deg((theta_grad + np.pi / 2.0) % np.pi).astype(np.float32)
    angles[~mask] = np.nan
    return sk, angles


# ---- skeleton walk (small DFS, no skan dependency) -------------------------

# 4-connected (orthogonal) neighbours listed FIRST so the DFS walk prefers a
# straight continuation when both an orthogonal and a diagonal neighbour are
# available. Scientist-tester M3: the v0.1.0 order put diagonals first, which
# biased near-axis fibers into a zig-zag walk and inflated arc length (and
# therefore depressed the chord/arc tortuosity systematically).
_OFFS = [(0, 1), (1, 0), (0, -1), (-1, 0), (1, 1), (1, -1), (-1, 1), (-1, -1)]

# The 8 neighbours in ring order (clockwise from North), for the Rutovitz
# crossing number. Order matters: the crossing number counts 0->1 transitions
# as you walk the ring, so a shuffled list gives a different -- wrong -- answer.
_RING = [(-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1)]


def _ring_stack(skel):
    """(8, H, W) uint8 stack of the ring neighbours of every pixel."""
    sk = np.asarray(skel, dtype=bool)
    h, w = sk.shape
    padded = np.pad(sk.astype(np.uint8), 1)
    return np.stack([padded[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy, dx in _RING], axis=0)


def neighbour_counts(skel):
    """(H, W) int, number of set 8-neighbours of each pixel."""
    return _ring_stack(skel).sum(axis=0).astype(np.int32)


def crossing_numbers(skel):
    """(H, W) int, the Rutovitz crossing number of each pixel.

    The number of 0->1 transitions around the 8-neighbour ring: 1 at an
    ordinary pixel whose two neighbours are themselves adjacent, 2 along a
    chain, and >= 3 only at a true junction.

    This is the correct junction test on an 8-connected skeleton; a raw
    neighbour count is not. A rasterised diagonal runs as a staircase, and a
    staircase corner has THREE neighbours while being topologically ordinary
    -- two of them are adjacent to each other, so the ring makes only two
    transitions. Counting neighbours instead calls every such corner a branch:
    measured on the waviness phantoms, 65% to 97% of the "branch points" found
    that way are staircase corners. On wav-00, straight horizontal fibers with
    69 real junctions, a neighbour count reports 2,245.

    Endpoints are the other way round -- use a neighbour count of 1 for those,
    because a staircase corner has crossing number 1 as well.
    """
    ring = _ring_stack(skel)
    nxt = np.roll(ring, -1, axis=0)
    return ((nxt == 1) & (ring == 0)).sum(axis=0).astype(np.int32)


def junction_mask(skel):
    """(H, W) bool, pixels that are true skeleton junctions."""
    sk = np.asarray(skel, dtype=bool)
    return sk & (crossing_numbers(sk) >= 3)


def neighbor_count(skel, y, x):
    h, w = skel.shape
    n = 0
    for dy, dx in _OFFS:
        yy, xx = y + dy, x + dx
        if 0 <= yy < h and 0 <= xx < w and skel[yy, xx]:
            n += 1
    return n


def walk_segments(skel):
    """Yield each maximal segment of the skeleton as a list of (y, x).

    A segment ends at a true junction, found with the Rutovitz crossing
    number rather than a neighbour count -- see :func:`crossing_numbers`. The
    difference is not cosmetic: breaking at every pixel with three neighbours
    cut each fiber at every staircase corner of its own rasterisation, so
    wav-00 (straight horizontal fibers, 540 of them, 69 real junctions) came
    apart into 1,969 pieces with a median length of 2 pixels.
    """
    sk = np.asarray(skel, dtype=bool)
    visited = np.zeros_like(sk, dtype=bool)
    h, w = sk.shape
    coords = list(zip(*np.where(sk)))
    if not coords:
        return
    nb = neighbour_counts(sk)
    is_junction = junction_mask(sk)
    # Endpoints first so a fiber is walked end to end, then everything else.
    starts = [p for p in coords if nb[p] <= 1] + [p for p in coords if nb[p] != 1]
    for start in starts:
        if visited[start]:
            continue
        if nb[start] == 0:
            visited[start] = True
            yield [start]
            continue
        path = [start]
        visited[start] = True
        cur = start
        while True:
            nxt = None
            # _OFFS lists the orthogonal neighbours first, so a staircase
            # corner steps onto its near neighbour and consumes the whole
            # run instead of jumping the corner and stranding a pixel.
            for dy, dx in _OFFS:
                yy, xx = cur[0] + dy, cur[1] + dx
                if 0 <= yy < h and 0 <= xx < w and sk[yy, xx] and not visited[yy, xx]:
                    nxt = (yy, xx)
                    break
            if nxt is None:
                break
            visited[nxt] = True
            path.append(nxt)
            cur = nxt
            if is_junction[cur]:
                break
        if len(path) >= 2:
            yield path


# ---- linking segments through junctions ------------------------------------

#: Largest change of direction, in degrees, that still counts as the same
#: fiber continuing through a junction. 70 deg is permissive enough for a
#: crimped fiber and tight enough that two fibers crossing near a right angle
#: are not welded into one.
MAX_LINK_TURN_DEG = 70.0

#: How many pixels from a junction are used to estimate a link's direction.
#: Short enough to be local, long enough to survive one staircase corner.
LINK_DIR_WINDOW_PX = 8


def _neighbours(skel, y, x):
    h, w = skel.shape
    out = []
    for dy, dx in _OFFS:
        yy, xx = y + dy, x + dx
        if 0 <= yy < h and 0 <= xx < w and skel[yy, xx]:
            out.append((yy, xx))
    return out


def _end_direction(path, window):
    """Unit vector along the first `window` pixels, pointing away from path[0]."""
    n = min(int(window), len(path))
    if n < 2:
        return (0.0, 0.0)
    dy = float(path[n - 1][0] - path[0][0])
    dx = float(path[n - 1][1] - path[0][1])
    mag = math.hypot(dy, dx)
    return (0.0, 0.0) if mag == 0.0 else (dy / mag, dx / mag)


def _trace_links(skel, node):
    """Chains of pixels running between two nodes. Returns (a, b, path) tuples."""
    links = []
    traced = set()
    for y, x in zip(*np.where(node)):
        start = (int(y), int(x))
        for first in _neighbours(skel, *start):
            if (start, first) in traced:
                continue
            path = [start, first]
            seen = {start, first}
            prev, cur = start, first
            while not node[cur]:
                cands = [q for q in _neighbours(skel, *cur) if q != prev and q not in seen]
                if not cands:
                    break
                if len(cands) == 1:
                    nxt = cands[0]
                else:
                    # Ambiguous only at a staircase corner, where one candidate
                    # continues the run and the other doubles back.
                    dy, dx = cur[0] - prev[0], cur[1] - prev[1]
                    nxt = max(cands, key=lambda q: (q[0] - cur[0]) * dy + (q[1] - cur[1]) * dx)
                path.append(nxt)
                seen.add(nxt)
                prev, cur = cur, nxt
            traced.add((start, first))
            traced.add((cur, path[-2]))
            links.append((start, cur, path))
    return links


def _pair_links_at_junctions(links, node_is_junction, max_turn_deg, dir_window):
    """Match up link ends at each junction by straightest continuation."""
    incident = {}
    for idx, (a, b, _path) in enumerate(links):
        incident.setdefault(a, []).append((idx, 0))
        incident.setdefault(b, []).append((idx, 1))

    partner = {}
    # Two links continue each other when their outward directions are opposed,
    # so the straightest join is the most negative dot product.
    cos_limit = math.cos(math.radians(180.0 - max_turn_deg))
    for nodept, ends in incident.items():
        if not node_is_junction.get(nodept, False) or len(ends) < 2:
            continue
        dirs = {}
        for idx, end in ends:
            path = links[idx][2]
            dirs[(idx, end)] = _end_direction(path if end == 0 else path[::-1], dir_window)
        cands = []
        for i in range(len(ends)):
            for j in range(i + 1, len(ends)):
                a, b = ends[i], ends[j]
                if a[0] == b[0]:
                    continue
                ua, ub = dirs[a], dirs[b]
                cands.append((ua[0] * ub[0] + ua[1] * ub[1], a, b))
        cands.sort(key=lambda t: t[0])
        taken = set()
        for dot, a, b in cands:
            if dot > cos_limit:
                break
            if a in taken or b in taken:
                continue
            partner[a] = b
            partner[b] = a
            taken.add(a)
            taken.add(b)
    return partner


def trace_fibers(skel, max_turn_deg=MAX_LINK_TURN_DEG, dir_window_px=LINK_DIR_WINDOW_PX):
    """Yield whole fibers as pixel paths, linked through crossings.

    A skeleton of overlapping collagen is a dense network, not a set of
    separate fibers: on the waviness phantoms, 540 drawn fibers produce up to
    4,117 real junctions, about 22 per fiber. Cutting the skeleton at every
    junction -- which is what :func:`walk_segments` does -- therefore measures
    fragments. At wav-55 the median piece was 28 pixels against a drawn fiber
    length of 160.

    This follows a fiber through a junction instead, choosing the continuation
    whose direction best matches the one arriving, and refusing the join when
    the turn exceeds ``max_turn_deg``. Measured against the phantoms, linking
    roughly doubles the median fiber length (wav-12: 38 -> 92 px) and widens
    the straightness response to waviness by a third (p10 range 0.339 ->
    0.450). The same approach is what CT-FIRE uses to rebuild fibers across
    crossings.

    Args:
        skel:          (H, W) bool-ish, a one-pixel-wide skeleton.
        max_turn_deg:  largest direction change still treated as one fiber.
        dir_window_px: pixels used to estimate a link's direction at a node.

    Returns:
        list of pixel paths, each a list of (y, x) in order along the fiber.
    """
    sk = np.asarray(skel, dtype=bool)
    if not sk.any():
        return []
    nb = neighbour_counts(sk)
    junction = junction_mask(sk)
    # Mask by the skeleton: a background pixel also has few neighbours, and
    # leaving it in seeds a link from every pixel next to a fiber.
    node = sk & (junction | (nb <= 1))
    links = _trace_links(sk, node)
    if not links:
        return []
    is_junction = {}
    for a, b, _p in links:
        is_junction[a] = bool(junction[a])
        is_junction[b] = bool(junction[b])
    partner = _pair_links_at_junctions(links, is_junction, max_turn_deg, dir_window_px)

    out = []
    used = set()
    for idx in range(len(links)):
        if idx in used:
            continue
        for end in (0, 1):
            if (idx, end) in partner:
                continue  # mid-chain, not a start
            chain = []
            ci, ce = idx, end
            while ci not in used:
                used.add(ci)
                path = links[ci][2]
                path = path if ce == 0 else path[::-1]
                chain.extend(path if not chain else path[1:])
                nxt = partner.get((ci, 1 - ce))
                if nxt is None:
                    break
                ci, ce = nxt
            if len(chain) >= 2:
                out.append(chain)
            break
    for idx in range(len(links)):  # closed loops, which have no chain start
        if idx not in used:
            used.add(idx)
            if len(links[idx][2]) >= 2:
                out.append(links[idx][2])
    return out


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


#: Straightness below which a fiber is called wavy, for `wavy_fraction`.
#: 0.85 is the cut that spreads the waviness phantoms best: 0.3% of fibers
#: fall below it at wav-00, then 7.9%, 27.9%, 85.9%, 93.0% as waviness rises.
#: No fixed cut spreads the whole range -- 0.95 is already at 89.9% by wav-12
#: and 0.70 still reads 0% at wav-12 -- because a threshold on a shifting
#: distribution is a step function. `wavy_fraction` is for interpretability;
#: `straightness_p10` is the statistic to use when sensitivity matters.
WAVY_STRAIGHTNESS_CUT = 0.85


def compute_skeleton_tortuosity(fiber_mask, window_grid=None, min_branch_px=2):
    """Per-fiber straightness (chord/arc), CT-FIRE convention.

    Fibers are traced through crossings by :func:`trace_fibers` rather than
    cut at every junction, so a value describes a fiber rather than the
    piece of one that happened to fall between two overlaps.

    Note the direction of the ratio: this is chord/arc, which is 1.0 for a
    straight fiber and FALLS as the fiber becomes wavy. That is CT-FIRE's
    `straightness`, not tortuosity, which is its reciprocal. The key name
    `mean_tortuosity` is kept because it is already in shipped results.json
    files and QuPath measurement tables.

    If ``window_grid`` is supplied (output of ``windows.compute_windows``),
    a per-window array is added: each window's value is the median over all
    fibers whose centroid falls inside that window.

    Returns a dict with the summary statistics below, plus optionally
    ``per_window_tortuosity`` (np.ndarray, NaN where empty):

      mean_tortuosity        unweighted mean over fibers (chord/arc).
      mean_straightness_len  length-weighted mean. A 3-pixel stub and a
                             300-pixel fiber count equally in the unweighted
                             mean; weighting widens the response to waviness
                             from 0.092 to 0.156 over the phantom sweep.
      straightness_p10       10th percentile -- the waviest decile, and the
                             single most responsive statistic measured
                             (range 0.45 across the sweep after linking).
      straightness_median    50th percentile.
      straightness_sd        standard deviation across fibers.
      wavy_fraction          fraction below WAVY_STRAIGHTNESS_CUT. Easy to
                             read, but it saturates -- see that constant.
      n_segments             number of fibers measured.
      median_fiber_len_px    median traced length. Report it: it is the
                             number that says whether the others describe
                             fibers or fragments.
    """
    sk = skmorph.skeletonize(fiber_mask.astype(bool))
    segs = [p for p in trace_fibers(sk) if len(p) >= int(min_branch_px)]
    paired = [(p, segment_tortuosity(p)) for p in segs]
    paired = [(p, t) for p, t in paired if t == t]  # drop NaNs
    segs = [p for p, _t in paired]
    tort = np.array([t for _p, t in paired], dtype=np.float64)
    lens = np.array([len(p) for p in segs], dtype=np.float64)
    if tort.size:
        out = {
            "mean_tortuosity": float(tort.mean()),
            "mean_straightness_len": float((tort * lens).sum() / max(lens.sum(), 1e-12)),
            "straightness_p10": float(np.percentile(tort, 10)),
            "straightness_median": float(np.median(tort)),
            "straightness_sd": float(tort.std()),
            "wavy_fraction": float(np.mean(tort < WAVY_STRAIGHTNESS_CUT)),
            "n_segments": int(tort.size),
            "median_fiber_len_px": float(np.median(lens)),
        }
    else:
        nan = float("nan")
        out = {
            "mean_tortuosity": nan,
            "mean_straightness_len": nan,
            "straightness_p10": nan,
            "straightness_median": nan,
            "straightness_sd": nan,
            "wavy_fraction": nan,
            "n_segments": 0,
            "median_fiber_len_px": nan,
        }
    tort = list(tort)
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

# Fraction of the inscribed-circle radius kept when forming the angular
# profile. The chord-length normalisation divides by 2*sqrt(R^2 - rho^2),
# which tends to zero at the rim, so the outermost rho rows amplify noise by
# an unbounded factor. Discarding them costs nothing measurable (the alignment
# dynamic range varies by under 10% for guards between 0.6 and 1.0) and keeps
# the variance profile from being driven by the rim.
RHO_GUARD_FRAC = 0.8


def compute_radon_scalar(image_scalar, fiber_mask, theta_step=1.0):
    """Per-ROI Radon transform with chord-length normalisation.

    Returns a dict with:
        pmr            : peak-to-mean ratio of the angular profile.
        ai             : alignment index = sum(top 10% angles) / sum(all).
                         0.1 is the isotropic floor; 1.0 is perfect alignment.
        fwhm_theta_deg : full-width-at-half-max of the angular profile (deg).
                         180 means no dominant orientation.
        fwhm_rho_um    : FWHM along rho at the dominant angle, in PIXELS. The
                         caller must multiply by the pixel size to honour the
                         key's name; pipeline.py does this at the call site.
        entropy        : Shannon entropy of the angular profile (bits).
                         log2(n_angles) = 7.49 at 1 deg steps is isotropic.
        theta_star_deg : dominant FIBER orientation in image coordinates,
                         [0, 180), 0 horizontal -- the same convention as
                         ``mean_angle_deg`` from ``windows.compute_windows``.

    The angular profile is the VARIANCE of each projection over rho, not its
    sum. The Radon transform conserves mass, so every column of the sinogram
    sums to the same total and ``sino.sum(axis=0)`` is constant in theta by
    construction -- measured CV 0.04% raw, 0.6% after chord normalisation.
    Through v0.3.2 that constant was the angular profile, which pinned ai at
    the isotropic 0.1018, entropy at 7.4918 against a 7.4919 ceiling, and
    fwhm_theta_deg at 180.0, and left theta_star_deg reading the argmax of
    numerical noise (errors of 63 to 89 deg on known-angle phantoms).
    Orientation lives in how CONCENTRATED each projection is, which is what
    the variance measures.

    Validated against ``tools/collagen-phantom-creation``: theta_star_deg
    exact to 1 deg on the known-angle series, and ai / entropy / pmr /
    fwhm_theta_deg each rank the alignment sweep at Spearman |rho| >= 0.93
    against the generator's order parameter (1.0000 down to 0.0203).

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
    rho_axis = np.arange(n_rho, dtype=np.float64) - (n_rho - 1) / 2.0
    chord_per_row = 2.0 * np.sqrt(np.maximum(R * R - rho_axis * rho_axis, 0.0))
    chord_col = chord_per_row[:, np.newaxis]  # (n_rho, 1) -- broadcasts to (n_rho, n_theta)
    sino_n = np.where(chord_col > 1e-9, sino / np.maximum(chord_col, 1e-9), 0.0).astype(np.float32)
    kept = np.abs(rho_axis) <= RHO_GUARD_FRAC * R
    sino_k = sino_n[kept]

    # Angular profile: how concentrated each projection is, per angle.
    profile = sino_k.var(axis=0)
    angular = profile / max(float(profile.sum()), 1e-12)
    theta_star_idx = int(np.argmax(angular))
    # skimage projects along the direction that rotating by theta sends to
    # vertical, so a fiber at image angle A peaks at theta = 90 - A.
    theta_star = float((90.0 - thetas[theta_star_idx]) % 180.0)

    peak = float(profile.max())
    mean_v = float(profile.mean())
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
    col = sino_k[:, theta_star_idx]
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
