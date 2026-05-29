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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.FiberAnalysisExtension;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Orchestrates one Fiber Analysis run: for each selected annotation, read the
 * dilated bounding box from the image server, dispatch an Appose task with
 * {@link FiberAnalysisParams} -> Python {@code run_fiber_analysis.py}, parse
 * the result, and append an {@link AnnotationResult} card to the UI panel.
 *
 * <p>Modelled on {@code qupath.ext.ppm.analysis.PPMPerpendicularityWorkflow}
 * (overall shape) and its {@code createWindowDetections} helper for the
 * optional per-window PathObject path.
 */
public class FiberAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisWorkflow.class);

    /** Windows reserved device names (case-insensitive) for the filename sanitiser. */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
            "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * The UI panel that consumes per-annotation results. Held as {@code Object}
     * for backward compatibility with the dialog constructor signature
     * {@code (QuPathGUI, Object)}; in practice this is a
     * {@link qupath.ext.fiberanalysis.analysis.FiberAnalysisPanel} created by
     * {@link FiberAnalysisExtension#getOrCreatePanel()}. The panel exposes
     * {@code public void appendResult(AnnotationResult result)} which is
     * called per annotation on the JavaFX thread.
     */
    private final Object panel;

    /**
     * Constructs a workflow that will append per-annotation results to the
     * given panel. {@code panel} may be {@code null} for headless runs.
     */
    public FiberAnalysisWorkflow(Object panel) {
        this.panel = panel;
    }

    /**
     * Runs analysis for the supplied annotations. The call returns immediately;
     * actual work happens on a background thread. Progress is shown in a small
     * modal-less stage with two progress bars (one for annotation index, one
     * for the per-annotation Appose step). The QPSC project's
     * {@code DualProgressDialog} is NOT reachable from this extension because
     * we deliberately do not depend on {@code qupath-extension-qpsc}; the local
     * minimal progress UI below stands in for it (see 02_design.md section 4
     * cross-cutting decision on DualProgressDialog reachability).
     */
    public Thread runForAnnotations(
            FiberAnalysisParams params,
            List<PathObject> annotations,
            ImageData<BufferedImage> imageData,
            QuPathGUI qupath) {
        if (annotations == null || annotations.isEmpty()) {
            Dialogs.showWarningNotification("Fiber Analysis", "No annotations selected.");
            return null;
        }
        if (imageData == null || imageData.getServer() == null) {
            Dialogs.showErrorMessage("Fiber Analysis", "No image data available.");
            return null;
        }

        Window owner = qupath != null ? qupath.getStage() : null;
        ProgressUi progress = new ProgressUi(owner, annotations.size());

        // Surface the results panel up front (B2 fix). If the user invoked Run
        // with the panel hidden, we make sure it is reachable before the first
        // appendResult, so the run is never "where did the results go?".
        Platform.runLater(() -> {
            progress.show();
            FiberAnalysisExtension.ensureResultWindow(qupath);
            // Start the run counter on the panel so the status line counts down
            // correctly even if appendResult fires before any UI interaction.
            try {
                Object p = panel;
                if (p != null) {
                    p.getClass().getMethod("startRun", int.class).invoke(p, annotations.size());
                }
            } catch (Exception ex) {
                logger.debug("Could not call panel.startRun: {}", ex.getMessage());
            }
        });

        Thread worker = new Thread(() -> runWorker(params, annotations, imageData, progress), "FiberAnalysis-Worker");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private void runWorker(
            FiberAnalysisParams params,
            List<PathObject> annotations,
            ImageData<BufferedImage> imageData,
            ProgressUi progress) {
        long t0 = System.currentTimeMillis();
        int total = annotations.size();
        int completed = 0;
        int failed = 0;
        try {
            // Lazy-init the Appose service. Heavy on first run.
            progress.setMain("Initializing Appose environment...", 0, total);
            try {
                ApposeFiberService.getInstance().initialize(progress::setSub);
            } catch (IOException e) {
                logger.error("Failed to initialize Appose service", e);
                Platform.runLater(() -> {
                    progress.close();
                    Dialogs.showErrorMessage(
                            "Fiber Analysis", "Failed to set up Python environment: " + e.getMessage());
                });
                return;
            }

            double pixelSizeUm = resolvePixelSizeUm(params, imageData);

            // Compute the parameter fingerprint once -- same hash for every
            // annotation in the run (PI M3). Stable hex prefix of SHA-256 over
            // a sorted-key JSON serialisation of the input parameters.
            String paramsHash = computeParamsHash(params);

            // Per-run output dir: <baseRoot>/<timestamp>_<paramsHash[:8]>/. The
            // base root prefers an explicit user setting, falls back to
            // <projectDir>/fiber-analysis/, and finally to ~/QuPath/fiber-out/.
            Path baseRoot = resolveBaseOutputRoot(params, imageData);
            String runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String runFolder = runStamp + "_" + paramsHash.substring(0, Math.min(8, paramsHash.length()));
            Path outputRoot = baseRoot.resolve(runFolder);
            try {
                Files.createDirectories(outputRoot);
                writeParamsFiles(outputRoot, params, paramsHash, runStamp, imageData);
            } catch (IOException e) {
                logger.warn("Could not create output dir or write params: {}", e.getMessage());
            }

            List<PathObject> allWindowDetections = new ArrayList<>();

            for (int i = 0; i < total; i++) {
                PathObject ann = annotations.get(i);
                String annName = displayName(ann, i);
                progress.setMain("Annotation " + (i + 1) + " of " + total + ": " + annName, i, total);

                try {
                    OneShotResult shot = runOne(params, ann, i, imageData, pixelSizeUm, outputRoot, progress);
                    AnnotationResult res = shot.result;
                    completed++;
                    appendResult(res);

                    // Optional: per-window detection objects from windows.json.
                    // Mirrors PPMPerpendicularityWorkflow.java:1243-1258 +
                    // createWindowDetections at PPMPerpendicularityWorkflow.java:2019-2054.
                    if (params.windowObjects()) {
                        Path windowsJson = res.outputDir().resolve("windows.json");
                        if (Files.exists(windowsJson)) {
                            List<PathObject> windowDetections = createWindowDetections(
                                    windowsJson,
                                    res.regionOffsetX(),
                                    res.regionOffsetY(),
                                    i,
                                    shot.runId,
                                    paramsHash,
                                    params.minWindowCoveragePercent());
                            allWindowDetections.addAll(windowDetections);
                            logger.info(
                                    "Created {} window detection objects for annotation '{}'",
                                    windowDetections.size(),
                                    annName);
                        } else {
                            logger.info(
                                    "Window-create requested but windows.json missing for annotation '{}'", annName);
                        }
                    }
                } catch (Exception e) {
                    failed++;
                    logger.error("Annotation {} failed", annName, e);
                    Platform.runLater(() -> Dialogs.showErrorNotification(
                            "Fiber Analysis", "Annotation \"" + annName + "\" failed: " + e.getMessage()));
                }
            }

            // Add all window detections to the hierarchy in one shot.
            if (!allWindowDetections.isEmpty() && imageData.getHierarchy() != null) {
                final PathObjectHierarchy hierarchy = imageData.getHierarchy();
                final List<PathObject> toAdd = new ArrayList<>(allWindowDetections);
                Platform.runLater(() -> {
                    hierarchy.addObjects(toAdd);
                    logger.info("Added {} window detection objects to hierarchy", toAdd.size());
                });
            }

            long ms = System.currentTimeMillis() - t0;
            logger.info("Fiber Analysis run complete: {} ok, {} failed, {} ms", completed, failed, ms);

            // Tell the panel we are done (status line flips to "complete in X s").
            try {
                final Object p = panel;
                if (p != null) {
                    Platform.runLater(() -> {
                        try {
                            p.getClass().getMethod("finishRun").invoke(p);
                        } catch (Exception ex) {
                            logger.debug("Could not call panel.finishRun: {}", ex.getMessage());
                        }
                    });
                }
            } catch (Exception ex) {
                logger.debug("finishRun dispatch failed: {}", ex.getMessage());
            }
        } finally {
            Platform.runLater(progress::close);
        }
    }

    /** Per-annotation analysis output: the panel-facing record plus run-level provenance keys. */
    private static final class OneShotResult {
        final AnnotationResult result;
        final String runId;

        OneShotResult(AnnotationResult result, String runId) {
            this.result = result;
            this.runId = runId;
        }
    }

    /**
     * Runs analysis for a single annotation. Returns an {@link AnnotationResult}
     * (for the panel) plus the Python-generated run-id (for PathObject
     * provenance). Throws on Python failure so the worker loop can record and
     * continue.
     */
    private OneShotResult runOne(
            FiberAnalysisParams params,
            PathObject annotation,
            int index,
            ImageData<BufferedImage> imageData,
            double pixelSizeUm,
            Path outputRoot,
            ProgressUi progress)
            throws Exception {

        ROI roi = annotation.getROI();
        if (roi == null) {
            throw new IllegalArgumentException("Annotation has no ROI");
        }
        ImageServer<BufferedImage> server = imageData.getServer();

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        // Dilated bbox: pad by the border-zone width (converted to pixels) plus a slop.
        double dilationPx = params.borderZoneUm() / pixelSizeUm;
        int pad = (int) Math.ceil(dilationPx) + 5;
        int rx = Math.max(0, x - pad);
        int ry = Math.max(0, y - pad);
        int rw = Math.min(server.getWidth() - rx, w + 2 * pad);
        int rh = Math.min(server.getHeight() - ry, h + 2 * pad);

        logger.info(
                "Annotation {}: roi=({},{}) {}x{}, dilated=({},{}) {}x{}, dilation_px={}",
                index,
                x,
                y,
                w,
                h,
                rx,
                ry,
                rw,
                rh,
                dilationPx);

        // Read the region as RGB and write it to disk for the Python script.
        // We avoid passing NDArrays through Appose for the first cut to keep
        // the contract simple. A future iteration can switch to NDArray IPC
        // for speed (pattern in PPMPerpendicularityWorkflow.bufferedImageToRGBNDArray).
        progress.setSub("Reading image region...");
        RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, rx, ry, rw, rh);
        BufferedImage region = server.readRegion(request);

        Path annDir = outputRoot.resolve(
                String.format(Locale.ROOT, "annotation_%03d_%s", index, sanitizeAnnotationName(annotation.getName())));
        Files.createDirectories(annDir);
        Path regionPng = annDir.resolve("region.png");
        javax.imageio.ImageIO.write(region, "PNG", regionPng.toFile());

        // Polygon boundary mask: rasterise the annotation's actual shape into a
        // binary PNG sized to the dilated region. v1 sent only the axis-aligned
        // bbox; the polygon raster gives the dilation step a tight footprint for
        // concave / curved annotations and matches PPM's rasterize_geojson_to_mask
        // semantics without dragging in a separate Python rasteriser.
        Path boundaryPng = annDir.resolve("boundary_mask.png");
        rasterisePolygonMask(roi, rx, ry, rw, rh, boundaryPng);

        Map<String, Object> inputs = buildScriptInputs(
                params, pixelSizeUm, regionPng, boundaryPng, annDir, rx, ry, rw, rh, x - rx, y - ry, w, h);

        progress.setSub("Running fiber analysis (Python)...");
        Task task = ApposeFiberService.getInstance().runTask("run_fiber_analysis", inputs);
        Object json = task.outputs.get("result_json");
        if (json == null) {
            throw new IOException("Python returned no result_json");
        }
        JsonObject result = new Gson().fromJson(String.valueOf(json), JsonObject.class);
        if (result.has("error") && !result.get("error").isJsonNull()) {
            throw new RuntimeException("Python error: " + result.get("error").getAsString());
        }

        Map<String, Path> overlays = new LinkedHashMap<>();
        registerIfExists(overlays, "fiber_mask", annDir.resolve("fiber_mask_overlay.png"));
        registerIfExists(overlays, "straightness", annDir.resolve("straightness_overlay.png"));
        // GLCM: one overlay per enabled property; same ComboBox pattern in
        // the panel. "Show GLCM" alone was ambiguous because GLCM is a
        // family of 6 properties.
        for (String prop : new String[] {
            "contrast", "correlation", "energy", "homogeneity", "entropy", "dissimilarity"
        }) {
            registerIfExists(overlays, "glcm:" + prop, annDir.resolve("texture_" + prop + "_overlay.png"));
        }
        // Morphometrics: each enabled metric in the family now has a
        // per-window heatmap (the user picks window size + overlap, so we
        // emit per-window everything that can be expressed per window and
        // let them decide what is meaningful at their chosen scale). The
        // panel surfaces them through a ComboBox the same way as GLCM.
        // Per-box lacunarity overlays are NOT registered to keep the
        // ComboBox tidy -- "lacunarity_mean" is the headline, and the
        // per-box numbers are still emitted to windows.json + the
        // measurement table for downstream pandas users.
        for (String prop : new String[] {
            "fiber_coverage_percent",
            "hdm",
            "ridge_count",
            "total_length_px",
            "total_length_um",
            "branch_points",
            "endpoints",
            "mean_curvature_per_px",
            "mean_curvature_per_um",
            "fractal_dimension",
            "lacunarity_mean",
            "gap_mean_px",
            "gap_mean_um",
            "gap_max_px",
            "gap_max_um"
        }) {
            registerIfExists(overlays, "morph:" + prop, annDir.resolve("morph_" + prop + "_overlay.png"));
        }

        Map<String, Double> summary = flattenScalars(result);

        // Extract the Python-generated run-id (UUID) for PathObject provenance.
        // Always present in the new schema (PI M1/M2); legacy / failure paths
        // fall back to a blank string so caller code can treat it as optional.
        String runId = "";
        if (result.has("run") && result.get("run").isJsonObject()) {
            JsonObject runBlock = result.getAsJsonObject("run");
            if (runBlock.has("run_id") && !runBlock.get("run_id").isJsonNull()) {
                runId = runBlock.get("run_id").getAsString();
            }
        }

        AnnotationResult annRes =
                new AnnotationResult(index, displayName(annotation, index), annDir, overlays, summary, rx, ry, rw, rh);
        return new OneShotResult(annRes, runId);
    }

    /**
     * Builds the input map handed to {@code run_fiber_analysis.py}. The names
     * here ARE the script's contract -- changing one means updating both sides.
     */
    private Map<String, Object> buildScriptInputs(
            FiberAnalysisParams p,
            double pixelSizeUm,
            Path regionPng,
            Path boundaryMaskPng,
            Path outputDir,
            int regionX,
            int regionY,
            int regionW,
            int regionH,
            int bboxXInRegion,
            int bboxYInRegion,
            int bboxW,
            int bboxH) {
        Map<String, Object> in = new HashMap<>();

        // Region image (read by Python via PIL)
        in.put("region_image_path", regionPng.toString());
        in.put("region_w", regionW);
        in.put("region_h", regionH);
        in.put("region_offset_x", regionX);
        in.put("region_offset_y", regionY);

        // Polygon-rasterised boundary mask (Java-side). Python prefers this over
        // the bbox when the file exists; the bbox fields remain as a fallback so
        // the script still works if the mask write fails or is omitted.
        in.put("boundary_mask_path", boundaryMaskPng.toString());

        // Annotation bbox in region-local coords (fallback boundary mask source).
        in.put("bbox_x", bboxXInRegion);
        in.put("bbox_y", bboxYInRegion);
        in.put("bbox_w", bboxW);
        in.put("bbox_h", bboxH);

        // Search area
        in.put("pixel_size_um", pixelSizeUm);
        in.put("border_zone_width_um", p.borderZoneUm());
        in.put("zone_mode", p.zoneMode());

        // Segmentation
        in.put("seg_source", p.segSource());
        in.put("seg_channel", p.internalChannel());
        in.put("threshold_method", p.thresholdMethod().toLowerCase());
        in.put("manual_threshold", p.manualThreshold());
        in.put("ridge_filter", p.ridgeFilter().toLowerCase());
        // All spatial inputs are exposed to the user in microns. Convert to
        // pixels here using this image's measured pixel size so the Python
        // side (which fundamentally operates in pixel-domain arrays) can stay
        // unchanged. Each image's pixel size is resolved per-annotation in
        // pixelSizeUm above, so cross-image runs convert correctly.
        in.put("sigma_min", p.sigmaMinUm() / pixelSizeUm);
        in.put("sigma_max", p.sigmaMaxUm() / pixelSizeUm);
        in.put("sigma_step", p.sigmaStepUm() / pixelSizeUm);
        // Min fiber area was supplied as um^2; pixels^2 = um^2 / (um/px)^2.
        in.put("min_fiber_area_px", (int) Math.max(0, Math.round(p.minFiberAreaUm2() / (pixelSizeUm * pixelSizeUm))));
        in.put("existing_mask_path", p.maskFile());
        // Project-calibrated threshold + brightfield handling. The Python
        // side reads project_threshold_norm only when threshold_method ==
        // 'project_otsu'; the others ignore it.
        in.put("invert_intensity", p.invertIntensity());
        in.put("rolling_ball_radius", (int) Math.max(0, Math.round(p.rollingBallRadiusUm() / pixelSizeUm)));
        if ("Project Otsu (calibrated)".equals(p.thresholdMethod())
                && p.projectCalibrationName() != null
                && !p.projectCalibrationName().isBlank()) {
            Double thr = loadCalibratedThreshold(p.projectCalibrationName());
            if (thr != null) {
                in.put("project_threshold_norm", thr);
            }
            // Also normalise the threshold_method string into the snake_case
            // value the Python segmentation module recognises.
            in.put("threshold_method", "project_otsu");
        }

        // Window analysis
        in.put("window_enabled", p.windowEnabled());
        in.put("window_size_um", p.windowSizeUm());
        in.put("window_overlap_percent", p.windowOverlapPercent());
        in.put("window_create_objects", p.windowObjects());
        in.put("min_window_coverage_percent", p.minWindowCoveragePercent());

        // Straightness
        in.put("straightness_enabled", p.straightnessEnabled());
        in.put("tortuosity_on", p.tortuosityOn());
        in.put("radon_on", p.radonOn());
        in.put("min_branch_um", p.minBranchUm());

        // Morphometrics
        in.put("morph_enabled", p.morphEnabled());
        in.put("morph_branch", p.branchpoints());
        in.put("morph_endpoints", p.endpoints());
        in.put("morph_length", p.length());
        in.put("morph_curvature", p.curvature());
        in.put("morph_hdm", p.hdm());
        in.put("morph_lac", p.lacunarity());
        in.put("morph_fd", p.fractal());
        in.put("morph_gaps", p.gapAnalysis());
        // Box-size lists are in microns; convert each element to integer px,
        // floor at 1 to keep the histograms / box-count meaningful at small
        // pixel-size images, and drop duplicates that collide after rounding.
        in.put("lac_box_sizes_px", umListToPxCsv(p.lacBoxSizesUm(), pixelSizeUm));
        in.put("fractal_box_sizes_px", umListToPxCsv(p.fractalBoxSizesUm(), pixelSizeUm));

        // Texture
        in.put("texture_enabled", p.textureEnabled());
        in.put("quant_levels", p.quantLevels());
        in.put("glcm_distances_px", umListToPxCsv(p.glcmDistancesUm(), pixelSizeUm));
        in.put("texture_contrast", p.contrast());
        in.put("texture_correlation", p.correlation());
        in.put("texture_energy", p.energy());
        in.put("texture_homogeneity", p.homogeneity());
        in.put("texture_entropy", p.entropy());
        in.put("texture_dissimilarity", p.dissimilarity());

        // Output
        in.put("output_dir", outputDir.toString());
        in.put("heatmap_property", p.glcmHeatmapProp());
        in.put("emit_fiber_mask_png", p.fiberMaskOverlay());
        in.put("emit_straightness_png", p.straightnessHeatmap());
        in.put("emit_glcm_png", p.glcmHeatmap());
        in.put("emit_morph_summary", p.morphSummary());
        in.put("emit_json_sidecar", p.jsonSidecar());
        in.put("emit_npz", p.emitNpz());

        // Provenance -- echoed back by the Python script into results.json /
        // windows.json so a sidecar can be matched to the build that produced
        // it (PI M1 / M2 fix).
        String extVersion = GeneralTools.getPackageVersion(FiberAnalysisWorkflow.class);
        in.put("extension_version", extVersion != null ? extVersion : "0.1.0-SNAPSHOT");

        return in;
    }

    private void appendResult(AnnotationResult res) {
        if (panel == null) {
            logger.info(
                    "No results panel registered; result for {} written to {}", res.annotationName(), res.outputDir());
            return;
        }
        Platform.runLater(() -> {
            try {
                panel.getClass()
                        .getMethod("appendResult", AnnotationResult.class)
                        .invoke(panel, res);
            } catch (Exception e) {
                logger.warn("Could not append result to panel: {}", e.getMessage());
            }
        });
    }

    private static Map<String, Double> flattenScalars(JsonObject obj) {
        Map<String, Double> out = new LinkedHashMap<>();
        flatten(obj, "", out);
        return out;
    }

    private static void flatten(JsonElement el, String prefix, Map<String, Double> out) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isNumber()) {
                out.put(prefix, el.getAsDouble());
            }
            return;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flatten(e.getValue(), key, out);
            }
        }
        // arrays intentionally skipped for the flat-scalar summary
    }

    private static void registerIfExists(Map<String, Path> out, String key, Path candidate) {
        if (Files.isRegularFile(candidate)) {
            out.put(key, candidate);
        }
    }

    private static double resolvePixelSizeUm(FiberAnalysisParams p, ImageData<BufferedImage> imageData) {
        if (p.useImagePixelSize()) {
            PixelCalibration cal = imageData.getServer().getPixelCalibration();
            if (cal != null && cal.hasPixelSizeMicrons()) {
                return cal.getAveragedPixelSizeMicrons();
            }
            logger.warn("Image has no pixel calibration; falling back to override value {}", p.pixelSizeOverrideUm());
        }
        return p.pixelSizeOverrideUm();
    }

    /**
     * Converts a comma-separated list of micron values into a comma-separated
     * list of integer pixel values for the Python side. Each element is
     * rounded to the nearest int and floored at 1 px so the histograms /
     * GLCM offsets remain meaningful. Duplicates that collide after rounding
     * are de-duped while preserving order.
     */
    static String umListToPxCsv(String umList, double pixelSizeUm) {
        if (umList == null || umList.isBlank() || pixelSizeUm <= 0) return "";
        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
        for (String tok : umList.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try {
                double v = Double.parseDouble(t);
                if (v <= 0) continue;
                int px = (int) Math.max(1, Math.round(v / pixelSizeUm));
                seen.add(px);
            } catch (NumberFormatException ignored) {
                // skip malformed tokens; validation in the dialog already gated this
            }
        }
        return seen.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Reads {@code <project>/fiber-analysis/calibration_<name>.json} and
     * returns the calibrated {@code threshold_normalised} value. Returns
     * {@code null} if the file is missing or malformed -- the Python side
     * then falls back to per-region Otsu and logs a warning.
     */
    static Double loadCalibratedThreshold(String calibrationName) {
        if (calibrationName == null || calibrationName.isBlank()) return null;
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getProject() == null || gui.getProject().getPath() == null) {
                return null;
            }
            Path projDir = gui.getProject().getPath().getParent();
            if (projDir == null) return null;
            Path calFile = projDir.resolve("fiber-analysis").resolve("calibration_" + calibrationName + ".json");
            if (!Files.isRegularFile(calFile)) return null;
            JsonObject obj = new Gson().fromJson(Files.readString(calFile), JsonObject.class);
            if (obj == null || !obj.has("threshold_normalised")) return null;
            return obj.get("threshold_normalised").getAsDouble();
        } catch (Exception ex) {
            logger.warn("Could not read calibration '{}': {}", calibrationName, ex.getMessage());
            return null;
        }
    }

    /**
     * Resolves the base directory under which the per-run subdirectory is
     * created. Priority:
     * <ol>
     *   <li>The user-set {@code outputDir} from the dialog, if non-blank.</li>
     *   <li>{@code <project>/fiber-analysis/} when a QuPath project is open.</li>
     *   <li>{@code ~/QuPath/fiber-out/} as the last-resort fallback.</li>
     * </ol>
     * The returned path is the *base* -- the workflow appends a per-run
     * timestamped subdirectory under it.
     */
    static Path resolveBaseOutputRoot(FiberAnalysisParams p, ImageData<BufferedImage> imageData) {
        String configured = p.outputDir();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        // Try the open project (via the QuPath GUI singleton) before the home fallback.
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getProject() != null) {
                Project<?> project = gui.getProject();
                Path projFile = project.getPath();
                if (projFile != null) {
                    Path projDir = projFile.getParent();
                    if (projDir != null) {
                        return projDir.resolve("fiber-analysis");
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not resolve project dir for default output: {}", ex.getMessage());
        }
        return Paths.get(System.getProperty("user.home"), "QuPath", "fiber-out");
    }

    /**
     * Writes the per-run {@code params.json} (machine-readable, suitable as
     * input to a batch run) and {@code params.txt} (human-readable echo of
     * every dialog control). Both land in the per-run subdir alongside the
     * per-annotation output folders.
     */
    static void writeParamsFiles(
            Path runDir, FiberAnalysisParams p, String paramsHash, String runStamp, ImageData<BufferedImage> imageData)
            throws IOException {
        Map<String, Object> meta = paramsAsOrderedMap(p);
        // Provenance keys at the top so a human reading params.txt sees them first.
        Map<String, Object> withMeta = new LinkedHashMap<>();
        withMeta.put("run_timestamp", runStamp);
        withMeta.put("params_hash", paramsHash);
        String extVersion = GeneralTools.getPackageVersion(FiberAnalysisWorkflow.class);
        withMeta.put("extension_version", extVersion != null ? extVersion : "0.1.0-SNAPSHOT");
        if (imageData != null && imageData.getServer() != null) {
            try {
                withMeta.put("image_name", imageData.getServer().getMetadata().getName());
            } catch (Exception ignored) {
                // best effort
            }
        }
        withMeta.putAll(meta);

        Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(runDir.resolve("params.json"), gson.toJson(withMeta));

        StringBuilder sb = new StringBuilder();
        sb.append("# fiber-analysis run parameters\n");
        sb.append("# Reload this run's settings by pointing the batch dialog at params.json in this folder.\n");
        for (Map.Entry<String, Object> e : withMeta.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(String.valueOf(e.getValue())).append('\n');
        }
        Files.writeString(runDir.resolve("params.txt"), sb.toString());
    }

    /**
     * Mirrors {@link #computeParamsHash}'s key ordering (TreeMap there, explicit
     * insertion order here) so a human reading params.txt sees fields grouped
     * by dialog section rather than alphabetically.
     */
    private static Map<String, Object> paramsAsOrderedMap(FiberAnalysisParams p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("searchArea", p.searchArea());
        m.put("classFilter", p.classFilter());
        m.put("borderZoneUm", p.borderZoneUm());
        m.put("zoneMode", p.zoneMode());
        m.put("useImagePixelSize", p.useImagePixelSize());
        m.put("pixelSizeOverrideUm", p.pixelSizeOverrideUm());
        m.put("segSource", p.segSource());
        m.put("internalChannel", p.internalChannel());
        m.put("thresholdMethod", p.thresholdMethod());
        m.put("manualThreshold", p.manualThreshold());
        m.put("ridgeFilter", p.ridgeFilter());
        m.put("sigmaMinUm", p.sigmaMinUm());
        m.put("sigmaMaxUm", p.sigmaMaxUm());
        m.put("sigmaStepUm", p.sigmaStepUm());
        m.put("minFiberAreaUm2", p.minFiberAreaUm2());
        m.put("maskSource", p.maskSource());
        m.put("classifierName", p.classifierName());
        m.put("objectClass", p.objectClass());
        m.put("maskFile", p.maskFile());
        m.put("windowEnabled", p.windowEnabled());
        m.put("windowSizeUm", p.windowSizeUm());
        m.put("windowOverlapPercent", p.windowOverlapPercent());
        m.put("windowObjects", p.windowObjects());
        m.put("minWindowCoveragePercent", p.minWindowCoveragePercent());
        m.put("straightnessEnabled", p.straightnessEnabled());
        m.put("tortuosityOn", p.tortuosityOn());
        m.put("radonOn", p.radonOn());
        m.put("minBranchUm", p.minBranchUm());
        m.put("morphEnabled", p.morphEnabled());
        m.put("branchpoints", p.branchpoints());
        m.put("endpoints", p.endpoints());
        m.put("length", p.length());
        m.put("curvature", p.curvature());
        m.put("hdm", p.hdm());
        m.put("lacunarity", p.lacunarity());
        m.put("fractal", p.fractal());
        m.put("gapAnalysis", p.gapAnalysis());
        m.put("lacBoxSizesUm", p.lacBoxSizesUm());
        m.put("fractalBoxSizesUm", p.fractalBoxSizesUm());
        m.put("textureEnabled", p.textureEnabled());
        m.put("quantLevels", p.quantLevels());
        m.put("glcmDistancesUm", p.glcmDistancesUm());
        m.put("contrast", p.contrast());
        m.put("correlation", p.correlation());
        m.put("energy", p.energy());
        m.put("homogeneity", p.homogeneity());
        m.put("entropy", p.entropy());
        m.put("dissimilarity", p.dissimilarity());
        m.put("outputDir", p.outputDir());
        m.put("fiberMaskOverlay", p.fiberMaskOverlay());
        m.put("straightnessHeatmap", p.straightnessHeatmap());
        m.put("glcmHeatmap", p.glcmHeatmap());
        m.put("glcmHeatmapProp", p.glcmHeatmapProp());
        m.put("morphSummary", p.morphSummary());
        m.put("jsonSidecar", p.jsonSidecar());
        m.put("emitNpz", p.emitNpz());
        return m;
    }

    private static String displayName(PathObject ann, int idx) {
        String n = ann.getName();
        if (n != null && !n.isBlank()) return n;
        String cls = ann.getPathClass() != null ? ann.getPathClass().toString() : "Annotation";
        return cls + " [" + idx + "]";
    }

    /**
     * Sanitises a user-supplied annotation name for use as a filesystem path
     * component. Hardened against Windows-specific failure modes that the
     * v0.1.0 sanitiser missed (Clinical-tester M3):
     *
     * <ul>
     *   <li>Strips invalid filename characters via QuPath's
     *       {@link GeneralTools#stripInvalidFilenameChars(String)}.</li>
     *   <li>Trims trailing dots and spaces (Windows silently folds both, so a
     *       sibling folder differing only in trailing punctuation would collide
     *       silently).</li>
     *   <li>Prepends an underscore when the result equals a Windows reserved
     *       device name ({@code CON}, {@code PRN}, {@code COM1}, etc.).</li>
     *   <li>Caps the component length at 80 characters so a deeply-nested
     *       project folder still fits under the legacy 260-char Windows MAX_PATH
     *       once the {@code annotation_NNN_} prefix and child filenames are
     *       added.</li>
     *   <li>Falls back to {@code "annotation"} on null/blank/all-stripped
     *       input, replacing the old "unnamed" placeholder.</li>
     * </ul>
     *
     * <p>Precedent: {@code claude-reports/2025-01-22_background-dialog-fix-filename-sanitization.md}.
     */
    private static String sanitizeAnnotationName(String raw) {
        if (raw == null) return "annotation";
        String stripped = GeneralTools.stripInvalidFilenameChars(raw);
        if (stripped == null) return "annotation";
        // Replace any remaining whitespace/path separators with underscores so
        // the component is shell-safe in the unsanitised cwd cases too.
        stripped = stripped.replaceAll("[\\s/\\\\:]+", "_");
        // Trim trailing dots and spaces (Windows refuses both at the OS layer).
        stripped = stripped.replaceAll("[. ]+$", "");
        if (stripped.isBlank()) return "annotation";
        // Path-component length cap. 80 chars leaves headroom for the
        // annotation_NNN_ prefix (~16 chars), the per-user output root, and
        // the child filenames (region.png, windows.json, etc.) under the
        // historic 260-char Windows MAX_PATH.
        if (stripped.length() > 80) {
            stripped = stripped.substring(0, 80);
        }
        // Windows reserved device names (case-insensitive), with or without
        // an extension. Prepend an underscore to dodge the collision.
        String upper = stripped.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String stem = dot >= 0 ? upper.substring(0, dot) : upper;
        if (WINDOWS_RESERVED.contains(stem)) {
            stripped = "_" + stripped;
        }
        return stripped;
    }

    /**
     * Computes a stable hash of a {@link FiberAnalysisParams} for run-to-run
     * comparison. Serialises the record to a JSON string with sorted keys
     * (TreeMap), takes SHA-256, returns the first 12 hex chars (~48 bits ->
     * birthday collision at 16.8M runs, well beyond v1 testbed scale).
     */
    static String computeParamsHash(FiberAnalysisParams p) {
        Map<String, Object> ordered = new TreeMap<>();
        ordered.put("borderZoneUm", p.borderZoneUm());
        ordered.put("zoneMode", p.zoneMode());
        ordered.put("useImagePixelSize", p.useImagePixelSize());
        ordered.put("pixelSizeOverrideUm", p.pixelSizeOverrideUm());
        ordered.put("segSource", p.segSource());
        ordered.put("internalChannel", p.internalChannel());
        ordered.put("thresholdMethod", p.thresholdMethod());
        ordered.put("manualThreshold", p.manualThreshold());
        ordered.put("ridgeFilter", p.ridgeFilter());
        ordered.put("sigmaMinUm", p.sigmaMinUm());
        ordered.put("sigmaMaxUm", p.sigmaMaxUm());
        ordered.put("sigmaStepUm", p.sigmaStepUm());
        ordered.put("minFiberAreaUm2", p.minFiberAreaUm2());
        ordered.put("maskSource", p.maskSource());
        ordered.put("classifierName", p.classifierName());
        ordered.put("objectClass", p.objectClass());
        ordered.put("maskFile", p.maskFile());
        ordered.put("windowEnabled", p.windowEnabled());
        ordered.put("windowSizeUm", p.windowSizeUm());
        ordered.put("windowOverlapPercent", p.windowOverlapPercent());
        ordered.put("windowObjects", p.windowObjects());
        ordered.put("minWindowCoveragePercent", p.minWindowCoveragePercent());
        ordered.put("straightnessEnabled", p.straightnessEnabled());
        ordered.put("tortuosityOn", p.tortuosityOn());
        ordered.put("radonOn", p.radonOn());
        ordered.put("minBranchUm", p.minBranchUm());
        ordered.put("morphEnabled", p.morphEnabled());
        ordered.put("branchpoints", p.branchpoints());
        ordered.put("endpoints", p.endpoints());
        ordered.put("length", p.length());
        ordered.put("curvature", p.curvature());
        ordered.put("hdm", p.hdm());
        ordered.put("lacunarity", p.lacunarity());
        ordered.put("fractal", p.fractal());
        ordered.put("gapAnalysis", p.gapAnalysis());
        ordered.put("lacBoxSizesUm", p.lacBoxSizesUm());
        ordered.put("fractalBoxSizesUm", p.fractalBoxSizesUm());
        ordered.put("textureEnabled", p.textureEnabled());
        ordered.put("quantLevels", p.quantLevels());
        ordered.put("glcmDistancesUm", p.glcmDistancesUm());
        ordered.put("contrast", p.contrast());
        ordered.put("correlation", p.correlation());
        ordered.put("energy", p.energy());
        ordered.put("homogeneity", p.homogeneity());
        ordered.put("entropy", p.entropy());
        ordered.put("dissimilarity", p.dissimilarity());
        ordered.put("glcmHeatmapProp", p.glcmHeatmapProp());
        // Output toggles are excluded -- they affect what is written, not the
        // numbers computed. Same params with different output toggles still
        // produces the same windows.json measurements.

        String canonical = new Gson().toJson(ordered);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.substring(0, 12);
        } catch (Exception ex) {
            logger.warn("SHA-256 not available; using fallback hash: {}", ex.getMessage());
            return String.format("%012x", canonical.hashCode() & 0xffffffffL);
        }
    }

    /**
     * Rasterises the annotation's polygon into a binary mask sized to the dilated
     * region. Output PNG is 8-bit grayscale (0 = outside, 255 = inside). The Python
     * side loads it via PIL and casts to bool.
     *
     * <p>Implementation uses {@link ROI#getShape()} (a {@link java.awt.Shape} in
     * full-image pixel coordinates) and renders into a {@link BufferedImage} with
     * a translating AffineTransform so the shape lands in the region-local frame.
     * This sidesteps GeoJSON serialisation entirely while still respecting concave,
     * curved, and multi-part annotations.
     */
    static void rasterisePolygonMask(ROI roi, int regionX, int regionY, int regionW, int regionH, Path outputPng)
            throws IOException {
        BufferedImage mask = new BufferedImage(regionW, regionH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            // Clear to zero (BYTE_GRAY initialises to zero already, but be explicit).
            g.setComposite(AlphaComposite.Src);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, regionW, regionH);

            // Translate full-image coords -> region-local coords.
            g.setTransform(AffineTransform.getTranslateInstance(-regionX, -regionY));

            // Anti-alias OFF for a crisp binary mask (no anti-aliased grey edges).
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            Shape shape = roi.getShape();
            if (shape != null) {
                g.fill(shape);
            }
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(mask, "PNG", outputPng.toFile());
    }

    /**
     * Build one rectangular detection per non-empty window described in the given
     * {@code windows.json}. Each detection's ROI is anchored at the window's pixel
     * coordinates (translated by the region offset), and the window's measurements
     * (mean angle, order parameter, valid-pixel count, tortuosity, per-texture
     * props) are attached to the measurement list so they show up in QuPath's
     * measurement table.
     *
     * <p>Mirrors {@code PPMPerpendicularityWorkflow.createWindowDetections} at
     * {@code qupath-extension-ppm/src/main/java/qupath/ext/ppm/analysis/PPMPerpendicularityWorkflow.java:2019-2054},
     * extended to handle the richer fiber-analysis window schema (see
     * {@code fiberlib/io.py} {@code save_windows_json}).
     */
    private static List<PathObject> createWindowDetections(
            Path windowsJsonPath,
            int offsetX,
            int offsetY,
            int annotationIndex,
            String runId,
            String paramsHash,
            double minWindowCoveragePercent) {
        List<PathObject> out = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(windowsJsonPath)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            if (root == null || !root.has("windows")) {
                return out;
            }
            JsonArray windows = root.getAsJsonArray("windows");
            int outlineColor = ColorTools.packRGB(140, 140, 200);
            PathClass windowClass = PathClass.fromString("FiberAnalysis-Window", outlineColor);
            windowClass.setColor(outlineColor);
            int skippedLowCoverage = 0;
            for (JsonElement el : windows) {
                if (!el.isJsonObject()) continue;
                JsonObject w = el.getAsJsonObject();
                // Coverage gate: drop windows whose fiber coverage falls
                // below the user's threshold. Python also marks these as
                // `included: false` and writes NaN metrics, so this is a
                // belt-and-braces check that costs nothing.
                if (w.has("included") && !w.get("included").isJsonNull() && !w.get("included").getAsBoolean()) {
                    skippedLowCoverage++;
                    continue;
                }
                if (minWindowCoveragePercent > 0
                        && w.has("fiber_coverage_percent")
                        && !w.get("fiber_coverage_percent").isJsonNull()) {
                    double cov = w.get("fiber_coverage_percent").getAsDouble();
                    if (cov < minWindowCoveragePercent) {
                        skippedLowCoverage++;
                        continue;
                    }
                }
                int wx = w.get("x").getAsInt() + offsetX;
                int wy = w.get("y").getAsInt() + offsetY;
                int ww = w.get("w").getAsInt();
                int wh = w.get("h").getAsInt();

                ROI rect = ROIs.createRectangleROI(wx, wy, ww, wh, ImagePlane.getDefaultPlane());
                PathObject det = PathObjects.createDetectionObject(rect, windowClass);
                det.getMeasurementList().put("Parent annotation index", annotationIndex);

                // Dropped the "Window " prefix from every measurement name:
                // each detection IS a window, so the prefix was redundant in
                // the QuPath measurement table (Tortuosity, HDM, etc. are
                // the natural column headers for downstream density-map work).
                // Phase 5 reconciled schema: new keys are `n_fiber_px` and
                // `tortuosity_median`. Fall back to the v0.1.0 legacy keys
                // (`n_pixels` / `tortuosity`) so a windows.json written by an
                // older build still loads cleanly.
                addOptionalNumericPreferFirst(det, w, "Fiber pixels", "n_fiber_px", "n_pixels");
                addOptionalNumeric(det, w, "fiber_coverage_percent", "Fiber coverage (%)");
                addOptionalNumeric(det, w, "mean_angle_deg", "Mean angle (deg)");
                addOptionalNumeric(det, w, "order_parameter", "Order parameter");
                addOptionalNumericPreferFirst(det, w, "Tortuosity", "tortuosity_median", "tortuosity");
                addOptionalNumeric(det, w, "n_fibers", "Ridge count");
                addOptionalNumeric(det, w, "hdm", "HDM");
                // Per-window morphometric measurements. Names match the
                // annotation-level scalars where the underlying quantity is
                // the same. Units use um where the user-facing measurement
                // is naturally length-like; the matching _px columns are
                // also written so a downstream pandas analyst can stay in
                // pixel-domain if they want.
                addOptionalNumeric(det, w, "total_length_um", "Skeleton length (um)");
                addOptionalNumeric(det, w, "total_length_px", "Skeleton length (px)");
                addOptionalNumeric(det, w, "branch_points", "Branch points");
                addOptionalNumeric(det, w, "endpoints", "Endpoints");
                addOptionalNumeric(det, w, "mean_curvature_per_um", "Mean curvature (rad/um)");
                addOptionalNumeric(det, w, "mean_curvature_per_px", "Mean curvature (rad/px)");
                addOptionalNumeric(det, w, "fractal_dimension", "Fractal dimension");
                addOptionalNumeric(det, w, "lacunarity_mean", "Lacunarity (mean)");
                addOptionalNumeric(det, w, "gap_mean_um", "Mean gap (um)");
                addOptionalNumeric(det, w, "gap_mean_px", "Mean gap (px)");
                addOptionalNumeric(det, w, "gap_max_um", "Max gap (um)");
                addOptionalNumeric(det, w, "gap_max_px", "Max gap (px)");
                // Per-box lacunarity columns -- key shape "lacunarity_box_<N>".
                // Number of columns matches the user's configured box-size list,
                // so we discover them dynamically rather than hard-coding.
                for (Map.Entry<String, JsonElement> le : w.entrySet()) {
                    if (!le.getKey().startsWith("lacunarity_box_")) continue;
                    JsonElement lv = le.getValue();
                    if (lv == null || lv.isJsonNull() || !lv.isJsonPrimitive()) continue;
                    if (!lv.getAsJsonPrimitive().isNumber()) continue;
                    String num = le.getKey().substring("lacunarity_box_".length());
                    det.getMeasurementList().put("Lacunarity (box " + num + ")", lv.getAsDouble());
                }

                if (w.has("texture") && w.get("texture").isJsonObject()) {
                    JsonObject tex = w.getAsJsonObject("texture");
                    for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                        // Capitalised GLCM property name -- "Contrast (GLCM)",
                        // "Correlation (GLCM)", etc. Suffix disambiguates if
                        // future families (e.g. LBP) also produce a "Contrast".
                        String prop = e.getKey();
                        String capitalised = prop.isEmpty()
                                ? prop
                                : Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
                        String key = capitalised + " (GLCM)";
                        JsonElement v = e.getValue();
                        if (v != null
                                && !v.isJsonNull()
                                && v.isJsonPrimitive()
                                && v.getAsJsonPrimitive().isNumber()) {
                            det.getMeasurementList().put(key, v.getAsDouble());
                        }
                    }
                }

                // PI M3 fix: stamp every per-window detection with the run-id
                // (UUID from the Python script's output) and a parameter
                // fingerprint, so a measurement table that mixes runs can be
                // filtered post-hoc. Run-id goes through `putString` so it
                // appears as a categorical column, params-hash as a numeric
                // would be misleading (use string semantics; see
                // measurement-list contract).
                //
                // QuPath's MeasurementList API is numeric-only. We attach the
                // identifiers as string-keyed metadata on the PathObject so
                // they survive GeoJSON round-trips, and additionally store a
                // numeric hash-prefix on the measurement list for table-side
                // filtering by hash equality.
                if (runId != null && !runId.isEmpty()) {
                    det.getMetadata().put("fiber_analysis.run_id", runId);
                }
                if (paramsHash != null && !paramsHash.isEmpty()) {
                    det.getMetadata().put("fiber_analysis.params_hash", paramsHash);
                    // Numeric companion: prefix as long for measurement-table
                    // filtering. 12 hex chars = 48 bits, fits comfortably in
                    // a double's 53-bit mantissa.
                    try {
                        long asLong = Long.parseLong(paramsHash.substring(0, Math.min(12, paramsHash.length())), 16);
                        det.getMeasurementList().put("FiberAnalysis params hash (num)", (double) asLong);
                    } catch (NumberFormatException ignore) {
                        // Hash should always be hex; skip if it ever is not.
                    }
                }
                out.add(det);
            }
            if (skippedLowCoverage > 0) {
                logger.info(
                        "createWindowDetections: skipped {} windows below {}% coverage",
                        skippedLowCoverage,
                        minWindowCoveragePercent);
            }
        } catch (Exception ex) {
            logger.warn("Failed to parse windows.json {}: {}", windowsJsonPath, ex.getMessage());
        }
        return out;
    }

    private static void addOptionalNumeric(PathObject det, JsonObject w, String jsonKey, String measurementName) {
        if (!w.has(jsonKey)) return;
        JsonElement v = w.get(jsonKey);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return;
        if (!v.getAsJsonPrimitive().isNumber()) return;
        det.getMeasurementList().put(measurementName, v.getAsDouble());
    }

    /**
     * Variant of {@link #addOptionalNumeric} that tries multiple JSON keys in
     * priority order. First-found-wins. Used for the schema-rename fallback
     * (e.g. {@code n_fiber_px} preferred; {@code n_pixels} accepted for
     * back-compat with v0.1.0 windows.json).
     */
    private static void addOptionalNumericPreferFirst(
            PathObject det, JsonObject w, String measurementName, String... jsonKeys) {
        for (String key : jsonKeys) {
            if (!w.has(key)) continue;
            JsonElement v = w.get(key);
            if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) continue;
            if (!v.getAsJsonPrimitive().isNumber()) continue;
            det.getMeasurementList().put(measurementName, v.getAsDouble());
            return;
        }
    }

    // ===================== Progress UI =====================

    /**
     * Small two-bar progress dialog. Stand-in for QPSC's
     * {@code DualProgressDialog} which is intentionally not available here.
     */
    private static final class ProgressUi {
        private final Stage stage;
        private final Label mainLabel = new Label("Starting...");
        private final ProgressBar mainBar = new ProgressBar(0);
        private final Label subLabel = new Label("");
        private final ProgressBar subBar = new ProgressBar();

        ProgressUi(Window owner, int totalAnnotations) {
            this.stage = new Stage();
            stage.setTitle("Fiber Analysis");
            stage.initModality(Modality.NONE);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.setResizable(false);

            mainBar.setPrefWidth(420);
            subBar.setPrefWidth(420);
            subBar.setProgress(-1); // indeterminate by default

            VBox root = new VBox(8, mainLabel, mainBar, subLabel, subBar);
            root.setPadding(new Insets(12));
            stage.setScene(new Scene(root));
        }

        void show() {
            stage.show();
        }

        void close() {
            stage.close();
        }

        void setMain(String message, int done, int total) {
            Platform.runLater(() -> {
                mainLabel.setText(message);
                if (total > 0) {
                    mainBar.setProgress((double) done / (double) total);
                } else {
                    mainBar.setProgress(-1);
                }
            });
        }

        void setSub(String message) {
            Platform.runLater(() -> {
                subLabel.setText(message != null ? message : "");
                subBar.setProgress(-1);
            });
        }
    }
}
