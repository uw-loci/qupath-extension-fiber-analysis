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

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The object-density and pixel-positivity channels, tile by tile. They used to
 * rasterise the whole slide into one byte per pixel plus an 8-byte integral
 * image, which cannot even be allocated past about 2.1 gigapixels. Tiling must
 * change nothing but the memory.
 */
class MaskBoxSamplerTest {

    /** Brute force: count the mask over each cell's box on the whole image. */
    private static float[] reference(byte[] mask, int w, int h, FiberDensityMapWorkflow.Geometry geo) {
        float[] out = new float[geo.gridW() * geo.gridH()];
        for (int gy = 0; gy < geo.gridH(); gy++) {
            int[] ys = MaskBoxSampler.box(gy, geo.cellPx(), geo.windowPx(), h, geo.pixel());
            for (int gx = 0; gx < geo.gridW(); gx++) {
                int[] xs = MaskBoxSampler.box(gx, geo.cellPx(), geo.windowPx(), w, geo.pixel());
                if (ys == null || xs == null) {
                    out[gy * geo.gridW() + gx] = Float.NaN;
                    continue;
                }
                long n = 0;
                for (int y = ys[0]; y < ys[1]; y++) for (int x = xs[0]; x < xs[1]; x++) if (mask[y * w + x] != 0) n++;
                out[gy * geo.gridW() + gx] = (float) ((double) n / ((long) (xs[1] - xs[0]) * (ys[1] - ys[0])));
            }
        }
        return out;
    }

    private static byte[] blobs(int w, int h, long seed) {
        Random r = new Random(seed);
        byte[] m = new byte[w * h];
        for (int k = 0; k < 40; k++) {
            int cx = r.nextInt(w), cy = r.nextInt(h), rad = 2 + r.nextInt(Math.max(3, w / 10));
            for (int y = Math.max(0, cy - rad); y < Math.min(h, cy + rad); y++)
                for (int x = Math.max(0, cx - rad); x < Math.min(w, cx + rad); x++)
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rad * rad) m[y * w + x] = 1;
        }
        return m;
    }

    @Test
    void tilingChangesNothingButTheMemory() throws Exception {
        int checked = 0;
        for (int[] dims : new int[][] {{97, 61}, {300, 211}, {512, 512}}) {
            int w = dims[0], h = dims[1];
            byte[] mask = blobs(w, h, w * 31L + h);
            for (int window : new int[] {4, 9, 32}) {
                for (int stride : new int[] {1, 3, window / 2, window}) {
                    if (stride < 1 || stride > window || window > Math.min(w, h)) continue;
                    // Tiles of two windows, so a small image still has many seams.
                    var geos = List.of(
                            FiberDensityMapWorkflow.Geometry.window(w, h, window, stride, 2),
                            pixelGeometry(w, h, window, stride));
                    for (var geo : geos) {
                        int[] maxArea = {0};
                        MaskBoxSampler.MaskSource src = (x, y, rw, rh) -> {
                            maxArea[0] = Math.max(maxArea[0], rw * rh);
                            byte[] out = new byte[rw * rh];
                            for (int yy = 0; yy < rh; yy++) System.arraycopy(mask, (y + yy) * w + x, out, yy * rw, rw);
                            return out;
                        };
                        float[] tiled = MaskBoxSampler.sample(src, geo, w, h);
                        assertThat(tiled).as("%s on %dx%d", geo, w, h).containsExactly(reference(mask, w, h, geo));
                        int side = geo.tileStep() + geo.margin() + geo.windowPx() + 1;
                        assertThat(maxArea[0]).as("one tile at a time").isLessThanOrEqualTo(side * side);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(20);
    }

    /** Pixel-mode geometry with small tiles, so a small test image has seams. */
    private static FiberDensityMapWorkflow.Geometry pixelGeometry(int w, int h, int window, int step) {
        var g = FiberDensityMapWorkflow.Geometry.pixel(w, h, window, step);
        int tile = Math.max(step, (2 * window / step) * step);
        return new FiberDensityMapWorkflow.Geometry(
                g.gridW(),
                g.gridH(),
                step,
                window,
                tile,
                0,
                FiberDensityMapWorkflow.ceilDiv(w, tile),
                FiberDensityMapWorkflow.ceilDiv(h, tile),
                true);
    }

    @Test
    void anObjectRasterCutIntoTilesMatchesTheWholeSlideRaster() {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Ellipse2D.Double(10.3, 20.7, 90.2, 40.9));
        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(150.5, 5.2);
        tri.lineTo(250.1, 180.6);
        tri.lineTo(120.9, 150.4);
        tri.closePath();
        shapes.add(tri);
        int w = 300, h = 200;
        byte[] whole = ObjectDensityComputer.rasterizeShapes(shapes, 0, 0, w, h);
        byte[] tiled = new byte[w * h];
        for (int ty = 0; ty < h; ty += 37) {
            for (int tx = 0; tx < w; tx += 53) {
                int tw = Math.min(53, w - tx), th = Math.min(37, h - ty);
                byte[] part = ObjectDensityComputer.rasterizeShapes(shapes, tx, ty, tw, th);
                for (int y = 0; y < th; y++) System.arraycopy(part, y * tw, tiled, (ty + y) * w + tx, tw);
            }
        }
        // Java2D steps along an edge incrementally, so neither raster is exact
        // on pixels whose centre grazes a long edge (measured up to 0.08 px).
        // Score both against exact centre containment: tiling must be no
        // worse than one whole-slide raster, and wrong only beside an edge.
        int tiledWrong = 0;
        int wholeWrong = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean truth = false;
                for (Shape sh : shapes) truth |= sh.contains(x + 0.5, y + 0.5);
                if ((whole[y * w + x] != 0) != truth) wholeWrong++;
                if ((tiled[y * w + x] != 0) != truth) {
                    tiledWrong++;
                    assertThat(onEdge(shapes, x + 0.5, y + 0.5, 0.25))
                            .as("pixel %d,%d is wrong away from any edge", x, y)
                            .isTrue();
                }
            }
        }
        assertThat(tiledWrong)
                .as("tiled raster no worse than the whole-slide raster")
                .isLessThanOrEqualTo(wholeWrong + 2);
        System.out.println("[raster] centre-containment errors: tiled " + tiledWrong + ", whole " + wholeWrong + " of "
                + (w * h) + " px");
        assertThat(tiledWrong).isLessThan(w * h / 1000);
    }

    private static boolean onEdge(List<Shape> shapes, double cx, double cy, double eps) {
        for (Shape s : shapes) {
            boolean in = s.contains(cx, cy);
            if (s.contains(cx + eps, cy) != in
                    || s.contains(cx - eps, cy) != in
                    || s.contains(cx, cy + eps) != in
                    || s.contains(cx, cy - eps) != in) return true;
        }
        return false;
    }

    @Test
    void aBoxHangingOffTheImageIsNormalisedByThePartInside() throws Exception {
        byte[] full = new byte[36];
        java.util.Arrays.fill(full, (byte) 1);
        var geo = FiberDensityMapWorkflow.Geometry.pixel(6, 6, 4, 2);
        float[] g = MaskBoxSampler.sample(
                (x, y, w, h) -> {
                    byte[] b = new byte[w * h];
                    java.util.Arrays.fill(b, (byte) 1);
                    return b;
                },
                geo,
                6,
                6);
        for (float v : g) assertThat(v).isEqualTo(1f);
    }
}
