# qupath-extension-fiber-analysis

Collagen fiber analysis testbed for QuPath -- straightness,
morphometrics, and texture metrics over a dilated border around an
annotation.

> **Status: testbed / experimental.** This extension is a research
> prototype for evaluating fiber-analysis methods against the
> dilated-border search-area pattern. **Research use only. Not for
> clinical decision-making.** It is **not** distributed via a QuPath
> catalog in v1; install the JAR by hand. Interfaces, parameter names,
> and output formats may change between revisions. Use the production
> `qupath-extension-ppm` for shipped PPM analysis; use this repository
> to evaluate metrics that may or may not later graduate.

## Install

1. Download `qupath-extension-fiber-analysis-*-all.jar` from the
   project's local build (no GitHub release in v1).
2. Drop it into `~/QuPath/v0.7/extensions/` (Linux) or the equivalent
   `extensions/` directory (Windows).
3. Restart QuPath. The extension appears under
   `Extensions > Fiber Analysis`.
4. **First run builds the Appose environment**, which can take
   several minutes. Subsequent runs reuse the cached env at
   `~/.local/share/appose/<env-name>/`.
5. Linux and Windows are the supported targets. macOS is not
   verified.

**Migration:** not applicable -- new extension. No prior preferences,
project metadata, or UI surfaces are being replaced.

## Quick start

1. **On first run only:** `Extensions > Fiber Analysis > Setup
   environment...` -- this builds the Python (Appose / Pixi)
   environment and takes several minutes (typically 5-10 min, network
   required). Wait for the "Environment ready" notification before
   running an analysis. Skipping this step makes step 4 below appear
   to hang silently on the first run.
2. Open an image in QuPath (RGB or single-channel).
3. Draw an annotation around the region of interest -- typically a
   tumour-stroma boundary.
4. `Extensions > Fiber Analysis > Run analysis...`
5. In the dialog, set dilation + zone mode (search area); pick
   segmentation source (**Internal** = threshold / ridge filter;
   **Existing fiber mask** = consume a pixel-classifier output,
   thresholded objects, or an annotation class); pick which families
   to run (straightness, morphometrics, texture); click OK.
6. The Python module runs via Appose and writes per-annotation
   overlays + a `windows.json` sidecar. Results appear in the **Fiber
   Analysis** panel, and one `CollagenAnalysis` detection per fiber
   blob is added under your annotation. The annotation itself gets no
   measurements -- tick **Create per-window detection objects**
   (Section 3) if you want per-window metrics in the measurement
   table.

![Run dialog showing the Search area, Fiber segmentation, and Window analysis sections, with the Straightness, Morphometrics, Texture, and Output sections collapsed below.](documentation/images/run-dialog.png)

## What the analyses tell you

<details>
<summary><strong>What each analysis family measures</strong></summary>

The three metric families run over a dilated border zone around the
annotation. The example below inspects a region about 120 microns wide
and shows, left to right, the segmented fiber mask, a morphometrics
HDM map, a GLCM entropy map, and a straightness map.

![Four side-by-side maps from one analysed region: segmented mask, morphometrics HDM, GLCM entropy, and straightness map.](documentation/images/output-maps-border-zone.png)

**Straightness / persistence.** Distinguishes wavy from straightened
collagen fibers. In TACS-3 breast pathology (Conklin et al. 2011,
*Am J Pathol* 178:1221), straightened fibers perpendicular to the
tumour-stroma boundary act as contact-guidance tracks for invading
cells (HR 3.0-3.9 for disease-free survival, independent of grade,
size, receptor status). The wavy-to-straight axis recurs in arterial
adventitia, sclera, alveolar wall, and cardiac fibrosis. We report
per-window skeleton tortuosity (chord / arc, CT-FIRE convention --
Bredfeldt et al. 2014, *J Biomed Opt* 19(1):016007) plus per-ROI
Radon scalars (peak-to-mean ratio, FWHM at theta*; Schaub & Gilbert
2011, *SPIE* 7897).

