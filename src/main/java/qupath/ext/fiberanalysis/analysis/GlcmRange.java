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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.apposed.appose.Service.Task;
import qupath.ext.fiberanalysis.service.ApposeFiberService;

/** How the GLCM gray range is chosen: by hand, or fitted once on a run's fiber pixels. */
public final class GlcmRange {

    public static final String BY_HAND = "Set by hand";
    public static final String PERCENTILE = "1st to 99th percentile of fibers";
    public static final String MEAN_SD = "Mean +/- 3 SD of fibers";
    public static final String MIN_MAX = "Minimum to maximum of fibers";

    /** Every choice, the default first. */
    public static final List<String> METHODS = List.of(BY_HAND, PERCENTILE, MEAN_SD, MIN_MAX);

    /** Shown in red wherever an automatic range is applied to several images. */
    public static final String MULTI_IMAGE_WARNING =
            "Automatic gray range on several images: the range is fitted to these images' own pixels, so texture"
                    + " results may be inconsistent between images and with any other run. Do not use it when the"
                    + " intensity distribution is expected to differ between images, for example on a negative"
                    + " control. For those, set one range by hand.";

    private GlcmRange() {}

    /**
     * @param method a value from {@link #METHODS}
     * @return true when the range is fitted rather than given
     */
    public static boolean isAuto(String method) {
        return method != null && !BY_HAND.equals(method);
    }

    /**
     * @param min    lowest gray level
     * @param max    highest gray level
     * @param source how it was fitted, for the run record
     */
    public record Fit(int min, int max, String source) {}

    /** @return the method's name in {@code fiberlib.texture.RANGE_METHODS} */
    static String pythonName(String method) {
        return switch (method) {
            case PERCENTILE -> "percentile_1_99";
            case MEAN_SD -> "mean_3sd";
            case MIN_MAX -> "min_max";
            default -> throw new IllegalArgumentException("Not an automatic GLCM range: " + method);
        };
    }

    /**
     * Fits one range on sample boxes of the run's analysis zones.
     *
     * @param p             the run's parameters
     * @param regions       the annotations' regions, from one or more images
     * @param thresholdNorm the run's cut on [0, 1], or null when the settings give it (Manual)
     * @param workDir       scratch directory
     * @param progress      status sink; may be null
     * @return the range
     * @throws IOException if nothing could be sampled or the fit failed
     */
    public static Fit fit(
            FiberAnalysisParams p,
            List<PooledThreshold.ZoneRegion> regions,
            Double thresholdNorm,
            Path workDir,
            Consumer<String> progress)
            throws IOException {
        if ("existing".equalsIgnoreCase(p.segSource())) {
            throw new IOException("An automatic gray range needs the extension's own segmentation."
                    + " With an existing mask, set the range by hand.");
        }
        PooledThreshold.Samples s = PooledThreshold.sampleZones(
                regions,
                p.zoneMode(),
                p.borderZoneUm(),
                p.internalChannel(),
                workDir,
                FiberAnalysisWorkflow.RUN_POOL_SAMPLES,
                progress);
        Map<String, Object> in = new HashMap<>();
        in.put("region_paths", new ArrayList<>(s.pngs()));
        in.put("mask_paths", new ArrayList<>(s.masks()));
        in.put("region_pixel_um", new ArrayList<>(s.pixelSizes()));
        in.put("method", pythonName(p.glcmRangeMethod()));
        in.put("seg_channel", p.internalChannel());
        in.put("threshold_method", thresholdNorm != null ? "project_otsu" : p.thresholdMethod());
        if (thresholdNorm != null) in.put("project_threshold_norm", thresholdNorm);
        in.put("manual_threshold", p.manualThreshold());
        in.put("manual_response_threshold", p.manualResponseThreshold());
        in.put("ridge_filter", p.ridgeFilter());
        in.put("sigma_min_um", p.sigmaMinUm());
        in.put("sigma_max_um", p.sigmaMaxUm());
        in.put("sigma_step_um", p.sigmaStepUm());
        in.put("min_fiber_area_um2", p.minFiberAreaUm2());
        in.put("invert_intensity", p.invertIntensity());
        in.put("rolling_ball_um", p.rollingBallRadiusUm());
        Task task;
        try {
            task = ApposeFiberService.getInstance().runTask("fit_glcm_range", in);
        } catch (Exception e) {
            throw new IOException("fit_glcm_range failed: " + e.getMessage(), e);
        }
        Object out = task.outputs.get("result_json");
        if (out == null) throw new IOException("fit_glcm_range returned no result");
        JsonObject r = JsonParser.parseString(out.toString()).getAsJsonObject();
        if (r.has("error"))
            throw new IOException(
                    "Could not fit a GLCM gray range: " + r.get("error").getAsString());
        return new Fit(
                r.get("gray_min").getAsInt(),
                r.get("gray_max").getAsInt(),
                String.format(
                        Locale.ROOT,
                        "GLCM gray range %d to %d: %s, from %d fiber pixels in %d sample(s) on %d image(s)",
                        r.get("gray_min").getAsInt(),
                        r.get("gray_max").getAsInt(),
                        p.glcmRangeMethod(),
                        r.get("n_fiber_pixels").getAsLong(),
                        r.get("n_regions_used").getAsInt(),
                        s.images()));
    }
}
