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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow;
import qupath.ext.fiberanalysis.analysis.PooledThreshold;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;

/**
 * The segmentation settings a window-feature run was computed with, in
 * physical units.
 *
 * <p>Carried separately from the model so it can be hashed: a model trained on
 * one segmentation does not describe features produced by another, and the
 * compatibility gate refuses the mismatch rather than quietly scoring it.
 *
 * <p><b>A per-tile threshold is a tiling artefact waiting to happen.</b> Otsu
 * and triangle recompute from each tile's own histogram, so two neighbouring
 * tiles over the same tissue can segment it differently and the model learns
 * the seam. {@link #hasGlobalThreshold()} says whether this spec avoids that;
 * the workflow warns when it does not.
 *
 * @param segChannel           which scalar the threshold is taken on
 * @param thresholdMethod      otsu / triangle / manual / project_otsu
 * @param manualThreshold      cut-off in the source image's own gray levels
 * @param manualResponseThreshold Manual cut on the ridge response (0-1), used instead with a ridge filter
 * @param projectThresholdNorm project-calibrated threshold in [0, 1], or null
 * @param ridgeFilter          none / frangi / sato / meijering
 * @param sigmaMinUm           smallest ridge scale
 * @param sigmaMaxUm           largest ridge scale
 * @param sigmaStepUm          ridge scale step
 * @param minFiberAreaUm2      objects smaller than this are dropped
 * @param invertIntensity      true for dark fibers on a light field
 * @param rollingBallRadiusUm  background-subtraction radius; 0 disables
 */