**Morphometrics.** Shape statistics of the segmented fiber graph,
native re-implementation of the cheap metrics from TWOMBLI (Wershof
et al. 2021, *Life Sci Alliance* 4(3):e202000880) -- HDM coverage,
total fiber length, branch / end-point counts, fractal dimension via
box-counting, lacunarity, and max-inscribed-circle gap analysis.
TWOMBLI's FIJI plugins are not bundled; the extension stays
Apache-2.0. See Caveats for implementation-difference notes.

**Texture.** Spatial heterogeneity of fiber density and packing --
the information fiber-tracing misses (a dense uniform mat and a
sparse clumped field can share mean alignment but differ in
texture). Per-window GLCM / Haralick features (contrast,
correlation, energy, homogeneity, entropy, dissimilarity) via
scikit-image. Framing follows the 3-D texture review (Depeursinge et
al. 2014, *Med Image Anal* 18(1):176-196) and the radiomics feature
set (Aerts et al. 2014, *Nat Commun* 5:4006). True-3D / volumetric
texture is out of scope -- our data are 2-D; only the feature math
transfers.

</details>

## Parameters

<details>
<summary><strong>The parameter table</strong></summary>

The main dialog's controls, in dialog order. The user guide
([`documentation/fiber-analysis.md`](documentation/fiber-analysis.md))
carries the full tooltip and rationale per control; this table is the
at-a-glance reference.

