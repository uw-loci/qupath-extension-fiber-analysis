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
                "fiber_pixels", 400.0,
                "zone_area_um2", 250.0);
        Map<String, Double> out = aggregate(List.of(tile, tile, tile, tile), List.of(10, 10, 10, 10));

        assertThat(out.get("morphometrics.total_length_um")).isEqualTo(4000.0);
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
    void aTileOwningNoWindowStillAddsItsPixelsButNotItsAverages() throws Exception {
        // A tile owns pixels whether or not a window centre falls in it. Skipping
        // such tiles made a real 42-tile run report half the zone.
        Map<String, Double> out = aggregate(
                List.of(
                        Map.of("zone_pixels", 100.0, "texture.contrast", 1.0),
                        Map.of("zone_pixels", 300.0, "texture.contrast", 9.0)),
                List.of(5, 0));
        assertThat(out.get("zone_pixels")).isEqualTo(400.0);
        assertThat(out.get("texture.contrast")).isEqualTo(1.0);
    }

    @Test
    void coverageAndHdmComeFromTheSummedPixels() throws Exception {
        // Tile A: 10 of 100 zone px are fiber; tile B: 90 of 300. Window-weighted
        // averaging of the per-tile ratios (0.1 and 0.3, weights 1 and 9) would
        // give 0.28; the annotation's ratio is 100 / 400 = 0.25.
        Map<String, Double> a = Map.of(
                "zone_pixels", 100.0,
                "fiber_in_zone_pixels", 10.0,
                "fiber_pixels", 20.0,
                "region_pixels", 1000.0,
                "morphometrics.hdm_coverage", 0.1,
                "fiber_coverage_zone_percent", 10.0);
        Map<String, Double> b = Map.of(
                "zone_pixels", 300.0,
                "fiber_in_zone_pixels", 90.0,
                "fiber_pixels", 180.0,
                "region_pixels", 1000.0,
                "morphometrics.hdm_coverage", 0.3,
                "fiber_coverage_zone_percent", 30.0);
        Map<String, Double> out = aggregate(List.of(a, b), List.of(1, 9));
        assertThat(out.get("morphometrics.hdm_coverage")).isCloseTo(0.25, within(1e-12));
        assertThat(out.get("fiber_coverage_zone_percent")).isCloseTo(25.0, within(1e-9));
        assertThat(out.get("fiber_coverage_region_percent")).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void gapWidthsCombineByGapCountAndMaximum() throws Exception {
        Map<String, Double> a = Map.of(
                "morphometrics.gap_mean_um", 2.0, "morphometrics.gap_max_um", 10.0, "morphometrics.gap_count", 30.0);
        Map<String, Double> b = Map.of(
                "morphometrics.gap_mean_um", 6.0, "morphometrics.gap_max_um", 40.0, "morphometrics.gap_count", 10.0);
        // A tile with no gap reports NaN, which must not pull the mean anywhere.
        Map<String, Double> c = Map.of(
                "morphometrics.gap_mean_um", Double.NaN,
                "morphometrics.gap_max_um", Double.NaN,
                "morphometrics.gap_count", 0.0);
        Map<String, Double> out = aggregate(List.of(a, b, c), List.of(1, 50, 5));
        assertThat(out.get("morphometrics.gap_mean_um")).isCloseTo(3.0, within(1e-12)); // (2*30 + 6*10) / 40
        assertThat(out.get("morphometrics.gap_max_um")).isEqualTo(40.0);
        assertThat(out.get("morphometrics.gap_count")).isEqualTo(40.0);
    }

    @Test
    void skeletonTopologyCountsAreOmittedNotSummed() throws Exception {
        // Cutting a fiber at a tile seam invents an endpoint on each side and
        // can destroy a branch point, so these do not add up across tiles.
        // Measured on a real 9-tile run: endpoints +70%, branch points -100%.
        Map<String, Double> tile = Map.of("morphometrics.branch_points", 50.0, "morphometrics.endpoints", 20.0);
        Map<String, Double> out = aggregate(List.of(tile, tile, tile), List.of(10, 10, 10));

        assertThat(out).doesNotContainKey("morphometrics.branch_points");
        assertThat(out).doesNotContainKey("morphometrics.endpoints");
    }
}
