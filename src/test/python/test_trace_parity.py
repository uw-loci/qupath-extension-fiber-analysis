"""
fiberlib 0.4.4: the faster fibre tracing and curvature give the same answers,
bit for bit, as the code they replaced (kept below as the reference).
"""

import math
import os
import sys

import numpy as np
from skimage.morphology import skeletonize

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis")
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import morphometrics as morph  # noqa: E402
from fiberlib import straightness as straight  # noqa: E402


# ---- reference: fiberlib 0.4.3 ----------------------------------------------


def _ref_neighbours(skel, y, x):
    h, w = skel.shape
    out = []
    for dy, dx in straight._OFFS:
        yy, xx = y + dy, x + dx
        if 0 <= yy < h and 0 <= xx < w and skel[yy, xx]:
            out.append((yy, xx))
    return out


def _ref_trace_links(skel, node):
    links = []
    traced = set()
    for y, x in zip(*np.where(node)):
        start = (int(y), int(x))
        for first in _ref_neighbours(skel, *start):
            if (start, first) in traced:
                continue
            path = [start, first]
            seen = {start, first}
            prev, cur = start, first
            while not node[cur]:
                cands = [q for q in _ref_neighbours(skel, *cur) if q != prev and q not in seen]
                if not cands:
                    break
                if len(cands) == 1:
                    nxt = cands[0]
                else:
                    dy, dx = cur[0] - prev[0], cur[1] - prev[1]
                    nxt = max(cands, key=lambda q: (q[0] - cur[0]) * dy + (q[1] - cur[1]) * dx)
                path.append(nxt)
                seen.add(nxt)
                prev, cur = cur, nxt
            traced.add((start, first))
            traced.add((cur, path[-2]))
            links.append((start, cur, path))
    return links


def _ref_mean_curvature(skeleton, segment_len_px=4):
    sk = skeleton.astype(bool)
    if not sk.any():
        return float("nan")
    seg_len = max(2, int(segment_len_px))
    rotations_per_unit = []
    for path in straight.trace_fibers(sk):
        n = len(path)
        if n < 2:
            continue
        pts = np.asarray(path, dtype=np.float64)
        v_start = pts[min(seg_len, n - 1)] - pts[0]
        v_end = pts[-1] - pts[max(0, n - 1 - seg_len)]
        if np.linalg.norm(v_start) == 0 or np.linalg.norm(v_end) == 0:
            continue
        dtheta = abs(math.atan2(v_end[1], v_end[0]) - math.atan2(v_start[1], v_start[0]))
        dtheta = min(dtheta, 2 * math.pi - dtheta)
        dtheta = min(dtheta, math.pi - dtheta)
        arc = float(np.sum(np.linalg.norm(np.diff(pts, axis=0), axis=1)))
        if arc <= 0:
            continue
        rotations_per_unit.append(dtheta / arc)
    if not rotations_per_unit:
        return float("nan")
    return float(np.mean(rotations_per_unit))


# ---- fixtures ---------------------------------------------------------------


def _skeleton(seed, h, w, n_lines=12):
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w), dtype=bool)
    for _ in range(n_lines):
        y0, x0 = rng.integers(0, h), rng.integers(0, w)
        ang = rng.uniform(0, np.pi)
        wave = rng.uniform(0, 6)
        t = np.arange(-max(h, w), max(h, w))
        ys = np.round(y0 + t * np.sin(ang) + wave * np.sin(t / 7.0)).astype(int)
        xs = np.round(x0 + t * np.cos(ang)).astype(int)
        keep = (ys >= 0) & (ys < h) & (xs >= 0) & (xs < w)
        img[ys[keep], xs[keep]] = True
        img[np.clip(ys[keep] + 1, 0, h - 1), xs[keep]] = True
    return skeletonize(img)


def _node(sk):
    nb = straight.neighbour_counts(sk)
    return sk & (straight.junction_mask(sk) | (nb <= 1))


def _bits(x):
    return np.asarray(x, dtype=np.float64).view(np.int64)


# ---- tests ------------------------------------------------------------------


def test_trace_links_match_the_reference():
    for seed, (h, w) in enumerate([(160, 160), (90, 230), (40, 3), (3, 40), (2, 50), (50, 2), (1, 30), (30, 1)]):
        sk = _skeleton(seed, h, w) if min(h, w) > 2 else np.random.default_rng(seed).random((h, w)) < 0.6
        node = _node(sk)
        assert straight._trace_links(sk, node) == _ref_trace_links(sk, node), (h, w)


def test_curvature_matches_the_reference_on_every_window():
    sk = _skeleton(7, 400, 400, n_lines=40)
    for y in range(0, 400, 50):
        for x in range(0, 400, 50):
            win = sk[y:y + 50, x:x + 50]
            for seg in (2, 4, 12):
                got = morph.mean_curvature(win, segment_len_px=seg)
                want = _ref_mean_curvature(win, segment_len_px=seg)
                assert _bits(got) == _bits(want), (y, x, seg)


def test_a_shared_trace_gives_the_same_values():
    sk = _skeleton(11, 300, 300, n_lines=30)
    fibers = straight.trace_fibers(sk)
    assert _bits(morph.mean_curvature(sk, 5, fibers=fibers)) == _bits(morph.mean_curvature(sk, 5))
    a = straight.compute_skeleton_tortuosity(sk, min_branch_px=3, skeleton=sk)
    b = straight.compute_skeleton_tortuosity(sk, min_branch_px=3, skeleton=sk, fibers=fibers)
    assert a.keys() == b.keys()
    for k in a:
        assert _bits(a[k]) == _bits(b[k]), k
