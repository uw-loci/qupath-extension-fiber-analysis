/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.fiberanalysis.classifier.WindowLabelExtractor.LabelledAnnotation;
import qupath.ext.fiberanalysis.classifier.WindowLabelExtractor.Reason;
import qupath.ext.fiberanalysis.classifier.WindowLabelExtractor.Request;

/** Which cores become training examples, and the reason each other one does not. */
class WindowLabelExtractorTest {

    private static final GeometryFactory GF = new GeometryFactory();
    private static final List<String> CLASSES = List.of("Dense", "Loose");

    private static Geometry box(double x, double y, double w, double h) {
        return GF.toGeometry(new Envelope(x, x + w, y, y + h));
    }

    /**
     * A 4x4 lattice of 100 px windows at stride 25 over a region just big
     * enough for them, so every window is whole. Cores are 25 px, the first
     * at (37, 37).
     */
    private static Request lattice(double minCorePurity) {
        int windowPx = 100;
        int stridePx = 25;
        int gridW = 4;
        int gridH = 4;
        int regionW = (gridW - 1) * stridePx + windowPx;
        int regionH = (gridH - 1) * stridePx + windowPx;
        return new Request(CLASSES, gridW, gridH, windowPx, stridePx, regionW, regionH, 0, 0, 1.0, minCorePurity);
    }

    @Test
    void anAnnotationCoveringEveryCoreLabelsEveryCore() {
        var res = WindowLabelExtractor.extract(
                lattice(1.0), List.of(new LabelledAnnotation("Dense", 7L, box(0, 0, 1000, 1000))));

        assertThat(res.labelledCount()).isEqualTo(16);
        assertThat(res.classIndex()).containsOnly(0);
        assertThat(res.annotationId()).containsOnly(7L);
        assertThat(res.windowsPerClass()).containsEntry("Dense", 16).containsEntry("Loose", 0);
        assertThat(res.annotationsPerClass().get("Dense")).containsExactly(7L);
    }

    @Test
    void noAnnotationAtAllLeavesEveryCoreUnlabelledForOneNamedReason() {
        var res = WindowLabelExtractor.extract(lattice(1.0), List.of());
        assertThat(res.labelledCount()).isZero();
        assertThat(res.counts()).containsEntry(Reason.OUTSIDE_ALL_CLASSES, 16);
        assertThat(res.classIndex()).containsOnly(-1);
    }

    @Test
    void aCoreTouchedByTwoClassesIsDroppedNotVotedOn() {
        // Dense covers the whole region; Loose covers a single pixel inside
        // the first core. One pixel of disagreement is enough: a vote here
        // would label the core Dense and hide that the stride is too coarse
        // for the geometry the user drew.
        var res = WindowLabelExtractor.extract(
                lattice(1.0),
                List.of(
                        new LabelledAnnotation("Dense", 1L, box(0, 0, 1000, 1000)),
                        new LabelledAnnotation("Loose", 2L, box(40, 40, 1, 1))));

        assertThat(res.counts()).containsEntry(Reason.MIXED_CLASSES, 1);
        assertThat(res.labelledCount()).isEqualTo(15);
        assertThat(res.classIndex()[0]).isEqualTo(-1);
    }

    @Test
    void aCorePartlyCoveredIsBelowPurityNotOutside() {
        // Dense is a full-height strip reaching x = 45, so it clips the
        // first core of every row (x in [37, 62)) and misses the rest. The
        // reason has to distinguish "not enough of it" from "no class here".
        var res = WindowLabelExtractor.extract(
                lattice(1.0), List.of(new LabelledAnnotation("Dense", 1L, box(0, 0, 45, 1000))));

        assertThat(res.counts()).containsEntry(Reason.BELOW_PURITY, 4);
        assertThat(res.counts()).containsEntry(Reason.OUTSIDE_ALL_CLASSES, 12);
        assertThat(res.labelledCount()).isZero();
    }

    @Test
    void aLoosenedPurityAcceptsAPartiallyCoveredCore() {
        // Same geometry, purity relaxed to 0.3: the first core is 8/25
        // covered in x, which is 0.32.
        var res = WindowLabelExtractor.extract(
                lattice(0.3), List.of(new LabelledAnnotation("Dense", 1L, box(0, 0, 45, 1000))));

        assertThat(res.counts()).doesNotContainKey(Reason.BELOW_PURITY);
        assertThat(res.labelledCount()).isEqualTo(4);
    }

