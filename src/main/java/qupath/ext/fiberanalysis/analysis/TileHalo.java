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

import java.util.Locale;

/**
 * How far past its core a tile must be read so the per-pixel filters see real
 * neighbours instead of the tile edge. Mirrors the filter chain in
 * {@code fiberlib} ({@code segmentation.segment_internal},
 * {@code straightness.skeletonize_and_tangents}, {@code intensity.halo_px}).
 *
 * <p>Covers every LOCAL filter exactly. Skeleton tracing and small-object
 * removal are not local -- a fiber can run for any distance -- so those stay
 * approximate at seams; the halo only makes it rarer.
 */
public final class TileHalo {

    /** Gaussian smoothing before every gradient in fiberlib, pixels. */
    public static final double GRAD_SIGMA_PX = 1.0;

    /** Structure-tensor integration scale for mask orientation, pixels. */
    public static final double TENSOR_SIGMA_PX = 4.0;

    private TileHalo() {}

    /**
     * @param pixelSizeUm          microns per pixel
     * @param rollingBallRadiusUm  rolling-ball radius in microns; 0 if off
     * @param ridgeFilter          ridge filter name; "none" if off
     * @param sigmaMaxUm           largest ridge-filter scale in microns
     * @return the halo in pixels
     */
    public static int px(double pixelSizeUm, double rollingBallRadiusUm, String ridgeFilter, double sigmaMaxUm) {
        if (!(pixelSizeUm > 0)) throw new IllegalArgumentException("pixel size must be positive: " + pixelSizeUm);
        // A white top-hat is an erosion then a dilation: twice its radius.
        int rolling = 2 * (int) Math.max(0, Math.round(rollingBallRadiusUm / pixelSizeUm));
        boolean ridge = ridgeFilter != null && !"none".equals(ridgeFilter.trim().toLowerCase(Locale.ROOT));
        // scipy truncates a Gaussian at 4 sigma.
        int ridgeReach = ridge ? (int) Math.ceil(4.0 * sigmaMaxUm / pixelSizeUm) : 0;
        int closing = 1;
        // Mask orientation: Gaussian, Scharr (one pixel), then the tensor
        // Gaussian. The intensity gradient is the same chain minus the last
        // step, so it is covered too.
        int orientation = (int) Math.ceil(4.0 * GRAD_SIGMA_PX) + 1 + (int) Math.ceil(4.0 * TENSOR_SIGMA_PX);
        return rolling + ridgeReach + closing + orientation;
    }
}
