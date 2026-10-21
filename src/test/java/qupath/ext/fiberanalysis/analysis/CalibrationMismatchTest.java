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

    @Test
    void matchingSettingsApply(@TempDir Path proj) throws Exception {
        write(proj, "a", SATO + ", \"response_scale\": \"absolute\"");
        assertThat(FiberAnalysisWorkflow.calibrationMismatch(
                        "a", proj, "Raw intensity", "Sato", false, 0.0, 1.0, 4.0, 1.0))
                .isNull();
    }

    @Test
    void aDifferentChannelOrFilterIsNamed(@TempDir Path proj) throws Exception {
        write(proj, "a", SATO + ", \"response_scale\": \"absolute\"");
        String msg =
                FiberAnalysisWorkflow.calibrationMismatch("a", proj, "Value (HSV)", "None", true, 0.0, 1.0, 4.0, 1.0);
        assertThat(msg).contains("source channel Raw intensity", "ridge filter Sato", "intensity not inverted");
    }

    @Test
    void aRidgeCalibrationFromBeforeTheFixedScaleDoesNotApply(@TempDir Path proj) throws Exception {
        write(proj, "old", SATO);
        assertThat(FiberAnalysisWorkflow.calibrationMismatch(
                        "old", proj, "Raw intensity", "Sato", false, 0.0, 1.0, 4.0, 1.0))
                .contains("before ridge responses had a fixed scale");
    }
}
