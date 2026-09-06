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
    void sentinelIsQuPathsOwnNameForTheNullClass() {
        assertThat(AnnotationClassFilter.UNCLASSIFIED).isEqualTo("Unclassified");
        assertThat(PathClass.NULL_CLASS.toString()).isEqualTo(AnnotationClassFilter.UNCLASSIFIED);
    }

    @Test
    void unclassifiedAnnotationMatchesTheUnclassifiedEntry() {
        assertThat(AnnotationClassFilter.predicate(Set.of("Unclassified")).test(annotation(null)))
                .isTrue();
    }

    @Test
    void unclassifiedAnnotationIgnoredWhenOnlyRealClassesChecked() {
        assertThat(AnnotationClassFilter.predicate(Set.of("Tumor")).test(annotation(null)))
                .isFalse();
    }

    @Test
    void classifiedAnnotationDoesNotMatchTheUnclassifiedEntry() {
        PathObject ann = annotation(PathClass.fromString("Tumor"));
        assertThat(AnnotationClassFilter.predicate(Set.of("Unclassified")).test(ann))
                .isFalse();
    }

    @Test
    void derivedClassMatchesItsFullDisplayName() {
        PathClass derived = PathClass.fromArray("Tumor", "Stroma");
        assertThat(derived.toString()).isEqualTo("Tumor: Stroma");
        assertThat(AnnotationClassFilter.predicate(Set.of("Tumor: Stroma")).test(annotation(derived)))
                .isTrue();
    }

    @Test
    void derivedClassDoesNotMatchItsBareName() {
        PathObject ann = annotation(PathClass.fromArray("Tumor", "Stroma"));
        assertThat(AnnotationClassFilter.predicate(Set.of("Stroma")).test(ann)).isFalse();
    }

    @Test
    void emptyFilterMatchesNothing() {
        assertThat(AnnotationClassFilter.predicate(Set.of()).test(annotation(null)))
                .isFalse();
        assertThat(AnnotationClassFilter.predicate(Set.of()).test(annotation(PathClass.fromString("Tumor"))))
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
