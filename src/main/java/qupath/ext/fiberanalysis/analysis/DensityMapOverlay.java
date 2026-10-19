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

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Locale;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.overlays.AbstractOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;

/**
 * One channel of a map drawn over the image on QuPath's pixel layer. Each
 * repaint colours only the visible part, at screen resolution, from the grid
 * itself, so the map is exact at every zoom: cells stay crisp when the map is
 * drawn nearest, and blend between cell centres when it is drawn smooth.
 * Hidden with "Show pixel classification" (C); the viewer applies the opacity.
 */
public final class DensityMapOverlay extends AbstractOverlay {

    /** Most output pixels coloured in one repaint; a larger view is coloured coarser. */
    static final long MAX_PIXELS = 4096L * 4096L;

    private static final int LUT_SIZE = 1024;

    private final DensityMapInfo info;
    private final float[] grid;
    private final String channelName;
    private final String unit;
    private final double min;
    private final double max;
    private final int[] lut = new int[LUT_SIZE];

    private Rectangle cachedRect;
    private double cachedDownsample;
    private BufferedImage cached;

    /**
     * @param options the viewer's overlay options
     * @param info    the map's placement and render mode
     * @param channel channel index in the map
     * @param grid    that channel's values, {@code gridW * gridH}
     * @param map     colour map
     * @param min     value drawn with the map's first colour
     * @param max     value drawn with its last colour
     */
    public DensityMapOverlay(
            OverlayOptions options,
            DensityMapInfo info,
            int channel,
            float[] grid,
            ColorMaps.ColorMap map,
            double min,
            double max) {
        super(options);
        this.info = info;
        this.grid = grid;
        var ch = info.channels().get(channel);
        this.channelName = ch.name;
        this.unit = ch.unit;
        this.min = min;
        this.max = max;
        for (int i = 0; i < LUT_SIZE; i++) {
            lut[i] = 0xff000000 | map.getColor(min + (max - min) * i / (LUT_SIZE - 1.0), min, max);
        }
    }

    @Override
    public void paintOverlay(
            Graphics2D g2d,
            ImageRegion imageRegion,
            double downsampleFactor,
            ImageData<BufferedImage> imageData,
            boolean paintCompletely) {
        if (!isVisible() || !getOverlayOptions().getShowPixelClassification()) return;
        Rectangle view = g2d.getClipBounds();
        if (view == null) {
            view = new Rectangle(
                    imageRegion.getX(), imageRegion.getY(), imageRegion.getWidth(), imageRegion.getHeight());
        }
        Rectangle rect = view.intersection(gridBounds());
        if (rect.isEmpty()) return;
        double d = Math.max(downsampleFactor, 1e-6);
        long w = (long) Math.ceil(rect.width / d);
        long h = (long) Math.ceil(rect.height / d);
        double scale = Math.sqrt(Math.max(1.0, (double) (w * h) / MAX_PIXELS));
        int outW = (int) Math.max(1, Math.ceil(w / scale));
        int outH = (int) Math.max(1, Math.ceil(h / scale));
        if (cached == null || !rect.equals(cachedRect) || cachedDownsample != d) {
            cached = render(rect.x, rect.y, rect.width, rect.height, outW, outH);
            cachedRect = rect;
            cachedDownsample = d;
        }
        g2d.drawImage(cached, rect.x, rect.y, rect.width, rect.height, null);
    }

    @Override
    public String getLocationString(ImageData<BufferedImage> imageData, double x, double y, int z, int t) {
        if (!getOverlayOptions().getShowPixelClassification()) return null;
        float v = info.sample(grid, x, y);
        if (!Float.isFinite(v)) return null;
        return String.format(Locale.ROOT, "%s: %.4g%s", channelName, v, unit == null ? "" : " " + unit);
    }

    /**
     * Colour a source region of the map.
     *
     * @param x    source x of the region
     * @param y    source y
     * @param w    source width
     * @param h    source height
     * @param outW output pixels across
     * @param outH output pixels down
     * @return ARGB image, transparent where the map has no value
     */
    BufferedImage render(int x, int y, int w, int h, int outW, int outH) {
        int[] argb = new int[outW * outH];
        double sx = (double) w / outW;
        double sy = (double) h / outH;
        double span = max - min;
        for (int oy = 0; oy < outH; oy++) {
            double v = y + (oy + 0.5) * sy;
            int row = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                float val = info.sample(grid, x + (ox + 0.5) * sx, v);
                if (!Float.isFinite(val)) continue;
                int i = (int) Math.round((val - min) / span * (LUT_SIZE - 1));
                argb[row + ox] = lut[i < 0 ? 0 : (i >= LUT_SIZE ? LUT_SIZE - 1 : i)];
            }
        }
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, outW, outH, argb, 0, outW);
        return img;
    }

    /** @return the source rectangle the grid covers, rounded outward to whole pixels */
    Rectangle gridBounds() {
        DensityLattice lat = info.lattice();
        int x0 = (int) Math.floor(lat.x0());
        int y0 = (int) Math.floor(lat.y0());
        int x1 = (int) Math.ceil(lat.x0() + lat.gridW() * (double) lat.cellPx());
        int y1 = (int) Math.ceil(lat.y0() + lat.gridH() * (double) lat.cellPx());
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }
}
