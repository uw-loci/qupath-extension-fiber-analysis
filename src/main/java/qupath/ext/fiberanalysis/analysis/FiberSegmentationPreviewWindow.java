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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
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
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Live segmentation preview window. Reads a small region (128/256/512 px)
 * around the viewer center or selected annotation, runs the same
 * {@code segment_internal} the real workflow uses via a lightweight Appose
 * task, and displays the magenta fiber-mask overlay layered over the source
 * region. Recomputes (debounced) whenever any of the parent dialog's Section
 * 2 controls change, so users can tune sigma / threshold / invert /
 * rolling-ball quickly without waiting for a full per-annotation run.
 *
 * <p>The window also drops a transient rectangle annotation onto the QuPath
 * viewer marking the exact region being previewed (PathClass
 * "Fiber-Preview-Region", locked, set as the viewer's selection). The marker
 * tracks the preview as the user pans / resizes the region and is removed
 * when the window closes -- so the user always knows which patch of the slide
 * the preview is reading from.
 */
final class FiberSegmentationPreviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(FiberSegmentationPreviewWindow.class);

    /** Debounce delay between a spinner change and the next preview redraw. */
    private static final long DEBOUNCE_MS = 350;

    /** Class assigned to the transient region-marker annotation. */
    private static final String MARKER_CLASS_NAME = "Fiber-Preview-Region";

    private FiberSegmentationPreviewWindow() {}

    /**
     * Opens the preview window. The caller passes the live Section 2 controls
     * so the preview tracks them as the user adjusts the dialog. Pass null
     * for any control that's not yet wired -- the preview will skip the
     * change-listener for that one.
     */
    static void show(
            QuPathGUI gui,
            ComboBox<String> channelCombo,
            ComboBox<String> thresholdMethodCombo,
            Spinner<Integer> manualThresholdSpinner,
            ComboBox<String> ridgeFilterCombo,
            Spinner<Double> sigmaMinSpinner,
            Spinner<Double> sigmaMaxSpinner,
            Spinner<Double> sigmaStepSpinner,
            Spinner<Double> minFiberAreaSpinner,
            CheckBox invertIntensityCheck,
            Spinner<Double> rollingBallSpinner,
            ComboBox<String> calibrationCombo,
            Spinner<Double> borderZoneSpinner) {

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
        sizeChoice.setTooltip(new Tooltip("Pixel side-length of the region to preview. Centred on the\n"
                + "viewer (or the selected annotation's centroid)."));

        Slider opacitySlider = new Slider(0.0, 1.0, 0.65);
        opacitySlider.setPrefWidth(140);
        opacitySlider.valueProperty().addListener((o, a, b) -> overlayView.setOpacity(b.doubleValue()));
        opacitySlider.setTooltip(new Tooltip("Opacity of the magenta fiber-mask overlay on top of the\n"
                + "source region. Does NOT re-run segmentation."));

        CheckBox autoCheck = new CheckBox("Auto-refresh");
        autoCheck.setSelected(true);
        autoCheck.setTooltip(new Tooltip("When ON, the preview re-runs (after a short debounce) every time\n"
                + "you change a segmentation knob in the main dialog -- threshold,\n"
                + "ridge filter, sigma range, invert, rolling-ball, calibration,\n"
                + "channel, border-zone -- or the Region size above.\n\n"
                + "Pan / zoom in the QuPath viewer does NOT auto-trigger; click\n"
                + "Refresh after moving to a new location.\n\n"
                + "When OFF, every preview update is on-demand via the Refresh\n"
                + "button. Useful while tweaking spinners quickly without paying\n"
                + "the Python round-trip on every keystroke."));

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setTooltip(new Tooltip("Re-read the current region from the viewer and re-run segmentation\n"
                + "with the current dialog settings. Use this after panning /\n"
                + "zooming the QuPath viewer (those don't auto-refresh) or when\n"
                + "Auto-refresh is OFF."));
        refreshBtn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        autoCheck.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Top summary line -- which patch of the slide are we previewing and
        // with which segmentation knobs. Always visible, even before pixels
        // load, so the user knows what the preview should reflect.
        Label regionLabel = new Label("Region: --");
        regionLabel.setStyle("-fx-font-weight: bold;");
        regionLabel.setWrapText(true);
        regionLabel.setMaxWidth(Double.MAX_VALUE);

        Label paramsLabel = new Label("Parameters: --");
        paramsLabel.setWrapText(true);
        paramsLabel.setMaxWidth(Double.MAX_VALUE);

        // Status (results / errors) -- inherit theme text colour rather than
        // hard-coding a grey that disappears against QuPath's dark theme.
        Label statusLabel = new Label("Click Refresh to start.");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        VBox infoBox = new VBox(3, regionLabel, paramsLabel, statusLabel);
        infoBox.setMaxWidth(Double.MAX_VALUE);

        // Two control rows so the 560-wide stage doesn't clip Auto-refresh /
        // Refresh on the right. Row 1: region + opacity (display knobs).
        // Row 2: refresh controls (run knobs).
        HBox displayRow = new HBox(8, new Label("Region size (px):"), sizeChoice, new Label("Opacity:"), opacitySlider);
        displayRow.setAlignment(Pos.CENTER_LEFT);

        HBox refreshRow = new HBox(8, autoCheck, refreshBtn);
        refreshRow.setAlignment(Pos.CENTER_LEFT);

        VBox controls = new VBox(4, displayRow, refreshRow);

        VBox root = new VBox(8, imageStack, controls, infoBox);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.TOP_CENTER);

        // Shared state for the worker.
        final Path[] tempDir = {null};
        try {
            tempDir[0] = Files.createTempDirectory("fiber-preview-");
            tempDir[0].toFile().deleteOnExit();
        } catch (IOException e) {
            Dialogs.showErrorMessage("Fiber preview", "Could not create temp dir: " + e.getMessage());
            return;
        }
        final AtomicLong lastRequest = new AtomicLong(0);
        final AtomicLong inflightThreadId = new AtomicLong(0);
        // Holds the transient region-marker annotation so we can update or
        // remove it across previews + window close.
        final AtomicReference<PathObject> regionMarker = new AtomicReference<>(null);

        Runnable schedule = () -> {
            long id = lastRequest.incrementAndGet();
            Thread t = new Thread(
                    () -> {
                        try {
                            Thread.sleep(DEBOUNCE_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        // If a newer request came in during the debounce, skip this one.
                        if (lastRequest.get() != id) return;
                        runOnce(
                                id,
                                gui,
                                tempDir[0],
                                sizeChoice.getValue(),
                                channelCombo,
                                thresholdMethodCombo,
                                manualThresholdSpinner,
                                ridgeFilterCombo,
                                sigmaMinSpinner,
                                sigmaMaxSpinner,
                                sigmaStepSpinner,
                                minFiberAreaSpinner,
                                invertIntensityCheck,
                                rollingBallSpinner,
                                calibrationCombo,
                                borderZoneSpinner,
                                baseView,
                                overlayView,
                                regionLabel,
                                paramsLabel,
                                statusLabel,
                                lastRequest,
                                inflightThreadId,
                                regionMarker);
                    },
                    "FiberPreview-" + id);
            t.setDaemon(true);
            t.start();
        };

        refreshBtn.setOnAction(e -> schedule.run());

        // Auto-refresh hookups -- wire change listeners on each non-null control.
        ChangeListener<Object> autoListener = (o, oldV, newV) -> {
            if (autoCheck.isSelected()) schedule.run();
        };
        sizeChoice.valueProperty().addListener(autoListener);
        if (channelCombo != null) channelCombo.valueProperty().addListener(autoListener);
        if (thresholdMethodCombo != null) thresholdMethodCombo.valueProperty().addListener(autoListener);
        if (manualThresholdSpinner != null)
            manualThresholdSpinner.valueProperty().addListener(autoListener);
        if (ridgeFilterCombo != null) ridgeFilterCombo.valueProperty().addListener(autoListener);
        if (sigmaMinSpinner != null) sigmaMinSpinner.valueProperty().addListener(autoListener);
        if (sigmaMaxSpinner != null) sigmaMaxSpinner.valueProperty().addListener(autoListener);
        if (sigmaStepSpinner != null) sigmaStepSpinner.valueProperty().addListener(autoListener);
        if (minFiberAreaSpinner != null) minFiberAreaSpinner.valueProperty().addListener(autoListener);
        if (invertIntensityCheck != null)
            invertIntensityCheck.selectedProperty().addListener(autoListener);
        if (rollingBallSpinner != null) rollingBallSpinner.valueProperty().addListener(autoListener);
        if (calibrationCombo != null) calibrationCombo.valueProperty().addListener(autoListener);
        if (borderZoneSpinner != null) borderZoneSpinner.valueProperty().addListener(autoListener);

        // Wider stage so the long region/params/status lines don't get clipped.
        stage.setScene(new Scene(root, 560, 640));
        stage.setOnHidden(e -> {
            // Remove the region marker from the QuPath hierarchy.
            removeRegionMarker(gui, regionMarker);
            // Best-effort temp cleanup
            try {
                if (tempDir[0] != null && Files.isDirectory(tempDir[0])) {
                    Files.walk(tempDir[0])
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            } catch (IOException ignored) {
            }
        });
        stage.show();

        // Trigger the first preview after window shows.
        schedule.run();
    }

    @SuppressWarnings("unchecked")
    private static void runOnce(
            long requestId,
            QuPathGUI gui,
            Path tempDir,
            int regionSize,
            ComboBox<String> channelCombo,
            ComboBox<String> thresholdMethodCombo,
            Spinner<Integer> manualThresholdSpinner,
            ComboBox<String> ridgeFilterCombo,
            Spinner<Double> sigmaMinSpinner,
            Spinner<Double> sigmaMaxSpinner,
            Spinner<Double> sigmaStepSpinner,
            Spinner<Double> minFiberAreaSpinner,
            CheckBox invertIntensityCheck,
            Spinner<Double> rollingBallSpinner,
            ComboBox<String> calibrationCombo,
            Spinner<Double> borderZoneSpinner,
            ImageView baseView,
            ImageView overlayView,
            Label regionLabel,
            Label paramsLabel,
            Label statusLabel,
            AtomicLong lastRequest,
            AtomicLong inflightThreadId,
            AtomicReference<PathObject> regionMarker) {

        inflightThreadId.set(requestId);
        long t0 = System.currentTimeMillis();
        try {
            QuPathViewer viewer = gui.getViewer();
            if (viewer == null || gui.getImageData() == null) {
                setStatus(statusLabel, "No image / viewer.");
                return;
            }
            ImageData<BufferedImage> data = (ImageData<BufferedImage>) gui.getImageData();
            ImageServer<BufferedImage> server = data.getServer();

            double cx, cy;
            PathObject selected = viewer.getSelectedObject();
            // Treat our own region marker as "no user selection" so the
            // preview keeps centering on the viewer instead of locking onto
            // the rectangle we just dropped.
            boolean haveUserSelection = selected != null && selected.getROI() != null && !isPreviewMarker(selected);
            if (haveUserSelection) {
                cx = selected.getROI().getCentroidX();
                cy = selected.getROI().getCentroidY();
            } else {
                cx = viewer.getCenterPixelX();
                cy = viewer.getCenterPixelY();
            }
            int x = (int) Math.max(0, Math.min(server.getWidth() - regionSize, cx - regionSize / 2.0));
            int y = (int) Math.max(0, Math.min(server.getHeight() - regionSize, cy - regionSize / 2.0));
            int w = Math.min(regionSize, server.getWidth() - x);
            int h = Math.min(regionSize, server.getHeight() - y);

            // Pixel size for um <-> px conversion (mirror buildScriptInputs logic).
            double pxUm = 0.5;
            boolean haveCalibration = false;
            try {
                PixelCalibration cal = server.getPixelCalibration();
                if (cal != null && cal.hasPixelSizeMicrons()) {
                    pxUm = cal.getAveragedPixelSizeMicrons();
                    haveCalibration = true;
                }
            } catch (Exception ignored) {
            }

            // Update the top region/params labels + drop the marker onto the
            // image right away so the user sees WHICH patch we're reading
            // even before Python returns.
            final int xF = x;
            final int yF = y;
            final int wF = w;
            final int hF = h;
            final double pxUmF = pxUm;
            final boolean haveCalF = haveCalibration;
            final String chanLabel = channelCombo == null ? "Raw intensity" : String.valueOf(channelCombo.getValue());
            final String thrMethod =
                    thresholdMethodCombo == null ? "Otsu" : String.valueOf(thresholdMethodCombo.getValue());
            final String ridgeLabel = ridgeFilterCombo == null ? "none" : String.valueOf(ridgeFilterCombo.getValue());
            final double sMin = sigmaMinSpinner == null ? 1.0 : sigmaMinSpinner.getValue();
            final double sMax = sigmaMaxSpinner == null ? 4.0 : sigmaMaxSpinner.getValue();
            final double sStep = sigmaStepSpinner == null ? 1.0 : sigmaStepSpinner.getValue();
            final boolean invert = invertIntensityCheck != null && invertIntensityCheck.isSelected();
            final double rbUm = rollingBallSpinner == null ? 0.0 : rollingBallSpinner.getValue();
            Platform.runLater(() -> {
                if (lastRequest.get() != requestId) return;
                String umPart = haveCalF
                        ? String.format(Locale.ROOT, " (%.1f x %.1f um)", wF * pxUmF, hF * pxUmF)
                        : " (uncalibrated)";
                regionLabel.setText(
                        String.format(Locale.ROOT, "Region: %d x %d px at (%d, %d)%s", wF, hF, xF, yF, umPart));
                paramsLabel.setText(String.format(
                        Locale.ROOT,
                        "Channel: %s | Threshold: %s | Ridge: %s | Sigma: %.1f-%.1f step %.1f um%s%s",
                        chanLabel,
                        thrMethod,
                        ridgeLabel,
                        sMin,
                        sMax,
                        sStep,
                        invert ? " | inverted" : "",
                        rbUm > 0 ? String.format(Locale.ROOT, " | rolling-ball %.1f um", rbUm) : ""));
                updateRegionMarker(gui, regionMarker, xF, yF, wF, hF);
            });

            RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, x, y, w, h);
            BufferedImage img = server.readRegion(req);
            if (img == null) {
                setStatus(statusLabel, "readRegion returned null.");
                return;
            }
            Path regionPng = tempDir.resolve("region.png");
            ImageIO.write(img, "PNG", regionPng.toFile());
            Path overlayPng = tempDir.resolve("overlay.png");

            // If a newer request came in while we were reading, drop this one.
            if (lastRequest.get() != requestId) return;

            Map<String, Object> in = new LinkedHashMap<>();
            in.put("region_image_path", regionPng.toAbsolutePath().toString());
            in.put("output_overlay_path", overlayPng.toAbsolutePath().toString());
            in.put("seg_channel", chanLabel);
            // Translate the dialog label into the snake_case the Python segmenter expects.
            String thrCode = "Project Otsu (calibrated)".equals(thrMethod) ? "project_otsu" : thrMethod.toLowerCase();
            in.put("threshold_method", thrCode);
            in.put("manual_threshold", manualThresholdSpinner == null ? 128 : manualThresholdSpinner.getValue());
            in.put("ridge_filter", ridgeLabel.toLowerCase());
            in.put("sigma_min", sMin / pxUm);
            in.put("sigma_max", sMax / pxUm);
            in.put("sigma_step", sStep / pxUm);
            double minAreaUm2 = minFiberAreaSpinner == null ? 1.0 : minFiberAreaSpinner.getValue();
            in.put("min_fiber_area_px", (int) Math.max(0, Math.round(minAreaUm2 / (pxUm * pxUm))));
            in.put("invert_intensity", invert);
            in.put("rolling_ball_radius", (int) Math.max(0, Math.round(rbUm / pxUm)));
            // Calibrated threshold lookup if needed.
            if ("project_otsu".equals(thrCode) && calibrationCombo != null && calibrationCombo.getValue() != null) {
                Double thr = FiberAnalysisWorkflow.loadCalibratedThreshold(calibrationCombo.getValue());
                if (thr != null) in.put("project_threshold_norm", thr);
            }

            // Lazy-init the Appose service if the main workflow hasn't been
            // run yet this session. First-time setup builds a pixi env and
            // can take several minutes -- pipe status into the preview's
            // status line so the user knows what's happening. `initialize`
            // is synchronized + idempotent, so concurrent preview requests
            // queue cleanly and the second one returns immediately.
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

            // Display the original region + the magenta overlay PNG produced by Python.
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
            Platform.runLater(() -> {
                if (lastRequest.get() != requestId) return;
                baseView.setImage(fxBase);
                overlayView.setImage(fxOverlay);
                statusLabel.setText(summary);
            });
        } catch (Exception ex) {
            logger.warn("Preview redraw failed: {}", ex.getMessage(), ex);
            setStatus(statusLabel, "Preview failed: " + ex.getMessage());
        }
    }

    private static void setStatus(Label label, String text) {
        Platform.runLater(() -> label.setText(text));
    }

    /**
     * Add (or move) the transient rectangle annotation that marks the patch
     * being previewed. Locked + classed so the user can tell it's not a real
     * annotation, and set as the viewer's selected object so they get the
     * marching-ants visualisation. Must be called on the JavaFX thread.
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
        QuPathViewer viewer = gui.getViewer();
        if (viewer != null) {
            viewer.setSelectedObject(marker);
        }
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

    private static boolean isPreviewMarker(PathObject obj) {
        if (obj == null) return false;
        PathClass cls = obj.getPathClass();
        return cls != null && MARKER_CLASS_NAME.equals(cls.getName());
    }
}
