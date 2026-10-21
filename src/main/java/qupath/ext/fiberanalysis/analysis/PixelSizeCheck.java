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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pixel sizes of the images a multi-image run would analyse. Fiber measurements depend on
 * resolution (fine fibers merge at coarse pixel sizes; GLCM offsets and box sizes round to whole
 * pixels), so images analysed at different pixel sizes do not give comparable numbers.
 */
public final class PixelSizeCheck {

    /**
     * @param name    image name
     * @param pixelUm its own pixel size, NaN when it has none
     */
    public record Image(String name, double pixelUm) {}

    private final List<Image> images;
    private final double downsample;

    private PixelSizeCheck(List<Image> images, double downsample) {
        this.images = List.copyOf(images);
        this.downsample = Math.max(1.0, downsample);
    }

    /**
     * @param images     the images
     * @param downsample the run's analysis downsample (1 when it has none)
     * @return the check
     */
    public static PixelSizeCheck of(List<Image> images, double downsample) {
        return new PixelSizeCheck(images, downsample);
    }

    /** @return the images with a pixel size */
    private List<Image> calibrated() {
        return images.stream().filter(i -> i.pixelUm() > 0).toList();
    }

    /** @return names of the images with no pixel size */
    public List<String> uncalibrated() {
        return images.stream().filter(i -> !(i.pixelUm() > 0)).map(Image::name).toList();
    }

    /** @return true when the calibrated images would be analysed at pixel sizes more than 2% apart */
    public boolean mixed() {
        var c = calibrated();
        if (c.size() < 2) return false;
        double lo = c.stream().mapToDouble(Image::pixelUm).min().orElse(0);
        double hi = c.stream().mapToDouble(Image::pixelUm).max().orElse(0);
        return !ThresholdIdentity.samePixelSize(lo, hi);
    }

    /** @return the coarsest analysed pixel size, which every image can be brought to */
    public double commonPixelUm() {
        return calibrated().stream().mapToDouble(Image::pixelUm).max().orElse(Double.NaN) * downsample;
    }

    /**
     * @param pixelUm an image's own pixel size
     * @return the downsample that analyses it at {@link #commonPixelUm()}
     */
    public double downsampleFor(double pixelUm) {
        if (!(pixelUm > 0)) return downsample;
        return Math.max(downsample, commonPixelUm() / pixelUm);
    }

    /** @return each analysed pixel size (images within 2% grouped) with its images, finest first */
    public Map<Double, List<String>> groups() {
        TreeMap<Double, List<String>> sorted = new TreeMap<>();
        for (Image i : calibrated())
            sorted.computeIfAbsent(i.pixelUm() * downsample, k -> new ArrayList<>())
                    .add(i.name());
        Map<Double, List<String>> out = new LinkedHashMap<>();
        Double first = null;
        for (var e : sorted.entrySet()) {
            if (first == null || !ThresholdIdentity.samePixelSize(first, e.getKey())) {
                first = e.getKey();
                out.put(first, new ArrayList<>());
            }
            out.get(first).addAll(e.getValue());
        }
        return out;
    }

    /** @return the groups as lines, e.g. {@code 0.1732 um/px: 3 image(s) (a, b, c)} */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (var g : groups().entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.format(
                    Locale.ROOT,
                    "  %s um/px: %d image(s) (%s)",
                    um(g.getKey()),
                    g.getValue().size(),
                    names(g.getValue())));
        }
        if (!uncalibrated().isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("  no pixel size: ").append(names(uncalibrated()));
        }
        return sb.toString();
    }

    /**
     * Whether a micron list (GLCM distances, box sizes) becomes the same whole-pixel offsets on every
     * image.
     *
     * @param umList  comma-separated microns
     * @param pixelUm the analysed pixel sizes
     * @param setting the setting's name, for the message
     * @return null when every image gets the same offsets, else what they become at each size
     */
    public static String offsetProblem(String umList, Collection<Double> pixelUm, String setting) {
        Map<String, List<Double>> byOffsets = new LinkedHashMap<>();
        for (double px : new java.util.TreeSet<>(pixelUm)) {
            if (!(px > 0)) continue;
            byOffsets
                    .computeIfAbsent(FiberAnalysisWorkflow.umListToPxCsv(umList, px), k -> new ArrayList<>())
                    .add(px);
        }
        if (byOffsets.size() <= 1) return null;
        List<String> parts = new ArrayList<>();
        for (var e : byOffsets.entrySet()) {
            parts.add(String.format(
                    Locale.ROOT,
                    "%s px at %s um/px",
                    e.getKey(),
                    String.join(
                            ", ", e.getValue().stream().map(PixelSizeCheck::um).toList())));
        }
        return setting + " " + umList.replace(" ", "") + " um become " + String.join("; ", parts) + ".";
    }

    /** @return the analysed pixel size of each calibrated image, at the run's downsample or the common size */
    public List<Double> analysedPixelSizes(boolean common) {
        return calibrated().stream()
                .map(i -> common ? commonPixelUm() : i.pixelUm() * downsample)
                .toList();
    }

    /**
     * @param um a size in microns
     * @return it with four significant digits and no trailing zeros, e.g. {@code 0.5}, {@code 0.1732}
     */
    public static String um(double um) {
        String t = String.format(Locale.ROOT, "%.4g", um);
        if (t.contains(".") && !t.contains("e")) t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
        return t;
    }

    private static String names(List<String> names) {
        if (names.size() <= 3) return String.join(", ", names);
        return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }
}