| Label | Section | Default | Units | What it does |
|---|---|---|---|---|
| Search area | 1. Search area | Selected annotations | -- | Which annotations to analyse: the viewer selection, every annotation on the image, or the classes checked below. |
| Annotation class(es) | 1. Search area | (none checked) | -- | Classes to analyse when Search area is "Annotations of class...". Lists the full class name including derived-class colons ("Tumor: Stroma"); "Unclassified" matches annotations with no class assigned. |
| Border zone width | 1. Search area | 50.0 | um | Distance from the annotation boundary that bounds the analysed zone. |
| Zone mode | 1. Search area | outside | -- | What to measure: `whole annotation` (the entire interior, ignoring the border width) or a band at the boundary (`inside` / `outside` / `both`). |
| Use image pixel size | 1. Search area | checked | -- | Read pixel size from the QuPath image; uncheck to override. |
| Override pixel size | 1. Search area | 0.5 | um/px | Forced pixel size when the image has no calibration (enabled only when "Use image pixel size" is off). |
| Analysis downsample | 1. Search area | 1.0 | -- | Read the region at 1/N resolution. Pixels read fall by N squared, which is what makes an annotation larger than Java's raster cap analyzable. Micron measurements stay correct (the effective pixel size is scaled with it); fibers thinner than the downsampled pixel are lost. |
| Source (segmentation) | 2. Fiber segmentation | Segment within extension | -- | Internal segmenter vs. consume an existing fiber mask. |
| Source channel | 2. Fiber segmentation | Raw intensity | -- | Scalar channel the internal segmenter operates on: Raw intensity / Hue / Saturation / Value derived from RGB, or one of the image's own channels by name. Naming a channel sends that single band, which is what lets a 16-bit source keep its full precision. |
| Threshold method | 2. Fiber segmentation | Otsu | -- | Threshold algorithm applied to the source channel (Otsu / Triangle / Manual / Project Otsu (calibrated)). |
| Manual threshold | 2. Fiber segmentation | 128 | gray level | Cutoff in the source image's own gray levels -- on a single-channel 16-bit image the spinner runs 0-65535, so 4500 cuts at 4500. Enabled only when threshold method is Manual. With a ridge filter selected it is replaced by **Response cutoff (0-1)**, a cut on the filter's response, where 1 is a fiber at full contrast (default 0.1). |
| Ridge filter | 2. Fiber segmentation | None | -- | Optional vesselness filter to enhance line-like structures (None / Frangi / Sato / Meijering). |
| Sigma min | 2. Fiber segmentation | 1.0 | um | Smallest fiber half-width the ridge filter looks for. |
| Sigma max | 2. Fiber segmentation | 4.0 | um | Largest fiber half-width the ridge filter looks for. |
| Sigma step | 2. Fiber segmentation | 1.0 | um | Step size for the sigma sweep (smaller = more sensitive, slower). |
| Min fiber area | 2. Fiber segmentation | 1.0 | um2 | Connected components smaller than this are removed. |
| Mask source | 2. Fiber segmentation | Pixel classifier | -- | Where to read the existing fiber mask from (classifier / object class / file). |
| Classifier name | 2. Fiber segmentation | first available | -- | Pixel classifier whose fiber channel will be used. |
| Object class | 2. Fiber segmentation | first available | -- | PathClass whose objects will be rasterised to a binary mask. |
| Mask file | 2. Fiber segmentation | (empty) | -- | Path to a .tif or .npy file containing a binary fiber mask. |
| Enable moving-window analysis | 3. Window analysis | checked | -- | Divide the analysed zone into a grid of windows and report per-window metrics. |
| Window size | 3. Window analysis | 15.0 | um | Side length of each square window. |
| Window overlap | 3. Window analysis | 0 | percent | Overlap between adjacent windows. |
| Create per-window detection objects | 3. Window analysis | unchecked | -- | Add one PathDetectionObject per non-empty window (off by default -- can produce thousands). |
| Enable straightness analysis | 4. Straightness | checked | -- | Master toggle for tortuosity and Radon. |
| Per-window skeleton tortuosity | 4. Straightness | checked | -- | Chord/arc ratio along skeleton paths, CT-FIRE convention. |
| Per-ROI Radon scalar | 4. Straightness | checked | -- | Peak-to-mean ratio and FWHM at theta* via Radon transform. |
| Min branch length | 4. Straightness | 5.0 | um | Skeleton branches shorter than this are dropped as noise. |
| Enable morphometrics | 5. Morphometrics | checked | -- | Master toggle for the TWOMBLI-derived metric panel. |
| Branch-point count | 5. Morphometrics | checked | -- | Count of skeleton junctions (>=3-neighbour pixels). |
| Endpoint count | 5. Morphometrics | checked | -- | Count of skeleton end-points (1-neighbour pixels). |
| Total fiber length | 5. Morphometrics | checked | -- | Sum of skeleton segment lengths in um. |
| Mean curvature | 5. Morphometrics | checked | -- | Mean absolute curvature along skeleton segments, in 1/um. |
| HDM coverage | 5. Morphometrics | checked | -- | High-Density-Matrix coverage: fiber area / zone area. |
| Lacunarity | 5. Morphometrics | checked | -- | Gappiness across multiple box sizes. |
| Fractal dimension | 5. Morphometrics | checked | -- | Box-counting fractal dimension; collagen networks typically 1.4-1.8. |
| Gap analysis | 5. Morphometrics | checked | -- | Widths of the gaps between fibers: the diameter of the largest empty circle at each gap centre inside the analysis zone. |
| Lacunarity box sizes | 5. Morphometrics | 0.5,1,2,4,8 | um | Box sizes for the gliding-box lacunarity computation. |
| Fractal box sizes | 5. Morphometrics | 0.25,0.5,1,2,4,8,16 | um | Box sizes for box-counting fractal dimension. |
| Enable GLCM texture | 6. Texture | checked | -- | Master toggle for per-window GLCM / Haralick features. |
| Quantization levels | 6. Texture | 16 | gray levels | Number of gray levels the source channel is quantised to before GLCM. |
| GLCM distance(s) | 6. Texture | 0.1,0.2,0.3 | um | Offsets at which co-occurrence is computed. Converted to whole pixels per image. |
| Contrast | 6. Texture | checked | -- | GLCM contrast (variance of intensity differences). |
| Correlation | 6. Texture | checked | -- | GLCM correlation (linear dependence of neighbouring intensities). |
| Energy | 6. Texture | checked | -- | GLCM energy / angular second moment (uniformity). |
| Homogeneity | 6. Texture | checked | -- | GLCM homogeneity / inverse difference moment (closeness to diagonal). |
| Entropy | 6. Texture | checked | -- | GLCM entropy (randomness of intensity distribution). |
| Dissimilarity | 6. Texture | checked | -- | GLCM dissimilarity (linear contrast variant). |
| Output directory | 7. Output | `<project>/fiber-analysis/` or `~/QuPath/fiber-out/` | -- | Folder where overlay PNGs, results.json, and per-window data are written. |
| Write fiber-mask overlay PNG | 7. Output | checked | -- | Save the segmented fiber mask as a PNG re-displayable from the results panel. |
| Write straightness heatmap PNG | 7. Output | checked | -- | Save a viridis heatmap of per-window tortuosity / Radon scalar values. |
| Write GLCM heatmap PNG | 7. Output | checked | -- | Save a per-window heatmap PNG of the selected GLCM property. |
| GLCM heatmap property | 7. Output | contrast | -- | Which GLCM property to render as a heatmap (only one PNG per run). |
| Write morphometric summary text | 7. Output | checked | -- | Render a text summary card of the morphometric metrics. |
| Write results.json sidecar | 7. Output | checked | -- | Write the full per-annotation result dictionary as results.json. |
| Add per-window detection objects to hierarchy | 7. Output | mirrors Section 3 | -- | Read-only mirror of the Section 3 toggle so the output side effect is visible. |

