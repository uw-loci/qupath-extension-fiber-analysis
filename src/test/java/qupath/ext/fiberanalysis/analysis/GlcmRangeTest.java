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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;

class GlcmRangeTest {

    @Test
    void setByHandIsTheDefaultAndNotAutomatic() {
        assertThat(GlcmRange.METHODS.get(0)).isEqualTo(FiberAnalysisPreferences.DEFAULT_GLCM_RANGE_METHOD);
        assertThat(GlcmRange.isAuto(GlcmRange.BY_HAND)).isFalse();
        assertThat(GlcmRange.isAuto(null)).isFalse();
    }

    @Test
    void everyAutomaticMethodHasAPythonName() {
        for (String m : GlcmRange.METHODS) {
            if (GlcmRange.isAuto(m)) assertThat(GlcmRange.pythonName(m)).isNotBlank();
        }
        assertThatThrownBy(() -> GlcmRange.pythonName(GlcmRange.BY_HAND)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMultiImageWarningNamesTheRisk() {
        assertThat(GlcmRange.MULTI_IMAGE_WARNING)
                .contains("inconsistent between images")
                .contains("negative control");
    }
}
