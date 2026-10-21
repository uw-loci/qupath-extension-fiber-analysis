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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apposed.appose.Service.Task;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * One threshold (Otsu or Triangle) for a whole run, from a grid of sample tiles
 * spread over every selected image -- slide-level for one image,
 * project-level for several.
 *
 * <p>Otsu per tile is the classic cause of seams: a nearly empty tile cuts
 * into background noise while its neighbour clips faint fibers, so coverage
 * jumps at the tile edge and is not comparable between tiles, let alone
 * slides. The density map is otherwise computed tile by tile; this is the one
 * statistic that has to see the whole run, and it is gathered by sampling,
 * never by loading a slide.
 */
public final class PooledThreshold {

    private static final Logger logger = LoggerFactory.getLogger(PooledThreshold.class);

    /** Sample tiles per image are {@code GRID x GRID}. */
    static final int GRID = 4;

    /** Side of a sample tile at full resolution, pixels. */
    static final int SAMPLE_PX = 1024;

    private PooledThreshold() {}

    /**
     * @param thresholdNorm the cut on [0, 1] every tile uses
     * @param method        "otsu" or "triangle"
     * @param images        images sampled
     * @param regions       sample tiles used
     * @param pixels        pixels in the pooled histogram
     * @param histogram     the pooled histogram's counts, on the channel's full scale
     */
    public record Result(
            double thresholdNorm, String method, int images, int regions, long pixels, List<Long> histogram) {}