</details>

## Outputs

<details>
<summary><strong>Files written by a run</strong></summary>

Each analysed annotation gets its own subfolder under the output
directory. Filenames are ASCII; conventions parallel PPM's
per-annotation outputs.

Per-annotation files:

- `windows.json` -- per-window records (centre, size, fiber-pixel
  count, one column per enabled metric).
- `fiber_mask_overlay.png` -- segmented fiber mask as a translucent
  RGBA overlay.
- `straightness_overlay.png` -- viridis heatmap of per-window
  tortuosity / Radon scalar.
- `texture_<prop>_overlay.png` -- magma heatmap of the selected GLCM
  property (one per run).
- `morphometrics_overlay.png` -- per-window morphometric heatmap.
- `window_metrics.npz` -- per-window arrays (optional).
- `results.json` -- the full result dictionary (optional sidecar).

`windows.json` schema (one record per non-empty window):

```
{
  "x": int,                     # top-left x in image pixels
  "y": int,                     # top-left y in image pixels
  "w": int,                     # window width in pixels
  "h": int,                     # window height in pixels
  "n_fiber_px": int,            # fiber-pixel count inside the window
  "mean_angle_deg": float,      # axial mean fiber orientation, 0-180 (0 = horizontal)
  "order_parameter": float,     # axial OS in [0, 1]; 1 = perfectly aligned
  "tortuosity_median": float,   # per-window median chord/arc (1.0 = straight)
  "n_fibers": int,              # path count contributing to tortuosity
  "glcm_<prop>": float,         # one field per enabled GLCM property
  "hdm": float,                 # fiber in zone / zone pixels in this window
  ...
}
```

Optional **per-window PathObjects** are added only when "Create
per-window detection objects" is on in Section 3. A 50 x 50 grid =
2500 objects; the toggle is off by default.

</details>

## Reproducing a run from disk

<details>
<summary><strong>Re-running from params.json</strong></summary>

Every file-producing analysis drops three companion artifacts next to its
outputs:

