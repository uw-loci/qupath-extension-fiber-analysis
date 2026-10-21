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
import java.util.List;
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
 *
 * <p>An optional second channel sets brightness (0 black to 1 full colour),
 * the usual orientation picture: hue for the angle, brightness for how well
 * aligned the fibers are, so a window of randomly oriented fibers does not get
 * a confident colour.
 *
 * <p>A classifier's predicted-class channel is drawn in each class's own
 * colour instead of on a colour map ({@link Style#classes}).
 */
public final class DensityMapOverlay extends AbstractOverlay {

    /** Most output pixels coloured in one repaint; a larger view is coloured coarser. */
    static final long MAX_PIXELS = 4096L * 4096L;

    private static final int LUT_SIZE = 1024;

    private final DensityMapInfo info;
    private final int channel;
    private final float[] grid;
    private final int brightnessChannel;
    private final float[] brightness;
    private final String brightnessName;
    private final String channelName;
    private final String unit;
    private final Style style;
    private final int[] lut = new int[LUT_SIZE];

    private Rectangle cachedRect;
    private double cachedDownsample;
    private BufferedImage cached;

    /**
     * How a channel's values become colours.
     *
     * @param map          colour map, for a range
     * @param min          value drawn with the map's first colour
     * @param max          value drawn with its last colour
     * @param hideAtOrBelowMin leave values at or below {@code min} undrawn rather than in the first colour
     * @param classNames   class names by index, for a class channel; null for a range
     * @param classColours packed RGB by index, matching {@code classNames}
     */
    public record Style(
            ColorMaps.ColorMap map,
            double min,
            double max,
            boolean hideAtOrBelowMin,
            List<String> classNames,
            int[] classColours) {

        /** Values from {@code min} to {@code max} along a colour map. */
        public static Style range(ColorMaps.ColorMap map, double min, double max, boolean hideAtOrBelowMin) {
            return new Style(map, min, max, hideAtOrBelowMin, null, null);
        }

        /** Each whole value is a class index, drawn in that class's colour; any other value is not drawn. */
        public static Style classes(List<String> names, int[] colours) {
            return new Style(null, 0, 0, false, List.copyOf(names), colours.clone());
        }

        /** @return true for a class channel */
        public boolean isClasses() {
            return classNames != null;
        }
    }

    /**
     * @param options the viewer's overlay options
     * @param info    the map's placement and render mode
     * @param channel channel index in the map
     * @param grid    that channel's values, {@code gridW * gridH}
     * @param style   how values become colours
     * @param brightnessChannel channel index setting brightness, or -1 for none
     * @param brightness        that channel's values (0-1), or null
     */
    public DensityMapOverlay(
            OverlayOptions options,
            DensityMapInfo info,
            int channel,
            float[] grid,
            Style style,
            int brightnessChannel,
            float[] brightness) {
        super(options);
        this.info = info;
        this.channel = channel;
        this.grid = grid;
        this.brightnessChannel = brightness == null ? -1 : brightnessChannel;
        this.brightness = brightness;
        this.brightnessName = brightness == null ? null : info.channels().get(brightnessChannel).name;
        var ch = info.channels().get(channel);
        this.channelName = ch.name;
        this.unit = ch.unit;
        this.style = style;
        if (!style.isClasses()) {
            double min = style.min();
            double max = style.max();
            for (int i = 0; i < LUT_SIZE; i++) {
                lut[i] = 0xff000000 | style.map().getColor(min + (max - min) * i / (LUT_SIZE - 1.0), min, max);
            }
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
        float v = value(x, y);
        if (!Float.isFinite(v)) return null;
        String s;
        if (style.isClasses()) {
            int k = classIndex(v);
            s = channelName + ": "
                    + (k < 0
                            ? String.format(Locale.ROOT, "%.4g", v)
                            : style.classNames().get(k));
        } else {
            s = String.format(Locale.ROOT, "%s: %.4g%s", channelName, v, unit == null ? "" : " " + unit);
        }
        if (brightness != null) {
            s += String.format(
                    Locale.ROOT, "; %s: %.3g", brightnessName, info.sample(brightnessChannel, brightness, x, y));
        }
        return s;
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
        double min = style.min();
        double span = style.max() - min;
        for (int oy = 0; oy < outH; oy++) {
            double v = y + (oy + 0.5) * sy;
            int row = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                double u = x + (ox + 0.5) * sx;
                float val = value(u, v);
                if (!Float.isFinite(val)) continue;
                int c;
                if (style.isClasses()) {
                    int k = classIndex(val);
                    if (k < 0) continue;
                    c = 0xff000000 | style.classColours()[k];
                } else {
                    if (style.hideAtOrBelowMin() && val <= min) continue;
                    int i = (int) Math.round((val - min) / span * (LUT_SIZE - 1));
                    c = lut[i < 0 ? 0 : (i >= LUT_SIZE ? LUT_SIZE - 1 : i)];
                }
                if (brightness != null) c = scale(c, info.sample(brightnessChannel, brightness, u, v));
                argb[row + ox] = c;
            }
        }
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, outW, outH, argb, 0, outW);
        return img;
    }

    /** The channel at a source point; a class channel is never interpolated. */
    private float value(double u, double v) {
        return style.isClasses() ? info.lattice().sample(grid, u, v, false, 0) : info.sample(channel, grid, u, v);
    }

    /** @return the class a value names, or -1 if it is not a whole number in range */
    private int classIndex(float v) {
        int k = Math.round(v);
        return k == v && k >= 0 && k < style.classNames().size() ? k : -1;
    }

    /** An opaque colour darkened by {@code b} (clamped to 0-1; NaN is black). */
    static int scale(int argb, float b) {
        double f = Float.isFinite(b) ? Math.max(0, Math.min(1, b)) : 0;
        int r = (int) Math.round(((argb >> 16) & 0xff) * f);
        int g = (int) Math.round(((argb >> 8) & 0xff) * f);
        int bl = (int) Math.round((argb & 0xff) * f);
        return 0xff000000 | (r << 16) | (g << 8) | bl;
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
