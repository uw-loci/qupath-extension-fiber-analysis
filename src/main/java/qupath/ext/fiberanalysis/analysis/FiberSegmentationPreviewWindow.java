/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Pattern lifted from qupath-extension-ppm's PPMMaskPreviewWindow (Apache-2.0,
 * same lab); behaviour is closely analogous but the worker is Appose-backed
 * because fiber segmentation requires the scikit-image ridge filters.
 */
package qupath.ext.fiberanalysis.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Live segmentation preview window. Reads a small region around the viewer
 * centre or selected annotation at the run's analysis downsample, runs the
 * same {@code segment_internal} the run uses via a lightweight Appose task,
 * and displays the magenta fiber-mask overlay over the source region.
 * Recomputes (debounced) whenever a setting in the parent dialog changes.
 *
 * <p>Otsu and Triangle are what a preview can get wrong: fitted on the
 * preview square they give a different cut from the run, which fits one value
 * over the analysis zones of all its annotations. By default the preview cuts
 * at that run-wide value; unticked, it fits on the square and says the run
 * will differ. Either way the window states the cut it used.
 *
 * <p>The window also drops a transient rectangle annotation (PathClass
 * "Fiber-Preview-Region", locked) marking the region being previewed. It is
 * removed when the window closes.
 */
final class FiberSegmentationPreviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(FiberSegmentationPreviewWindow.class);

    /** Debounce delay between a setting change and the next preview redraw. */
    private static final long DEBOUNCE_MS = 350;

    /** Class assigned to the transient region-marker annotation. */
    private static final String MARKER_CLASS_NAME = "Fiber-Preview-Region";

    /** Style of the threshold line when the preview's cut is not the run's. */
    private static final String DIFFERS_STYLE = "-fx-text-fill: #e07a10; -fx-font-weight: bold;";

    /**
     * What a run with the dialog's current settings would do.
     *
     * @param params      the run's parameters
     * @param annotations the annotations it would analyse
     */
    record Context(FiberAnalysisParams params, List<PathObject> annotations) {}

    /** A run-wide threshold already fitted, and the settings it was fitted for. */
    private record Pooled(String key, PooledThreshold.Result result, int annotations) {}

    private FiberSegmentationPreviewWindow() {}

    /**
     * Opens the preview window.
     *
     * @param gui      the QuPath window
     * @param context  the run the dialog describes; called on the FX thread
     * @param triggers settings whose change re-runs the preview
     */
    static void show(QuPathGUI gui, Supplier<Context> context, List<? extends Observable> triggers) {

        if (gui == null || gui.getImageData() == null) {
            Dialogs.showWarningNotification("Fiber segmentation preview", "No image is open.");
            return;
        }

        Stage stage = new Stage();
        stage.initOwner(gui.getStage());
        stage.setTitle("Fiber segmentation preview");

        ImageView baseView = new ImageView();
        baseView.setPreserveRatio(true);
        baseView.setSmooth(false);
        baseView.setFitWidth(480);
        baseView.setFitHeight(480);

        ImageView overlayView = new ImageView();
        overlayView.setPreserveRatio(true);
        overlayView.setSmooth(false);
        overlayView.setFitWidth(480);
        overlayView.setFitHeight(480);
        overlayView.setOpacity(0.65);

        StackPane imageStack = new StackPane(baseView, overlayView);
        imageStack.setStyle("-fx-background-color: #1f1f1f;");
        imageStack.setPrefSize(480, 480);

        ChoiceBox<Integer> sizeChoice = new ChoiceBox<>();
        sizeChoice.getItems().addAll(128, 256, 512);
        sizeChoice.setValue(256);
        sizeChoice.setTooltip(new Tooltip("Side of the preview region in analysed pixels (at the run's analysis\n"
                + "downsample). Centred on the viewer (or the selected annotation's centroid)."));

        Slider opacitySlider = new Slider(0.0, 1.0, 0.65);
        opacitySlider.setPrefWidth(140);
        opacitySlider.valueProperty().addListener((o, a, b) -> overlayView.setOpacity(b.doubleValue()));
        opacitySlider.setTooltip(new Tooltip("Opacity of the magenta fiber-mask overlay on top of the\n"
                + "source region. Does NOT re-run segmentation."));

        CheckBox autoCheck = new CheckBox("Auto-refresh");
        autoCheck.setSelected(true);
        autoCheck.setTooltip(
                new Tooltip(
                        "Re-run the preview whenever a setting in the Run dialog or Region size changes. Panning or zooming does not trigger it; click Refresh after moving."));

        CheckBox runCutCheck = new CheckBox("Cut at the run's threshold");
        runCutCheck.setSelected(true);
        runCutCheck.setTooltip(new Tooltip(
                "Otsu and Triangle: fit the threshold the way the run does, once over the analysis zones of\n"
                        + "every annotation the run would analyse (Search area), and cut the preview there.\n"
                        + "Unticked, the threshold is fitted on this preview square alone: quicker, but the\n"
                        + "run will cut at a different value."));

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setTooltip(
                new Tooltip(
                        "Re-read the region from the viewer and segment it again. Needed after panning or zooming, which do not refresh on their own."));
        refreshBtn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        autoCheck.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        runCutCheck.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        Label regionLabel = new Label("Region: --");
        regionLabel.setStyle("-fx-font-weight: bold;");
        regionLabel.setWrapText(true);
        regionLabel.setMaxWidth(Double.MAX_VALUE);

        Label paramsLabel = new Label("Parameters: --");
        paramsLabel.setWrapText(true);
        paramsLabel.setMaxWidth(Double.MAX_VALUE);

        // Which cut the preview used, and whether the run will use the same.
        Label thresholdLabel = new Label("Threshold: --");
        thresholdLabel.setWrapText(true);
        thresholdLabel.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label("Click Refresh to start.");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // Wrapped labels in a fixed-height window are otherwise cut to one line with "...".
        for (Label l : List.of(regionLabel, paramsLabel, thresholdLabel, statusLabel))
            l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox infoBox = new VBox(3, regionLabel, paramsLabel, thresholdLabel, statusLabel);
        infoBox.setMaxWidth(Double.MAX_VALUE);

        HBox displayRow = new HBox(8, new Label("Region size (px):"), sizeChoice, new Label("Opacity:"), opacitySlider);
        displayRow.setAlignment(Pos.CENTER_LEFT);

        HBox refreshRow = new HBox(8, autoCheck, refreshBtn, runCutCheck);
        refreshRow.setAlignment(Pos.CENTER_LEFT);

        VBox controls = new VBox(4, displayRow, refreshRow);

        VBox root = new VBox(8, imageStack, controls, infoBox);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.TOP_CENTER);

        final Path[] tempDir = {null};
        try {
            tempDir[0] = Files.createTempDirectory("fiber-preview-");
            tempDir[0].toFile().deleteOnExit();
        } catch (IOException e) {
            Dialogs.showErrorMessage("Fiber preview", "Could not create temp dir: " + e.getMessage());
            return;
        }
        final AtomicLong lastRequest = new AtomicLong(0);
        final AtomicReference<PathObject> regionMarker = new AtomicReference<>(null);
        final AtomicReference<Pooled> pooled = new AtomicReference<>(null);

        Runnable schedule = () -> {
            long id = lastRequest.incrementAndGet();
            int size = sizeChoice.getValue();
            boolean runCut = runCutCheck.isSelected();
            Thread t = new Thread(
                    () -> {
                        try {
                            Thread.sleep(DEBOUNCE_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (lastRequest.get() != id) return;
                        runOnce(
                                id,
                                gui,
                                tempDir[0],
                                size,
                                context,
                                runCut,
                                pooled,
                                baseView,
                                overlayView,
                                regionLabel,
                                paramsLabel,
                                thresholdLabel,
                                runCutCheck,
                                statusLabel,
                                lastRequest,
                                regionMarker);
                    },
                    "FiberPreview-" + id);
            t.setDaemon(true);
            t.start();
        };

        refreshBtn.setOnAction(e -> schedule.run());
        runCutCheck.selectedProperty().addListener((o, a, b) -> schedule.run());

        javafx.beans.InvalidationListener autoListener = o -> {
            if (autoCheck.isSelected()) schedule.run();
        };
        sizeChoice.valueProperty().addListener(autoListener);
        for (Observable trigger : triggers) trigger.addListener(autoListener);

        stage.setScene(new Scene(root, 560, 760));
        stage.setOnHidden(e -> {
            for (Observable trigger : triggers) trigger.removeListener(autoListener);
            // A refresh still in flight would put the marker back.
            lastRequest.incrementAndGet();
            removeRegionMarker(gui, regionMarker);
            try {
                if (tempDir[0] != null && Files.isDirectory(tempDir[0])) {
                    try (var walk = Files.walk(tempDir[0])) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
                    }
                }
            } catch (IOException ignored) {
            }
        });
        stage.show();

        schedule.run();
    }

    @SuppressWarnings("unchecked")
    private static void runOnce(
            long requestId,
            QuPathGUI gui,
            Path tempDir,
            int regionSize,
            Supplier<Context> contextSupplier,
            boolean runCut,
            AtomicReference<Pooled> pooledCache,
            ImageView baseView,
            ImageView overlayView,
            Label regionLabel,
            Label paramsLabel,
            Label thresholdLabel,
            CheckBox runCutCheck,
            Label statusLabel,
            AtomicLong lastRequest,
            AtomicReference<PathObject> regionMarker) {

        long t0 = System.currentTimeMillis();
        try {
            QuPathViewer viewer = gui.getViewer();
            if (viewer == null || gui.getImageData() == null) {
                setStatus(statusLabel, "No image / viewer.");
                return;
            }
            ImageData<BufferedImage> data = (ImageData<BufferedImage>) gui.getImageData();
            ImageServer<BufferedImage> server = data.getServer();
            Context ctx = onFx(contextSupplier::get);
            FiberAnalysisParams p = ctx.params();
            List<PathObject> annotations =
                    ctx.annotations().stream().filter(a -> !isPreviewMarker(a)).toList();

            // Read the way the run reads: at its analysis downsample, converting
            // microns with its pixel size.
            double ds = Math.max(1.0, p.analysisDownsample());
            double pxUm = FiberAnalysisWorkflow.resolvePixelSizeUm(p, data);
            double readPxUm = pxUm * ds;
            int span = (int) Math.round(regionSize * ds);

            double cx, cy;
            PathObject selected = viewer.getSelectedObject();
            boolean haveUserSelection = selected != null && selected.getROI() != null && !isPreviewMarker(selected);
            if (haveUserSelection) {
                cx = selected.getROI().getCentroidX();
                cy = selected.getROI().getCentroidY();
            } else {
                cx = viewer.getCenterPixelX();
                cy = viewer.getCenterPixelY();
            }
            int x = (int) Math.max(0, Math.min(server.getWidth() - span, cx - span / 2.0));
            int y = (int) Math.max(0, Math.min(server.getHeight() - span, cy - span / 2.0));
            int w = Math.min(span, server.getWidth() - x);
            int h = Math.min(span, server.getHeight() - y);

            boolean pools = FiberAnalysisWorkflow.poolsThreshold(p);
            Platform.runLater(() -> {
                if (lastRequest.get() != requestId) return;
                runCutCheck.setVisible(pools);
                runCutCheck.setManaged(pools);
                regionLabel.setText(String.format(
                        Locale.ROOT,
                        "Region: %d x %d px at (%d, %d), downsample %s (%.1f x %.1f um)",
                        w,
                        h,
                        x,
                        y,
                        ds == Math.rint(ds) ? Long.toString((long) ds) : Double.toString(ds),
                        w * pxUm,
                        h * pxUm));
                paramsLabel.setText(String.format(
                        Locale.ROOT,
                        "Channel: %s | Threshold: %s | Ridge: %s | Sigma: %.1f-%.1f step %.1f um%s%s",
                        p.internalChannel(),
                        p.thresholdMethod(),
                        p.ridgeFilter(),
                        p.sigmaMinUm(),
                        p.sigmaMaxUm(),
                        p.sigmaStepUm(),
                        p.invertIntensity() ? " | inverted" : "",
                        p.rollingBallRadiusUm() > 0
                                ? String.format(Locale.ROOT, " | rolling-ball %.1f um", p.rollingBallRadiusUm())
                                : ""));
                updateRegionMarker(gui, regionMarker, x, y, w, h);
            });

            ApposeFiberService apposeSvc = ApposeFiberService.getInstance();
            if (!apposeSvc.isAvailable()) {
                setStatus(statusLabel, "First-time setup: building Python environment (may take several minutes)...");
                try {
                    apposeSvc.initialize(msg -> setStatus(statusLabel, "Setup: " + msg));
                } catch (IOException initEx) {
                    setStatus(statusLabel, "Appose setup failed: " + initEx.getMessage());
                    return;
                }
                if (lastRequest.get() != requestId) return;
            }

            // The cut. Manual and a calibration are fixed values the run uses
            // too; Otsu / Triangle are fitted, so say on what.
            Map<String, Object> in = new LinkedHashMap<>();
            String thrCode = "Project Otsu (calibrated)".equals(p.thresholdMethod())
                    ? "project_otsu"
                    : p.thresholdMethod().toLowerCase(Locale.ROOT);
            String fitNote; // completes "Cut: <value>, ..."
            boolean matchesRun = true;
            if ("project_otsu".equals(thrCode)) {
                Double thr = FiberAnalysisWorkflow.loadCalibratedThreshold(p.projectCalibrationName());
                if (thr == null) {
                    setStatus(statusLabel, "Calibration '" + p.projectCalibrationName() + "' could not be read.");
                    return;
                }
                in.put("project_threshold_norm", thr);
                fitNote = "from calibration '" + p.projectCalibrationName() + "'. The run cuts at the same value.";
            } else if (!pools) {
                fitNote = "set by hand. The run cuts at the same value.";
            } else if (runCut && !annotations.isEmpty()) {
                Pooled run = runThreshold(p, data, annotations, tempDir, pooledCache, statusLabel);
                if (lastRequest.get() != requestId) return;
                thrCode = "project_otsu";
                in.put("project_threshold_norm", run.result().thresholdNorm());
                fitNote = String.format(
                        Locale.ROOT,
                        "%s fitted over the analysis zones of %d annotation(s), as the run does. The run cuts"
                                + " at the same value.",
                        "triangle".equals(run.result().method()) ? "Triangle" : "Otsu",
                        run.annotations());
            } else {
                matchesRun = false;
                fitNote = p.thresholdMethod() + " fitted on this preview square only. The run fits one threshold"
                        + " over the analysis zones of all its annotations, so it will cut at a different value."
                        + (runCut ? " (No annotations match the Search area, so there is nothing to fit it on.)" : "");
            }

            RegionRequest req = RegionRequest.createInstance(server.getPath(), ds, x, y, w, h);
            Path regionPng = tempDir.resolve("region.png");
            SourceChannel.writeRegionPng(server, req, p.internalChannel(), regionPng);
            Path overlayPng = tempDir.resolve("overlay.png");
            if (lastRequest.get() != requestId) return;

            in.put("region_image_path", regionPng.toAbsolutePath().toString());
            in.put("output_overlay_path", overlayPng.toAbsolutePath().toString());
            in.put("seg_channel", p.internalChannel());
            in.put("threshold_method", thrCode);
            in.put("manual_threshold", p.manualThreshold());
            in.put("manual_response_threshold", p.manualResponseThreshold());
            in.put("ridge_filter", p.ridgeFilter().toLowerCase(Locale.ROOT));
            in.put("sigma_min", p.sigmaMinUm() / readPxUm);
            in.put("sigma_max", p.sigmaMaxUm() / readPxUm);
            in.put("sigma_step", p.sigmaStepUm() / readPxUm);
            in.put("min_fiber_area_px", (int) Math.max(0, Math.round(p.minFiberAreaUm2() / (readPxUm * readPxUm))));
            in.put("invert_intensity", p.invertIntensity());
            in.put("rolling_ball_radius", (int) Math.max(0, Math.round(p.rollingBallRadiusUm() / readPxUm)));

            Task task = apposeSvc.runTask("preview_segmentation", in);
            if (lastRequest.get() != requestId) return;

            Object out = task.outputs.get("result_json");
            JsonObject result = out == null ? null : new Gson().fromJson(String.valueOf(out), JsonObject.class);
            if (result != null && result.has("error")) {
                setStatus(statusLabel, "Python error: " + result.get("error").getAsString());
                return;
            }
            if (result == null) {
                // No payload means the task did not produce a segmentation. The
                // overlay PNG has a fixed path, so falling through here would
                // re-display the PREVIOUS preview's mask beside the NEW
                // parameters -- a wrong answer that looks like a right one.
                setStatus(statusLabel, "Preview failed: Python returned no result payload");
                return;
            }

            BufferedImage baseImg = ImageIO.read(regionPng.toFile());
            BufferedImage overlayImg = ImageIO.read(overlayPng.toFile());
            Image fxBase = SwingFXUtils.toFXImage(baseImg, null);
            Image fxOverlay = SwingFXUtils.toFXImage(overlayImg, null);
            final long elapsed = System.currentTimeMillis() - t0;
            final String summary = String.format(
                    Locale.ROOT,
                    "Fiber: %d / %d px (%.1f%%) | Python %.0f ms | total %d ms",
                    result.get("fiber_pixels").getAsInt(),
                    result.get("total_pixels").getAsInt(),
                    result.get("coverage_percent").getAsDouble(),
                    result.get("ms_segment").getAsDouble(),
                    elapsed);
            final String cutText = "Cut: "
                    + cutValue(
                            result.get("threshold").getAsDouble(),
                            result.get("full_scale").getAsDouble(),
                            !"none".equalsIgnoreCase(p.ridgeFilter()))
                    + ", " + fitNote
                    + peakNote(
                            result.get("threshold").getAsDouble(),
                            result.get("peak").getAsDouble(),
                            !"none".equalsIgnoreCase(p.ridgeFilter()));
            final boolean matchesF = matchesRun;
            Platform.runLater(() -> {
                if (lastRequest.get() != requestId) return;
                baseView.setImage(fxBase);
                overlayView.setImage(fxOverlay);
                thresholdLabel.setText(cutText);
                thresholdLabel.setStyle(matchesF ? "" : DIFFERS_STYLE);
                statusLabel.setText(summary);
            });
        } catch (Exception ex) {
            logger.warn("Preview redraw failed: {}", ex.getMessage(), ex);
            setStatus(statusLabel, "Preview failed: " + ex.getMessage());
        }
    }

    /**
     * The run-wide threshold for these settings and annotations, fitted once and reused until a
     * setting that moves it changes.
     */
    private static Pooled runThreshold(
            FiberAnalysisParams p,
            ImageData<BufferedImage> data,
            List<PathObject> annotations,
            Path tempDir,
            AtomicReference<Pooled> cache,
            Label statusLabel)
            throws IOException {
        String key = poolKey(p, data, annotations);
        Pooled cached = cache.get();
        if (cached != null && cached.key().equals(key)) return cached;
        setStatus(statusLabel, "Fitting the run's threshold over " + annotations.size() + " annotation(s)...");
        Path work = Files.createTempDirectory(tempDir, "pool-");
        PooledThreshold.Result r = FiberAnalysisWorkflow.poolForRun(
                p, FiberAnalysisWorkflow.zoneRegions(p, data, annotations), work, msg -> setStatus(statusLabel, msg));
        Pooled fresh = new Pooled(key, r, annotations.size());
        cache.set(fresh);
        return fresh;
    }

    /** @return every input the run-wide threshold depends on */
    static String poolKey(FiberAnalysisParams p, ImageData<BufferedImage> data, List<PathObject> annotations) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.getServerPath())
                .append('|')
                .append(FiberAnalysisWorkflow.resolvePixelSizeUm(p, data))
                .append('|')
                .append(p.thresholdMethod())
                .append('|')
                .append(p.internalChannel())
                .append('|')
                .append(p.ridgeFilter())
                .append('|')
                .append(p.sigmaMinUm())
                .append(',')
                .append(p.sigmaMaxUm())
                .append(',')
                .append(p.sigmaStepUm())
                .append('|')
                .append(p.invertIntensity())
                .append('|')
                .append(p.rollingBallRadiusUm())
                .append('|')
                .append(p.zoneMode())
                .append('|')
                .append(p.borderZoneUm())
                .append('|')
                .append(p.analysisDownsample());
        for (PathObject a : annotations) {
            ROI r = a.getROI();
            sb.append('|').append(a.getID());
            if (r != null)
                sb.append('@')
                        .append(r.getBoundsX())
                        .append(',')
                        .append(r.getBoundsY())
                        .append(',')
                        .append(r.getBoundsWidth())
                        .append(',')
                        .append(r.getBoundsHeight())
                        .append(',')
                        .append(r.getArea());
        }
        return sb.toString();
    }

    /** @return the cut as a ridge response, or as a fraction and the gray level it is */
    static String cutValue(double cut, double fullScale, boolean ridge) {
        if (ridge) return String.format(Locale.ROOT, "response %.4g", cut);
        if (fullScale > 1.0) {
            return String.format(Locale.ROOT, "%.4g (gray level %.0f of %.0f)", cut, cut * fullScale, fullScale);
        }
        return String.format(Locale.ROOT, "%.4g", cut);
    }

    /** @return the strongest ridge response in view, and a warning when the cut is above it */
    static String peakNote(double cut, double peak, boolean ridge) {
        if (!ridge) return "";
        String note = String.format(Locale.ROOT, "; strongest response here %.4g", peak);
        return cut > peak ? note + ", so this cut finds no fibers" : note;
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) return work.call();
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get();
    }

    private static void setStatus(Label label, String text) {
        Platform.runLater(() -> label.setText(text));
    }

    /**
     * Add (or move) the transient rectangle annotation that marks the patch
     * being previewed. Locked + classed so the user can tell it's not a real
     * annotation. It is not selected: the selection is what a run with
     * Search area "selected" analyses. Must be called on the JavaFX thread.
     */
    private static void updateRegionMarker(QuPathGUI gui, AtomicReference<PathObject> ref, int x, int y, int w, int h) {
        if (gui == null || gui.getImageData() == null) return;
        PathObjectHierarchy hierarchy = gui.getImageData().getHierarchy();
        if (hierarchy == null) return;
        PathObject existing = ref.get();
        if (existing != null) {
            hierarchy.removeObject(existing, false);
        }
        ROI rect = ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane());
        PathClass markerClass = PathClass.fromString(MARKER_CLASS_NAME, ColorTools.packRGB(0, 200, 255));
        PathObject marker = PathObjects.createAnnotationObject(rect, markerClass);
        marker.setName("[Fiber preview region]");
        marker.setLocked(true);
        hierarchy.addObject(marker);
        ref.set(marker);
    }

    /** Strip the marker on window close so it doesn't pollute the hierarchy. */
    private static void removeRegionMarker(QuPathGUI gui, AtomicReference<PathObject> ref) {
        Platform.runLater(() -> {
            PathObject marker = ref.getAndSet(null);
            if (marker == null || gui == null || gui.getImageData() == null) return;
            PathObjectHierarchy hierarchy = gui.getImageData().getHierarchy();
            if (hierarchy != null) {
                hierarchy.removeObject(marker, false);
                QuPathViewer viewer = gui.getViewer();
                if (viewer != null && marker.equals(viewer.getSelectedObject())) {
                    viewer.setSelectedObject(null);
                }
            }
        });
    }

    /** @return true for the preview's own region marker, which no run should analyse */
    static boolean isPreviewMarker(PathObject obj) {
        if (obj == null) return false;
        PathClass cls = obj.getPathClass();
        return cls != null && MARKER_CLASS_NAME.equals(cls.getName());
    }
}
