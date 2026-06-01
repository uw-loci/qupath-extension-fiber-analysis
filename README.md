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
   overlays + a `windows.json` sidecar; the panel attaches the
   measurements to the annotation.

![Run dialog showing the Search area, Fiber segmentation, and Window analysis sections, with the Straightness, Morphometrics, Texture, and Output sections collapsed below.](documentation/images/run-dialog.png)

## What the analyses tell you

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

## Parameters

Every dialog control is listed below in dialog order. The user guide
([`documentation/fiber-analysis.md`](documentation/fiber-analysis.md))
carries the full tooltip and rationale per control; this table is the
at-a-glance reference.

| Label | Section | Default | Units | What it does |
|---|---|---|---|---|
| Border zone width | 1. Search area | 50.0 | um | Distance from the annotation boundary that bounds the analysed zone. |
| Zone mode | 1. Search area | outside | -- | Which side of the boundary to analyse (inside / outside / both). |
| Use image pixel size | 1. Search area | checked | -- | Read pixel size from the QuPath image; uncheck to override. |
| Override pixel size | 1. Search area | 0.5 | um/px | Forced pixel size when the image has no calibration (enabled only when "Use image pixel size" is off). |
| Source (segmentation) | 2. Fiber segmentation | Segment within extension | -- | Internal segmenter vs. consume an existing fiber mask. |
| Source channel | 2. Fiber segmentation | Value (HSV) | -- | Scalar channel the internal segmenter operates on. |
| Threshold method | 2. Fiber segmentation | Otsu | -- | Threshold algorithm applied to the source channel (Otsu / Triangle / Manual). |
| Manual threshold | 2. Fiber segmentation | 128 | gray level | Manual cutoff on the 0-255 source channel (enabled only when threshold method is Manual). |
| Ridge filter | 2. Fiber segmentation | None | -- | Optional vesselness filter to enhance line-like structures (None / Frangi / Sato / Meijering). |
| Sigma min | 2. Fiber segmentation | 1.0 | pixels | Smallest fiber width the ridge filter looks for. |
| Sigma max | 2. Fiber segmentation | 4.0 | pixels | Largest fiber width the ridge filter looks for. |
| Sigma step | 2. Fiber segmentation | 1.0 | pixels | Step size for the sigma sweep (smaller = more sensitive, slower). |
| Min fiber area | 2. Fiber segmentation | 50 | pixels | Connected components smaller than this are removed. |
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
| Gap analysis | 5. Morphometrics | checked | -- | Distribution of inscribed-circle diameters in fiber-free regions. |
| Lacunarity box sizes | 5. Morphometrics | 4,8,16,32,64 | pixels | Box sizes for the gliding-box lacunarity computation. |
| Fractal box sizes | 5. Morphometrics | 2,4,8,16,32,64,128 | pixels | Box sizes for box-counting fractal dimension. |
| Enable GLCM texture | 6. Texture | checked | -- | Master toggle for per-window GLCM / Haralick features. |
| Quantization levels | 6. Texture | 16 | gray levels | Number of gray levels the source channel is quantised to before GLCM. |
| GLCM distance(s) | 6. Texture | 1,2,3 | pixels | Pixel offsets at which co-occurrence is computed. |
| Contrast | 6. Texture | checked | -- | GLCM contrast (variance of intensity differences). |
| Correlation | 6. Texture | checked | -- | GLCM correlation (linear dependence of neighbouring intensities). |
| Energy | 6. Texture | checked | -- | GLCM energy / angular second moment (uniformity). |
| Homogeneity | 6. Texture | checked | -- | GLCM homogeneity / inverse difference moment (closeness to diagonal). |
| Entropy | 6. Texture | checked | -- | GLCM entropy (randomness of intensity distribution). |
| Dissimilarity | 6. Texture | checked | -- | GLCM dissimilarity (linear contrast variant). |
| Output directory | 7. Output | project entry's `data/<name>/fiber/` or `~/QuPath/fiber-out/` | -- | Folder where overlay PNGs, results.json, and per-window data are written. |
| Write fiber-mask overlay PNG | 7. Output | checked | -- | Save the segmented fiber mask as a PNG re-displayable from the results panel. |
| Write straightness heatmap PNG | 7. Output | checked | -- | Save a viridis heatmap of per-window tortuosity / Radon scalar values. |
| Write GLCM heatmap PNG | 7. Output | checked | -- | Save a per-window heatmap PNG of the selected GLCM property. |
| GLCM heatmap property | 7. Output | contrast | -- | Which GLCM property to render as a heatmap (only one PNG per run). |
| Write morphometric summary text | 7. Output | checked | -- | Render a text summary card of the morphometric metrics. |
| Write results.json sidecar | 7. Output | checked | -- | Write the full per-annotation result dictionary as results.json. |
| Add per-window detection objects to hierarchy | 7. Output | mirrors Section 3 | -- | Read-only mirror of the Section 3 toggle so the output side effect is visible. |

## Outputs

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
  "mean_angle_deg": float,      # circular mean of axial skeleton tangent (0-180)
  "order_parameter": float,     # axial OS in [0, 1]
  "tortuosity_median": float,   # per-window median chord/arc (when enabled)
  "n_fibers": int,              # path count contributing to tortuosity
  "glcm_<prop>": float,         # one field per enabled GLCM property
  "hdm": float,                 # local HDM (when enabled)
  ...
}
```

Optional **per-window PathObjects** are added only when "Create
per-window detection objects" is on in Section 3. A 50 x 50 grid =
2500 objects; the toggle is off by default.

## Troubleshooting

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
-- the env will auto-rebuild because `syncPixiToml()` content-hashes the
toml.

### "thread death" appears in the Python console

Known Appose race -- a prior task's worker-thread cleanup event is
misattributed to the next task's UUID. `ApposeFiberService.runTask` now
retries on this (max 2 attempts). If you see it land repeatedly on the
same task, file an issue with the console log.

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
