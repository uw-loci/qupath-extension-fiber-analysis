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

__version__ = "0.2.6"

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
#
# 0.2.4 (2026-05-31):
#   - NEW pipeline.py: the full orchestration is now a callable library
#     function `fiberlib.analyze(image, ...)` (numpy in -> result dict out),
#     with no Appose/QuPath dependency. __init__.py re-exports analyze +
#     sanitize_json. scripts/run_fiber_analysis.py became a thin wrapper that
#     unpacks the Appose globals, loads the PNGs, and calls analyze(). No
#     behavior change to the QuPath path; this just makes the same pipeline
#     importable for tests / batch / the collagen-phantom tooling. A repo-root
#     pyproject.toml makes `pip install -e .` expose `import fiberlib`.
#     columns next to n_fiber_px / tortuosity_median.
#
# 0.2.5 (2026-06-07):
#   - pipeline.py morph rendering: NaN-mask per-window arrays against a
#     `zone_included` boolean grid (any zone_mask pixel in the window cell)
#     before handing them to render_window_heatmap. Fixes ring annotations
#     (e.g. tumor circles with N-um border zone) where the morph heatmaps
#     were papering the full bounding box with 0-valued viridis dark-purple
#     in the corners, suggesting "value=0 there" when actually "outside the
#     analysis zone". GLCM rendered correctly because its per-window arrays
#     are NaN-initialised and only filled where fiber_mask has signal; morph
#     uses raw integer n_pixels / n_fibers grids which stay 0 in empty
#     corners. The same zone gate now applies to the legacy
#     morphometrics_overlay.png (HDM) too.
#
# 0.2.6 (2026-09-10):
#   - segmentation.py segment_internal: the per-region min-max stretch now runs
#     ONLY when a ridge filter produced a response. It previously ran always,
#     including with ridge_filter='none', which undid the absolute bit-depth
#     scaling pick_channel had just applied. A manual threshold was therefore a
#     percentile of each region's own dynamic range, not a gray level: the same
#     setting of 128 cut at raw 32896, 6124 and 6012 in three 16-bit regions
#     differing only in range. Manual is now an absolute cut in the source
#     image's own gray levels (4500 cuts at 4500, every region, every image).
#     Otsu / triangle are unaffected -- a min-max stretch is affine, so it moved
#     their threshold without moving the partition. threshold_scalar takes the
#     divisor as manual_full_scale; new full_scale() mirrors _scale_to_unit.
#     Rolling-ball no longer rescales by its own max on the absolute path.
#   - scripts/calibrate_threshold.py: same change, and it matters more here --
#     min-max stretching EACH region before pooling the histogram gave every
#     region its own scale, which is precisely the per-region adaptivity the
#     project calibration exists to remove. The pooled histogram is now a real
#     project-wide absolute one when no ridge filter is in play.
#   - segmentation.py: resolved threshold logged at INFO for every method, so
#     an otsu / triangle / project_otsu run records what it actually cut at.