    @Test
    void aCoreAbuttingTheAnnotationEdgeExactlyIsStillInside() {
        // The rim test. containsProperly() would reject a core whose edge
        // touches the annotation boundary, throwing away the whole outer
        // ring of every hand-drawn region.
        var res = WindowLabelExtractor.extract(
                lattice(1.0), List.of(new LabelledAnnotation("Dense", 1L, box(37, 37, 25, 25))));

        assertThat(res.labelledCount()).isEqualTo(1);
        assertThat(res.classIndex()[0]).isZero();
    }

    @Test
    void aCoreStraddlingTwoAnnotationsOfOneClassIsLabelledAndAttributedToTheLarger() {
        // Unambiguous class, two annotations: a legitimate example. The
        // attribution matters because annotation count is one of the gates
        // and the unit the permutation null shuffles at.
        var res = WindowLabelExtractor.extract(
                new Request(CLASSES, 1, 1, 100, 25, 100, 100, 0, 0, 1.0, 1.0),
                List.of(
                        new LabelledAnnotation("Dense", 1L, box(0, 0, 45, 1000)),
                        new LabelledAnnotation("Dense", 2L, box(45, 0, 1000, 1000))));

        assertThat(res.labelledCount()).isEqualTo(1);
        // Core spans x in [37, 62): 8 px from annotation 1, 17 px from 2.
        assertThat(res.annotationId()[0]).isEqualTo(2L);
        assertThat(res.annotationsPerClass().get("Dense")).containsExactly(2L);
    }

    @Test
    void aPartialWindowIsNeverATrainingExampleEvenWhenFullyInsideAClass() {
        // Region one stride wider than the whole windows need, so the last
        // column of windows is clamped. Those cores sit inside the
        // annotation, but the features behind them came from a truncated
        // window.
        var req = new Request(CLASSES, 5, 4, 100, 25, 180, 175, 0, 0, 1.0, 1.0);
        var res =
                WindowLabelExtractor.extract(req, List.of(new LabelledAnnotation("Dense", 1L, box(0, 0, 1000, 1000))));

        assertThat(res.counts()).containsEntry(Reason.PARTIAL_WINDOW, 4);
        assertThat(res.labelledCount()).isEqualTo(16);
    }

    @Test
    void anAnnotationOfAnUnselectedClassIsIgnoredRatherThanMixing() {
        // The user did not pick "Other", so an Other annotation must not
        // poison the cores it overlaps -- it is simply not part of the task.
        var res = WindowLabelExtractor.extract(
                lattice(1.0),
                List.of(
                        new LabelledAnnotation("Dense", 1L, box(0, 0, 1000, 1000)),
                        new LabelledAnnotation("Other", 9L, box(0, 0, 1000, 1000))));

        assertThat(res.labelledCount()).isEqualTo(16);
        assertThat(res.counts()).doesNotContainKey(Reason.MIXED_CLASSES);
    }

    @Test
    void regionOffsetAndDownsamplePlaceTheCoresInFullImageCoordinates() {
        // Same lattice, but the region starts at (1000, 2000) in a 4x
        // downsampled read. The annotation is drawn in full-image pixels, so
        // getting the conversion wrong silently labels nothing.
        var req = new Request(CLASSES, 1, 1, 100, 25, 100, 100, 1000, 2000, 4.0, 1.0);
        var inside = WindowLabelExtractor.extract(
                req, List.of(new LabelledAnnotation("Dense", 1L, box(1148, 2148, 100, 100))));
        assertThat(inside.labelledCount()).isEqualTo(1);

        var atOrigin =
                WindowLabelExtractor.extract(req, List.of(new LabelledAnnotation("Dense", 1L, box(37, 37, 25, 25))));
        assertThat(atOrigin.labelledCount()).isZero();
    }

    @Test
    void theRejectionSummaryNamesEveryReasonThatFired() {
        var res = WindowLabelExtractor.extract(
                lattice(1.0),
                List.of(
                        new LabelledAnnotation("Dense", 1L, box(0, 0, 90, 1000)),
                        new LabelledAnnotation("Loose", 2L, box(40, 40, 1, 1))));

        String summary = res.rejectionSummary();
        assertThat(summary).contains("labelled").contains("mixed_classes").contains("outside_all_classes");
    }
}
