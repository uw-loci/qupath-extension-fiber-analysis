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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

/**
 * Computes per-spec pixel-positivity density channels at the same grid
 * geometry as the fiber-density channels.
 *
 * <p>For each {@link PixelPositivitySpec}:
 * <ol>
 *   <li>Stream the source image in fixed-size tiles via
 *       {@link ImageServer#readRegion}; for every source pixel evaluate
 *       {@code value op threshold} on the spec's channel and write a
 *       1/0 bit into a source-resolution binary mask.</li>
 *   <li>Box-filter the mask with an integral image (summed-area table)
 *       and sample into the output grid at the SAME window / stride the
 *       fiber-density channels use.</li>
 * </ol>
 *
 * <p>The returned grids are float[gridH*gridW] with NaN where the local
 * neighborhood is entirely outside the slide (corner partial windows).
 * Values are in [0.0, 1.0] -- fraction of the local box that is positive
 * under the spec's rule. Multiply by 100 for percent coverage.
 *
 * <p>Memory cost is one byte per source pixel for the binary mask + one
 * long per source pixel for the integral image = 9 bytes per source
 * pixel per spec. On a 100k x 100k slide with 3 positivity channels
 * that is ~270 GB across the run -- but the mask + integral image are
 * allocated and released per spec, so peak is one spec's cost
 * (~90 GB per spec at that size). Realistic slide sizes are much
 * smaller; callers should preflight before running on WSIs.
 */
final class PixelPositivityComputer {

    private static final Logger logger = LoggerFactory.getLogger(PixelPositivityComputer.class);

    /** Source-tile size for streaming reads, in pixels. */
    private static final int SOURCE_TILE = 512;

    private PixelPositivityComputer() {}

    /**
     * Compute one density grid per positivity spec.
     *
     * @param server         source image server
     * @param specs          ordered positivity rules; one output per rule
     * @param srcW           source image width in pixels
     * @param srcH           source image height in pixels
     * @param windowPx       neighborhood size in source pixels (box-filter window)
     * @param stridePx       window stride, or the sampling step in pixel mode
     * @param gridW          output grid columns
     * @param gridH          output grid rows
     * @param centred        true in pixel mode: boxes centred on each step block
     * @return grids in the same order as {@code specs}. Each is
     *         float[gridW*gridH]; NaN = no data at that cell.
     */
    static List<float[]> compute(
            ImageServer<BufferedImage> server,
            List<PixelPositivitySpec> specs,
            int srcW,
            int srcH,
            int windowPx,
            int stridePx,
            int gridW,
            int gridH,
            boolean centred)
            throws IOException {

        List<float[]> out = new ArrayList<>(specs.size());
        if (specs.isEmpty()) return out;

        int nServerChannels = server.nChannels();
        for (PixelPositivitySpec spec : specs) {
            if (spec.channelIndex >= nServerChannels) {
                logger.warn(
                        "Pixel positivity: spec references channel {} but server has {} channels; emitting NaN grid",
                        spec.channelIndex,
                        nServerChannels);
                float[] empty = new float[gridW * gridH];
                Arrays.fill(empty, Float.NaN);
                out.add(empty);
                continue;
            }

            byte[] mask = buildMask(server, spec, srcW, srcH);
            long maskPx = countNonZero(mask);
            logger.info(
                    "Pixel positivity '{}': {} positive px ({}% of slide)",
                    spec.channelDisplayName(),
                    maskPx,
                    String.format(java.util.Locale.ROOT, "%.2f", 100.0 * maskPx / Math.max(1L, (long) srcW * srcH)));

            float[] grid = MaskBoxSampler.sample(mask, srcW, srcH, windowPx, stridePx, gridW, gridH, centred);
            out.add(grid);
        }
        return out;
    }

    /**
     * Stream the source tile by tile and produce a source-resolution byte[]
     * mask: 1 = positive under the spec's rule, 0 = negative. Uses
     * {@code readRegion} at downsample = 1.
     */
    private static byte[] buildMask(ImageServer<BufferedImage> server, PixelPositivitySpec spec, int srcW, int srcH)
            throws IOException {
        byte[] mask = new byte[srcW * srcH];
        for (int yy = 0; yy < srcH; yy += SOURCE_TILE) {
            int hh = Math.min(SOURCE_TILE, srcH - yy);
            for (int xx = 0; xx < srcW; xx += SOURCE_TILE) {
                int ww = Math.min(SOURCE_TILE, srcW - xx);
                RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, xx, yy, ww, hh);
                BufferedImage tile = server.readRegion(req);
                if (tile == null) continue;
                // getSamples(float[]) works for any channel-band raster
                // (int, short, byte, float) -- Bio-Formats/AWT autoconvert
                // and we get the physical intensity per sample.
                float[] samples = tile.getRaster().getSamples(0, 0, ww, hh, spec.channelIndex, (float[]) null);
                for (int ty = 0; ty < hh; ty++) {
                    int maskRowBase = (yy + ty) * srcW + xx;
                    int sampleRowBase = ty * ww;
                    for (int tx = 0; tx < ww; tx++) {
                        if (spec.test(samples[sampleRowBase + tx])) {
                            mask[maskRowBase + tx] = 1;
                        }
                    }
                }
            }
        }
        return mask;
    }

    private static long countNonZero(byte[] mask) {
        long c = 0;
        for (byte b : mask) if (b != 0) c++;
        return c;
    }
}
