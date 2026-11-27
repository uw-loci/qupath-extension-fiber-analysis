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

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the Run dialog, the batch dialog and the results panel tell the user. */
class RunDialogWordingTest {

    @Test
    void aRegionAboveTheTileBudgetIsSaidToBeTiledNotUnreadable() {
        String text = FiberAnalysisDialog.regionEstimateText(20_000, 10_000, 1.0, 105, 0.5, 64_000_000L);
        assertThat(text).contains("200.0 MP").contains("analyzed in tiles").doesNotContain("TOO LARGE");
        String small = FiberAnalysisDialog.regionEstimateText(2_000, 1_000, 1.0, 105, 0.5, 64_000_000L);
        assertThat(small).doesNotContain("tiles").contains("dilation 105 px");
    }

    @Test
    void aBatchSaysWhatASavedSelectedSearchAreaMeans() throws Exception {
        assertThat(FiberAnalysisBatchDialog.searchAreaNote(params("selected")))
                .contains("every annotation on each image");
        assertThat(FiberAnalysisBatchDialog.searchAreaNote(params("all"))).isNull();
        assertThat(FiberAnalysisBatchDialog.searchAreaNote(params("class"))).isNull();
    }

    @Test
    void aResultKnowsWhichImageItsOverlaysBelongTo() {
        AnnotationResult r = new AnnotationResult(
                0, "a", Path.of("out"), Map.of(), Map.of(), 0, 0, 10, 10, 1.0, "file:/images/one.tif");
        assertThat(r.isOn("file:/images/one.tif")).isTrue();
        assertThat(r.isOn("file:/images/two.tif")).isFalse();
        assertThat(r.isOn(null)).isFalse();
    }

    private static FiberAnalysisParams params(String searchArea) throws Exception {
        RecordComponent[] cs = FiberAnalysisParams.class.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            Class<?> t = cs[i].getType();
            types[i] = t;
            if (cs[i].getName().equals("searchArea")) values[i] = searchArea;
            else if (t == String.class) values[i] = "";
            else if (t == int.class) values[i] = 1;
            else if (t == double.class) values[i] = 1.0;
            else values[i] = false;
        }
        return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(values);
    }
}
