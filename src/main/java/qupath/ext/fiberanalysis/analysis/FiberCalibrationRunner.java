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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
import javax.imageio.ImageIO;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
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
 *   <li>For each selected annotation, extract the dilated region as a PNG to
 *       a temp directory.</li>
 *   <li>Dispatch the {@code calibrate_threshold.py} Appose task with the list
 *       of PNG paths -- Python accumulates a 256-bin histogram across all
 *       regions, then runs Otsu on the accumulated histogram.</li>
 *   <li>Persist the result to
 *       {@code <project>/fiber-analysis/calibration_<name>.json} so future
 *       runs with {@code threshold_method = "Project Otsu (calibrated)"} read
 *       it back.</li>
 * </ol>
 *
 * <p>Memory: regions are written to disk one at a time and the BufferedImage
 * is released before the next region is read, so peak Java-side RAM is one
 * region. Python keeps a single 256-bin uint64 histogram (constant size
 * regardless of how many regions are streamed).
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
            List<EntryAnn> pool = new ArrayList<>();
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (progress != null && progress.isCancelled()) {
                    throw new IOException("Calibration cancelled");
                }
                String name = entry.getImageName();
                if (!needle.isEmpty() && (name == null || !name.toLowerCase().contains(needle))) {
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
                    if (!cfg.classFilter.isEmpty() && !AnnotationClassFilter.matches(ann, cfg.classFilter)) {
                        continue;
                    }
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

            // Phase 3: extract one region per annotation into the temp dir.
            List<String> regionPngs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (progress != null && progress.isCancelled()) {
                    throw new IOException("Calibration cancelled");
                }
                EntryAnn ea = pool.get(i);
                if (progress != null) {
                    progress.update("Extracting region " + (i + 1) + " of " + total, i, total);
                }
                Path png = extractRegion(ea, cfg.borderZoneUm, tempDir, i);
                if (png != null) {
                    regionPngs.add(png.toAbsolutePath().toString());
                }
            }
            if (regionPngs.isEmpty()) {
                throw new IOException("Could not extract any region PNGs -- check the log for read errors.");
            }
            if (progress != null) progress.update("Computing project threshold...", total, total);

            // Phase 4: dispatch the Appose task. Calibration spans multiple
            // images and each can have a different pixel size, but Python's
            // sigma / rolling-ball are pixel-domain. We resolve a single
            // *median* pixel size across the calibration pool and convert
            // micron inputs against it -- a sound choice when the pool is
            // already filtered to one image cohort (which the user is
            // expected to do via the image-name + class filters; the
            // calibration dialog tells them so explicitly).
            double medianPxUm = medianPixelSizeUm(pool);
            if (medianPxUm <= 0) medianPxUm = 0.5; // last-ditch fallback
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("region_paths", regionPngs);
            in.put("seg_channel", cfg.segChannel);
            in.put("ridge_filter", cfg.ridgeFilter.toLowerCase());
            in.put("sigma_min", cfg.sigmaMinUm / medianPxUm);
            in.put("sigma_max", cfg.sigmaMaxUm / medianPxUm);
            in.put("sigma_step", cfg.sigmaStepUm / medianPxUm);
            in.put("invert_intensity", cfg.invertIntensity);
            in.put("rolling_ball_radius", (int) Math.max(0, Math.round(cfg.rollingBallRadiusUm / medianPxUm)));

            Task task = ApposeFiberService.getInstance().runTask("calibrate_threshold", in);
            Object outJson = task.outputs.get("result_json");
            if (outJson == null) throw new IOException("calibrate_threshold returned no result_json");
            JsonObject result = new Gson().fromJson(String.valueOf(outJson), JsonObject.class);
            if (result.has("error") && !result.get("error").isJsonNull()) {
                throw new IOException(
                        "Calibration Python error: " + result.get("error").getAsString());
            }

            double thr = result.get("threshold_normalised").getAsDouble();
            int regionsUsed = result.get("n_regions_used").getAsInt();
            long pixelsUsed = result.get("n_pixels_total").getAsLong();

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
            params.put("median_pixel_size_um_used", medianPxUm);
            params.put("border_zone_um", cfg.borderZoneUm);
            payload.put("calibration_params", params);
            // Keep the histogram so a downstream tool can plot/inspect it.
            if (result.has("histogram_counts")) {
                payload.put("histogram_bins", 256);
                List<Long> hist = new ArrayList<>(256);
                for (var el : result.getAsJsonArray("histogram_counts")) {
                    hist.add(el.getAsLong());
                }
                payload.put("histogram_counts", hist);
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

    /**
     * Extracts one annotation's dilated region as a PNG. Returns null on
     * failure so the calibration can continue across the rest of the pool.
     */
    private static Path extractRegion(EntryAnn ea, double borderZoneUm, Path tempDir, int index) {
        try {
            ImageServer<BufferedImage> server = ea.data.getServer();
            ROI roi = ea.ann.getROI();
            double px = 0.5;
            try {
                PixelCalibration cal = server.getPixelCalibration();
                if (cal != null && cal.hasPixelSizeMicrons()) {
                    px = cal.getAveragedPixelSizeMicrons();
                }
            } catch (Exception ignored) {
                // fall back to 0.5
            }
            double dilationPx = borderZoneUm / Math.max(px, 1e-6);
            int pad = (int) Math.ceil(dilationPx) + 5;
            int x = (int) roi.getBoundsX();
            int y = (int) roi.getBoundsY();
            int w = (int) Math.ceil(roi.getBoundsWidth());
            int h = (int) Math.ceil(roi.getBoundsHeight());
            int rx = Math.max(0, x - pad);
            int ry = Math.max(0, y - pad);
            int rw = Math.min(server.getWidth() - rx, w + 2 * pad);
            int rh = Math.min(server.getHeight() - ry, h + 2 * pad);
            if (rw <= 0 || rh <= 0) return null;
            RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, rx, ry, rw, rh);
            BufferedImage img = server.readRegion(req);
            if (img == null) return null;
            Path out = tempDir.resolve(String.format("region_%05d.png", index));
            ImageIO.write(img, "PNG", out.toFile());
            return out;
        } catch (Exception ex) {
            logger.warn("Region extraction failed for annotation {}: {}", index, ex.getMessage());
            return null;
        }
    }

    /**
     * Computes the median pixel size (um/px) across a calibration pool. The
     * calibration pool typically spans one image cohort with consistent
     * pixel size, but a median tolerates outliers from images that lack
     * calibration metadata. Returns 0 if no pool entry exposes a pixel size.
     */
    private static double medianPixelSizeUm(java.util.List<EntryAnn> pool) {
        java.util.List<Double> sizes = new java.util.ArrayList<>();
        for (EntryAnn ea : pool) {
            try {
                ImageServer<BufferedImage> server = ea.data.getServer();
                PixelCalibration cal = server.getPixelCalibration();
                if (cal != null && cal.hasPixelSizeMicrons()) {
                    sizes.add(cal.getAveragedPixelSizeMicrons());
                }
            } catch (Exception ignored) {
                // skip; we'll fall back if everything fails
            }
        }
        if (sizes.isEmpty()) return 0.0;
        java.util.Collections.sort(sizes);
        return sizes.get(sizes.size() / 2);
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
