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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
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
 * <p>Cancellation is checked between images, between sample regions, before
 * the Python pass and before anything is written; a canceled pass writes
 * nothing and throws {@link CancellationException}.
 *
 * <p>Memory: samples are written to disk one at a time, at most 1024 px a
 * side at the analysis downsample. Python keeps one histogram.
 */
public final class FiberCalibrationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FiberCalibrationRunner.class);

    /** Reads an entry's image data; the dialog substitutes the open viewer's for its entry. */
    public interface ImageSource {
        ImageData<BufferedImage> read(ProjectImageEntry<BufferedImage> entry) throws IOException;
    }

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
        /** As in a run: false analyzes every image at {@link #pixelSizeOverrideUm}. */
        public boolean useImagePixelSize = true;
        /** Pixel size for images without one, or for all when {@link #useImagePixelSize} is false; NaN = none. */
        public double pixelSizeOverrideUm = Double.NaN;
        /**
         * Asked when the pooled images have different pixel sizes, with the check (and no extra
         * note); null analyses each at its own size.
         */
        public java.util.function.BiFunction<PixelSizeCheck, String, PixelSizePrompt.Choice> onMixedPixelSizes;
        /** Where each image's annotations are read from. */
        public ImageSource imageSource = ProjectImageEntry::readImageData;

        /**
         * @param p the parameters of the run the calibration is for
         * @return a config that fits the scalar image and zones that run cuts on
         */
        public static CalibrationConfig fromRun(FiberAnalysisParams p) {
            CalibrationConfig cfg = new CalibrationConfig();
            cfg.segChannel = p.internalChannel();
            cfg.ridgeFilter = p.ridgeFilter();
            cfg.sigmaMinUm = p.sigmaMinUm();
            cfg.sigmaMaxUm = p.sigmaMaxUm();
            cfg.sigmaStepUm = p.sigmaStepUm();
            cfg.invertIntensity = p.invertIntensity();
            cfg.rollingBallRadiusUm = p.rollingBallRadiusUm();
            cfg.borderZoneUm = p.borderZoneUm();
            cfg.zoneMode = p.zoneMode();
            cfg.analysisDownsample = Math.max(1.0, p.analysisDownsample());
            cfg.useImagePixelSize = p.useImagePixelSize();
            cfg.pixelSizeOverrideUm = p.pixelSizeOverrideUm();
            return cfg;
        }

        /**
         * Rebuilds the config a saved calibration was made with, including its answer to mixed
         * pixel sizes.
         *
         * @param calibrationJson a {@code calibration_<name>.json}
         * @return the config; a setting the file does not record keeps its default, with a warning
         * @throws IOException if the file cannot be read
         */
        public static CalibrationConfig fromCalibrationFile(Path calibrationJson) throws IOException {
            JsonObject json = new Gson().fromJson(Files.readString(calibrationJson), JsonObject.class);
            if (json == null) throw new IOException("Empty calibration file: " + calibrationJson);
            JsonObject cp = json.has("calibration_params")
                            && json.get("calibration_params").isJsonObject()
                    ? json.getAsJsonObject("calibration_params")
                    : new JsonObject();
            JsonObject fl = json.has("filters") && json.get("filters").isJsonObject()
                    ? json.getAsJsonObject("filters")
                    : new JsonObject();
            CalibrationConfig cfg = new CalibrationConfig();
            List<String> missing = new ArrayList<>();
            cfg.calibrationName = str(json, "name", "default", null);
            cfg.sampleSize = (int) num(json, "sample_size_requested", 0, null);
            cfg.randomSeed = (long) num(json, "random_seed", 42, null);
            cfg.nameFilter = str(fl, "name_filter", "", null);
            Set<String> classes = new HashSet<>();
            if (fl.has("class_filter") && fl.get("class_filter").isJsonArray()) {
                for (JsonElement e : fl.getAsJsonArray("class_filter")) classes.add(e.getAsString());
            }
            cfg.classFilter = classes;
            cfg.segChannel = str(cp, "seg_channel", cfg.segChannel, missing);
            cfg.ridgeFilter = str(cp, "ridge_filter", cfg.ridgeFilter, missing);
            cfg.sigmaMinUm = num(cp, "sigma_min_um", cfg.sigmaMinUm, missing);
            cfg.sigmaMaxUm = num(cp, "sigma_max_um", cfg.sigmaMaxUm, missing);
            cfg.sigmaStepUm = num(cp, "sigma_step_um", cfg.sigmaStepUm, missing);
            cfg.invertIntensity = cp.has("invert_intensity")
                    ? cp.get("invert_intensity").getAsBoolean()
                    : add(missing, "invert_intensity", false);
            cfg.rollingBallRadiusUm = num(cp, "rolling_ball_radius_um", cfg.rollingBallRadiusUm, missing);
            cfg.borderZoneUm = num(cp, "border_zone_um", cfg.borderZoneUm, missing);
            cfg.zoneMode = str(cp, "zone_mode", cfg.zoneMode, missing);
            cfg.analysisDownsample = num(cp, "analysis_downsample", cfg.analysisDownsample, missing);
            // Written since 0.3.1; older calibrations always used each image's own pixel size.
            if (cp.has("use_image_pixel_size"))
                cfg.useImagePixelSize = cp.get("use_image_pixel_size").getAsBoolean();
            cfg.pixelSizeOverrideUm = num(cp, "pixel_size_override_um", Double.NaN, null);
            if (!missing.isEmpty()) {
                logger.warn(
                        "Calibration file {} does not record {}; using the defaults for them",
                        calibrationJson.getFileName(),
                        String.join(", ", missing));
            }
            double common = num(cp, "common_pixel_um", Double.NaN, null);
            boolean mixedOwn =
                    cp.has("mixed_pixel_sizes") && cp.get("mixed_pixel_sizes").getAsBoolean();
            cfg.onMixedPixelSizes = (check, note) -> {
                if (!Double.isNaN(common)) {
                    if (!ThresholdIdentity.samePixelSize(common, check.commonPixelUm())) {
                        logger.warn(
                                "The calibration was fitted at a common pixel size of {} um/px; the images"
                                        + " now give {} um/px",
                                PixelSizeCheck.um(common),
                                PixelSizeCheck.um(check.commonPixelUm()));
                    }
                    return PixelSizePrompt.Choice.COMMON;
                }
                if (mixedOwn) return PixelSizePrompt.Choice.OWN;
                logger.warn(
                        "The calibration's images all had one pixel size; they now differ, so it cannot be"
                                + " reproduced:\n{}",
                        check.describe());
                return PixelSizePrompt.Choice.CANCEL;
            };
            return cfg;
        }

        private static <T> T add(List<String> missing, String key, T fallback) {
            missing.add(key);
            return fallback;
        }

        private static String str(JsonObject o, String key, String fallback, List<String> missing) {
            JsonElement e = o.get(key);
            if (e != null && e.isJsonPrimitive()) return e.getAsString();
            if (missing != null) missing.add(key);
            return fallback;
        }

        private static double num(JsonObject o, String key, double fallback, List<String> missing) {
            JsonElement e = o.get(key);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
            if (missing != null) missing.add(key);
            return fallback;
        }
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

    /** One annotation in the pool, with the image it is on. */
    record Sample(String imageName, ImageData<BufferedImage> data, PathObject annotation) {}

    /** Fits the threshold over the pooled regions; the seam a test replaces to run without Python. */
    interface Pooler {
        PooledThreshold.Result pool(
                List<PooledThreshold.ZoneRegion> regions,
                int maxSamples,
                Path workDir,
                Consumer<String> progress,
                BooleanSupplier cancelled)
                throws IOException;
    }

    private FiberCalibrationRunner() {}

    /**
     * @param name a calibration name as typed
     * @return the name as it appears in the file name
     * @throws IOException if nothing usable is left
     */
    public static String safeName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Calibration name is required");
        String safe = name.replaceAll("[^A-Za-z0-9_.-]+", "_");
        if (safe.isBlank()) throw new IOException("Calibration name produced empty filename after sanitisation");
        return safe;
    }

    /**
     * @param projDir the project folder
     * @param name    a calibration name as typed
     * @return where that calibration is saved
     * @throws IOException if the name is unusable
     */
    public static Path calibrationFile(Path projDir, String name) throws IOException {
        return projDir.resolve("fiber-analysis").resolve("calibration_" + safeName(name) + ".json");
    }

    public static CalibrationResult run(
            Project<BufferedImage> project, CalibrationConfig cfg, ProgressCallback progress) throws IOException {
        if (project == null) throw new IOException("No project open");
        if (cfg == null) throw new IOException("Calibration config is null");
        safeName(cfg.calibrationName);
        Path projDir = project.getPath() == null ? null : project.getPath().getParent();
        if (projDir == null) throw new IOException("Could not resolve project directory");
        ProgressCallback prog = progress == null ? (m, c, t) -> {} : progress;

        List<Sample> pool = collect(project, cfg, prog);
        if (pool.isEmpty()) {
            throw new IOException("No annotations matched the calibration filters."
                    + " Draw annotations on the project images first.");
        }
        logger.info(
                "Calibration pool: {} annotations from {} project images",
                pool.size(),
                project.getImageList().size());
        Pooler pooler = (regions, maxSamples, work, msg, cancelled) -> PooledThreshold.poolZones(
                regions, cfg.zoneMode, cfg.borderZoneUm, settings(cfg), work, maxSamples, msg, cancelled);
        return fitAndSave(projDir, project.getPath(), pool, cfg, prog, pooler);
    }

    /** Walks the project for annotations matching the filters, subsampled. */
    private static List<Sample> collect(Project<BufferedImage> project, CalibrationConfig cfg, ProgressCallback prog)
            throws IOException {
        String needle = cfg.nameFilter == null
                ? ""
                : cfg.nameFilter.toLowerCase(Locale.ROOT).trim();
        Predicate<PathObject> classPredicate =
                cfg.classFilter.isEmpty() ? null : AnnotationClassFilter.predicate(cfg.classFilter);
        List<Sample> pool = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            checkCancelled(prog);
            String name = entry.getImageName();
            if (!needle.isEmpty()
                    && (name == null || !name.toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            // A channels entry this extension made is derived from another
            // image, not a source to calibrate on.
            if (DensityChannelAttacher.isChannelsEntry(entry)) {
                continue;
            }
            ImageData<BufferedImage> data;
            try {
                data = cfg.imageSource.read(entry);
            } catch (IOException ioe) {
                logger.warn("Could not read image data for {}: {}", name, ioe.getMessage());
                continue;
            }
            if (data == null) continue;
            for (PathObject ann : poolableAnnotations(data.getHierarchy(), classPredicate)) {
                pool.add(new Sample(name, data, ann));
            }
        }
        if (cfg.sampleSize > 0 && cfg.sampleSize < pool.size()) {
            Collections.shuffle(pool, new Random(cfg.randomSeed));
            pool = new ArrayList<>(pool.subList(0, cfg.sampleSize));
        }
        return pool;
    }

    /**
     * @param hierarchy      an image's objects; may be null
     * @param classPredicate the class filter, or null for none
     * @return its annotations a calibration pools, leaving out the preview's region marker
     */
    static List<PathObject> poolableAnnotations(PathObjectHierarchy hierarchy, Predicate<PathObject> classPredicate) {
        List<PathObject> out = new ArrayList<>();
        if (hierarchy == null) return out;
        for (PathObject ann : hierarchy.getAnnotationObjects()) {
            if (ann == null || !ann.isAnnotation() || ann.getROI() == null) continue;
            if (FiberSegmentationPreviewWindow.isPreviewMarker(ann)) continue;
            if (classPredicate != null && !classPredicate.test(ann)) continue;
            out.add(ann);
        }
        return out;
    }

    /**
     * Fits the threshold over the pool and writes the calibration and its provenance.
     *
     * @param projDir     the project folder
     * @param projectFile the project file, for the rerun script; may be null
     * @param pool        the annotations
     * @param cfg         the settings
     * @param prog        progress and cancellation
     * @param pooler      fits the threshold
     * @return the result
     * @throws IOException if nothing could be fitted or written
     * @throws CancellationException once canceled; nothing is written then
     */
    static CalibrationResult fitAndSave(
            Path projDir,
            Path projectFile,
            List<Sample> pool,
            CalibrationConfig cfg,
            ProgressCallback prog,
            Pooler pooler)
            throws IOException {
        String safeName = safeName(cfg.calibrationName);
        Path faDir = projDir.resolve("fiber-analysis");
        Files.createDirectories(faDir);
        final int total = pool.size();
        checkCancelled(prog);

        // Fit the threshold the run would: the analysis zone of each
        // annotation, at the analysis downsample, with the micron settings
        // converted per image (PooledThreshold.poolZones, the same routine
        // an Otsu run pools with).
        double ds = Math.max(1.0, cfg.analysisDownsample);
        Map<String, PixelSizeCheck.Image> scales = new LinkedHashMap<>();
        for (Sample s : pool) {
            double px = pixelUm(s.data().getServer(), cfg);
            if (px > 0) scales.putIfAbsent(s.imageName(), new PixelSizeCheck.Image(s.imageName(), px));
        }
        PixelSizeCheck check = PixelSizeCheck.of(new ArrayList<>(scales.values()), ds);
        boolean common = false;
        if (check.mixed()) {
            PixelSizePrompt.Choice choice = cfg.onMixedPixelSizes == null
                    ? PixelSizePrompt.Choice.OWN
                    : cfg.onMixedPixelSizes.apply(check, null);
            logger.info("Calibration: images have different pixel sizes; chose {}\n{}", choice, check.describe());
            if (choice == PixelSizePrompt.Choice.CANCEL)
                throw new CancellationException("Calibration canceled: the images have different pixel sizes.");
            common = choice == PixelSizePrompt.Choice.COMMON;
        }
        checkCancelled(prog);
        List<PooledThreshold.ZoneRegion> regions = new ArrayList<>();
        java.util.TreeSet<Double> pixelSizes = new java.util.TreeSet<>();
        int uncalibrated = 0;
        for (Sample s : pool) {
            ImageServer<BufferedImage> server = s.data().getServer();
            double px = pixelUm(server, cfg);
            if (!(px > 0)) {
                uncalibrated++;
                continue;
            }
            pixelSizes.add(px);
            ROI roi = s.annotation().getROI();
            regions.add(new PooledThreshold.ZoneRegion(
                    server,
                    roi,
                    FiberAnalysisWorkflow.readBox(roi, server, cfg.borderZoneUm / px),
                    common ? check.downsampleFor(px) : ds,
                    px));
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

        Path tempDir = Files.createTempDirectory(faDir, "calibration_tmp_");
        PooledThreshold.Result pooled;
        try {
            int maxSamples = Math.max(16, Math.min(256, 4 * regions.size()));
            pooled = pooler.pool(regions, maxSamples, tempDir, msg -> prog.update(msg, 0, total), prog::isCancelled);
        } finally {
            deleteTree(tempDir);
        }
        // Nothing is written once the user has canceled.
        checkCancelled(prog);

        double thr = pooled.thresholdNorm();
        Path calFile = faDir.resolve("calibration_" + safeName + ".json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", safeName);
        payload.put("timestamp_utc", OffsetDateTime.now().toString());
        String extVersion = GeneralTools.getPackageVersion(FiberCalibrationRunner.class);
        payload.put("extension_version", extVersion == null ? "dev" : extVersion);
        payload.put("threshold_normalised", thr);
        payload.put("n_regions_used", pooled.regions());
        payload.put("n_pixels_total", pooled.pixels());
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
        params.put("use_image_pixel_size", cfg.useImagePixelSize);
        if (cfg.pixelSizeOverrideUm > 0) params.put("pixel_size_override_um", cfg.pixelSizeOverrideUm);
        // Fitted at one common pixel size, or each image at its own.
        if (common) params.put("common_pixel_um", check.commonPixelUm());
        else if (check.mixed()) params.put("mixed_pixel_sizes", true);
        params.put("border_zone_um", cfg.borderZoneUm);
        params.put("zone_mode", cfg.zoneMode);
        // Ridge responses are on a fixed scale (1.0 = a full-contrast fiber).
        params.put("response_scale", "absolute");
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

        // Provenance: a human-readable params.txt echo and a Groovy re-run
        // script. calibration_<name>.json is itself the load-back file the
        // script reads, so there is no separate params.json.
        try {
            String projectPath =
                    projectFile != null ? projectFile.toAbsolutePath().toString() : "";
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("PROJECT_PATH", projectPath);
            placeholders.put("PROJECT_PATH_LITERAL", RunProvenance.groovyString(projectPath));
            placeholders.put("PARAMS_JSON_PATH", calFile.toAbsolutePath().toString());
            placeholders.put(
                    "PARAMS_JSON_PATH_LITERAL",
                    RunProvenance.groovyString(calFile.toAbsolutePath().toString()));
            Path txtPath = faDir.resolve("calibration_" + safeName + "_params.txt");
            Path groovyPath = faDir.resolve("calibration_" + safeName + "_rerun.groovy");
            RunProvenance.writeParamsTxt(
                    txtPath,
                    java.util.List.of(
                            "calibration run parameters",
                            "The companion " + calFile.getFileName() + " is the load-back file the rerun script reads.",
                            "Re-run headlessly via:  QuPath script " + groovyPath.getFileName()),
                    paramsEcho(safeName, cfg, common ? check.commonPixelUm() : Double.NaN));
            RunProvenance.writeRerunGroovy(groovyPath, RunProvenance.KIND_CALIBRATION, placeholders);
        } catch (IOException provEx) {
            logger.warn("Could not write calibration provenance for '{}': {}", safeName, provEx.getMessage());
        }

        logger.info(
                "Calibration '{}' saved to {} -- threshold={}, regions={}, pixels={}",
                safeName,
                calFile,
                thr,
                pooled.regions(),
                pooled.pixels());
        return new CalibrationResult(calFile, thr, pooled.regions(), pooled.pixels());
    }

    /**
     * @param safeName       the calibration's file-name form
     * @param cfg            the settings
     * @param commonPixelUm  the common pixel size the images were brought to, or NaN
     * @return the settings for params.txt, one per line
     */
    static Map<String, Object> paramsEcho(String safeName, CalibrationConfig cfg, double commonPixelUm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calibrationName", safeName);
        m.put("sampleSize", cfg.sampleSize);
        m.put("randomSeed", cfg.randomSeed);
        m.put("nameFilter", cfg.nameFilter == null ? "" : cfg.nameFilter);
        m.put("classFilter", new ArrayList<>(cfg.classFilter));
        m.put("segChannel", cfg.segChannel);
        m.put("ridgeFilter", cfg.ridgeFilter);
        m.put("sigmaMinUm", cfg.sigmaMinUm);
        m.put("sigmaMaxUm", cfg.sigmaMaxUm);
        m.put("sigmaStepUm", cfg.sigmaStepUm);
        m.put("invertIntensity", cfg.invertIntensity);
        m.put("rollingBallRadiusUm", cfg.rollingBallRadiusUm);
        m.put("zoneMode", cfg.zoneMode);
        m.put("borderZoneUm", cfg.borderZoneUm);
        m.put("analysisDownsample", Math.max(1.0, cfg.analysisDownsample));
        m.put("useImagePixelSize", cfg.useImagePixelSize);
        if (cfg.pixelSizeOverrideUm > 0) m.put("pixelSizeOverrideUm", cfg.pixelSizeOverrideUm);
        m.put("pixelSizes", commonPixelUm > 0 ? "common " + PixelSizeCheck.um(commonPixelUm) + " um/px" : "own");
        return m;
    }

    /** @return the pixel size a run with these settings uses for this image, NaN when none */
    private static double pixelUm(ImageServer<BufferedImage> server, CalibrationConfig cfg) {
        if (cfg.useImagePixelSize) {
            PixelCalibration cal = server.getPixelCalibration();
            if (cal != null && cal.hasPixelSizeMicrons()) return cal.getAveragedPixelSizeMicrons();
        }
        return cfg.pixelSizeOverrideUm > 0 ? cfg.pixelSizeOverrideUm : Double.NaN;
    }

    private static void checkCancelled(ProgressCallback prog) {
        if (prog != null && prog.isCancelled()) throw new CancellationException("Calibration canceled");
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
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
}
