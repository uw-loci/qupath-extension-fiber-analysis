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

import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

/**
 * Drives one project-wide threshold-calibration pass.
 *
 * <p>Flow:
 * <ol>
 *   <li>Walk a filtered subset of project annotations (image-name + image-type
 *       + class filters mirror the batch dialog).</li>
 *   <li>Optionally subsample to {@code sampleSize} annotations (random order
 *       with a recorded seed so the calibration is reproducible).</li>
 *   <li>Pool a threshold over their analysis zones with
 *       {@link PooledThreshold#poolZones}, the routine an Otsu run uses, at the
 *       run's analysis downsample and zone settings.</li>
 *   <li>Persist the result to
 *       {@code <project>/fiber-analysis/calibration_<name>.json} so future
 *       runs with {@code threshold_method = "Project Otsu (calibrated)"} read
 *       it back.</li>
 * </ol>
 *
 * <p>Memory: samples are written to disk one at a time, at most 1024 px a
 * side at the analysis downsample. Python keeps one histogram.
 */
public final class FiberCalibrationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FiberCalibrationRunner.class);

    /**
     * Caller-supplied configuration for the calibration pass.
     */
    public static final class CalibrationConfig {
        public String calibrationName = "default";
        public int sampleSize = 50; // <=0 means "all"
        public long randomSeed = 42L;
        public String nameFilter = ""; // image-name substring (case-insensitive); blank = no filter
        public Set<String> classFilter = Collections.emptySet(); // empty = no class filter
        // Segmentation knobs the calibration mirrors so the threshold transfers
        // cleanly into the run path. All spatial values are in MICRONS;
        // the runner converts per-region using each image's pixel size when
        // building the Python task inputs.
        public String segChannel = "Raw intensity";
        public String ridgeFilter = "None"; // matches FiberAnalysisParams casing
        public double sigmaMinUm = 1.0;
        public double sigmaMaxUm = 4.0;
        public double sigmaStepUm = 1.0;
        public boolean invertIntensity = false;
        public double rollingBallRadiusUm = 0.0;
        public double borderZoneUm = 50.0;
        /** The run's zone mode: "whole", "inside", "outside" or "both". */
        public String zoneMode = "outside";
        /**
         * The run's analysis downsample. Samples are read at it because the
         * ridge filter and rolling ball act at that scale, so a threshold
         * fitted at another scale is fitted on a different image.
         */
        public double analysisDownsample = 1.0;
    }

    /** Lightweight progress callback so the dialog can stream "(i/N) ..." updates. */
    public interface ProgressCallback {
        void update(String message, int completed, int total);

        default boolean isCancelled() {
            return false;
        }
    }

    /** Final result handed back to the UI for display. */
    public static final class CalibrationResult {
        public final Path calibrationFile;
        public final double thresholdNormalised;
        public final int regionsUsed;
        public final long pixelsUsed;

        CalibrationResult(Path file, double t, int n, long p) {
            this.calibrationFile = file;
            this.thresholdNormalised = t;
            this.regionsUsed = n;
            this.pixelsUsed = p;
        }
    }

    private FiberCalibrationRunner() {}

    public static CalibrationResult run(
            Project<BufferedImage> project, CalibrationConfig cfg, ProgressCallback progress) throws IOException {
        if (project == null) throw new IOException("No project open");
        if (cfg == null) throw new IOException("Calibration config is null");
        if (cfg.calibrationName == null || cfg.calibrationName.isBlank()) {
            throw new IOException("Calibration name is required");
        }
        // Sanitise calibration name -- it becomes part of a filename.
        String safeName = cfg.calibrationName.replaceAll("[^A-Za-z0-9_.-]+", "_");
        if (safeName.isBlank()) throw new IOException("Calibration name produced empty filename after sanitisation");

        Path projDir = project.getPath() == null ? null : project.getPath().getParent();
        if (projDir == null) throw new IOException("Could not resolve project directory");
        Path faDir = projDir.resolve("fiber-analysis");
        Files.createDirectories(faDir);
        Path tempDir = Files.createTempDirectory(faDir, "calibration_tmp_");

        try {
            // Phase 1: collect (entry, annotation) pairs that match the filters.
            String needle =
                    cfg.nameFilter == null ? "" : cfg.nameFilter.toLowerCase().trim();
            var classPredicate = cfg.classFilter.isEmpty() ? null : AnnotationClassFilter.predicate(cfg.classFilter);
            List<EntryAnn> pool = new ArrayList<>();
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (progress != null && progress.isCancelled()) {
                    throw new IOException("Calibration cancelled");
                }
                String name = entry.getImageName();
                if (!needle.isEmpty() && (name == null || !name.toLowerCase().contains(needle))) {
                    continue;
                }
                // A channels entry this extension made is derived from another
                // image, not a source to calibrate on.
                if (entry.getMetadata().containsKey(DensityChannelAttacher.META_KIND)) {
                    continue;
                }
                ImageData<BufferedImage> data;
                try {
                    data = entry.readImageData();
                } catch (IOException ioe) {
                    logger.warn("Could not read image data for {}: {}", name, ioe.getMessage());
                    continue;
                }
                if (data == null || data.getHierarchy() == null) continue;
                for (PathObject ann : data.getHierarchy().getAnnotationObjects()) {
                    if (ann == null || !ann.isAnnotation()) continue;
                    if (ann.getROI() == null) continue;
                    if (classPredicate != null && !classPredicate.test(ann)) continue;
                    pool.add(new EntryAnn(entry, data, ann));
                }
            }
            if (pool.isEmpty()) {
                throw new IOException("No annotations matched the calibration filters."
                        + " Draw annotations on the project images first.");
            }

            // Phase 2: subsample.
            if (cfg.sampleSize > 0 && cfg.sampleSize < pool.size()) {
                Collections.shuffle(pool, new Random(cfg.randomSeed));
                pool = pool.subList(0, cfg.sampleSize);
            }
            final int total = pool.size();
            logger.info(
                    "Calibration pool: {} annotations from {} project images",
                    total,
                    project.getImageList().size());

            // Phase 3: fit the threshold the run would: the analysis zone of each
            // annotation, at the analysis downsample, with the micron settings
            // converted per image (PooledThreshold.poolZones, the same routine
            // an Otsu run pools with).
            double ds = Math.max(1.0, cfg.analysisDownsample);
            List<PooledThreshold.ZoneRegion> regions = new ArrayList<>();
            java.util.TreeSet<Double> pixelSizes = new java.util.TreeSet<>();
            int uncalibrated = 0;
            for (EntryAnn ea : pool) {
                ImageServer<BufferedImage> server = ea.data.getServer();
                PixelCalibration cal = server.getPixelCalibration();
                if (cal == null || !cal.hasPixelSizeMicrons()) {
                    uncalibrated++;
                    continue;
                }
                double px = cal.getAveragedPixelSizeMicrons();
                pixelSizes.add(px);
                ROI roi = ea.ann.getROI();
                regions.add(new PooledThreshold.ZoneRegion(
                        server, roi, FiberAnalysisWorkflow.readBox(roi, server, cfg.borderZoneUm / px), ds, px));
            }
            if (uncalibrated > 0) {
                logger.warn(
                        "Calibration: skipped {} annotation(s) on images with no pixel size; their zone width"
                                + " and filter scales cannot be converted from microns",
                        uncalibrated);
            }
            if (regions.isEmpty()) {
                throw new IOException("No image in the calibration pool has a pixel size. Set the pixel size"
                        + " (Image > Set pixel size) and run the calibration again.");
            }
            int maxSamples = Math.max(16, Math.min(256, 4 * regions.size()));
            PooledThreshold.Result pooled = PooledThreshold.poolZones(
                    regions,
                    cfg.zoneMode,
                    cfg.borderZoneUm,
                    settings(cfg),
                    tempDir,
                    maxSamples,
                    progress == null ? null : msg -> progress.update(msg, 0, total));

            double thr = pooled.thresholdNorm();
            int regionsUsed = pooled.regions();
            long pixelsUsed = pooled.pixels();

            // Phase 5: write calibration_<name>.json.
            Path calFile = faDir.resolve("calibration_" + safeName + ".json");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", safeName);
            payload.put("timestamp_utc", OffsetDateTime.now().toString());
            String extVersion = GeneralTools.getPackageVersion(FiberCalibrationRunner.class);
            payload.put("extension_version", extVersion == null ? "dev" : extVersion);
            payload.put("threshold_normalised", thr);
            payload.put("n_regions_used", regionsUsed);
            payload.put("n_pixels_total", pixelsUsed);
            payload.put("sample_size_requested", cfg.sampleSize);
            payload.put("total_regions_available", total);
            payload.put("random_seed", cfg.randomSeed);
            Map<String, Object> filt = new LinkedHashMap<>();
            filt.put("name_filter", cfg.nameFilter == null ? "" : cfg.nameFilter);
            filt.put("class_filter", new ArrayList<>(cfg.classFilter));
            payload.put("filters", filt);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("seg_channel", cfg.segChannel);
            params.put("ridge_filter", cfg.ridgeFilter);
            params.put("sigma_min_um", cfg.sigmaMinUm);
            params.put("sigma_max_um", cfg.sigmaMaxUm);
            params.put("sigma_step_um", cfg.sigmaStepUm);
            params.put("invert_intensity", cfg.invertIntensity);
            params.put("rolling_ball_radius_um", cfg.rollingBallRadiusUm);
            params.put("pixel_sizes_um", new ArrayList<>(pixelSizes));
            params.put("analysis_downsample", ds);
            params.put("border_zone_um", cfg.borderZoneUm);
            params.put("zone_mode", cfg.zoneMode);
            payload.put("calibration_params", params);
            // Keep the histogram so a downstream tool can plot/inspect it.
            if (!pooled.histogram().isEmpty()) {
                payload.put("histogram_bins", pooled.histogram().size());
                payload.put("histogram_counts", pooled.histogram());
            }
            Files.writeString(
                    calFile,
                    new GsonBuilder()
                            .setPrettyPrinting()
                            .disableHtmlEscaping()
                            .create()
                            .toJson(payload));

            // Provenance triple: alongside calibration_<name>.json we emit a
            // human-readable params.txt echo and a Groovy re-run script.
            // The calibration_<name>.json itself is the load-back file
            // (so we don't duplicate it as a separate params.json) -- the
            // Groovy template reads calibration_<name>.json directly.
            try {
                Map<String, Object> cfgEchoed = new LinkedHashMap<>();
                cfgEchoed.put("calibrationName", safeName);
                cfgEchoed.put("sampleSize", cfg.sampleSize);
                cfgEchoed.put("randomSeed", cfg.randomSeed);
                cfgEchoed.put("nameFilter", cfg.nameFilter == null ? "" : cfg.nameFilter);
                cfgEchoed.put("classFilter", new ArrayList<>(cfg.classFilter));
                cfgEchoed.put("segChannel", cfg.segChannel);
                cfgEchoed.put("ridgeFilter", cfg.ridgeFilter);
                cfgEchoed.put("sigmaMinUm", cfg.sigmaMinUm);
                cfgEchoed.put("sigmaMaxUm", cfg.sigmaMaxUm);
                cfgEchoed.put("sigmaStepUm", cfg.sigmaStepUm);
                cfgEchoed.put("invertIntensity", cfg.invertIntensity);
                cfgEchoed.put("rollingBallRadiusUm", cfg.rollingBallRadiusUm);
                cfgEchoed.put("borderZoneUm", cfg.borderZoneUm);

                String projectPath = project.getPath() != null
                        ? project.getPath().toAbsolutePath().toString()
                        : "";
                Map<String, String> placeholders = new LinkedHashMap<>();
                placeholders.put("PROJECT_PATH", projectPath);
                placeholders.put("PROJECT_PATH_LITERAL", RunProvenance.groovyString(projectPath));
                // For calibration the rerun script reads the calibration_<name>.json
                // directly (it's already a complete settings record); override the
                // auto-injected PARAMS_JSON_PATH to point at that file rather than
                // a sibling params.json we don't write.
                placeholders.put("PARAMS_JSON_PATH", calFile.toAbsolutePath().toString());
                placeholders.put(
                        "PARAMS_JSON_PATH_LITERAL",
                        RunProvenance.groovyString(calFile.toAbsolutePath().toString()));

                // Write params.txt + rerun.groovy. We skip params.json (calibration_<name>.json
                // IS the params.json) by writing only the two we need.
                Path txtPath = faDir.resolve("calibration_" + safeName + "_params.txt");
                Path groovyPath = faDir.resolve("calibration_" + safeName + "_rerun.groovy");
                RunProvenance.writeParamsTxt(
                        txtPath,
                        java.util.List.of(
                                "calibration run parameters",
                                "The companion " + calFile.getFileName()
                                        + " is the load-back file the rerun script reads.",
                                "Re-run headlessly via:  QuPath script " + groovyPath.getFileName()),
                        cfgEchoed);
                RunProvenance.writeRerunGroovy(groovyPath, RunProvenance.KIND_CALIBRATION, placeholders);
            } catch (IOException provEx) {
                logger.warn("Could not write calibration provenance for '{}': {}", safeName, provEx.getMessage());
            }

            logger.info(
                    "Calibration '{}' saved to {} -- threshold={}, regions={}, pixels={}",
                    safeName,
                    calFile,
                    thr,
                    regionsUsed,
                    pixelsUsed);
            return new CalibrationResult(calFile, thr, regionsUsed, pixelsUsed);
        } finally {
            // Best-effort cleanup of the temp PNGs.
            try {
                Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /** The settings {@link PooledThreshold} pools under, from a calibration config. */
    private static PooledThreshold.Settings settings(CalibrationConfig cfg) {
        return new PooledThreshold.Settings() {
            @Override
            public String thresholdMethod() {
                return "otsu";
            }

            @Override
            public String segChannel() {
                return cfg.segChannel;
            }

            @Override
            public String ridgeFilter() {
                return cfg.ridgeFilter;
            }

            @Override
            public double sigmaMinUm() {
                return cfg.sigmaMinUm;
            }

            @Override
            public double sigmaMaxUm() {
                return cfg.sigmaMaxUm;
            }

            @Override
            public double sigmaStepUm() {
                return cfg.sigmaStepUm;
            }

            @Override
            public boolean invertIntensity() {
                return cfg.invertIntensity;
            }

            @Override
            public double rollingBallRadiusUm() {
                return cfg.rollingBallRadiusUm;
            }
        };
    }

    private static final class EntryAnn {
        final ProjectImageEntry<BufferedImage> entry;
        final ImageData<BufferedImage> data;
        final PathObject ann;

        EntryAnn(ProjectImageEntry<BufferedImage> entry, ImageData<BufferedImage> data, PathObject ann) {
            this.entry = entry;
            this.data = data;
            this.ann = ann;
        }
    }
}
