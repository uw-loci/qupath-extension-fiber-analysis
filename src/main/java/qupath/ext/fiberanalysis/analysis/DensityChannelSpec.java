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
 *   <li><b>Classifier channels</b> ({@code classifierRole} set) -- the
 *       per-window output of a trained window classifier, one grid cell
 *       per window core. Filled by the classifier workflow, which borrows
 *       nothing from the density tile loop except this descriptor and
 *       {@link DensityTiffWriter}.</li>
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
     * rule the channel was computed for. Null for the other flavours.
     */
    public final PixelPositivitySpec pixelPositivity;

    /** {@link #classifierRole} for a single class's posterior probability. */
    public static final String ROLE_CLASS_PROBABILITY = "class_probability";

    /** {@link #classifierRole} for the argmax class index, as a float. */
    public static final String ROLE_PREDICTED_CLASS = "predicted_class";

    /** {@link #classifierRole} for the top-1 probability. */
    public static final String ROLE_MAX_PROBABILITY = "max_probability";

    /** {@link #classifierRole} for the top1-minus-top2 gap. */
    public static final String ROLE_MARGIN = "margin";

    /** {@link #classifierRole} for the novelty p-value (Mahalanobis, chi-square tail). */
    public static final String ROLE_NOVELTY = "novelty";

    /**
     * For classifier channels, which of the classifier's outputs this channel
     * carries -- one of the {@code ROLE_*} constants. Null for the other
     * flavours.
     */
    public final String classifierRole;

    private DensityChannelSpec(
            String npzKey,
            String channelName,
            String unit,
            String objectClassName,
            PixelPositivitySpec pixelPositivity,
            String classifierRole) {
        this.npzKey = npzKey;
        this.channelName = channelName;
        this.unit = unit;
        this.objectClassName = objectClassName;
        this.pixelPositivity = pixelPositivity;
        this.classifierRole = classifierRole;
    }

    /** Convenience: is this a class-presence channel rather than a fiber-derived one. */
    public boolean isObjectDensity() {
        return objectClassName != null;
    }

    /** Convenience: is this a source-pixel-threshold density channel. */
    public boolean isPixelPositivity() {
        return pixelPositivity != null;
    }

    /** Convenience: is this a window-classifier output channel. */
    public boolean isClassifier() {
        return classifierRole != null;
    }

    /**
     * The class a {@link #ROLE_CLASS_PROBABILITY} channel carries the
     * probability of. Null for every other channel flavour, including the
     * other classifier roles.
     *
     * <p>Reuses {@link #objectClassName} as the carrier: both mean "the
     * PathClass this channel is about", and a channel is never both an
     * object-density and a classifier channel.
     */
    public String classProbabilityClassName() {
        return ROLE_CLASS_PROBABILITY.equals(classifierRole) ? objectClassName : null;
    }

    /**
     * @return 180 for the mean-orientation channel (an axial angle in degrees, where 1 and 179
     *     are two degrees apart), 0 for every other channel
     */
    public double axialPeriod() {
        return "mean_angle_deg".equals(npzKey) ? 180.0 : 0.0;
    }

    /** Default v1 channel set in canonical order. */
    public static List<DensityChannelSpec> defaultChannels() {
        return List.of(
                new DensityChannelSpec("fiber_coverage_percent", "Fiber coverage (%)", "%", null, null, null),
                new DensityChannelSpec("hdm", "HDM", null, null, null, null),
                new DensityChannelSpec("ridge_count", "Ridge count", null, null, null, null),
                new DensityChannelSpec("skeleton_length_um", "Skeleton length (um)", "um", null, null, null),
                new DensityChannelSpec("branch_points", "Branch points", null, null, null, null),
                new DensityChannelSpec("mean_angle_deg", "Mean angle (deg)", "deg", null, null, null),
                new DensityChannelSpec("order_parameter", "Order parameter", null, null, null, null),
                new DensityChannelSpec(
                        "signal_density", "Signal density (no threshold)", "fraction of full scale", null, null, null),
                new DensityChannelSpec(
                        "intensity_coherence", "Intensity coherence (no threshold)", null, null, null, null));
    }

    /**
     * Build an object-density channel for the given class. Channel name
     * is prefixed with "Object density:" so it is visually distinct from
     * fiber channels in QuPath's channel list and OME-XML.
     */
    public static DensityChannelSpec forObjectClass(String className) {
        return new DensityChannelSpec(null, "Object density: " + className, "fraction", className, null, null);
    }

    /**
     * Build a pixel-positivity density channel for the given rule. Channel
     * name is prefixed with "Pixel positive:" so it is visually distinct
     * from fiber and object-density channels.
     */
    public static DensityChannelSpec forPixelPositivity(PixelPositivitySpec spec) {
        return new DensityChannelSpec(null, spec.channelDisplayName(), "fraction", null, spec, null);
    }

    /**
     * Posterior probability channel for one predicted class.
     *
     * <p>Named {@code P(<class>)} so the channel list, the OME-XML and the
     * core detections' measurement table all use the same string.
     *
     * @param className the {@code PathClass.toString()} this channel scores
     * @return the channel descriptor
     */
    public static DensityChannelSpec forClassProbability(String className) {
        return new DensityChannelSpec(
                null, "P(" + className + ")", "probability", className, null, ROLE_CLASS_PROBABILITY);
    }

    /**
     * Argmax class index channel, written as a float whose integral value
     * indexes the model's {@code class_names}.
     *
     * <p>Not a quantity: do not average it, do not smooth it. The index is
     * stored rather than the name because the sidecar is numeric, and it is
     * only interpretable beside the model metadata that names the classes.
     *
     * @return the channel descriptor
     */
    public static DensityChannelSpec forPredictedClassIndex() {
        return new DensityChannelSpec(null, "Predicted class index", null, null, null, ROLE_PREDICTED_CLASS);
    }

    /**
     * Top-1 probability channel -- how confident the model is in whatever it
     * predicted, without saying what that was.
     *
     * @return the channel descriptor
     */
    public static DensityChannelSpec forMaxProbability() {
        return new DensityChannelSpec(null, "Max probability", "probability", null, null, ROLE_MAX_PROBABILITY);
    }

    /**
     * Top1-minus-top2 channel. Separates "confident" from "undecided between
     * two classes", which the max probability alone cannot: 0.45 against
     * 0.44 and 0.45 against 0.05 read identically there.
     *
     * @return the channel descriptor
     */
    public static DensityChannelSpec forMargin() {
        return new DensityChannelSpec(null, "Margin (top1 - top2)", "probability", null, null, ROLE_MARGIN);
    }

    /**
     * Novelty channel: the chi-square tail probability of a window's robust
     * Mahalanobis distance from the training distribution. Low means "this
     * window does not look like anything the model was trained on".
     *
     * <p>It is a p-value, not a posterior. A closed-set softmax will label
     * unseen tissue confidently, and this channel is the only output that
     * can say so.
     *
     * @return the channel descriptor
     */
    public static DensityChannelSpec forNovelty() {
        return new DensityChannelSpec(null, "Novelty p", "probability", null, null, ROLE_NOVELTY);
    }
}
