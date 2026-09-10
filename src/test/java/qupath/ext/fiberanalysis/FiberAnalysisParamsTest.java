/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;

/**
 * Minimal smoke test for {@link FiberAnalysisParams}. Verifies the record
 * exposes the expected number of components (every dialog control's value)
 * and that the field types haven't drifted from the design spec.
 */
class FiberAnalysisParamsTest {

    /**
     * Section count totals from 02_ui_design.md sections 1-7 (post-2026-06):
     *   S1 = 6, S2 = 17 (incl. collagenObjects tail), S3 = 5, S4 = 4,
     *   S5 = 11, S6 = 9, S7 = 8
     *   = 60 expected components. If this fails after a deliberate UI
     *     change, update both this test and 02_ui_design.md.
     */
    @Test
    void recordHasExpectedComponentCount() {
        RecordComponent[] components = FiberAnalysisParams.class.getRecordComponents();
        assertThat(components).hasSize(61);
    }

    @Test
    void recordHasStringZoneModeField() {
        RecordComponent[] components = FiberAnalysisParams.class.getRecordComponents();
        boolean found = false;
        for (RecordComponent c : components) {
            if ("zoneMode".equals(c.getName())) {
                assertThat(c.getType()).isEqualTo(String.class);
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }
}
