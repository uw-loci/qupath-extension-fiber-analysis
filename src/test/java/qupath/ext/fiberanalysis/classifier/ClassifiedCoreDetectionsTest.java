/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.classifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;

/** The core-detection layer: one object per core, classed by argmax. */
class ClassifiedCoreDetectionsTest {

    private static final List<String> TWO_CLASSES = List.of("Dense collagen", "Loose collagen");

    private static ClassifiedCoreDetections.Geometry geom(int gridW, int gridH, int windowPx, int stridePx) {
        return new ClassifiedCoreDetections.Geometry(
                TWO_CLASSES, gridW, gridH, windowPx, stridePx, 0, 0, 1.0, "clf-1", "run-1", false);
    }

    private static float[] filled(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void oneDetectionPerCoreWithTheArgmaxClass() {
        int gridW = 3;
        int gridH = 2;
        int n = gridW * gridH;
        float[] pDense = filled(n, 0.2f);
        float[] pLoose = filled(n, 0.8f);
        pDense[4] = 0.9f;
        pLoose[4] = 0.1f;

        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(gridW, gridH, 100, 25),
                new ClassifiedCoreDetections.Grids(new float[][] {pDense, pLoose}, null, null, Map.of()));

        assertThat(dets).hasSize(n);
        assertThat(dets.stream().map(d -> d.getPathClass().toString()))
                .containsExactly(
                        "Loose collagen",
                        "Loose collagen",
                        "Loose collagen",
                        "Loose collagen",
                        "Dense collagen",
                        "Loose collagen");
    }

