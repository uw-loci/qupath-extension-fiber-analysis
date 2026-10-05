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

__version__ = "0.3.4"

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
#     divisor as manual_full_scale; new full_scale() mirrors scale_to_unit.
#     Rolling-ball no longer rescales by its own max on the absolute path.
#   - scripts/calibrate_threshold.py: same change, and it matters more here --
#     min-max stretching EACH region before pooling the histogram gave every
#     region its own scale, which is precisely the per-region adaptivity the
#     project calibration exists to remove. The pooled histogram is now a real
#     project-wide absolute one when no ridge filter is in play.
#   - segmentation.py: resolved threshold logged at INFO for every method, so
#     an otsu / triangle / project_otsu run records what it actually cut at.
#
# 0.2.7 (2026-09-10):
#   - segmentation.py: _scale_to_unit is now public scale_to_unit, because two
#     other modules needed it and had each grown their own wrong version.
#   - pipeline.py rgb_to_value: scaled by dtype instead of dividing by 255
#     unconditionally -- the same defect pick_channel's docstring says was fixed
#     in 0.2.1, still live here. On a uint16 region it returned values up to
#     257, and texture.quantise then divided by 255 a SECOND time to bring them
#     back. 255*255 is 65025, not 65535, so GLCM ran on values ~0.8% low and
#     only worked at all because both bugs were present.
#   - texture.py quantise: trusts its documented [0,1] input and only clips.
#     Also drops a dead `whole` assignment.

# (0.2.8 through 0.3.2 -- the tiling / owned-core, whole-zone, channel-picker
#  and project-calibration work -- were shipped without an entry here. Noted
#  rather than reconstructed after the fact.)
#
# 0.3.3 (2026-10-05):
#   The whole orientation family was measuring nothing. Three independent
#   defects, all found by running the synthetic phantoms in
#   tools/collagen-phantom-creation and comparing against the generator's
#   own inputs.
#
#   - straightness.skeletonize_and_tangents: the per-pixel tangent was taken
#     from the gradient AT the skeleton pixel. The skeleton lies on the ridge
#     crest of the smoothed mask, where the gradient vanishes by construction
#     -- measured magnitude 0.001 on-skeleton against 0.145 one pixel out on
#     the flank -- so arctan2(gx, -gy) collapsed to arctan2(0, 0) == 0 and
#     every fiber read as horizontal. On the known-angle phantoms the mean
#     absolute error was 26.3 deg and 90 deg read as 0. Replaced with a
#     structure tensor integrated over tensor_sigma, using a Scharr gradient
#     (np.gradient biases 30 and 60 deg toward 45 by about 2 deg): mean
#     absolute error 0.07 deg, worst case 0.16 deg. This feeds mean_angle_deg
#     and order_parameter, so both were affected.
#   - straightness.skeletonize_and_tangents: the angle field is now dense over
#     the fiber mask instead of skeleton-only. windows.compute_windows gates a
#     window on min_pixels, defaulting to 10% of the window AREA -- a
#     threshold inherited from ppm_library, where the orientation map is
#     dense. A one-pixel-wide skeleton can never reach it, so the axial stats
#     were silently dropped: 7,891 of the 7,935 scored windows on the
#     MH_Colon run carried a null mean_angle_deg and order_parameter.
#   - straightness.compute_radon_scalar: the angular profile was
#     sino.sum(axis=0). The Radon transform conserves mass, so every column of
#     the sinogram sums to the same total and that profile is constant in
#     theta by construction (measured CV 0.04% raw, 0.6% after chord
#     normalisation). It pinned ai at 0.1018 against an isotropic floor of
#     exactly 0.1, entropy at 7.4918 against a log2(180) ceiling of 7.4919,
#     and fwhm_theta_deg at 180.0 for every image ever analysed, and left
#     theta_star_deg reading the argmax of numerical noise -- errors of 63 to
#     89 deg, with true 30, 45 and 60 all returning about 148. Orientation
#     lives in how CONCENTRATED each projection is, so the profile is now the
#     per-angle variance over rho, guarded to |rho| <= 0.8R because the
#     chord normalisation divides by a quantity that goes to zero at the rim.
#     theta_star_deg is also converted into image-angle convention (0
#     horizontal), matching mean_angle_deg; skimage peaks at 90 - A for a
#     fiber at image angle A. pmr is now the peak-to-mean of the angular
#     profile rather than of the raw sinogram.
#
#   Validated against tools/collagen-phantom-creation: theta_star_deg and
#   per-window mean_angle_deg exact to 0.2 deg on the known-angle series, and
#   ai / entropy / pmr / fwhm_theta_deg / order_parameter each rank the
#   alignment sweep at Spearman |rho| >= 0.93 against the generator's order
#   parameter (1.0000 down to 0.0203). Nine regression tests added; all nine
#   fail against the 0.3.2 code, and the old test_windows_axial_stats was
#   removed because it asserted only that the order parameter was HIGH --
#   which a collapsed angle field satisfies perfectly, every window agreeing
#   on the same wrong value.

# 0.3.4 (2026-10-05):
#   Fiber tracing. The skeleton of overlapping collagen is a network, not a
#   set of separate fibers, and the code was measuring the pieces between
#   crossings.
#
#   - straightness: branch points were found with a raw 8-neighbour count.
#     A rasterised diagonal runs as a staircase, and a staircase corner has
#     three neighbours while being topologically ordinary, so every corner
#     read as a branch. Measured on the waviness phantoms, 65% to 97% of the
#     branch points found that way are corners; wav-00 -- straight horizontal
#     fibers with 69 real junctions -- reported 2,245. New crossing_numbers()
#     (Rutovitz) and junction_mask() do the test properly, and walk_segments
#     no longer cuts a fiber at its own rasterisation. Endpoints keep the
#     neighbour-count test, which is correct for them and which the crossing
#     number is not.
#   - morphometrics.branch_endpoint_counts: same fix. branch_points is a
#     shipped measurement and was inflated by between 2x and 30x.
#   - straightness.trace_fibers: NEW. Follows a fiber through a junction by
#     choosing the continuation whose direction best matches the one
#     arriving, refusing joins sharper than MAX_LINK_TURN_DEG. Even with
#     correct junctions there are ~22 real crossings per fiber on these
#     phantoms, so cutting at every one measured fragments: at wav-55 the
#     median piece was 28 px against a drawn length of 160. Linking roughly
#     doubles the median traced length (wav-12: 38 -> 92 px) and widens the
#     straightness response to waviness by a third (p10 range 0.339 ->
#     0.450). compute_skeleton_tortuosity and morphometrics.mean_curvature
#     both now run on traced fibers.
#   - compute_skeleton_tortuosity: new summary statistics beside the existing
#     mean -- mean_straightness_len (length-weighted; a 3-px stub no longer
#     counts as much as a 300-px fiber), straightness_p10 / _median / _sd,
#     wavy_fraction, and median_fiber_len_px, which is the number that says
#     whether the rest describe fibers or fragments. The order statistics are
#     marked UNCOMBINABLE across tiles, because a mean of medians is not a
#     median.
#
#   mean_tortuosity keeps its name although it computes chord/arc, which is
#   CT-FIRE's STRAIGHTNESS (1.0 straight, falling as a fiber waves) and not
#   tortuosity. The name is already in shipped results.json files and QuPath
#   measurement tables; renaming it is a separate, deliberate break.
#
#   Validated against tools/collagen-phantom-creation: straightness mean,
#   length-weighted mean, p10, wavy_fraction and curvature all rank BOTH the
#   waviness series and the jaggedness series at Spearman |rho| = 1.000. The
#   jaggedness series had never been tested.
