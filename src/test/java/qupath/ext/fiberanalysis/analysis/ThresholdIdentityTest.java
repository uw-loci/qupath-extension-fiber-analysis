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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One line says which threshold a result was cut at and at what pixel size. Two results compare
 * when the cut and settings match and the pixel sizes are within 2%, however the cut was obtained.
 */
class ThresholdIdentityTest {

    private static ThresholdIdentity otsu(double cut, String source, String ridge, double rb, double px) {
        return ThresholdIdentity.of("Otsu", 128, 0.1, cut, source, "Raw intensity", ridge, 1, 4, 1, false, rb, 1, px);
    }

    private static ThresholdIdentity otsu(double cut, String source) {
        return otsu(cut, source, "None", 0, 0.1732);
    }

    @Test
    void theLabelReadsAndParsesBack() {
        var id = otsu(0.0833740234375, "pooled over 2 annotation(s) on 1 image(s), run 20261021_101500");
        assertThat(id.label()).startsWith("Otsu 0.08337 | pooled over 2 annotation(s)");
        assertThat(id.label()).endsWith(" at 0.1732 um/px");
        var back = ThresholdIdentity.parse(id.label());
        assertThat(back).isNotNull();
        assertThat(back.key()).isEqualTo(id.key());
        assertThat(back.pixelUm()).isEqualTo(0.1732);
        assertThat(back.source()).isEqualTo(id.source());
        assertThat(back.comparableWith(id)).isTrue();
        // A line without a pixel size (a spec not tied to one image) still parses.
        var noPx = otsu(0.08337, "x", "None", 0, Double.NaN);
        assertThat(noPx.label()).doesNotContain("um/px");
        assertThat(ThresholdIdentity.parse(noPx.label()).comparableWith(id)).isTrue();
    }

    @Test
    void theSameCutObtainedTwoWaysIsComparable() {
        var pooled = otsu(0.08337, "pooled, run A");
        var calibrated = ThresholdIdentity.of(
                "Project Otsu (calibrated)",
                128,
                0.1,
                0.08337,
                "calibration 'x'",
                "Raw intensity",
                "None",
                1,
                4,
                1,
                false,
                0,
                1,
                0.1732);
        assertThat(calibrated.method()).isEqualTo("Calibrated");
        assertThat(calibrated.comparableWith(pooled)).isTrue();
    }

    @Test
    void otherSettingsAnotherCutOrAnotherPixelSizeAreNotComparable() {
        var a = otsu(0.08337, "run A");
        assertThat(otsu(0.0763, "run B").comparableWith(a)).isFalse();
        assertThat(otsu(0.08337, "run B", "None", 10, 0.1732).comparableWith(a)).isFalse();
        assertThat(otsu(0.08337, "run B", "None", 0, 0.3464).comparableWith(a)).isFalse();
        // Within 2% is the same pixel size.
        assertThat(otsu(0.08337, "run B", "None", 0, 0.175).comparableWith(a)).isTrue();
        var sato = otsu(0.036, "run C", "Sato", 0, 0.1732);
        assertThat(sato.unit()).isEqualTo("response");
        assertThat(sato.cut()).isEqualTo("Otsu response 0.03600");
        // Sigmas only matter with a ridge filter.
        assertThat(ThresholdIdentity.of(
                                "Otsu",
                                128,
                                0.1,
                                0.08337,
                                "run A",
                                "Raw intensity",
                                "None",
                                2,
                                8,
                                2,
                                false,
                                0,
                                1,
                                0.1732)
                        .comparableWith(a))
                .isTrue();
    }

    @Test
    void manualCutsReadInTheirOwnUnits() {
        var gray =
                ThresholdIdentity.of("Manual", 30, 0.1, null, null, "Raw intensity", "None", 1, 4, 1, false, 0, 1, 0.5);
        assertThat(gray.label()).startsWith("Manual gray level 30 | set by hand | settings ");
        var resp = ThresholdIdentity.of(
                "Manual", 30, 0.25, null, null, "Raw intensity", "Sato", 1, 4, 1, false, 0, 1, 0.5);
        assertThat(resp.cut()).isEqualTo("Manual response 0.2500");
        assertThat(ThresholdIdentity.parse(gray.label()).comparableWith(gray)).isTrue();
    }

    @Test
    void theWarningNamesEachGroupAndMarksThisRun() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("img1", otsu(0.0833740234375, "batch A").label());
        labels.put("img2", otsu(0.0833740234375, "batch A").label());
        assertThat(ThresholdIdentity.incomparableWarning(labels, List.of("img1"), true))
                .isNull();
        labels.put("old1", otsu(0.0763, "run B").label());
        labels.put("old2", otsu(0.0763, "run B").label());
        labels.put("coarse", otsu(0.0833740234375, "batch A", "None", 0, 0.3464).label());
        String w = ThresholdIdentity.incomparableWarning(labels, List.of("img1", "img2"), true);
        // The notification form drops how each cut was obtained, so it fits on screen.
        String brief = ThresholdIdentity.incomparableWarning(labels, List.of("img1", "img2"), false);
        assertThat(brief)
                .doesNotContain("(run B)")
                .contains("[this run]: img1, img2")
                .endsWith("Details are in the log.");
        assertThat(w).startsWith("Results in this project were cut at 3 different thresholds or pixel sizes");
        assertThat(w).contains("Otsu 0.08337 at 0.1732 um/px").contains("[this run]: img1, img2");
        assertThat(w).contains("Otsu 0.07630 at 0.1732 um/px").contains("(run B): old1, old2");
        assertThat(w).contains("at 0.3464 um/px").contains(": coarse");
    }

    @Test
    void groupsPutTheLargestFirst() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("a", otsu(0.1, "x").label());
        m.put("b", otsu(0.2, "y").label());
        m.put("c", otsu(0.2, "z").label());
        var g = ThresholdIdentity.groups(m);
        assertThat(g.values().iterator().next()).isEqualTo(List.of("b", "c"));
    }

    @Test
    void theMapFileCarriesTheThreshold() {
        var id = otsu(0.08337, "pooled over 3 image(s), density run 20261021_1015");
        var info = new DensityMapInfo(
                DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", id.label(), null, List.of());
        String desc = SidecarDescription.format(info, DensityChannelSpec.defaultChannels());
        assertThat(desc).contains("\nThreshold: Otsu 0.08337 | pooled over 3 image(s)");
        assertThat(SidecarDescription.parseInfo(desc).threshold()).isEqualTo(id.label());
        var old = new DensityMapInfo(DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", null, null, List.of());
        assertThat(SidecarDescription.parseInfo(SidecarDescription.format(old, DensityChannelSpec.defaultChannels()))
                        .threshold())
                .isNull();
    }
}
