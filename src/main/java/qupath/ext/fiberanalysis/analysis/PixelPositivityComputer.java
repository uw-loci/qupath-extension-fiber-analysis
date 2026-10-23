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
 * Per-rule pixel-positivity density channels on the fiber channels' lattice:
 * the fraction of each cell's box where {@code value op threshold} holds on
 * the rule's channel.
 *
 * <p>Tile by tile ({@link MaskBoxSampler}): each tile reads only its own
 * rectangle, so memory is one tile's mask whatever the slide size. The rule
 * is a fixed threshold in the channel's own units, so no slide-wide
 * statistic is needed for tiles to agree. Values are in [0, 1]; NaN where a
 * window leaves the slide (window mode).
 */
final class PixelPositivityComputer {

    private static final Logger logger = LoggerFactory.getLogger(PixelPositivityComputer.class);

    private PixelPositivityComputer() {}

    /**
     * @param server source image
     * @param specs  positivity rules, one channel each, in order
     * @param geo    the density map's tiling and lattice
     * @param srcW   image width
     * @param srcH   image height
     * @param inside the map's region, or null for the whole image; tiles outside it are not read
     * @return one {@code float[gridW * gridH]} per rule, NaN = no data
     * @throws IOException if a tile cannot be read
     */
    static List<float[]> compute(
            ImageServer<BufferedImage> server,
            List<PixelPositivitySpec> specs,
            FiberDensityMapWorkflow.Geometry geo,
            int srcW,
            int srcH,
            DensityRegion.Footprints inside)
            throws IOException {

        List<float[]> out = new ArrayList<>(specs.size());
        int nServerChannels = server.nChannels();
        for (PixelPositivitySpec spec : specs) {
            if (spec.channelIndex >= nServerChannels) {
                logger.warn(
                        "Pixel positivity: spec references channel {} but server has {} channels; emitting NaN grid",
                        spec.channelIndex,
                        nServerChannels);
                float[] empty = new float[geo.gridW() * geo.gridH()];
                Arrays.fill(empty, Float.NaN);
                out.add(empty);
                continue;
            }
            logger.info("Pixel positivity '{}': thresholding per tile", spec.channelDisplayName());
            out.add(MaskBoxSampler.sample(
                    (x, y, w, h) -> inside == null || inside.touches(x, y, w, h)
                            ? threshold(server, spec, x, y, w, h)
                            : new byte[w * h],
                    geo,
                    srcW,
                    srcH));
        }
        return out;
    }

    private static byte[] threshold(
            ImageServer<BufferedImage> server, PixelPositivitySpec spec, int x, int y, int w, int h)
            throws IOException {
        byte[] mask = new byte[w * h];
        BufferedImage tile = server.readRegion(RegionRequest.createInstance(server.getPath(), 1.0, x, y, w, h));
        if (tile == null) return mask;
        // getSamples(float[]) works for any band type and gives the physical
        // intensity. A server can hand back a region a pixel short at the
        // image edge, so only the part it returned is tested.
        int tw = Math.min(w, tile.getWidth());
        int th = Math.min(h, tile.getHeight());
        float[] samples = tile.getRaster().getSamples(0, 0, tw, th, spec.channelIndex, (float[]) null);
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                if (spec.test(samples[ty * tw + tx])) mask[ty * w + tx] = 1;
            }
        }
        return mask;
    }
}
