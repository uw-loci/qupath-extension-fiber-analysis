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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A saved threshold is a cut on one particular image; settings that change the image make it not apply. */
class CalibrationMismatchTest {

    private static void write(Path proj, String name, String params) throws Exception {
        Path dir = Files.createDirectories(proj.resolve("fiber-analysis"));
        Files.writeString(
                dir.resolve("calibration_" + name + ".json"),
                "{\"threshold_normalised\": 0.08, \"calibration_params\": {" + params + "}}");
    }

    private static final String SATO = "\"seg_channel\": \"Raw intensity\", \"ridge_filter\": \"Sato\","
            + " \"invert_intensity\": false, \"rolling_ball_radius_um\": 0.0, \"sigma_min_um\": 1.0,"
            + " \"sigma_max_um\": 4.0, \"sigma_step_um\": 1.0";

    /** What 0.3.0 wrote: everything, at downsample 2 on 0.25 um/px images, zone outside 50 um. */
    private static final String FULL = SATO + ", \"response_scale\": \"absolute\", \"pixel_sizes_um\": [0.25],"
            + " \"analysis_downsample\": 2.0, \"border_zone_um\": 50.0, \"zone_mode\": \"outside\"";

    private static CalibrationCheck.Target segmentation(String channel, String ridge, boolean invert) {
        return CalibrationCheck.Target.segmentation(channel, ridge, invert, 0.0, 1.0, 4.0, 1.0);
    }

    private static CalibrationCheck.Target run(String zone, double border, double ds, double analyzedPx) {
        return new CalibrationCheck.Target(
                "Raw intensity", "Sato", false, 0.0, 1.0, 4.0, 1.0, zone, border, ds, analyzedPx);
    }

    @Test
    void matchingSettingsApply(@TempDir Path proj) throws Exception {
        write(proj, "a", SATO + ", \"response_scale\": \"absolute\"");
        assertThat(CalibrationCheck.mismatch("a", proj, segmentation("Raw intensity", "Sato", false)))
                .isNull();
    }

    @Test
    void aDifferentChannelOrFilterIsNamed(@TempDir Path proj) throws Exception {
        write(proj, "a", SATO + ", \"response_scale\": \"absolute\"");
        String msg = CalibrationCheck.mismatch("a", proj, segmentation("Saturation (HSV)", "None", true));
        assertThat(msg).contains("source channel Raw intensity", "ridge filter Sato", "intensity not inverted");
    }

    @Test
    void valueAndRawIntensityAreTheSameChannel(@TempDir Path proj) throws Exception {
        write(proj, "a", SATO + ", \"response_scale\": \"absolute\"");
        assertThat(CalibrationCheck.mismatch("a", proj, segmentation("Value (HSV)", "Sato", false)))
                .isNull();
    }

    @Test
    void aRidgeCalibrationFromBeforeTheFixedScaleDoesNotApply(@TempDir Path proj) throws Exception {
        write(proj, "old", SATO);
        assertThat(CalibrationCheck.mismatch("old", proj, segmentation("Raw intensity", "Sato", false)))
                .contains("before ridge responses had a fixed scale");
    }

    @Test
    void aRunWithTheCalibrationsZonesAndScaleApplies(@TempDir Path proj) throws Exception {
        write(proj, "a", FULL);
        assertThat(CalibrationCheck.mismatch("a", proj, run("outside", 50.0, 2.0, 0.5)))
                .isNull();
        // Within rounding of the analyzed pixel size.
        assertThat(CalibrationCheck.mismatch("a", proj, run("outside", 50.0, 2.0, 0.505)))
                .isNull();
    }

    @Test
    void anotherZoneModeOrWidthIsNamed(@TempDir Path proj) throws Exception {
        write(proj, "a", FULL);
        assertThat(CalibrationCheck.mismatch("a", proj, run("inside", 50.0, 2.0, 0.5)))
                .contains("zone mode outside (this run: inside)");
        assertThat(CalibrationCheck.mismatch("a", proj, run("outside", 30.0, 2.0, 0.5)))
                .contains("border zone 50 um (this run: 30 um)");
    }