    /**
     * Sample boxes for one image: one per grid cell, centred in it.
     *
     * @param w    image width
     * @param h    image height
     * @param grid cells per side
     * @param side preferred box side
     * @return {@code {x, y, w, h}} boxes inside the image
     */
    static List<int[]> sampleBoxes(int w, int h, int grid, int side) {
        List<int[]> out = new ArrayList<>();
        int cellW = Math.max(1, w / grid);
        int cellH = Math.max(1, h / grid);
        int bw = Math.min(side, cellW);
        int bh = Math.min(side, cellH);
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int x = Math.min(w - bw, gx * cellW + (cellW - bw) / 2);
                int y = Math.min(h - bh, gy * cellH + (cellH - bh) / 2);
                out.add(new int[] {Math.max(0, x), Math.max(0, y), bw, bh});
            }
        }
        return out;
    }

    /**
     * @param entries  images in the run
     * @param spec     the segmentation settings to pool under
     * @param workDir  scratch directory for the sample PNGs
     * @param progress status sink; may be null
     * @return the pooled threshold
     * @throws IOException if no image could be sampled or the task failed
     */
    public static Result pool(
            List<ProjectImageEntry<BufferedImage>> entries, Settings spec, Path workDir, Consumer<String> progress)
            throws IOException {
        Files.createDirectories(workDir);
        List<String> pngs = new ArrayList<>();
        List<Double> pixelSizes = new ArrayList<>();
        int images = 0;
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            if (progress != null) progress.accept("Sampling for a run-wide threshold: " + entry.getImageName());
            ImageData<BufferedImage> data = entry.readImageData();
            try (ImageServer<BufferedImage> server = data.getServer()) {
                var cal = server.getPixelCalibration();
                if (cal == null || !cal.hasPixelSizeMicrons()) continue;
                int k = 0;
                for (int[] b : sampleBoxes(server.getWidth(), server.getHeight(), GRID, SAMPLE_PX)) {
                    Path png = workDir.resolve(String.format("pool_%03d_%02d.png", images, k++));
                    RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, b[0], b[1], b[2], b[3]);
                    SourceChannel.writeRegionPng(server, req, spec.segChannel(), png);
                    pngs.add(png.toString());
                    pixelSizes.add(cal.getAveragedPixelSizeMicrons());
                }
                images++;
            } catch (Exception e) {
                if (e instanceof IOException io) throw io;
                throw new IOException("Could not sample " + entry.getImageName() + ": " + e.getMessage(), e);
            }
        }
        if (pngs.isEmpty()) throw new IOException("No calibrated image could be sampled for a run-wide threshold");
        return poolSamples(pngs, List.of(), pixelSizes, spec, images);
    }

    /**
     * One annotation's analysis region, as the pooling reads it.
     *
     * @param server      the image
     * @param roi         the annotation
     * @param readBox     {@code {x, y, w, h}} the region read around it, full-resolution pixels
     * @param downsample  the analysis downsample
     * @param pixelSizeUm the image's full-resolution pixel size
     */
    public record ZoneRegion(
            ImageServer<BufferedImage> server, ROI roi, int[] readBox, double downsample, double pixelSizeUm) {}

    /**
     * One threshold for every annotation of a run, fitted on samples of their
     * analysis zones at the analysis downsample, with the micron settings
     * converted per region. Used by annotation runs (one image or a batch)
     * and by project calibration, so all three fit the same thing.
     *
     * @param regions    the annotations' regions
     * @param zoneMode   "whole", "inside", "outside" or "both"
     * @param borderUm   zone width, microns
     * @param spec       the segmentation settings
     * @param workDir    scratch directory for the sample PNGs
     * @param maxSamples most sample boxes in all; regions are taken evenly when there are more
     * @param progress   status sink; may be null
     * @return the pooled threshold
     * @throws IOException if nothing could be sampled or the task failed
     */
    public static Result poolZones(
            List<ZoneRegion> regions,
            String zoneMode,
            double borderUm,
            Settings spec,
            Path workDir,
            int maxSamples,
            Consumer<String> progress)
            throws IOException {
        Files.createDirectories(workDir);
        List<ZoneRegion> chosen = regions;
        if (regions.size() > maxSamples) {
            chosen = new ArrayList<>();
            for (int i = 0; i < maxSamples; i++)
                chosen.add(regions.get((int) ((i + 0.5) * regions.size() / maxSamples)));
        }
        int perRegion = Math.max(1, Math.min(GRID * GRID, maxSamples / Math.max(1, chosen.size())));
        List<String> pngs = new ArrayList<>();
        List<String> masks = new ArrayList<>();
        List<Double> pixelSizes = new ArrayList<>();
        java.util.Set<String> images = new java.util.HashSet<>();
        int k = 0;
        for (int r = 0; r < chosen.size(); r++) {
            ZoneRegion z = chosen.get(r);
            if (progress != null)
                progress.accept("Sampling for a run-wide threshold: region " + (r + 1) + " of " + chosen.size());
            ROI zoneRoi = AnalysisZone.roi(z.roi(), zoneMode, borderUm / z.pixelSizeUm());
            int side = (int) Math.round(SAMPLE_PX * z.downsample());
            for (int[] b : zoneBoxes(z.readBox(), zoneRoi.getGeometry(), side, perRegion)) {
                Path png = workDir.resolve(String.format(java.util.Locale.ROOT, "zone_%04d.png", k));
                Path mask = workDir.resolve(String.format(java.util.Locale.ROOT, "zone_%04d_mask.png", k++));
                RegionRequest req =
                        RegionRequest.createInstance(z.server().getPath(), z.downsample(), b[0], b[1], b[2], b[3]);
                SourceChannel.WrittenSize size = SourceChannel.writeRegionPng(z.server(), req, spec.segChannel(), png);
                FiberAnalysisWorkflow.rasterisePolygonMask(
                        zoneRoi, b[0], b[1], size.width(), size.height(), z.downsample(), mask);
                pngs.add(png.toString());
                masks.add(mask.toString());
                pixelSizes.add(z.pixelSizeUm() * z.downsample());
                images.add(z.server().getPath());
            }
        }
        if (pngs.isEmpty()) throw new IOException("The analysis zones are empty; nothing to fit a threshold on");
        return poolSamples(pngs, masks, pixelSizes, spec, images.size());
    }

    /**
     * Sample boxes over a read region that touch the zone, spread evenly
     * through the candidates so a thin band is sampled along its length.
     *
     * @param readBox {@code {x, y, w, h}}
     * @param zone    the zone
     * @param side    box side, same units
     * @param max     most boxes returned
     * @return {@code {x, y, w, h}} boxes inside the read region
     */
    static List<int[]> zoneBoxes(int[] readBox, Geometry zone, int side, int max) {
        List<int[]> candidates = new ArrayList<>();
        int bw = Math.min(side, readBox[2]);
        int bh = Math.min(side, readBox[3]);
        var factory = zone.getFactory();
        for (int y = readBox[1]; y < readBox[1] + readBox[3]; y += bh) {
            for (int x = readBox[0]; x < readBox[0] + readBox[2]; x += bw) {
                int x0 = Math.min(x, readBox[0] + readBox[2] - bw);
                int y0 = Math.min(y, readBox[1] + readBox[3] - bh);
                Geometry cell = factory.toGeometry(new Envelope(x0, x0 + bw, y0, y0 + bh));
                if (cell.intersects(zone)) candidates.add(new int[] {x0, y0, bw, bh});
            }
        }
        if (candidates.size() <= max) return candidates;
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < max; i++) out.add(candidates.get((int) ((i + 0.5) * candidates.size() / max)));
        return out;
    }

    /** Pools sample PNGs, each with its own pixel size (um/px as read). */
    private static Result poolSamples(
            List<String> pngs, List<String> masks, List<Double> pixelSizes, Settings spec, int images)
            throws IOException {
        double[] sorted =
                pixelSizes.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double medianPx = sorted[sorted.length / 2];
        Map<String, Object> in = new HashMap<>();
        in.put("region_paths", pngs);
        in.put("mask_paths", new ArrayList<>(masks));
        in.put("region_pixel_um", new ArrayList<>(pixelSizes));
        in.put("seg_channel", spec.segChannel());
        in.put("ridge_filter", spec.ridgeFilter().toLowerCase(java.util.Locale.ROOT));
        in.put("sigma_min_um", spec.sigmaMinUm());
        in.put("sigma_max_um", spec.sigmaMaxUm());
        in.put("sigma_step_um", spec.sigmaStepUm());
        in.put("rolling_ball_um", spec.rollingBallRadiusUm());
        // Pixel values for a region with no size of its own; every region has one here.
        in.put("sigma_min", spec.sigmaMinUm() / medianPx);
        in.put("sigma_max", spec.sigmaMaxUm() / medianPx);
        in.put("sigma_step", spec.sigmaStepUm() / medianPx);
        in.put("invert_intensity", spec.invertIntensity());
        in.put("rolling_ball_radius", (int) Math.max(0, Math.round(spec.rollingBallRadiusUm() / medianPx)));
        in.put("method", method(spec.thresholdMethod()));
        Task task;
        try {
            task = ApposeFiberService.getInstance().runTask("calibrate_threshold", in);
        } catch (Exception e) {
            throw new IOException("calibrate_threshold failed: " + e.getMessage(), e);
        }
        Object out = task.outputs.get("result_json");
        if (out == null) throw new IOException("calibrate_threshold returned no result");
        JsonObject r = JsonParser.parseString(out.toString()).getAsJsonObject();
        if (r.has("error"))
            throw new IOException("calibrate_threshold: " + r.get("error").getAsString());
        List<Long> histogram = new ArrayList<>();
        if (r.has("histogram_counts")) {
            for (var el : r.getAsJsonArray("histogram_counts")) histogram.add(el.getAsLong());
        }
        Result res = new Result(
                r.get("threshold_normalised").getAsDouble(),
                method(spec.thresholdMethod()),
                images,
                r.get("n_regions_used").getAsInt(),
                r.get("n_pixels_total").getAsLong(),
                List.copyOf(histogram));
        logger.info(
                "Run-wide {} threshold {} from {} sample tile(s) over {} image(s), {} px",
                res.method(),
                String.format(java.util.Locale.ROOT, "%.5f", res.thresholdNorm()),
                res.regions(),
                res.images(),
                res.pixels());
        return res;
    }

    /**
     * The segmentation settings the pooling needs. The density map and the
     * window classifier each carry their own settings type; both adapt to this.
     */
    public interface Settings {
        String thresholdMethod();

        String segChannel();

        String ridgeFilter();

        double sigmaMinUm();

        double sigmaMaxUm();

        double sigmaStepUm();

        boolean invertIntensity();

        double rollingBallRadiusUm();
    }

    /** @return true when the method is resolved per region and must be pooled instead */
    public static boolean needsPooling(String thresholdMethod) {
        String m = thresholdMethod == null ? "" : thresholdMethod.trim().toLowerCase(java.util.Locale.ROOT);
        return "otsu".equals(m) || "triangle".equals(m);
    }

    private static String method(String thresholdMethod) {
        return thresholdMethod.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @param r a pooled result
     * @return how it was obtained, for provenance records
     */
    public static String describe(Result r) {
        return String.format(
                java.util.Locale.ROOT,
                "%s pooled over %d image(s), %d sample tile(s) of up to %d px (%d px)",
                "triangle".equals(r.method()) ? "Triangle" : "Otsu",
                r.images(),
                r.regions(),
                SAMPLE_PX,
                r.pixels());
    }
}
