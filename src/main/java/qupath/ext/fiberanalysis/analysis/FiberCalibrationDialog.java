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

import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Dialog that explains and launches a project-wide threshold calibration.
 *
 * <p>Why this exists: per-annotation Otsu gives each region its own threshold,
 * so sparse regions threshold noise as fiber and dense regions clip dim
 * fibers. Coverage / ridge count / HDM are then not comparable across the
 * project. This dialog scans a subsample of project annotations, accumulates
 * a global intensity histogram, and computes a single project-wide Otsu
 * threshold. The result lands at
 * {@code <project>/fiber-analysis/calibration_<name>.json} for the main
 * dialog's "Project Otsu (calibrated)" threshold method to pick up.
 */
public final class FiberCalibrationDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberCalibrationDialog.class);

    private final QuPathGUI gui;
    private TextField nameField;
    private Spinner<Integer> sampleSpinner;
    private CheckBox sampleAllCheck;
    private TextField nameFilterField;
    private TextField classFilterField;
    private CheckBox invertCheck;
    private Spinner<Double> rollingBallSpinner;
    private ComboBox<String> channelCombo;
    private ComboBox<String> ridgeCombo;
    private Spinner<Double> borderZoneSpinner;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runBtn;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public FiberCalibrationDialog(QuPathGUI gui) {
        this.gui = gui;
    }

    public void show() {
        Stage dialog = new Stage();
        dialog.setTitle("Fiber Analysis -- Calibrate project threshold");
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));

        content.getChildren().add(buildExplanation());
        content.getChildren().add(new Separator());
        content.getChildren().add(buildImageGuidance());
        content.getChildren().add(new Separator());
        content.getChildren().add(buildSettingsGrid());
        content.getChildren().add(new Separator());
        content.getChildren().add(buildFooter(dialog));

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(scroller, 680, 720);
        dialog.setScene(scene);
        dialog.setMinWidth(560);
        dialog.setMinHeight(480);
        dialog.show();
    }

    private VBox buildExplanation() {
        VBox box = new VBox(6);
        Label title = new Label("What this does");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        Label body = new Label("Per-annotation Otsu picks a fresh threshold for every region, which makes "
                + "fiber coverage and density NOT comparable across images: a region with "
                + "no real collagen will threshold background noise as fiber, and a region "
                + "with dense bright collagen will set a high threshold that misses dimmer "
                + "fibers.\n\n"
                + "Project calibration fixes this by streaming a random subsample of "
                + "project regions, accumulating a single intensity histogram across all "
                + "of them, and running Otsu once on that pooled distribution. The "
                + "resulting threshold is saved as a named JSON file under "
                + "<project>/fiber-analysis/. Future runs pick it from the main dialog's "
                + "'Project Otsu (calibrated)' option.");
        body.setWrapText(true);
        box.getChildren().addAll(title, body);
        return box;
    }

    private VBox buildImageGuidance() {
        VBox box = new VBox(6);
        Label title = new Label("Image-type guidance -- read this before running");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        Label body = new Label("Calibration assumes collagen fibers are BRIGHTER than background. That's true for:\n"
                + "    [OK] Picrosirius polarised / SHG fluorescence\n"
                + "    [OK] PPM sum / birefringence images (this lab's primary modality)\n"
                + "    [OK] Single-channel collagen-targeted fluorescence (col1a1-GFP, etc.)\n\n"
                + "Brightfield / chromogenic stains need care:\n"
                + "    [Caution] Brightfield H&E: pick the right source channel (Saturation or"
                + " Hue often works better than Raw intensity); collagen is pink/lavender, not"
                + " always the brightest thing\n"
                + "    [Caution] Trichrome: blue collagen is darker than background; tick"
                + " 'Invert intensity' below\n"
                + "    [Caution] DAB / brown chromogenic: collagen is brown/dark; ALWAYS tick"
                + " 'Invert intensity' for these. Without invert, calibration will threshold"
                + " the bright unstained tissue as 'fiber' and report nonsense.\n\n"
                + "When in doubt: run a regular per-annotation Otsu on a single representative"
                + " image first, confirm 'Show fiber mask' overlays the actual fibers, then come"
                + " back here with the same source-channel + invert settings.");
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 11px;");
        box.getChildren().addAll(title, body);
        return box;
    }

    private VBox buildSettingsGrid() {
        VBox box = new VBox(8);
        Label title = new Label("Calibration settings");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        box.getChildren().add(title);

        nameField = new TextField("default");
        nameField.setTooltip(new Tooltip("Calibration name -- appears in the main dialog's Calibration combo."
                + " Useful when you want separate calibrations for different image cohorts"
                + " (e.g. 'tumour-stroma', 'biopsy', 'shg-only')."));
        nameField.setPrefColumnCount(20);

        sampleSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 5000, 50, 5));
        sampleSpinner.setEditable(true);
        sampleSpinner.setPrefWidth(100);
        sampleSpinner.setTooltip(new Tooltip("Random subsample of project annotations to use for the histogram pool."
                + " 10 = fast, 50 = solid for most projects, >100 = slow but exhaustive."));
        sampleAllCheck = new CheckBox("Use all available annotations");
        sampleAllCheck.setTooltip(
                new Tooltip("Override 'Sample size' and pool histograms across every matching annotation"
                        + " in the project. Recommended only for small projects."));
        sampleAllCheck.selectedProperty().addListener((o, a, b) -> sampleSpinner.setDisable(b));

        nameFilterField = new TextField("");
        nameFilterField.setPromptText("(blank = all images)");
        nameFilterField.setTooltip(
                new Tooltip("Only consider project images whose name contains this substring (case-insensitive)."));

        classFilterField = new TextField("");
        classFilterField.setPromptText("(blank = all classes; e.g. Tumor, Stroma)");
        classFilterField.setTooltip(
                new Tooltip("Comma-separated PathClass names. Only annotations with one of these classes"
                        + " are included in the calibration pool."));

        channelCombo = new ComboBox<>(
                FXCollections.observableArrayList("Raw intensity", "Hue (HSV)", "Saturation (HSV)", "Value (HSV)"));
        channelCombo.setValue(FiberAnalysisPreferences.internalChannelProperty().get());
        channelCombo.setTooltip(
                new Tooltip("Same source channel the run will use. Calibration only transfers cleanly when"
                        + " this matches the channel in Section 2 of the main dialog."));

        ridgeCombo = new ComboBox<>(FXCollections.observableArrayList("None", "Frangi", "Sato", "Meijering"));
        ridgeCombo.setValue(FiberAnalysisPreferences.ridgeFilterProperty().get());
        ridgeCombo.setTooltip(new Tooltip("Same ridge filter the run will use. Calibration histograms the post-filter"
                + " scalar, so the threshold transfers cleanly only when filters match."));

        invertCheck = new CheckBox("Invert intensity (DAB / dark-fiber-on-bright)");
        invertCheck.setSelected(
                FiberAnalysisPreferences.invertIntensityProperty().get());
        invertCheck.setTooltip(new Tooltip("ENABLE for DAB, trichrome blue, or any image where fibers are darker than"
                + " background. The same toggle is on Section 2 of the main dialog and must"
                + " be set the same way there for the calibration to apply correctly."));

        rollingBallSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0,
                50.0,
                FiberAnalysisPreferences.rollingBallRadiusUmProperty().get(),
                0.5));
        rollingBallSpinner.setEditable(true);
        rollingBallSpinner.setPrefWidth(100);
        rollingBallSpinner.setTooltip(
                new Tooltip("Optional background-subtract radius in microns. 0 disables. Use ~2x typical fiber"
                        + " width to flatten uneven illumination before thresholding. Java converts"
                        + " to pixels per region using the median pixel size across the pool."));

        borderZoneSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1.0, 500.0, FiberAnalysisPreferences.borderZoneUmProperty().get(), 5.0));
        borderZoneSpinner.setEditable(true);
        borderZoneSpinner.setPrefWidth(100);
        borderZoneSpinner.setTooltip(
                new Tooltip("Dilation around each annotation when extracting the region for calibration."
                        + " Match the value in Section 1 of the main dialog."));

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int r = 0;
        grid.add(new Label("Calibration name:"), 0, r);
        grid.add(nameField, 1, r++);
        grid.add(new Label("Sample size:"), 0, r);
        grid.add(new HBox(8, sampleSpinner, sampleAllCheck), 1, r++);
        grid.add(new Label("Image name filter:"), 0, r);
        grid.add(nameFilterField, 1, r++);
        grid.add(new Label("Class filter:"), 0, r);
        grid.add(classFilterField, 1, r++);
        grid.add(new Label("Source channel:"), 0, r);
        grid.add(channelCombo, 1, r++);
        grid.add(new Label("Ridge filter:"), 0, r);
        grid.add(ridgeCombo, 1, r++);
        grid.add(invertCheck, 0, r, 2, 1);
        r++;
        grid.add(new Label("Rolling-ball radius (um):"), 0, r);
        grid.add(rollingBallSpinner, 1, r++);
        grid.add(new Label("Border zone width (um):"), 0, r);
        grid.add(borderZoneSpinner, 1, r++);

        box.getChildren().add(grid);
        return box;
    }

    private VBox buildFooter(Stage dialog) {
        VBox box = new VBox(6);
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        statusLabel.setWrapText(true);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            dialog.close();
        });

        runBtn = new Button("Run calibration");
        runBtn.setDefaultButton(true);
        runBtn.setOnAction(e -> runCalibration(dialog));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, cancelBtn, runBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(progressBar, statusLabel, footer);
        return box;
    }

    private void runCalibration(Stage dialog) {
        Project<BufferedImage> project = getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Calibration", "A QuPath project must be open. Create or open a project and try again.");
            return;
        }
        FiberCalibrationRunner.CalibrationConfig cfg = new FiberCalibrationRunner.CalibrationConfig();
        cfg.calibrationName = nameField.getText() == null ? "default" : nameField.getText();
        cfg.sampleSize = sampleAllCheck.isSelected() ? 0 : sampleSpinner.getValue();
        cfg.nameFilter = nameFilterField.getText() == null ? "" : nameFilterField.getText();
        cfg.classFilter = parseClassFilter(classFilterField.getText());
        cfg.segChannel = channelCombo.getValue();
        cfg.ridgeFilter = ridgeCombo.getValue();
        cfg.invertIntensity = invertCheck.isSelected();
        cfg.rollingBallRadiusUm = rollingBallSpinner.getValue();
        cfg.borderZoneUm = borderZoneSpinner.getValue();

        runBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Starting calibration...");

        Thread worker = new Thread(
                () -> {
                    try {
                        FiberCalibrationRunner.CalibrationResult result =
                                FiberCalibrationRunner.run(project, cfg, new FiberCalibrationRunner.ProgressCallback() {
                                    @Override
                                    public void update(String message, int completed, int total) {
                                        double frac = total > 0 ? (double) completed / total : 0.0;
                                        Platform.runLater(() -> {
                                            statusLabel.setText(message);
                                            progressBar.setProgress(frac);
                                        });
                                    }

                                    @Override
                                    public boolean isCancelled() {
                                        return cancelled.get();
                                    }
                                });
                        Platform.runLater(() -> {
                            progressBar.setProgress(1.0);
                            statusLabel.setText(String.format(
                                    "Done. threshold=%.4f (normalised), regions=%d, pixels=%d. Saved to %s",
                                    result.thresholdNormalised,
                                    result.regionsUsed,
                                    result.pixelsUsed,
                                    result.calibrationFile.getFileName()));
                            Dialogs.showInfoNotification(
                                    "Fiber Analysis Calibration",
                                    String.format(
                                            "Saved calibration '%s' -- threshold=%.4f across %d regions",
                                            cfg.calibrationName, result.thresholdNormalised, result.regionsUsed));
                            runBtn.setDisable(false);
                        });
                    } catch (Exception ex) {
                        logger.error("Calibration failed", ex);
                        Platform.runLater(() -> {
                            progressBar.setProgress(0);
                            statusLabel.setText("Calibration failed: " + ex.getMessage());
                            runBtn.setDisable(false);
                            Dialogs.showErrorMessage("Fiber Analysis Calibration", ex.getMessage());
                        });
                    }
                },
                "FiberCalibration");
        worker.setDaemon(true);
        worker.start();
    }

    private static Set<String> parseClassFilter(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String tok : csv.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Project<BufferedImage> getProject() {
        if (gui == null) return null;
        try {
            return (Project<BufferedImage>) gui.getProject();
        } catch (Exception e) {
            return null;
        }
    }
}
