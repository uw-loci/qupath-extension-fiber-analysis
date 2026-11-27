# Changelog

All notable changes to the Fiber Analysis extension. Versions name the
extension jar; the bundled Python package `fiberlib` has its own version,
recorded in every result file.

## Unreleased

### Read before upgrading

- **"Use existing fiber mask" now works** (pixel classifier, object class, mask file). If you
  hit issue #1, upgrade; the user guide, section 4, has a step-by-step "Use a pixel classifier
  as the fiber mask".
- **Results that change:**
  - Density maps differ from 0.3.0 maps made with default settings: windows below the Run's
    "Min coverage per window" (default 5 %) are empty in the fiber channels, ridge count leaves
    out traced fibers shorter than the Run's "Min branch length" (default 5 um; it was a fixed
    2 um), and object density reads 0 where a class has no objects (it read as no data).
    Make maps you compare again with one version.
  - With "Window overlap" above 0, per-window tortuosity and ridge count (heatmaps, detections,
    `windows.json`, density maps) change: a traced fiber now counts in every window that holds
    its center. At the default 0 % overlap nothing changes.
  - Run folder names change for the same settings: the settings hash no longer includes the
    removed GLCM heatmap "Property" choice. Scripts that find a run folder by its hash need
    updating.
  - Apply window classifier segments with the settings stored in the model; in 0.3.0 it used
    the current Run settings, so its maps can differ.
- **Files that stop working:**
  - A density `rerun.groovy` written by 0.3.0 no longer runs; make the map again to get a new
    one.
  - Calibrations made by 0.1.x are refused (they do not record zone mode, border zone width,
    analysis downsample and pixel size); calibrate again.
  - A mask file made for one region is now read as a mask of the whole image. It is refused
    unless its proportions match the image's, and then it gives the wrong mask with no error.
    Remake it as a mask of the whole image.
- **Removed from the Run dialog:** the GLCM heatmap "Property" choice (one heatmap is written
  per enabled property, as before) and the "Value (HSV)" source channel. Saved settings that use
  them still load; "Value (HSV)" counts as "Raw intensity", which computes the same pixels.

### Fixed

