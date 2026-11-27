"""Per-window straightness and fiber counts with overlapping windows."""

import os
import sys

import numpy as np

sys.path.insert(
    0,
    os.path.join(os.path.dirname(__file__), "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"),
)

from fiberlib import straightness as st  # noqa: E402
from fiberlib.straightness import _windows_holding  # noqa: E402


def test_windows_holding_matches_the_window_boxes():
    window, stride, n = 10, 5, 7
    for c10 in range(0, 400):
        c = c10 / 10.0
        want = [i for i in range(n) if i * stride <= c < i * stride + window]
        assert list(_windows_holding(c, window, stride, n)) == want, c


def test_no_overlap_keeps_one_window_per_fiber():
    assert list(_windows_holding(12.0, 10, 10, 5)) == [1]


def test_a_fiber_counts_in_every_overlapping_window_that_holds_it():
    mask = np.zeros((40, 40), dtype=bool)
    mask[12, 10:21] = True  # horizontal fiber, center (12, 15)
    grid = {"grid_shape": (7, 7), "window_px": 10, "stride_px": 5}
    out = st.compute_skeleton_tortuosity(mask, window_grid=grid, min_branch_px=2)
    n = out["per_window_n_fibers"]
    # Rows 1 and 2 hold y = 12 ([5, 15) and [10, 20)); columns 2 and 3 hold x = 15.
    assert {tuple(i) for i in np.argwhere(n > 0)} == {(1, 2), (1, 3), (2, 2), (2, 3)}
    assert n.sum() == 4
