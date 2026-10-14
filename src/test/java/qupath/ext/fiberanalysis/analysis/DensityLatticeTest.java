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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Where a window value is drawn on the source image. */
class DensityLatticeTest {

    /** A 3x1 grid whose cell value is its index. */
    private static final float[] ROW = {0f, 1f, 2f};

    @Test
    void aWindowValueIsDrawnCentredOnItsWindowNotAtItsCorner() {
        // 100 px windows, stride 25: window 1 spans [25, 125) and is centred
        // at 75. The old writer drew it over [25, 50), 37.5 px up-left.
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 0, 0);
        assertThat(lat.nearest(ROW, 75.5, 50)).isEqualTo(1f);
        assertThat(lat.nearest(ROW, 62.5 + 0.5, 50)).isEqualTo(1f);
        assertThat(lat.nearest(ROW, 87.0, 50)).isEqualTo(1f);
        assertThat(lat.nearest(ROW, 88.0, 50)).isEqualTo(2f);
        assertThat(lat.nearest(ROW, 30.5, 50)).isEqualTo(0f);
    }

    @Test
    void pixelsOutsideEveryWindowAreNoData() {
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 0, 0);
        // The windows span [0, 150) across and [0, 100) down.
        assertThat(lat.nearest(ROW, 149.5, 50)).isEqualTo(2f);
        assertThat(lat.nearest(ROW, 150.5, 50)).isNaN();
        assertThat(lat.nearest(ROW, 50, 100.5)).isNaN();
        assertThat(lat.bilinear(ROW, 150.5, 50)).isNaN();
    }

    @Test
    void aRegionLatticeSitsAtTheRegionNotAtTheImageCorner() {
        // The annotation-scope classifier map: windows laid from (1000, 2000).
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 1000, 2000);
        assertThat(lat.nearest(ROW, 75.5, 50)).isNaN();
        assertThat(lat.nearest(ROW, 1075.5, 2050)).isEqualTo(1f);
    }

    @Test
    void bilinearPassesThroughEachWindowCentreAndBlendsBetween() {
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 0, 0);
        assertThat(lat.bilinear(ROW, 50, 50)).isEqualTo(0f);
        assertThat(lat.bilinear(ROW, 75, 50)).isEqualTo(1f);
        assertThat(lat.bilinear(ROW, 87.5, 50)).isEqualTo(1.5f);
    }

    @Test
    void bilinearSkipsANoDataNeighbourInsteadOfDraggingTowardIt() {
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 0, 0);
        float[] holed = {0f, Float.NaN, 2f};
        assertThat(lat.bilinear(holed, 70, 50)).isEqualTo(0f);
    }

    @Test
    void aBlockLatticeCentresEachSampleOnItsBlock() {
        DensityLattice lat = DensityLattice.blocks(3, 1, 4);
        assertThat(lat.nearest(ROW, 4.5, 1)).isEqualTo(1f);
        assertThat(lat.nearest(ROW, 7.5, 1)).isEqualTo(1f);
        assertThat(lat.nearest(ROW, 8.5, 1)).isEqualTo(2f);
        assertThat(lat.bilinear(ROW, 6, 2)).isEqualTo(1f);
    }

    @Test
    void cellsThatWouldLeaveGapsAreRefused() {
        assertThatThrownBy(() -> new DensityLattice(3, 3, 50, 25, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
