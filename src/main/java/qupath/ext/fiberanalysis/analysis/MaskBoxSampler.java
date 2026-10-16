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

import java.io.IOException;
import java.util.Arrays;

/**
 * Fraction of a binary mask inside each cell's box, on the same geometry and
 * the same tiles as the fiber channels, so every channel of a density map
 * lines up and none of them needs the whole slide in memory.
 *
 * <p>Window lattice: the box is the window, {@code [gx * stride, gx * stride + window)},
 * and a window that would leave the image is NaN. Block lattice (per-pixel
 * mode): the box is centred on the block's sample pixel
 * {@code gx * step + step / 2}, exactly as {@code density_tile.py} samples
 * its centred filter, and a box hanging off the image is normalised by the
 * part inside it.
 *
 * <p>Each tile renders only the mask its own cells can see, so peak memory is
 * one tile's mask and integral image whatever the slide size.
 */
final class MaskBoxSampler {

    /** Renders the mask for a source rectangle: row-major, nonzero = inside. */
    @FunctionalInterface
    interface MaskSource {
        /**
         * @param x source x
         * @param y source y
         * @param w width
         * @param h height
         * @return {@code w * h} bytes
         * @throws IOException if the source cannot be read
         */
        byte[] render(int x, int y, int w, int h) throws IOException;
    }

    private MaskBoxSampler() {}

    /**
     * @param source mask renderer
     * @param geo    the density map's tiling and lattice
     * @param srcW   image width
     * @param srcH   image height
     * @return fractions in [0, 1], NaN where undefined, {@code gridW * gridH}
     * @throws IOException if a tile cannot be rendered
     */
    static float[] sample(MaskSource source, FiberDensityMapWorkflow.Geometry geo, int srcW, int srcH)
            throws IOException {
        int gridW = geo.gridW();
        int gridH = geo.gridH();
        int cell = geo.cellPx();
        int window = geo.windowPx();
        boolean centred = geo.pixel();
        float[] grid = new float[gridW * gridH];
        Arrays.fill(grid, Float.NaN);

        for (int ty = 0; ty < geo.nTilesY(); ty++) {
            int coreY = ty * geo.tileStep();
            int cellsY = geo.cellsIn(Math.min(geo.tileStep() + geo.margin(), srcH - coreY));
            if (cellsY <= 0) continue;
            int gy0 = coreY / cell;
            for (int tx = 0; tx < geo.nTilesX(); tx++) {
                int coreX = tx * geo.tileStep();
                int cellsX = geo.cellsIn(Math.min(geo.tileStep() + geo.margin(), srcW - coreX));
                if (cellsX <= 0) continue;
                int gx0 = coreX / cell;

                // The read is exactly the union of this tile's boxes.
                int[] spanX = span(gx0, cellsX, cell, window, srcW, centred);
                int[] spanY = span(gy0, cellsY, cell, window, srcH, centred);
                if (spanX == null || spanY == null) continue;
                int rw = spanX[1] - spanX[0];
                int rh = spanY[1] - spanY[0];
                byte[] mask = source.render(spanX[0], spanY[0], rw, rh);
                int[] integral = integral(mask, rw, rh);
                int iw = rw + 1;

                for (int j = 0; j < cellsY; j++) {
                    int gy = gy0 + j;
                    if (gy >= gridH) break;
                    int[] ys = box(gy, cell, window, srcH, centred);
                    if (ys == null) continue;
                    int y0 = ys[0] - spanY[0];
                    int y1 = ys[1] - spanY[0];
                    for (int i = 0; i < cellsX; i++) {
                        int gx = gx0 + i;
                        if (gx >= gridW) break;
                        int[] xs = box(gx, cell, window, srcW, centred);
                        if (xs == null) continue;
                        int x0 = xs[0] - spanX[0];
                        int x1 = xs[1] - spanX[0];
                        long sum = (long) integral[y1 * iw + x1]
                                + integral[y0 * iw + x0]
                                - integral[y0 * iw + x1]
                                - integral[y1 * iw + x0];
                        long area = (long) (x1 - x0) * (y1 - y0);
                        grid[gy * gridW + gx] = (float) ((double) sum / area);
                    }
                }
            }
        }
        return grid;
    }

    /** Integral image with a zero first row and column; int is enough for one tile. */
    private static int[] integral(byte[] mask, int w, int h) {
        int iw = w + 1;
        int[] out = new int[Math.multiplyExact(iw, h + 1)];
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            int src = y * w;
            int row = (y + 1) * iw;
            int prev = y * iw;
            for (int x = 0; x < w; x++) {
                rowSum += mask[src + x] != 0 ? 1 : 0;
                out[row + x + 1] = out[prev + x + 1] + rowSum;
            }
        }
        return out;
    }

    /** {@code [start, end)} covering the boxes of cells {@code g0 .. g0+n-1}, or null if none. */
    private static int[] span(int g0, int n, int cell, int window, int extent, boolean centred) {
        int s = Integer.MAX_VALUE;
        int e = Integer.MIN_VALUE;
        for (int g = g0; g < g0 + n; g++) {
            int[] b = box(g, cell, window, extent, centred);
            if (b == null) continue;
            s = Math.min(s, b[0]);
            e = Math.max(e, b[1]);
        }
        return e > s ? new int[] {s, e} : null;
    }

    /** {@code [start, end)} of cell {@code g}'s box on one axis, or null if undefined. */
    static int[] box(int g, int cellPx, int windowPx, int extent, boolean centred) {
        if (!centred) {
            int s = g * cellPx;
            int e = s + windowPx;
            return e > extent ? null : new int[] {s, e};
        }
        int c = Math.min(g * cellPx + cellPx / 2, extent - 1);
        int s = c - windowPx / 2;
        int e = s + windowPx;
        s = Math.max(0, s);
        e = Math.min(extent, e);
        return e > s ? new int[] {s, e} : null;
    }
}
