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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The classifier attributes a window's prediction to a small box at the
 * window's centre. Everything rests on those boxes tiling the plane exactly:
 * a gap leaves unlabelled pixels, an overlap means two windows claim the same
 * pixel, and an off-centre box silently shifts the whole output map away from
 * the measurements that produced it.
 */
class WindowGeometryTest {

    @Test
    void coreIsCentredInItsWindow() {
        // The defect this guards. TileGrid.ownedBoxes() anchors at the tile
        // origin; reusing that construction per window puts the core in the
        // window's top-left corner instead of its centre. At window 289 /
        // stride 72 that is an 108 px offset -- 37.5 um at 0.3464 um/px.
        int windowPx = 289;
        int stridePx = 72;
        WindowGeometry.Box win = WindowGeometry.windowBox(3, 5, windowPx, stridePx);
        WindowGeometry.Box core = WindowGeometry.coreBox(3, 5, windowPx, stridePx);

        double winCx = win.x() + win.w() / 2.0;
        double winCy = win.y() + win.h() / 2.0;
        double coreCx = core.x() + core.w() / 2.0;
        double coreCy = core.y() + core.h() / 2.0;

        // Half a pixel of slack: the inset is floored when the parities differ.
        assertThat(Math.abs(coreCx - winCx)).isLessThanOrEqualTo(0.5);
        assertThat(Math.abs(coreCy - winCy)).isLessThanOrEqualTo(0.5);
        // And the core must sit strictly inside the window.
        assertThat(core.x()).isGreaterThanOrEqualTo(win.x());
        assertThat(core.y()).isGreaterThanOrEqualTo(win.y());
        assertThat(core.x() + core.w()).isLessThanOrEqualTo(win.x() + win.w());
        assertThat(core.y() + core.h()).isLessThanOrEqualTo(win.y() + win.h());
    }

    @Test
    void coreIsNotTheTopLeftCornerOfTheWindow() {
        // Stated separately because this is the specific wrong answer that
        // reusing ownedBoxes() would have produced, and it passes every
        // "cores tile the plane" check.
        WindowGeometry.Box win = WindowGeometry.windowBox(0, 0, 289, 72);
        WindowGeometry.Box core = WindowGeometry.coreBox(0, 0, 289, 72);
        assertThat(core.x()).isNotEqualTo(win.x());
        assertThat(core.y()).isNotEqualTo(win.y());
        assertThat(core.x()).isEqualTo(108);
    }

    @Test
    void coresPartitionTheCoveredRegionExactly() {
        int[][] cases = {
            {289, 72}, // 100 um window, 25% stride, the production geometry
            {289, 144}, // 50% stride, what the MH_Colon run used
            {200, 200}, // no overlap at all
            {100, 1}, // pathological: stride of a single pixel
            {64, 32},
            {65, 32}, // odd window, even stride -- the floored half-pixel
            {64, 33} // even window, odd stride
        };
        for (int[] c : cases) {
            int windowPx = c[0];
            int stridePx = c[1];
            int n = 7;
            Set<Long> claimed = new HashSet<>();
            long area = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int iy = 0; iy < n; iy++) {
                for (int ix = 0; ix < n; ix++) {
                    WindowGeometry.Box b = WindowGeometry.coreBox(ix, iy, windowPx, stridePx);
                    assertThat(b.w())
                            .as("core side must equal the stride for %d/%d", windowPx, stridePx)
                            .isEqualTo(stridePx);
                    assertThat(b.h()).isEqualTo(stridePx);
                    area += (long) b.w() * b.h();
                    minX = Math.min(minX, b.x());
                    minY = Math.min(minY, b.y());
                    maxX = Math.max(maxX, b.x() + b.w());
                    maxY = Math.max(maxY, b.y() + b.h());
                    // Corners and centre are enough to catch any overlap.
                    // Collect into a set first: a 1 px core makes all five
                    // probes the same point, and that is not an overlap.
                    Set<Long> probes = new HashSet<>();
                    int[][] offsets = {
                        {0, 0}, {b.w() - 1, 0}, {0, b.h() - 1}, {b.w() - 1, b.h() - 1}, {b.w() / 2, b.h() / 2}
                    };
                    for (int[] p : offsets) {
                        probes.add(((long) (b.x() + p[0]) << 32) | (b.y() + p[1]));
                    }
                    for (long key : probes) {
                        assertThat(claimed.add(key))
                                .as("point owned by more than one core at %d/%d", windowPx, stridePx)
                                .isTrue();
                    }
                }
            }
            // No gaps either: the cores exactly fill their bounding box.
            long bbox = (long) (maxX - minX) * (maxY - minY);
            assertThat(area)
                    .as("cores must fill their bounding box with no gap at %d/%d", windowPx, stridePx)
                    .isEqualTo(bbox);
        }
    }

    @Test
    void wholeWindowCountMatchesThePythonLattice() {
        // compute_windows uses (extent - window) // stride + 1.
        assertThat(WindowGeometry.wholeCount(1000, 100, 50)).isEqualTo(19);
        assertThat(WindowGeometry.wholeCount(100, 100, 50)).isEqualTo(1);
        assertThat(WindowGeometry.wholeCount(99, 100, 50)).isZero();
        assertThat(WindowGeometry.wholeCount(2048, 289, 72)).isEqualTo(25);
    }

    @Test
    void aTrailingStripGetsOnePartialWindow() {
        // Floor division leaves up to windowPx-1 pixels uncovered on each
        // axis, which on a classifier raster is an unclassified border on
        // every slide. One clamped window per axis closes it.
        assertThat(WindowGeometry.partialCount(1000, 100, 50)).isZero(); // 19 whole reach exactly 1000
        assertThat(WindowGeometry.partialCount(950, 100, 50)).isZero(); // 18 whole reach exactly 950
        assertThat(WindowGeometry.totalCount(950, 100, 50)).isEqualTo(18);
        assertThat(WindowGeometry.partialCount(975, 100, 50)).isEqualTo(1); // 25 px left over
        assertThat(WindowGeometry.totalCount(975, 100, 50)).isEqualTo(19);
        assertThat(WindowGeometry.partialCount(60, 100, 50)).isEqualTo(1); // narrower than one window
        assertThat(WindowGeometry.partialCount(0, 100, 50)).isZero();
    }

    @Test
    void partialWindowsAreFlaggedSoTheyCanBeExcludedFromTraining() {
        int w = 1000;
        int h = 500;
        assertThat(WindowGeometry.isPartial(0, 0, w, h, 100, 50)).isFalse();
        assertThat(WindowGeometry.isPartial(18, 0, w, h, 100, 50)).isFalse(); // 18*50+100 = 1000, fits exactly
        assertThat(WindowGeometry.isPartial(19, 0, w, h, 100, 50)).isTrue(); // 1050 > 1000
        assertThat(WindowGeometry.isPartial(0, 8, w, h, 100, 50)).isFalse(); // 8*50+100 = 500, fits exactly
        assertThat(WindowGeometry.isPartial(0, 9, w, h, 100, 50)).isTrue(); // 550 > 500
    }

    @Test
    void rejectsGeometryThatWouldLeaveGapsBetweenCores() {
        // A stride wider than the window means the windows themselves do not
        // overlap or even touch, so no set of cores can tile the region.
        assertThatThrownBy(() -> WindowGeometry.coreBox(0, 0, 100, 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
        assertThatThrownBy(() -> WindowGeometry.coreBox(0, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowGeometry.coreBox(-1, 0, 100, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
