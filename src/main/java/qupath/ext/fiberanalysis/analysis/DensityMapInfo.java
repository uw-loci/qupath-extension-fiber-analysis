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

import java.util.List;

/**
 * What a density or classifier map file says about itself: where its grid
 * sits on the source image, how to draw it, and its channels.
 *
 * @param lattice       the grid's placement on the source image
 * @param sourceWidth   source image width, pixels
 * @param sourceHeight  source image height, pixels
 * @param sourcePixelUm source pixel size, microns
 * @param smooth        draw by interpolating between cell centres rather than filling each cell
 * @param sourceImage   name of the project image the map was computed on
 * @param channels      channel names and units, in file order
 */
public record DensityMapInfo(
        DensityLattice lattice,
        int sourceWidth,
        int sourceHeight,
        double sourcePixelUm,
        boolean smooth,
        String sourceImage,
        List<DensitySidecar.ChannelInfo> channels) {

    /**
     * @param grid one channel, {@code gridW * gridH}
     * @param u    source x
     * @param v    source y
     * @return the value drawn at that source point, NaN outside the grid
     */
    public float sample(float[] grid, double u, double v) {
        return lattice.sample(grid, u, v, smooth);
    }

    /**
     * @param width  an image's width
     * @param height its height
     * @return true when the map lines up with an image of that size
     */
    public boolean fits(int width, int height) {
        return width == sourceWidth && height == sourceHeight;
    }

    /** @return the index of the named channel, or -1 */
    public int channelIndex(String name) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).name.equals(name)) return i;
        }
        return -1;
    }
}
