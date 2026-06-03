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

import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;

/**
 * Immutable parameter bundle for a single Fiber Analysis run.
 *
 * <p>This record IS the dialog's output and the Appose script's input. Field
 * names match the dialog control names from {@code 02_ui_design.md} sections
 * 1-7. The Python script reads these by the snake_case names produced in
 * {@link FiberAnalysisWorkflow#buildScriptInputs}.
 */
public record FiberAnalysisParams(
        // Section 1 -- Search area
        // searchArea: "selected" = currently selected annotations in the viewer;
        //             "all"      = every annotation on the current image;
        //             "class"    = annotations whose class name is in classFilter
        //                          (comma-separated; case-sensitive class names).
        String searchArea,
        String classFilter,
        double borderZoneUm,
        String zoneMode, // "inside" | "outside" | "both"
        boolean useImagePixelSize,
        double pixelSizeOverrideUm,

        // Section 2 -- Segmentation
        String segSource, // "internal" | "existing"
        String internalChannel,
        String thresholdMethod, // "Otsu" | "Triangle" | "Manual" | "Project Otsu (calibrated)"
        int manualThreshold,
        String ridgeFilter, // "None" | "Frangi" | "Sato" | "Meijering"
        // sigma values are in microns; Java converts to pixels per-image
        // using the image's measured pixel size before passing to Python.
        double sigmaMinUm,
        double sigmaMaxUm,
        double sigmaStepUm,
        // Minimum fiber area in square microns (was square pixels in v0.1).
        double minFiberAreaUm2,
        // Project-calibrated threshold: which calibration file under
        // <project>/fiber-analysis/ to use. Resolved to a float at run time
        // and passed through `project_threshold_norm`.
        String projectCalibrationName,
        // Bright<->dark flip before thresholding -- needed for DAB / chromogenic
        // brightfield where collagen appears DARKER than background.
        boolean invertIntensity,
        // Rolling-ball background-subtract radius in microns. 0 disables.
        double rollingBallRadiusUm,
        String maskSource, // "Pixel classifier" | "Object class" | "File on disk"
        String classifierName,
        String objectClass,
        String maskFile,

        // Section 3 -- Window analysis
        boolean windowEnabled,
        double windowSizeUm,
        int windowOverlapPercent,
        boolean windowObjects,
        // Minimum fiber coverage % a window must hit to be reported.
        // Below this, the window is dropped from PathObject creation and
        // rendered transparent in every heatmap.
        double minWindowCoveragePercent,

        // Section 4 -- Straightness
        boolean straightnessEnabled,
        boolean tortuosityOn,
        boolean radonOn,
        double minBranchUm,

        // Section 5 -- Morphometrics
        boolean morphEnabled,
        boolean branchpoints,
        boolean endpoints,
        boolean length,
        boolean curvature,
        boolean hdm,
        boolean lacunarity,
        boolean fractal,
        boolean gapAnalysis,
        // Comma-separated lists in microns. Java converts to integer pixel
        // sizes per image before passing to Python (preserves the per-image
        // pixel-size resolution the user expects).
        String lacBoxSizesUm,
        String fractalBoxSizesUm,

        // Section 6 -- Texture
        boolean textureEnabled,
        int quantLevels,
        String glcmDistancesUm,
        boolean contrast,
        boolean correlation,
        boolean energy,
        boolean homogeneity,
        boolean entropy,
        boolean dissimilarity,

        // Section 7 -- Output
        String outputDir,
        boolean fiberMaskOverlay,
        boolean straightnessHeatmap,
        boolean glcmHeatmap,
        String glcmHeatmapProp,
        boolean morphSummary,
        boolean jsonSidecar,
        boolean emitNpz) {

    /**
     * Builds a {@link FiberAnalysisParams} populated from current preference values.
     */
    public static FiberAnalysisParams fromPreferences() {
        return new FiberAnalysisParams(
                // Section 1
                FiberAnalysisPreferences.searchAreaProperty().get(),
                FiberAnalysisPreferences.classFilterProperty().get(),
                FiberAnalysisPreferences.borderZoneUmProperty().get(),
                FiberAnalysisPreferences.zoneModeProperty().get(),
                FiberAnalysisPreferences.useImagePixelSizeProperty().get(),
                FiberAnalysisPreferences.pixelSizeOverrideUmProperty().get(),
                // Section 2
                FiberAnalysisPreferences.segSourceProperty().get(),
                FiberAnalysisPreferences.internalChannelProperty().get(),
                FiberAnalysisPreferences.thresholdMethodProperty().get(),
                FiberAnalysisPreferences.manualThresholdProperty().get(),
                FiberAnalysisPreferences.ridgeFilterProperty().get(),
                FiberAnalysisPreferences.sigmaMinProperty().get(),
                FiberAnalysisPreferences.sigmaMaxProperty().get(),
                FiberAnalysisPreferences.sigmaStepProperty().get(),
                FiberAnalysisPreferences.minFiberAreaUm2Property().get(),
                FiberAnalysisPreferences.projectCalibrationNameProperty().get(),
                FiberAnalysisPreferences.invertIntensityProperty().get(),
                FiberAnalysisPreferences.rollingBallRadiusUmProperty().get(),
                FiberAnalysisPreferences.maskSourceProperty().get(),
                FiberAnalysisPreferences.classifierNameProperty().get(),
                FiberAnalysisPreferences.objectClassProperty().get(),
                FiberAnalysisPreferences.maskFileProperty().get(),
                // Section 3
                FiberAnalysisPreferences.windowEnabledProperty().get(),
                FiberAnalysisPreferences.windowSizeUmProperty().get(),
                FiberAnalysisPreferences.windowOverlapPercentProperty().get(),
                FiberAnalysisPreferences.windowObjectsProperty().get(),
                FiberAnalysisPreferences.minWindowCoveragePercentProperty().get(),
                // Section 4
                FiberAnalysisPreferences.straightnessEnabledProperty().get(),
                FiberAnalysisPreferences.tortuosityOnProperty().get(),
                FiberAnalysisPreferences.radonOnProperty().get(),
                FiberAnalysisPreferences.minBranchUmProperty().get(),
                // Section 5
                FiberAnalysisPreferences.morphEnabledProperty().get(),
                FiberAnalysisPreferences.branchpointsProperty().get(),
                FiberAnalysisPreferences.endpointsProperty().get(),
                FiberAnalysisPreferences.lengthProperty().get(),
                FiberAnalysisPreferences.curvatureProperty().get(),
                FiberAnalysisPreferences.hdmProperty().get(),
                FiberAnalysisPreferences.lacunarityProperty().get(),
                FiberAnalysisPreferences.fractalProperty().get(),
                FiberAnalysisPreferences.gapAnalysisProperty().get(),
                FiberAnalysisPreferences.lacBoxSizesPxProperty()
                        .get(), // now microns; property name kept for back-compat
                FiberAnalysisPreferences.fractalBoxSizesPxProperty().get(), // now microns
                // Section 6
                FiberAnalysisPreferences.textureEnabledProperty().get(),
                FiberAnalysisPreferences.quantLevelsProperty().get(),
                FiberAnalysisPreferences.glcmDistancesPxProperty().get(), // now microns
                FiberAnalysisPreferences.contrastProperty().get(),
                FiberAnalysisPreferences.correlationProperty().get(),
                FiberAnalysisPreferences.energyProperty().get(),
                FiberAnalysisPreferences.homogeneityProperty().get(),
                FiberAnalysisPreferences.entropyProperty().get(),
                FiberAnalysisPreferences.dissimilarityProperty().get(),
                // Section 7
                FiberAnalysisPreferences.outputDirProperty().get(),
                FiberAnalysisPreferences.fiberMaskOverlayProperty().get(),
                FiberAnalysisPreferences.straightnessHeatmapProperty().get(),
                FiberAnalysisPreferences.glcmHeatmapProperty().get(),
                FiberAnalysisPreferences.glcmHeatmapPropProperty().get(),
                FiberAnalysisPreferences.morphSummaryProperty().get(),
                FiberAnalysisPreferences.jsonSidecarProperty().get(),
                FiberAnalysisPreferences.emitNpzProperty().get());
    }
}
