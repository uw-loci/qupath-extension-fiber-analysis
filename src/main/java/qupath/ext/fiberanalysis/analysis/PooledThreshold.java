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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

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
     */
    public record Result(double thresholdNorm, String method, int images, int regions, long pixels) {}

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
                pixelSizes.add(cal.getAveragedPixelSizeMicrons());
                int k = 0;
                for (int[] b : sampleBoxes(server.getWidth(), server.getHeight(), GRID, SAMPLE_PX)) {
                    Path png = workDir.resolve(String.format("pool_%03d_%02d.png", images, k++));
                    RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, b[0], b[1], b[2], b[3]);
                    SourceChannel.writeRegionPng(server, req, spec.segChannel(), png);
                    pngs.add(png.toString());
                }
                images++;
            } catch (Exception e) {
                if (e instanceof IOException io) throw io;
                throw new IOException("Could not sample " + entry.getImageName() + ": " + e.getMessage(), e);
            }
        }
        if (pngs.isEmpty()) throw new IOException("No calibrated image could be sampled for a run-wide threshold");

        double[] px =
                pixelSizes.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double pxUm = px[px.length / 2];
        Map<String, Object> in = new HashMap<>();
        in.put("region_paths", pngs);
        in.put("mask_paths", new ArrayList<String>());
        in.put("seg_channel", spec.segChannel());
        in.put("ridge_filter", spec.ridgeFilter().toLowerCase(java.util.Locale.ROOT));
        in.put("sigma_min", spec.sigmaMinUm() / pxUm);
        in.put("sigma_max", spec.sigmaMaxUm() / pxUm);
        in.put("sigma_step", spec.sigmaStepUm() / pxUm);
        in.put("invert_intensity", spec.invertIntensity());
        in.put("rolling_ball_radius", (int) Math.max(0, Math.round(spec.rollingBallRadiusUm() / pxUm)));
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
        Result res = new Result(
                r.get("threshold_normalised").getAsDouble(),
                method(spec.thresholdMethod()),
                images,
                r.get("n_regions_used").getAsInt(),
                r.get("n_pixels_total").getAsLong());
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
