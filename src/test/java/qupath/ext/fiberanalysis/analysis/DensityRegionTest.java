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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class DensityRegionTest {

    private static PathObject rect(double x, double y, double w, double h, String cls) {
        var a = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()),
                cls == null ? null : PathClass.fromString(cls));
        return a;
    }

    private static PathObjectHierarchy hierarchy(PathObject... objects) {
        var h = new PathObjectHierarchy();
        h.addObjects(List.of(objects));
        return h;
    }

    @Test
    void eachModePicksItsAnnotations() {
        PathObject tissue = rect(0, 0, 100, 100, "Tissue");
        PathObject tumor = rect(200, 0, 50, 50, "Tumor");
        PathObject none = rect(400, 0, 10, 10, null);
        PathObject line = PathObjects.createAnnotationObject(
                ROIs.createLineROI(0, 0, 50, 50, ImagePlane.getDefaultPlane()), PathClass.fromString("Tissue"));
        var h = hierarchy(tissue, tumor, none, line);

        assertThat(DensityRegion.wholeImage().resolve(h)).isNull();
        // Lines have no area and never count.
        assertThat(DensityRegion.anyAnnotation().resolve(h).count()).isEqualTo(3);
        var tissueOnly = DensityRegion.ofClasses(List.of("Tissue")).resolve(h);
        assertThat(tissueOnly.count()).isEqualTo(1);
        assertThat(tissueOnly.geometry().getArea()).isEqualTo(10000.0);
        assertThat(DensityRegion.ofClasses(List.of(AnnotationClassFilter.UNCLASSIFIED))
                        .resolve(h)
                        .count())
                .isEqualTo(1);
        assertThat(DensityRegion.ofClasses(List.of("Stroma")).resolve(h).count())
                .isZero();

        var sel = DensityRegion.selected(List.of(tumor, line));
        assertThat(sel.annotationIds).containsExactly(tumor.getID().toString());
        var r = sel.resolve(h);
        assertThat(r.count()).isEqualTo(1);
        assertThat(r.missing()).isZero();
        // A selection resolved in an image without those annotations finds nothing.
        var other = sel.resolve(hierarchy(rect(0, 0, 10, 10, "Tumor")));
        assertThat(other.count()).isZero();
        assertThat(other.missing()).isEqualTo(1);
    }

    @Test
    void emptyChoicesAreRefused() {
        assertThatThrownBy(() -> DensityRegion.ofClasses(List.of())).isInstanceOf(IllegalArgumentException.class);
        PathObject line =
                PathObjects.createAnnotationObject(ROIs.createLineROI(0, 0, 50, 50, ImagePlane.getDefaultPlane()));
        assertThatThrownBy(() -> DensityRegion.selected(List.of(line))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellsWhoseWindowMissesTheRegionAreEmptied() {
        // Region covers x in [25, 55), y in [0, 20): windows of 10 px every 10 px.
        var region = DensityRegion.ofClasses(List.of("Tissue")).resolve(hierarchy(rect(25, 0, 30, 20, "Tissue")));
        var fp = new DensityRegion.Footprints(region.geometry());
        int gw = 8;
        int gh = 3;
        float[][] accum = {new float[gw * gh], new float[gw * gh]};
        for (float[] ch : accum) Arrays.fill(ch, 1f);
        long kept = fp.mask(accum, gw, gh, 10, 10);
        // Columns 2..5 touch [25, 55); rows 0..1 touch [0, 20). Column 2 is
        // [20, 30), column 5 is [50, 60). Row 2 is [20, 30), which only shares
        // the edge y = 20, so it is empty.
        assertThat(kept).isEqualTo(4 * 2);
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                boolean in = i >= 2 && i <= 5 && j <= 1;
                for (float[] ch : accum) {
                    if (in) assertThat(ch[j * gw + i]).isEqualTo(1f);
                    else assertThat(ch[j * gw + i]).isNaN();
                }
            }
        }
    }

    @Test
    void anOverlappingWindowCountsWhereverItReaches() {
        // Cells every 5 px, windows 10 px: cell 0's window [0, 10) reaches x = 9.
        var region = DensityRegion.anyAnnotation().resolve(hierarchy(rect(9, 0, 1, 10, null)));
        var fp = new DensityRegion.Footprints(region.geometry());
        float[][] accum = {new float[4]};
        Arrays.fill(accum[0], 1f);
        fp.mask(accum, 4, 1, 5, 10);
        // Windows [0,10) and [5,15) touch [9,10); [10,20) and [15,25) do not.
        assertThat(accum[0][0]).isEqualTo(1f);
        assertThat(accum[0][1]).isEqualTo(1f);
        assertThat(accum[0][2]).isNaN();
        assertThat(accum[0][3]).isNaN();
    }

    @Test
    void tilesAreTestedByTheirInterior() {
        var region = DensityRegion.anyAnnotation().resolve(hierarchy(rect(100, 100, 50, 50, null)));
        var fp = new DensityRegion.Footprints(region.geometry());
        assertThat(fp.touches(0, 0, 100, 100)).isFalse(); // shares only the corner
        assertThat(fp.touches(0, 0, 101, 101)).isTrue();
        assertThat(fp.touches(110, 110, 10, 10)).isTrue(); // inside
        assertThat(fp.touches(500, 500, 10, 10)).isFalse();
    }

    @Test
    void theRegionTravelsThroughTheRunParameters() throws Exception {
        PathObject a = rect(0, 0, 10, 10, "Tissue");
        a.setName("Atypical 1");
        for (DensityRegion region : List.of(
                DensityRegion.wholeImage(),
                DensityRegion.anyAnnotation(),
                DensityRegion.ofClasses(List.of("Tissue", "Tumor: Stroma")),
                DensityRegion.selected(List.of(a)))) {
            var spec = spec().withRegion(region).withRunThreshold(0.2, "calibration 'x'");
            assertThat(spec.region).isSameAs(region);
            java.io.File json = java.io.File.createTempFile("density_params", ".json");
            json.deleteOnExit();
            java.nio.file.Files.writeString(json.toPath(), new com.google.gson.Gson().toJson(spec.toOrderedMap()));
            var back = FiberDensityMapWorkflow.DensityMapJobSpec.fromJson(json).region;
            assertThat(back.mode).isEqualTo(region.mode);
            assertThat(back.classes).isEqualTo(region.classes);
            assertThat(back.annotationIds).isEqualTo(region.annotationIds);
            assertThat(back.describe()).isEqualTo(region.describe());
        }
        assertThat(DensityRegion.selected(List.of(a)).describe()).isEqualTo("selected annotations (1): Atypical 1");
        // A parameters file from before the option replays as a whole-image run.
        assertThat(DensityRegion.fromStored(null, null, null, null).isWholeImage())
                .isTrue();
    }

    @Test
    void theMapFileRecordsTheRegion() {
        var info = new DensityMapInfo(
                DensityLattice.blocks(4, 4, 10),
                40,
                40,
                0.5,
                false,
                "img",
                null,
                "area annotations of class Tissue; 3 annotation(s), 1.2e+04 um^2",
                List.of());
        String desc = SidecarDescription.format(info, List.of());
        assertThat(desc).contains("Region: area annotations of class Tissue; 3 annotation(s)");
        assertThat(SidecarDescription.parseInfo(desc).region())
                .isEqualTo("area annotations of class Tissue; 3 annotation(s), 1.2e+04 um^2");
        var old = new DensityMapInfo(DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", null, null, List.of());
        assertThat(SidecarDescription.parseInfo(SidecarDescription.format(old, List.of()))
                        .region())
                .isNull();
        assertThat(DensityOverlayDialog.regionText(null)).startsWith("Whole image");
    }

    @Test
    void theDialogSaysWhyARegionCannotRun() {
        String open = "open";
        String other = "other";
        assertThat(FiberDensityMapDialog.regionProblem(
                        DensityRegion.Mode.WHOLE_IMAGE, List.of(other), null, null, 0, 0))
                .isNull();
        assertThat(FiberDensityMapDialog.regionProblem(
                        DensityRegion.Mode.ANY_ANNOTATION, List.of(other), null, null, 0, 0))
                .isNull();
        assertThat(FiberDensityMapDialog.regionProblem(DensityRegion.Mode.CLASSES, List.of(other), null, null, 0, 0))
                .contains("at least one annotation class");
        assertThat(FiberDensityMapDialog.regionProblem(DensityRegion.Mode.SELECTED, List.of(open), null, null, 1, 0))
                .contains("Open the image");
        assertThat(FiberDensityMapDialog.regionProblem(
                        DensityRegion.Mode.SELECTED, List.of(open, other), open, "open", 1, 0))
                .contains("open image only")
                .contains("\"open\"");
        assertThat(FiberDensityMapDialog.regionProblem(DensityRegion.Mode.SELECTED, List.of(other), open, "open", 1, 0))
                .contains("open image only");
        assertThat(FiberDensityMapDialog.regionProblem(DensityRegion.Mode.SELECTED, List.of(open), open, "open", 0, 0))
                .contains("Select one or more");
        assertThat(FiberDensityMapDialog.regionProblem(DensityRegion.Mode.SELECTED, List.of(open), open, "open", 2, 0))
                .isNull();
    }

    private static FiberDensityMapWorkflow.DensityMapJobSpec spec() {
        return new FiberDensityMapWorkflow.DensityMapJobSpec(
                15,
                0,
                "Raw intensity",
                "Otsu",
                128,
                0.1,
                "none",
                1,
                4,
                1,
                1,
                false,
                0,
                null,
                false,
                "window",
                0,
                false,
                List.of(),
                false,
                List.of());
    }
}
