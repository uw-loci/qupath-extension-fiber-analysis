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

import java.awt.image.BufferedImage;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.objects.PathObject;

/** What a tiled annotation produces: collagen objects, a stitched mask overlay, and a note. */
class TiledAnnotationOutputsTest {

    /** A tile mask PNG as render.py writes it: magenta, alpha 200 on fiber. */
    private static Path mask(Path dir, String name, int w, int h, int fx0, int fx1, int fy0, int fy1) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = fy0; y < fy1; y++) {
            for (int x = fx0; x < fx1; x++) img.setRGB(x, y, (200 << 24) | (255 << 16) | 255);
        }
        Path p = dir.resolve(name);
        ImageIO.write(img, "PNG", p.toFile());
        return p;
    }

    @Test
    void aBlobAcrossASeamBecomesOneObjectPerTileWithNoAreaCountedTwice(@TempDir Path dir) throws Exception {
        // Region 0..60 in x; tile A covers 0..40 and owns 0..30, tile B covers 30..60 and owns 30..60.
        // The blob spans x 20..40, y 10..20 in region pixels: 200 px, split 10 + 10 columns.
        Path a = mask(dir, "a.png", 40, 30, 20, 40, 10, 20);
        Path b = mask(dir, "b.png", 30, 30, 0, 10, 10, 20);
        List<PathObject> objects = new ArrayList<>();
        objects.addAll(CollagenObjectBuilder.build(
                "test", a, 0, 0, new TileGrid.Box(0, 0, 30, 30), 0, "run", "hash", 1.0, 0.0, 1.0));
        objects.addAll(CollagenObjectBuilder.build(
                "test", b, 30, 0, new TileGrid.Box(0, 0, 30, 30), 0, "run", "hash", 1.0, 0.0, 1.0));
        assertThat(objects).hasSize(2);
        double area = objects.stream().mapToDouble(o -> o.getROI().getArea()).sum();
        assertThat(area).isEqualTo(200.0);
        assertThat(objects.get(0).getROI().getBoundsX()).isEqualTo(20.0);
        assertThat(objects.get(1).getROI().getBoundsX()).isEqualTo(30.0);
    }

    @Test
    void anUntiledMaskKeepsEveryBlob(@TempDir Path dir) throws Exception {
        Path m = mask(dir, "m.png", 40, 30, 20, 40, 10, 20);
        List<PathObject> objects =
                CollagenObjectBuilder.build("test", m, 100, 50, null, 0, "run", "hash", 1.0, 0.0, 1.0);
        assertThat(objects).hasSize(1);
        assertThat(objects.get(0).getROI().getArea()).isEqualTo(200.0);
        assertThat(objects.get(0).getROI().getBoundsX()).isEqualTo(120.0);
    }

    @Test
    void theStitchedOverlayStaysUnderItsBudgetAndKeepsThinFibers(@TempDir Path dir) throws Exception {
        // A 1 px fiber column at region x = 33 lies in tile B's owned part; tile A also sees it
        // (its x = 33) but does not own it, so it is drawn once.
        Path a = mask(dir, "a.png", 40, 40, 33, 34, 0, 40);
        Path b = mask(dir, "b.png", 30, 40, 3, 4, 0, 40);
        StitchedMaskOverlay overlay = new StitchedMaskOverlay(60, 40, 600);
        assertThat((long) overlay.width() * overlay.height()).isLessThanOrEqualTo(600);
        assertThat(overlay.factor()).isEqualTo(2);
        overlay.add(a, 0, 0, new TileGrid.Box(0, 0, 30, 40));
        overlay.add(b, 30, 0, new TileGrid.Box(0, 0, 30, 40));
        int fiberColumn = 33 / overlay.factor();
        for (int y = 0; y < overlay.height(); y++) {
            for (int x = 0; x < overlay.width(); x++) {
                assertThat(overlay.isFiber(x, y)).as("cell %d,%d", x, y).isEqualTo(x == fiberColumn);
            }
        }
        Path png = dir.resolve("stitched.png");
        overlay.write(png);
        BufferedImage back = ImageIO.read(png.toFile());
        assertThat(back.getWidth()).isEqualTo(overlay.width());
        assertThat((back.getRGB(fiberColumn, 0) >>> 24) & 0xFF).isEqualTo(200);
        assertThat((back.getRGB(0, 0) >>> 24) & 0xFF).isZero();
    }

    @Test
    void theRunNoteListsWhatTheSettingsAskedForAndATiledAnnotationLacks() throws Exception {
        FiberAnalysisParams p = params(true);
        String note = FiberAnalysisWorkflow.tiledRunNote(
                "Tumor", 9, 64_000_000L, p, Set.of("morphometrics.fractal_dimension"), 4);
        assertThat(note)
                .contains("\"Tumor\"", "64 MP", "9 tiles")
                .contains("no heatmap overlays")
                .contains("1/4 of the analyzed resolution")
                .contains("no Radon scalars")
                .contains("morphometrics_summary.txt")
                .contains("window_metrics.npz")
                .contains("morphometrics.fractal_dimension")
                .contains("crosses a tile seam");
    }

    @Test
    void theRunNoteLeavesOutWhatWasNotAskedFor() throws Exception {
        String note = FiberAnalysisWorkflow.tiledRunNote("A", 4, 64_000_000L, params(false), Set.of(), 1);
        assertThat(note)
                .doesNotContain("Radon")
                .doesNotContain("npz")
                .doesNotContain("seam")
                .doesNotContain("summary")
                .doesNotContain("resolution");
    }

    /** Every boolean set to {@code on}, the rest to plain values. */
    private static FiberAnalysisParams params(boolean on) throws Exception {
        RecordComponent[] cs = FiberAnalysisParams.class.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            Class<?> t = cs[i].getType();
            types[i] = t;
            if (t == String.class) values[i] = "";
            else if (t == int.class) values[i] = 1;
            else if (t == double.class) values[i] = 1.0;
            else values[i] = on;
        }
        return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(values);
    }
}
