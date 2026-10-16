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
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * A density or classifier map shown as a coloured overlay on QuPath's pixel
 * layer, leaving the image server alone -- unlike attaching the map as
 * channels, which makes an RGB image render as separate channels.
 *
 * <p>One channel is read from the sidecar at a bounded size (the map's cells
 * are far coarser than the image, so nothing is lost), coloured with one of
 * QuPath's own colour maps, and drawn over the whole image. NaN (no data) is
 * transparent. QuPath's opacity slider and "Show pixel classification"
 * control it; it occupies the viewer's single pixel-layer slot.
 */
public final class DensityOverlay {

    /** Longest side of the image read from the sidecar, pixels. */
    public static final int MAX_SIDE = 4096;

    /** Output rows per read when assembling the overview. */
    static final int STRIP_ROWS = 256;

    private DensityOverlay() {}

    /**
     * One channel of a map, at a reduced size.
     *
     * @param values row-major, {@code width * height}, NaN = no data
     * @param width  pixels across
     * @param height pixels down
     * @param fullWidth  the map's full-resolution width (the image's)
     * @param fullHeight the map's full-resolution height
     */
    public record Channel(float[] values, int width, int height, int fullWidth, int fullHeight) {}

    /**
     * @param sidecar the map's OME-TIFF
     * @param channel channel index
     * @return the channel at most {@link #MAX_SIDE} on its longest side
     * @throws IOException if the sidecar cannot be read
     */
    public static Channel read(Path sidecar, int channel) throws IOException {
        try (ImageServer<BufferedImage> server =
                ImageServers.buildServer(sidecar.toUri().toString())) {
            int w = server.getWidth();
            int h = server.getHeight();
            // A power of two lands on a pyramid level the writer made, so
            // nothing has to be resampled from the level below.
            double d = 1.0;
            while (Math.max(w, h) / d > MAX_SIDE) d *= 2.0;
            long t0 = System.nanoTime();
            // A read returns every channel, and a map can carry a dozen: one
            // read of the whole overview held 11 channels x 3440 x 2573 floats
            // (390 MB) and ran a 768 MB QuPath out of memory. Strips keep only
            // the chosen channel, so memory is one channel at overview size.
            int stripSrc = (int) Math.max(1, Math.round(STRIP_ROWS * d));
            List<float[]> strips = new ArrayList<>();
            int outW = -1;
            int outH = 0;
            for (int y = 0; y < h; y += stripSrc) {
                int hh = Math.min(stripSrc, h - y);
                BufferedImage img = server.readRegion(RegionRequest.createInstance(server.getPath(), d, 0, y, w, hh));
                Raster r = img.getRaster();
                if (outW < 0) outW = r.getWidth();
                int rw = Math.min(outW, r.getWidth());
                float[] row = new float[outW * r.getHeight()];
                java.util.Arrays.fill(row, Float.NaN);
                float[] got = r.getSamples(0, 0, rw, r.getHeight(), channel, new float[rw * r.getHeight()]);
                for (int yy = 0; yy < r.getHeight(); yy++) System.arraycopy(got, yy * rw, row, yy * outW, rw);
                strips.add(row);
                outH += r.getHeight();
            }
            float[] v = new float[Math.max(0, outW) * outH];
            int off = 0;
            for (float[] st : strips) {
                System.arraycopy(st, 0, v, off, st.length);
                off += st.length;
            }
            org.slf4j.LoggerFactory.getLogger(DensityOverlay.class)
                    .info(
                            "Overlay read: channel {} of {} at downsample {} ({}x{}) in {} ms",
                            channel,
                            sidecar.getFileName(),
                            d,
                            outW,
                            outH,
                            (System.nanoTime() - t0) / 1_000_000);
            return new Channel(v, outW, outH, w, h);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read " + sidecar.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param values channel values
     * @param lowPct lower percentile, 0-100
     * @param highPct upper percentile, 0-100
     * @return {@code {low, high}} over the finite values, or {@code {0, 1}} if there are none
     */
    public static double[] percentileRange(float[] values, double lowPct, double highPct) {
        float[] finite = new float[values.length];
        int n = 0;
        for (float v : values) if (Float.isFinite(v)) finite[n++] = v;
        if (n == 0) return new double[] {0, 1};
        Arrays.sort(finite, 0, n);
        double lo = finite[(int) Math.floor((n - 1) * lowPct / 100.0)];
        double hi = finite[(int) Math.ceil((n - 1) * highPct / 100.0)];
        if (hi <= lo) hi = lo + 1e-9;
        return new double[] {lo, hi};
    }

    /**
     * @param ch   the channel
     * @param map  colour map
     * @param min  value drawn with the map's first colour
     * @param max  value drawn with its last colour
     * @return ARGB image, NaN pixels fully transparent
     */
    public static BufferedImage colour(Channel ch, ColorMaps.ColorMap map, double min, double max) {
        BufferedImage img = new BufferedImage(ch.width(), ch.height(), BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[ch.values().length];
        for (int i = 0; i < argb.length; i++) {
            float v = ch.values()[i];
            if (!Float.isFinite(v)) continue;
            double c = Math.max(min, Math.min(max, v));
            argb[i] = 0xff000000 | map.getColor(c, min, max);
        }
        img.setRGB(0, 0, ch.width(), ch.height(), argb, 0, ch.width());
        return img;
    }

    /**
     * Show a coloured map over the whole image, replacing whatever is on the
     * viewer's pixel layer.
     *
     * @param viewer  the viewer
     * @param options its overlay options
     * @param ch      the channel the image was coloured from (for its full size)
     * @param img     the coloured image
     * @return the overlay installed
     */
    public static PathOverlay show(QuPathViewer viewer, OverlayOptions options, Channel ch, BufferedImage img) {
        var region = ImageRegion.createInstance(
                0, 0, ch.fullWidth(), ch.fullHeight(), viewer.getZPosition(), viewer.getTPosition());
        var overlay = new BufferedImageOverlay(options, region, img);
        viewer.setCustomPixelLayerOverlay(overlay);
        options.setShowPixelClassification(true);
        return overlay;
    }

    /**
     * Remove an overlay this class installed; leaves anything else alone.
     *
     * @param viewer  the viewer
     * @param overlay the overlay {@link #show} returned
     */
    public static void hide(QuPathViewer viewer, PathOverlay overlay) {
        if (overlay != null && viewer.getCustomPixelLayerOverlay() == overlay) {
            viewer.resetCustomPixelLayerOverlay();
        }
    }
}
