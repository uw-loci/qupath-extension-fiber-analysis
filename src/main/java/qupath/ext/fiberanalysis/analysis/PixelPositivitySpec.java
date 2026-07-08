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

/**
 * One row in the dialog's "Pixel-positivity channels" table.
 *
 * <p>Describes a positivity rule on a single source channel: pixel is
 * positive when {@code channelValue op threshold}. The workflow reads the
 * source through {@code ImageServer.readRegion}, evaluates the rule at
 * source resolution, then box-filters the resulting binary mask to produce
 * a density channel comparable to the fiber-coverage channel.
 *
 * <p>{@link #channelIndex} is the raw server channel index (0-based) so
 * we can pull samples via {@code Raster.getSamples(...)} without going
 * through display colour mapping. {@link #channelName} is captured only
 * for OME-XML channel naming and provenance; the workflow does not look
 * it up at runtime.
 */
public final class PixelPositivitySpec {

    /** Threshold operator: pixel is positive when {@code value > threshold}. */
    public static final String OP_GT = "GT";

    /** Threshold operator: pixel is positive when {@code value < threshold}. */
    public static final String OP_LT = "LT";

    public final int channelIndex;
    public final String channelName;
    public final String op; // "GT" or "LT"
    public final double threshold;

    public PixelPositivitySpec(int channelIndex, String channelName, String op, double threshold) {
        if (channelIndex < 0) {
            throw new IllegalArgumentException("channelIndex must be non-negative, got " + channelIndex);
        }
        if (!OP_GT.equals(op) && !OP_LT.equals(op)) {
            throw new IllegalArgumentException("op must be '" + OP_GT + "' or '" + OP_LT + "', got '" + op + "'");
        }
        this.channelIndex = channelIndex;
        this.channelName = channelName == null || channelName.isBlank() ? "Channel " + channelIndex : channelName;
        this.op = op;
        this.threshold = threshold;
    }

    /** Test whether a source-pixel value is positive under this rule. */
    public boolean test(float value) {
        if (Float.isNaN(value)) return false;
        return OP_GT.equals(op) ? value > threshold : value < threshold;
    }

    /** Compact display symbol -- ">" for GT, "<" for LT. */
    public String opSymbol() {
        return OP_GT.equals(op) ? ">" : "<";
    }

    /**
     * Derived channel name used in OME-XML + measurement columns, e.g.
     * {@code "Pixel positive: DAPI > 500"}. Kept short so it fits in
     * QuPath's channel list without truncation.
     */
    public String channelDisplayName() {
        // Format threshold cleanly: integers stay integer-shaped, other
        // values get %.4g so we don't emit "500.0" for an int input.
        String thr;
        if (threshold == Math.floor(threshold) && !Double.isInfinite(threshold)) {
            long asLong = (long) threshold;
            thr = Long.toString(asLong);
        } else {
            thr = String.format(java.util.Locale.ROOT, "%.4g", threshold);
        }
        return "Pixel positive: " + channelName + " " + opSymbol() + " " + thr;
    }

    @Override
    public String toString() {
        return channelDisplayName();
    }
}
