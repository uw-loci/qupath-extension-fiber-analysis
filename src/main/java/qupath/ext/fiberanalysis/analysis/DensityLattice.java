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

/**
 * Where a compact grid of window values sits on the source image.
 *
 * <p>Cell {@code (gx, gy)} is the window whose top-left corner is
 * {@code (originX + gx * cellPx, originY + gy * cellPx)} and whose side is
 * {@code windowPx}. Its value is drawn over the cell-sized block CENTRED on
 * that window, so a map always sits over the tissue that produced it. Pixels
 * outside every window are no-data.
 *
 * @param gridW    cells across
 * @param gridH    cells down
 * @param cellPx   source pixels between neighbouring cells (the stride)
 * @param windowPx source pixels a cell's window spans; equals {@code cellPx}
 *                 for a grid sampled once per block
 * @param originX  source x of cell (0, 0)'s window
 * @param originY  source y of cell (0, 0)'s window
 */
public record DensityLattice(int gridW, int gridH, int cellPx, int windowPx, double originX, double originY) {

    /** @throws IllegalArgumentException if the geometry is nonsense */
    public DensityLattice {
        if (gridW <= 0 || gridH <= 0) {
            throw new IllegalArgumentException("grid must be non-empty, got " + gridW + "x" + gridH);
        }
        if (cellPx <= 0) throw new IllegalArgumentException("cellPx must be positive, got " + cellPx);
        if (windowPx < cellPx) {
            throw new IllegalArgumentException(
                    "windowPx (" + windowPx + ") must be >= cellPx (" + cellPx + "), or cells leave gaps");
        }
    }

    /**
     * A window lattice anchored at a region's corner.
     *
     * @param gridW    windows across
     * @param gridH    windows down
     * @param stridePx window stride
     * @param windowPx window side
     * @param originX  region x
     * @param originY  region y
     * @return the lattice
     */
    public static DensityLattice windows(int gridW, int gridH, int stridePx, int windowPx, int originX, int originY) {
        return new DensityLattice(gridW, gridH, stridePx, windowPx, originX, originY);
    }

    /**
     * A grid holding one sample per {@code stepPx} block from the image corner.
     *
     * @param gridW  cells across
     * @param gridH  cells down
     * @param stepPx block side
     * @return the lattice
     */
    public static DensityLattice blocks(int gridW, int gridH, int stepPx) {
        return new DensityLattice(gridW, gridH, stepPx, stepPx, 0, 0);
    }

    /** @return true when source point {@code (u, v)} lies inside some window */
    public boolean covers(double u, double v) {
        double spanX = (gridW - 1) * (double) cellPx + windowPx;
        double spanY = (gridH - 1) * (double) cellPx + windowPx;
        return u >= originX && u < originX + spanX && v >= originY && v < originY + spanY;
    }

    /**
     * Value at a source point, from the cell whose centred block holds it.
     *
     * @param grid row-major values, {@code gridW * gridH}
     * @param u    source x (continuous; a pixel's centre is {@code x + 0.5})
     * @param v    source y
     * @return the value, or NaN outside every window
     */
    public float nearest(float[] grid, double u, double v) {
        if (!covers(u, v)) return Float.NaN;
        int gx = clamp((int) Math.floor((u - originX - inset()) / cellPx), gridW);
        int gy = clamp((int) Math.floor((v - originY - inset()) / cellPx), gridH);
        return grid[gy * gridW + gx];
    }

    /**
     * Bilinear value between the four nearest window centres, skipping NaN
     * corners and renormalising their weights.
     *
     * @param grid row-major values, {@code gridW * gridH}
     * @param u    source x (continuous)
     * @param v    source y
     * @return the value, or NaN outside every window or where all four are NaN
     */
    public float bilinear(float[] grid, double u, double v) {
        if (!covers(u, v)) return Float.NaN;
        double fx = (u - originX - windowPx / 2.0) / cellPx;
        double fy = (v - originY - windowPx / 2.0) / cellPx;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double wx = fx - x0;
        double wy = fy - y0;
        double sum = 0.0;
        double wsum = 0.0;
        for (int dy = 0; dy <= 1; dy++) {
            int gy = clamp(y0 + dy, gridH);
            double w1 = dy == 0 ? 1.0 - wy : wy;
            for (int dx = 0; dx <= 1; dx++) {
                int gx = clamp(x0 + dx, gridW);
                double w = w1 * (dx == 0 ? 1.0 - wx : wx);
                float val = grid[gy * gridW + gx];
                if (w > 0.0 && !Float.isNaN(val)) {
                    sum += val * w;
                    wsum += w;
                }
            }
        }
        return wsum <= 0.0 ? Float.NaN : (float) (sum / wsum);
    }

    private double inset() {
        return (windowPx - cellPx) / 2.0;
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }
}
