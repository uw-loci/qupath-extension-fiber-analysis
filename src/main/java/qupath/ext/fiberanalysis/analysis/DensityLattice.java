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
 * {@code windowPx}. Its value covers the cell-sized block CENTRED on that
 * window, {@code x0() + gx * cellPx} to {@code x0() + (gx + 1) * cellPx}, so a
 * map always sits over the tissue that produced it. The blocks tile the
 * rectangle {@link #x0()}, {@link #y0()}, {@code gridW * cellPx},
 * {@code gridH * cellPx}; pixels outside it are no-data.
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

    /** @return source x of the left edge of cell column 0 */
    public double x0() {
        return originX + inset();
    }

    /** @return source y of the top edge of cell row 0 */
    public double y0() {
        return originY + inset();
    }

    /** @return true when source point {@code (u, v)} lies inside some cell */
    public boolean covers(double u, double v) {
        return u >= x0() && u < x0() + gridW * (double) cellPx && v >= y0() && v < y0() + gridH * (double) cellPx;
    }

    /**
     * Value at a source point, from the cell whose centred block holds it.
     *
     * @param grid row-major values, {@code gridW * gridH}
     * @param u    source x (continuous; a pixel's centre is {@code x + 0.5})
     * @param v    source y
     * @return the value, or NaN outside every cell
     */
    public float nearest(float[] grid, double u, double v) {
        if (!covers(u, v)) return Float.NaN;
        int gx = clamp((int) Math.floor((u - x0()) / cellPx), gridW);
        int gy = clamp((int) Math.floor((v - y0()) / cellPx), gridH);
        return grid[gy * gridW + gx];
    }

    /**
     * {@link #bilinear} when {@code smooth}, else {@link #nearest}.
     *
     * @param grid   row-major values, {@code gridW * gridH}
     * @param u      source x
     * @param v      source y
     * @param smooth interpolate between cell centres
     * @param period the period of an axial angle (180 for an orientation in degrees), or 0 for
     *               an ordinary value; an axial value is interpolated as an angle, so 1 and 179
     *               blend to 0, not 90
     * @return the value, or NaN
     */
    public float sample(float[] grid, double u, double v, boolean smooth, double period) {
        return smooth ? bilinear(grid, u, v, period) : nearest(grid, u, v);
    }

    /**
     * Bilinear value between the four nearest window centres, skipping NaN
     * corners and renormalising their weights.
     *
     * @param grid row-major values, {@code gridW * gridH}
     * @param u    source x (continuous)
     * @param v    source y
     * @return the value, or NaN outside every cell or where all four are NaN
     */
    public float bilinear(float[] grid, double u, double v) {
        return bilinear(grid, u, v, 0);
    }

    /**
     * {@link #bilinear(float[], double, double)} for a value that may be an axial angle: with
     * {@code period > 0} the corners are averaged as angles of that period.
     *
     * @param grid   row-major values
     * @param u      source x
     * @param v      source y
     * @param period axial period, or 0 for an ordinary value
     * @return the value in {@code [0, period)} for an angle, or NaN
     */
    public float bilinear(float[] grid, double u, double v, double period) {
        if (!covers(u, v)) return Float.NaN;
        double fx = (u - x0()) / cellPx - 0.5;
        double fy = (v - y0()) / cellPx - 0.5;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double wx = fx - x0;
        double wy = fy - y0;
        double sum = 0.0;
        double sumSin = 0.0;
        double wsum = 0.0;
        double k = period > 0 ? 2 * Math.PI / period : 0;
        for (int dy = 0; dy <= 1; dy++) {
            int gy = clamp(y0 + dy, gridH);
            double w1 = dy == 0 ? 1.0 - wy : wy;
            for (int dx = 0; dx <= 1; dx++) {
                int gx = clamp(x0 + dx, gridW);
                double w = w1 * (dx == 0 ? 1.0 - wx : wx);
                float val = grid[gy * gridW + gx];
                if (w > 0.0 && !Float.isNaN(val)) {
                    if (period > 0) {
                        sum += w * Math.cos(k * val);
                        sumSin += w * Math.sin(k * val);
                    } else {
                        sum += val * w;
                    }
                    wsum += w;
                }
            }
        }
        if (wsum <= 0.0) return Float.NaN;
        return period > 0 ? (float) axialAngle(sumSin, sum, period) : (float) (sum / wsum);
    }

    /**
     * @param sumSin sum of {@code sin(2 pi a / period)}
     * @param sumCos sum of {@code cos(2 pi a / period)}
     * @param period the axial period
     * @return the mean angle in {@code [0, period)}
     */
    static double axialAngle(double sumSin, double sumCos, double period) {
        double a = Math.atan2(sumSin, sumCos) * period / (2 * Math.PI);
        return a < 0 ? a + period : (a >= period ? a - period : a);
    }

    /**
     * Mean of the finite values in each {@code d x d} block, NaN where a block has none; an
     * axial angle ({@code period > 0}) is averaged as an angle.
     */
    static float[] blockMean(float[] grid, int gridW, int gridH, int d, int outW, int outH, double period) {
        float[] out = new float[outW * outH];
        double k = period > 0 ? 2 * Math.PI / period : 0;
        for (int oy = 0; oy < outH; oy++) {
            for (int ox = 0; ox < outW; ox++) {
                double sum = 0;
                double sumSin = 0;
                int n = 0;
                for (int y = oy * d; y < Math.min(gridH, (oy + 1) * d); y++) {
                    for (int x = ox * d; x < Math.min(gridW, (ox + 1) * d); x++) {
                        float v = grid[y * gridW + x];
                        if (Float.isFinite(v)) {
                            if (period > 0) {
                                sum += Math.cos(k * v);
                                sumSin += Math.sin(k * v);
                            } else {
                                sum += v;
                            }
                            n++;
                        }
                    }
                }
                out[oy * outW + ox] =
                        n == 0 ? Float.NaN : (float) (period > 0 ? axialAngle(sumSin, sum, period) : sum / n);
            }
        }
        return out;
    }

    private double inset() {
        return (windowPx - cellPx) / 2.0;
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }
}
