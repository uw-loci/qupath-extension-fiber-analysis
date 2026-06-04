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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.application.Platform;
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
            Dialogs.showWarningNotification("Fiber density map", "No images selected.");
            return null;
        }

        Window owner = qupath != null ? qupath.getStage() : null;
        ProgressUi progress = new ProgressUi(owner);
        Platform.runLater(progress::show);

        Thread worker = new Thread(
                () -> {
                    try {
                        runWorker(spec, entries, project, progress);
                    } finally {
                        if (onComplete != null) Platform.runLater(onComplete);
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
                Platform.runLater(() -> {
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

            int total = entries.size();
            int ok = 0;
            int failed = 0;
            for (int i = 0; i < total; i++) {
                ProjectImageEntry<BufferedImage> entry = entries.get(i);
                final int idx = i;
                progress.setMain("Image " + (i + 1) + " of " + total + ": " + entry.getImageName(), i, total);
                try {
                    Path tiffPath = runForOneImage(spec, entry, outRoot, progress);
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
                    Platform.runLater(() -> Dialogs.showErrorNotification(
                            "Fiber density map", "Image \"" + name + "\" failed: " + msg));
                }
            }
            final int okF = ok;
            final int failedF = failed;
            Platform.runLater(() -> {
                progress.close();
                Dialogs.showInfoNotification(
                        "Fiber density map",
                        String.format(Locale.ROOT, "Done: %d ok, %d failed. Sidecars in %s", okF, failedF, outRoot));
            });
        } finally {
            Platform.runLater(progress::close);
        }
    }

    /** Compute + write the density-map sidecar for a single image. Returns the output path. */
    private Path runForOneImage(
            DensityMapJobSpec spec, ProjectImageEntry<BufferedImage> entry, Path outRoot, ProgressUi progress)
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
        int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlapFrac)));

        // Slide-wide grid dimensions, computed the same way fiberlib.windows.compute_windows does:
        // Hw = (H - windowPx) / stridePx + 1 (floor; partial trailing windows are dropped).
        int gridH = Math.max(0, (srcH - windowPx) / stridePx + 1);
        int gridW = Math.max(0, (srcW - windowPx) / stridePx + 1);
        if (gridH == 0 || gridW == 0) {
            throw new IOException("Image is smaller than one window in at least one dimension (window_px=" + windowPx
                    + "; image=" + srcW + "x" + srcH + ")");
        }

        // Per-channel float accumulators (slide-wide). NaN = no data.
        List<DensityChannelSpec> channels = DensityChannelSpec.defaultChannels();
        float[][] accum = new float[channels.size()][gridW * gridH];
        for (float[] arr : accum) java.util.Arrays.fill(arr, Float.NaN);

        // Tile size = TILE_WIN whole windows.
        int tilePx = TILE_WIN * windowPx;
        // Total compute tiles = ceil(srcW / tilePx) * ceil(srcH / tilePx).
        int nTilesX = (srcW + tilePx - 1) / tilePx;
        int nTilesY = (srcH + tilePx - 1) / tilePx;
        int totalTiles = nTilesX * nTilesY;
        int doneTiles = 0;

        Path workDir = Files.createTempDirectory("fiber-density-tile-");
        try {
            for (int ty = 0; ty < nTilesY; ty++) {
                int tileY = ty * tilePx;
                int thh = Math.min(tilePx, srcH - tileY);
                if (thh <= 0) continue;
                for (int tx = 0; tx < nTilesX; tx++) {
                    int tileX = tx * tilePx;
                    int tww = Math.min(tilePx, srcW - tileX);
                    if (tww <= 0) continue;

                    // For honest per-window stats we need the tile to be the full width
                    // of an integer number of windows; the last tile in a row / column
                    // may be smaller, in which case some trailing windows are partial
                    // and the Python side will simply produce fewer windows for that
                    // tile. We accumulate into whatever indices the tile contributes.
                    int tileGridW = (tww - windowPx) / stridePx + 1;
                    int tileGridH = (thh - windowPx) / stridePx + 1;
                    if (tileGridW <= 0 || tileGridH <= 0) {
                        doneTiles++;
                        continue;
                    }

                    final int tdoneTiles = doneTiles + 1;
                    progress.setSub("Tile " + tdoneTiles + " of " + totalTiles + " (" + tileX + "," + tileY + ")");

                    Path tilePng = workDir.resolve(String.format("tile_%04d_%04d.png", tx, ty));
                    RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, tileX, tileY, tww, thh);
                    BufferedImage img = server.readRegion(req);
                    if (img == null) {
                        doneTiles++;
                        continue;
                    }
                    javax.imageio.ImageIO.write(img, "PNG", tilePng.toFile());

                    Path tileNpz = workDir.resolve(String.format("tile_%04d_%04d.npz", tx, ty));
                    Map<String, Object> in = buildTileInputs(spec, pxUm, tilePng, tww, thh, tileNpz);
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

                    // Slot the tile into the slide-wide accumulator.
                    accumulateTile(accum, channels, tileNpz, tx, ty, gridW, gridH, tileGridW, tileGridH);
                    doneTiles++;
                }
            }
        } finally {
            // Best-effort cleanup of per-tile artefacts.
            try {
                Files.walk(workDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // workdir cleanup is best-effort; leaked tmp files are
                        // not worth surfacing to the user.
                    }
                });
            } catch (IOException ignored) {
                // tmp tree walked off underneath us; nothing to do.
            }
        }

        // Quantize each channel into uint16 with sentinel 0 = no-data.
        short[][] grids = new short[channels.size()][gridW * gridH];
        List<DensityTiffWriter.ChannelQuant> quants = new ArrayList<>(channels.size());
        for (int c = 0; c < channels.size(); c++) {
            DensityTiffWriter.ChannelQuant q = quantize(accum[c], grids[c]);
            quants.add(q);
            logger.info(
                    "Quantized channel {} ({}): scale={}, offset={}",
                    c,
                    channels.get(c).channelName,
                    q.scale,
                    q.offset);
        }

        // Sidecar pixel size = stride_px * source_pixel_size_um (one density
        // pixel per window stride in source pixels).
        double sidecarPxUm = stridePx * pxUm;

        String safeName = sanitize(entry.getImageName());
        Path outPath = outRoot.resolve(safeName + "_density.ome.tif");
        DensityTiffWriter.write(outPath.toString(), gridW, gridH, sidecarPxUm, channels, grids, quants);
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
        in.put("output_npz_path", outNpz.toString());
        return in;
    }

    /** Copy the per-window arrays from one tile's npz into the slide-wide accumulator. */
    private static void accumulateTile(
            float[][] accum,
            List<DensityChannelSpec> channels,
            Path npzPath,
            int tileX,
            int tileY,
            int slideGridW,
            int slideGridH,
            int tileGridW,
            int tileGridH)
            throws IOException {
        Map<String, NpzReader.Entry> npz = NpzReader.read(npzPath);
        // Verify the tile produced the expected grid shape (the Python side
        // may have computed slightly fewer rows/cols if the tile clipped at
        // the image edge; the smaller of the two governs).
        NpzReader.Entry shape = npz.get("grid_shape");
        int npyH = tileGridH;
        int npyW = tileGridW;
        if (shape != null) {
            int[] s = shape.asInt32();
            if (s.length >= 2) {
                npyH = s[0];
                npyW = s[1];
            }
        }
        int copyH = Math.min(npyH, tileGridH);
        int copyW = Math.min(npyW, tileGridW);

        // Where this tile lands in the slide-wide grid. Each tile begins at
        // window-coord (tileX * TILE_WIN, tileY * TILE_WIN).
        int slideX = tileX * TILE_WIN;
        int slideY = tileY * TILE_WIN;

        for (int c = 0; c < channels.size(); c++) {
            String key = channels.get(c).npzKey;
            NpzReader.Entry e = npz.get(key);
            if (e == null) {
                logger.warn("Tile npz missing key '{}' -- channel will keep NaN", key);
                continue;
            }
            float[] tileArr = e.asFloat32();
            for (int ry = 0; ry < copyH; ry++) {
                int sy = slideY + ry;
                if (sy >= slideGridH) break;
                for (int rx = 0; rx < copyW; rx++) {
                    int sx = slideX + rx;
                    if (sx >= slideGridW) break;
                    accum[c][sy * slideGridW + sx] = tileArr[ry * npyW + rx];
                }
            }
        }
    }

    /**
     * Linearly quantize the float accumulator into uint16 raw values:
     * sentinel 0 for NaN cells, otherwise scale into [1, 65535].
     * Returns the scale + offset so {@code real = raw * scale + offset}.
     */
    static DensityTiffWriter.ChannelQuant quantize(float[] src, short[] dst) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (float v : src) {
            if (Float.isNaN(v) || Float.isInfinite(v)) continue;
            any = true;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (!any) {
            // Channel is empty -- write all-zero, scale = 1, offset = 0.
            java.util.Arrays.fill(dst, (short) 0);
            return new DensityTiffWriter.ChannelQuant(1.0, 0.0);
        }
        if (max - min < 1e-12) {
            // Constant channel -- map every valid cell to the midpoint of
            // [1, 65535] and encode the constant via offset.
            for (int i = 0; i < src.length; i++) {
                float v = src[i];
                dst[i] = (Float.isNaN(v) || Float.isInfinite(v)) ? (short) 0 : (short) 32768;
            }
            return new DensityTiffWriter.ChannelQuant(0.0, min);
        }
        // Map [min, max] into [1, 65535]: raw = round(1 + (v - min) * (65534 / (max - min))).
        double range = max - min;
        double slope = 65534.0 / range;
        // real = (raw - 1) / slope + min  ==  raw * (1/slope) + (min - 1/slope)
        double scale = 1.0 / slope;
        double offset = min - 1.0 * scale;
        for (int i = 0; i < src.length; i++) {
            float v = src[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                dst[i] = 0;
                continue;
            }
            long raw = Math.round(1.0 + (v - min) * slope);
            if (raw < 1) raw = 1;
            if (raw > 65535) raw = 65535;
            dst[i] = (short) (raw & 0xffff);
        }
        return new DensityTiffWriter.ChannelQuant(scale, offset);
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
                Double projectThresholdNorm) {
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
        }
    }

    /** Two-bar progress UI, mirroring the single-image workflow pattern. */
    private static final class ProgressUi {
        private final Stage stage;
        private final Label mainLabel = new Label("Starting...");
        private final ProgressBar mainBar = new ProgressBar(0);
        private final Label subLabel = new Label("");
        private final ProgressBar subBar = new ProgressBar();

        ProgressUi(Window owner) {
            this.stage = new Stage();
            stage.setTitle("Fiber density map");
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
            stage.show();
        }

        void close() {
            stage.close();
        }

        void setMain(String message, int done, int total) {
            Platform.runLater(() -> {
                mainLabel.setText(message);
                if (total > 0) mainBar.setProgress((double) done / (double) total);
                else mainBar.setProgress(-1);
            });
        }

        Consumer<String> setSub = msg -> Platform.runLater(() -> {
            subLabel.setText(msg != null ? msg : "");
            subBar.setProgress(-1);
        });

        void setSub(String message) {
            setSub.accept(message);
        }
    }
}
