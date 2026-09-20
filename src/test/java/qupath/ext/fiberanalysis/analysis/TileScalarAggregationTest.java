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
import static org.assertj.core.api.Assertions.within;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A tiled run must not report an additive quantity as an average. Averaging
 * total skeleton length over 112 tiles returned about 1/112 of the real value,
 * with nothing in the output to show it had happened.
 */
class TileScalarAggregationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Double> aggregate(List<Map<String, Double>> tiles, List<Integer> weights)
            throws Exception {
        Method m = FiberAnalysisWorkflow.class.getDeclaredMethod("aggregateTileScalars", List.class, List.class);
        m.setAccessible(true);
        return (Map<String, Double>) m.invoke(null, tiles, weights);
    }

    @Test
    void additiveScalarsAreSummedNotAveraged() throws Exception {
        Map<String, Double> tile = Map.of(
                "morphometrics.total_length_um", 1000.0,
                "morphometrics.branch_points", 50.0,
                "morphometrics.endpoints", 20.0,
                "fiber_pixels", 400.0,
                "zone_area_um2", 250.0);
        Map<String, Double> out = aggregate(List.of(tile, tile, tile, tile), List.of(10, 10, 10, 10));

        assertThat(out.get("morphometrics.total_length_um")).isEqualTo(4000.0);
        assertThat(out.get("morphometrics.branch_points")).isEqualTo(200.0);
        assertThat(out.get("morphometrics.endpoints")).isEqualTo(80.0);
        assertThat(out.get("fiber_pixels")).isEqualTo(1600.0);
        assertThat(out.get("zone_area_um2")).isEqualTo(1000.0);
    }

    @Test
    void axialAnglesAreOmittedRatherThanAveragedAcrossTheWrap() throws Exception {
        // 179 deg and 1 deg are nearly the same axis; the arithmetic mean is 90,
        // which is perpendicular to both. Absent beats confidently wrong.
        Map<String, Double> out = aggregate(
                List.of(
                        Map.of("straightness.radon.theta_star_deg", 179.0, "mean_angle_deg", 179.0),
                        Map.of("straightness.radon.theta_star_deg", 1.0, "mean_angle_deg", 1.0)),
                List.of(5, 5));
        assertThat(out).doesNotContainKey("straightness.radon.theta_star_deg");
        assertThat(out).doesNotContainKey("mean_angle_deg");
    }

    @Test
    void fractalDimensionIsOmittedBecauseItIsNotAnAverage() throws Exception {
        Map<String, Double> out = aggregate(
                List.of(Map.of("morphometrics.fractal_dimension", 1.7), Map.of("morphometrics.fractal_dimension", 1.5)),
                List.of(5, 5));
        assertThat(out).doesNotContainKey("morphometrics.fractal_dimension");
    }

    @Test
    void ratiosStayWindowWeightedMeans() throws Exception {
        // A GLCM property is already a per-window mean, so pooling by window
        // count is the correct combination.
        Map<String, Double> out =
                aggregate(List.of(Map.of("texture.contrast", 10.0), Map.of("texture.contrast", 20.0)), List.of(30, 10));
        assertThat(out.get("texture.contrast")).isCloseTo(12.5, within(1e-9)); // (10*30 + 20*10)/40
    }

    @Test
    void zeroWeightTilesDoNotContribute() throws Exception {
        Map<String, Double> out =
                aggregate(List.of(Map.of("fiber_pixels", 100.0), Map.of("fiber_pixels", 999.0)), List.of(5, 0));
        assertThat(out.get("fiber_pixels")).isEqualTo(100.0);
    }
}
