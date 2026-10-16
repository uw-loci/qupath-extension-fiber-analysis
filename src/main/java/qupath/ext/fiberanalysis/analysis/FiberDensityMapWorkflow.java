/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.analysis;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Orchestrates whole-slide density-map computation for one or more project
 * images. For each selected entry:
 *
 * <ol>
 *   <li>Open the image, read its pixel calibration.</li>
 *   <li>Compute window-grid dimensions (Hw, Ww) and tile dimensions
 *       (each tile is a whole number of windows wide / tall, so windows
 *       never straddle tile seams).</li>
 *   <li>Tile-stream the slide: read each tile as a PNG, dispatch
 *       {@code density_tile.py} via Appose, read the per-tile npz back,
 *       and accumulate into the slide-wide per-channel float grids.</li>
 *   <li>Quantize each channel separately into uint16 (sentinel 0 for
 *       no-data) and write the pyramid OME-TIFF sidecar.</li>
 * </ol>
 *
 * <p>This is the v1 path -- a small fixed channel set, no per-tile margin
 * overlap for fiber continuity, and per-tile project-calibrated Otsu (when
 * configured). The user reviews these outputs before we wire up channel-
 * concat reattach (task 76) or the sampling command (task 77).
 */
public final class FiberDensityMapWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(FiberDensityMapWorkflow.class);

    /**
     * Tile size in windows. Each tile is {@code TILE_WIN * window_px} pixels on
     * a side. 16 keeps each tile under a few thousand pixels for the default
     * 64-pixel-window case, so the PNG round-trip per Appose call stays cheap.
     */
    private static final int TILE_WIN = 16;

    /**
     * Compute density maps for a list of project entries. Runs on a background
     * thread; the call returns immediately with the worker.
     */
    public Thread runForEntries(
            DensityMapJobSpec spec,
            List<ProjectImageEntry<BufferedImage>> entries,
            Project<BufferedImage> project,
            QuPathGUI qupath) {
        return runForEntries(spec, entries, project, qupath, null);
    }

    /**
     * Variant that runs {@code onComplete} on the JavaFX thread after every
     * image has been processed (regardless of per-image success). Used by the
     * dialog to fire the Channels-mode attach for the currently-open image
     * once its sidecar is on disk.
     */
    public Thread runForEntries(
            DensityMapJobSpec spec,
            List<ProjectImageEntry<BufferedImage>> entries,
            Project<BufferedImage> project,
            QuPathGUI qupath,
            Runnable onComplete) {
        if (entries == null || entries.isEmpty()) {
            if (HeadlessFx.isReady()) Dialogs.showWarningNotification("Fiber density map", "No images selected.");
            else logger.warn("Fiber density map: no images selected (headless).");
            return null;
        }

        Window owner = qupath != null ? qupath.getStage() : null;
        ProgressUi progress = new ProgressUi(owner);
        HeadlessFx.runLater(progress::show);

        Thread worker = new Thread(
                () -> {
                    try {
                        runWorker(spec, entries, project, progress);
                    } finally {
                        // User callbacks (onComplete) must execute regardless of
                        // FX availability -- headless callers depend on it
                        // (e.g. a CountDownLatch in a rerun.groovy script).
                        // Defer to FX when up, run inline otherwise.
                        if (onComplete != null) {
                            if (HeadlessFx.isReady()) HeadlessFx.runLater(onComplete);
                            else onComplete.run();
                        }
                    }
                },
                "FiberDensityMap-Worker");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private void runWorker(
            DensityMapJobSpec spec,
            List<ProjectImageEntry<BufferedImage>> entries,
            Project<BufferedImage> project,
            ProgressUi progress) {
        try {
            try {
                ApposeFiberService.getInstance().initialize(progress::setSub);
            } catch (IOException e) {
                logger.error("Could not init Appose service", e);
                HeadlessFx.runLater(() -> {
                    progress.close();
                    Dialogs.showErrorMessage(
                            "Fiber density map", "Failed to set up Python environment: " + e.getMessage());
                });
                return;
            }

            Path projectDir = resolveProjectDir(project);
            Path outRoot = projectDir.resolve("fiber-analysis").resolve("density-maps");
            try {
                Files.createDirectories(outRoot);
            } catch (IOException e) {
                logger.warn("Could not create density-maps output dir: {}", e.getMessage());
            }

            // The one statistic that must see the whole run: a per-tile Otsu
            // cuts every tile differently, so coverage jumps at tile seams and
            // is not comparable between slides.
            DensityMapJobSpec runSpec = spec;
            if (PooledThreshold.needsPooling(spec.thresholdMethod)) {
                progress.setMain("Pooling a run-wide threshold", 0, 0);
                try {
                    Path poolDir = Files.createTempDirectory("fiber-density-pool-");
                    try {
                        PooledThreshold.Result pooled = PooledThreshold.otsu(entries, spec, poolDir, progress::setSub);
                        runSpec = spec.withPooledThreshold(pooled.thresholdNorm(), PooledThreshold.describe(pooled));
                    } finally {
                        deleteTree(poolDir);
                    }
                } catch (IOException e) {
                    logger.error("Could not pool a run-wide threshold", e);
                    final String msg = e.getMessage();
                    HeadlessFx.runLater(() -> Dialogs.showErrorMessage(
                            "Fiber density map",
                            "Could not compute one threshold for the whole run, so no map was made"
                                    + " (thresholding each tile separately would leave seams): " + msg));
                    return;
                }
            } else if ("triangle".equalsIgnoreCase(spec.thresholdMethod)) {
                logger.warn("Triangle threshold is computed per tile; expect seams. Otsu is pooled over the run.");
            }

            int total = entries.size();
            int ok = 0;
            int failed = 0;
            for (int i = 0; i < total; i++) {
                ProjectImageEntry<BufferedImage> entry = entries.get(i);
                final int idx = i;
                progress.setMain("Image " + (i + 1) + " of " + total + ": " + entry.getImageName(), i, total);
                try {
                    Path tiffPath = runForOneImage(runSpec, entry, project, outRoot, progress);
                    if (tiffPath != null) {
                        ok++;
                        logger.info("Density map written: {}", tiffPath);
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    logger.error("Density map failed for {}", entry.getImageName(), e);
                    final String name = entry.getImageName();
                    final String msg = e.getMessage();
                    HeadlessFx.runLater(() -> Dialogs.showErrorNotification(
                            "Fiber density map", "Image \"" + name + "\" failed: " + msg));
                }
            }
            final int okF = ok;
            final int failedF = failed;
            HeadlessFx.runLater(() -> {
                progress.close();
                Dialogs.showInfoNotification(
                        "Fiber density map",
                        String.format(Locale.ROOT, "Done: %d ok, %d failed. Sidecars in %s", okF, failedF, outRoot));
            });
        } finally {
            HeadlessFx.runLater(progress::close);
        }
    }

    /** Compute + write the density-map sidecar for a single image. Returns the output path. */
    private Path runForOneImage(
            DensityMapJobSpec spec,
            ProjectImageEntry<BufferedImage> entry,
            Project<BufferedImage> project,
            Path outRoot,
            ProgressUi progress)
            throws IOException {
        ImageData<BufferedImage> data = entry.readImageData();
        ImageServer<BufferedImage> server = data.getServer();
        PixelCalibration cal = server.getPixelCalibration();
        if (cal == null || !cal.hasPixelSizeMicrons()) {
            throw new IOException("Image has no pixel calibration");
        }
        double pxUm = cal.getAveragedPixelSizeMicrons();
        int srcW = server.getWidth();
        int srcH = server.getHeight();

        int windowPx = Math.max(2, (int) Math.round(spec.windowSizeUm / pxUm));
        double overlapFrac = Math.max(0.0, Math.min(0.95, spec.windowOverlapPercent / 100.0));
        int stridePxWindow = Math.max(1, (int) Math.round(windowPx * (1.0 - overlapFrac)));

        boolean pixelMode = "pixel".equals(spec.densityMode);
        // Count EVERY channel the accumulator will hold, not just the fiber
        // ones, or a run with several selected classes passes the memory
        // check and then runs out of memory anyway.
        int nChannels = DensityChannelSpec.defaultChannels().size()
                + (spec.includeObjectDensity ? spec.objectDensityClasses.size() : 0)
                + (spec.includePixelPositivity ? spec.pixelPositivitySpecs.size() : 0);

        Geometry geo = pixelMode
                ? Geometry.pixel(srcW, srcH, windowPx, pixelStepPx(spec, pxUm, windowPx, srcW, srcH, nChannels))
                : Geometry.window(srcW, srcH, windowPx, stridePxWindow, TILE_WIN);
        if (geo.gridW() == 0 || geo.gridH() == 0) {
            throw new IOException("Image is smaller than one window in at least one dimension (window_px=" + windowPx
                    + "; image=" + srcW + "x" + srcH + ")");
        }
        int gridW = geo.gridW();
        int gridH = geo.gridH();
        int stridePx = geo.cellPx();
        int halo = TileHalo.px(pxUm, spec.rollingBallRadiusUm, spec.ridgeFilter, spec.sigmaMaxUm)
                + (pixelMode ? windowPx / 2 + 1 : 0);
        if (pixelMode) {
            logger.info(
                    "Per-pixel density: {}x{} samples every {} px ({} um), accumulator ~{} MB",
                    gridW,
                    gridH,
                    stridePx,
                    String.format(Locale.ROOT, "%.2f", stridePx * pxUm),
                    DensityMapJobSpec.estimateGridMemoryBytes(gridW, gridH, nChannels) / (1024L * 1024L));
        }

        // Per-channel float accumulators (slide-wide). NaN = no data.
        // Fiber channels come first (filled by the tile loop below), then
        // optional object-density channels (one per selected class, filled
        // after the tile loop completes).
        List<DensityChannelSpec> channels = new ArrayList<>(DensityChannelSpec.defaultChannels());
        int nFiberChannels = channels.size();
        if (spec.includeObjectDensity) {
            for (String cls : spec.objectDensityClasses) {
                channels.add(DensityChannelSpec.forObjectClass(cls));
            }
        }
        int nAfterObject = channels.size();
        if (spec.includePixelPositivity) {
            for (PixelPositivitySpec pps : spec.pixelPositivitySpecs) {
                channels.add(DensityChannelSpec.forPixelPositivity(pps));
            }
        }
        float[][] accum = new float[channels.size()][gridW * gridH];
        for (float[] arr : accum) java.util.Arrays.fill(arr, Float.NaN);

        int totalTiles = geo.nTilesX() * geo.nTilesY();
        int doneTiles = 0;

        Path workDir = Files.createTempDirectory("fiber-density-tile-");
        try {
            for (int ty = 0; ty < geo.nTilesY(); ty++) {
                for (int tx = 0; tx < geo.nTilesX(); tx++) {
                    doneTiles++;
                    // The tile's core: the pixels whose windows (window mode)
                    // or samples (pixel mode) this tile owns.
                    int coreX = tx * geo.tileStep();
                    int coreY = ty * geo.tileStep();
                    int coreW = Math.min(geo.tileStep() + geo.margin(), srcW - coreX);
                    int coreH = Math.min(geo.tileStep() + geo.margin(), srcH - coreY);
                    int tileGridW = geo.cellsIn(coreW);
                    int tileGridH = geo.cellsIn(coreH);
                    if (tileGridW <= 0 || tileGridH <= 0) continue;

                    // What is actually read: the core plus a halo of filter
                    // context, clamped at the slide edge.
                    int readX = Math.max(0, coreX - halo);
                    int readY = Math.max(0, coreY - halo);
                    int readW = Math.min(srcW, coreX + coreW + halo) - readX;
                    int readH = Math.min(srcH, coreY + coreH + halo) - readY;

                    progress.setSub("Tile " + doneTiles + " of " + totalTiles + " (" + coreX + "," + coreY + ")");

                    Path tilePng = workDir.resolve(String.format("tile_%04d_%04d.png", tx, ty));
                    RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, readX, readY, readW, readH);
                    try {
                        SourceChannel.writeRegionPng(server, req, spec.segChannel, tilePng);
                    } catch (IOException ioe) {
                        logger.warn("Tile ({},{}) skipped: {}", coreX, coreY, ioe.getMessage());
                        continue;
                    }

                    Path tileNpz = workDir.resolve(String.format("tile_%04d_%04d.npz", tx, ty));
                    Map<String, Object> in = buildTileInputs(spec, pxUm, tilePng, readW, readH, tileNpz);
                    in.put("crop_x", coreX - readX);
                    in.put("crop_y", coreY - readY);
                    in.put("crop_w", coreW);
                    in.put("crop_h", coreH);
                    if (pixelMode) in.put("pixel_step", stridePx);
                    Task task;
                    try {
                        task = ApposeFiberService.getInstance().runTask("density_tile", in);
                    } catch (Exception apEx) {
                        throw new IOException("Appose density_tile failed: " + apEx.getMessage(), apEx);
                    }
                    Object okObj = task.outputs.get("ok");
                    if (okObj == null || !"1".equals(okObj.toString())) {
                        Object errObj = task.outputs.get("error");
                        throw new IOException(
                                "density_tile did not complete (error=" + (errObj != null ? errObj : "unknown") + ")");
                    }

                    // Every core origin is a whole number of cells from the
                    // slide origin, so the tile's cell (0, 0) is slide cell
                    // (coreX / cellPx, coreY / cellPx) exactly.
                    accumulateTile(
                            accum,
                            channels,
                            tileNpz,
                            coreX / stridePx,
                            coreY / stridePx,
                            gridW,
                            gridH,
                            tileGridW,
                            tileGridH);
                }
            }
        } finally {
            deleteTree(workDir);
        }

        // Object-density channels: compute one full grid per selected class
        // and slot it into the trailing positions of `accum`. Uses the same
        // grid geometry (windowPx, stridePx, gridW, gridH) as the fiber
        // channels so QuPath's channel list shows them side-by-side at the
        // identical neighborhood scale.
        if (spec.includeObjectDensity && !spec.objectDensityClasses.isEmpty()) {
            progress.setSub("Computing object-density channels...");
            Map<String, float[]> objDensities =
                    ObjectDensityComputer.compute(data, spec.objectDensityClasses, geo, srcW, srcH);
            int idx = nFiberChannels;
            for (String cls : spec.objectDensityClasses) {
                float[] grid = objDensities.get(cls);
                if (grid != null) {
                    System.arraycopy(grid, 0, accum[idx], 0, Math.min(grid.length, accum[idx].length));
                }
                idx++;
            }
        }

        // Pixel-positivity channels: for each row, stream the source, threshold
        // into a source-resolution binary mask, box-filter into the output grid.
        // Same window / stride / grid geometry as the fiber + object channels
        // so QuPath's channel list stays consistent.
        if (spec.includePixelPositivity && !spec.pixelPositivitySpecs.isEmpty()) {
            progress.setSub("Computing pixel-positivity channels...");
            List<float[]> posGrids =
                    PixelPositivityComputer.compute(server, spec.pixelPositivitySpecs, geo, srcW, srcH);
            for (int i = 0; i < posGrids.size(); i++) {
                float[] grid = posGrids.get(i);
                int dst = nAfterObject + i;
                if (grid != null && dst < accum.length) {
                    System.arraycopy(grid, 0, accum[dst], 0, Math.min(grid.length, accum[dst].length));
                }
            }
        }

        // Sidecar is written at SOURCE pixel dimensions, each value drawn over
        // the block centred on the window that produced it, so Attach /
        // Sample / standalone-open all work in identity source-pixel coords.
        // Channels are float32, so the values on disk ARE the physical values.
        String safeName = sanitize(entry.getImageName());
        Path outPath = outRoot.resolve(safeName + "_density.ome.tif");
        DensityTiffWriter.write(
                outPath.toString(), srcW, srcH, pxUm, geo.lattice(), channels, accum, spec.smoothInterpolation);
        // Drop / refresh the auto-reattach marker. We intentionally do not
        // delete an existing marker when the user runs without the checkbox
        // checked -- removing the marker is an explicit user action via the
        // "Stop auto-reattaching density channels" menu command.
        if (spec.writeAutoReattachMarker) {
            DensitySidecar.writeAutoReattachMarker(outPath, spec.windowSizeUm);
        }

        // Provenance triple: <image>_density_params.json + _params.txt + _rerun.groovy
        // next to the sidecar so the run can be audited / re-executed from disk.
        try {
            String projectPath = project != null && project.getPath() != null
                    ? project.getPath().toAbsolutePath().toString()
                    : "";
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("PROJECT_PATH", projectPath);
            placeholders.put("PROJECT_PATH_LITERAL", RunProvenance.groovyString(projectPath));
            placeholders.put(
                    "IMAGE_NAMES_LITERAL", RunProvenance.groovyStringList(java.util.List.of(entry.getImageName())));
            RunProvenance.writeAll(
                    outRoot,
                    safeName + "_density",
                    RunProvenance.KIND_DENSITY_MAP,
                    spec.toOrderedMap(),
                    java.util.List.of(
                            "density-map run parameters",
                            "Re-run headlessly via:  QuPath script " + safeName + "_density_rerun.groovy"),
                    entry.getImageName(),
                    null,
                    null,
                    placeholders);
        } catch (IOException provEx) {
            logger.warn("Could not write density-map provenance for {}: {}", entry.getImageName(), provEx.getMessage());
        }
        return outPath;
    }

    private static Map<String, Object> buildTileInputs(
            DensityMapJobSpec spec, double pixelSizeUm, Path tilePng, int tileW, int tileH, Path outNpz) {
        Map<String, Object> in = new HashMap<>();
        in.put("region_image_path", tilePng.toString());
        in.put("region_w", tileW);
        in.put("region_h", tileH);
        in.put("pixel_size_um", pixelSizeUm);
        in.put("window_size_um", spec.windowSizeUm);
        in.put("window_overlap_percent", spec.windowOverlapPercent);
        in.put("seg_channel", spec.segChannel);
        in.put("threshold_method", spec.thresholdMethod);
        in.put("manual_threshold", spec.manualThreshold);
        in.put("ridge_filter", spec.ridgeFilter);
        in.put("sigma_min", spec.sigmaMinUm / pixelSizeUm);
        in.put("sigma_max", spec.sigmaMaxUm / pixelSizeUm);
        in.put("sigma_step", spec.sigmaStepUm / pixelSizeUm);
        in.put("min_fiber_area_px", (int) Math.max(0, Math.round(spec.minFiberAreaUm2 / (pixelSizeUm * pixelSizeUm))));
        in.put("invert_intensity", spec.invertIntensity);
        in.put("rolling_ball_radius", (int) Math.max(0, Math.round(spec.rollingBallRadiusUm / pixelSizeUm)));
        if (spec.projectThresholdNorm != null) {
            in.put("project_threshold_norm", spec.projectThresholdNorm);
        }
        in.put("density_mode", spec.densityMode);
        in.put("output_npz_path", outNpz.toString());
        return in;
    }

    /** Copy one tile's cells into the slide-wide accumulator, starting at slide cell (slideX, slideY). */
    private static void accumulateTile(
            float[][] accum,
            List<DensityChannelSpec> channels,
            Path npzPath,
            int slideX,
            int slideY,
            int slideGridW,
            int slideGridH,
            int tileGridW,
            int tileGridH)
            throws IOException {
        Map<String, NpzReader.Entry> npz = NpzReader.read(npzPath);
        NpzReader.Entry shape = npz.get("grid_shape");
        int npyH = tileGridH;
        int npyW = tileGridW;
        if (shape != null) {
            int[] sh = shape.asInt32();
            if (sh.length >= 2) {
                npyH = sh[0];
                npyW = sh[1];
            }
        }
        if (npyH != tileGridH || npyW != tileGridW) {
            // The tiling guarantees the shape; a mismatch means Java and
            // Python disagree about the lattice, and slotting it anyway would
            // shift every cell of this tile.
            throw new IOException("Tile " + npzPath.getFileName() + " returned a " + npyW + "x" + npyH + " grid where "
                    + tileGridW + "x" + tileGridH + " was expected");
        }

        for (int c = 0; c < channels.size(); c++) {
            String key = channels.get(c).npzKey;
            // Object-density channels are filled by ObjectDensityComputer after
            // the tile loop completes; they are not in the per-tile npz.
            if (key == null) continue;
            NpzReader.Entry e = npz.get(key);
            if (e == null) {
                logger.warn("Tile npz missing key '{}' -- channel will keep NaN", key);
                continue;
            }
            float[] tileArr = e.asFloat32();
            for (int ry = 0; ry < npyH; ry++) {
                int sy = slideY + ry;
                if (sy >= slideGridH) break;
                for (int rx = 0; rx < npyW; rx++) {
                    int sx = slideX + rx;
                    if (sx >= slideGridW) break;
                    accum[c][sy * slideGridW + sx] = tileArr[ry * npyW + rx];
                }
            }
        }
    }

    /**
     * The output step in per-pixel mode. An explicit step that will not fit
     * in memory is refused; the automatic one (an eighth of a window) is
     * coarsened until it fits, and the coarsening is logged.
     */
    static int pixelStepPx(DensityMapJobSpec spec, double pxUm, int windowPx, int srcW, int srcH, int nChannels)
            throws IOException {
        long cap = Runtime.getRuntime().maxMemory() / 2L;
        if (spec.pixelStepUm > 0) {
            int step = Math.max(1, (int) Math.round(spec.pixelStepUm / pxUm));
            long need = DensityMapJobSpec.estimateGridMemoryBytes(ceilDiv(srcW, step), ceilDiv(srcH, step), nChannels);
            if (need > cap) {
                throw new IOException(String.format(
                        Locale.ROOT,
                        "A per-pixel map of %dx%d sampled every %.2f um with %d channels needs ~%.1f GB of heap"
                                + " (cap %.1f GB, half of QuPath's max). Use a coarser output step, set it to 0"
                                + " (automatic), or use window mode.",
                        srcW,
                        srcH,
                        step * pxUm,
                        nChannels,
                        need / 1.0e9,
                        cap / 1.0e9));
            }
            return step;
        }
        int step = Math.max(1, (int) Math.round(windowPx / 8.0));
        int first = step;
        while (DensityMapJobSpec.estimateGridMemoryBytes(ceilDiv(srcW, step), ceilDiv(srcH, step), nChannels) > cap) {
            step *= 2;
        }
        if (step != first) {
            logger.info(
                    "Per-pixel density: output step coarsened from {} px to {} px to fit half the heap", first, step);
        }
        return step;
    }

    static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * How a slide is cut into tiles and where its cells sit.
     *
     * @param gridW     cells across the slide
     * @param gridH     cells down the slide
     * @param cellPx    stride (window mode) or output step (pixel mode)
     * @param windowPx  window side
     * @param tileStep  distance between tile core origins, a whole number of cells
     * @param margin    extra core pixels each tile reads past its step, so its
     *                  last window is whole (window mode; 0 in pixel mode)
     * @param nTilesX   tiles across
     * @param nTilesY   tiles down
     * @param pixel     true for pixel mode
     */
    record Geometry(
            int gridW,
            int gridH,
            int cellPx,
            int windowPx,
            int tileStep,
            int margin,
            int nTilesX,
            int nTilesY,
            boolean pixel) {

        /** Window mode: floor windows, tiles overlapping by window - stride. */
        static Geometry window(int srcW, int srcH, int windowPx, int stridePx, int tileWindows) {
            int gridW = Math.max(0, (srcW - windowPx) / stridePx + 1);
            int gridH = Math.max(0, (srcH - windowPx) / stridePx + 1);
            int tileStep = Math.max(1, tileWindows) * windowPx;
            tileStep = Math.max(stridePx, (tileStep / stridePx) * stridePx);
            int perTile = tileStep / stridePx;
            return new Geometry(
                    gridW,
                    gridH,
                    stridePx,
                    windowPx,
                    tileStep,
                    windowPx - stridePx,
                    ceilDiv(gridW, perTile),
                    ceilDiv(gridH, perTile),
                    false);
        }

        /** Pixel mode: one sample per step block; tiles are a whole number of blocks. */
        static Geometry pixel(int srcW, int srcH, int windowPx, int stepPx) {
            int tileStep = Math.max(stepPx, ((TILE_WIN * windowPx) / stepPx) * stepPx);
            return new Geometry(
                    ceilDiv(srcW, stepPx),
                    ceilDiv(srcH, stepPx),
                    stepPx,
                    windowPx,
                    tileStep,
                    0,
                    ceilDiv(srcW, tileStep),
                    ceilDiv(srcH, tileStep),
                    true);
        }

        /** Cells a core of this length holds, matching density_tile.py. */
        int cellsIn(int coreLen) {
            if (pixel) return ceilDiv(coreLen, cellPx);
            return coreLen < windowPx ? 0 : (coreLen - windowPx) / cellPx + 1;
        }

        DensityLattice lattice() {
            return pixel
                    ? DensityLattice.blocks(gridW, gridH, cellPx)
                    : DensityLattice.windows(gridW, gridH, cellPx, windowPx, 0, 0);
        }
    }

    /** Best-effort recursive delete of a scratch directory. */
    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // leaked tmp files are not worth surfacing to the user
                }
            });
        } catch (IOException ignored) {
            // the tree went away underneath us; nothing to do
        }
    }

    private static Path resolveProjectDir(Project<BufferedImage> project) {
        if (project != null && project.getPath() != null) {
            Path p = project.getPath().getParent();
            if (p != null) return p;
        }
        return Path.of(System.getProperty("user.home"), "QuPath", "fiber-out");
    }

    /**
     * Delegates to {@link DensitySidecar#sanitize(String)} so the writer and
     * the sampling-command's sidecar-locator stay in sync. If you change the
     * sanitisation rules, change them there.
     */
    private static String sanitize(String name) {
        return DensitySidecar.sanitize(name);
    }

    /**
     * Job spec carrying the dialog's resolved settings. Records keep the
     * workflow signature small and let the dialog assemble in one place.
     */
    public static final class DensityMapJobSpec {
        public final double windowSizeUm;
        public final double windowOverlapPercent;
        public final String segChannel;
        public final String thresholdMethod;
        public final int manualThreshold;
        public final String ridgeFilter;
        public final double sigmaMinUm;
        public final double sigmaMaxUm;
        public final double sigmaStepUm;
        public final double minFiberAreaUm2;
        public final boolean invertIntensity;
        public final double rollingBallRadiusUm;
        public final Double projectThresholdNorm; // null = not used
        /**
         * When true (and Channels mode was picked), the workflow writes a
         * companion {@code <sidecar>.attach} marker file alongside the
         * sidecar. The extension's image-open hook reads this marker to
         * auto-attach the channels on future opens. The dialog's pre-run
         * warning for RGB base images was the consent moment; the auto-hook
         * does not re-prompt.
         */
        public final boolean writeAutoReattachMarker;
        /**
         * When true, the sidecar is bilinear-interpolated from the compact
         * grid into source-pixel space (smooth heatmap). When false, each
         * grid cell is nearest-neighbour replicated across its window
         * footprint (visible tile boundaries). Both modes are sentinel-aware:
         * raw=0 / no-data corners stay transparent.
         */
        public final boolean smoothInterpolation;

        /**
         * Density compute mode: "window" (per-window aggregation; cheap, blocky)
         * or "pixel" (a window centred on every pixel via uniform_filter,
         * sampled every {@link #pixelStepUm}; smooth).
         */
        public final String densityMode;

        /**
         * Pixel mode only: microns between output samples. 0 = automatic (an
         * eighth of a window, coarsened until the map fits half the heap).
         */
        public final double pixelStepUm;

        /**
         * How a run-wide threshold was obtained, when the workflow pooled one
         * (see {@link PooledThreshold}); null when the user's own method was used.
         */
        public final String thresholdSource;

        /**
         * When true, the sidecar grows additional channels: one per class
         * name in {@link #objectDensityClasses}, computed as the fraction
         * of pixels in the local box that fall inside any object of that
         * class. Same window-size / mode / stride as the fiber channels.
         */
        public final boolean includeObjectDensity;

        /** Class names (e.g. "CollagenAnalysis", "Tumor: Stroma") to compute as object-density channels. */
        public final List<String> objectDensityClasses;

        /**
         * When true, the sidecar grows additional channels: one per row in
         * {@link #pixelPositivitySpecs}, computed as the fraction of pixels
         * in the local box that pass the row's {@code channel op threshold}
         * rule. Same window-size / mode / stride as the fiber channels.
         */
        public final boolean includePixelPositivity;

        /** Per-row positivity rules (source channel + operator + threshold). */
        public final List<PixelPositivitySpec> pixelPositivitySpecs;

        public DensityMapJobSpec(
                double windowSizeUm,
                double windowOverlapPercent,
                String segChannel,
                String thresholdMethod,
                int manualThreshold,
                String ridgeFilter,
                double sigmaMinUm,
                double sigmaMaxUm,
                double sigmaStepUm,
                double minFiberAreaUm2,
                boolean invertIntensity,
                double rollingBallRadiusUm,
                Double projectThresholdNorm,
                boolean writeAutoReattachMarker,
                boolean smoothInterpolation,
                String densityMode,
                double pixelStepUm,
                boolean includeObjectDensity,
                List<String> objectDensityClasses,
                boolean includePixelPositivity,
                List<PixelPositivitySpec> pixelPositivitySpecs) {
            this.windowSizeUm = windowSizeUm;
            this.windowOverlapPercent = windowOverlapPercent;
            this.segChannel = segChannel;
            this.thresholdMethod = thresholdMethod;
            this.manualThreshold = manualThreshold;
            this.ridgeFilter = ridgeFilter;
            this.sigmaMinUm = sigmaMinUm;
            this.sigmaMaxUm = sigmaMaxUm;
            this.sigmaStepUm = sigmaStepUm;
            this.minFiberAreaUm2 = minFiberAreaUm2;
            this.invertIntensity = invertIntensity;
            this.rollingBallRadiusUm = rollingBallRadiusUm;
            this.projectThresholdNorm = projectThresholdNorm;
            this.writeAutoReattachMarker = writeAutoReattachMarker;
            this.smoothInterpolation = smoothInterpolation;
            String mode = densityMode == null ? "window" : densityMode.trim().toLowerCase();
            if (!"window".equals(mode) && !"pixel".equals(mode)) {
                throw new IllegalArgumentException(
                        "densityMode must be 'window' or 'pixel', got '" + densityMode + "'");
            }
            this.densityMode = mode;
            if (!(pixelStepUm >= 0)) {
                throw new IllegalArgumentException("pixelStepUm must be >= 0 (0 = automatic), got " + pixelStepUm);
            }
            this.pixelStepUm = pixelStepUm;
            this.thresholdSource = null;
            this.includeObjectDensity = includeObjectDensity;
            this.objectDensityClasses = objectDensityClasses == null
                    ? java.util.Collections.emptyList()
                    : List.copyOf(objectDensityClasses);
            this.includePixelPositivity = includePixelPositivity;
            this.pixelPositivitySpecs = pixelPositivitySpecs == null
                    ? java.util.Collections.emptyList()
                    : List.copyOf(pixelPositivitySpecs);
        }

        /**
         * This spec with a run-wide threshold every tile uses instead of its own.
         *
         * @param thresholdNorm the cut on [0, 1]
         * @param source        how it was obtained, for the provenance record
         * @return a copy using {@code project_otsu} at that value
         */
        public DensityMapJobSpec withPooledThreshold(double thresholdNorm, String source) {
            return new DensityMapJobSpec(this, thresholdNorm, source);
        }

        private DensityMapJobSpec(DensityMapJobSpec o, double thresholdNorm, String source) {
            this.windowSizeUm = o.windowSizeUm;
            this.windowOverlapPercent = o.windowOverlapPercent;
            this.segChannel = o.segChannel;
            this.thresholdMethod = "project_otsu";
            this.manualThreshold = o.manualThreshold;
            this.ridgeFilter = o.ridgeFilter;
            this.sigmaMinUm = o.sigmaMinUm;
            this.sigmaMaxUm = o.sigmaMaxUm;
            this.sigmaStepUm = o.sigmaStepUm;
            this.minFiberAreaUm2 = o.minFiberAreaUm2;
            this.invertIntensity = o.invertIntensity;
            this.rollingBallRadiusUm = o.rollingBallRadiusUm;
            this.projectThresholdNorm = thresholdNorm;
            this.writeAutoReattachMarker = o.writeAutoReattachMarker;
            this.smoothInterpolation = o.smoothInterpolation;
            this.densityMode = o.densityMode;
            this.pixelStepUm = o.pixelStepUm;
            this.thresholdSource = source;
            this.includeObjectDensity = o.includeObjectDensity;
            this.objectDensityClasses = o.objectDensityClasses;
            this.includePixelPositivity = o.includePixelPositivity;
            this.pixelPositivitySpecs = o.pixelPositivitySpecs;
        }

        /** Ordered echo of every spec field (for params.json / params.txt). */
        public Map<String, Object> toOrderedMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windowSizeUm", windowSizeUm);
            m.put("windowOverlapPercent", windowOverlapPercent);
            m.put("segChannel", segChannel);
            m.put("thresholdMethod", thresholdMethod);
            m.put("manualThreshold", manualThreshold);
            m.put("ridgeFilter", ridgeFilter);
            m.put("sigmaMinUm", sigmaMinUm);
            m.put("sigmaMaxUm", sigmaMaxUm);
            m.put("sigmaStepUm", sigmaStepUm);
            m.put("minFiberAreaUm2", minFiberAreaUm2);
            m.put("invertIntensity", invertIntensity);
            m.put("rollingBallRadiusUm", rollingBallRadiusUm);
            m.put("projectThresholdNorm", projectThresholdNorm);
            m.put("writeAutoReattachMarker", writeAutoReattachMarker);
            m.put("smoothInterpolation", smoothInterpolation);
            m.put("densityMode", densityMode);
            m.put("pixelStepUm", pixelStepUm);
            if (thresholdSource != null) m.put("thresholdSource", thresholdSource);
            m.put("includeObjectDensity", includeObjectDensity);
            m.put("objectDensityClasses", objectDensityClasses);
            m.put("includePixelPositivity", includePixelPositivity);
            // Serialize positivity specs as an ordered list of maps -- easier to
            // read in params.txt than one long string, and Gson round-trips
            // Map<String, Object> cleanly for params.json.
            java.util.List<Map<String, Object>> posMaps = new ArrayList<>(pixelPositivitySpecs.size());
            for (PixelPositivitySpec pps : pixelPositivitySpecs) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("channelIndex", pps.channelIndex);
                row.put("channelName", pps.channelName);
                row.put("op", pps.op);
                row.put("threshold", pps.threshold);
                posMaps.add(row);
            }
            m.put("pixelPositivitySpecs", posMaps);
            return m;
        }

        /**
         * Heap for the slide-wide accumulator: one float per cell per channel.
         *
         * @param gridW     cells across
         * @param gridH     cells down
         * @param nChannels channels
         * @return bytes
         */
        public static long estimateGridMemoryBytes(int gridW, int gridH, int nChannels) {
            return (long) gridW * (long) gridH * 4L * (long) nChannels;
        }

        /**
         * Reconstruct a spec from a {@code _density_params.json} file written
         * by the workflow. Tolerant of the {@code meta} wrapper: pulls
         * {@code parameters} if present, otherwise reads keys at the root.
         */
        public static DensityMapJobSpec fromJson(java.io.File file) throws IOException {
            String body = java.nio.file.Files.readString(file.toPath());
            com.google.gson.JsonObject root =
                    new com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject.class);
            com.google.gson.JsonObject p = root.has("parameters") ? root.getAsJsonObject("parameters") : root;
            DensityMapJobSpec spec = new DensityMapJobSpec(
                    jdouble(p, "windowSizeUm", 15.0),
                    jdouble(p, "windowOverlapPercent", 0.0),
                    jstring(p, "segChannel", "Raw intensity"),
                    jstring(p, "thresholdMethod", "Otsu"),
                    jint(p, "manualThreshold", 128),
                    jstring(p, "ridgeFilter", "None"),
                    jdouble(p, "sigmaMinUm", 1.0),
                    jdouble(p, "sigmaMaxUm", 4.0),
                    jdouble(p, "sigmaStepUm", 1.0),
                    jdouble(p, "minFiberAreaUm2", 1.0),
                    jbool(p, "invertIntensity", false),
                    jdouble(p, "rollingBallRadiusUm", 0.0),
                    p.has("projectThresholdNorm")
                                    && !p.get("projectThresholdNorm").isJsonNull()
                            ? p.get("projectThresholdNorm").getAsDouble()
                            : null,
                    jbool(p, "writeAutoReattachMarker", false),
                    jbool(p, "smoothInterpolation", false),
                    jstring(p, "densityMode", "window"),
                    jdouble(p, "pixelStepUm", 0.0),
                    jbool(p, "includeObjectDensity", false),
                    jstringList(p, "objectDensityClasses"),
                    jbool(p, "includePixelPositivity", false),
                    jpositivityList(p, "pixelPositivitySpecs"));
            // A pooled run-wide threshold is replayed at the value it had, not
            // re-pooled, so a re-run reproduces the original map.
            String source = jstring(p, "thresholdSource", null);
            if (source != null && spec.projectThresholdNorm != null) {
                return spec.withPooledThreshold(spec.projectThresholdNorm, source);
            }
            return spec;
        }

        private static List<PixelPositivitySpec> jpositivityList(com.google.gson.JsonObject o, String key) {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return java.util.Collections.emptyList();
            com.google.gson.JsonElement el = o.get(key);
            if (!el.isJsonArray()) return java.util.Collections.emptyList();
            com.google.gson.JsonArray arr = el.getAsJsonArray();
            List<PixelPositivitySpec> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement item = arr.get(i);
                if (item == null || !item.isJsonObject()) continue;
                com.google.gson.JsonObject row = item.getAsJsonObject();
                try {
                    out.add(new PixelPositivitySpec(
                            jint(row, "channelIndex", 0),
                            jstring(row, "channelName", null),
                            jstring(row, "op", PixelPositivitySpec.OP_GT),
                            jdouble(row, "threshold", 0.0)));
                } catch (IllegalArgumentException iae) {
                    // Skip malformed rows -- the run should still succeed with
                    // the remaining valid entries.
                    logger.warn("Skipping malformed pixel-positivity spec row: {}", iae.getMessage());
                }
            }
            return out;
        }

        private static List<String> jstringList(com.google.gson.JsonObject o, String key) {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return java.util.Collections.emptyList();
            com.google.gson.JsonElement el = o.get(key);
            if (!el.isJsonArray()) return java.util.Collections.emptyList();
            com.google.gson.JsonArray arr = el.getAsJsonArray();
            List<String> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement item = arr.get(i);
                if (item != null && !item.isJsonNull()) out.add(item.getAsString());
            }
            return out;
        }

        private static String jstring(com.google.gson.JsonObject o, String k, String def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
        }

        private static double jdouble(com.google.gson.JsonObject o, String k, double def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def;
        }

        private static int jint(com.google.gson.JsonObject o, String k, int def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
        }

        private static boolean jbool(com.google.gson.JsonObject o, String k, boolean def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
        }
    }

    /** Two-bar progress UI, mirroring the single-image workflow pattern. */
    private static final class ProgressUi {
        // headless == true: no JavaFX is touched; progress goes to the logger
        // instead. Used when this workflow is dispatched from a headless
        // Groovy script (e.g. `QuPath script rerun.groovy`) where the
        // JavaFX toolkit was never started and instantiating any Control
        // would throw "Toolkit not initialized" out of Control.<clinit>.
        private final boolean headless;
        private Stage stage;
        private Label mainLabel;
        private ProgressBar mainBar;
        private Label subLabel;
        private ProgressBar subBar;

        ProgressUi(Window owner) {
            this.headless = !HeadlessFx.isReady();
            if (headless) {
                logger.info("Fiber density map (headless mode -- progress logged below).");
                return;
            }
            // A Stage can only be built on the FX thread, and a script run
            // from QuPath's script editor calls in from its own thread.
            if (javafx.application.Platform.isFxApplicationThread()) {
                build(owner);
            } else {
                java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                    build(owner);
                    return null;
                });
                javafx.application.Platform.runLater(task);
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while opening the progress window", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Could not open the progress window", e.getCause());
                }
            }
        }

        private void build(Window owner) {
            this.mainLabel = new Label("Starting...");
            this.mainBar = new ProgressBar(0);
            this.subLabel = new Label("");
            this.subBar = new ProgressBar();

            this.stage = new Stage();
            stage.setTitle("Fiber density map - Progress");
            stage.initModality(Modality.NONE);
            if (owner != null) stage.initOwner(owner);
            stage.setResizable(false);

            mainBar.setPrefWidth(420);
            subBar.setPrefWidth(420);
            subBar.setProgress(-1);

            VBox root = new VBox(8, mainLabel, mainBar, subLabel, subBar);
            root.setPadding(new Insets(12));
            stage.setScene(new Scene(root));
        }

        void show() {
            if (headless) return;
            stage.show();
        }

        void close() {
            if (headless) return;
            stage.close();
        }

        void setMain(String message, int done, int total) {
            if (headless) {
                if (total > 0) logger.info("[{}/{}] {}", done, total, message);
                else logger.info("{}", message);
                return;
            }
            HeadlessFx.runLater(() -> {
                mainLabel.setText(message);
                if (total > 0) mainBar.setProgress((double) done / (double) total);
                else mainBar.setProgress(-1);
            });
        }

        void setSub(String msg) {
            if (headless) {
                if (msg != null && !msg.isBlank()) logger.info("  {}", msg);
                return;
            }
            HeadlessFx.runLater(() -> {
                subLabel.setText(msg != null ? msg : "");
                subBar.setProgress(-1);
            });
        }
    }
}
