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

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * One fiber-mask overlay for a tiled annotation, built from the tiles' own
 * mask PNGs at a coarser scale so it stays under a pixel budget.
 *
 * <p>Each tile contributes only the rectangle it owns, so the overlap between
 * tiles is drawn once. A cell is fiber when any fiber pixel falls in it, so a
 * fiber thinner than a cell still shows.
 */
final class StitchedMaskOverlay {

    /** Alpha above which a mask PNG pixel is fiber; render.py writes 200 for fiber, 0 elsewhere. */
    private static final int ALPHA_THRESHOLD = 100;

    private final int factor;
    private final int width;
    private final int height;
    private final boolean[] fiber;

    /**
     * @param regionW region width in read pixels
     * @param regionH region height in read pixels
     * @param maxPx   largest overlay, in pixels
     */
    StitchedMaskOverlay(int regionW, int regionH, long maxPx) {
        long area = (long) Math.max(1, regionW) * Math.max(1, regionH);
        int k = (int) Math.max(1, Math.ceil(Math.sqrt((double) area / Math.max(1, maxPx))));
        while ((long) ceilDiv(regionW, k) * ceilDiv(regionH, k) > maxPx) k++;
        this.factor = k;
        this.width = Math.max(1, ceilDiv(regionW, k));
        this.height = Math.max(1, ceilDiv(regionH, k));
        this.fiber = new boolean[width * height];
    }

    /** @return read pixels per overlay pixel along each axis */
    int factor() {
        return factor;
    }

    /** @return overlay width in pixels */
    int width() {
        return width;
    }

    /** @return overlay height in pixels */
    int height() {
        return height;
    }

    /**
     * @param x overlay column
     * @param y overlay row
     * @return true when that cell holds fiber
     */
    boolean isFiber(int x, int y) {
        return fiber[y * width + x];
    }

    /**
     * Adds one tile's fiber mask.
     *
     * @param maskPng the tile's fiber_mask_overlay.png
     * @param tileX   tile origin X in region read pixels
     * @param tileY   tile origin Y in region read pixels
     * @param owned   rectangle of the tile it alone owns, tile-local; null adds the whole tile
     * @throws IOException if the PNG cannot be read
     */
    void add(Path maskPng, int tileX, int tileY, TileGrid.Box owned) throws IOException {
        BufferedImage img = ImageIO.read(maskPng.toFile());
        if (img == null) throw new IOException("Could not decode " + maskPng);
        WritableRaster alpha = img.getAlphaRaster();
        if (alpha == null) throw new IOException(maskPng.getFileName() + " has no alpha channel");
        int x0 = owned == null ? 0 : Math.max(0, owned.x());
        int y0 = owned == null ? 0 : Math.max(0, owned.y());
        int x1 = owned == null ? img.getWidth() : Math.min(img.getWidth(), owned.x() + owned.w());
        int y1 = owned == null ? img.getHeight() : Math.min(img.getHeight(), owned.y() + owned.h());
        if (x1 <= x0) return;
        int[] row = new int[x1 - x0];
        for (int y = y0; y < y1; y++) {
            alpha.getSamples(x0, y, x1 - x0, 1, 0, row);
            int cy = (tileY + y) / factor;
            if (cy >= height) continue;
            for (int i = 0; i < row.length; i++) {
                if (row[i] <= ALPHA_THRESHOLD) continue;
                int cx = (tileX + x0 + i) / factor;
                if (cx < width) fiber[cy * width + cx] = true;
            }
        }
    }

    /**
     * Writes the overlay in the colors of a single-region fiber mask.
     *
     * @param png file to write
     * @throws IOException if the file cannot be written
     */
    void write(Path png) throws IOException {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        int magenta = (200 << 24) | (255 << 16) | 255;
        for (int i = 0; i < argb.length; i++) {
            if (fiber[i]) argb[i] = magenta;
        }
        out.setRGB(0, 0, width, height, argb, 0, width);
        if (!ImageIO.write(out, "PNG", png.toFile())) throw new IOException("No PNG writer for " + png);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
