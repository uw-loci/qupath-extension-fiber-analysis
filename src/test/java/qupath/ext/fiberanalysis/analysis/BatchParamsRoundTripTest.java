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

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The batch dialog reads back every setting a run wrote to params.json. */
class BatchParamsRoundTripTest {

    @Test
    void everySettingSurvivesWriteAndLoad(@TempDir Path dir) throws Exception {
        FiberAnalysisParams written = distinctParams();
        FiberAnalysisWorkflow.writeParamsFiles(dir, written, "hash", "stamp", null, null, null);
        FiberAnalysisParams read = FiberAnalysisBatchDialog.loadParamsFromJson(dir.resolve("params.json"));
        java.util.List<String> lost = new java.util.ArrayList<>();
        for (RecordComponent c : FiberAnalysisParams.class.getRecordComponents()) {
            Object w = c.getAccessor().invoke(written);
            Object r = c.getAccessor().invoke(read);
            if (!java.util.Objects.equals(w, r)) lost.add(c.getName() + " (wrote " + w + ", read " + r + ")");
        }
        assertThat(lost)
                .as("settings lost between params.json and the batch dialog")
                .isEmpty();
    }

    @Test
    void aRerunKeepsTheRunsHashWhenNoClassifierIsChosen(@TempDir Path dir) throws Exception {
        // The Run dialog leaves the classifier name null when no pixel classifier exists.
        FiberAnalysisParams written = distinctParams("classifierName");
        FiberAnalysisWorkflow.writeParamsFiles(dir, written, "hash", "stamp", null, null, null);
        FiberAnalysisParams read = FiberAnalysisBatchDialog.loadParamsFromJson(dir.resolve("params.json"));
        assertThat(FiberAnalysisWorkflow.computeParamsHash(read))
                .isEqualTo(FiberAnalysisWorkflow.computeParamsHash(written));
    }

    @Test
    void aFileWithoutSettingsIsRefused(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("other.json");
        Files.writeString(f, "{\"searchArea\": \"all\"}");
        assertThatThrownBy(() -> FiberAnalysisBatchDialog.loadParamsFromJson(f))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("parameters");
    }

    /** A value in every component that no loader default could produce by accident. */
    private static FiberAnalysisParams distinctParams(String... nullNames) throws Exception {
        java.util.Set<String> nulls = java.util.Set.of(nullNames);
        RecordComponent[] cs = FiberAnalysisParams.class.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            Class<?> t = cs[i].getType();
            types[i] = t;
            if (t == String.class) values[i] = nulls.contains(cs[i].getName()) ? null : "value-" + i;
            else if (t == int.class) values[i] = 1000 + i;
            else if (t == double.class) values[i] = 1000.25 + i;
            else if (t == boolean.class) values[i] = i % 2 == 0;
            else throw new IllegalStateException("Unhandled type " + t + " for " + cs[i].getName());
        }
        return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(values);
    }
}
