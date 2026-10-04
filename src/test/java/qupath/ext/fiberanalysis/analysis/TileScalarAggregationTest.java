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

    /** Aggregate with no area scaling: every tile owns all of itself. */
    private static Map<String, Double> aggregate(List<Map<String, Double>> tiles, List<Integer> weights)
            throws Exception {
        double[] unitScale = new double[tiles.size()];
        java.util.Arrays.fill(unitScale, 1.0);
        List<Integer> identity =
                java.util.stream.IntStream.range(0, tiles.size()).boxed().toList();
        return aggregate(tiles, weights, identity, unitScale);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> aggregate(
            List<Map<String, Double>> tiles, List<Integer> weights, List<Integer> tileIndices, double[] areaScale)
            throws Exception {
        Method m = FiberAnalysisWorkflow.class.getDeclaredMethod(
                "aggregateTileScalars", List.class, List.class, List.class, double[].class);
        m.setAccessible(true);
        return (Map<String, Double>) m.invoke(null, tiles, weights, tileIndices, areaScale);
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

    @Test
    void additiveScalarsAreScaledOntoTheAreaEachTileOwns() throws Exception {
        // Tiles overlap by one window, so each reports a total covering area it
        // shares with its neighbour. Summing raw counts the seam twice; scaling
        // by the owned fraction makes the parts sum to the region.
        Map<String, Double> tile = Map.of("fiber_pixels", 1000.0);
        double[] owned = {0.8, 0.8, 1.0}; // two interior tiles, one edge tile
        Map<String, Double> out = aggregate(List.of(tile, tile, tile), List.of(10, 10, 10), List.of(0, 1, 2), owned);

        assertThat(out.get("fiber_pixels")).isCloseTo(2600.0, within(1e-9)); // not 3000
    }

    @Test
    void areaScaleIsIndexedByGridPositionNotBySurvivorOrder() throws Exception {
        // Tile 1 failed, so the two survivors are grid tiles 0 and 2. Indexing
        // the scale array by survivor order would apply 0.5 to grid tile 2.
        Map<String, Double> tile = Map.of("fiber_pixels", 100.0);
        double[] owned = {1.0, 0.5, 0.25};
        Map<String, Double> out = aggregate(List.of(tile, tile), List.of(10, 10), List.of(0, 2), owned);

        assertThat(out.get("fiber_pixels")).isCloseTo(125.0, within(1e-9)); // 100*1.0 + 100*0.25
    }

    @Test
    void averagedScalarsAreNotTouchedByAreaScaling() throws Exception {
        // A ratio is already normalised; scaling it by owned area would be wrong.
        double[] owned = {0.5, 0.5};
        Map<String, Double> out = aggregate(
                List.of(Map.of("texture.contrast", 10.0), Map.of("texture.contrast", 20.0)),
                List.of(10, 10),
                List.of(0, 1),
                owned);

        assertThat(out.get("texture.contrast")).isCloseTo(15.0, within(1e-9));
    }
}
