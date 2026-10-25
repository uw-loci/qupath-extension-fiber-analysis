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

class WindowGridAlignmentTest {

    @Test
    void theReadBoxStartsOnTheGridAndStillCoversTheAnnotation() {
        // An MH_FNA2 pilot rectangle at (41651, 31982), 11547 px, read with a 273 px margin.
        int[] box = {41378, 31709, 12093, 12093};
        int[] a = FiberAnalysisWorkflow.alignToGrid(box, 290, 94850, 75512);
        assertThat(a[0] % 290).isZero();
        assertThat(a[1] % 290).isZero();
        assertThat(a[0]).isLessThanOrEqualTo(box[0]).isGreaterThan(box[0] - 290);
        assertThat(a[1]).isLessThanOrEqualTo(box[1]).isGreaterThan(box[1] - 290);
        assertThat(a[0] + a[2]).isEqualTo(box[0] + box[2]);
        assertThat(a[1] + a[3]).isEqualTo(box[1] + box[3]);
    }

    @Test
    void textureWindowsNestOnDensityMapCells() {
        // 10 um and 50.2 um at 0.1732 um/px: the map's cells and the texture windows.
        int cell = TileGrid.windowPx(10.0, 0.1732);
        int window = TileGrid.windowPx(50.2, 0.1732);
        assertThat(cell).isEqualTo(58);
        assertThat(window).isEqualTo(290).isEqualTo(5 * cell);
        int[] a = FiberAnalysisWorkflow.alignToGrid(new int[] {12345, 6789, 5000, 5000}, window, 20000, 20000);
        // Every texture window edge (origin + k * window) is a map cell edge (a multiple of 58).
        for (int k = 0; k < 20; k++) {
            assertThat((a[0] + k * window) % cell).isZero();
            assertThat((a[1] + k * window) % cell).isZero();
        }
    }

    @Test
    void edgesAndTrivialStepsAreSafe() {
        int[] atOrigin = FiberAnalysisWorkflow.alignToGrid(new int[] {0, 0, 100, 100}, 58, 1000, 1000);
        assertThat(atOrigin).containsExactly(0, 0, 100, 100);
        int[] clipped = FiberAnalysisWorkflow.alignToGrid(new int[] {950, 950, 200, 200}, 58, 1000, 1000);
        assertThat(clipped).containsExactly(928, 928, 72, 72);
        int[] none = {7, 9, 10, 10};
        assertThat(FiberAnalysisWorkflow.alignToGrid(none, 1, 100, 100)).isSameAs(none);
    }
}
