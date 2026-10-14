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

import java.util.Arrays;

/**
 * Fraction of a binary mask inside each cell's box, on the same geometry as
 * the fiber channels, so every channel of a density map lines up.
 *
 * <p>Window lattice: the box is the window, {@code [gx * stride, gx * stride + window)},
 * and a window that would leave the image is NaN. Block lattice (per-pixel
 * mode): the box is centred on the block's sample pixel
 * {@code gx * step + step / 2}, exactly as {@code density_tile.py} samples
 * its centred filter, and a box hanging off the image is normalised by the
 * part inside it.
 */
final class MaskBoxSampler {

    private MaskBoxSampler() {}

    /**
     * @param mask     row-major mask, nonzero = inside; {@code srcW * srcH}
     * @param srcW     image width
     * @param srcH     image height
     * @param windowPx box side
     * @param cellPx   stride (window lattice) or step (block lattice)
     * @param gridW    cells across
     * @param gridH    cells down
     * @param centred  true for a block lattice, false for a window lattice
     * @return fractions in [0, 1], NaN where undefined
     */
    static float[] sample(
            byte[] mask, int srcW, int srcH, int windowPx, int cellPx, int gridW, int gridH, boolean centred) {
        int iw = srcW + 1;
        // long: a whole-slide mask can hold more than 2^31 set pixels.
        long[] integral = new long[iw * (srcH + 1)];
        for (int y = 0; y < srcH; y++) {
            long rowSum = 0;
            int src = y * srcW;
            int row = (y + 1) * iw;
            int prev = y * iw;
            for (int x = 0; x < srcW; x++) {
                rowSum += mask[src + x] != 0 ? 1 : 0;
                integral[row + x + 1] = integral[prev + x + 1] + rowSum;
            }
        }

        float[] grid = new float[gridW * gridH];
        Arrays.fill(grid, Float.NaN);
        for (int gy = 0; gy < gridH; gy++) {
            int[] ys = box(gy, cellPx, windowPx, srcH, centred);
            if (ys == null) continue;
            for (int gx = 0; gx < gridW; gx++) {
                int[] xs = box(gx, cellPx, windowPx, srcW, centred);
                if (xs == null) continue;
                long sum = integral[ys[1] * iw + xs[1]]
                        + integral[ys[0] * iw + xs[0]]
                        - integral[ys[0] * iw + xs[1]]
                        - integral[ys[1] * iw + xs[0]];
                long area = (long) (xs[1] - xs[0]) * (ys[1] - ys[0]);
                grid[gy * gridW + gx] = (float) ((double) sum / area);
            }
        }
        return grid;
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
