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

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a region into overlapping tiles that keep the window lattice intact.
 *
 * <p>Two constraints drive the geometry. Python places windows at multiples of
 * {@code stride} from the region origin, so a tile origin that is not itself a
 * multiple of {@code stride} produces windows off the global lattice and they
 * cannot be merged. And a window straddling a tile edge belongs to neither
 * tile, so consecutive tiles overlap by one window; the duplicate positions
 * that creates are dropped at merge time by global position.
 *
 * @param tiles     tile boxes in region-local read pixels
 * @param stride    window stride the origins are aligned to
 * @param windowPx  window size the overlap is sized from
 */
record TileGrid(List<Box> tiles, int stride, int windowPx) {

    /**
     * One tile in region-local read-scale pixels.
     *
     * @param x tile origin X, a multiple of {@code stride}
     * @param y tile origin Y, a multiple of {@code stride}
     * @param w tile width
     * @param h tile height
     */
    record Box(int x, int y, int w, int h) {}

    /**
     * @param regionW    region width in read pixels
     * @param regionH    region height in read pixels
     * @param windowPx   window size in read pixels; must be positive
     * @param stride     window stride in read pixels; must be positive
     * @param maxSamples largest tile the caller will read, in pixels
     * @return a grid covering the region, or a single full-region tile when it
     *     already fits
     */
    static TileGrid create(int regionW, int regionH, int windowPx, int stride, long maxSamples) {
        if (windowPx <= 0 || stride <= 0) {
            throw new IllegalArgumentException("windowPx and stride must be positive, got " + windowPx + "/" + stride);
        }
        if ((long) regionW * regionH <= maxSamples) {
            return new TileGrid(List.of(new Box(0, 0, regionW, regionH)), stride, windowPx);
        }

        // Square tiles under the budget, snapped UP to a whole number of strides
        // plus the window remainder so the tile holds an exact window count.
        int side = (int) Math.max(windowPx, Math.floor(Math.sqrt((double) maxSamples)));
        int windowsPerTile = Math.max(1, (side - windowPx) / stride + 1);
        int tileSide = (windowsPerTile - 1) * stride + windowPx;

        // Advance by one window less than the tile so a window straddling an
        // edge is whole inside the next tile. Keep the step on the lattice.
        int step = Math.max(stride, ((tileSide - windowPx) / stride) * stride);

        List<Box> boxes = new ArrayList<>();
        for (int y = 0; y < regionH; y += step) {
            int h = Math.min(tileSide, regionH - y);
            if (h < windowPx && y > 0) break; // remainder too thin to hold a window
            for (int x = 0; x < regionW; x += step) {
                int w = Math.min(tileSide, regionW - x);
                if (w < windowPx && x > 0) break;
                boxes.add(new Box(x, y, w, h));
            }
        }
        if (boxes.isEmpty()) {
            boxes.add(new Box(0, 0, regionW, regionH));
        }
        return new TileGrid(boxes, stride, windowPx);
    }

    int count() {
        return tiles.size();
    }

    /**
     * Window size in pixels, matching {@code pipeline.py}:
     * {@code max(2, int(round(win_um / px_um)))}.
     *
     * <p>Math.rint, not Math.round. Python's {@code round} breaks halfway cases
     * to even and Java's {@code Math.round} breaks them up, so a window landing
     * exactly on .5 would size differently on the two sides and shift the whole
     * lattice by a pixel.
     *
     * @param windowSizeUm window size in microns
     * @param pixelSizeUm  effective pixel size in microns
     * @return window size in pixels, at least 2
     */
    static int windowPx(double windowSizeUm, double pixelSizeUm) {
        return Math.max(2, (int) Math.rint(windowSizeUm / pixelSizeUm));
    }

    /**
     * Window stride in pixels, matching {@code pipeline.py}:
     * {@code max(1, int(round(window_px * (1 - overlap))))} with overlap
     * clamped to [0, 0.95].
     *
     * @param windowPx       window size in pixels
     * @param overlapPercent requested window overlap
     * @return stride in pixels, at least 1
     */
    static int stridePx(int windowPx, double overlapPercent) {
        double frac = Math.max(0.0, Math.min(0.95, overlapPercent / 100.0));
        return Math.max(1, (int) Math.rint(windowPx * (1.0 - frac)));
    }
}