    @Test
    void theWidthDoesNotMatterWhenBothUseTheWholeAnnotation(@TempDir Path proj) throws Exception {
        write(proj, "a", FULL.replace("\"outside\"", "\"whole\""));
        assertThat(CalibrationCheck.mismatch("a", proj, run("whole", 30.0, 2.0, 0.5)))
                .isNull();
    }

    @Test
    void anotherDownsampleOrPixelSizeIsNamed(@TempDir Path proj) throws Exception {
        write(proj, "a", FULL);
        assertThat(CalibrationCheck.mismatch("a", proj, run("outside", 50.0, 1.0, 0.25)))
                .contains("analysis downsample 2 (this run: 1)", "pixel size 0.5 um/px (this run: 0.25 um/px)");
        // Same downsample on an image of another pixel size.
        assertThat(CalibrationCheck.mismatch("a", proj, run("outside", 50.0, 2.0, 1.0)))
                .contains("pixel size 0.5 um/px (this run: 1 um/px)")
                .doesNotContain("analysis downsample");
    }

    @Test
    void aCommonPixelSizeCalibrationComparesTheAnalyzedSizeOnly(@TempDir Path proj) throws Exception {
        write(
                proj,
                "c",
                SATO + ", \"response_scale\": \"absolute\", \"pixel_sizes_um\": [0.25, 0.5],"
                        + " \"analysis_downsample\": 1.0, \"common_pixel_um\": 0.5, \"border_zone_um\": 50.0,"
                        + " \"zone_mode\": \"outside\"");
        // A batch brings the 0.25 um/px image to 0.5 with downsample 2: that is how it was fitted.
        assertThat(CalibrationCheck.mismatch("c", proj, run("outside", 50.0, 2.0, 0.5)))
                .isNull();
        assertThat(CalibrationCheck.mismatch("c", proj, run("outside", 50.0, 1.0, 0.25)))
                .contains("a common pixel size of 0.5 um/px (this run: 0.25 um/px)");
    }

    @Test
    void anOlderCalibrationThatDoesNotRecordTheZonesIsUnknownNotPassed(@TempDir Path proj) throws Exception {
        // The shape 0.1.x wrote: no zone mode, downsample, pixel sizes or response scale.
        write(
                proj,
                "old",
                "\"seg_channel\": \"Raw intensity\", \"ridge_filter\": \"None\", \"invert_intensity\": false,"
                        + " \"rolling_ball_radius_um\": 0.0, \"sigma_min_um\": 1.0, \"sigma_max_um\": 4.0,"
                        + " \"sigma_step_um\": 1.0, \"median_pixel_size_um_used\": 0.25, \"border_zone_um\": 50.0");
        String msg = CalibrationCheck.mismatch(
                "old",
                proj,
                new CalibrationCheck.Target(
                        "Raw intensity", "None", false, 0.0, 1.0, 4.0, 1.0, "outside", 50.0, 1.0, 0.25));
        assertThat(msg)
                .contains("does not record its zone mode, analysis downsample, pixel size")
                .contains("cannot be checked against this run")
                .doesNotContain("border zone");
        // The density map checks the scalar image only, so the same file still applies there.
        assertThat(CalibrationCheck.mismatch("old", proj, segmentation("Raw intensity", "None", false)))
                .isNull();
    }

    @Test
    void theRunTargetTakesZoneScaleAndPixelSizeFromTheParameters() {
        FiberAnalysisParams p = TestParams.with(java.util.Map.of(
                "zoneMode", "inside", "borderZoneUm", 25.0, "analysisDownsample", 0.5, "internalChannel", "DAPI"));
        CalibrationCheck.Target t = CalibrationCheck.Target.of(p, 0.3);
        assertThat(t.zoneMode()).isEqualTo("inside");
        assertThat(t.borderZoneUm()).isEqualTo(25.0);
        assertThat(t.analysisDownsample()).isEqualTo(1.0);
        assertThat(t.analyzedPixelUm()).isEqualTo(0.3);
        assertThat(t.channel()).isEqualTo("DAPI");
    }
}
