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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks a saved project calibration against the settings a run cuts with. A threshold is a cut
 * on one particular scalar image, fitted on the pixels of particular zones, so everything that
 * changes either must match.
 */
public final class CalibrationCheck {

    private CalibrationCheck() {}

    /**
     * What a run cuts on. A null zone mode and NaN numbers are not checked.
     *
     * @param channel            source channel
     * @param ridgeFilter        ridge filter ("None" for none)
     * @param invert             invert-intensity setting
     * @param rollingBallUm      rolling-ball radius, microns
     * @param sigmaMinUm         smallest ridge sigma, microns
     * @param sigmaMaxUm         largest ridge sigma, microns
     * @param sigmaStepUm        ridge sigma step, microns
     * @param zoneMode           "whole", "inside", "outside" or "both"; null = not checked
     * @param borderZoneUm       zone width, microns; NaN = not checked
     * @param analysisDownsample analysis downsample; NaN = not checked
     * @param analyzedPixelUm    pixel size as analyzed (own size times downsample); NaN = not checked
     */
    public record Target(
            String channel,
            String ridgeFilter,
            boolean invert,
            double rollingBallUm,
            double sigmaMinUm,
            double sigmaMaxUm,
            double sigmaStepUm,
            String zoneMode,
            double borderZoneUm,
            double analysisDownsample,
            double analyzedPixelUm) {

        /**
         * @param p               the run's parameters
         * @param analyzedPixelUm the pixel size the run analyzes at, or NaN when unknown
         * @return everything the run cuts on
         */
        public static Target of(FiberAnalysisParams p, double analyzedPixelUm) {
            return new Target(
                    p.internalChannel(),
                    p.ridgeFilter(),
                    p.invertIntensity(),
                    p.rollingBallRadiusUm(),
                    p.sigmaMinUm(),
                    p.sigmaMaxUm(),
                    p.sigmaStepUm(),
                    p.zoneMode(),
                    p.borderZoneUm(),
                    Math.max(1.0, p.analysisDownsample()),
                    analyzedPixelUm);
        }

        /** @return the scalar-image settings only, for a caller with no zones or fixed scale */
        public static Target segmentation(
                String channel,
                String ridgeFilter,
                boolean invert,
                double rollingBallUm,
                double sigmaMinUm,
                double sigmaMaxUm,
                double sigmaStepUm) {
            return new Target(
                    channel,
                    ridgeFilter,
                    invert,
                    rollingBallUm,
                    sigmaMinUm,
                    sigmaMaxUm,
                    sigmaStepUm,
                    null,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN);
        }
    }