    @Test
    void coresTileThePlaneWithNoGapAndNoOverlap() {
        int gridW = 4;
        int gridH = 4;
        int n = gridW * gridH;
        int stridePx = 25;
        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(gridW, gridH, 100, stridePx),
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(n, 0.9f), filled(n, 0.1f)}, null, null, Map.of()));

        // Every core covers stridePx^2, the union covers the full rectangle
        // exactly, and no pixel is covered twice.
        Set<Long> covered = new HashSet<>();
        for (PathObject det : dets) {
            var roi = det.getROI();
            assertThat(roi.getBoundsWidth()).isEqualTo(stridePx);
            assertThat(roi.getBoundsHeight()).isEqualTo(stridePx);
            for (int y = (int) roi.getBoundsY(); y < roi.getBoundsY() + stridePx; y++) {
                for (int x = (int) roi.getBoundsX(); x < roi.getBoundsX() + stridePx; x++) {
                    assertThat(covered.add(((long) y << 32) | x))
                            .as("pixel %d,%d covered twice", x, y)
                            .isTrue();
                }
            }
        }
        assertThat(covered).hasSize(gridW * stridePx * gridH * stridePx);
    }

    @Test
    void theCoreIsCentredInItsWindowNotAnchoredAtTheCorner() {
        // The error worth a test of its own: TileGrid.ownedBoxes() would put
        // the core at the window's top-left, offsetting every label by
        // (window - stride) / 2 from where the measurement was taken.
        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(1, 1, 100, 25),
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(1, 0.9f), filled(1, 0.1f)}, null, null, Map.of()));

        var roi = dets.get(0).getROI();
        assertThat(roi.getBoundsX()).isEqualTo(37); // (100 - 25) / 2, floored
        assertThat(roi.getBoundsY()).isEqualTo(37);
    }

    @Test
    void probabilitiesMaxAndMarginAreAttached() {
        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(1, 1, 100, 25),
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(1, 0.7f), filled(1, 0.3f)}, null, null, Map.of()));

        var ml = dets.get(0).getMeasurementList();
        assertThat(ml.get("P(Dense collagen)")).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ml.get("P(Loose collagen)")).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ml.get("Max probability")).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ml.get("Margin (top1 - top2)")).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void aCellWithNoProbabilityInAnyClassProducesNoDetection() {
        int n = 4;
        float[] pDense = filled(n, 0.9f);
        float[] pLoose = filled(n, 0.1f);
        pDense[2] = Float.NaN;
        pLoose[2] = Float.NaN;

        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(2, 2, 100, 25),
                new ClassifiedCoreDetections.Grids(new float[][] {pDense, pLoose}, null, null, Map.of()));

        assertThat(dets).hasSize(3);
    }

    @Test
    void anAbstainedCoreIsUnclassifiedButKeepsItsNumbers() {
        float[] abstained = {1.0f};
        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(1, 1, 100, 25),
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(1, 0.55f), filled(1, 0.45f)}, new float[] {0.004f}, abstained, Map.of()));

        PathObject det = dets.get(0);
        assertThat(det.getPathClass()).isNull();
        assertThat(det.getMeasurementList().get("Abstained")).isEqualTo(1.0);
        assertThat(det.getMeasurementList().get("P(Dense collagen)"))
                .isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(det.getMeasurementList().get("Novelty p"))
                .isCloseTo(0.004, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void regionOffsetAndDownsampleMoveTheCoreIntoFullImageCoordinates() {
        var g = new ClassifiedCoreDetections.Geometry(
                TWO_CLASSES, 1, 1, 100, 25, 1000, 2000, 4.0, "clf-1", "run-1", false);
        List<PathObject> dets = ClassifiedCoreDetections.create(
                g,
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(1, 0.9f), filled(1, 0.1f)}, null, null, Map.of()));

        var roi = dets.get(0).getROI();
        assertThat(roi.getBoundsX()).isEqualTo(37 * 4 + 1000);
        assertThat(roi.getBoundsY()).isEqualTo(37 * 4 + 2000);
        assertThat(roi.getBoundsWidth()).isEqualTo(25 * 4);
    }

    @Test
    void extraFeatureGridsBecomeMeasurementColumns() {
        List<PathObject> dets = ClassifiedCoreDetections.create(
                geom(1, 1, 100, 25),
                new ClassifiedCoreDetections.Grids(
                        new float[][] {filled(1, 0.9f), filled(1, 0.1f)},
                        null,
                        null,
                        Map.of("Order parameter", new float[] {0.81f})));

        assertThat(dets.get(0).getMeasurementList().get("Order parameter"))
                .isCloseTo(0.81, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void aProvisionalRunStampsEveryObject() {
        var g = new ClassifiedCoreDetections.Geometry(TWO_CLASSES, 1, 1, 100, 25, 0, 0, 1.0, "clf-1", "run-1", true);
        PathObject det = ClassifiedCoreDetections.create(
                        g,
                        new ClassifiedCoreDetections.Grids(
                                new float[][] {filled(1, 0.9f), filled(1, 0.1f)}, null, null, Map.of()))
                .get(0);

        assertThat(det.getMeasurementList().get("Provisional")).isEqualTo(1.0);
        assertThat(det.getMetadata()).containsEntry("fiber_analysis.provisional", "true");
        assertThat(det.getMetadata()).containsEntry("fiber_analysis.classifier_id", "clf-1");
    }

    @Test
    void aGridOfTheWrongLengthIsRefusedRatherThanSilentlyTruncated() {
        assertThatThrownBy(() -> ClassifiedCoreDetections.create(
                        geom(2, 2, 100, 25),
                        new ClassifiedCoreDetections.Grids(
                                new float[][] {filled(4, 0.9f), filled(3, 0.1f)}, null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P(Loose collagen)");
    }

    @Test
    void theDetectionBudgetCountsCoresNotWindowsOfTissue() {
        // 87.5% overlap on a 100 px window is a 12 px stride, so a 10k x 10k
        // region is ~694k cores -- over budget, and the reason the guard
        // exists at all.
        assertThat(ClassifiedCoreDetections.estimateCount(833, 833)).isEqualTo(693889L);
        assertThat(ClassifiedCoreDetections.exceedsBudget(833, 833, ClassifiedCoreDetections.DEFAULT_MAX_DETECTIONS))
                .isTrue();
        assertThat(ClassifiedCoreDetections.exceedsBudget(100, 100, ClassifiedCoreDetections.DEFAULT_MAX_DETECTIONS))
                .isFalse();
        assertThat(ClassifiedCoreDetections.budgetMessage(833, 833, 500_000))
                .contains("693,889")
                .contains("raster-only");
    }
}