- `params.json` -- pretty-printed JSON with a top-level `meta` block
  (`versions`, `run: {timestamp_utc, run_id, ...}`) and the full
  insertion-ordered `parameters` block. This is the machine-readable record
  of what was actually run.
- `params.txt` -- the same key/value list as a `key = value`-per-line text
  file. Grep-friendly; quick to scan.
- `rerun.groovy` -- a self-contained Groovy script that re-executes the
  analysis headlessly via QuPath's script mode. All paths are baked in as
  string literals; edit the script if the project moves.

To re-run from disk (no GUI clicks):

```
~/path/to/QuPath script <output-dir>/rerun.groovy
```

Per analysis, the files land as follows:

| Analysis | Output dir | Files |
|---|---|---|
| Per-annotation fiber analysis | `<base>/YYYYMMDD_HHMMSS_<hash>/` | `params.json`, `params.txt`, `rerun.groovy`, and with Otsu or Triangle `threshold.json` (the one cut every annotation in the run used) |
| Project density map | `<project>/fiber-analysis/density-maps/` | `<image>_density_params.json`, `<image>_density_params.txt`, `<image>_density_rerun.groovy` (one set per image) |
| Project calibration | `<project>/fiber-analysis/` | `calibration_<name>_params.txt`, `calibration_<name>_rerun.groovy` (the existing `calibration_<name>.json` is itself the params record the rerun reads) |

The rerun scripts use the project + image names recorded in the params at
write time. If you move the project to a different machine or path, open
the `.groovy` file and adjust the `projectPath` (and `imageName(s)`) at the
top of the file.

</details>

## Project density map (WSI scale)

<details>
<summary><strong>The whole-slide density-map workflow</strong></summary>

A separate workflow for **whole-slide density-map output**: tile-streams
each selected image, runs the same per-window analysis the regular
"Run analysis..." dialog does, and writes a small map file per image
(one float32 value per channel per window step) at
`<project>/fiber-analysis/density-maps/<image>_density.ome.tif`.
Show it as an overlay, sample it into object measurements, or add it as
channels in a new project entry; the original image is never changed.

Menu: `Extensions > Fiber Analysis > Project density map...`

### Output (you pick at dialog time)

- **Map file only** (default). Use `Show density map as overlay...`
  and `Sample fiber density into measurements`.
- **Also add each image with its density channels as a new entry**.
  After the run, each image gets a new project entry
  `<image> [fiber density]`: the image converted to 32-bit float,
  followed by the map's channels at the image's size, for QuPath's
  `Add intensity features`, thresholders or classifiers. RGB images
  are shown red / green / blue over 0-255 so they look like the
  original. The entry is tagged `Fiber analysis`, `Fiber source image`
  and `Fiber map` in the project metadata for sorting. It reads the map
  file when opened, so keep the file and the extension installed.

### Validation rules in the dialog

The dialog opens a split-pane picker. Left = project images with
pixel type / dimensions / calibration status (populated in a
background thread). Right = settings. A live validation banner
gates the Run button:

