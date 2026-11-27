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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageChannel;
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
     * Finds each rule's channel in this image by name, so images whose channels are in another
     * order still measure the channel the rule names.
     *
     * @param server source image
     * @param specs  positivity rules
     * @return the band of each rule, in order
     * @throws IOException naming the channel when the image has none of that name
     */
    static int[] resolveBands(ImageServer<BufferedImage> server, List<PixelPositivitySpec> specs) throws IOException {
        List<String> names = new ArrayList<>();
        for (ImageChannel c : server.getMetadata().getChannels()) names.add(c.getName());
        int[] bands = new int[specs.size()];
        for (int i = 0; i < specs.size(); i++) bands[i] = resolveBand(names, specs.get(i));
        return bands;
    }

    /**
     * @param channelNames the image's channel names, in band order
     * @param spec         the rule
     * @return the band holding the rule's channel
     * @throws IOException when no channel, or more than one, has that name
     */
    static int resolveBand(List<String> channelNames, PixelPositivitySpec spec) throws IOException {
        List<String> names = new ArrayList<>(channelNames.size());
        for (int i = 0; i < channelNames.size(); i++) names.add(displayName(channelNames.get(i), i));
        int stored = spec.channelIndex;
        // Two channels can share a name; the band the rule was made on then decides.
        if (stored < names.size() && names.get(stored).equals(spec.channelName)) return stored;
        int found = -1;
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).equals(spec.channelName)) continue;
            if (found >= 0)
                throw new IOException("Pixel positivity: this image has more than one channel named '"
                        + spec.channelName + "', so the rule cannot tell which one to measure.");
            found = i;
        }
        if (found < 0)
            throw new IOException("Pixel positivity: this image has no channel named '" + spec.channelName
                    + "' (its channels: " + String.join(", ", names) + "). Rules measure channels by name;"
                    + " leave this image out or rename its channel.");
        return found;
    }

    /** A channel's name as the density dialog lists it; an unnamed channel reads "Channel i". */
    static String displayName(String name, int index) {
        return name == null || name.isBlank() ? "Channel " + index : name;
    }

    /**
     * @param server source image
     * @param specs  positivity rules, one channel each, in order
     * @param bands  each rule's band in this image, from {@link #resolveBands}
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
            int[] bands,
            FiberDensityMapWorkflow.Geometry geo,
            int srcW,
            int srcH,
            DensityRegion.Footprints inside)
            throws IOException {
        List<float[]> out = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            PixelPositivitySpec spec = specs.get(i);
            int band = bands[i];
            logger.info("Pixel positivity '{}': band {}, thresholding per tile", spec.channelDisplayName(), band);
            out.add(MaskBoxSampler.sample(
                    (x, y, w, h) -> inside == null || inside.touches(x, y, w, h)
                            ? threshold(server, spec, band, x, y, w, h)
                            : new byte[w * h],
                    geo,
                    srcW,
                    srcH));
        }
        return out;
    }

    private static byte[] threshold(
            ImageServer<BufferedImage> server, PixelPositivitySpec spec, int band, int x, int y, int w, int h)
            throws IOException {
        byte[] mask = new byte[w * h];
        BufferedImage tile = server.readRegion(RegionRequest.createInstance(server.getPath(), 1.0, x, y, w, h));
        if (tile == null) return mask;
        // getSamples(float[]) works for any band type and gives the physical
        // intensity. A server can hand back a region a pixel short at the
        // image edge, so only the part it returned is tested.
        int tw = Math.min(w, tile.getWidth());
        int th = Math.min(h, tile.getHeight());
        float[] samples = tile.getRaster().getSamples(0, 0, tw, th, band, (float[]) null);
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                if (spec.test(samples[ty * tw + tx])) mask[ty * w + tx] = 1;
            }
        }
        return mask;
    }
}