    /**
     * Why a saved calibration does not apply to a run, or null when it does. A setting the
     * calibration does not record (an older calibration) is named as unknown, which also stops it
     * applying.
     *
     * @param calibrationName calibration to check
     * @param projDir         project directory
     * @param t               what the run cuts on
     * @return a sentence for the user, or null
     */
    public static String mismatch(String calibrationName, Path projDir, Target t) {
        if (calibrationName == null || calibrationName.isBlank() || projDir == null || t == null) return null;
        JsonObject cp;
        try {
            Path calFile = projDir.resolve("fiber-analysis").resolve("calibration_" + calibrationName + ".json");
            if (!Files.isRegularFile(calFile)) return null;
            JsonObject obj = new Gson().fromJson(Files.readString(calFile), JsonObject.class);
            if (obj == null || !obj.has("calibration_params")) return null;
            cp = obj.getAsJsonObject("calibration_params");
        } catch (Exception ex) {
            return null;
        }
        List<String> diffs = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        String ridgeFilter = t.ridgeFilter();
        String calRidge = cp.has("ridge_filter") ? cp.get("ridge_filter").getAsString() : "None";
        boolean ridgeOn = ridgeFilter != null && !"none".equalsIgnoreCase(ridgeFilter);
        if (cp.has("seg_channel") && !cp.get("seg_channel").getAsString().equals(t.channel()))
            diffs.add("source channel " + cp.get("seg_channel").getAsString());
        if (!calRidge.equalsIgnoreCase(ridgeFilter == null ? "None" : ridgeFilter))
            diffs.add("ridge filter " + calRidge);
        if (cp.has("invert_intensity") && cp.get("invert_intensity").getAsBoolean() != t.invert())
            diffs.add(t.invert() ? "intensity not inverted" : "intensity inverted");
        if (cp.has("rolling_ball_radius_um") && differs(num(cp, "rolling_ball_radius_um"), t.rollingBallUm()))
            diffs.add("rolling ball " + num(cp, "rolling_ball_radius_um") + " um");
        if (ridgeOn && calRidge.equalsIgnoreCase(ridgeFilter)) {
            if (Double.isNaN(num(cp, "sigma_min_um"))
                    || Double.isNaN(num(cp, "sigma_max_um"))
                    || Double.isNaN(num(cp, "sigma_step_um"))) {
                unknown.add("ridge sigmas");
            } else if (differs(num(cp, "sigma_min_um"), t.sigmaMinUm())
                    || differs(num(cp, "sigma_max_um"), t.sigmaMaxUm())
                    || differs(num(cp, "sigma_step_um"), t.sigmaStepUm())) {
                diffs.add(String.format(
                        Locale.ROOT,
                        "sigmas %.2g-%.2g step %.2g um",
                        num(cp, "sigma_min_um"),
                        num(cp, "sigma_max_um"),
                        num(cp, "sigma_step_um")));
            }
            if (!cp.has("response_scale")) {
                return "Calibration '" + calibrationName + "' was made before ridge responses had a fixed scale,"
                        + " so its threshold does not apply. Calibrate again.";
            }
        }

        // Which pixels were pooled: the zone and its width.
        if (t.zoneMode() != null) {
            String calZone = str(cp, "zone_mode");
            if (calZone == null) unknown.add("zone mode");
            else if (!calZone.equalsIgnoreCase(t.zoneMode()))
                diffs.add("zone mode " + calZone + " (this run: " + t.zoneMode() + ")");
            boolean bothWhole = "whole".equalsIgnoreCase(t.zoneMode()) && "whole".equalsIgnoreCase(calZone);
            if (!bothWhole && !Double.isNaN(t.borderZoneUm())) {
                double calBorder = num(cp, "border_zone_um");
                if (Double.isNaN(calBorder)) unknown.add("border zone width");
                else if (differs(calBorder, t.borderZoneUm()))
                    diffs.add("border zone " + PixelSizeCheck.um(calBorder) + " um (this run: "
                            + PixelSizeCheck.um(t.borderZoneUm()) + " um)");
            }
        }

        // The scale the image was read at. A calibration at a common pixel size brought each image
        // there with its own downsample, so only the analyzed size is compared then.
        double common = num(cp, "common_pixel_um");
        double calDs = num(cp, "analysis_downsample");
        if (!Double.isNaN(t.analysisDownsample()) && Double.isNaN(common)) {
            if (Double.isNaN(calDs)) unknown.add("analysis downsample");
            else if (differs(Math.max(1.0, calDs), t.analysisDownsample()))
                diffs.add("analysis downsample " + PixelSizeCheck.um(calDs) + " (this run: "
                        + PixelSizeCheck.um(t.analysisDownsample()) + ")");
        }
        if (t.analyzedPixelUm() > 0) {
            if (!Double.isNaN(common)) {
                if (!ThresholdIdentity.samePixelSize(common, t.analyzedPixelUm()))
                    diffs.add("a common pixel size of " + PixelSizeCheck.um(common) + " um/px (this run: "
                            + PixelSizeCheck.um(t.analyzedPixelUm()) + " um/px)");
            } else {
                List<Double> own = nums(cp, "pixel_sizes_um");
                if (own.isEmpty() || Double.isNaN(calDs)) {
                    unknown.add("pixel size");
                } else {
                    double ds = Math.max(1.0, calDs);
                    boolean any =
                            own.stream().anyMatch(px -> ThresholdIdentity.samePixelSize(px * ds, t.analyzedPixelUm()));
                    if (!any) {
                        diffs.add("pixel size "
                                + String.join(
                                        ", ",
                                        own.stream()
                                                .map(px -> PixelSizeCheck.um(px * ds))
                                                .toList())
                                + " um/px (this run: " + PixelSizeCheck.um(t.analyzedPixelUm()) + " um/px)");
                    }
                }
            }
        }

        if (diffs.isEmpty() && unknown.isEmpty()) return null;
        StringBuilder sb =
                new StringBuilder("Calibration '").append(calibrationName).append("'");
        if (!diffs.isEmpty()) {
            sb.append(" was fitted with ")
                    .append(String.join(", ", diffs))
                    .append("; its threshold is a cut on a different image.");
        }
        if (!unknown.isEmpty()) {
            sb.append(diffs.isEmpty() ? " does not record its " : " It also does not record its ")
                    .append(String.join(", ", unknown))
                    .append(" (it was made by an older version), so it cannot be checked against this run.");
        }
        sb.append(" Calibrate again with these settings, or match them.");
        return sb.toString();
    }

    /** @return true when two known settings differ by more than rounding */
    private static boolean differs(double a, double b) {
        return Math.abs(a - b) > 1e-6 * Math.max(1.0, Math.abs(a));
    }

    private static double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static List<Double> nums(JsonObject o, String key) {
        List<Double> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement v : e.getAsJsonArray()) {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() > 0)
                out.add(v.getAsDouble());
        }
        return out;
    }
}
