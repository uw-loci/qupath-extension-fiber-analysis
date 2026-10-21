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
 * One line says which threshold a result was cut at. Two results are comparable when the cut and
 * the segmentation settings match, however the cut was obtained.
 */
class ThresholdIdentityTest {

    private static ThresholdIdentity otsu(double cut, String source, String ridge, double rb) {
        return ThresholdIdentity.of("Otsu", 128, 0.1, cut, source, "Raw intensity", ridge, 1, 4, 1, false, rb, 1, 1);
    }

    @Test
    void theLabelReadsAndParsesBack() {
        var id = otsu(0.0833740234375, "pooled over 2 annotation(s) on 1 image(s), run 20261021_101500", "None", 0);
        assertThat(id.label()).startsWith("Otsu 0.08337 | pooled over 2 annotation(s)");
        assertThat(id.label()).contains("| settings ");
        var back = ThresholdIdentity.parse(id.label());
        assertThat(back).isNotNull();
        assertThat(back.key()).isEqualTo(id.key());
        assertThat(back.settings()).isEqualTo(id.settings());
        assertThat(back.source()).isEqualTo(id.source());
    }

    @Test
    void theSameCutObtainedTwoWaysIsComparable() {
        var pooled = otsu(0.08337, "pooled, run A", "None", 0);
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
                1);
        assertThat(calibrated.method()).isEqualTo("Calibrated");
        assertThat(calibrated.key()).isEqualTo(pooled.key());
    }

    @Test
    void otherSettingsOrAnotherCutAreNotComparable() {
        var a = otsu(0.08337, "run A", "None", 0);
        assertThat(otsu(0.0763, "run B", "None", 0).key()).isNotEqualTo(a.key());
        assertThat(otsu(0.08337, "run B", "None", 10).key()).isNotEqualTo(a.key());
        // Sigmas only matter with a ridge filter.
        var sato = otsu(0.036, "run C", "Sato", 0);
        assertThat(sato.unit()).isEqualTo("response");
        assertThat(sato.cut()).isEqualTo("Otsu response 0.03600");
        assertThat(ThresholdIdentity.of(
                                "Otsu", 128, 0.1, 0.08337, "run A", "Raw intensity", "None", 2, 8, 2, false, 0, 1, 1)
                        .key())
                .isEqualTo(a.key());
    }

    @Test
    void manualCutsReadInTheirOwnUnits() {
        var gray =
                ThresholdIdentity.of("Manual", 30, 0.1, null, null, "Raw intensity", "None", 1, 4, 1, false, 0, 1, 1);
        assertThat(gray.label()).startsWith("Manual gray level 30 | set by hand | settings ");
        var resp =
                ThresholdIdentity.of("Manual", 30, 0.25, null, null, "Raw intensity", "Sato", 1, 4, 1, false, 0, 1, 1);
        assertThat(resp.cut()).isEqualTo("Manual response 0.2500");
        assertThat(ThresholdIdentity.parse(gray.label()).key()).isEqualTo(gray.key());
    }

    @Test
    void theWarningNamesTheImagesCutDifferently() {
        var mine = otsu(0.0833740234375, "batch A", "None", 0);
        Map<String, String> others = new LinkedHashMap<>();
        others.put("img1", otsu(0.0833740234375, "batch A", "None", 0).label());
        assertThat(ThresholdIdentity.mismatchWarning(mine, others)).isNull();
        others.put("img2", otsu(0.0763, "run B", "None", 0).label());
        others.put("img3", otsu(0.0763, "run B", "None", 0).label());
        others.put("img4", otsu(0.08337, "run C", "None", 10).label());
        String w = ThresholdIdentity.mismatchWarning(mine, others);
        assertThat(w).startsWith("3 other image(s)");
        assertThat(w).contains("Otsu 0.07630 (run B): img2, img3");
        assertThat(w).contains("with other segmentation settings: img4");
        assertThat(w).doesNotContain("img1");
    }

    @Test
    void groupsPutTheLargestFirst() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("a", otsu(0.1, "x", "None", 0).label());
        m.put("b", otsu(0.2, "y", "None", 0).label());
        m.put("c", otsu(0.2, "z", "None", 0).label());
        var g = ThresholdIdentity.groups(m);
        assertThat(g.values().iterator().next()).isEqualTo(List.of("b", "c"));
    }

    @Test
    void theMapFileCarriesTheThreshold() {
        var id = otsu(0.08337, "pooled over 3 image(s), density run 20261021_1015", "None", 0);
        var info =
                new DensityMapInfo(DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", id.label(), List.of());
        String desc = SidecarDescription.format(info, DensityChannelSpec.defaultChannels());
        assertThat(desc).contains("\nThreshold: Otsu 0.08337 | pooled over 3 image(s)");
        assertThat(SidecarDescription.parseInfo(desc).threshold()).isEqualTo(id.label());
        // Maps written before the threshold was recorded parse with none.
        var old = new DensityMapInfo(DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", null, List.of());
        assertThat(SidecarDescription.parseInfo(SidecarDescription.format(old, DensityChannelSpec.defaultChannels()))
                        .threshold())
                .isNull();
    }
}
