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
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;

/** What the calibration dialog and the preview tell the user about a calibration. */
class CalibrationDialogTest {

    private static ImageServerMetadata fluorescence(String... channels) {
        return new ImageServerMetadata.Builder()
                .width(10)
                .height(10)
                .rgb(false)
                .pixelType(PixelType.UINT16)
                .channels(java.util.Arrays.stream(channels)
                        .map(c -> ImageChannel.getInstance(c, 0xFFFFFF))
                        .toList())
                .build();
    }

    @Test
    void theSourceChannelChoicesAreTheOnesEveryCalibratedImageOffers() {
        List<String> choices =
                SourceChannel.commonChoices(List.of(fluorescence("DAPI", "SHG", "CD31"), fluorescence("SHG", "DAPI")));
        assertThat(choices).containsExactly("Raw intensity", "Value (HSV)", "DAPI", "SHG");
        // One image alone gets what the Run dialog offers for it.
        assertThat(SourceChannel.commonChoices(List.of(fluorescence("DAPI", "SHG"))))
                .isEqualTo(SourceChannel.choices(fluorescence("DAPI", "SHG")));
    }

    @Test
    void theDialogShowsTheSettingsItTakesFromTheRun() {
        FiberAnalysisParams p = TestParams.with(Map.of(
                "zoneMode", "both",
                "analysisDownsample", 4.0,
                "sigmaMinUm", 0.5,
                "sigmaMaxUm", 3.0,
                "sigmaStepUm", 0.5,
                "useImagePixelSize", false,
                "pixelSizeOverrideUm", 0.4));
        assertThat(FiberCalibrationDialog.describeFixedSettings(p))
                .contains("Zone mode: both", "Analysis downsample: 4", "Ridge sigmas: 0.5-3 um, step 0.5 um")
                .contains("0.4 um/px for every image");
    }

    @Test
    void thePreviewSaysTheRunWillRefuseAMismatchedCalibration() {
        assertThat(FiberSegmentationPreviewWindow.calibrationNote("a", null))
                .endsWith("The run cuts at the same value.");
        String note =
                FiberSegmentationPreviewWindow.calibrationNote("a", "Calibration 'a' was fitted with zone mode x.");
        assertThat(note).contains("the run will refuse").doesNotContain("cuts at the same value");
    }
}
