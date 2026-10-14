/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.classifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.NpzReader;
import qupath.ext.fiberanalysis.analysis.SourceChannel;
import qupath.ext.fiberanalysis.analysis.TileHalo;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * Builds the slide-wide window-feature matrix by tile-streaming a region and
 * pooling each tile's windows onto one lattice.
 *
 * <p><b>Tiles overlap by exactly {@code windowPx - stridePx}.</b> The density
 * map does not, which is harmless for a visualisation and not harmless here: a
 * window near a tile's trailing edge would be computed against a mask that
 * stops at the tile boundary, so its coverage, order parameter and skeleton
 * length would all be wrong in a way that correlates with position. The model
 * would learn the tiling. With the overlap, every whole window on the lattice
 * is computed from its full support in exactly one tile.
 *
 * <p>Tile origins are multiples of the stride, so a tile's window {@code j}
 * is slide window {@code tileOriginPx / stridePx + j} with no interpolation
 * and no rounding. That identity is what lets the matrix, the raster and the
 * core detections all agree on where a window is.
 *
 * <p>The cost is {@code O(region pixels)} and does not depend on the stride.
 * Halving the stride quadruples the number of windows and costs the same,
 * because every feature in the bank is a fixed function of additive per-pixel
 * accumulators.
 */
public final class WindowFeatureMatrix {

    private static final Logger logger = LoggerFactory.getLogger(WindowFeatureMatrix.class);

    /** Nominal tile side, in whole windows, before the overlap margin is added. */
    private static final int DEFAULT_TILE_WINDOWS = 8;

    private WindowFeatureMatrix() {}

    /**
     * What to compute, and where.
     *
     * @param segmentation          segmentation settings
     * @param windowSizeUm          window side in microns
     * @param windowOverlapPercent  overlap between consecutive windows, 0 to 95
     * @param includeIntensity      compute the intensity features
     * @param tensorSigma           structure-tensor integration scale, or null for the default
     * @param minWindowPixels       fiber pixels needed for orientation statistics, or null for the default
     * @param tileWindows           nominal tile side in whole windows
     */
    public record Spec(
            SegmentationSpec segmentation,
            double windowSizeUm,
            double windowOverlapPercent,
            boolean includeIntensity,
            Double tensorSigma,
            Integer minWindowPixels,
            int tileWindows) {

        /** @param segmentation settings; everything else takes its default */
        public static Spec defaults(SegmentationSpec segmentation, double windowSizeUm, double overlapPercent) {
            return new Spec(segmentation, windowSizeUm, overlapPercent, true, null, null, DEFAULT_TILE_WINDOWS);
        }
    }

    /**
     * The assembled matrix.
     *
     * @param featureNames feature names in column order
     * @param columns      one slide-wide grid per feature, length {@code gridW * gridH},
     *                     row-major, NaN where undefined or never computed
     * @param gridW        windows across
     * @param gridH        windows down
     * @param windowPx     window side in read-scale pixels
     * @param stridePx     window stride in read-scale pixels
     * @param partial      per window, true when it was clamped to the region edge
     * @param tilesFailed  tiles that threw; their windows stay NaN
     */
    public record Result(
            List<String> featureNames,
            float[][] columns,
            int gridW,
            int gridH,
            int windowPx,
            int stridePx,
            boolean[] partial,
            int tilesFailed) {

        /** @return number of windows on the lattice */
        public int windowCount() {
            return gridW * gridH;
        }

        /**
         * @param name feature name
         * @return that feature's slide-wide grid
         * @throws IllegalArgumentException when the feature is not present
         */
        public float[] column(String name) {
            int i = featureNames.indexOf(name);
            if (i < 0) throw new IllegalArgumentException("No such feature: " + name);
            return columns[i];
        }
    }

