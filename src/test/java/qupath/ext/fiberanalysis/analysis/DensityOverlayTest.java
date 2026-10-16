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

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorMaps;

/** Colouring a map channel for the pixel-layer overlay. */
class DensityOverlayTest {

    private static ColorMaps.ColorMap viridis() {
        var maps = ColorMaps.getColorMaps();
        return maps.getOrDefault("Viridis", maps.values().iterator().next());
    }

    @Test
    void noDataIsTransparentAndEverythingElseIsOpaque() {
        var ch = new DensityOverlay.Channel(new float[] {Float.NaN, 0.5f, Float.POSITIVE_INFINITY, 0f}, 2, 2, 20, 20);
        BufferedImage img = DensityOverlay.colour(ch, viridis(), 0, 1);
        assertThat(img.getRGB(0, 0) >>> 24).isZero();
        assertThat(img.getRGB(1, 0) >>> 24).isEqualTo(255);
        assertThat(img.getRGB(0, 1) >>> 24).isZero();
        assertThat(img.getRGB(1, 1) >>> 24).isEqualTo(255);
    }

    @Test
    void valuesOutsideTheRangeTakeTheEndColours() {
        var map = viridis();
        var ch = new DensityOverlay.Channel(new float[] {-5f, 0f, 1f, 9f}, 4, 1, 4, 1);
        BufferedImage img = DensityOverlay.colour(ch, map, 0, 1);
        assertThat(img.getRGB(0, 0)).isEqualTo(img.getRGB(1, 0));
        assertThat(img.getRGB(3, 0)).isEqualTo(img.getRGB(2, 0));
        assertThat(img.getRGB(0, 0)).isNotEqualTo(img.getRGB(3, 0));
        assertThat(img.getRGB(1, 0)).isEqualTo(0xff000000 | map.getColor(0, 0, 1));
    }

    @Test
    void theAutoRangeIgnoresNoDataAndOutliers() {
        float[] v = new float[1000];
        for (int i = 0; i < v.length; i++) v[i] = i / 999f;
        v[0] = Float.NaN;
        v[999] = 1e6f;
        double[] r = DensityOverlay.percentileRange(v, 1, 99);
        assertThat(r[0]).isBetween(0.005, 0.02);
        assertThat(r[1]).isBetween(0.98, 1.0);
    }

    @Test
    void anAllNoDataChannelStillGetsAUsableRange() {
        double[] r = DensityOverlay.percentileRange(new float[] {Float.NaN, Float.NaN}, 1, 99);
        assertThat(r[1]).isGreaterThan(r[0]);
    }
}
