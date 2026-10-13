/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.classifier;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.DensityChannelAttacher;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.HeadlessFx;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.ui.SectionBuilder;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Apply a trained window classifier to the open image.
 *
 * <p>Two outputs, and the default pairing is deliberate. The probability
 * raster is a few float channels and costs one image server however big the
 * slide is; the core detections are one object per core, which at a fine
 * stride over a whole slide is hundreds of thousands of them. So the raster
 * is on by default everywhere and the detections default on only for an
 * annotation-sized run, with a hard count check before either.
 *
 * <p>A model that failed its own validation can still be applied -- a
 * refusal to predict should not be a refusal to look -- but only after an
 * explicit acknowledgement, and everything it produces is stamped
 * provisional so a screenshot that escapes into a slide deck still says so.
 */
public final class FiberWindowApplyDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberWindowApplyDialog.class);

    private static final String TITLE = "Fiber Analysis -- Apply window classifier";

    private final QuPathGUI gui;

    private ComboBox<WindowClassifierModelManager.ModelInfo> modelCombo;
    private Label modelInfoLabel;
    private Label compatibilityLabel;
    private RadioButton scopeImageRadio;
    private RadioButton scopeAnnotationRadio;
    private CheckBox rasterCheck;
    private CheckBox attachCheck;
    private CheckBox detectionsCheck;
    private CheckBox overrideCheck;
    private CheckBox acknowledgeCheck;
    private Label countLabel;
    private TitledPane overridesPane;
    private TextArea logArea;
    private Button applyBtn;

    public FiberWindowApplyDialog(QuPathGUI gui) {
        this.gui = gui;
    }

    /** Open the dialog. Requires an open image in a project. */
    public void show() {
        if (gui == null || gui.getImageData() == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }
        Project<BufferedImage> project = gui.getProject();
        List<WindowClassifierModelManager.ModelInfo> models = WindowClassifierModelManager.list(project);
        if (models.isEmpty()) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "No trained window classifier was found.\n\nTrain one first," + " or copy a model folder into\n"
                            + WindowClassifierModelManager.userRoot());
            return;
        }

        Stage dialog = new Stage();
        dialog.setTitle(TITLE);
        if (gui.getStage() != null) dialog.initOwner(gui.getStage());
        VBox root = new VBox(8, buildBody(models), buildFooter(dialog));
        root.setPadding(new Insets(12));
        dialog.setScene(new Scene(root, 780, 640));
        dialog.setMinWidth(680);
        dialog.setMinHeight(520);
        refresh();
        dialog.show();
    }

    private VBox buildBody(List<WindowClassifierModelManager.ModelInfo> models) {
        modelCombo = new ComboBox<>(FXCollections.observableArrayList(models));
        modelCombo.setMaxWidth(Double.MAX_VALUE);
        modelCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(WindowClassifierModelManager.ModelInfo m) {
                return m == null ? "" : m.label();
            }

            @Override
            public WindowClassifierModelManager.ModelInfo fromString(String s) {
                return null;
            }
        });
        modelCombo.getSelectionModel().selectFirst();
        modelCombo.valueProperty().addListener((o, a, b) -> refresh());

        modelInfoLabel = wrapping(new Label());
        compatibilityLabel = wrapping(new Label());
        compatibilityLabel.setStyle("-fx-text-fill: -fx-accent;");

        ToggleGroup scope = new ToggleGroup();
        scopeImageRadio = new RadioButton("Whole image");
        scopeAnnotationRadio = new RadioButton("Selected annotation");
        scopeImageRadio.setToggleGroup(scope);
        scopeAnnotationRadio.setToggleGroup(scope);
        boolean hasSelection = selectedAnnotation() != null;
        scopeAnnotationRadio.setDisable(!hasSelection);
        if (hasSelection) {
            scopeAnnotationRadio.setSelected(true);
        } else {
            scopeImageRadio.setSelected(true);
        }
        scope.selectedToggleProperty().addListener((o, a, b) -> refresh());

        rasterCheck = new CheckBox("Write probability map (OME-TIFF sidecar)");
        rasterCheck.setSelected(true);
        attachCheck = new CheckBox("Attach the map to this image as channels");
        attachCheck.setSelected(true);
        attachCheck.disableProperty().bind(rasterCheck.selectedProperty().not());
        detectionsCheck = new CheckBox("Create one detection per core");
        detectionsCheck.setSelected(hasSelection);
        detectionsCheck.selectedProperty().addListener((o, a, b) -> refresh());
        overrideCheck = new CheckBox("Apply despite a geometry mismatch (recorded in the run)");
        acknowledgeCheck = new CheckBox("I understand this model failed its own validation");

        countLabel = wrapping(new Label());

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(8);

        overridesPane = SectionBuilder.createSection("Overrides", false, new VBox(4, overrideCheck, acknowledgeCheck));
        VBox body = new VBox(
                8,
                SectionBuilder.createSection(
                        "Model", true, new VBox(6, modelCombo, modelInfoLabel, compatibilityLabel)),
                SectionBuilder.createSection("Scope", true, new VBox(4, scopeImageRadio, scopeAnnotationRadio)),
                SectionBuilder.createSection(
                        "Output", true, new VBox(4, rasterCheck, attachCheck, detectionsCheck, countLabel)),
                overridesPane,
                SectionBuilder.createSection("Log", true, logArea));
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private HBox buildFooter(Stage dialog) {
        applyBtn = new Button("Apply");
        applyBtn.setDefaultButton(true);
        applyBtn.setOnAction(e -> onApply());
        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox footer = new HBox(8, applyBtn, close);
        footer.setPadding(new Insets(8, 0, 0, 0));
        return footer;
    }

    // ---------- state ----------

    /** A label that wraps instead of ellipsising -- these carry the warnings. */
    private static Label wrapping(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }

    private PathObject selectedAnnotation() {
        ImageData<BufferedImage> data = gui == null ? null : gui.getImageData();
        if (data == null) return null;
        PathObject sel = data.getHierarchy().getSelectionModel().getSelectedObject();
        return sel != null && sel.isAnnotation() && sel.getROI() != null ? sel : null;
    }

    private RegionRequest scopeRegion() {
        ImageServer<BufferedImage> server = gui.getImageData().getServer();
        PathObject sel = selectedAnnotation();
        if (scopeAnnotationRadio.isSelected() && sel != null) {
            ROI roi = sel.getROI();
            return RegionRequest.createInstance(
                    server.getPath(),
                    1.0,
                    (int) Math.floor(roi.getBoundsX()),
                    (int) Math.floor(roi.getBoundsY()),
                    (int) Math.ceil(roi.getBoundsWidth()),
                    (int) Math.ceil(roi.getBoundsHeight()));
        }
        return RegionRequest.createInstance(server.getPath(), 1.0, 0, 0, server.getWidth(), server.getHeight());
    }

    private void refresh() {
        var model = modelCombo.getValue();
        if (model == null) return;
        modelInfoLabel.setText(String.format(
                Locale.ROOT,
                "%s, trained %s, %d feature(s). Classes: %s",
                model.directory(),
                model.createdUtc(),
                model.featureNames().size(),
                String.join(", ", model.classNames())));

        var problems = WindowClassifierModelManager.checkCompatibility(model, model.featureNames(), currentGeometry());
        // Feature names are not known until the extraction runs, so the
        // pre-check compares the geometry only -- it is here to catch the
        // mismatch while the user can still change it, not to replace the
        // Python-side refusal.
        problems.removeIf(p -> p.startsWith("Feature set") || p.startsWith("Same features"));
        compatibilityLabel.setText(problems.isEmpty() ? "" : String.join("\n", problems));
        overrideCheck.setDisable(problems.isEmpty());
        acknowledgeCheck.setDisable(!model.provisional());
        if (model.provisional()) {
            acknowledgeCheck.setText("I understand this model failed its own validation (" + model.verdict()
                    + "); its objects will be marked provisional");
        }
        // Open the section when it holds something the run needs. Leaving a
        // required acknowledgement folded away means the user clicks Apply,
        // is told to tick a box, and cannot see one.
        if (overridesPane != null && (model.provisional() || !problems.isEmpty())) {
            overridesPane.setExpanded(true);
        }

        RegionRequest region = scopeRegion();
        double pxUm = pixelSizeUm();
        double windowUm = FiberAnalysisPreferences.windowSizeUmProperty().get();
        int overlap = FiberAnalysisPreferences.windowOverlapPercentProperty().get();
        int windowPx = Math.max(2, (int) Math.round(windowUm / pxUm));
        int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlap / 100.0)));
        int gridW = WindowGeometry.totalCount(region.getWidth(), windowPx, stridePx);
        int gridH = WindowGeometry.totalCount(region.getHeight(), windowPx, stridePx);
        long cores = ClassifiedCoreDetections.estimateCount(gridW, gridH);

        boolean overBudget = detectionsCheck.isSelected()
                && ClassifiedCoreDetections.exceedsBudget(
                        gridW, gridH, ClassifiedCoreDetections.DEFAULT_MAX_DETECTIONS);
        countLabel.setText(
                overBudget
                        ? ClassifiedCoreDetections.budgetMessage(
                                gridW, gridH, ClassifiedCoreDetections.DEFAULT_MAX_DETECTIONS)
                        : String.format(
                                Locale.ROOT,
                                "%d x %d windows (%,d cores of %.1f um).",
                                gridW,
                                gridH,
                                cores,
                                stridePx * pxUm));
        applyBtn.setDisable(overBudget);
    }

    private double pixelSizeUm() {
        var cal = gui.getImageData().getServer().getPixelCalibration();
        return cal != null && cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 1.0;
    }

    private Map<String, Object> currentGeometry() {
        double pxUm = pixelSizeUm();
        double windowUm = FiberAnalysisPreferences.windowSizeUmProperty().get();
        int overlap = FiberAnalysisPreferences.windowOverlapPercentProperty().get();
        int windowPx = Math.max(2, (int) Math.round(windowUm / pxUm));
        int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlap / 100.0)));
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("window_um", windowUm);
        geometry.put("pixel_size_um", pxUm);
        geometry.put("window_px", windowPx);
        geometry.put("stride_px", stridePx);
        geometry.put("core_px", stridePx);
        geometry.put("segmentation_hash", SegmentationSpec.fromPreferences().hash());
        return geometry;
    }

    // ---------- run ----------

    private void onApply() {
        var model = modelCombo.getValue();
        if (model == null) return;
        if (model.provisional() && !acknowledgeCheck.isSelected()) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "This model failed its own validation (" + model.verdict()
                            + "). Tick the acknowledgement under Overrides to apply it anyway;"
                            + " everything it produces will be marked provisional.");
            return;
        }
        if (!rasterCheck.isSelected() && !detectionsCheck.isSelected()) {
            Dialogs.showErrorMessage(TITLE, "Choose at least one output.");
            return;
        }
        applyBtn.setDisable(true);
        var progress = new ClassifierProgress(gui.getStage(), TITLE);
        Thread worker = new Thread(() -> runApply(model, progress), "fiber-window-apply");
        worker.setDaemon(true);
        worker.start();
    }

    private void runApply(WindowClassifierModelManager.ModelInfo model, ClassifierProgress progress) {
        StringBuilder log = new StringBuilder();
        try {
            ImageData<BufferedImage> data = gui.getImageData();
            RegionRequest region = scopeRegion();
            var seg = SegmentationSpec.fromPreferences();
            var request = new WindowClassifierWorkflow.Request(
                    WindowFeatureMatrix.Spec.defaults(
                            seg,
                            FiberAnalysisPreferences.windowSizeUmProperty().get(),
                            FiberAnalysisPreferences.windowOverlapPercentProperty()
                                    .get()),
                    FiberWindowTrainDialog.parseRadii(
                            String.valueOf(model.geometry().getOrDefault("context_radii", ""))),
                    1.0,
                    WindowClassifierWorkflow.createWorkDir());

            progress.status("Computing window features...");
            var features = WindowClassifierWorkflow.computeFeatures(
                    data, region, request, progress::status, progress::cancelled);
            log.append(String.format(
                    Locale.ROOT,
                    "Features: %d x %d windows, %d columns%n",
                    features.gridW(),
                    features.gridH(),
                    features.featureNames().size()));

            progress.status("Scoring...");
            var result = WindowClassifierWorkflow.apply(
                    features, model.directory(), currentGeometry(), overrideCheck.isSelected(), request.workDir());
            for (String w : result.warnings()) log.append("WARNING: ").append(w).append('\n');

            String runId = UUID.randomUUID().toString();
            if (rasterCheck.isSelected()) {
                progress.status("Writing the probability map...");
                Project<BufferedImage> project = gui.getProject();
                ProjectImageEntry<BufferedImage> entry = project == null ? null : project.getEntry(data);
                Path sidecar = DensitySidecar.classifierSidecarPathFor(project, entry, model.id());
                if (sidecar == null) {
                    log.append("No project folder on disk, so the probability map was not written.\n");
                } else {
                    WindowClassifierWorkflow.writeProbabilityMap(
                            result,
                            sidecar,
                            data.getServer().getWidth(),
                            data.getServer().getHeight(),
                            pixelSizeUm());
                    log.append("Probability map: ").append(sidecar).append('\n');
                    if (attachCheck.isSelected()) {
                        var colors = WindowClassifierWorkflow.probabilityChannelColors(result.classNames());
                        DensityChannelAttacher.attachSidecar(gui, data, sidecar, colors);
                        log.append("Attached as channels.\n");
                    }
                }
            }

            if (detectionsCheck.isSelected()) {
                progress.status("Creating core detections...");
                List<PathObject> dets = WindowClassifierWorkflow.coreDetections(
                        result, region, model.id(), runId, model.provisional(), List.of());
                final List<PathObject> toAdd = new ArrayList<>(dets);
                HeadlessFx.runLater(() -> {
                    data.getHierarchy().addObjects(toAdd);
                    data.getHierarchy().fireHierarchyChangedEvent(this);
                });
                log.append(String.format(Locale.ROOT, "Core detections: %,d%n", dets.size()));
            }

            log.append("Training verdict carried from the model: ")
                    .append(result.verdict())
                    .append('\n');
            if (model.provisional()) {
                log.append("Every object from this run is marked provisional.\n");
            }
            String text = log.toString();
            HeadlessFx.runLater(() -> logArea.setText(text));
            logger.info("Applied window classifier {}: {}", model.id(), text.replace("\n", " | "));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            HeadlessFx.runLater(() -> logArea.setText("Cancelled."));
        } catch (Exception ex) {
            logger.error("Applying the window classifier failed", ex);
            String msg = String.valueOf(ex.getMessage());
            HeadlessFx.runLater(() -> {
                logArea.setText(log + "\nFailed: " + msg);
                Dialogs.showErrorMessage(TITLE, msg);
            });
        } finally {
            progress.close();
            HeadlessFx.runLater(() -> applyBtn.setDisable(false));
        }
    }
}
