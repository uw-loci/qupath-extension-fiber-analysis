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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which threshold a set of results was cut at, and at what pixel size, as one line every output
 * carries: the map file, the run record, project entry metadata and each detection. Two results
 * are comparable when the cut and the segmentation settings match and they were analysed at the
 * same pixel size (within 2%), however the cut was obtained.
 *
 * <p>The line reads {@code <cut> | <source> | settings <fingerprint> at <pixel size> um/px}, for example
 * {@code Otsu 0.08337 | pooled over 2 annotation(s) on 1 image(s), run 20261021_101500 | settings 1a2b3c4d
 * at 0.1732 um/px}.
 *
 * @param method   Manual, Otsu, Triangle or Calibrated
 * @param unit     "gray level" for a Manual cut without a ridge filter, "response" with a ridge filter,
 *                 otherwise "" (a cut on [0, 1])
 * @param value    the cut in that unit
 * @param source   how it was obtained
 * @param settings fingerprint of the segmentation settings the cut applies to
 * @param pixelUm  the pixel size the image was analysed at (its own times the downsample), NaN if unknown
 */
public record ThresholdIdentity(
        String method, String unit, double value, String source, String settings, double pixelUm) {

    /** Project entry metadata key for the threshold of the latest annotation run on an image. */
    public static final String META_ANNOTATIONS = "Fiber threshold (annotations)";

    /** Project entry metadata key for the threshold of an image's density map. */
    public static final String META_DENSITY = "Fiber threshold (density map)";

    /** Detection metadata key. */
    public static final String META_OBJECT = "fiber_analysis.threshold";

    /** Map-file description line prefix. */
    static final String DESCRIPTION_PREFIX = "Threshold:";

    /** Pixel sizes within this ratio count as the same. */
    public static final double PIXEL_TOLERANCE = 1.02;

    private static final String SEP = " | ";

    private static final java.util.regex.Pattern SETTINGS =
            java.util.regex.Pattern.compile("settings (\\w+)(?: at ([0-9.eE+-]+) um/px)?");

    /**
     * @param thresholdMethod      the method as the dialog or a spec names it
     *                             (Otsu, Triangle, Manual, Project Otsu (calibrated), project_otsu)
     * @param manualGray           Manual cut in gray levels
     * @param manualResponse       Manual cut on the ridge response
     * @param resolvedNorm         the cut a pooled or calibrated method resolved to, or null
     * @param source               how {@code resolvedNorm} was obtained; null for Manual
     * @param channel              source channel
     * @param ridgeFilter          ridge filter, or None
     * @param sigmaMinUm           ridge sigma min
     * @param sigmaMaxUm           ridge sigma max
     * @param sigmaStepUm          ridge sigma step
     * @param invert               intensity inverted
     * @param rollingBallUm        rolling-ball radius, 0 for none
     * @param minFiberAreaUm2      smallest fiber kept
     * @param pixelUm              analysed pixel size, NaN if unknown
     * @return the identity
     */
    public static ThresholdIdentity of(
            String thresholdMethod,
            int manualGray,
            double manualResponse,
            Double resolvedNorm,
            String source,
            String channel,
            String ridgeFilter,
            double sigmaMinUm,
            double sigmaMaxUm,
            double sigmaStepUm,
            boolean invert,
            double rollingBallUm,
            double minFiberAreaUm2,
            double pixelUm) {
        boolean ridge = ridgeOn(ridgeFilter);
        String m = thresholdMethod == null ? "" : thresholdMethod.trim().toLowerCase(Locale.ROOT);
        String settings = fingerprint(
                channel, ridgeFilter, sigmaMinUm, sigmaMaxUm, sigmaStepUm, invert, rollingBallUm, minFiberAreaUm2);
        String unit = ridge ? "response" : "";
        if ("manual".equals(m)) {
            return ridge
                    ? new ThresholdIdentity("Manual", "response", manualResponse, "set by hand", settings, pixelUm)
                    : new ThresholdIdentity("Manual", "gray level", manualGray, "set by hand", settings, pixelUm);
        }
        String method = m.startsWith("project") || m.contains("calibrated")
                ? "Calibrated"
                : "triangle".equals(m) ? "Triangle" : "Otsu";
        if (resolvedNorm == null) {
            return new ThresholdIdentity(method, unit, Double.NaN, "fitted per region", settings, pixelUm);
        }
        return new ThresholdIdentity(method, unit, resolvedNorm, source == null ? "" : source, settings, pixelUm);
    }

    /**
     * @param p            an annotation run's parameters
     * @param resolvedNorm the cut a pooled or calibrated method resolved to, or null for Manual
     * @param source       how it was obtained
     * @param pixelUm      the image's analysed pixel size (its own times the analysis downsample)
     * @return the run's identity
     */
    public static ThresholdIdentity of(FiberAnalysisParams p, Double resolvedNorm, String source, double pixelUm) {
        return of(
                p.thresholdMethod(),
                p.manualThreshold(),
                p.manualResponseThreshold(),
                resolvedNorm,
                source,
                p.internalChannel(),
                p.ridgeFilter(),
                p.sigmaMinUm(),
                p.sigmaMaxUm(),
                p.sigmaStepUm(),
                p.invertIntensity(),
                p.rollingBallRadiusUm(),
                p.minFiberAreaUm2(),
                pixelUm);
    }

    /**
     * @param s       a density-map run's spec, after any pooling
     * @param pixelUm the image's pixel size (maps are read at full resolution), NaN if not for one image
     * @return its identity
     */
    public static ThresholdIdentity of(FiberDensityMapWorkflow.DensityMapJobSpec s, double pixelUm) {
        return of(
                s.fittedMethod,
                s.manualThreshold,
                s.manualResponseThreshold,
                s.projectThresholdNorm,
                s.thresholdSource,
                s.segChannel,
                s.ridgeFilter,
                s.sigmaMinUm,
                s.sigmaMaxUm,
                s.sigmaStepUm,
                s.invertIntensity,
                s.rollingBallRadiusUm,
                s.minFiberAreaUm2,
                pixelUm);
    }

    /** @return the cut as it reads, e.g. {@code Otsu response 0.0360} */
    public String cut() {
        String u = unit == null || unit.isEmpty() ? "" : unit + " ";
        return method + " " + u + valueText();
    }

    /** The value as the label prints it; the key uses the same text, so a parsed label compares equal. */
    private String valueText() {
        if ("gray level".equals(unit)) return Long.toString(Math.round(value));
        return Double.isNaN(value) ? "?" : String.format(Locale.ROOT, "%.4g", value);
    }

    /** @return the pixel size as the label prints it, or "" when unknown */
    private String pixelText() {
        return Double.isFinite(pixelUm) && pixelUm > 0 ? PixelSizeCheck.um(pixelUm) : "";
    }

    /** @return the one-line form every output records */
    public String label() {
        String px = pixelText();
        return cut() + SEP + source + SEP + "settings " + settings + (px.isEmpty() ? "" : " at " + px + " um/px");
    }

    /** @return the cut and the settings; the pixel size is compared separately, with a tolerance */
    public String key() {
        return unit + ":" + valueText() + ":" + settings;
    }

    /**
     * @param other another identity
     * @return true when the two results can be compared: same cut, same settings, pixel sizes within
     *     {@link #PIXEL_TOLERANCE} (or either unknown)
     */
    public boolean comparableWith(ThresholdIdentity other) {
        return other != null && key().equals(other.key()) && samePixelSize(pixelUm, other.pixelUm);
    }

    /** @return true when two pixel sizes are within {@link #PIXEL_TOLERANCE}, or either is unknown */
    public static boolean samePixelSize(double a, double b) {
        if (!(a > 0) || !(b > 0)) return true;
        return Math.max(a, b) / Math.min(a, b) <= PIXEL_TOLERANCE;
    }

    @Override
    public String toString() {
        return label();
    }

    /**
     * @param label a line written by {@link #label()}
     * @return the identity, or null when the line is not one
     */
    public static ThresholdIdentity parse(String label) {
        if (label == null) return null;
        String[] parts = label.trim().split(java.util.regex.Pattern.quote(SEP), -1);
        if (parts.length != 3) return null;
        java.util.regex.Matcher sm = SETTINGS.matcher(parts[2].trim());
        if (!sm.matches()) return null;
        String[] cut = parts[0].trim().split(" ");
        if (cut.length < 2) return null;
        String method = cut[0];
        String unit = String.join(" ", java.util.Arrays.copyOfRange(cut, 1, cut.length - 1));
        double value;
        double px;
        try {
            value = "?".equals(cut[cut.length - 1]) ? Double.NaN : Double.parseDouble(cut[cut.length - 1]);
            px = sm.group(2) == null ? Double.NaN : Double.parseDouble(sm.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        return new ThresholdIdentity(method, unit, value, parts[1], sm.group(1), px);
    }

    /**
     * Groups entries whose results can be compared with each other.
     *
     * @param labels entry name to recorded label, entries without one left out
     * @return one label per group (its first member's) to the entries in it, largest group first
     */
    public static Map<String, List<String>> groups(Map<String, String> labels) {
        List<Map.Entry<String, ThresholdIdentity>> parsed = new ArrayList<>();
        Map<String, List<String>> unparsed = new LinkedHashMap<>();
        for (var e : labels.entrySet()) {
            ThresholdIdentity t = parse(e.getValue());
            if (t == null)
                unparsed.computeIfAbsent(e.getValue(), x -> new ArrayList<>()).add(e.getKey());
            else parsed.add(Map.entry(e.getKey(), t));
        }
        parsed.sort((a, b) -> {
            int c = a.getValue().key().compareTo(b.getValue().key());
            return c != 0 ? c : Double.compare(px(a.getValue()), px(b.getValue()));
        });
        List<Map.Entry<ThresholdIdentity, List<String>>> found = new ArrayList<>();
        for (var e : parsed) {
            Map.Entry<ThresholdIdentity, List<String>> last = found.isEmpty() ? null : found.get(found.size() - 1);
            if (last != null && last.getKey().comparableWith(e.getValue()))
                last.getValue().add(e.getKey());
            else found.add(Map.entry(e.getValue(), new ArrayList<>(List.of(e.getKey()))));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        found.stream()
                .sorted((a, b) ->
                        Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(g -> out.put(g.getKey().label(), g.getValue()));
        out.putAll(unparsed);
        return out;
    }

    private static double px(ThresholdIdentity t) {
        return t.pixelUm > 0 ? t.pixelUm : 0;
    }

    /**
     * Says when the results recorded across a project are not all comparable.
     *
     * @param labels  every image entry's recorded label, by entry name
     * @param thisRun the entries the run that just finished covered
     * @param full    true to say how each group's cut was obtained (for the log); false for a short
     *                form that fits a notification
     * @return a warning naming each group and its images, or null when they all compare
     */
    public static String incomparableWarning(
            Map<String, String> labels, java.util.Collection<String> thisRun, boolean full) {
        Map<String, List<String>> groups = groups(labels);
        if (groups.size() <= 1 || thisRun.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("Results in this project were cut at ")
                .append(groups.size())
                .append(" different thresholds or pixel sizes, so they are not all comparable:");
        int shown = 0;
        for (var g : groups.entrySet()) {
            if (shown++ == 4) {
                sb.append("\n  ...");
                break;
            }
            ThresholdIdentity t = parse(g.getKey());
            String what = t == null
                    ? g.getKey()
                    : t.cut() + (t.pixelText().isEmpty() ? "" : " at " + t.pixelText() + " um/px") + ", settings "
                            + t.settings() + (full ? " (" + t.source() + ")" : "");
            boolean mine = g.getValue().stream().anyMatch(thisRun::contains);
            sb.append("\n  ")
                    .append(what)
                    .append(mine ? " [this run]" : "")
                    .append(": ")
                    .append(nameList(g.getValue(), full ? 3 : 2));
        }
        if (!full) {
            sb.append("\nRun them together, or use one project calibration. Details are in the log.");
            return sb.toString();
        }
        sb.append("\nTo compare them, run them together (a batch can analyse images of different pixel sizes")
                .append(" at one common size), or use one project calibration.");
        return sb.toString();
    }

    private static String nameList(List<String> names, int max) {
        if (names.size() <= max) return String.join(", ", names);
        return String.join(", ", names.subList(0, max)) + " and " + (names.size() - max) + " more";
    }

    private static boolean ridgeOn(String ridgeFilter) {
        return ridgeFilter != null && !ridgeFilter.isBlank() && !"none".equalsIgnoreCase(ridgeFilter.trim());
    }

    /** @return eight hex digits over the settings that decide what a cut means */
    static String fingerprint(
            String channel,
            String ridgeFilter,
            double sigmaMinUm,
            double sigmaMaxUm,
            double sigmaStepUm,
            boolean invert,
            double rollingBallUm,
            double minFiberAreaUm2) {
        boolean ridge = ridgeOn(ridgeFilter);
        String canon = String.format(
                Locale.ROOT,
                "channel=%s;ridge=%s;sigmas=%s;invert=%b;rb=%.6g;minarea=%.6g",
                channel == null ? "" : channel.trim(),
                ridge ? ridgeFilter.trim().toLowerCase(Locale.ROOT) : "none",
                ridge ? String.format(Locale.ROOT, "%.6g,%.6g,%.6g", sigmaMinUm, sigmaMaxUm, sigmaStepUm) : "",
                invert,
                rollingBallUm,
                minFiberAreaUm2);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canon.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
