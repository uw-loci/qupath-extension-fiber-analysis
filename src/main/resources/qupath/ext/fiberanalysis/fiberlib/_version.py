"""Single source of truth for the bundled fiberlib version.

Bumped in lockstep with REQUIRED_FIBERLIB_VERSION in ApposeFiberService.java
WHENEVER any fiberlib/*.py file changes. The Java side caches the unpacked
fiberlib/ directory on disk and re-extracts only when this version differs
from the one stored in <env>/fiberlib_pkg/fiberlib/_installed_version.txt --
forgetting the bump means users keep running stale Python.

0.2.0 (2026-05-27):
  - segmentation.py: invert_intensity, rolling_ball_radius, project_otsu
    threshold method, project_threshold_norm kwarg on segment_internal
  - io.py: per-window fiber_coverage_percent
  - render.py: magenta fiber-mask overlay (was light grey, effectively invisible)

0.2.1 (2026-05-27):
  - segmentation.py apply_ridge_filter: black_ridges=False default (was
    silently defaulting to True via scikit-image, which made Frangi /
    Sato / Meijering hunt the dark GAPS between bright fibers instead of
    the fibers themselves). Tied to invert_intensity so DAB workflows
    still get black_ridges=True.
  - segmentation.py pick_channel: proper bit-depth-aware normalisation
    (16-bit grayscale was being divided by 255, producing values up to
    257 instead of ~1.0 -- broke downstream Otsu / Frangi sigma
    expectations).
"""
__version__ = "0.2.3"

# 0.2.2 (2026-05-27):
#   - io.py: per-window `included` flag (False when the parent script set
#     `_invalid_below_coverage[iy, ix]` due to the min-window-coverage gate).
#     Below-threshold cells now write a stub entry with no metrics; the
#     Java side filters PathObject creation accordingly.
#
# 0.2.3 (2026-05-27):
#   - morphometrics.py: new per_window_morphometrics() function. Returns
#     per-window arrays for total_length_px, branch_points, endpoints,
#     mean_curvature_per_px, fractal_dimension, lacunarity_box_<N> +
#     lacunarity_mean, gap_mean_px / gap_max_px. Replaces the previous
#     "only HDM / coverage / ridge count have per-window heatmaps"
#     editorial -- the user chooses window size + overlap, so we emit
#     per-window everything that can be expressed per window and let
#     them decide what is meaningful at their scale.
#   - io.py: flattens window_grid["morphometrics"] sub-dict into each
#     window entry so pd.json_normalize(windows) sees them as flat
#     columns next to n_fiber_px / tortuosity_median.
