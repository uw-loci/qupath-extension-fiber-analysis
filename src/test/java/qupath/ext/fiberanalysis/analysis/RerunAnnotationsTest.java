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

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** rerun.groovy finds the annotations the run analyzed, and only those. */
class RerunAnnotationsTest {

    private static PathObject box(String name, double x, double y, double w, double h) {
        PathObject a =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()));
        if (name != null) a.setName(name);
        return a;
    }

    @Test
    void unnamedAnnotationsAreFoundByTheirBoundsNotWidenedToAll() {
        PathObject ran = box(null, 10, 10, 100, 50);
        PathObject other = box(null, 500, 500, 100, 50);
        String recorded = RerunAnnotations.describe(List.of(ran));
        assertThat(RerunAnnotations.select(List.of(other, ran), recorded)).containsExactly(ran);
    }

    @Test
    void namedAnnotationsAreFoundByNameEvenAfterTheirShapeChanged() {
        PathObject tumor = box("Tumor", 10, 10, 100, 50);
        String recorded = RerunAnnotations.describe(List.of(tumor));
        PathObject edited = box("Tumor", 12, 10, 140, 50);
        assertThat(RerunAnnotations.select(List.of(box("Stroma", 10, 10, 100, 50), edited), recorded))
                .containsExactly(edited);
    }

    @Test
    void repeatedNamesPreferTheOneWithTheSameBounds() {
        PathObject first = box("Tumor", 0, 0, 10, 10);
        PathObject second = box("Tumor", 50, 50, 10, 10);
        String recorded = RerunAnnotations.describe(List.of(second));
        assertThat(RerunAnnotations.select(List.of(first, second), recorded)).containsExactly(second);
    }

    @Test
    void previewMarkersAreNeverPicked() {
        PathObject marker = box(null, 10, 10, 100, 50);
        marker.setPathClass(PathClass.fromString("Fiber-Preview-Region"));
        PathObject ran = box(null, 10, 10, 100, 50);
        String recorded = RerunAnnotations.describe(List.of(ran));
        assertThat(RerunAnnotations.select(List.of(marker, ran), recorded)).containsExactly(ran);
    }

    @Test
    void aMissingAnnotationFailsAndSaysWhichOne() {
        String recorded = RerunAnnotations.describe(List.of(box(null, 10, 10, 100, 50)));
        assertThatThrownBy(() -> RerunAnnotations.select(List.of(box(null, 0, 0, 5, 5)), recorded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an unnamed annotation at (10, 10) size 100 x 50");
    }

    @Test
    void aScriptThatNamesNoAnnotationsFails() {
        assertThatThrownBy(() -> RerunAnnotations.select(List.of(box(null, 0, 0, 5, 5)), "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no annotations");
    }

    @Test
    void theWrittenRerunScriptCarriesTheAnnotationsAndSavesTheImage(@TempDir Path dir) throws Exception {
        PathObject ann = box(null, 10, 20, 30, 40);
        FiberAnalysisWorkflow.writeParamsFiles(dir, params(), "hash", "stamp", null, null, List.of(ann));
        String script = Files.readString(dir.resolve("rerun.groovy"));
        assertThat(script)
                .doesNotContainPattern("\\$\\{[A-Z_]+\\}")
                .contains("RerunAnnotations.select(")
                .contains("\"x\":10.0", "\"y\":20.0", "\"w\":30.0", "\"h\":40.0")
                .contains("entry.saveImageData(imageData)")
                .doesNotContain("targets.addAll(all)");
    }

    private static FiberAnalysisParams params() throws Exception {
        RecordComponent[] cs = FiberAnalysisParams.class.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            Class<?> t = cs[i].getType();
            types[i] = t;
            if (t == String.class) values[i] = "v";
            else if (t == int.class) values[i] = 1;
            else if (t == double.class) values[i] = 1.0;
            else values[i] = false;
        }
        return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(values);
    }
}
