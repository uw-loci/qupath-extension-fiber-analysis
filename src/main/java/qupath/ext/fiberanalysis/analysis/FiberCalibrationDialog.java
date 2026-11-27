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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Dialog that explains and launches a project-wide threshold calibration for
 * the settings of one run (the batch's loaded params.json).
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
    private static final String TITLE = "Fiber Analysis Calibration";

    private final QuPathGUI gui;
    private final FiberAnalysisParams runParams;
    private TextField nameField;
    private Spinner<Integer> sampleSpinner;
    private CheckBox sampleAllCheck;
    private TextField nameFilterField;
    private TextField classFilterField;
    private CheckBox invertCheck;
    private Spinner<Double> rollingBallSpinner;
    private ComboBox<String> channelCombo;
    private Label channelNote;
    private ComboBox<String> ridgeCombo;
    private Spinner<Double> borderZoneSpinner;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button runBtn;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** Each project image's metadata, by image name, filled in the background. */
    private final Map<String, ImageServerMetadata> metadataByImage = new LinkedHashMap<>();

    /**
     * @param gui       the QuPath window
     * @param runParams the parameters of the run the calibration is for
     */
    public FiberCalibrationDialog(QuPathGUI gui, FiberAnalysisParams runParams) {
        this.gui = gui;
        this.runParams = runParams;
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

        Scene scene = new Scene(scroller, 680, 760);
        dialog.setScene(scene);
        dialog.setMinWidth(560);
        dialog.setMinHeight(480);
        dialog.show();
        loadChannelChoices();
    }

    private VBox buildExplanation() {
        VBox box = new VBox(6);
        Label title = new Label("What this does");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        Label body = new Label("An Otsu run fits one threshold for the annotations in that run (every image, in"
                + " a batch), so one run is comparable within itself. A different run, on other"
                + " annotations or images, fits its own, and the two are NOT directly comparable.\n\n"
                + "Project calibration fits one threshold the same way, on a random subsample of"
                + " project annotations: samples of their analysis zones at the zone, downsample and"
                + " filter settings of the loaded params.json. It is saved as a named JSON file under"
                + " <project>/fiber-analysis/, and every run that picks 'Project Otsu (calibrated)'"
                + " in the main dialog uses that one value. A run whose settings differ from the"
                + " calibration's refuses it.");
        body.setWrapText(true);
        box.getChildren().addAll(title, body);
        return box;
    }

    private VBox buildImageGuidance() {
        VBox box = new VBox(6);
        Label title = new Label("Image-type guidance -- read this before running");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        Label body = new Label("Calibration assumes collagen fibers are BRIGHTER than background. That's true for:\n"
                + "    [OK] Picrosirius polarized / SHG fluorescence\n"
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
                + "When in doubt: run a regular Otsu on a single representative"
                + " image first, confirm the result card's 'Fiber mask' overlay covers the actual fibers, then come"
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
                + " (e.g. 'tumor-stroma', 'biopsy', 'shg-only')."));
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
        nameFilterField.textProperty().addListener((o, a, b) -> refreshChannelChoices());

        classFilterField = new TextField(
                "class".equals(runParams.searchArea()) && runParams.classFilter() != null
                        ? runParams.classFilter()
                        : "");
        classFilterField.setPromptText("(blank = all classes; e.g. Tumor, Stroma)");
        classFilterField.setTooltip(
                new Tooltip("Comma-separated PathClass names. Only annotations with one of these classes"
                        + " are included in the calibration pool. 'Unclassified' matches annotations with"
                        + " no class assigned."));

        channelCombo =
                new ComboBox<>(FXCollections.observableArrayList(SourceChannel.choices((ImageServerMetadata) null)));
        channelCombo.setValue(SourceChannel.offered(runParams.internalChannel()));
        channelCombo.setTooltip(new Tooltip("Same source channel the run will use. Lists the choices every image"
                + " the calibration reads offers, as the Run dialog does for one image."));
        channelNote = new Label("");
        channelNote.setWrapText(true);
        channelNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #e07a10;");
        channelNote.setVisible(false);
        channelNote.managedProperty().bind(channelNote.visibleProperty());

        ridgeCombo = new ComboBox<>(FXCollections.observableArrayList("None", "Frangi", "Sato", "Meijering"));
        ridgeCombo.setValue(runParams.ridgeFilter());
        ridgeCombo.setTooltip(new Tooltip("Same ridge filter the run will use. Calibration histograms the post-filter"
                + " scalar, so the threshold transfers cleanly only when filters match."));

        invertCheck = new CheckBox("Invert intensity (DAB / dark-fiber-on-bright)");
        invertCheck.setSelected(runParams.invertIntensity());
        invertCheck.setTooltip(new Tooltip("Tick for DAB, trichrome blue, or any stain where fibers are darker than"
                + " background. Set it the same way in Section 2 of the Run dialog."));

        rollingBallSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 50.0, runParams.rollingBallRadiusUm(), 0.5));
        rollingBallSpinner.setEditable(true);
        rollingBallSpinner.setPrefWidth(100);
        rollingBallSpinner.setTooltip(
                new Tooltip("Optional background-subtract radius in microns. 0 disables. Use ~2x typical fiber"
                        + " width to flatten uneven illumination before thresholding. Converted to"
                        + " pixels with each image's own pixel size."));

        borderZoneSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 500.0, runParams.borderZoneUm(), 5.0));
        borderZoneSpinner.setEditable(true);
        borderZoneSpinner.setPrefWidth(100);
        borderZoneSpinner.setDisable("whole".equals(runParams.zoneMode()));
        borderZoneSpinner.setTooltip(new Tooltip("Width of the analysis zone around each annotation, as in"
                + " Section 1 of the Run dialog. A run with another width refuses the calibration."));

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
        grid.add(new VBox(4, channelCombo, channelNote), 1, r++);
        grid.add(new Label("Ridge filter:"), 0, r);
        grid.add(ridgeCombo, 1, r++);
        grid.add(invertCheck, 0, r, 2, 1);
        r++;
        grid.add(new Label("Rolling-ball radius (um):"), 0, r);
        grid.add(rollingBallSpinner, 1, r++);
        grid.add(new Label("Border zone width (um):"), 0, r);
        grid.add(borderZoneSpinner, 1, r++);

        Label fixedTitle = new Label("From the loaded params.json (change them there):");
        fixedTitle.setStyle("-fx-font-weight: bold;");
        Label fixed = new Label(describeFixedSettings(runParams));
        fixed.setWrapText(true);
        fixed.setStyle("-fx-font-size: 11px;");

        box.getChildren().addAll(grid, fixedTitle, fixed);
        return box;
    }

    /**
     * @param p the run's parameters
     * @return the settings the calibration takes from the run unchanged, one per line
     */
    static String describeFixedSettings(FiberAnalysisParams p) {
        String pixel = p.useImagePixelSize()
                ? "each image's own (" + PixelSizeCheck.um(p.pixelSizeOverrideUm()) + " um/px for an image with none)"
                : PixelSizeCheck.um(p.pixelSizeOverrideUm()) + " um/px for every image";
        return String.format(
                Locale.ROOT,
                "Zone mode: %s\nAnalysis downsample: %s\nRidge sigmas: %s-%s um, step %s um\nPixel size: %s",
                p.zoneMode(),
                PixelSizeCheck.um(Math.max(1.0, p.analysisDownsample())),
                PixelSizeCheck.um(p.sigmaMinUm()),
                PixelSizeCheck.um(p.sigmaMaxUm()),
                PixelSizeCheck.um(p.sigmaStepUm()),
                pixel);
    }

    /** Reads every project image's metadata in the background, then fills the channel choices. */
    private void loadChannelChoices() {
        Project<BufferedImage> project = getProject();
        if (project == null) return;
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>(project.getImageList());
        Thread t = new Thread(
                () -> {
                    Map<String, ImageServerMetadata> found = new LinkedHashMap<>();
                    for (ProjectImageEntry<BufferedImage> entry : entries) {
                        if (DensityChannelAttacher.isChannelsEntry(entry)) continue;
                        ImageServerMetadata md = metadataOf(entry);
                        if (md != null) found.put(entry.getImageName(), md);
                    }
                    Platform.runLater(() -> {
                        metadataByImage.putAll(found);
                        refreshChannelChoices();
                    });
                },
                "FiberCalibrationChannels");
        t.setDaemon(true);
        t.start();
    }

    private static ImageServerMetadata metadataOf(ProjectImageEntry<BufferedImage> entry) {
        try {
            var stored = entry.getServerBuilder().getMetadata();
            if (stored.isPresent()) return stored.get();
            try (ImageServer<BufferedImage> server = entry.getServerBuilder().build()) {
                return server.getMetadata();
            }
        } catch (Exception ex) {
            logger.debug("Could not read the channels of {}: {}", entry.getImageName(), ex.getMessage());
            return null;
        }
    }

    /** Offers the source channels every image the name filter selects has. */
    private void refreshChannelChoices() {
        if (metadataByImage.isEmpty()) return;
        String needle = nameFilterField.getText() == null
                ? ""
                : nameFilterField.getText().toLowerCase(Locale.ROOT).trim();
        List<ImageServerMetadata> selected = new ArrayList<>();
        for (var e : metadataByImage.entrySet()) {
            if (needle.isEmpty()
                    || (e.getKey() != null
                            && e.getKey().toLowerCase(Locale.ROOT).contains(needle))) selected.add(e.getValue());
        }
        List<String> choices = SourceChannel.commonChoices(selected);
        String current = channelCombo.getValue();
        boolean missing = current != null && !choices.contains(current);
        if (missing) choices.add(current);
        channelCombo.getItems().setAll(choices);
        channelCombo.setValue(current);
        channelNote.setText(
                missing
                        ? "'" + current + "' is not a choice on every image the calibration reads; those images would"
                                + " fall back to the brightest band or fail."
                        : "");
        channelNote.setVisible(missing);
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
        cancelBtn.setTooltip(new Tooltip("Close this window. A calibration in progress stops and writes nothing."));
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            dialog.close();
        });

        runBtn = new Button("Run calibration");
        runBtn.setDefaultButton(true);
        runBtn.setOnAction(e -> runCalibration());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, cancelBtn, runBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(progressBar, statusLabel, footer);
        return box;
    }

    private void runCalibration() {
        Project<BufferedImage> project = getProject();
        if (project == null || project.getPath() == null) {
            UserMessage.error(TITLE, "A QuPath project must be open. Create or open a project and try again.");
            return;
        }
        FiberCalibrationRunner.CalibrationConfig cfg = FiberCalibrationRunner.CalibrationConfig.fromRun(runParams);
        cfg.calibrationName = nameField.getText() == null ? "default" : nameField.getText();
        cfg.sampleSize = sampleAllCheck.isSelected() ? 0 : sampleSpinner.getValue();
        cfg.nameFilter = nameFilterField.getText() == null ? "" : nameFilterField.getText();
        cfg.classFilter = AnnotationClassFilter.parse(classFilterField.getText());
        cfg.segChannel = channelCombo.getValue();
        cfg.ridgeFilter = ridgeCombo.getValue();
        cfg.invertIntensity = invertCheck.isSelected();
        cfg.rollingBallRadiusUm = rollingBallSpinner.getValue();
        cfg.borderZoneUm = borderZoneSpinner.getValue();
        // The open image's own hierarchy, so its unsaved annotations count.
        cfg.imageSource = entry -> FiberAnalysisBatchDialog.openOrRead(gui, entry);
        javafx.stage.Window owner =
                runBtn.getScene() == null ? null : runBtn.getScene().getWindow();
        cfg.onMixedPixelSizes =
                (check, note) -> PixelSizePrompt.ask(owner, "Fiber Analysis -- Calibration", check, true, note);

        try {
            Path existing =
                    FiberCalibrationRunner.calibrationFile(project.getPath().getParent(), cfg.calibrationName);
            if (Files.isRegularFile(existing)
                    && !Dialogs.showConfirmDialog(
                            TITLE,
                            "A calibration named '" + FiberCalibrationRunner.safeName(cfg.calibrationName)
                                    + "' already exists. Runs that use it will cut at the new threshold"
                                    + " instead. Replace it?")) {
                return;
            }
        } catch (java.io.IOException ex) {
            UserMessage.error(TITLE, ex.getMessage());
            return;
        }

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
                                    "Done. threshold=%.4f (normalized), regions=%d, pixels=%d. Saved to %s",
                                    result.thresholdNormalised,
                                    result.regionsUsed,
                                    result.pixelsUsed,
                                    result.calibrationFile.getFileName()));
                            Dialogs.showInfoNotification(
                                    TITLE,
                                    String.format(
                                            "Saved calibration '%s' -- threshold=%.4f across %d regions",
                                            cfg.calibrationName, result.thresholdNormalised, result.regionsUsed));
                            runBtn.setDisable(false);
                        });
                    } catch (CancellationException ce) {
                        logger.info("Calibration '{}' canceled: {}", cfg.calibrationName, ce.getMessage());
                        Platform.runLater(() -> {
                            progressBar.setProgress(0);
                            statusLabel.setText("Canceled. Nothing was written.");
                            runBtn.setDisable(false);
                            Dialogs.showInfoNotification(
                                    TITLE, "Calibration '" + cfg.calibrationName + "' canceled. Nothing was written.");
                        });
                    } catch (Exception ex) {
                        logger.error("Calibration failed", ex);
                        Platform.runLater(() -> {
                            progressBar.setProgress(0);
                            statusLabel.setText("Calibration failed: " + ex.getMessage());
                            runBtn.setDisable(false);
                            Dialogs.showErrorMessage(TITLE, ex.getMessage());
                        });
                    }
                },
                "FiberCalibration");
        worker.setDaemon(true);
        worker.start();
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
