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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The image description {@link DensityTiffWriter} writes into a map file, and
 * the parser for it. Kept apart from {@link DensitySidecar} so it carries no
 * Bio-Formats dependency and can be tested on its own.
 */
final class SidecarDescription {

    static final String PLACEMENT = "Placement:";
    static final String SOURCE_IMAGE = "Source image:";
    static final String THRESHOLD = ThresholdIdentity.DESCRIPTION_PREFIX;
    static final String REGION = "Region:";

    private static final Pattern CHANNEL_ROW =
            Pattern.compile("\\[(\\d+)]\\s*name=([^;\\r\\n]+)(?:;\\s*unit=([^\\r\\n;]+))?(?:;\\s*axial=([0-9.]+))?");
    private static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=([^;\\s]+)");

    private SidecarDescription() {}

    /**
     * @param info     placement, render mode, source image, threshold and region (its channel list is ignored)
     * @param channels the channels, in file order
     * @return the description text
     */
    static String format(DensityMapInfo info, List<DensityChannelSpec> channels) {
        DensityLattice lat = info.lattice();
        StringBuilder d = new StringBuilder();
        d.append("FiberAnalysis density map. Pixel type: float32; NaN = no data; values are physical.\n");
        d.append("Each pixel is one cell of a grid laid on the source image. Pixel (i, j) covers source pixels\n");
        d.append("x0 + i*cell_px to x0 + (i+1)*cell_px across and y0 + j*cell_px to y0 + (j+1)*cell_px down;\n");
        d.append("its value belongs at the cell centre. render=bilinear: interpolate between cell centres;\n");
        d.append("render=nearest: fill each cell. Source pixels outside the grid have no value.\n");
        d.append("A channel marked axial=P is an angle of period P: interpolate and average it as an angle.\n");
        d.append(String.format(
                Locale.ROOT,
                "%s source_width=%d; source_height=%d; source_pixel_um=%s; cell_px=%d; window_px=%d;"
                        + " x0=%s; y0=%s; grid=%dx%d; render=%s%n",
                PLACEMENT,
                info.sourceWidth(),
                info.sourceHeight(),
                num(info.sourcePixelUm()),
                lat.cellPx(),
                lat.windowPx(),
                num(lat.x0()),
                num(lat.y0()),
                lat.gridW(),
                lat.gridH(),
                info.smooth() ? "bilinear" : "nearest"));
        if (info.sourceImage() != null)
            d.append(SOURCE_IMAGE)
                    .append(' ')
                    .append(oneLine(info.sourceImage()))
                    .append('\n');
        if (info.threshold() != null)
            d.append(THRESHOLD).append(' ').append(oneLine(info.threshold())).append('\n');
        if (info.region() != null)
            d.append(REGION).append(' ').append(oneLine(info.region())).append('\n');
        d.append("Channels:\n");
        for (int c = 0; c < channels.size(); c++) {
            DensityChannelSpec spec = channels.get(c);
            d.append(String.format(
                    Locale.ROOT,
                    "  [%d] name=%s%s%s%n",
                    c,
                    spec.channelName,
                    spec.unit != null ? "; unit=" + spec.unit : "",
                    spec.axialPeriod() > 0 ? "; axial=" + num(spec.axialPeriod()) : ""));
        }
        return d.toString();
    }

    /**
     * @param desc a map file's image description
     * @return placement, render mode, source image, threshold, region and channels
     * @throws IllegalArgumentException if the description has no placement line, as in
     *     files written before maps were stored at grid resolution
     */
    static DensityMapInfo parseInfo(String desc) {
        Map<String, String> kv = null;
        String source = null;
        String threshold = null;
        String region = null;
        for (String line : desc.split("\\R")) {
            String t = line.trim();
            if (t.startsWith(PLACEMENT)) {
                kv = new HashMap<>();
                Matcher m = KEY_VALUE.matcher(t.substring(PLACEMENT.length()));
                while (m.find()) kv.put(m.group(1), m.group(2));
            } else if (t.startsWith(SOURCE_IMAGE)) {
                source = t.substring(SOURCE_IMAGE.length()).trim();
            } else if (t.startsWith(THRESHOLD)) {
                threshold = t.substring(THRESHOLD.length()).trim();
            } else if (t.startsWith(REGION)) {
                region = t.substring(REGION.length()).trim();
            }
        }
        if (kv == null) {
            throw new IllegalArgumentException(
                    "This map file has no placement line: it was written before maps were stored at grid"
                            + " resolution (2026-10-19). Re-run the density map or the classifier for this image.");
        }
        try {
            int cell = Integer.parseInt(kv.get("cell_px"));
            int window = Integer.parseInt(kv.get("window_px"));
            String[] grid = kv.get("grid").split("x");
            double inset = (window - cell) / 2.0;
            DensityLattice lat = new DensityLattice(
                    Integer.parseInt(grid[0]),
                    Integer.parseInt(grid[1]),
                    cell,
                    window,
                    Double.parseDouble(kv.get("x0")) - inset,
                    Double.parseDouble(kv.get("y0")) - inset);
            return new DensityMapInfo(
                    lat,
                    Integer.parseInt(kv.get("source_width")),
                    Integer.parseInt(kv.get("source_height")),
                    Double.parseDouble(kv.get("source_pixel_um")),
                    "bilinear".equals(kv.get("render")),
                    source,
                    threshold,
                    region,
                    parse(desc));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed placement line in map file: " + kv, e);
        }
    }

    /** Visible for tests: extract channel rows from the description. */
    static List<DensitySidecar.ChannelInfo> parse(String desc) {
        // The name runs to the next ';' or the end of the line: a lazy (.+?)
        // followed only by optional groups matched ONE character, so every
        // channel was named by its first letter.
        Matcher m = CHANNEL_ROW.matcher(desc);
        List<DensitySidecar.ChannelInfo> out = new ArrayList<>();
        while (m.find()) {
            String unit = m.group(3);
            out.add(new DensitySidecar.ChannelInfo(
                    Integer.parseInt(m.group(1)),
                    m.group(2).trim(),
                    unit == null ? null : unit.trim(),
                    m.group(4) == null ? 0 : Double.parseDouble(m.group(4))));
        }
        out.sort((a, b) -> Integer.compare(a.index, b.index));
        return out;
    }

    private static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ");
    }
}
