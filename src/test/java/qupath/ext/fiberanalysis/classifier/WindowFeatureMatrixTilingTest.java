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

import org.junit.jupiter.api.Test;

/**
 * The tiling invariant: every window on the slide lattice is computed exactly
 * once, from its full support.
 *
 * <p>Breaking it does not raise anything. It produces features that are wrong
 * in a way that correlates with position, and a model that has learned the
 * tile seams.
 */
class WindowFeatureMatrixTilingTest {

    /**
     * Walk the tiling the way the extractor does and assert the two
     * properties that matter.
     */
    private static void assertTilingIsSound(int regionW, int windowPx, int stridePx, int tileWindows) {
        var t = WindowFeatureMatrix.planTiles(regionW, regionW, windowPx, stridePx, tileWindows);
        int slideGrid = WindowGeometry.totalCount(regionW, windowPx, stridePx);
        String what =
                String.format("region=%d window=%d stride=%d tileWindows=%d", regionW, windowPx, stridePx, tileWindows);

        int nextExpected = 0;
        for (int tx = 0; tx < t.nTilesX(); tx++) {
            int localX = tx * t.tileStep();
            if (localX >= regionW) continue;
            int readW = Math.min(t.tileStep() + t.margin(), regionW - localX);
            int tileGrid = WindowGeometry.totalCount(readW, windowPx, stridePx);
            int slideX0 = localX / stridePx;

            // (1) The tile's windows continue the slide lattice exactly --
            // no gap, no overlap, no window computed twice.
            assertThat(slideX0)
                    .as("%s: tile %d starts at slide window %d", what, tx, nextExpected)
                    .isEqualTo(nextExpected);
            nextExpected = slideX0 + tileGrid;

            // (2) Every WHOLE window of the tile has its full support inside
            // the read. A clamped one is allowed only where the region
            // itself ends.
            int whole = WindowGeometry.wholeCount(readW, windowPx, stridePx);
            for (int j = 0; j < whole; j++) {
                assertThat(j * stridePx + windowPx)
                        .as("%s: tile %d window %d runs past the read", what, tx, j)
                        .isLessThanOrEqualTo(readW);
            }
            if (tileGrid > whole) {
                assertThat(localX + readW)
                        .as("%s: tile %d clamped away from the region edge", what, tx)
                        .isEqualTo(regionW);
            }
        }
        assertThat(nextExpected).as("%s: lattice coverage", what).isEqualTo(slideGrid);
    }

    @Test
    void theTilesPartitionTheLatticeAcrossEveryGeometryWeOffer() {
        int[] regions = {100, 257, 1000, 1024, 4096, 10_000, 65_537};
        int[] windows = {32, 100, 128, 200};
        double[] overlaps = {0.0, 0.5, 0.75, 0.875, 0.95};
        int[] tileWindowCounts = {1, 4, 8};
        for (int regionW : regions) {
            for (int windowPx : windows) {
                for (double overlap : overlaps) {
                    int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlap)));
                    for (int tw : tileWindowCounts) {
                        assertTilingIsSound(regionW, windowPx, stridePx, tw);
                    }
                }
            }
        }
    }

    @Test
    void theTileStepIsAWholeNumberOfStrides() {
        // A tile origin off the lattice would make "slide index = origin /
        // stride" lossy, and every window in that tile would land one
        // position out.
        for (int windowPx : new int[] {100, 128, 200}) {
            for (int stridePx : new int[] {7, 12, 25, 50, 100}) {
                if (stridePx > windowPx) continue;
                var t = WindowFeatureMatrix.planTiles(10_000, 10_000, windowPx, stridePx, 8);
                assertThat(t.tileStep() % stridePx)
                        .as("window=%d stride=%d", windowPx, stridePx)
                        .isZero();
            }
        }
    }

    @Test
    void theReadMarginIsExactlyTheWindowsOverhang() {
        var t = WindowFeatureMatrix.planTiles(10_000, 10_000, 100, 25, 8);
        assertThat(t.margin()).isEqualTo(75);
        // Zero overlap needs no margin at all: windows abut, so a tile's last
        // window already ends at the tile boundary.
        assertThat(WindowFeatureMatrix.planTiles(10_000, 10_000, 100, 100, 8).margin())
                .isZero();
    }

    @Test
    void aRegionSmallerThanOneWindowIsStillOneTileAndOneWindow() {
        var t = WindowFeatureMatrix.planTiles(60, 60, 100, 25, 8);
        assertThat(t.nTilesX()).isEqualTo(1);
        assertThat(WindowGeometry.totalCount(60, 100, 25)).isEqualTo(1);
    }
}