- Any selected image **without pixel calibration** -- **blocked**
  in either mode (the um window size can't be translated).
- Otherwise ready; the banner says whether each image will also get a
  channels entry.

### Map file format

- One float32 value per channel per window step (not per image
  pixel), planar, 256x256 tiles, LZW; halving pyramid levels (each the
  NaN-aware mean of the level below) only when the grid is larger than
  256 px.
- Values are physical; NaN means no data. Channel names match the
  QuPath measurement-table column names.
- The image description places the grid on the source image with a
  `Placement:` line (`source_width`, `source_height`,
  `source_pixel_um`, `cell_px`, `window_px`, `x0`, `y0`, `grid`,
  `render`) and names the source image. Grid pixel (i, j) covers source
  pixels `x0 + i*cell_px` to `x0 + (i+1)*cell_px`; `render` is
  `bilinear` (interpolate between cell centres) or `nearest`.
- A channel row ending `; axial=180` (`Mean angle (deg)`) is an
  axial angle: interpolated, pyramid-averaged and sampled as an angle.
  The overlay shows it with the cyclic map `Angle (cyclic)` over
  0-180 (copied from TME Quant, same names).
- The OME physical pixel size is `cell_px * source_pixel_um`.
- Files written before 2026-10-19 were full image resolution (9 GB
  for a 0.3-gigapixel slide with Smooth on) and are refused; re-run.

### Menu items at a glance

| Item | What it does |
|---|---|
| `Project density map...` | Open the picker, pick images, Run. Writes one map file per image; with the channels option, also adds each image with its density channels as a new project entry and opens the current image's. |
| `Sample fiber density into measurements` | For the open image, reads its map and writes the mean of each channel inside each target object (read every quarter cell; a smaller object takes its centre's value) as `Density: <channel>`. Targets selected objects when any are selected, otherwise all annotations. |
| `Show density map as overlay...` | Draw one channel of a density or classifier map over the open image in colour, on QuPath's pixel-classification layer: the image's own maps, or a map from another project image of the same size (another image of the same slide). Exact at any zoom; the location bar shows the value under the cursor. The image is not changed. |
| `Add density channels as a new image` | Add a new project entry: the open image (as float32) followed by its density map's channels, tagged `Fiber analysis` / `Fiber source image` / `Fiber map` in the project metadata. The original entry is not changed; adding again refreshes the same entry. |

### Limitations carried in v1

- **No tile-margin overlap**. Skeleton-derived metrics (branch
  points, total length) may have minor seam artefacts at tile
  boundaries. Coverage / HDM / ridge_count are unaffected.
- **Sequential per-tile Appose calls** -- a 100k slide is roughly
  10-15 min. NDArray IPC + tile batching is a known speed
  follow-up.
- **Mean-angle channel is arithmetically averaged in the sampling
  command** -- near the 0/180 axial boundary this gives wrong
  values. Axial-circular averaging is a follow-up.
- **Channel set is fixed** (the 7 above). Per-channel toggles in
  the dialog are a follow-up; for now every run writes all 7.

</details>

## Troubleshooting

<details>
<summary><strong>Common problems and what to do about them</strong></summary>

### "Show fiber mask" -- what the magenta overlay should look like

The fiber-mask overlay paints **magenta (255, 0, 255) at alpha ~200** wherever
the segmenter judged a pixel to be fiber. It covers the dilated analyzed
region (the annotation's bounding box padded out by the `Border zone width`
preference), **not** just the annotation interior. If you see no overlay at
all, see the table below; if you see magenta in a "wrong" place, that's
almost always one of three things:

| Symptom | Likely cause | Fix |
|---|---|---|
| **Entire bounding box painted magenta** | Threshold is too low (Otsu picked a value that's saturating everything), or you have DAB / dark-fiber staining and didn't tick `Invert intensity`. The mask reads "everything is fiber" because the wrong polarity made background look brighter than fiber. | Open Section 2; for DAB / trichrome / any chromogenic where fibers are darker than background, tick `Invert intensity`. Otherwise try `Manual` threshold and step up. |
| **No magenta visible at all** | Three possibilities: (a) the segmenter found zero fiber pixels (threshold too high, or wrong source channel), (b) you switched images since the overlay was painted -- the old overlay is auto-cleared when the image changes, (c) QuPath's master overlay opacity slider is at 0. | Check (c) first (View > Show overlay options, slider at top). For (a), open the result panel's `Summary` button -- if `fiber_in_zone_pixels = 0`, segmentation failed; change source channel or lower threshold. |
| **Magenta visible OUTSIDE the annotation polygon** | Working as designed. The analysis runs on the dilated bbox so it can see the border zone, and the mask covers everything in that bbox. Only fiber pixels **inside the zone** are used for metrics. | None -- the metrics are correct; only the visual extent is wider than the annotation. If you want a zone-clipped mask, future work; for v0.2 it's the bbox. |
| **Magenta is shifted / offset from the actual fibers** | Coordinate-transform bug, should be filed -- the workflow records `region_offset_x/y` in `results.json` next to the overlay. | Save the results folder + a screenshot and open an issue. |
| **Magenta on a totally different image** | The image was switched while the overlay was active and the listener didn't catch it (rare). | Click `Hide overlay` on the results-panel header, or close the result window (auto-clears). |

### "Show morphometrics" or "Show GLCM" doesn't list everything I expected

These are **per-window** heatmaps only. Annotation-level scalars (branch
points, endpoints, total length, mean curvature, fractal dimension,
lacunarity per box, gap stats) are reported in the card's text summary and
in `results.json`, but they have no per-window equivalent so they don't
show up in the Morph combo. The Morph combo currently lists
`fiber_coverage_percent`, `hdm`, and `ridge_count` -- the three
morphometric quantities that genuinely have a value per window.

### Project-calibrated Otsu picked an absurd threshold

If the calibration's `threshold_normalised` is near 0 or near 1, the pool
probably wasn't representative of the workflow's real targets. Common
causes:

- **Mixed staining types in the calibration pool.** Use the calibration
  dialog's `Image name filter` and `Class filter` to restrict the pool to
  one staining type / one tissue class.
- **DAB or trichrome was in the pool without `Invert intensity` ticked.**
  Calibrate again with invert on -- the calibration pool will then mean
  the same thing as the run-time threshold path.
- **Sample size too small.** Default is 50; for projects with high
  variability across sections, raise to 100+ or tick `Use all available
  annotations`.

The full calibration provenance (filter, seed, regions used, histogram
counts) is in `<project>/fiber-analysis/calibration_<name>.json` for
post-hoc inspection.

### Settings aren't sticking across QuPath restarts

`Run` auto-persists the dialog state (as of v0.2). If a parameter still
reverts after restart, two things to check:
- Did `Save defaults` get clicked while the dialog was open? Save defaults
  also writes -- they don't compete, but a partial earlier save can mask
  the issue.
- Are you running the same JAR as the one in
  `~/QuPath/v0.7/extensions/`? Outdated extensions land there
  occasionally; verify the JAR's mod time matches the latest build.

### `ModuleNotFoundError: No module named 'appose'` on Setup environment

The bundled `pixi.toml` is missing the `appose` conda dep. Fixed in v0.2
(2026-05-26). Re-run `Extensions > Fiber Analysis > Setup environment...`
-- the env will auto-rebuild because `ApposeFiberService.syncManifest()`
compares the on-disk `pixi.toml` AND `pixi.lock` against the JAR-bundled
copies and wipes `.pixi/` when either differs.

### "thread death" appears in the Python console

Known Appose race -- a stale worker emits `FAILURE("thread death")` on its
next task *before any Python runs*, and Appose then relaunches that task
inside the same worker as an untracked zombie.

`ApposeFiberService.runTask` recovers from this automatically: it restarts
the Python worker (which kills the zombie, so two copies of the analysis
can never race on one output directory) and retries the task once on the
fresh worker. It only does so when no `LAUNCH`/`UPDATE` event arrived for
the task -- a "thread death" *after* Python started is a real mid-run
death and is reported, not retried, because re-running would repeat
whatever the script had already written. The recovered case logs at WARN;
everything else logs at ERROR. If you see it land repeatedly on the same
task, file an issue with the console log.

### Density-map: the sidecar TIFF opens but every pixel is "0"

Two common causes. First: the image had no fibers in any tile under
your current segmentation settings -- preview a few tiles via the
regular "Run analysis..." dialog before launching the whole-slide
compute. Second: the source image was uncalibrated and the density
fell back to the wrong scale -- the project-density dialog blocks
uncalibrated images, but if you manually constructed the spec, set
the pixel size in QuPath first (`Image > Set image type / properties`).

### Density-map: measurements from attached channels look wrong

Before 2026-10-19, `Attach density channels` stored the map in the
image's own pixel type, so on an 8-bit or 16-bit image every map value
was cut to a whole number (a signal density of 0.12 read 0). Re-run the
density map, then use `Add density channels as a new image` (32-bit
float) or `Sample fiber density into measurements`.

### Density-map: "This map file has no placement line"

The map was written before maps were stored at grid resolution.
Re-run `Project density map...` (or the window classifier) for that
image.

### Density-map: "Sample fiber density into measurements" doesn't add any columns

Three checks:
1. Did the density compute finish for that image? Look for
   `<project>/fiber-analysis/density-maps/<image>_density.ome.tif`.
2. Are there target objects? The command samples selected objects
   first; if nothing is selected, it falls back to every annotation.
   If neither set exists, it bails out with an error.
3. Did the object's bbox overlap any non-no-data sidecar pixels?
   Empty regions of the slide are all-zero in the sidecar; sampling
   over a region with no fiber returns NaN for every channel.

</details>

## Caveats

- 15 um is on the small end for Radon (paper precedents use
  40-256 px tiles); per-window straightness at 15 um is workable when
  fibers span the window, and degrades below that.
- Native morphometrics will not match TWOMBLI numerically to 3
  decimals -- the FIJI plugins are not bundled and the macro is
  unlicensed, so the implementation is independent. Expect parameter
  sensitivity for fractal dimension and lacunarity in particular.
- GLCM stability requires gray-level quantisation (16-32 levels) and
  small offset `d` (~window/8). Defaults are set accordingly; widen
  `d` only if you know your window is larger than a few fiber widths.
- Steger ridge detection is **out of v1 scope** -- fiber-width
  distributions are therefore not provided. Ridge filters
  (Frangi/Sato/Meijering) supply a mask, not a width-annotated
  centreline.
- Segmentation quality bounds metric quality. A thresholded mask with
  broken fibers gives an inflated branch-point count and a depressed
  straightness; check the segmentation overlay before interpreting
  metrics.
- Linux + Windows are tested; macOS is not verified. Multichannel
  (HSV-cube) Haralick, true-3D / volumetric texture, and a TACS-3
  classifier are also out of v1.

## Cite-as / License

Apache-2.0 (see [`LICENSE`](LICENSE)). If you use this in published
work, cite Wershof 2021 (TWOMBLI method), Bredfeldt 2014 (CT-FIRE
straightness convention), and Aerts 2014 (radiomics texture features)
as appropriate. See the references section in the user guide
([`documentation/fiber-analysis.md`](documentation/fiber-analysis.md#references))
for the full list.

### Why no FIJI plugins?

We deliberately do **not** bundle TWOMBLI's FIJI plugin dependencies
(Ridge Detection, AnaMorf, OrientationJ, BIOP Max Inscribed Circles).
All four are GPL; bundling would relicense this extension from
Apache-2.0 to GPL-3.0. The TWOMBLI macro itself ships without a
LICENSE file and is therefore not redistributable. We cite Wershof
et al. 2021 for the method and re-implement the cheap morphometric
metrics natively in Python (scikit-image + numpy).

Practical consequence for users comparing numbers: native fractal
dimension and lacunarity will diverge from TWOMBLI by roughly
**20-40%**, not the "few percent" you might expect from "same
algorithm, different implementation." TWOMBLI's upstream is
**Steger ridge-extracted centerlines** (width-annotated curvilinear
structures), whereas this extension's upstream is a **thresholded /
vesselness-filtered binary mask** skeletonised with
`skimage.morphology.skeletonize`. Different skeletons feed different
box-counts and lacunarity boxes. We explicitly do **not** provide
Steger ridge detection in v1; see user guide section 7 for the
deferred-to-v2 note.
