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

import org.junit.jupiter.api.Test;

/**
 * The density map's tiling: every cell of the slide computed exactly once,
 * from a tile core that holds all of its window (window mode) or its sample
 * point (pixel mode), and slotted where it belongs.
 *
 * <p>The tiling this replaced made 16-window tiles with no overlap. At any
 * overlap above 0% the windows straddling a seam were never computed (18 of
 * 397 columns at 75%), and when the tile width was not a whole number of
 * strides most windows were slotted one cell off (193 of 221 at 30%).
 */
class DensityMapTilingTest {

    @Test
    void windowModeComputesEveryWindowOnceFromItsWholeSupport() {
        int checked = 0;
        for (int extent : new int[] {64, 99, 1000, 4097, 10000}) {
            for (int window : new int[] {2, 7, 32, 60, 64, 100}) {
                if (window > extent) continue;
                for (double overlap : new double[] {0.0, 0.3, 0.5, 0.75, 0.9, 0.95}) {
                    int stride = Math.max(1, (int) Math.round(window * (1.0 - overlap)));
                    var geo = FiberDensityMapWorkflow.Geometry.window(extent, extent, window, stride, 16);
                    assertCoverage(geo, extent, false);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void pixelModeComputesEverySampleOnceInsideItsCore() {
        for (int extent : new int[] {5, 64, 1000, 4097}) {
            for (int window : new int[] {4, 32, 100}) {
                for (int step : new int[] {1, 3, 4, 13, 50}) {
                    var geo = FiberDensityMapWorkflow.Geometry.pixel(extent, extent, window, step);
                    assertCoverage(geo, extent, true);
                }
            }
        }
    }

    /** Replays the workflow's tile loop along one axis. */
    private static void assertCoverage(FiberDensityMapWorkflow.Geometry geo, int extent, boolean pixel) {
        int[] writes = new int[geo.gridW()];
        for (int t = 0; t < geo.nTilesX(); t++) {
            int core0 = t * geo.tileStep();
            int coreLen = Math.min(geo.tileStep() + geo.margin(), extent - core0);
            int cells = geo.cellsIn(coreLen);
            assertThat(core0 % geo.cellPx()).as("core origin on the lattice").isZero();
            int slide0 = core0 / geo.cellPx();
            for (int j = 0; j < cells; j++) {
                int g = slide0 + j;
                assertThat(g).as("cell inside the slide grid").isLessThan(geo.gridW());
                writes[g]++;
                if (pixel) {
                    // density_tile samples core pixel min(j*step + step/2, len-1).
                    int local = Math.min(j * geo.cellPx() + geo.cellPx() / 2, coreLen - 1);
                    int global = Math.min(g * geo.cellPx() + geo.cellPx() / 2, extent - 1);
                    assertThat(core0 + local).as("sample point").isEqualTo(global);
                } else {
                    int start = g * geo.cellPx();
                    assertThat(start).isGreaterThanOrEqualTo(core0);
                    assertThat(start + geo.windowPx())
                            .as("window inside the core")
                            .isLessThanOrEqualTo(core0 + coreLen);
                }
            }
        }
        for (int g = 0; g < writes.length; g++) {
            assertThat(writes[g])
                    .as("cell %d of %s on extent %d", g, geo, extent)
                    .isEqualTo(1);
        }
    }

    @Test
    void aBoxSamplerCellUsesTheSameBoxAsTheFiberChannels() {
        // Window lattice: the window itself; past the edge it is undefined.
        assertThat(MaskBoxSampler.box(2, 25, 100, 1000, false)).containsExactly(50, 150);
        assertThat(MaskBoxSampler.box(37, 25, 100, 1000, false)).isNull();
        // Block lattice: centred on the sample pixel g*step + step/2, like
        // scipy's uniform_filter, clipped (not padded) at the image edge.
        assertThat(MaskBoxSampler.box(3, 4, 10, 1000, true)).containsExactly(9, 19);
        assertThat(MaskBoxSampler.box(0, 4, 10, 1000, true)).containsExactly(0, 7);
    }
}