public record SegmentationSpec(
        String segChannel,
        String thresholdMethod,
        int manualThreshold,
        double manualResponseThreshold,
        Double projectThresholdNorm,
        String ridgeFilter,
        double sigmaMinUm,
        double sigmaMaxUm,
        double sigmaStepUm,
        double minFiberAreaUm2,
        boolean invertIntensity,
        double rollingBallRadiusUm)
        implements PooledThreshold.Settings {

    /**
     * @param source how a pooled or calibrated threshold was obtained
     * @return the threshold this spec cuts at, for map files and objects
     */
    public qupath.ext.fiberanalysis.analysis.ThresholdIdentity thresholdIdentity(String source) {
        return qupath.ext.fiberanalysis.analysis.ThresholdIdentity.of(
                thresholdMethod,
                manualThreshold,
                manualResponseThreshold,
                projectThresholdNorm,
                source,
                segChannel,
                ridgeFilter,
                sigmaMinUm,
                sigmaMaxUm,
                sigmaStepUm,
                invertIntensity,
                rollingBallRadiusUm,
                minFiberAreaUm2,
                1.0);
    }

    /**
     * Read the segmentation settings the user last ran with.
     *
     * <p>The classifier deliberately does not grow its own copy of the
     * segmentation controls. The per-annotation Run dialog is where they are
     * tuned and previewed, the preferences are where the result lives, and
     * the density-map dialog already works this way -- a second set of
     * controls would be a second source of truth, and the one the user did
     * not look at would be the one that ran.
     *
     * @return the current settings, with a project-calibrated threshold
     *         resolved to a number when that method is selected
     */
    public static SegmentationSpec fromPreferences() {
        Double projectThresholdNorm = null;
        String method = FiberAnalysisPreferences.thresholdMethodProperty().get();
        if ("Project Otsu (calibrated)".equals(method)) {
            String calName =
                    FiberAnalysisPreferences.projectCalibrationNameProperty().get();
            if (calName != null && !calName.isBlank()) {
                projectThresholdNorm = FiberAnalysisWorkflow.loadCalibratedThreshold(calName);
            }
            method = "project_otsu";
        } else {
            method = method == null ? "otsu" : method.toLowerCase(Locale.ROOT);
        }
        return new SegmentationSpec(
                FiberAnalysisPreferences.internalChannelProperty().get(),
                method,
                FiberAnalysisPreferences.manualThresholdProperty().get(),
                FiberAnalysisPreferences.manualResponseThresholdProperty().get(),
                projectThresholdNorm,
                FiberAnalysisPreferences.ridgeFilterProperty().get(),
                FiberAnalysisPreferences.sigmaMinProperty().get(),
                FiberAnalysisPreferences.sigmaMaxProperty().get(),
                FiberAnalysisPreferences.sigmaStepProperty().get(),
                FiberAnalysisPreferences.minFiberAreaUm2Property().get(),
                FiberAnalysisPreferences.invertIntensityProperty().get(),
                FiberAnalysisPreferences.rollingBallRadiusUmProperty().get());
    }

    /**
     * @return a short description of the threshold rule, for the dialog's
     *         warning when it is recomputed per tile
     */
    public String thresholdDescription() {
        if (projectThresholdNorm != null) {
            return String.format(Locale.ROOT, "project-calibrated (%.4f)", projectThresholdNorm);
        }
        if ("manual".equalsIgnoreCase(thresholdMethod)) {
            return ridgeFilterOn()
                    ? String.format(Locale.ROOT, "manual (ridge response %.3g)", manualResponseThreshold)
                    : "manual (" + manualThreshold + ")";
        }
        if (PooledThreshold.needsPooling(thresholdMethod)) {
            return thresholdMethod + ", pooled once over the training images";
        }
        return thresholdMethod;
    }

    /**
     * @return true when every tile will threshold at the same level -- a
     *         manual cut-off, or a project-calibrated one
     */
    public boolean hasGlobalThreshold() {
        String m = thresholdMethod == null ? "" : thresholdMethod.toLowerCase(Locale.ROOT);
        return "manual".equals(m) || projectThresholdNorm != null;
    }

    /**
     * @param thresholdNorm a run-wide cut on [0, 1]
     * @return these settings with every tile cutting at that value
     */
    public SegmentationSpec withPooledThreshold(double thresholdNorm) {
        return new SegmentationSpec(
                segChannel,
                "project_otsu",
                manualThreshold,
                manualResponseThreshold,
                thresholdNorm,
                ridgeFilter,
                sigmaMinUm,
                sigmaMaxUm,
                sigmaStepUm,
                minFiberAreaUm2,
                invertIntensity,
                rollingBallRadiusUm);
    }

    /** @return every setting by name, for the model's metadata */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seg_channel", segChannel);
        m.put("threshold_method", thresholdMethod);
        m.put("manual_threshold", manualThreshold);
        m.put("manual_response_threshold", manualResponseThreshold);
        m.put("project_threshold_norm", projectThresholdNorm);
        m.put("ridge_filter", ridgeFilter);
        m.put("sigma_min_um", sigmaMinUm);
        m.put("sigma_max_um", sigmaMaxUm);
        m.put("sigma_step_um", sigmaStepUm);
        m.put("min_fiber_area_um2", minFiberAreaUm2);
        m.put("invert_intensity", invertIntensity);
        m.put("rolling_ball_radius_um", rollingBallRadiusUm);
        // Ridge responses are on a fixed scale (1.0 = a full-contrast fiber).
        m.put("response_scale", "absolute");
        return m;
    }

    /**
     * @param m a map written by {@link #toMap()}, as read back from JSON
     * @return the settings
     * @throws IllegalArgumentException if a setting is missing
     */
    public static SegmentationSpec fromMap(Map<?, ?> m) {
        Object norm = m.get("project_threshold_norm");
        return new SegmentationSpec(
                str(m, "seg_channel"),
                str(m, "threshold_method"),
                (int) num(m, "manual_threshold"),
                m.get("manual_response_threshold") instanceof Number r
                        ? r.doubleValue()
                        : FiberAnalysisPreferences.DEFAULT_MANUAL_RESPONSE_THRESHOLD,
                norm instanceof Number n ? n.doubleValue() : null,
                str(m, "ridge_filter"),
                num(m, "sigma_min_um"),
                num(m, "sigma_max_um"),
                num(m, "sigma_step_um"),
                num(m, "min_fiber_area_um2"),
                Boolean.parseBoolean(String.valueOf(m.get("invert_intensity"))),
                num(m, "rolling_ball_radius_um"));
    }

    /**
     * @param m a stored segmentation map
     * @return why a model with these settings cannot be applied any more, or null
     */
    public static String staleScaleProblem(Map<?, ?> m) {
        Object rf = m.get("ridge_filter");
        boolean ridge = rf != null && !"none".equalsIgnoreCase(String.valueOf(rf));
        if (ridge && !m.containsKey("response_scale")) {
            return "Trained with a ridge filter before ridge responses had a fixed scale; its threshold does"
                    + " not apply to today's segmentation. Train the model again.";
        }
        return null;
    }

    /** @return true when a ridge filter is on, so thresholds apply to its response */
    public boolean ridgeFilterOn() {
        return ridgeFilter != null && !"none".equalsIgnoreCase(ridgeFilter);
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("stored segmentation is missing '" + k + "'");
        return String.valueOf(v);
    }

    private static double num(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("stored segmentation is missing '" + k + "'");
        }
        return n.doubleValue();
    }

    /**
     * Appose inputs for one tile. Scale-dependent settings are converted to
     * pixels here, so the caller never has to remember which of them are in
     * microns.
     *
     * @param pixelSizeUm microns per pixel of the read
     * @return a mutable input map, ready for the per-tile keys
     */
    public Map<String, Object> toInputs(double pixelSizeUm) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("seg_channel", segChannel);
        in.put("threshold_method", thresholdMethod);
        in.put("manual_threshold", manualThreshold);
        in.put("manual_response_threshold", manualResponseThreshold);
        in.put("ridge_filter", ridgeFilter);
        in.put("sigma_min", sigmaMinUm / pixelSizeUm);
        in.put("sigma_max", sigmaMaxUm / pixelSizeUm);
        in.put("sigma_step", sigmaStepUm / pixelSizeUm);
        in.put("min_fiber_area_px", (int) Math.max(0, Math.round(minFiberAreaUm2 / (pixelSizeUm * pixelSizeUm))));
        in.put("invert_intensity", invertIntensity);
        in.put("rolling_ball_radius", (int) Math.max(0, Math.round(rollingBallRadiusUm / pixelSizeUm)));
        if (projectThresholdNorm != null) {
            in.put("project_threshold_norm", projectThresholdNorm);
        }
        return in;
    }

    /**
     * @return a short stable fingerprint of these settings, for the model's
     *         compatibility gate
     */
    public String hash() {
        String canonical = String.format(
                Locale.ROOT,
                "%s|%s|%d|%s|%s|%.6g|%.6g|%.6g|%.6g|%b|%.6g",
                segChannel,
                thresholdMethod,
                manualThreshold,
                projectThresholdNorm == null ? "none" : String.format(Locale.ROOT, "%.8g", projectThresholdNorm),
                ridgeFilter,
                sigmaMinUm,
                sigmaMaxUm,
                sigmaStepUm,
                minFiberAreaUm2,
                invertIntensity,
                rollingBallRadiusUm);
        if (ridgeFilterOn() && "manual".equalsIgnoreCase(thresholdMethod)) {
            canonical += String.format(Locale.ROOT, "|r%.6g", manualResponseThreshold);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception ex) {
            return String.format("%012x", canonical.hashCode() & 0xffffffffL);
        }
    }
}
