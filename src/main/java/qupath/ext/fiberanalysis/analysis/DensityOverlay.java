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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.objects.classes.PathClass;

/**
 * A density or classifier map shown as a coloured overlay on QuPath's pixel
 * layer ({@link DensityMapOverlay}), leaving the image server alone. NaN (no
 * data) is transparent. QuPath's opacity slider and "Show pixel
 * classification" control it; it occupies the viewer's single pixel-layer slot.
 */
public final class DensityOverlay {

    private DensityOverlay() {}

    /**
     * One channel of a map, read whole at grid resolution.
     *
     * @param info    the map's placement, render mode and channels
     * @param channel the channel index
     * @param grid    its values, {@code gridW * gridH}, NaN = no data
     */
    public record Loaded(DensityMapInfo info, int channel, float[] grid) {}

    /**
     * @param map     a map file
     * @param channel channel index
     * @return that channel
     * @throws IOException if the file cannot be read
     */
    public static Loaded load(Path map, int channel) throws IOException {
        long t0 = System.nanoTime();
        try (DensityMapReader reader = DensityMapReader.open(map)) {
            float[] grid = reader.readChannel(channel);
            org.slf4j.LoggerFactory.getLogger(DensityOverlay.class)
                    .info(
                            "Overlay read: channel {} of {} ({}x{} cells) in {} ms",
                            channel,
                            map.getFileName(),
                            reader.info().lattice().gridW(),
                            reader.info().lattice().gridH(),
                            (System.nanoTime() - t0) / 1_000_000);
            return new Loaded(reader.info(), channel, grid);
        }
    }

    /**
     * @param values channel values
     * @param lowPct lower percentile, 0-100
     * @param highPct upper percentile, 0-100
     * @return {@code {low, high}} over the finite values, or {@code {0, 1}} if there are none
     */
    public static double[] percentileRange(float[] values, double lowPct, double highPct) {
        float[] finite = new float[values.length];
        int n = 0;
        for (float v : values) if (Float.isFinite(v)) finite[n++] = v;
        if (n == 0) return new double[] {0, 1};
        Arrays.sort(finite, 0, n);
        double lo = finite[(int) Math.floor((n - 1) * lowPct / 100.0)];
        double hi = finite[(int) Math.ceil((n - 1) * highPct / 100.0)];
        if (hi <= lo) hi = lo + 1e-9;
        return new double[] {lo, hi};
    }

    /**
     * The classes a classifier map's predicted-class channel indexes: the
     * {@code P(<class>)} channels, which are written in the model's class order.
     *
     * @param info a map's channels
     * @return class names by index, empty for a map with no class channels
     */
    public static List<String> classNames(DensityMapInfo info) {
        List<String> out = new ArrayList<>();
        for (var ch : info.channels()) {
            String n = ch.name;
            if (n.startsWith("P(") && n.endsWith(")")) out.add(n.substring(2, n.length() - 1));
        }
        return out;
    }

    /**
     * @param channel a channel
     * @return true for a classifier's predicted-class channel
     */
    public static boolean isClassChannel(DensitySidecar.ChannelInfo channel) {
        return channel != null
                && DensityChannelSpec.forPredictedClassIndex().channelName.equals(channel.name);
    }

    /**
     * Each class in the colour its QuPath class already has.
     *
     * @param names class names by index
     * @return the class-colour style for them
     */
    public static DensityMapOverlay.Style classStyle(List<String> names) {
        int[] colours = new int[names.size()];
        for (int i = 0; i < colours.length; i++) {
            Integer c = PathClass.fromString(names.get(i)).getColor();
            colours[i] = c == null ? 0x808080 : c & 0xffffff;
        }
        return DensityMapOverlay.Style.classes(names, colours);
    }

    /**
     * Show a channel over the image, replacing whatever is on the viewer's pixel layer.
     *
     * @param viewer  the viewer
     * @param options its overlay options
     * @param loaded  the channel
     * @param style   how its values become colours
     * @param brightness a 0-1 channel of the same map setting brightness, or null
     * @return the overlay installed
     */
    public static PathOverlay show(
            QuPathViewer viewer,
            OverlayOptions options,
            Loaded loaded,
            DensityMapOverlay.Style style,
            Loaded brightness) {
        var overlay = new DensityMapOverlay(
                options,
                loaded.info(),
                loaded.channel(),
                loaded.grid(),
                style,
                brightness == null ? -1 : brightness.channel(),
                brightness == null ? null : brightness.grid());
        viewer.setCustomPixelLayerOverlay(overlay);
        options.setShowPixelClassification(true);
        return overlay;
    }

    /**
     * Remove an overlay this class installed; leaves anything else alone.
     *
     * @param viewer  the viewer
     * @param overlay the overlay {@link #show} returned
     */
    public static void hide(QuPathViewer viewer, PathOverlay overlay) {
        if (overlay != null && viewer.getCustomPixelLayerOverlay() == overlay) {
            viewer.resetCustomPixelLayerOverlay();
        }
    }
}
