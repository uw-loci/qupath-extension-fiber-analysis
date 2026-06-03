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
 * Fixed list of per-window scalars that get written into the density-map
 * sidecar OME-TIFF for v1.
 *
 * <p>Each entry pairs an npz key (matching {@code density_tile.py}'s
 * {@code np.savez_compressed} kwarg) with the QuPath measurement-table
 * column name we use as the OME-XML channel name. Matching the measurement
 * column name keeps the user's mental model consistent: the column they
 * see on a per-window detection ("Fiber coverage (%)") is the same string
 * they see in QuPath's channel list when the sidecar attaches as channels
 * or shows up under "Add intensity features".
 *
 * <p>A follow-up iteration will expose per-channel toggles in the dialog
 * so the user can drop channels they do not want -- v1 always writes the
 * full set so the channel ordering is stable across runs.
 */
public final class DensityChannelSpec {

    public final String npzKey;
    public final String channelName;
    public final String unit; // free-text, for OME-XML description; null if unitless

    private DensityChannelSpec(String npzKey, String channelName, String unit) {
        this.npzKey = npzKey;
        this.channelName = channelName;
        this.unit = unit;
    }

    /** Default v1 channel set in canonical order. */
    public static List<DensityChannelSpec> defaultChannels() {
        return List.of(
                new DensityChannelSpec("fiber_coverage_percent", "Fiber coverage (%)", "%"),
                new DensityChannelSpec("hdm", "HDM", null),
                new DensityChannelSpec("ridge_count", "Ridge count", null),
                new DensityChannelSpec("skeleton_length_um", "Skeleton length (um)", "um"),
                new DensityChannelSpec("branch_points", "Branch points", null),
                new DensityChannelSpec("mean_angle_deg", "Mean angle (deg)", "deg"),
                new DensityChannelSpec("order_parameter", "Order parameter", null));
    }
}
