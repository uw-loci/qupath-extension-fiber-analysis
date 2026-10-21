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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

/** The run-wide threshold's sampling and the spec it hands every tile. */
class PooledThresholdTest {

    @Test
    void samplesAreSpreadOverTheWholeSlideAndStayInside() {
        List<int[]> boxes = PooledThreshold.sampleBoxes(100_000, 80_000, 4, 1024);
        assertThat(boxes).hasSize(16);
        int minX = Integer.MAX_VALUE, maxX = 0;
        for (int[] b : boxes) {
            assertThat(b[0]).isGreaterThanOrEqualTo(0);
            assertThat(b[0] + b[2]).isLessThanOrEqualTo(100_000);
            assertThat(b[1] + b[3]).isLessThanOrEqualTo(80_000);
            assertThat(b[2]).isEqualTo(1024);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0] + b[2]);
        }
        // From the first quarter to the last, not a corner of the slide.
        assertThat(minX).isLessThan(25_000);
        assertThat(maxX).isGreaterThan(75_000);
    }

    @Test
    void aSmallImageGetsSmallerSamplesNotOutOfBoundsOnes() {
        for (int[] b : PooledThreshold.sampleBoxes(300, 90, 4, 1024)) {
            assertThat(b[0] + b[2]).isLessThanOrEqualTo(300);
            assertThat(b[1] + b[3]).isLessThanOrEqualTo(90);
            assertThat(b[2]).isPositive();
            assertThat(b[3]).isPositive();
        }
    }

    @Test
    void perRegionMethodsArePooledAndFixedOnesAreNot() {
        assertThat(PooledThreshold.needsPooling("Otsu")).isTrue();
        assertThat(PooledThreshold.needsPooling("otsu")).isTrue();
        assertThat(PooledThreshold.needsPooling("Triangle")).isTrue();
        assertThat(PooledThreshold.needsPooling("manual")).isFalse();
        assertThat(PooledThreshold.needsPooling("project_otsu")).isFalse();
        assertThat(PooledThreshold.needsPooling(null)).isFalse();
    }

    @Test
    void everyTileGetsTheSameCutAndTheRecordSaysWhereItCameFrom() throws Exception {
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                25,
                50,
                "Raw intensity",
                "Otsu",
                128,
                0.1,
                "none",
                1,
                4,
                1,
                1,
                false,
                0,
                null,
                false,
                "pixel",
                2.0,
                true,
                List.of("Tumor"),
                false,
                List.of());
        var pooled = spec.withRunThreshold(0.123, "Otsu pooled over 3 image(s)");
        assertThat(pooled.thresholdMethod).isEqualTo("project_otsu");
        assertThat(pooled.projectThresholdNorm).isEqualTo(0.123);
        assertThat(pooled.toOrderedMap()).containsEntry("thresholdSource", "Otsu pooled over 3 image(s)");
        // The map records the cut as an Otsu one, not a calibration, and a replay keeps it so.
        assertThat(ThresholdIdentity.of(pooled).cut()).isEqualTo("Otsu 0.1230");
        assertThat(pooled.toOrderedMap()).containsEntry("fittedMethod", spec.thresholdMethod);
        java.io.File json = java.io.File.createTempFile("density_params", ".json");
        json.deleteOnExit();
        java.nio.file.Files.writeString(json.toPath(), new com.google.gson.Gson().toJson(pooled.toOrderedMap()));
        var replayed = FiberDensityMapWorkflow.DensityMapJobSpec.fromJson(json);
        assertThat(ThresholdIdentity.of(replayed).label())
                .isEqualTo(ThresholdIdentity.of(pooled).label());
        // Everything else is carried over untouched.
        var a = new java.util.LinkedHashMap<>(spec.toOrderedMap());
        var b = new java.util.LinkedHashMap<>(pooled.toOrderedMap());
        for (String k : List.of(
                "thresholdMethod", "projectThresholdNorm", "thresholdSource", "thresholdIdentity", "fittedMethod")) {
            a.remove(k);
            b.remove(k);
        }
        assertThat(b).isEqualTo(a);
    }

    private static Geometry squareWithHole() {
        var f = new GeometryFactory();
        var shell = f.createLinearRing(new Coordinate[] {
            new Coordinate(1000, 1000),
            new Coordinate(3000, 1000),
            new Coordinate(3000, 3000),
            new Coordinate(1000, 3000),
            new Coordinate(1000, 1000)
        });
        var hole = f.createLinearRing(new Coordinate[] {
            new Coordinate(1900, 1900),
            new Coordinate(2100, 1900),
            new Coordinate(2100, 2100),
            new Coordinate(1900, 2100),
            new Coordinate(1900, 1900)
        });
        return f.createPolygon(shell, new org.locationtech.jts.geom.LinearRing[] {hole});
    }

    @Test
    void theZoneMatchesEachModeWithHolesFilled() {
        Geometry ann = squareWithHole();
        double d = 100;
        // whole: the filled 2000 x 2000 square.
        assertThat(AnalysisZone.geometry(ann, "whole", d).getArea()).isEqualTo(4_000_000.0);
        // inside: a 100 px band inside the edge = 2000^2 - 1800^2.
        assertThat(AnalysisZone.geometry(ann, "inside", d).getArea()).isEqualTo(760_000.0);
        // outside: a 100 px band outside it (rounded corners, so a little under 2200^2 - 2000^2).
        double outside = AnalysisZone.geometry(ann, "outside", d).getArea();
        assertThat(outside).isBetween(800_000.0 + Math.PI * d * d * 0.95, 840_000.0);
        double both = AnalysisZone.geometry(ann, "both", d).getArea();
        assertThat(both).isCloseTo(outside + 760_000.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void zoneSamplesFollowAThinBandAndSkipTheInterior() {
        Geometry band = AnalysisZone.geometry(squareWithHole(), "inside", 100);
        int[] read = {800, 800, 2400, 2400};
        List<int[]> boxes = PooledThreshold.zoneBoxes(read, band, 400, 16);
        assertThat(boxes).hasSize(16);
        var f = band.getFactory();
        for (int[] b : boxes) {
            assertThat(b[0]).isGreaterThanOrEqualTo(800);
            assertThat(b[0] + b[2]).isLessThanOrEqualTo(3200);
            assertThat(b[1] + b[3]).isLessThanOrEqualTo(3200);
            var cell = f.toGeometry(new org.locationtech.jts.geom.Envelope(b[0], b[0] + b[2], b[1], b[1] + b[3]));
            assertThat(cell.intersects(band)).isTrue();
        }
        // The interior box at (2000, 2000) never touches an inside band 100 px wide.
        assertThat(boxes).noneMatch(b -> b[0] == 2000 && b[1] == 2000);
    }
}
