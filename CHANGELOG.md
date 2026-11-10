# Changelog

All notable changes to the Fiber Analysis extension. Versions name the
extension jar; the bundled Python package `fiberlib` has its own version,
recorded in every result file.

## Unreleased

### Fixed

- **Works when QuPath's language or number format is Turkish, German, French or another
  non-English locale.** With QuPath's language set to Turkish, the image-name filter in the batch
  dialog and in calibration no longer misses names with an upper-case `I`, and the threshold and
  ridge-filter names sent to Python are lowered with `Locale.ROOT`. Temporary tile file names
  (`tile_0001_0002.png`, `wf_...`, `pool_...`) never take local digits.

## 0.3.0 (2026-11-09)

First release since 0.1.1. 0.2.0 was never released. fiberlib 0.4.8.

### Read before upgrading

- **Results are not comparable with 0.1.1.** Thresholds, tracing, gap widths,
  GLCM and the window grid all changed (below). Re-run a project end to end
  rather than mixing old and new results.
- **Manual threshold is an absolute gray level** in the source channel's own
  units (for example 0-65535 on a 16-bit image), not a percentile of each
  region.
- **Density maps are float32 OME-TIFFs at grid resolution** (one value per
  window step), with NaN for no data and a `Placement:` line that says where
  the grid sits on the image. Maps written by earlier snapshots must be
  computed again.
- **Unreleased snapshots from 2026-06-08 to 2026-11-08:** a batch started from
  `Run on project images...` with a loaded params.json, and every
  `rerun.groovy`, ran on the loader's built-in defaults instead of the saved
  settings, because params.json nests them under `parameters` and the loader
  read the top level. Fixed in 0.3.0; re-run any batch made that way. Version
  0.1.1 was not affected.

### Analysis

- One Otsu or Triangle threshold per run (or per batch), fitted on samples of
  every annotation's analysis zone, instead of one per annotation. A project
  calibration saves a threshold every run and density map can reuse. Every
  result records the threshold it was cut at, and runs whose thresholds differ
  are flagged.
- Ridge-filter responses (Frangi, Sato, Meijering) are on one fixed 0-1 scale,
  so a response cutoff means the same thing on every image. The preview shows
  the strongest response in view and says when a cutoff finds no fibers.
- Multi-channel 16-bit images reach Python at full bit depth; one named
  channel is sent to the segmenter rather than an 8-bit composite.
- Fibers are traced through crossings instead of being cut at every junction,
  roughly doubling the median traced length on the waviness phantoms.
- Gaps are reported as widths (the diameter of the largest empty circle), not
  radii, and only gap centers inside the analysis zone count; GLCM is computed
  on fiber-pixel pairs; one HDM definition.
- GLCM gray levels can span a gray range set by hand, or fitted once per run
  (1st to 99th percentile, mean +/- 3 SD, or minimum to maximum of fiber
  pixels). A run warns when fibers fill too few levels, and a batch with a
  fitted range shows a red warning that results may not compare between images.
- Large annotations are tiled, so tissue size is no longer a failure mode;
  additive totals are counted inside each tile's owned core, and windows near a
  seam come from the tile that owns them. The window grid is laid from the
  image origin, so windows from different annotations line up.
- Pixel-size check before multi-image runs, with an option to analyze every
  image at one common pixel size.
- Analysis downsample, progress estimate and cancel in the run dialog; a
  "whole annotation" zone mode.
- Detection objects for collagen blobs and for windows (optional).

### Density maps

- `Project density map...` writes a per-image map file with fiber channels,
  threshold-free signal and coherence channels, optional per-class object
  density and pixel-positivity channels, in per-window or per-pixel mode, and
  optionally only inside annotations.
- `Show density map as overlay...`, `Sample fiber density into measurements`
  and `Add density channels as a new image` read those files. Mean angle uses
  cyclic color maps and is interpolated as an angle.
- A tile that cannot be read fails its image instead of leaving a gap, and map
  file names keep a copied entry's ` (1)`.

### Window classifier (experimental)

- `Train window classifier...` and `Apply window classifier...`: a
  measurement-based classifier on per-window features with slide-grouped
  validation, a refusal report when the data cannot support it, and a
  probability map. Not yet tested on a real training set.

### Interface and platform

- Unique dialog titles; labels and buttons no longer cut off at the default
  dialog size; American spelling in the interface.
- Configurable Python environment location; locked environment
  (`pixi.lock`, installed with `--frozen`); Windows file-lock recovery steps;
  the Python worker is isolated from QuPath's own Python and library paths.
- CI builds and tests on Linux and Windows.

### Speed

- fiberlib 0.4.4 and 0.4.8: faster fiber tracing and curvature with
  bit-identical output; empty tiles skipped; one skeleton, distance transform
  and trace shared between families.

## 0.1.1 (2026-06-09)

Rebuilt to Java 21 bytecode: 0.1.0's jar failed to load on QuPath 0.7
(`UnsupportedClassVersionError`). No functional changes.

## 0.1.0 (2026-05-29)

First release: straightness, TWOMBLI-derived morphometrics and GLCM texture on
segmented collagen fibers, per annotation, with a moving-window analysis.