- **"Use existing fiber mask" works** ([#1](https://github.com/uw-loci/qupath-extension-fiber-analysis/issues/1)).
  The `Pixel classifier` and `Object class` mask sources were in the dialog
  but had no code behind them since 0.1.0, so every run with them failed
  with "seg_source=existing but no existing_fiber_mask array or valid
  existing_mask_path given". The classifier now runs on each region (and
  each tile of a large annotation) at its own resolution, and the new
  **Fiber class** picker, filled with that classifier's classes, says which
  class is fiber. `Object class` fills the outlines of that class's objects.
  `File on disk` now takes a mask of the whole image, at full resolution or
  one downsample, and cuts each region from it; before, the file had to
  match each region's exact size, which no user could know. A source that
  cannot work (no classifier saved, a class the classifier lacks, an image
  with no objects of the class, one mask file for several images) is
  refused before the run starts. fiberlib 0.4.9.
- **Per-window straightness and fiber counts with window overlap above 0.** Each traced fiber
  was given to one window by its center divided by the stride, so with overlap a window counted
  only the fibers in its top-left stride-sized corner. A fiber now counts in every window whose box
  holds its center. Changes per-window tortuosity, ridge count and their heatmaps, detections and
  density-map channels whenever overlap is above 0; at the default 0 % overlap nothing changes.
- **A mask file with fibers drawn in color reads them.** A pixel is fiber when any color channel
  is non-zero; an alpha channel is ignored. Before, only the first (red) channel was read, so a
  mask drawn in green gave no fibers.
- **An existing-mask run records what made its mask.** `existing_mask.json` in the run folder
  names the source, the classifier, its fiber class and a SHA-256 of the classifier (or the mask
  file's size, date and SHA-256), so a classifier retrained under the same name can be told apart.
  The image entry's "Fiber threshold (annotations)" metadata now says the mask source instead of
  keeping a threshold from an earlier run, and the project-wide "not all comparable" warning
  counts it.
- Error messages from the dialogs are also written to the log.
- **Every control now does what it says.** After #1, an audit traced every control in every
  dialog to the code it should change; 36 more were dead, partly wired or described wrongly.
  The ones that change what you get:

  *Run and batch*
  - A large annotation that is split into tiles (over 64 MP at the analysis downsample) now gets
    its collagen detection objects (before: none) and a fiber-mask overlay in the results panel.
    A fiber blob that crosses a tile seam becomes one object per tile. What a tiled annotation
    still does not produce (per-window heatmap overlays, Radon, a combined summary or `.npz`) is
    listed in the run notes and at the end of the run, instead of going missing silently.
  - `rerun.groovy` reruns exactly the run's annotations (unnamed ones found by their bounds),
    saves the detections it makes, skips preview markers, and stops with a message when an
    annotation is gone. Before, it lost its detections, and with unnamed annotations it ran on
    every annotation in the image.
  - Morphometric heatmaps are written whenever morphometrics and window analysis are on; before,
    turning off "Write fiber-mask overlay PNG" removed them too.
  - "Min coverage per window" now also hides below-gate windows in the heatmaps built from
    counts (fiber coverage, ridge count).
  - Removed the GLCM heatmap "Property" choice: it never limited anything, one heatmap is written
    per enabled property. An old params.json that has it still loads. This changes the settings
    hash in every run folder name.
  - "Value (HSV)" is no longer offered: it computes exactly the same pixels as "Raw intensity".
    Saved settings and calibrations that name it still work and count as Raw intensity. Hue and
    Saturation are offered only for RGB images.
  - The batch dialog says that a saved "Selected annotations" search area means every annotation
    (filtered by class) in a batch.
  - A results card's overlay buttons are disabled while another image is open, instead of
    drawing that result on the wrong image.
  - "Rebuild" in Setup environment deletes only the environment at the configured location.

  *Project threshold calibration*
  - `Calibrate threshold...` fits the settings of the loaded params.json (zone mode, analysis
    downsample, sigmas, pixel size, shown read-only), not whatever the Run dialog last saved, and
    offers the same source channels as the Run dialog, including named channels.
  - A run refuses a calibration fitted at another zone mode, border zone width, analysis
    downsample or analyzed pixel size, and the preview says so instead of promising the same
    cut. A calibration file from 0.1.x that does not record these is refused; calibrate again.
  - Cancel stops a calibration and writes nothing; replacing a calibration asks first.
  - The calibration's `rerun.groovy` reproduces its zone mode, downsample and pixel-size choice.
  - The preview's region marker is no longer pooled, and unsaved annotations of the open image
    count.

  *Density maps* (values differ from 0.3.0 maps made with default settings)
  - Windows below the Run's "Min coverage per window" (default 5 %) are empty in the fiber
    channels; the two threshold-free channels keep them.
  - Ridge count drops branches shorter than the Run's "Min branch length" (default 5 um; it used
    a fixed 2 um).
  - Object density reads 0 where a class has no objects (it read as no data).
  - A window and overlap that land on a half pixel (for example 15 um at 0.5 um/px with 25 %
    overlap) no longer fail the image with a grid-size mismatch.
  - Refuses to run while the Run settings use an existing fiber mask, instead of thresholding
    with settings you may never have set.
  - Pixel-positivity rules find their channel by name in each image (before: by position, from
    the first image); incomplete rows block Run with a message.
  - The channels option adds entries only for images whose map this run wrote; the output mode
    is saved and honored by the rerun script; "Map file only" never touches a project entry.
    A density `rerun.groovy` written by 0.3.0 no longer runs; make the map again to get a new one.

  *Window classifier (experimental)*
  - Apply no longer refuses every model: a feature column excluded at training made every image
    fail the compatibility check, even with the override.
  - Apply segments with the settings stored in the model (including its pooled threshold),
    window size, overlap and context radii, not with the current Run settings. Before, the
    stored settings were never read back.
  - Train refuses "Use existing fiber mask" and a calibrated threshold that cannot be loaded.
    Both dialogs say which Run settings the classifier does not use (analysis downsample,
    pixel-size override).
  - Permutation shuffles (minimum 20) and bootstrap resamples (minimum 200) can no longer crash
    training or silently skip a refusal check.
  - The window grid sent to Python is the one Java places tiles on (a half-pixel stride put
    features on the wrong grid).
  - Map files record the model, its verdict and any override; detections from an override run
    carry an `Overridden` measurement. "Selected annotation" means its outline, not its box.
    Retraining under an existing name asks first.

  *Wording*: tooltips for min branch length (straightness only; enabled with Tortuosity), the
  morphometric summary file, the output folder (`.npz`, not CSV), the source channel used by
  texture and Radon, the region-size line ("will be tiled"), "Open folder", and the
  density-overlay render style now match the code.
- **The Python worker runs in UTF-8 mode on every host.** It starts with `PYTHONUTF8=1` (unless
  QuPath was started with `PYTHONUTF8` set), so on Windows files it reads or writes without an
  explicit encoding, paths and its error output use UTF-8 like the Linux and macOS workers,
  instead of the ANSI code page. Shared Appose plumbing; the same change is in QP-CAT, the DL
  Pixel Classifier, cellAPpose and PPM.

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
