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
 * Descriptor for one output channel in the density-map sidecar OME-TIFF.
 *
 * <p>Three flavours live under this record:
 *
 * <ol>
 *   <li><b>Fiber-derived channels</b> ({@code npzKey} set, everything else
 *       null) -- values come from {@code density_tile.py} via the tile
 *       accumulator.</li>
 *   <li><b>Object-density channels</b> ({@code objectClassName} set) --
 *       fraction of the local box covered by any object of the class.
 *       Computed by {@link ObjectDensityComputer} after the tile loop.</li>
 *   <li><b>Pixel-positivity channels</b> ({@code pixelPositivity} set) --
 *       fraction of the local box where a source-channel pixel meets a
 *       threshold rule. Computed by {@link PixelPositivityComputer} after
 *       the tile loop.</li>
 * </ol>
 *
 * <p>{@code channelName} is the OME-XML channel name AND the QuPath
 * measurement-table column name -- keeping them identical means the user
 * sees the same string in the channel list and in the measurement table.
 */
public final class DensityChannelSpec {

    /**
     * For fiber channels, the npz array key {@code density_tile.py} writes
     * under. Null for object-density and pixel-positivity channels.
     */
    public final String npzKey;

    public final String channelName;
    public final String unit; // free-text, for OME-XML description; null if unitless

    /**
     * For object-density channels, the {@code PathClass.toString()} the
     * channel was computed for. Null for the other two flavours.
     */
    public final String objectClassName;

    /**
     * For pixel-positivity channels, the source-channel + operator + threshold
     * rule the channel was computed for. Null for the other two flavours.
     */
    public final PixelPositivitySpec pixelPositivity;

    private DensityChannelSpec(
            String npzKey,
            String channelName,
            String unit,
            String objectClassName,
            PixelPositivitySpec pixelPositivity) {
        this.npzKey = npzKey;
        this.channelName = channelName;
        this.unit = unit;
        this.objectClassName = objectClassName;
        this.pixelPositivity = pixelPositivity;
    }

    /** Convenience: is this a class-presence channel rather than a fiber-derived one. */
    public boolean isObjectDensity() {
        return objectClassName != null;
    }

    /** Convenience: is this a source-pixel-threshold density channel. */
    public boolean isPixelPositivity() {
        return pixelPositivity != null;
    }

    /** Default v1 channel set in canonical order. */
    public static List<DensityChannelSpec> defaultChannels() {
        return List.of(
                new DensityChannelSpec("fiber_coverage_percent", "Fiber coverage (%)", "%", null, null),
                new DensityChannelSpec("hdm", "HDM", null, null, null),
                new DensityChannelSpec("ridge_count", "Ridge count", null, null, null),
                new DensityChannelSpec("skeleton_length_um", "Skeleton length (um)", "um", null, null),
                new DensityChannelSpec("branch_points", "Branch points", null, null, null),
                new DensityChannelSpec("mean_angle_deg", "Mean angle (deg)", "deg", null, null),
                new DensityChannelSpec("order_parameter", "Order parameter", null, null, null));
    }

    /**
     * Build an object-density channel for the given class. Channel name
     * is prefixed with "Object density:" so it is visually distinct from
     * fiber channels in QuPath's channel list and OME-XML.
     */
    public static DensityChannelSpec forObjectClass(String className) {
        return new DensityChannelSpec(null, "Object density: " + className, "fraction", className, null);
    }

    /**
     * Build a pixel-positivity density channel for the given rule. Channel
     * name is prefixed with "Pixel positive:" so it is visually distinct
     * from fiber and object-density channels.
     */
    public static DensityChannelSpec forPixelPositivity(PixelPositivitySpec spec) {
        return new DensityChannelSpec(null, spec.channelDisplayName(), "fraction", null, spec);
    }
}
