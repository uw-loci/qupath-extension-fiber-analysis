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
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class TileZoneSkipTest {

    // An L-shaped tissue outline: the top-right block of its bounding box is empty.
    private static PreparedGeometry lShape() {
        var roi = ROIs.createPolygonROI(
                new double[] {0, 10000, 10000, 4000, 4000, 0},
                new double[] {0, 0, 4000, 4000, 10000, 10000},
                ImagePlane.getDefaultPlane());
        return PreparedGeometryFactory.prepare(roi.getGeometry());
    }

    @Test
    void aTileInTheEmptyCornerIsSkipped() {
        assertThat(FiberAnalysisWorkflow.tileTouchesZone(lShape(), 5000, 5000, 3000, 3000))
                .isFalse();
    }

    @Test
    void aTileHoldingAnyOfTheZoneIsKept() {
        PreparedGeometry z = lShape();
        assertThat(FiberAnalysisWorkflow.tileTouchesZone(z, 0, 0, 3000, 3000)).isTrue();
        // Overlaps the zone by 10 px only, in its padding.
        assertThat(FiberAnalysisWorkflow.tileTouchesZone(z, 3990, 5000, 3000, 3000))
                .isTrue();
        // A tile containing the whole zone.
        assertThat(FiberAnalysisWorkflow.tileTouchesZone(z, -100, -100, 20000, 20000))
                .isTrue();
    }

    @Test
    void aRunIsWarnedWhenFibersFillTooFewGrayLevels() {
        var pdac = java.util.Map.of(
                "texture.levels_holding_99pct",
                4.0,
                "texture.fiber_gray_p1",
                4523.0,
                "texture.fiber_gray_p99",
                19520.0);
        var fine = java.util.Map.of(
                "texture.levels_holding_99pct",
                16.0,
                "texture.fiber_gray_p1",
                3000.0,
                "texture.fiber_gray_p99",
                25000.0);
        assertThat(FiberAnalysisWorkflow.textureLevelNote(java.util.List.of(fine), 16))
                .isNull();
        assertThat(FiberAnalysisWorkflow.textureLevelNote(java.util.List.of(pdac, fine), 16))
                .contains("1 of 2 annotations")
                .contains("4 or fewer of 16")
                .contains("3000 to 25000");
        // No texture block, nothing to say.
        assertThat(FiberAnalysisWorkflow.textureLevelNote(java.util.List.of(java.util.Map.of()), 16))
                .isNull();
    }

    @Test
    void perDistanceGlcmNamesCarryTheDistance() {
        assertThat(FiberAnalysisWorkflow.glcmDistanceName("contrast", 58 * 0.1732))
                .isEqualTo("Contrast (GLCM, 10.05 um)");
        assertThat(FiberAnalysisWorkflow.glcmDistanceName("entropy", 14 * 0.1732))
                .isEqualTo("Entropy (GLCM, 2.42 um)");
    }
}
