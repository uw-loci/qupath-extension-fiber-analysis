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
import java.util.Arrays;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;

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
     * Show a channel over the image, replacing whatever is on the viewer's pixel layer.
     *
     * @param viewer  the viewer
     * @param options its overlay options
     * @param loaded  the channel
     * @param map     colour map
     * @param min     value drawn with the map's first colour
     * @param max     value drawn with its last colour
     * @return the overlay installed
     */
    public static PathOverlay show(
            QuPathViewer viewer,
            OverlayOptions options,
            Loaded loaded,
            ColorMaps.ColorMap map,
            double min,
            double max) {
        var overlay = new DensityMapOverlay(options, loaded.info(), loaded.channel(), loaded.grid(), map, min, max);
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
