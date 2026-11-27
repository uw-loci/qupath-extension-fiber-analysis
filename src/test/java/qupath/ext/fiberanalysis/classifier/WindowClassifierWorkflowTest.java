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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** The workflow's checks and bookkeeping around a train or apply run. */
class WindowClassifierWorkflowTest {

    private static SegmentationSpec spec(String method, Double norm) {
        return new SegmentationSpec("Raw intensity", method, 128, 0.1, norm, "none", 1.0, 4.0, 0.5, 2.0, false, 0);
    }

    @Test
    void anExistingFiberMaskIsRefusedBecauseTheClassifierCannotUseIt() {
        String msg = WindowClassifierWorkflow.runSettingsRefusal("existing", spec("otsu", null), null);
        assertThat(msg).contains("existing fiber mask", "own segmentation");
        assertThat(WindowClassifierWorkflow.runSettingsRefusal("internal", spec("otsu", null), null))
                .isNull();
    }

    @Test
    void aCalibratedThresholdWithNoValueIsRefusedInsteadOfFallingBackToPerTileOtsu() {
        assertThat(WindowClassifierWorkflow.runSettingsRefusal("internal", spec("project_otsu", null), "cal-1"))
                .contains("'cal-1' could not be loaded", "Calibrate threshold");
        assertThat(WindowClassifierWorkflow.runSettingsRefusal("internal", spec("project_otsu", null), null))
                .contains("no calibration is selected");
        assertThat(WindowClassifierWorkflow.runSettingsRefusal("internal", spec("project_otsu", 0.21), "cal-1"))
                .isNull();
    }

    @Test
    void theIgnoredRunSettingsAreNamedWithTheUsersOwnValues() {
        String plain = WindowClassifierWorkflow.ignoredRunSettingsNote(1.0, true, 0.5);
        assertThat(plain).contains("analysis downsample", "pixel-size override");
        assertThat(plain).doesNotContain("Your");
        String custom = WindowClassifierWorkflow.ignoredRunSettingsNote(2.0, false, 0.325);
        assertThat(custom).contains("downsample of 2", "override of 0.325 um");
    }

    @Test
    void radiiThatAreNotWholeNumbersAboveZeroAreReportedNotDropped() {
        var parsed = WindowClassifierWorkflow.parseRadii(" 1, 3 ,x,0,2.5,,-1");
        assertThat(parsed.radii()).containsExactly(1, 3);
        assertThat(parsed.rejected()).containsExactly("x", "0", "2.5", "-1");
        assertThat(parsed.csv()).isEqualTo("1,3");
        assertThat(WindowClassifierWorkflow.parseRadii("").rejected()).isEmpty();
    }

    @Test
    void overridesAreRecordedInTheMapProvenance() {
        var warnings = List.of(
                "novelty could not be computed: x",
                "OVERRIDDEN: stride 30 differs from the trained 25; the map resolution will differ");
        var overrides = WindowClassifierWorkflow.overrides(warnings);
        assertThat(overrides).containsExactly("stride 30 differs from the trained 25; the map resolution will differ");
        String line = WindowClassifierWorkflow.provenance("colon-v1", "REFUSE", true, overrides);
        assertThat(line).contains("colon-v1", "REFUSE", "PROVISIONAL", "applied despite", "stride 30");
        assertThat(WindowClassifierWorkflow.provenance("m", "OK", false, List.of()))
                .isEqualTo("model m; training verdict OK");
    }

    @Test
    void aSelectedAnnotationRunCoversTheOutlineNotItsBoundingBox() {
        int gridW = 4;
        int gridH = 4;
        int n = gridW * gridH;
        var features = new WindowClassifierWorkflow.ImageFeatures(
                List.of("f"), new float[n], gridW, gridH, 50, 50, new boolean[n]);
        float[] p = new float[n];
        Arrays.fill(p, 0.8f);
        float[] q = new float[n];
        Arrays.fill(q, 0.2f);
        var result = new WindowClassifierWorkflow.ApplyResult(
                List.of("A", "B"),
                new float[][] {p, q},
                p.clone(),
                p.clone(),
                new float[n],
                new float[n],
                "OK",
                List.of(),
                features);
        // A circle inscribed in the 200 x 200 region at (1000, 2000): the four
        // corner cores (centers 106 px from the middle) fall outside it.
        var roi = ROIs.createEllipseROI(1000, 2000, 200, 200, ImagePlane.getDefaultPlane());
        var region = RegionRequest.createInstance("img", 1.0, 1000, 2000, 200, 200);

        var clipped = WindowClassifierWorkflow.clipToRoi(result, roi, region);
        for (int i : new int[] {0, 3, 12, 15}) {
            assertThat(clipped.probability()[0][i]).isNaN();
            assertThat(clipped.maxProba()[i]).isNaN();
        }
        assertThat(clipped.probability()[0][5]).isEqualTo(0.8f);
        assertThat(result.probability()[0][0]).isEqualTo(0.8f); // input untouched

        List<PathObject> dets =
                WindowClassifierWorkflow.coreDetections(clipped, region, "m", "r", false, List.of(), List.of());
        assertThat(dets).hasSize(12);
        assertThat(dets)
                .allMatch(
                        d -> roi.contains(d.getROI().getCentroidX(), d.getROI().getCentroidY()));
    }
}
