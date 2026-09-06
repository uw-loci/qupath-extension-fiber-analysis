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

import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Matching rules behind the "Annotations of class..." search area. */
class AnnotationClassFilterTest {

    private static PathObject annotation(PathClass pathClass) {
        ROI roi = ROIs.createRectangleROI(0, 0, 10, 10);
        return pathClass == null
                ? PathObjects.createAnnotationObject(roi)
                : PathObjects.createAnnotationObject(roi, pathClass);
    }

    @Test
    void unclassifiedAnnotationMatchesTheUnclassifiedEntry() {
        assertThat(AnnotationClassFilter.matches(annotation(null), Set.of("Unclassified")))
                .isTrue();
    }

    @Test
    void unclassifiedAnnotationIgnoredWhenOnlyRealClassesChecked() {
        assertThat(AnnotationClassFilter.matches(annotation(null), Set.of("Tumor")))
                .isFalse();
    }

    @Test
    void classifiedAnnotationDoesNotMatchTheUnclassifiedEntry() {
        PathObject ann = annotation(PathClass.fromString("Tumor"));
        assertThat(AnnotationClassFilter.matches(ann, Set.of("Unclassified"))).isFalse();
    }

    @Test
    void derivedClassMatchesItsFullDisplayName() {
        PathClass derived = PathClass.fromArray("Tumor", "Stroma");
        PathObject ann = annotation(derived);
        assertThat(derived.toString()).isEqualTo("Tumor: Stroma");
        assertThat(AnnotationClassFilter.matches(ann, Set.of("Tumor: Stroma"))).isTrue();
    }

    @Test
    void derivedClassAlsoMatchesItsBareName() {
        PathObject ann = annotation(PathClass.fromArray("Tumor", "Stroma"));
        assertThat(AnnotationClassFilter.matches(ann, Set.of("Stroma"))).isTrue();
    }

    @Test
    void emptyFilterMatchesNothing() {
        assertThat(AnnotationClassFilter.matches(annotation(null), Set.of())).isFalse();
        assertThat(AnnotationClassFilter.matches(annotation(PathClass.fromString("Tumor")), Set.of()))
                .isFalse();
    }

    @Test
    void displayNameUsesTheSentinelForNoClass() {
        assertThat(AnnotationClassFilter.displayName(annotation(null))).isEqualTo("Unclassified");
        assertThat(AnnotationClassFilter.displayName(PathClass.NULL_CLASS)).isEqualTo("Unclassified");
        assertThat(AnnotationClassFilter.displayName(PathClass.fromArray("Tumor", "Stroma")))
                .isEqualTo("Tumor: Stroma");
    }

    @Test
    void parseTrimsAndDropsEmptyTokens() {
        assertThat(AnnotationClassFilter.parse(" Tumor , ,Unclassified ")).containsExactly("Tumor", "Unclassified");
        assertThat(AnnotationClassFilter.parse("  ")).isEmpty();
        assertThat(AnnotationClassFilter.parse(null)).isEmpty();
    }
}
