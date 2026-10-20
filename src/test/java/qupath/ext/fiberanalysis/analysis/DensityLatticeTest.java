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
        assertThat(lat.nearest(ROW, 40.5, 50)).isEqualTo(0f);
    }

    @Test
    void pixelsOutsideEveryCellAreNoData() {
        DensityLattice lat = DensityLattice.windows(3, 1, 25, 100, 0, 0);
        // The cells are centred on the windows: they tile [37.5, 112.5) across
        // and [37.5, 62.5) down.
        assertThat(lat.x0()).isEqualTo(37.5);
        assertThat(lat.nearest(ROW, 112.0, 50)).isEqualTo(2f);
        assertThat(lat.nearest(ROW, 112.5, 50)).isNaN();
        assertThat(lat.nearest(ROW, 37.0, 50)).isNaN();
        assertThat(lat.nearest(ROW, 50, 62.5)).isNaN();
        assertThat(lat.bilinear(ROW, 112.5, 50)).isNaN();
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
    void anAxialAngleBlendsAcrossTheWrap() {
        DensityLattice lat = DensityLattice.blocks(2, 1, 10);
        float[] angles = {170f, 10f};
        // Half way between the centres: 0 degrees (= 180), not the plain mean 90.
        float a = lat.bilinear(angles, 10, 5, 180);
        assertThat(Math.min(a, 180 - a)).isLessThan(1e-3f);
        assertThat(lat.bilinear(angles, 10, 5)).isEqualTo(90f);
        // A quarter of the way from 170 toward 10 goes through 180, landing near 175 (a weighted
        // mean of unit vectors, so not exactly linear in the angle: 174.84).
        assertThat(lat.bilinear(angles, 7.5, 5, 180)).isCloseTo(175f, org.assertj.core.data.Offset.offset(0.5f));
        assertThat(lat.sample(angles, 7.5, 5, false, 180)).isEqualTo(170f);
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
