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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.AnnotationClassFilter;
import qupath.ext.fiberanalysis.analysis.HeadlessFx;
import qupath.ext.fiberanalysis.analysis.PooledThreshold;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.ui.SectionBuilder;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Train a window classifier from the pathologist's own annotations.
 *
 * <p>Two things this dialog deliberately does not do. It has no segmentation
 * controls: those live in the Run dialog, where they can be previewed, and
 * the preferences are the single source of truth (the density-map dialog
 * already works this way). And it has no class list of its own -- the classes
 * are whatever PathClasses the user drew with, so the taxonomy is theirs.
 *
 * <p>Each image is one cross-validation group. That is the claim the report
 * will make -- "it works on a slide it has not seen" -- and no other grouping
 * estimates it, so the dialog refuses a training set that cannot support it
 * rather than producing a number that cannot mean what it looks like.
 */
public final class FiberWindowTrainDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberWindowTrainDialog.class);

    private static final String TITLE = "Fiber Analysis -- Train window classifier";

    private final QuPathGUI gui;

    private ListView<ImageRow> imageList;
    private ListView<String> classList;
    private TextField modelNameField;
    private CheckBox projectLocalCheck;
    private ComboBox<String> modelKindCombo;
    private TextField radiiField;
    private Spinner<Integer> permSpinner;
    private Spinner<Integer> bootSpinner;
    private Spinner<Double> puritySpinner;
    private Label geometryLabel;
    private Label thresholdWarning;
    private TextArea reportArea;
    private Button trainBtn;

    public FiberWindowTrainDialog(QuPathGUI gui) {
        this.gui = gui;
    }

    /** Open the dialog. Requires an open project with annotated images. */
    public void show() {
        Project<BufferedImage> project = gui == null ? null : gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "Open a project first. A classifier is trained across images,"
                            + " and each image is one cross-validation group.");
            return;
        }

        Stage dialog = new Stage();
        dialog.setTitle(TITLE);
        if (gui.getStage() != null) dialog.initOwner(gui.getStage());

        VBox root = new VBox(8, buildBody(project), buildFooter(dialog));
        root.setPadding(new Insets(12));
        dialog.setScene(new Scene(root, 860, 700));
        dialog.setMinWidth(720);
        dialog.setMinHeight(560);
        refreshGeometry();
        dialog.show();
    }

    // ---------- layout ----------

    private VBox buildBody(Project<BufferedImage> project) {
        imageList = new ListView<>(FXCollections.observableArrayList(collectImages(project)));
        imageList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        imageList.setPrefHeight(110);
        for (ImageRow row : imageList.getItems()) {
            if (row.annotationCount > 0) imageList.getSelectionModel().select(row);
        }
        imageList.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<ImageRow>)
                c -> refreshClasses());

        classList = new ListView<>();
        classList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        classList.setPrefHeight(90);
        refreshClasses();

        geometryLabel = wrapping(new Label());
        thresholdWarning = wrapping(new Label());
        thresholdWarning.setStyle("-fx-text-fill: -fx-accent;");

        modelNameField = new TextField("window-classifier");
        projectLocalCheck = new CheckBox("Save inside this project");
        projectLocalCheck.setSelected(true);
        modelKindCombo = new ComboBox<>(FXCollections.observableArrayList("hist_gradient_boosting", "random_forest"));
        modelKindCombo.getSelectionModel().selectFirst();
        radiiField = new TextField("1,3");
        permSpinner = new Spinner<>(0, 2000, 200, 50);
        permSpinner.setEditable(true);
        bootSpinner = new Spinner<>(0, 20000, 2000, 500);
        bootSpinner.setEditable(true);
        puritySpinner = new Spinner<>(0.5, 1.0, 1.0, 0.05);
        puritySpinner.setEditable(true);

        GridPane settings = new GridPane();
        settings.setHgap(8);
        settings.setVgap(6);
        int r = 0;
        settings.addRow(r++, new Label("Model name"), modelNameField);
        settings.addRow(r++, new Label(""), projectLocalCheck);
        settings.addRow(r++, new Label("Model"), modelKindCombo);
        settings.addRow(r++, new Label("Context radii (windows)"), radiiField);
        settings.addRow(r++, new Label("Core purity"), puritySpinner);
        settings.addRow(r++, new Label("Permutation shuffles"), permSpinner);
        settings.addRow(r++, new Label("Bootstrap resamples"), bootSpinner);

        reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setWrapText(true);
        reportArea.setPrefRowCount(10);
        reportArea.setPromptText("The validation report appears here. It is written whatever the verdict:"
                + " refusing to predict is not refusing to inform.");

        // The report sits OUTSIDE the scroll pane. Inside it, "Check
        // training set" wrote its answer below the fold and the button
        // looked like it had done nothing -- the same defect as a required
        // checkbox folded into a collapsed section.
        VBox scrolled = new VBox(
                8,
                SectionBuilder.createSection("Images (one cross-validation group each)", true, imageList),
                SectionBuilder.createSection("Classes to train", true, classList),
                SectionBuilder.createSection(
                        "Geometry (from the Run dialog's settings)",
                        true,
                        new VBox(4, geometryLabel, thresholdWarning)),
                SectionBuilder.createSection("Model and validation", true, settings));
        ScrollPane scroll = new ScrollPane(scrolled);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(380);
        VBox wrapper = new VBox(8, scroll, SectionBuilder.createSection("Report", true, reportArea));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private HBox buildFooter(Stage dialog) {
        trainBtn = new Button("Train");
        trainBtn.setDefaultButton(true);
        trainBtn.setOnAction(e -> onTrain());
        Button check = new Button("Check training set");
        check.setOnAction(e -> reportArea.setText(preflight()));
        Button close = new Button("Close");
        close.setOnAction(e -> dialog.close());
        HBox footer = new HBox(8, check, trainBtn, close);
        footer.setPadding(new Insets(8, 0, 0, 0));
        return footer;
    }

    /** A label that wraps instead of ellipsising -- these carry the warnings. */
    private static Label wrapping(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }

    // ---------- state ----------

    private void refreshGeometry() {
        double windowUm = FiberAnalysisPreferences.windowSizeUmProperty().get();
        int overlap = FiberAnalysisPreferences.windowOverlapPercentProperty().get();
        SegmentationSpec seg = SegmentationSpec.fromPreferences();
        geometryLabel.setText(String.format(
                Locale.ROOT,
                "Window %.1f um at %d%% overlap, so each prediction is attributed to a core of"
                        + " %.1f um at the window's centre. Segmentation: %s on %s.",
                windowUm,
                overlap,
                windowUm * (1.0 - overlap / 100.0),
                seg.thresholdDescription(),
                seg.segChannel()));
        thresholdWarning.setText(
                seg.hasGlobalThreshold() || PooledThreshold.needsPooling(seg.thresholdMethod())
                        ? ""
                        : "This threshold is recomputed from each tile's own histogram, so neighbouring"
                                + " tiles over the same tissue can segment it differently and the model can"
                                + " learn the seams. Use Otsu, Triangle, a manual threshold or a project"
                                + " calibration.");
    }

    private void refreshClasses() {
        if (classList == null) return;
        Set<String> names = new TreeSet<>();
        for (ImageRow row : imageList.getSelectionModel().getSelectedItems()) {
            names.addAll(row.classNames);
        }
        // An unclassified annotation is an unlabelled region, not a class.
        // Letting it through trains a garbage class that wins on every bit of
        // novel tissue, so it is not offered.
        names.remove(AnnotationClassFilter.UNCLASSIFIED);
        List<String> previous = new ArrayList<>(classList.getSelectionModel().getSelectedItems());
        classList.setItems(FXCollections.observableArrayList(names));
        for (String p : previous) {
            if (names.contains(p)) classList.getSelectionModel().select(p);
        }
        if (classList.getSelectionModel().isEmpty()) {
            classList.getSelectionModel().selectAll();
        }
    }

    private List<ImageRow> collectImages(Project<BufferedImage> project) {
        List<ImageRow> rows = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            Set<String> classes = new LinkedHashSet<>();
            int count = 0;
            try {
                var hierarchy = entry.readHierarchy();
                for (PathObject obj : hierarchy.getAnnotationObjects()) {
                    if (obj.getROI() == null) continue;
                    count++;
                    classes.add(AnnotationClassFilter.displayName(obj));
                }
            } catch (Exception ex) {
                logger.warn("Could not read annotations for {}: {}", entry.getImageName(), ex.getMessage());
            }
            rows.add(new ImageRow(entry, count, classes));
        }
        return rows;
    }

    /** Count what the user has, without computing a single feature. */
    private String preflight() {
        List<String> classes = new ArrayList<>(classList.getSelectionModel().getSelectedItems());
        List<ImageRow> images = new ArrayList<>(imageList.getSelectionModel().getSelectedItems());
        if (classes.isEmpty() || images.isEmpty()) {
            return "Select at least one image and two classes.";
        }
        // Annotation and slide counts are exact. The window count is an upper
        // bound -- it assumes every core inside an annotation is pure, which
        // the real labelling will whittle down -- so a BLOCK here is certain
        // and an OK here is not a guarantee.
        double windowUm = FiberAnalysisPreferences.windowSizeUmProperty().get();
        int overlap = FiberAnalysisPreferences.windowOverlapPercentProperty().get();
        double coreUm = Math.max(1e-6, windowUm * (1.0 - overlap / 100.0));
        Map<String, int[]> tally = new LinkedHashMap<>(); // windows, annotations, slides
        Map<String, Set<String>> slidesPerClass = new LinkedHashMap<>();
        for (String c : classes) {
            tally.put(c, new int[] {0, 0});
            slidesPerClass.put(c, new LinkedHashSet<>());
        }
        for (ImageRow row : images) {
            try {
                var hierarchy = row.entry.readHierarchy();
                for (PathObject obj : hierarchy.getAnnotationObjects()) {
                    String name = AnnotationClassFilter.displayName(obj);
                    if (!tally.containsKey(name) || obj.getROI() == null) continue;
                    double areaUm2 = obj.getROI().getArea() * row.pixelSizeUm() * row.pixelSizeUm();
                    tally.get(name)[0] += (int) Math.floor(areaUm2 / (coreUm * coreUm));
                    tally.get(name)[1]++;
                    slidesPerClass.get(name).add(row.entry.getImageName());
                }
            } catch (Exception ex) {
                logger.warn("Pre-flight could not read {}: {}", row.entry.getImageName(), ex.getMessage());
            }
        }
        List<TrainingViability.ClassTally> tallies = new ArrayList<>();
        for (String c : classes) {
            tallies.add(new TrainingViability.ClassTally(
                    c, tally.get(c)[0], tally.get(c)[1], slidesPerClass.get(c).size()));
        }
        var report = TrainingViability.check(tallies, images.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Upper-bound counts (cores are counted as if every one were pure):\n");
        for (var t : tallies) {
            sb.append(String.format(
                    Locale.ROOT,
                    "  %-28s up to %,7d cores from %3d annotation(s) on %d slide(s)%n",
                    t.className(),
                    t.windows(),
                    t.annotations(),
                    t.groups()));
        }
        sb.append('\n').append(report.summary());
        return sb.toString();
    }

    // ---------- run ----------

    private void onTrain() {
        List<String> classes = new ArrayList<>(classList.getSelectionModel().getSelectedItems());
        List<ImageRow> images = new ArrayList<>(imageList.getSelectionModel().getSelectedItems());
        if (classes.size() < 2) {
            Dialogs.showErrorMessage(TITLE, "Select at least two classes; there is nothing to separate.");
            return;
        }
        if (images.isEmpty()) {
            Dialogs.showErrorMessage(TITLE, "Select at least one image.");
            return;
        }
        Project<BufferedImage> project = gui.getProject();
        Path modelDir;
        try {
            modelDir = WindowClassifierModelManager.resolveForWrite(
                    project, modelNameField.getText(), projectLocalCheck.isSelected());
        } catch (Exception ex) {
            Dialogs.showErrorMessage(TITLE, ex.getMessage());
            return;
        }

        var preflightReport = preflight();
        reportArea.setText(preflightReport);
        if (preflightReport.contains("[BLOCK]")) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "This training set cannot support a slide-level estimate."
                            + " See the report; every finding names what to do about it.");
            return;
        }

        trainBtn.setDisable(true);
        var progress = new ClassifierProgress(gui.getStage(), TITLE);
        Thread worker =
                new Thread(() -> runTraining(project, images, classes, modelDir, progress), "fiber-window-train");
        worker.setDaemon(true);
        worker.start();
    }

    private void runTraining(
            Project<BufferedImage> project,
            List<ImageRow> images,
            List<String> classes,
            Path modelDir,
            ClassifierProgress progress) {
        try {
            // One threshold for the whole training set, stored in the model so
            // Apply cuts every slide at the same value. Thresholding each tile
            // on its own histogram would let the model learn the seams.
            SegmentationSpec seg = SegmentationSpec.fromPreferences();
            String thresholdSource = null;
            if (PooledThreshold.needsPooling(seg.thresholdMethod())) {
                progress.status("Pooling one threshold over the training images...");
                Path poolDir = WindowClassifierWorkflow.createWorkDir();
                var pooled = PooledThreshold.pool(
                        images.stream().map(r -> r.entry).toList(), seg, poolDir, progress::status);
                seg = seg.withPooledThreshold(pooled.thresholdNorm());
                thresholdSource = PooledThreshold.describe(pooled);
            }
            var request = buildRequest(seg);
            List<WindowClassifierWorkflow.TrainingContribution> contributions = new ArrayList<>();
            int group = 0;
            for (ImageRow row : images) {
                if (progress.cancelled()) throw new InterruptedException("cancelled");
                progress.status("Features: " + row.entry.getImageName());
                ImageData<BufferedImage> data = row.entry.readImageData();
                var region = RegionRequest.createInstance(
                        data.getServer().getPath(),
                        1.0,
                        0,
                        0,
                        data.getServer().getWidth(),
                        data.getServer().getHeight());
                var features = WindowClassifierWorkflow.computeFeatures(
                        data, region, request, progress::status, progress::cancelled);
                var labels = WindowClassifierWorkflow.labelCores(
                        data.getHierarchy().getAnnotationObjects().stream().toList(),
                        classes,
                        features,
                        region,
                        puritySpinner.getValue());
                contributions.add(new WindowClassifierWorkflow.TrainingContribution(features, labels, group++));
            }

            progress.status("Validating and fitting (the permutation null is the slow part)...");
            Map<String, Object> geometry = buildGeometry(images.get(0).pixelSizeUm(), seg, thresholdSource);
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("model_kind", modelKindCombo.getValue());
            options.put("n_perm", permSpinner.getValue());
            options.put("n_boot", bootSpinner.getValue());
            var result = WindowClassifierWorkflow.train(contributions, classes, geometry, modelDir, options);

            StringBuilder sb = new StringBuilder(result.summary());
            sb.append("\n\nCores not used, by reason:\n");
            result.rejections()
                    .forEach((k, v) ->
                            sb.append(String.format(Locale.ROOT, "  %-22s %,d%n", k.toLowerCase(Locale.ROOT), v)));
            if (result.modelDir() != null) {
                sb.append("\nSaved to ").append(result.modelDir());
            }
            String text = sb.toString();
            HeadlessFx.runLater(() -> reportArea.setText(text));
            logger.info("Window classifier training finished: {}", result.verdict());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            HeadlessFx.runLater(() -> reportArea.setText("Cancelled."));
        } catch (Exception ex) {
            logger.error("Window classifier training failed", ex);
            HeadlessFx.runLater(() -> {
                reportArea.setText("Training failed: " + ex.getMessage());
                Dialogs.showErrorMessage(TITLE, String.valueOf(ex.getMessage()));
            });
        } finally {
            progress.close();
            HeadlessFx.runLater(() -> trainBtn.setDisable(false));
        }
    }

    private WindowClassifierWorkflow.Request buildRequest(SegmentationSpec seg) throws java.io.IOException {
        var featureSpec = WindowFeatureMatrix.Spec.defaults(
                seg,
                FiberAnalysisPreferences.windowSizeUmProperty().get(),
                FiberAnalysisPreferences.windowOverlapPercentProperty().get());
        return new WindowClassifierWorkflow.Request(
                featureSpec,
                parseRadii(radiiField.getText()),
                puritySpinner.getValue(),
                WindowClassifierWorkflow.createWorkDir());
    }

    private Map<String, Object> buildGeometry(double pixelSizeUm, SegmentationSpec seg, String thresholdSource) {
        double windowUm = FiberAnalysisPreferences.windowSizeUmProperty().get();
        int overlap = FiberAnalysisPreferences.windowOverlapPercentProperty().get();
        int windowPx = Math.max(2, (int) Math.round(windowUm / pixelSizeUm));
        int stridePx = Math.max(1, (int) Math.round(windowPx * (1.0 - overlap / 100.0)));
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("window_um", windowUm);
        geometry.put("pixel_size_um", pixelSizeUm);
        geometry.put("window_px", windowPx);
        geometry.put("stride_px", stridePx);
        geometry.put("core_px", stridePx);
        geometry.put("context_radii", radiiField.getText());
        geometry.put("segmentation_hash", seg.hash());
        // The settings themselves, so Apply segments new slides exactly as
        // the training slides were, pooled threshold included.
        geometry.put("segmentation", seg.toMap());
        if (thresholdSource != null) geometry.put("threshold_source", thresholdSource);
        return geometry;
    }

    static List<Integer> parseRadii(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        for (String tok : text.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try {
                int r = Integer.parseInt(t);
                if (r > 0) out.add(r);
            } catch (NumberFormatException ignore) {
                // A radius that is not a number is simply not a radius; the
                // field's tooltip says what it wants and an empty list means
                // "no context", which is a legitimate choice.
            }
        }
        return out;
    }

    /** One project image, with what it can contribute. */
    private static final class ImageRow {
        final ProjectImageEntry<BufferedImage> entry;
        final int annotationCount;
        final Set<String> classNames;

        ImageRow(ProjectImageEntry<BufferedImage> entry, int annotationCount, Set<String> classNames) {
            this.entry = entry;
            this.annotationCount = annotationCount;
            this.classNames = classNames;
        }

        double pixelSizeUm() {
            try {
                var cal = entry.readImageData().getServer().getPixelCalibration();
                return cal != null && cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 1.0;
            } catch (Exception ex) {
                return 1.0;
            }
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "%s  --  %d annotation(s), %d class(es)",
                    entry.getImageName(),
                    annotationCount,
                    classNames.size());
        }
    }
}
