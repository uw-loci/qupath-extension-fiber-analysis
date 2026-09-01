/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the Fiber Analysis extension.
 *
 * <p>All defaults match the "Default" column of {@code 02_ui_design.md} sections 1-7.
 * The dialog reads these on open and writes them back when the user clicks
 * "Save defaults".
 *
 * <p>Pattern source: {@code qupath-extension-confusion-matrix/.../CMPreferences.java}.
 */
public class FiberAnalysisPreferences {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisPreferences.class);
    private static final String PREFIX = "fiberanalysis.";

    private static final String CATEGORY_ENV = "Fiber Analysis: Python environment";

    // ==================== Python environment ====================
    //
    // DUPLICATED ACROSS THE APPOSE EXTENSIONS. The same pair of preferences and
    // the same ApposeEnvLocation helper exist in QP-CAT, cellAPpose, the DL
    // pixel classifier and PPM. No shared library yet -- see
    // claude-reports/TODO_LIST.md, "shared Appose env-location library". Change
    // all five together or they diverge.

    /** Base dir for the Appose env; blank means the Appose default. */
    private static javafx.beans.property.StringProperty envBaseDir;

    /** Where an env was last successfully built. Bookkeeping, not a setting. */
    private static javafx.beans.property.StringProperty envLastBuiltDir;

    // === Section 1 -- Search area ===
    public static final String DEFAULT_SEARCH_AREA = "selected";
    public static final String DEFAULT_CLASS_FILTER = "";
    public static final double DEFAULT_BORDER_ZONE_UM = 50.0;
    public static final String DEFAULT_ZONE_MODE = "outside";
    public static final boolean DEFAULT_USE_IMAGE_PIXEL_SIZE = true;
    public static final double DEFAULT_PIXEL_SIZE_OVERRIDE_UM = 0.5;

    // === Section 2 -- Segmentation ===
    public static final String DEFAULT_SEG_SOURCE = "internal";
    public static final String DEFAULT_INTERNAL_CHANNEL = "Raw intensity";
    public static final String DEFAULT_THRESHOLD_METHOD = "Otsu";
    public static final int DEFAULT_MANUAL_THRESHOLD = 128;
    public static final String DEFAULT_RIDGE_FILTER = "None";
    // All spatial inputs are in microns. The Python pipeline still needs
    // pixel-domain values for sigma / area / box-size; Java converts at the
    // buildScriptInputs boundary using each image's measured pixel size.
    // Defaults below assume a typical "fiber half-width 1-3 um" use case.
    public static final double DEFAULT_SIGMA_MIN_UM = 1.0;
    public static final double DEFAULT_SIGMA_MAX_UM = 4.0;
    public static final double DEFAULT_SIGMA_STEP_UM = 1.0;
    // Minimum fiber area in square microns -- 1 um^2 ~ 100 px^2 at 0.1 um/px.
    public static final double DEFAULT_MIN_FIBER_AREA_UM2 = 1.0;
    public static final String DEFAULT_PROJECT_CALIBRATION_NAME = "";
    public static final boolean DEFAULT_INVERT_INTENSITY = false;
    // Rolling-ball radius in microns; 0 disables.
    public static final double DEFAULT_ROLLING_BALL_RADIUS_UM = 0.0;
    public static final String DEFAULT_MASK_SOURCE = "Pixel classifier";
    public static final String DEFAULT_CLASSIFIER_NAME = "";
    public static final String DEFAULT_OBJECT_CLASS = "";
    public static final String DEFAULT_MASK_FILE = "";

    // === Section 3 -- Window analysis ===
    public static final boolean DEFAULT_WINDOW_ENABLED = true;
    public static final double DEFAULT_WINDOW_SIZE_UM = 15.0;
    public static final int DEFAULT_WINDOW_OVERLAP_PERCENT = 0;
    public static final boolean DEFAULT_WINDOW_OBJECTS = false;
    // Trace each per-annotation fiber mask into split connected-component
    // detections (one per fiber blob) under the source annotation, classed
    // as CollagenAnalysis. ON by default so the analyzed fibers are
    // immediately queryable / classifiable in the hierarchy.
    public static final boolean DEFAULT_COLLAGEN_OBJECTS = true;
    // Minimum fiber coverage % per window for it to be reported. Below this,
    // the window is excluded from PathObject creation AND its heatmap cell
    // is rendered transparent. Default 5% suppresses the corners-of-rounded-
    // annotations bias without dropping legitimately sparse regions.
    public static final double DEFAULT_MIN_WINDOW_COVERAGE_PERCENT = 5.0;

    // === Section 4 -- Straightness ===
    public static final boolean DEFAULT_STRAIGHTNESS_ENABLED = true;
    public static final boolean DEFAULT_TORTUOSITY_ON = true;
    public static final boolean DEFAULT_RADON_ON = true;
    public static final double DEFAULT_MIN_BRANCH_UM = 5.0;

    // === Section 5 -- Morphometrics ===
    public static final boolean DEFAULT_MORPH_ENABLED = true;
    public static final boolean DEFAULT_BRANCHPOINTS = true;
    public static final boolean DEFAULT_ENDPOINTS = true;
    public static final boolean DEFAULT_LENGTH = true;
    public static final boolean DEFAULT_CURVATURE = true;
    public static final boolean DEFAULT_HDM = true;
    public static final boolean DEFAULT_LACUNARITY = true;
    public static final boolean DEFAULT_FRACTAL = true;
    public static final boolean DEFAULT_GAP_ANALYSIS = true;
    // Box sizes in microns. Java converts to integer pixel sizes per image
    // before passing to Python. Defaults span 0.5 -- 16 um, a sensible
    // range for connective-tissue lacunarity / fractal-dimension analysis.
    public static final String DEFAULT_LAC_BOX_SIZES_UM = "0.5,1,2,4,8";
    public static final String DEFAULT_FRACTAL_BOX_SIZES_UM = "0.25,0.5,1,2,4,8,16";

    // === Section 6 -- Texture ===
    public static final boolean DEFAULT_TEXTURE_ENABLED = true;
    public static final int DEFAULT_QUANT_LEVELS = 16;
    // GLCM offset distances in microns; Java converts to integer pixels per
    // image. 0.1-0.3 um covers the typical fiber-width texture scale.
    public static final String DEFAULT_GLCM_DISTANCES_UM = "0.1,0.2,0.3";
    public static final boolean DEFAULT_CONTRAST = true;
    public static final boolean DEFAULT_CORRELATION = true;
    public static final boolean DEFAULT_ENERGY = true;
    public static final boolean DEFAULT_HOMOGENEITY = true;
    public static final boolean DEFAULT_ENTROPY = true;
    public static final boolean DEFAULT_DISSIMILARITY = true;

    // === Section 7 -- Output ===
    public static final String DEFAULT_OUTPUT_DIR = "";
    public static final boolean DEFAULT_FIBER_MASK_OVERLAY = true;
    public static final boolean DEFAULT_STRAIGHTNESS_HEATMAP = true;
    public static final boolean DEFAULT_GLCM_HEATMAP = true;
    public static final String DEFAULT_GLCM_HEATMAP_PROP = "contrast";
    public static final boolean DEFAULT_MORPH_SUMMARY = true;
    public static final boolean DEFAULT_JSON_SIDECAR = true;
    public static final boolean DEFAULT_EMIT_NPZ = false;

    // === Properties (initialized in installPreferences) ===
    private static StringProperty searchArea;
    private static StringProperty classFilter;
    private static DoubleProperty borderZoneUm;
    private static StringProperty zoneMode;
    private static BooleanProperty useImagePixelSize;
    private static DoubleProperty pixelSizeOverrideUm;

    private static StringProperty segSource;
    private static StringProperty internalChannel;
    private static StringProperty thresholdMethod;
    private static IntegerProperty manualThreshold;
    private static StringProperty ridgeFilter;
    private static DoubleProperty sigmaMin;
    private static DoubleProperty sigmaMax;
    private static DoubleProperty sigmaStep;
    private static DoubleProperty minFiberAreaUm2;
    private static StringProperty projectCalibrationName;
    private static BooleanProperty invertIntensity;
    private static DoubleProperty rollingBallRadiusUm;
    private static StringProperty maskSource;
    private static StringProperty classifierName;
    private static StringProperty objectClass;
    private static StringProperty maskFile;

    private static BooleanProperty windowEnabled;
    private static DoubleProperty windowSizeUm;
    private static IntegerProperty windowOverlapPercent;
    private static BooleanProperty windowObjects;
    private static BooleanProperty collagenObjects;
    private static DoubleProperty minWindowCoveragePercent;

    private static BooleanProperty straightnessEnabled;
    private static BooleanProperty tortuosityOn;
    private static BooleanProperty radonOn;
    private static DoubleProperty minBranchUm;

    private static BooleanProperty morphEnabled;
    private static BooleanProperty branchpoints;
    private static BooleanProperty endpoints;
    private static BooleanProperty length;
    private static BooleanProperty curvature;
    private static BooleanProperty hdm;
    private static BooleanProperty lacunarity;
    private static BooleanProperty fractal;
    private static BooleanProperty gapAnalysis;
    private static StringProperty lacBoxSizesPx;
    private static StringProperty fractalBoxSizesPx;

    private static BooleanProperty textureEnabled;
    private static IntegerProperty quantLevels;
    private static StringProperty glcmDistancesPx;
    private static BooleanProperty contrast;
    private static BooleanProperty correlation;
    private static BooleanProperty energy;
    private static BooleanProperty homogeneity;
    private static BooleanProperty entropy;
    private static BooleanProperty dissimilarity;

    private static StringProperty outputDir;
    private static BooleanProperty fiberMaskOverlay;
    private static BooleanProperty straightnessHeatmap;
    private static BooleanProperty glcmHeatmap;
    private static StringProperty glcmHeatmapProp;
    private static BooleanProperty morphSummary;
    private static BooleanProperty jsonSidecar;
    private static BooleanProperty emitNpz;

    private static boolean installed = false;

    private FiberAnalysisPreferences() {}

    /**
     * Installs persistent preferences. Idempotent.
     */
    public static synchronized void installPreferences() {
        if (installed) {
            return;
        }
        logger.info("Installing Fiber Analysis preferences");

        envBaseDir = PathPrefs.createPersistentPreference(PREFIX + "env.baseDir", "");
        envLastBuiltDir = PathPrefs.createPersistentPreference(PREFIX + "env.lastBuiltDir", "");
        searchArea = PathPrefs.createPersistentPreference(PREFIX + "searchArea", DEFAULT_SEARCH_AREA);
        classFilter = PathPrefs.createPersistentPreference(PREFIX + "classFilter", DEFAULT_CLASS_FILTER);
        borderZoneUm = PathPrefs.createPersistentPreference(PREFIX + "borderZoneUm", DEFAULT_BORDER_ZONE_UM);
        zoneMode = PathPrefs.createPersistentPreference(PREFIX + "zoneMode", DEFAULT_ZONE_MODE);
        useImagePixelSize =
                PathPrefs.createPersistentPreference(PREFIX + "useImagePixelSize", DEFAULT_USE_IMAGE_PIXEL_SIZE);
        pixelSizeOverrideUm =
                PathPrefs.createPersistentPreference(PREFIX + "pixelSizeOverrideUm", DEFAULT_PIXEL_SIZE_OVERRIDE_UM);

        segSource = PathPrefs.createPersistentPreference(PREFIX + "segSource", DEFAULT_SEG_SOURCE);
        internalChannel = PathPrefs.createPersistentPreference(PREFIX + "internalChannel", DEFAULT_INTERNAL_CHANNEL);
        thresholdMethod = PathPrefs.createPersistentPreference(PREFIX + "thresholdMethod", DEFAULT_THRESHOLD_METHOD);
        manualThreshold = PathPrefs.createPersistentPreference(PREFIX + "manualThreshold", DEFAULT_MANUAL_THRESHOLD);
        ridgeFilter = PathPrefs.createPersistentPreference(PREFIX + "ridgeFilter", DEFAULT_RIDGE_FILTER);
        // New pref keys (sigmaMinUm etc.) because we switched units; legacy
        // keys (sigmaMin in px) would otherwise silently be re-interpreted as
        // microns for users upgrading from v0.2.0-SNAPSHOT pre-2026-05-27.
        sigmaMin = PathPrefs.createPersistentPreference(PREFIX + "sigmaMinUm", DEFAULT_SIGMA_MIN_UM);
        sigmaMax = PathPrefs.createPersistentPreference(PREFIX + "sigmaMaxUm", DEFAULT_SIGMA_MAX_UM);
        sigmaStep = PathPrefs.createPersistentPreference(PREFIX + "sigmaStepUm", DEFAULT_SIGMA_STEP_UM);
        minFiberAreaUm2 = PathPrefs.createPersistentPreference(PREFIX + "minFiberAreaUm2", DEFAULT_MIN_FIBER_AREA_UM2);
        projectCalibrationName = PathPrefs.createPersistentPreference(
                PREFIX + "projectCalibrationName", DEFAULT_PROJECT_CALIBRATION_NAME);
        invertIntensity = PathPrefs.createPersistentPreference(PREFIX + "invertIntensity", DEFAULT_INVERT_INTENSITY);
        rollingBallRadiusUm =
                PathPrefs.createPersistentPreference(PREFIX + "rollingBallRadiusUm", DEFAULT_ROLLING_BALL_RADIUS_UM);
        maskSource = PathPrefs.createPersistentPreference(PREFIX + "maskSource", DEFAULT_MASK_SOURCE);
        classifierName = PathPrefs.createPersistentPreference(PREFIX + "classifierName", DEFAULT_CLASSIFIER_NAME);
        objectClass = PathPrefs.createPersistentPreference(PREFIX + "objectClass", DEFAULT_OBJECT_CLASS);
        maskFile = PathPrefs.createPersistentPreference(PREFIX + "maskFile", DEFAULT_MASK_FILE);

        windowEnabled = PathPrefs.createPersistentPreference(PREFIX + "windowEnabled", DEFAULT_WINDOW_ENABLED);
        windowSizeUm = PathPrefs.createPersistentPreference(PREFIX + "windowSizeUm", DEFAULT_WINDOW_SIZE_UM);
        windowOverlapPercent =
                PathPrefs.createPersistentPreference(PREFIX + "windowOverlapPercent", DEFAULT_WINDOW_OVERLAP_PERCENT);
        windowObjects = PathPrefs.createPersistentPreference(PREFIX + "windowObjects", DEFAULT_WINDOW_OBJECTS);
        collagenObjects = PathPrefs.createPersistentPreference(PREFIX + "collagenObjects", DEFAULT_COLLAGEN_OBJECTS);
        minWindowCoveragePercent = PathPrefs.createPersistentPreference(
                PREFIX + "minWindowCoveragePercent", DEFAULT_MIN_WINDOW_COVERAGE_PERCENT);

        straightnessEnabled =
                PathPrefs.createPersistentPreference(PREFIX + "straightnessEnabled", DEFAULT_STRAIGHTNESS_ENABLED);
        tortuosityOn = PathPrefs.createPersistentPreference(PREFIX + "tortuosityOn", DEFAULT_TORTUOSITY_ON);
        radonOn = PathPrefs.createPersistentPreference(PREFIX + "radonOn", DEFAULT_RADON_ON);
        minBranchUm = PathPrefs.createPersistentPreference(PREFIX + "minBranchUm", DEFAULT_MIN_BRANCH_UM);

        morphEnabled = PathPrefs.createPersistentPreference(PREFIX + "morphEnabled", DEFAULT_MORPH_ENABLED);
        branchpoints = PathPrefs.createPersistentPreference(PREFIX + "branchpoints", DEFAULT_BRANCHPOINTS);
        endpoints = PathPrefs.createPersistentPreference(PREFIX + "endpoints", DEFAULT_ENDPOINTS);
        length = PathPrefs.createPersistentPreference(PREFIX + "length", DEFAULT_LENGTH);
        curvature = PathPrefs.createPersistentPreference(PREFIX + "curvature", DEFAULT_CURVATURE);
        hdm = PathPrefs.createPersistentPreference(PREFIX + "hdm", DEFAULT_HDM);
        lacunarity = PathPrefs.createPersistentPreference(PREFIX + "lacunarity", DEFAULT_LACUNARITY);
        fractal = PathPrefs.createPersistentPreference(PREFIX + "fractal", DEFAULT_FRACTAL);
        gapAnalysis = PathPrefs.createPersistentPreference(PREFIX + "gapAnalysis", DEFAULT_GAP_ANALYSIS);
        lacBoxSizesPx = PathPrefs.createPersistentPreference(PREFIX + "lacBoxSizesUm", DEFAULT_LAC_BOX_SIZES_UM);
        fractalBoxSizesPx =
                PathPrefs.createPersistentPreference(PREFIX + "fractalBoxSizesUm", DEFAULT_FRACTAL_BOX_SIZES_UM);

        textureEnabled = PathPrefs.createPersistentPreference(PREFIX + "textureEnabled", DEFAULT_TEXTURE_ENABLED);
        quantLevels = PathPrefs.createPersistentPreference(PREFIX + "quantLevels", DEFAULT_QUANT_LEVELS);
        glcmDistancesPx = PathPrefs.createPersistentPreference(PREFIX + "glcmDistancesUm", DEFAULT_GLCM_DISTANCES_UM);
        contrast = PathPrefs.createPersistentPreference(PREFIX + "contrast", DEFAULT_CONTRAST);
        correlation = PathPrefs.createPersistentPreference(PREFIX + "correlation", DEFAULT_CORRELATION);
        energy = PathPrefs.createPersistentPreference(PREFIX + "energy", DEFAULT_ENERGY);
        homogeneity = PathPrefs.createPersistentPreference(PREFIX + "homogeneity", DEFAULT_HOMOGENEITY);
        entropy = PathPrefs.createPersistentPreference(PREFIX + "entropy", DEFAULT_ENTROPY);
        dissimilarity = PathPrefs.createPersistentPreference(PREFIX + "dissimilarity", DEFAULT_DISSIMILARITY);

        outputDir = PathPrefs.createPersistentPreference(PREFIX + "outputDir", DEFAULT_OUTPUT_DIR);
        fiberMaskOverlay =
                PathPrefs.createPersistentPreference(PREFIX + "fiberMaskOverlay", DEFAULT_FIBER_MASK_OVERLAY);
        straightnessHeatmap =
                PathPrefs.createPersistentPreference(PREFIX + "straightnessHeatmap", DEFAULT_STRAIGHTNESS_HEATMAP);
        glcmHeatmap = PathPrefs.createPersistentPreference(PREFIX + "glcmHeatmap", DEFAULT_GLCM_HEATMAP);
        glcmHeatmapProp = PathPrefs.createPersistentPreference(PREFIX + "glcmHeatmapProp", DEFAULT_GLCM_HEATMAP_PROP);
        morphSummary = PathPrefs.createPersistentPreference(PREFIX + "morphSummary", DEFAULT_MORPH_SUMMARY);
        jsonSidecar = PathPrefs.createPersistentPreference(PREFIX + "jsonSidecar", DEFAULT_JSON_SIDECAR);
        emitNpz = PathPrefs.createPersistentPreference(PREFIX + "emitNpz", DEFAULT_EMIT_NPZ);

        installed = true;
        logger.info("Fiber Analysis preferences installed");
    }

    // ===== Property accessors =====
    public static StringProperty searchAreaProperty() {
        return searchArea;
    }

    public static StringProperty classFilterProperty() {
        return classFilter;
    }

    public static DoubleProperty borderZoneUmProperty() {
        return borderZoneUm;
    }

    public static StringProperty zoneModeProperty() {
        return zoneMode;
    }

    public static BooleanProperty useImagePixelSizeProperty() {
        return useImagePixelSize;
    }

    public static DoubleProperty pixelSizeOverrideUmProperty() {
        return pixelSizeOverrideUm;
    }

    public static StringProperty segSourceProperty() {
        return segSource;
    }

    public static StringProperty internalChannelProperty() {
        return internalChannel;
    }

    public static StringProperty thresholdMethodProperty() {
        return thresholdMethod;
    }

    public static IntegerProperty manualThresholdProperty() {
        return manualThreshold;
    }

    public static StringProperty ridgeFilterProperty() {
        return ridgeFilter;
    }

    public static DoubleProperty sigmaMinProperty() {
        return sigmaMin;
    }

    public static DoubleProperty sigmaMaxProperty() {
        return sigmaMax;
    }

    public static DoubleProperty sigmaStepProperty() {
        return sigmaStep;
    }

    public static DoubleProperty minFiberAreaUm2Property() {
        return minFiberAreaUm2;
    }

    public static StringProperty maskSourceProperty() {
        return maskSource;
    }

    public static StringProperty projectCalibrationNameProperty() {
        return projectCalibrationName;
    }

    public static BooleanProperty invertIntensityProperty() {
        return invertIntensity;
    }

    public static DoubleProperty rollingBallRadiusUmProperty() {
        return rollingBallRadiusUm;
    }

    public static StringProperty classifierNameProperty() {
        return classifierName;
    }

    public static StringProperty objectClassProperty() {
        return objectClass;
    }

    public static StringProperty maskFileProperty() {
        return maskFile;
    }

    public static BooleanProperty windowEnabledProperty() {
        return windowEnabled;
    }

    public static DoubleProperty windowSizeUmProperty() {
        return windowSizeUm;
    }

    public static IntegerProperty windowOverlapPercentProperty() {
        return windowOverlapPercent;
    }

    public static BooleanProperty windowObjectsProperty() {
        return windowObjects;
    }

    public static BooleanProperty collagenObjectsProperty() {
        return collagenObjects;
    }

    public static DoubleProperty minWindowCoveragePercentProperty() {
        return minWindowCoveragePercent;
    }

    public static BooleanProperty straightnessEnabledProperty() {
        return straightnessEnabled;
    }

    public static BooleanProperty tortuosityOnProperty() {
        return tortuosityOn;
    }

    public static BooleanProperty radonOnProperty() {
        return radonOn;
    }

    public static DoubleProperty minBranchUmProperty() {
        return minBranchUm;
    }

    public static BooleanProperty morphEnabledProperty() {
        return morphEnabled;
    }

    public static BooleanProperty branchpointsProperty() {
        return branchpoints;
    }

    public static BooleanProperty endpointsProperty() {
        return endpoints;
    }

    public static BooleanProperty lengthProperty() {
        return length;
    }

    public static BooleanProperty curvatureProperty() {
        return curvature;
    }

    public static BooleanProperty hdmProperty() {
        return hdm;
    }

    public static BooleanProperty lacunarityProperty() {
        return lacunarity;
    }

    public static BooleanProperty fractalProperty() {
        return fractal;
    }

    public static BooleanProperty gapAnalysisProperty() {
        return gapAnalysis;
    }

    public static StringProperty lacBoxSizesPxProperty() {
        return lacBoxSizesPx;
    }

    public static StringProperty fractalBoxSizesPxProperty() {
        return fractalBoxSizesPx;
    }

    public static BooleanProperty textureEnabledProperty() {
        return textureEnabled;
    }

    public static IntegerProperty quantLevelsProperty() {
        return quantLevels;
    }

    public static StringProperty glcmDistancesPxProperty() {
        return glcmDistancesPx;
    }

    public static BooleanProperty contrastProperty() {
        return contrast;
    }

    public static BooleanProperty correlationProperty() {
        return correlation;
    }

    public static BooleanProperty energyProperty() {
        return energy;
    }

    public static BooleanProperty homogeneityProperty() {
        return homogeneity;
    }

    public static BooleanProperty entropyProperty() {
        return entropy;
    }

    public static BooleanProperty dissimilarityProperty() {
        return dissimilarity;
    }

    public static StringProperty outputDirProperty() {
        return outputDir;
    }

    public static BooleanProperty fiberMaskOverlayProperty() {
        return fiberMaskOverlay;
    }

    public static BooleanProperty straightnessHeatmapProperty() {
        return straightnessHeatmap;
    }

    public static BooleanProperty glcmHeatmapProperty() {
        return glcmHeatmap;
    }

    public static StringProperty glcmHeatmapPropProperty() {
        return glcmHeatmapProp;
    }

    public static BooleanProperty morphSummaryProperty() {
        return morphSummary;
    }

    public static BooleanProperty jsonSidecarProperty() {
        return jsonSidecar;
    }

    public static BooleanProperty emitNpzProperty() {
        return emitNpz;
    }

    /** Base dir for the Appose env, or "" for the Appose default. */
    public static String getEnvBaseDir() {
        installPreferences();
        return envBaseDir.get();
    }

    public static void setEnvBaseDir(String v) {
        installPreferences();
        envBaseDir.set(v == null ? "" : v.strip());
    }

    /** Directory an env was last successfully built at; "" if none. */
    public static String getEnvLastBuiltDir() {
        installPreferences();
        return envLastBuiltDir.get();
    }

    public static void setEnvLastBuiltDir(String v) {
        installPreferences();
        envLastBuiltDir.set(v == null ? "" : v);
    }

    /**
     * Add the environment-location preference to QuPath's Preferences pane.
     *
     * <p>Separate from {@link #installPreferences()}, which only creates the
     * persistent properties -- this extension previously exposed none of them in
     * the pane at all.
     */
    public static synchronized void installPreferencePane(QuPathGUI qupath) {
        if (qupath == null || paneInstalled) {
            return;
        }
        installPreferences();
        paneInstalled = true;
        qupath.getPreferencePane().getPropertySheet().getItems().add(
                new PropertyItemBuilder<>(envBaseDir, String.class)
                        .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                        .name("Python environment location")
                        .category(CATEGORY_ENV)
                        .description("Directory the Python environment is built under. Leave "
                                + "blank for the default (~/.local/share/appose), which is right "
                                + "on most machines. Set it when the home directory is "
                                + "quota-limited -- on HPC and managed desktops an environment "
                                + "this size fails there. Changing it builds a NEW environment; "
                                + "the old one is left alone and you are asked about removing it "
                                + "only after the new one works.")
                        .build());
    }

    private static boolean paneInstalled = false;
}
