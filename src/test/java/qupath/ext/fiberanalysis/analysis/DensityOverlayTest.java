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
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorMaps;

/** A map channel coloured for the pixel-layer overlay, at any zoom. */
class DensityOverlayTest {

    private static ColorMaps.ColorMap viridis() {
        var maps = ColorMaps.getColorMaps();
        return maps.getOrDefault("Viridis", maps.values().iterator().next());
    }

    private static DensityMapOverlay overlay(DensityLattice lat, float[] grid, boolean smooth, double min, double max) {
        var info = new DensityMapInfo(
                lat, 1000, 1000, 0.25, smooth, "img", List.of(new DensitySidecar.ChannelInfo(0, "Signal", null)));
        return new DensityMapOverlay(null, info, 0, grid, viridis(), min, max);
    }

    @Test
    void noDataIsTransparentAndEverythingElseIsOpaque() {
        var o = overlay(DensityLattice.blocks(2, 1, 10), new float[] {Float.NaN, 0.5f}, false, 0, 1);
        BufferedImage img = o.render(0, 0, 20, 10, 20, 10);
        assertThat(img.getRGB(5, 5) >>> 24).isZero();
        assertThat(img.getRGB(15, 5) >>> 24).isEqualTo(255);
    }

    @Test
    void valuesOutsideTheRangeTakeTheEndColours() {
        var o = overlay(DensityLattice.blocks(4, 1, 1), new float[] {-5f, 0f, 1f, 9f}, false, 0, 1);
        BufferedImage img = o.render(0, 0, 4, 1, 4, 1);
        assertThat(img.getRGB(0, 0)).isEqualTo(img.getRGB(1, 0));
        assertThat(img.getRGB(3, 0)).isEqualTo(img.getRGB(2, 0));
        assertThat(img.getRGB(0, 0)).isNotEqualTo(img.getRGB(3, 0));
        assertThat(img.getRGB(1, 0)).isEqualTo(0xff000000 | viridis().getColor(0, 0, 1));
    }

    @Test
    void zoomedInANearestMapKeepsSharpCellEdges() {
        // Two 10 px cells, drawn 10x zoomed: every output pixel left of the
        // cell edge at x = 10 is cell 0's colour, every one right of it cell 1's.
        var o = overlay(DensityLattice.blocks(2, 1, 10), new float[] {0f, 1f}, false, 0, 1);
        BufferedImage img = o.render(8, 0, 4, 10, 40, 100);
        int left = img.getRGB(0, 50);
        int right = img.getRGB(39, 50);
        assertThat(left).isNotEqualTo(right);
        for (int x = 0; x < 40; x++) assertThat(img.getRGB(x, 50)).isEqualTo(x < 20 ? left : right);
    }

    @Test
    void zoomedInASmoothMapBlendsBetweenCellCentres() {
        var o = overlay(DensityLattice.blocks(2, 1, 10), new float[] {0f, 1f}, true, 0, 1);
        // Output pixel centres at source 2.5, 7.5, 12.5 and 17.5; the cell
        // centres are at 5 and 15, so the ends hold the cell values and the
        // middle two are a quarter and three quarters of the way between.
        BufferedImage img = o.render(0, 0, 20, 10, 4, 2);
        var map = viridis();
        assertThat(img.getRGB(0, 0)).isEqualTo(0xff000000 | map.getColor(0, 0, 1));
        assertThat(img.getRGB(3, 0)).isEqualTo(0xff000000 | map.getColor(1, 0, 1));
        int quarter = img.getRGB(1, 0);
        int threeQuarter = img.getRGB(2, 0);
        assertThat(quarter).isNotEqualTo(img.getRGB(0, 0)).isNotEqualTo(threeQuarter);
    }

    @Test
    void aMapIsDrawnOnlyOverTheCellsItsPlacementGives() {
        // 100 px windows, stride 25, laid from (1000, 2000): the cells tile
        // x 1037.5 to 1112.5 and y 2037.5 to 2062.5.
        var o = overlay(DensityLattice.windows(3, 1, 25, 100, 1000, 2000), new float[] {0f, 1f, 2f}, false, 0, 2);
        assertThat(o.gridBounds()).isEqualTo(new java.awt.Rectangle(1037, 2037, 76, 26));
        BufferedImage img = o.render(1000, 2000, 200, 100, 200, 100);
        assertThat(img.getRGB(30, 50) >>> 24).isZero();
        assertThat(img.getRGB(40, 50) >>> 24).isEqualTo(255);
        assertThat(img.getRGB(120, 50) >>> 24).isZero();
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
