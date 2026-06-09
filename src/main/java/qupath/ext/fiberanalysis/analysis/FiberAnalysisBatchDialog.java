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
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Project-wide batch runner for the fiber-analysis extension.
 *
 * <p>The dialog lets the user (1) load a {@code params.json} produced by a
 * single-image run, (2) filter project images by name, (3) check the entries
 * to run, and (4) iterate -- for each selected entry the workflow reads the
 * stored {@link ImageData}, resolves the annotation set with the same Search
 * Area logic the single-image dialog uses, and dispatches a
 * {@link FiberAnalysisWorkflow#runForAnnotations} call.
 *
 * <p>This is the "apply across a selection of images within a project"
 * convenience requested in v0.2. Pattern reference: QuIET's
 * {@code ImageSelectionPane} (name filter + checkbox list) and PPM's
 * {@code PPMBatchAnalysisWorkflow} (per-entry read/save loop).
 */
public final class FiberAnalysisBatchDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisBatchDialog.class);

    private final QuPathGUI gui;
    private final FiberAnalysisPanel resultPanel;
    private FiberAnalysisParams loadedParams; // null until user loads a params.json
    private Label paramsStatusLabel;

    private TextField nameFilterField;
    private TextField classFilterField;
    private ListView<EntryRow> imageList;
    private ObservableList<EntryRow> allRows;
    private Label countLabel;

    public FiberAnalysisBatchDialog(QuPathGUI gui, FiberAnalysisPanel panel) {
        this.gui = gui;
        this.resultPanel = panel;
    }

    public void show() {
        Stage dialog = new Stage();
        dialog.setTitle("Fiber Analysis -- Batch (research use only)");
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }

        Project<BufferedImage> project = getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Fiber Analysis Batch", "A QuPath project must be open. Open or create a project and try again.");
            return;
        }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label header = new Label("Apply a saved params.json across multiple project images. The Search area\n"
                + "and class filter saved in params.json (or overridden below) drive which\n"
                + "annotations are analysed per image.");
        header.setWrapText(true);
        header.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        root.getChildren().add(header);

        // Project calibration banner: put this at the TOP so it's the first
        // thing users see -- per-region Otsu is the default but is NOT
        // comparable across images, and that fact is easy to miss without a
        // prominent banner. The button opens a dedicated calibration dialog
        // with full explanation + image-type guidance (DAB / chromogenic
        // warnings).
        root.getChildren().add(buildCalibrationBanner());
        root.getChildren().add(new Separator());

        root.getChildren().add(buildParamsLoaderRow(dialog));
        root.getChildren().add(new Separator());

        Label filterHdr = new Label("Filter project images");
        filterHdr.setStyle("-fx-font-weight: bold;");
        root.getChildren().add(filterHdr);
        root.getChildren().add(buildFilterRow());

        countLabel = new Label("");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        root.getChildren().add(countLabel);

        imageList = new ListView<>();
        imageList.setPrefHeight(280);
        imageList.setCellFactory(lv -> new EntryRowCell());
        VBox.setVgrow(imageList, Priority.ALWAYS);
        allRows = FXCollections.observableArrayList();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            allRows.add(new EntryRow(entry));
        }
        refreshList();
        root.getChildren().add(imageList);

        HBox selectButtons = new HBox(
                8,
                buildButton("Select all", () -> setAllChecked(true)),
                buildButton("Select none", () -> setAllChecked(false)));
        root.getChildren().add(selectButtons);

        root.getChildren().add(new Separator());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dialog.close());

        Button runBtn = new Button("Run on selected images");
        runBtn.setDefaultButton(true);
        runBtn.setTooltip(new Tooltip(
                "Read each selected image, resolve annotations using the loaded Search area + class filter,\n"
                        + "then dispatch the fiber-analysis workflow. Each image runs sequentially so the\n"
                        + "Appose pixi env is reused."));
        runBtn.setOnAction(e -> {
            List<ProjectImageEntry<BufferedImage>> chosen = checkedEntries();
            if (chosen.isEmpty()) {
                Dialogs.showWarningNotification("Fiber Analysis Batch", "No images selected.");
                return;
            }
            if (loadedParams == null) {
                Dialogs.showErrorMessage(
                        "Fiber Analysis Batch",
                        "Load a params.json first (Browse...). Use the dialog's 'Save defaults'"
                                + " after a single-image run to produce one.");
                return;
            }
            FiberAnalysisParams paramsForRun = mergeOverrides(loadedParams);
            dialog.close();
            runBatch(paramsForRun, chosen);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, cancelBtn, runBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(footer);

        Scene scene = new Scene(root, 640, 640);
        dialog.setScene(scene);
        dialog.setMinWidth(520);
        dialog.setMinHeight(420);
        dialog.show();
    }

    private javafx.scene.layout.VBox buildCalibrationBanner() {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6);
        box.setStyle("-fx-background-color: #fff5d6; -fx-border-color: #d4a82a; -fx-border-radius: 4;"
                + " -fx-background-radius: 4; -fx-padding: 8;");
        Label title = new Label("Calibrate threshold across this project (recommended)");
        title.setStyle("-fx-font-weight: bold;");
        Label body = new Label("Per-annotation Otsu gives every region a different threshold, so fiber coverage"
                + " / ridge count / HDM are NOT directly comparable across images. Run"
                + " calibration once to compute a single project-wide threshold from a"
                + " random subsample of regions. Required for cross-image density-map"
                + " workflows.");
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 11px;");

        Button calBtn = new Button("Calibrate threshold...");
        calBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Open the calibration dialog: explanation, image-type guidance (including DAB /"
                        + " brightfield warnings), settings, and a Run Calibration button."));
        calBtn.setOnAction(e -> new FiberCalibrationDialog(gui).show());
        javafx.scene.layout.HBox btnRow = new javafx.scene.layout.HBox(calBtn);
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        box.getChildren().addAll(title, body, btnRow);
        return box;
    }

    private HBox buildParamsLoaderRow(Stage owner) {
        Label l = new Label("Params:");
        paramsStatusLabel = new Label("(none loaded -- click Browse...)");
        paramsStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select fiber-analysis params.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON params", "*.json"));
            // Suggest the project's fiber-analysis folder as the initial dir.
            Project<BufferedImage> project = getProject();
            if (project != null && project.getPath() != null) {
                Path projDir = project.getPath().getParent();
                if (projDir != null) {
                    Path fa = projDir.resolve("fiber-analysis");
                    if (Files.isDirectory(fa)) {
                        chooser.setInitialDirectory(fa.toFile());
                    }
                }
            }
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                try {
                    loadedParams = loadParamsFromJson(f.toPath());
                    paramsStatusLabel.setText(f.getName() + "  (loaded)");
                    paramsStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #060;");
                } catch (Exception ex) {
                    logger.warn("Could not load params from {}", f, ex);
                    paramsStatusLabel.setText("Load failed: " + ex.getMessage());
                    paramsStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #c33;");
                }
            }
        });
        HBox row = new HBox(8, l, browse, paramsStatusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildFilterRow() {
        nameFilterField = new TextField();
        nameFilterField.setPromptText("Image name contains (case-insensitive)");
        nameFilterField.setTooltip(new Tooltip("Filter project images by substring match on image name."));
        nameFilterField.textProperty().addListener((o, a, b) -> refreshList());

        classFilterField = new TextField();
        classFilterField.setPromptText("Override class filter (optional, comma-separated)");
        classFilterField.setTooltip(
                new Tooltip("If non-empty, overrides the class filter from the loaded params.json for this batch run."
                        + " Format: 'Tumor, Stroma' (case-sensitive)."));

        Label nameLbl = new Label("Name:");
        Label classLbl = new Label("Class override:");
        HBox row = new HBox(6, nameLbl, nameFilterField, classLbl, classFilterField);
        HBox.setHgrow(nameFilterField, Priority.ALWAYS);
        HBox.setHgrow(classFilterField, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshList() {
        String needle = nameFilterField == null || nameFilterField.getText() == null
                ? ""
                : nameFilterField.getText().toLowerCase().trim();
        ObservableList<EntryRow> filtered = FXCollections.observableArrayList();
        for (EntryRow row : allRows) {
            if (needle.isEmpty() || row.entry.getImageName().toLowerCase().contains(needle)) {
                filtered.add(row);
            }
        }
        imageList.setItems(filtered);
        if (countLabel != null) {
            int checked = (int) filtered.stream().filter(r -> r.checked.get()).count();
            countLabel.setText(filtered.size() + " image(s) shown, " + checked + " checked");
        }
    }

    private void setAllChecked(boolean v) {
        for (EntryRow r : imageList.getItems()) {
            r.checked.set(v);
        }
        refreshList();
    }

    private List<ProjectImageEntry<BufferedImage>> checkedEntries() {
        List<ProjectImageEntry<BufferedImage>> out = new ArrayList<>();
        for (EntryRow r : allRows) {
            if (r.checked.get()) out.add(r.entry);
        }
        return out;
    }

    private FiberAnalysisParams mergeOverrides(FiberAnalysisParams base) {
        String override = classFilterField == null ? "" : classFilterField.getText();
        if (override == null || override.isBlank()) {
            return base;
        }
        // Force search area to "class" when an explicit override is supplied --
        // otherwise the override would be silently ignored if the loaded params
        // had searchArea="selected" or "all".
        return new FiberAnalysisParams(
                "class",
                override,
                base.borderZoneUm(),
                base.zoneMode(),
                base.useImagePixelSize(),
                base.pixelSizeOverrideUm(),
                base.segSource(),
                base.internalChannel(),
                base.thresholdMethod(),
                base.manualThreshold(),
                base.ridgeFilter(),
                base.sigmaMinUm(),
                base.sigmaMaxUm(),
                base.sigmaStepUm(),
                base.minFiberAreaUm2(),
                base.projectCalibrationName(),
                base.invertIntensity(),
                base.rollingBallRadiusUm(),
                base.maskSource(),
                base.classifierName(),
                base.objectClass(),
                base.maskFile(),
                base.windowEnabled(),
                base.windowSizeUm(),
                base.windowOverlapPercent(),
                base.windowObjects(),
                base.minWindowCoveragePercent(),
                base.straightnessEnabled(),
                base.tortuosityOn(),
                base.radonOn(),
                base.minBranchUm(),
                base.morphEnabled(),
                base.branchpoints(),
                base.endpoints(),
                base.length(),
                base.curvature(),
                base.hdm(),
                base.lacunarity(),
                base.fractal(),
                base.gapAnalysis(),
                base.lacBoxSizesUm(),
                base.fractalBoxSizesUm(),
                base.textureEnabled(),
                base.quantLevels(),
                base.glcmDistancesUm(),
                base.contrast(),
                base.correlation(),
                base.energy(),
                base.homogeneity(),
                base.entropy(),
                base.dissimilarity(),
                base.outputDir(),
                base.fiberMaskOverlay(),
                base.straightnessHeatmap(),
                base.glcmHeatmap(),
                base.glcmHeatmapProp(),
                base.morphSummary(),
                base.jsonSidecar(),
                base.emitNpz(),
                base.collagenObjects());
    }

    private void runBatch(FiberAnalysisParams params, List<ProjectImageEntry<BufferedImage>> entries) {
        Thread t = new Thread(
                () -> {
                    int ok = 0;
                    int skipped = 0;
                    int failed = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        ProjectImageEntry<BufferedImage> entry = entries.get(i);
                        String name = entry.getImageName();
                        logger.info("Batch [{}/{}]: {}", i + 1, entries.size(), name);
                        try {
                            ImageData<BufferedImage> data = entry.readImageData();
                            List<PathObject> annotations = resolveAnnotations(params, data);
                            if (annotations.isEmpty()) {
                                logger.info("Skipping '{}': no matching annotations", name);
                                skipped++;
                                continue;
                            }
                            // Block on this image's run before advancing -- runForAnnotations
                            // returns the worker Thread so the batch can join it instead of
                            // racing the next image's readImageData against the previous
                            // image's Appose call.
                            Thread worker = new FiberAnalysisWorkflow(resultPanel)
                                    .runForAnnotations(params, annotations, data, gui);
                            if (worker != null) {
                                worker.join();
                            }
                            ok++;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            failed++;
                            logger.warn("Batch interrupted at '{}'", name);
                            break;
                        } catch (IOException ioe) {
                            failed++;
                            logger.error("Batch entry '{}' failed to read: {}", name, ioe.getMessage());
                        } catch (Exception ex) {
                            failed++;
                            logger.error("Batch entry '{}' failed", name, ex);
                        }
                    }
                    final int okF = ok;
                    final int skipF = skipped;
                    final int failF = failed;
                    Platform.runLater(() -> Dialogs.showInfoNotification(
                            "Fiber Analysis Batch",
                            String.format("Done. ran=%d, skipped=%d, failed=%d", okF, skipF, failF)));
                },
                "FiberAnalysisBatch");
        t.setDaemon(true);
        t.start();
    }

    private static List<PathObject> resolveAnnotations(FiberAnalysisParams params, ImageData<BufferedImage> data) {
        if (data == null || data.getHierarchy() == null) return List.of();
        List<PathObject> all = data.getHierarchy().getAnnotationObjects().stream()
                .filter(o -> o != null && o.isAnnotation())
                .collect(Collectors.toList());
        String mode = params.searchArea() == null ? "selected" : params.searchArea();
        switch (mode) {
            case "all":
                return all;
            case "class":
                Set<String> wanted = parseClassFilter(params.classFilter());
                if (wanted.isEmpty()) return List.of();
                return all.stream()
                        .filter(o -> {
                            PathClass pc = o.getPathClass();
                            return pc != null && pc.getName() != null && wanted.contains(pc.getName());
                        })
                        .collect(Collectors.toList());
            case "selected":
            default:
                // In batch context "selected" makes no sense -- a project entry is
                // not the live viewer. Fall back to "all" with a one-line log.
                logger.info("Batch run: search area was 'selected'; falling back to all annotations on the image.");
                return all;
        }
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

    /**
     * Reads a params.json (as produced by {@link FiberAnalysisWorkflow#writeParamsFiles})
     * back into a {@link FiberAnalysisParams}. Tolerates the extra provenance
     * keys ({@code run_timestamp}, {@code params_hash}, {@code extension_version},
     * {@code image_name}) by ignoring them.
     */
    static FiberAnalysisParams loadParamsFromJson(Path jsonFile) throws IOException {
        String body = Files.readString(jsonFile);
        JsonObject obj = new Gson().fromJson(body, JsonObject.class);
        return new FiberAnalysisParams(
                str(obj, "searchArea", "selected"),
                str(obj, "classFilter", ""),
                num(obj, "borderZoneUm", 50.0),
                str(obj, "zoneMode", "outside"),
                bool(obj, "useImagePixelSize", true),
                num(obj, "pixelSizeOverrideUm", 0.5),
                str(obj, "segSource", "internal"),
                str(obj, "internalChannel", "Value (HSV)"),
                str(obj, "thresholdMethod", "Otsu"),
                (int) num(obj, "manualThreshold", 128),
                str(obj, "ridgeFilter", "None"),
                num(obj, "sigmaMinUm", 1.0),
                num(obj, "sigmaMaxUm", 4.0),
                num(obj, "sigmaStepUm", 1.0),
                num(obj, "minFiberAreaUm2", 1.0),
                str(obj, "projectCalibrationName", ""),
                bool(obj, "invertIntensity", false),
                num(obj, "rollingBallRadiusUm", 0.0),
                str(obj, "maskSource", "Pixel classifier"),
                str(obj, "classifierName", ""),
                str(obj, "objectClass", ""),
                str(obj, "maskFile", ""),
                bool(obj, "windowEnabled", true),
                num(obj, "windowSizeUm", 15.0),
                (int) num(obj, "windowOverlapPercent", 0),
                bool(obj, "windowObjects", false),
                num(obj, "minWindowCoveragePercent", 5.0),
                bool(obj, "straightnessEnabled", true),
                bool(obj, "tortuosityOn", true),
                bool(obj, "radonOn", true),
                num(obj, "minBranchUm", 5.0),
                bool(obj, "morphEnabled", true),
                bool(obj, "branchpoints", true),
                bool(obj, "endpoints", true),
                bool(obj, "length", true),
                bool(obj, "curvature", true),
                bool(obj, "hdm", true),
                bool(obj, "lacunarity", true),
                bool(obj, "fractal", true),
                bool(obj, "gapAnalysis", true),
                str(obj, "lacBoxSizesUm", "0.5,1,2,4,8"),
                str(obj, "fractalBoxSizesUm", "0.25,0.5,1,2,4,8,16"),
                bool(obj, "textureEnabled", true),
                (int) num(obj, "quantLevels", 16),
                str(obj, "glcmDistancesUm", "0.1,0.2,0.3"),
                bool(obj, "contrast", true),
                bool(obj, "correlation", true),
                bool(obj, "energy", true),
                bool(obj, "homogeneity", true),
                bool(obj, "entropy", true),
                bool(obj, "dissimilarity", true),
                str(obj, "outputDir", ""),
                bool(obj, "fiberMaskOverlay", true),
                bool(obj, "straightnessHeatmap", true),
                bool(obj, "glcmHeatmap", true),
                str(obj, "glcmHeatmapProp", "contrast"),
                bool(obj, "morphSummary", true),
                bool(obj, "jsonSidecar", true),
                bool(obj, "emitNpz", false),
                bool(obj, "collagenObjects", true));
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static double num(JsonObject o, String key, double def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
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

    private static Button buildButton(String text, Runnable r) {
        Button b = new Button(text);
        b.setOnAction(e -> r.run());
        return b;
    }

    /** Per-row state in the image list: the entry + the checkbox state. */
    private final class EntryRow {
        final ProjectImageEntry<BufferedImage> entry;
        final SimpleBooleanProperty checked = new SimpleBooleanProperty(false);

        EntryRow(ProjectImageEntry<BufferedImage> entry) {
            this.entry = entry;
            this.checked.addListener((o, a, b) -> {
                // Keep the count label fresh.
                if (countLabel != null) refreshList();
            });
        }
    }

    /** Renders one EntryRow as "[x] Image name (annotations: N)". */
    private final class EntryRowCell extends ListCell<EntryRow> {
        private final CheckBox check = new CheckBox();
        private final Label nameLbl = new Label();
        private final HBox box = new HBox(8, check, nameLbl);

        EntryRowCell() {
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(EntryRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            check.selectedProperty().bindBidirectional(item.checked);
            nameLbl.setText(item.entry.getImageName());
            setGraphic(box);
        }
    }
}
