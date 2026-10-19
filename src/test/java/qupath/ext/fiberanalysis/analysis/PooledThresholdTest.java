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
    void everyTileGetsTheSameCutAndTheRecordSaysWhereItCameFrom() {
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                25,
                50,
                "Raw intensity",
                "Otsu",
                128,
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
        var pooled = spec.withPooledThreshold(0.123, "Otsu pooled over 3 image(s)");
        assertThat(pooled.thresholdMethod).isEqualTo("project_otsu");
        assertThat(pooled.projectThresholdNorm).isEqualTo(0.123);
        assertThat(pooled.toOrderedMap()).containsEntry("thresholdSource", "Otsu pooled over 3 image(s)");
        // Everything else is carried over untouched.
        var a = new java.util.LinkedHashMap<>(spec.toOrderedMap());
        var b = new java.util.LinkedHashMap<>(pooled.toOrderedMap());
        for (String k : List.of("thresholdMethod", "projectThresholdNorm", "thresholdSource")) {
            a.remove(k);
            b.remove(k);
        }
        assertThat(b).isEqualTo(a);
    }
}