    /**
     * Compute the matrix over a region of an image.
     *
     * @param server      the image
     * @param region      the region to cover, at downsample 1
     * @param pixelSizeUm microns per pixel
     * @param spec        what to compute
     * @param workDir     scratch directory for tile PNGs and npz files
     * @param progress    called with a short status line per tile; may be null
     * @param cancelled   polled between tiles; may be null
     * @return the assembled matrix
     * @throws IOException if the lattice is empty or every tile failed
     * @throws InterruptedException if the caller cancelled
     */
    public static Result compute(
            ImageServer<BufferedImage> server,
            RegionRequest region,
            double pixelSizeUm,
            Spec spec,
            Path workDir,
            Consumer<String> progress,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {

        int windowPx = Math.max(2, (int) Math.round(spec.windowSizeUm() / pixelSizeUm));
        double overlapFrac = Math.max(0.0, Math.min(0.95, spec.windowOverlapPercent() / 100.0));
        int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlapFrac)));

        int regionW = region.getWidth();
        int regionH = region.getHeight();
        int gridW = WindowGeometry.totalCount(regionW, windowPx, stridePx);
        int gridH = WindowGeometry.totalCount(regionH, windowPx, stridePx);
        if (gridW == 0 || gridH == 0) {
            throw new IOException("Region " + regionW + "x" + regionH + " holds no windows at window_px=" + windowPx);
        }

        Tiling tiling = planTiles(regionW, regionH, windowPx, stridePx, spec.tileWindows());
        int tileStep = tiling.tileStep();
        int margin = tiling.margin();
        int nTilesX = tiling.nTilesX();
        int nTilesY = tiling.nTilesY();
        int totalTiles = nTilesX * nTilesY;

        String[] featureNames = null;
        float[][] columns = null;
        boolean[] partial = new boolean[gridW * gridH];
        // Every window starts flagged partial. A window a tile never
        // wrote -- because the tile failed -- then stays NaN AND excluded
        // from training, instead of being an all-NaN row nobody noticed.
        Arrays.fill(partial, true);
        int tilesFailed = 0;
        int done = 0;
        SegmentationSpec seg = spec.segmentation();
        int halo = TileHalo.px(pixelSizeUm, seg.rollingBallRadiusUm(), seg.ridgeFilter(), seg.sigmaMaxUm());

        Files.createDirectories(workDir);
        for (int ty = 0; ty < nTilesY; ty++) {
            for (int tx = 0; tx < nTilesX; tx++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new InterruptedException("Window feature extraction cancelled");
                }
                done++;
                int localX = tx * tileStep;
                int localY = ty * tileStep;
                int coreW = Math.min(tileStep + margin, regionW - localX);
                int coreH = Math.min(tileStep + margin, regionH - localY);
                if (coreW <= 0 || coreH <= 0) continue;
                // Read a halo of filter context around the core. It may run
                // past the region (an annotation's bounds) but never past the
                // image; the features still pool over the core only.
                int coreX = region.getX() + localX;
                int coreY = region.getY() + localY;
                int readX = Math.max(0, coreX - halo);
                int readY = Math.max(0, coreY - halo);
                int readW = Math.min(server.getWidth(), coreX + coreW + halo) - readX;
                int readH = Math.min(server.getHeight(), coreY + coreH + halo) - readY;

                if (progress != null) {
                    progress.accept("Tile " + done + " of " + totalTiles);
                }

                Path tilePng = workDir.resolve(String.format("wf_%04d_%04d.png", tx, ty));
                Path tileNpz = workDir.resolve(String.format("wf_%04d_%04d.npz", tx, ty));
                RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, readX, readY, readW, readH);
                try {
                    SourceChannel.writeRegionPng(
                            server, req, spec.segmentation().segChannel(), tilePng);
                } catch (IOException ex) {
                    logger.warn("Tile ({},{}) could not be read: {}", localX, localY, ex.getMessage());
                    tilesFailed++;
                    continue;
                }

                Map<String, Object> in = spec.segmentation().toInputs(pixelSizeUm);
                in.put("region_image_path", tilePng.toString());
                in.put("region_w", readW);
                in.put("region_h", readH);
                in.put("crop_x", coreX - readX);
                in.put("crop_y", coreY - readY);
                in.put("crop_w", coreW);
                in.put("crop_h", coreH);
                in.put("pixel_size_um", pixelSizeUm);
                in.put("window_size_um", spec.windowSizeUm());
                in.put("window_overlap_percent", spec.windowOverlapPercent());
                in.put("include_intensity", spec.includeIntensity());
                if (spec.tensorSigma() != null) in.put("tensor_sigma", spec.tensorSigma());
                if (spec.minWindowPixels() != null) in.put("min_window_pixels", spec.minWindowPixels());
                in.put("output_npz_path", tileNpz.toString());

                Task task;
                try {
                    task = ApposeFiberService.getInstance().runTask("window_features", in);
                } catch (Exception ex) {
                    throw new IOException("Appose window_features failed: " + ex.getMessage(), ex);
                }
                Object ok = task.outputs.get("ok");
                if (ok == null || !"1".equals(ok.toString())) {
                    logger.warn(
                            "Tile ({},{}) returned no features: {}",
                            localX,
                            localY,
                            task.outputs.getOrDefault("error", "unknown"));
                    tilesFailed++;
                    continue;
                }

                String namesCsv = String.valueOf(task.outputs.get("feature_names"));
                String[] names = namesCsv.split(",");
                if (featureNames == null) {
                    featureNames = names;
                    columns = new float[names.length][gridW * gridH];
                    for (float[] col : columns) Arrays.fill(col, Float.NaN);
                } else if (!Arrays.equals(featureNames, names)) {
                    // Two tiles disagreeing about the columns means the
                    // feature set is not a function of the settings alone.
                    // Assembling them anyway would mix two features in one
                    // column, which no downstream check could detect.
                    throw new IOException("Tile (" + localX + "," + localY
                            + ") produced a different feature set than the first tile; refusing to mix them."
                            + " First: " + String.join(",", featureNames) + "; this tile: " + namesCsv);
                }

                slotTile(
                        tileNpz,
                        columns,
                        partial,
                        featureNames.length,
                        localX / stridePx,
                        localY / stridePx,
                        gridW,
                        gridH);
            }
        }

        if (featureNames == null) {
            throw new IOException("Every tile failed; no features were computed (" + tilesFailed + " tiles)");
        }
        logger.info(
                "Window features: {}x{} windows, {} features, window_px={}, stride_px={}, {} tile(s) failed",
                gridW,
                gridH,
                featureNames.length,
                windowPx,
                stridePx,
                tilesFailed);
        return new Result(List.of(featureNames), columns, gridW, gridH, windowPx, stridePx, partial, tilesFailed);
    }

    /**
     * How the region is cut into tiles.
     *
     * @param tileStep distance between tile origins, a whole number of strides
     * @param margin   extra pixels each tile reads past its step, so the last
     *                 window in the tile still has its full support
     * @param nTilesX  tiles across
     * @param nTilesY  tiles down
     */
    public record Tiling(int tileStep, int margin, int nTilesX, int nTilesY) {}

    /**
     * Plan the tiling.
     *
     * <p>Separated out and tested directly, because the property that matters
     * -- every window on the slide lattice computed exactly once, from its
     * full support -- is pure arithmetic, and getting it wrong produces a
     * model that has learned the seams rather than an error.
     *
     * @param regionW     region width in read-scale pixels
     * @param regionH     region height in read-scale pixels
     * @param windowPx    window side
     * @param stridePx    window stride
     * @param tileWindows nominal tile side in whole windows
     * @return the tiling
     */
    public static Tiling planTiles(int regionW, int regionH, int windowPx, int stridePx, int tileWindows) {
        int tileStep = Math.max(1, tileWindows) * windowPx;
        // Snap down to a whole number of strides so every tile origin is a
        // lattice position, which makes "tile window j is slide window
        // origin/stride + j" exact rather than approximately right.
        tileStep = Math.max(stridePx, (tileStep / stridePx) * stridePx);
        int margin = windowPx - stridePx;
        // Tiles are counted in WINDOWS, not pixels. Counting
        // ceil(regionW / tileStep) overshoots whenever the overlap margin
        // already carried the previous tile to the region edge, and the
        // extra tile then invents a window the slide lattice does not have.
        int windowsPerTile = Math.max(1, tileStep / stridePx);
        int nTilesX = tileCount(WindowGeometry.totalCount(regionW, windowPx, stridePx), windowsPerTile);
        int nTilesY = tileCount(WindowGeometry.totalCount(regionH, windowPx, stridePx), windowsPerTile);
        return new Tiling(tileStep, margin, nTilesX, nTilesY);
    }

    private static int tileCount(int windowsOnAxis, int windowsPerTile) {
        if (windowsOnAxis <= 0) return 0;
        return (windowsOnAxis + windowsPerTile - 1) / windowsPerTile;
    }

    /** Copy one tile's (n_windows, n_features) matrix into the slide-wide columns. */
    private static void slotTile(
            Path npzPath,
            float[][] columns,
            boolean[] partial,
            int nFeatures,
            int slideX0,
            int slideY0,
            int gridW,
            int gridH)
            throws IOException {
        Map<String, NpzReader.Entry> npz = NpzReader.read(npzPath);
        NpzReader.Entry feat = require(npz, "features", npzPath);
        NpzReader.Entry shape = require(npz, "grid_shape", npzPath);
        NpzReader.Entry part = require(npz, "partial", npzPath);

        int[] gs = shape.asInt32();
        int tileH = gs[0];
        int tileW = gs[1];
        if (feat.shape.length != 2 || feat.shape[0] != tileH * tileW || feat.shape[1] != nFeatures) {
            throw new IOException("Tile " + npzPath.getFileName() + " features shape "
                    + Arrays.toString(feat.shape) + " does not match grid " + tileH + "x" + tileW + " x "
                    + nFeatures);
        }
        float[] values = feat.asFloat32();
        int[] partialFlags = part.asInt32();

        for (int ly = 0; ly < tileH; ly++) {
            int sy = slideY0 + ly;
            if (sy < 0 || sy >= gridH) continue;
            for (int lx = 0; lx < tileW; lx++) {
                int sx = slideX0 + lx;
                if (sx < 0 || sx >= gridW) continue;
                int src = ly * tileW + lx;
                int dst = sy * gridW + sx;
                // The tiling is built so a tile only ever produces clamped
                // windows at the region's own edge. This guard keeps a
                // truncated window from overwriting a whole one if that ever
                // stops being true -- silently mixing the two is the failure
                // the overlap margin exists to prevent.
                boolean tilePartial = partialFlags[src] != 0;
                boolean slideEdge = sx == gridW - 1 || sy == gridH - 1;
                if (tilePartial && !slideEdge) continue;
                for (int f = 0; f < nFeatures; f++) {
                    columns[f][dst] = values[src * nFeatures + f];
                }
                partial[dst] = tilePartial;
            }
        }
    }

    private static NpzReader.Entry require(Map<String, NpzReader.Entry> npz, String key, Path path) throws IOException {
        NpzReader.Entry e = npz.get(key);
        if (e == null) {
            throw new IOException("Tile npz " + path.getFileName() + " is missing '" + key + "'");
        }
        return e;
    }
}
