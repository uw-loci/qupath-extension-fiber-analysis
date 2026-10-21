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
 * Which threshold a set of results was cut at, as one line every output carries: the map file,
 * the run record, project entry metadata and each detection. Two results with the same
 * {@link #key()} were segmented the same way; two with different keys are not comparable.
 *
 * <p>The line reads {@code <cut> | <source> | settings <fingerprint>}, for example
 * {@code Otsu 0.08337 | pooled over 2 annotation(s) on 1 image(s), run 20261021_101500 | settings 1a2b3c4d}.
 *
 * @param method   Manual, Otsu, Triangle or Calibrated
 * @param unit     "gray level" for a Manual cut without a ridge filter, "response" with a ridge filter,
 *                 otherwise "" (a cut on [0, 1])
 * @param value    the cut in that unit
 * @param source   how it was obtained
 * @param settings fingerprint of the segmentation settings the cut applies to
 */
public record ThresholdIdentity(String method, String unit, double value, String source, String settings) {

    /** Project entry metadata key for the threshold of the latest annotation run on an image. */
    public static final String META_ANNOTATIONS = "Fiber threshold (annotations)";

    /** Project entry metadata key for the threshold of an image's density map. */
    public static final String META_DENSITY = "Fiber threshold (density map)";

    /** Detection metadata key. */
    public static final String META_OBJECT = "fiber_analysis.threshold";

    /** Map-file description line prefix. */
    static final String DESCRIPTION_PREFIX = "Threshold:";

    private static final String SEP = " | ";

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
     * @param downsample           analysis downsample
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
            double downsample) {
        boolean ridge = ridgeOn(ridgeFilter);
        String m = thresholdMethod == null ? "" : thresholdMethod.trim().toLowerCase(Locale.ROOT);
        String settings = fingerprint(
                channel,
                ridgeFilter,
                sigmaMinUm,
                sigmaMaxUm,
                sigmaStepUm,
                invert,
                rollingBallUm,
                minFiberAreaUm2,
                downsample);
        String unit = ridge ? "response" : "";
        if ("manual".equals(m)) {
            return ridge
                    ? new ThresholdIdentity("Manual", "response", manualResponse, "set by hand", settings)
                    : new ThresholdIdentity("Manual", "gray level", manualGray, "set by hand", settings);
        }
        String method = m.startsWith("project") || m.contains("calibrated")
                ? "Calibrated"
                : "triangle".equals(m) ? "Triangle" : "Otsu";
        if (resolvedNorm == null) {
            return new ThresholdIdentity(method, unit, Double.NaN, "fitted per region", settings);
        }
        return new ThresholdIdentity(method, unit, resolvedNorm, source == null ? "" : source, settings);
    }

    /**
     * @param p            an annotation run's parameters
     * @param resolvedNorm the cut a pooled or calibrated method resolved to, or null for Manual
     * @param source       how it was obtained
     * @return the run's identity
     */
    public static ThresholdIdentity of(FiberAnalysisParams p, Double resolvedNorm, String source) {
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
                Math.max(1.0, p.analysisDownsample()));
    }

    /**
     * @param s a density-map run's spec, after any pooling
     * @return its identity
     */
    public static ThresholdIdentity of(FiberDensityMapWorkflow.DensityMapJobSpec s) {
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
                1.0);
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

    /** @return the one-line form every output records */
    public String label() {
        return cut() + SEP + source + SEP + "settings " + settings;
    }

    /** @return what two comparable results share: the cut and the settings, not how it was obtained */
    public String key() {
        return unit + ":" + valueText() + ":" + settings;
    }

    @Override
    public String toString() {
        return label();
    }

    /**
     * @param label a line written by {@link #label()}
     * @return its comparison key, or null when the line is not one
     */
    public static String keyOf(String label) {
        ThresholdIdentity t = parse(label);
        return t == null ? null : t.key();
    }

    /**
     * @param label a line written by {@link #label()}
     * @return the identity, or null when the line is not one
     */
    public static ThresholdIdentity parse(String label) {
        if (label == null) return null;
        String[] parts = label.trim().split(java.util.regex.Pattern.quote(SEP), -1);
        if (parts.length != 3 || !parts[2].startsWith("settings ")) return null;
        String[] cut = parts[0].trim().split(" ");
        if (cut.length < 2) return null;
        String method = cut[0];
        String unit = String.join(" ", java.util.Arrays.copyOfRange(cut, 1, cut.length - 1));
        double value;
        try {
            value = "?".equals(cut[cut.length - 1]) ? Double.NaN : Double.parseDouble(cut[cut.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new ThresholdIdentity(method, unit, value, parts[1], parts[2].substring("settings ".length()));
    }

    /**
     * Groups entries by the threshold recorded under a metadata key.
     *
     * @param labels entry name to recorded label, entries without one left out
     * @return label to the entries cut at it, largest group first
     */
    public static Map<String, List<String>> groups(Map<String, String> labels) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        Map<String, String> labelOfKey = new LinkedHashMap<>();
        for (var e : labels.entrySet()) {
            String k = keyOf(e.getValue());
            if (k == null) k = e.getValue();
            labelOfKey.putIfAbsent(k, e.getValue());
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(e.getKey());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byKey.entrySet().stream()
                .sorted((a, b) ->
                        Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(e -> out.put(labelOfKey.get(e.getKey()), e.getValue()));
        return out;
    }

    /**
     * Says when other images in a project were cut at a different threshold than this one.
     *
     * @param mine   this result's identity
     * @param others other entries' recorded labels under the same key, by entry name
     * @return a warning naming how many images differ and how, or null when all match
     */
    public static String mismatchWarning(ThresholdIdentity mine, Map<String, String> others) {
        Map<String, String> differ = new LinkedHashMap<>();
        for (var e : others.entrySet()) {
            String k = keyOf(e.getValue());
            if (!mine.key().equals(k)) differ.put(e.getKey(), e.getValue());
        }
        if (differ.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(differ.size())
                .append(" other image(s) in this project were cut at a different threshold, so their results are")
                .append(" not comparable with these:");
        int shown = 0;
        for (var g : groups(differ).entrySet()) {
            if (shown++ == 3) {
                sb.append("\n  ...");
                break;
            }
            ThresholdIdentity t = parse(g.getKey());
            String why = t == null
                    ? g.getKey()
                    : t.settings().equals(mine.settings())
                            ? t.cut() + " (" + t.source() + ")"
                            : t.cut() + " with other segmentation settings";
            sb.append("\n  ").append(why).append(": ").append(nameList(g.getValue(), 3));
        }
        sb.append("\nRun them together, or use one project calibration, to compare them.");
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
            double minFiberAreaUm2,
            double downsample) {
        boolean ridge = ridgeOn(ridgeFilter);
        String canon = String.format(
                Locale.ROOT,
                "channel=%s;ridge=%s;sigmas=%s;invert=%b;rb=%.6g;minarea=%.6g;ds=%.6g",
                channel == null ? "" : channel.trim(),
                ridge ? ridgeFilter.trim().toLowerCase(Locale.ROOT) : "none",
                ridge ? String.format(Locale.ROOT, "%.6g,%.6g,%.6g", sigmaMinUm, sigmaMaxUm, sigmaStepUm) : "",
                invert,
                rollingBallUm,
                minFiberAreaUm2,
                downsample);
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
