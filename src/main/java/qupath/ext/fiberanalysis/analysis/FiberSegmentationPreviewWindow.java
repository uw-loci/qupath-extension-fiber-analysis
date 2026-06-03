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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;

/**
 * Live segmentation preview window. Reads a small region (128/256/512 px)
 * around the viewer center or selected annotation, runs the same
 * {@code segment_internal} the real workflow uses via a lightweight Appose
 * task, and displays the magenta fiber-mask overlay layered over the source
 * region. Recomputes (debounced) whenever any of the parent dialog's Section
 * 2 controls change, so users can tune sigma / threshold / invert /
 * rolling-ball quickly without waiting for a full per-annotation run.
 */
final class FiberSegmentationPreviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(FiberSegmentationPreviewWindow.class);

    /** Debounce delay between a spinner change and the next preview redraw. */
    private static final long DEBOUNCE_MS = 350;

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
        baseView.setFitWidth(420);
        baseView.setFitHeight(420);

        ImageView overlayView = new ImageView();
        overlayView.setPreserveRatio(true);
        overlayView.setSmooth(false);
        overlayView.setFitWidth(420);
        overlayView.setFitHeight(420);
        overlayView.setOpacity(0.65);

        StackPane imageStack = new StackPane(baseView, overlayView);
        imageStack.setStyle("-fx-background-color: #1f1f1f;");
        imageStack.setPrefSize(420, 420);

        ChoiceBox<Integer> sizeChoice = new ChoiceBox<>();
        sizeChoice.getItems().addAll(128, 256, 512);
        sizeChoice.setValue(256);

        Slider opacitySlider = new Slider(0.0, 1.0, 0.65);
        opacitySlider.setPrefWidth(150);
        opacitySlider.valueProperty().addListener((o, a, b) -> overlayView.setOpacity(b.doubleValue()));

        CheckBox autoCheck = new CheckBox("Auto-refresh");
        autoCheck.setSelected(true);

        Button refreshBtn = new Button("Refresh");

        Label statusLabel = new Label("Click Refresh to start.");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        statusLabel.setWrapText(true);

        HBox controls = new HBox(
                8,
                new Label("Region size (px):"),
                sizeChoice,
                new Label("Opacity:"),
                opacitySlider,
                autoCheck,
                refreshBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, imageStack, controls, statusLabel);
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
                                statusLabel,
                                lastRequest,
                                inflightThreadId);
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

        stage.setScene(new Scene(root, 480, 560));
        stage.setOnHidden(e -> {
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
            Label statusLabel,
            AtomicLong lastRequest,
            AtomicLong inflightThreadId) {

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
            if (selected != null && selected.getROI() != null) {
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

            // Pixel size for um->px conversion (mirror buildScriptInputs logic).
            double pxUm = 0.5;
            try {
                PixelCalibration cal = server.getPixelCalibration();
                if (cal != null && cal.hasPixelSizeMicrons()) {
                    pxUm = cal.getAveragedPixelSizeMicrons();
                }
            } catch (Exception ignored) {
            }

            Map<String, Object> in = new LinkedHashMap<>();
            in.put("region_image_path", regionPng.toAbsolutePath().toString());
            in.put("output_overlay_path", overlayPng.toAbsolutePath().toString());
            in.put("seg_channel", channelCombo == null ? "Raw intensity" : channelCombo.getValue());
            String thrMethod = thresholdMethodCombo == null ? "Otsu" : thresholdMethodCombo.getValue();
            // Translate the dialog label into the snake_case the Python segmenter expects.
            String thrCode = "Project Otsu (calibrated)".equals(thrMethod) ? "project_otsu" : thrMethod.toLowerCase();
            in.put("threshold_method", thrCode);
            in.put("manual_threshold", manualThresholdSpinner == null ? 128 : manualThresholdSpinner.getValue());
            in.put(
                    "ridge_filter",
                    ridgeFilterCombo == null
                            ? "none"
                            : ridgeFilterCombo.getValue().toLowerCase());
            double sMin = sigmaMinSpinner == null ? 1.0 : sigmaMinSpinner.getValue();
            double sMax = sigmaMaxSpinner == null ? 4.0 : sigmaMaxSpinner.getValue();
            double sStep = sigmaStepSpinner == null ? 1.0 : sigmaStepSpinner.getValue();
            in.put("sigma_min", sMin / pxUm);
            in.put("sigma_max", sMax / pxUm);
            in.put("sigma_step", sStep / pxUm);
            double minAreaUm2 = minFiberAreaSpinner == null ? 1.0 : minFiberAreaSpinner.getValue();
            in.put("min_fiber_area_px", (int) Math.max(0, Math.round(minAreaUm2 / (pxUm * pxUm))));
            in.put("invert_intensity", invertIntensityCheck != null && invertIntensityCheck.isSelected());
            double rbUm = rollingBallSpinner == null ? 0.0 : rollingBallSpinner.getValue();
            in.put("rolling_ball_radius", (int) Math.max(0, Math.round(rbUm / pxUm)));
            // Calibrated threshold lookup if needed.
            if ("project_otsu".equals(thrCode) && calibrationCombo != null && calibrationCombo.getValue() != null) {
                Double thr = FiberAnalysisWorkflow.loadCalibratedThreshold(calibrationCombo.getValue());
                if (thr != null) in.put("project_threshold_norm", thr);
            }

            Task task = ApposeFiberService.getInstance().runTask("preview_segmentation", in);
            if (lastRequest.get() != requestId) return;

            Object out = task.outputs.get("result_json");
            JsonObject result = out == null ? null : new Gson().fromJson(String.valueOf(out), JsonObject.class);
            if (result != null && result.has("error")) {
                setStatus(statusLabel, "Python error: " + result.get("error").getAsString());
                return;
            }

            // Display the original region + the magenta overlay PNG produced by Python.
            BufferedImage baseImg = ImageIO.read(regionPng.toFile());
            BufferedImage overlayImg = ImageIO.read(overlayPng.toFile());
            Image fxBase = SwingFXUtils.toFXImage(baseImg, null);
            Image fxOverlay = SwingFXUtils.toFXImage(overlayImg, null);
            final long elapsed = System.currentTimeMillis() - t0;
            final String summary;
            if (result != null) {
                summary = String.format(
                        java.util.Locale.ROOT,
                        "%dx%d at (%d,%d) | fiber %d/%d px (%.1f%%) | Python %.0f ms (total %d ms)",
                        w,
                        h,
                        x,
                        y,
                        result.get("fiber_pixels").getAsInt(),
                        result.get("total_pixels").getAsInt(),
                        result.get("coverage_percent").getAsDouble(),
                        result.get("ms_segment").getAsDouble(),
                        elapsed);
            } else {
                summary = String.format("%dx%d at (%d,%d) | %d ms", w, h, x, y, elapsed);
            }
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
}
