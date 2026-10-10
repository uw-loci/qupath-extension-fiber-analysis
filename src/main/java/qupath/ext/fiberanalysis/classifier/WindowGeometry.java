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

/**
 * Lattice arithmetic for the window classifier: where each measurement window
 * sits, and which small central box its prediction is attributed to.
 *
 * <p>A fiber measurement needs a window large enough to contain many fibers,
 * but a window that large can straddle two kinds of collagen. Sliding a large
 * window with a small stride and labelling only its centre separates the two
 * concerns: the window sets the context, the stride sets the resolution of the
 * output map.
 *
 * <p><b>The core is centred, and {@code TileGrid.ownedBoxes()} is not the same
 * thing.</b> That method partitions <i>tiles</i>, anchored at the tile origin
 * and running to the next origin. The construction looks transferable and is
 * not: applied per window it yields a box at the window's top-left corner,
 * offset from the window centre by {@code (windowPx - stridePx) / 2}. At 75%
 * overlap on a 100 um window that is a 37.5 um systematic shift between where
 * a measurement was taken and where its label is attributed. The partition
 * property carries over; the anchor does not.
 */
public final class WindowGeometry {

    private WindowGeometry() {}

    /**
     * A box in region-local read-scale pixels.
     *
     * @param x left edge
     * @param y top edge
     * @param w width
     * @param h height
     */
    public record Box(int x, int y, int w, int h) {

        /** @return true when this box contains the point, edges inclusive on the low side */
        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    /**
     * @param windowPx window side in pixels
     * @param stridePx distance between consecutive window origins
     * @return the inset from a window's origin to its core's origin
     * @throws IllegalArgumentException if the geometry is nonsense
     */
    public static int coreInset(int windowPx, int stridePx) {
        validate(windowPx, stridePx);
        // Floor the half-pixel when the parities differ. Flooring rather than
        // rounding keeps the inset identical for every window, which is what
        // makes the cores tile the plane exactly.
        return (windowPx - stridePx) / 2;
    }

    /**
     * The window box at grid position {@code (ix, iy)}.
     *
     * <p>Matches the lattice {@code fiberlib.windows.compute_windows} lays
     * down: origins at multiples of the stride, every window exactly
     * {@code windowPx} square.
     *
     * @param ix       column index
     * @param iy       row index
     * @param windowPx window side
     * @param stridePx window stride
     * @return the window box
     */
    public static Box windowBox(int ix, int iy, int windowPx, int stridePx) {
        validate(windowPx, stridePx);
        requireNonNegative(ix, iy);
        return new Box(ix * stridePx, iy * stridePx, windowPx, windowPx);
    }

    /**
     * The core box at grid position {@code (ix, iy)} -- the part of the window
     * a prediction is attributed to.
     *
     * <p>Side is exactly {@code stridePx}, centred in the window, so
     * consecutive cores abut with no gap and no overlap and the full set
     * partitions the covered region.
     *
     * @param ix       column index
     * @param iy       row index
     * @param windowPx window side
     * @param stridePx window stride
     * @return the core box
     */
    public static Box coreBox(int ix, int iy, int windowPx, int stridePx) {
        int inset = coreInset(windowPx, stridePx);
        requireNonNegative(ix, iy);
        return new Box(ix * stridePx + inset, iy * stridePx + inset, stridePx, stridePx);
    }

    /**
     * Number of whole windows along one axis.
     *
     * <p>Mirrors the floor division in {@code compute_windows}, which is why a
     * trailing strip of up to {@code windowPx - 1} pixels is covered by no
     * whole window. {@link #partialCount} says how many extra clamped windows
     * are needed to reach the edge.
     *
     * @param extentPx region extent along the axis
     * @param windowPx window side
     * @param stridePx window stride
     * @return count of whole windows, possibly zero
     */
    public static int wholeCount(int extentPx, int windowPx, int stridePx) {
        validate(windowPx, stridePx);
        if (extentPx < windowPx) return 0;
        return (extentPx - windowPx) / stridePx + 1;
    }

    /**
     * Extra windows needed past the last whole one to reach the region edge.
     *
     * <p>Zero or one. A partial window is clamped to the edge, so its support
     * is truncated: predict on it, but never train on it, because every
     * area-normalised feature is biased by the smaller denominator.
     *
     * @param extentPx region extent along the axis
     * @param windowPx window side
     * @param stridePx window stride
     * @return 0 when the whole windows already reach the edge, else 1
     */
    public static int partialCount(int extentPx, int windowPx, int stridePx) {
        int whole = wholeCount(extentPx, windowPx, stridePx);
        if (whole == 0) return extentPx > 0 ? 1 : 0;
        int covered = (whole - 1) * stridePx + windowPx;
        return covered < extentPx ? 1 : 0;
    }

    /**
     * @param extentPx region extent along the axis
     * @param windowPx window side
     * @param stridePx window stride
     * @return total window positions along the axis, whole plus any partial
     */
    public static int totalCount(int extentPx, int windowPx, int stridePx) {
        return wholeCount(extentPx, windowPx, stridePx) + partialCount(extentPx, windowPx, stridePx);
    }

    /**
     * @param ix       column index
     * @param iy       row index
     * @param regionW  region width
     * @param regionH  region height
     * @param windowPx window side
     * @param stridePx window stride
     * @return true when this window hangs off the region and was clamped
     */
    public static boolean isPartial(int ix, int iy, int regionW, int regionH, int windowPx, int stridePx) {
        validate(windowPx, stridePx);
        requireNonNegative(ix, iy);
        return ix * stridePx + windowPx > regionW || iy * stridePx + windowPx > regionH;
    }

    private static void validate(int windowPx, int stridePx) {
        if (windowPx < 2) throw new IllegalArgumentException("windowPx must be >= 2 (got " + windowPx + ")");
        if (stridePx < 1) throw new IllegalArgumentException("stridePx must be >= 1 (got " + stridePx + ")");
        if (stridePx > windowPx) {
            throw new IllegalArgumentException(
                    "stridePx must not exceed windowPx, or windows leave gaps and the cores do not"
                            + " partition the region (window " + windowPx + ", stride " + stridePx + ")");
        }
    }

    private static void requireNonNegative(int ix, int iy) {
        if (ix < 0 || iy < 0)
            throw new IllegalArgumentException("grid indices must be >= 0 (got " + ix + "," + iy + ")");
    }
}
