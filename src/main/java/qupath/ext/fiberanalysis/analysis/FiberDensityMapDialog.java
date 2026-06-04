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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
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
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Project-wide density-map output dialog. Whole-slide tile-stream of the
 * per-window fiber metrics into a uint16 quantized pyramid OME-TIFF sidecar,
 * with the user choosing whether to attach it back to the source image as
 * channels (changes RGB display) or keep it as a sampling-only sidecar
 * (preserves RGB display).
 *
 * <p>UX shape (per design discussion):
 *
 * <ul>
 *   <li><b>Split pane</b>: image picker on the left, settings on the right.
 *       Live validation banner at the top of the settings pane updates as
 *       images are checked / output mode is toggled.</li>
 *   <li><b>Image-select-first</b>: the user must pick images before settings
 *       can be validated; the picker lists each entry's pixel type,
 *       dimensions, and pixel-calibration status so the legality rules below
 *       are obvious.</li>
 *   <li><b>Mode validation</b>: <i>uint8 RGB</i> picking Channels triggers a
 *       warning (forces RGB display to multi-channel); mixed-type selection
 *       + Channels mode is blocked; any image missing pixel calibration
 *       blocks the run entirely.</li>
 * </ul>
 *
 * <p>This class is the UX scaffold; the compute pipeline + sidecar writer +
 * reattach hook + sampling command live in their own classes, dispatched
 * from {@link #onRun()}. Skeleton step writes "not yet implemented" so the
 * UX can be reviewed before the compute side lands.
 */
public final class FiberDensityMapDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberDensityMapDialog.class);

    private static final String MODE_CHANNELS = "Attach as channels";
    private static final String MODE_SIDECAR = "Sidecar + sampling commands";

    private final QuPathGUI gui;

    private TableView<ImageRow> imageTable;
    private ObservableList<ImageRow> rows;

    private ToggleGroup outputModeGroup;
    private RadioButton modeChannelsRadio;
    private RadioButton modeSidecarRadio;
    private CheckBox autoReattachCheck;

    private Label validationBanner;
    private VBox validationBox;
    private Button runBtn;

    private final ConcurrentHashMap<String, ImageMeta> metaCache = new ConcurrentHashMap<>();

    public FiberDensityMapDialog(QuPathGUI gui) {
        this.gui = gui;
    }

    public void show() {
        Stage dialog = new Stage();
        dialog.setTitle("Fiber Analysis -- Project density map (research use only)");
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }

        Project<BufferedImage> project = gui != null ? gui.getProject() : null;
        if (project == null) {
            Dialogs.showErrorMessage("Fiber Analysis density map", "Open a project first.");
            return;
        }

        rows = FXCollections.observableArrayList();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            rows.add(new ImageRow(entry));
        }

        SplitPane split = new SplitPane();
        split.getItems().addAll(buildImagePane(), buildSettingsPane());
        split.setDividerPositions(0.5);

        VBox root = new VBox(0, split, buildFooter(dialog));
        VBox.setVgrow(split, Priority.ALWAYS);

        Scene scene = new Scene(root, 1100, 680);
        dialog.setScene(scene);
        dialog.setMinWidth(900);
        dialog.setMinHeight(540);

        // Kick off the background pixel-type detector so the table shows real
        // type / dimension info within a few seconds of dialog open.
        runMetaPopulator(project);

        // Initial validation pass (covers the case where the project is empty
        // or every image has the same starting state).
        revalidate();
        dialog.show();
    }

    // ---------- left pane: image picker ----------

    private VBox buildImagePane() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));

        Label hdr = new Label("Images");
        hdr.setFont(Font.font("System", FontWeight.BOLD, 13));
        Label sub = new Label("Select the project images to compute a density-map sidecar for. "
                + "Type / dimensions / calibration populate in the background.");
        sub.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");
        sub.setWrapText(true);

        imageTable = new TableView<>(rows);
        imageTable.setPlaceholder(new Label("This project has no images."));

        TableColumn<ImageRow, Boolean> selCol = new TableColumn<>("Run");
        selCol.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setEditable(true);
        selCol.setPrefWidth(36);
        selCol.setSortable(false);

        TableColumn<ImageRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(220);

        TableColumn<ImageRow, String> typeCol = new TableColumn<>("Pixel type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("pixelTypeText"));
        typeCol.setPrefWidth(120);

        TableColumn<ImageRow, String> dimCol = new TableColumn<>("Dimensions");
        dimCol.setCellValueFactory(new PropertyValueFactory<>("dimensionsText"));
        dimCol.setPrefWidth(120);

        TableColumn<ImageRow, String> calCol = new TableColumn<>("Calibrated");
        calCol.setCellValueFactory(new PropertyValueFactory<>("calibrationText"));
        calCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty && item != null && item.startsWith("No")) {
                    setStyle("-fx-text-fill: #b04020; -fx-font-weight: bold;");
                } else {
                    setStyle(null);
                }
            }
        });
        calCol.setPrefWidth(110);

        imageTable.getColumns().addAll(selCol, nameCol, typeCol, dimCol, calCol);
        imageTable.setEditable(true);
        VBox.setVgrow(imageTable, Priority.ALWAYS);

        HBox quickButtons = new HBox(
                8,
                buildSmallButton("Select all", () -> setAllSelected(true)),
                buildSmallButton("Select none", () -> setAllSelected(false)));

        box.getChildren().addAll(hdr, sub, imageTable, quickButtons);

        // Re-validate whenever a checkbox flips. ListChangeListener on rows
        // would also need to add per-row listeners, so we attach individually.
        for (ImageRow row : rows) {
            row.selectedProperty().addListener((obs, oldV, newV) -> revalidate());
        }
        return box;
    }

    private static Button buildSmallButton(String label, Runnable action) {
        Button b = new Button(label);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void setAllSelected(boolean v) {
        for (ImageRow r : rows) r.selectedProperty().set(v);
    }

    // ---------- right pane: settings + validation ----------

    private VBox buildSettingsPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        Label hdr = new Label("Settings");
        hdr.setFont(Font.font("System", FontWeight.BOLD, 13));

        validationBanner = new Label("");
        validationBanner.setWrapText(true);
        validationBox = new VBox(validationBanner);
        validationBox.setPadding(new Insets(8));
        validationBox.setStyle(emptyBannerStyle());

        // --- Output mode ---
        Label modeLabel = new Label("Output mode");
        modeLabel.setStyle("-fx-font-weight: bold;");
        outputModeGroup = new ToggleGroup();
        modeChannelsRadio = new RadioButton(MODE_CHANNELS);
        modeChannelsRadio.setToggleGroup(outputModeGroup);
        modeSidecarRadio = new RadioButton(MODE_SIDECAR);
        modeSidecarRadio.setToggleGroup(outputModeGroup);
        modeSidecarRadio.setSelected(true); // default: safer for RGB base images
        outputModeGroup.selectedToggleProperty().addListener((obs, o, n) -> revalidate());

        Label modeHelp =
                new Label("Channels:  density appears as extra channels on the source image and is sampleable\n"
                        + "via Analyze > Calculate Features > Add intensity features. For RGB base\n"
                        + "images this changes how the image renders (R/G/B become separate display\n"
                        + "channels; we auto-configure to recreate the RGB look on attach).\n\n"
                        + "Sidecar:  density is written to a pyramid OME-TIFF beside the project\n"
                        + "and exposed via the \"Sample density into measurements\" command in\n"
                        + "this extension. RGB display stays native; works for every image type.");
        modeHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        modeHelp.setWrapText(true);

        // --- Cross-session opt-in (only meaningful in Channels mode) ---
        autoReattachCheck = new CheckBox("Auto-reattach channels on image open");
        autoReattachCheck.setTooltip(new Tooltip(
                "When checked, a small marker file is written beside the sidecar. The\n"
                        + "extension's image-open listener reads it on every future open of this\n"
                        + "image (in this project) and re-attaches the density channels without\n"
                        + "asking. To stop, run \"Stop auto-reattaching density channels\" or\n"
                        + "delete the .attach marker file beside the sidecar."));
        autoReattachCheck.disableProperty().bind(modeSidecarRadio.selectedProperty());
        Label autoReattachHelp = new Label(
                "Marker file: <sidecar>.attach (a few hundred bytes). Sidecar mode ignores\n"
                        + "this checkbox -- the sampling command does not need an attach.");
        autoReattachHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        autoReattachHelp.setWrapText(true);
        autoReattachHelp.disableProperty().bind(modeSidecarRadio.selectedProperty());

        // --- Window grid (placeholder; will read from defaults later) ---
        Label windowLabel = new Label("Window grid (microns)");
        windowLabel.setStyle("-fx-font-weight: bold;");
        Label windowHelp = new Label("Window size, overlap, and minimum coverage gate are inherited from your last\n"
                + "fiber-analysis Run. The defaults are loaded here; advanced controls land\n"
                + "in a later iteration of this dialog.");
        windowHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        windowHelp.setWrapText(true);

        // --- Sidecar location ---
        Label sidecarLabel = new Label("Sidecar location");
        sidecarLabel.setStyle("-fx-font-weight: bold;");
        Label sidecarBody = new Label("Per image, a tiled uint16 pyramid OME-TIFF is written to\n"
                + "    <project>/fiber-analysis/density-maps/<image>_density.ome.tif\n"
                + "LZW-compressed; empty regions encoded as the no-data sentinel (raw=0,\n"
                + "channel scale + offset preserved in OME-XML so real units round-trip).\n"
                + "Expected size for a 100k x 100k slide with 8 channels: ~10-40 MB per image.");
        sidecarBody.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        sidecarBody.setWrapText(true);

        VBox modeBox = new VBox(
                4, modeLabel, modeChannelsRadio, modeSidecarRadio, modeHelp, autoReattachCheck, autoReattachHelp);
        VBox winBox = new VBox(4, windowLabel, windowHelp);
        VBox sideBox = new VBox(4, sidecarLabel, sidecarBody);

        VBox content = new VBox(12, validationBox, modeBox, new Separator(), winBox, new Separator(), sideBox);
        content.setPadding(new Insets(0));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(hdr, scroll);
        return box;
    }

    private HBox buildFooter(Stage dialog) {
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dialog.close());

        runBtn = new Button("Compute density maps");
        runBtn.setDefaultButton(true);
        runBtn.setTooltip(new Tooltip("Tile-stream each selected image, write a uint16 quantized pyramid OME-TIFF\n"
                + "sidecar per image, and either attach as channels (Channels mode) or\n"
                + "expose via the sampling command (Sidecar mode)."));
        runBtn.setOnAction(e -> onRun());
        runBtn.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, cancelBtn, runBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 12, 10, 12));
        return footer;
    }

    private void onRun() {
        Project<BufferedImage> project = gui != null ? gui.getProject() : null;
        if (project == null) {
            Dialogs.showErrorMessage("Fiber density map", "No open project.");
            return;
        }
        List<ImageRow> picked = pickedRows();
        if (picked.isEmpty()) return;

        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>(picked.size());
        for (ImageRow r : picked) entries.add(r.entry);

        // v1 reads the persisted segmentation + window prefs (the user's last
        // run from the per-annotation dialog). A follow-up iteration adds
        // controls to this dialog for window size / overlap / channel
        // selection; right now the prefs ARE the source of truth so the user
        // can review the segmentation behaviour in the regular Run dialog
        // before kicking off the whole-slide density compute.
        Double projectThresholdNorm = null;
        String thrMethod = FiberAnalysisPreferences.thresholdMethodProperty().get();
        if ("Project Otsu (calibrated)".equals(thrMethod)) {
            String calName =
                    FiberAnalysisPreferences.projectCalibrationNameProperty().get();
            if (calName != null && !calName.isBlank()) {
                projectThresholdNorm = FiberAnalysisWorkflow.loadCalibratedThreshold(calName);
            }
            // Always submit lowercase canonical method name to the Python side.
            thrMethod = "project_otsu";
        } else {
            thrMethod = thrMethod.toLowerCase(Locale.ROOT);
        }

        boolean channelsMode = modeChannelsRadio != null && modeChannelsRadio.isSelected();
        boolean autoReattach = channelsMode && autoReattachCheck != null && autoReattachCheck.isSelected();

        FiberDensityMapWorkflow.DensityMapJobSpec spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                FiberAnalysisPreferences.windowSizeUmProperty().get(),
                FiberAnalysisPreferences.windowOverlapPercentProperty().get(),
                FiberAnalysisPreferences.internalChannelProperty().get(),
                thrMethod,
                FiberAnalysisPreferences.manualThresholdProperty().get(),
                FiberAnalysisPreferences.ridgeFilterProperty().get(),
                FiberAnalysisPreferences.sigmaMinProperty().get(),
                FiberAnalysisPreferences.sigmaMaxProperty().get(),
                FiberAnalysisPreferences.sigmaStepProperty().get(),
                FiberAnalysisPreferences.minFiberAreaUm2Property().get(),
                FiberAnalysisPreferences.invertIntensityProperty().get(),
                FiberAnalysisPreferences.rollingBallRadiusUmProperty().get(),
                projectThresholdNorm,
                autoReattach);
        logger.info("Project density map: dispatching {} image(s), mode={}", entries.size(), channelsMode ? "Channels" : "Sidecar");

        Runnable onComplete = null;
        if (channelsMode) {
            // After the whole batch completes, auto-attach the density
            // channels onto whatever image is currently open in QuPath, if
            // that image was part of this run. Other images in the batch
            // are left as sidecars-on-disk; the user can switch to them
            // and run "Attach density channels" manually. Session-only --
            // the project entry's stored server builder is untouched.
            final List<ProjectImageEntry<BufferedImage>> ranEntries = new ArrayList<>(entries);
            final QuPathGUI guiRef = gui;
            onComplete = () -> {
                if (guiRef == null) return;
                ImageData<BufferedImage> currentData = guiRef.getImageData();
                if (currentData == null) return;
                ProjectImageEntry<BufferedImage> currentEntry =
                        guiRef.getProject() != null ? guiRef.getProject().getEntry(currentData) : null;
                if (currentEntry == null || !ranEntries.contains(currentEntry)) {
                    logger.info("Channels mode: current image was not part of the run; manual attach via menu");
                    return;
                }
                DensityChannelAttacher.attachForCurrentImage(guiRef);
            };
        }

        new FiberDensityMapWorkflow().runForEntries(spec, entries, project, gui, onComplete);
    }

    // ---------- validation ----------

    private void revalidate() {
        List<ImageRow> picked = pickedRows();
        boolean channelsMode = modeChannelsRadio != null && modeChannelsRadio.isSelected();

        ValidationResult v = validate(picked, channelsMode);
        applyValidation(v);
    }

    static ValidationResult validate(List<ImageRow> picked, boolean channelsMode) {
        if (picked.isEmpty()) {
            return ValidationResult.warn("Select one or more images on the left to compute a density map.");
        }

        // Pending = some rows still loading. Treat as blocking so the user
        // doesn't kick off a run on stale meta.
        boolean pending = picked.stream().anyMatch(r -> r.getMeta() == null);
        if (pending) {
            return ValidationResult.warn(
                    "Reading image metadata for the selected images. Validation will refresh as it completes...");
        }

        // Cal first: missing calibration blocks every mode.
        List<ImageRow> uncalibrated = new ArrayList<>();
        for (ImageRow r : picked) {
            ImageMeta m = r.getMeta();
            if (m == null || !m.hasPixelCalibration) {
                uncalibrated.add(r);
            }
        }
        if (!uncalibrated.isEmpty()) {
            return ValidationResult.error("Cannot run: "
                    + uncalibrated.size()
                    + " selected image"
                    + (uncalibrated.size() == 1 ? " has" : "s have")
                    + " no pixel calibration. "
                    + "Set the pixel size in QuPath (Image > Set image type / properties) "
                    + "or remove "
                    + (uncalibrated.size() == 1 ? "it" : "them")
                    + " from the selection.\n"
                    + "Affected: "
                    + summariseNames(uncalibrated));
        }

        // Pixel-type set across the selection drives mode-legality.
        Set<PixelType> types = new HashSet<>();
        int rgbCount = 0;
        for (ImageRow r : picked) {
            ImageMeta m = r.getMeta();
            types.add(m.pixelType);
            if (m.isRGB) rgbCount++;
        }

        if (channelsMode) {
            if (types.size() > 1) {
                return ValidationResult.error(
                        "Cannot run in Channels mode: selected images have different pixel types ("
                                + describeTypes(types)
                                + "). "
                                + "Either switch to Sidecar mode, or run them in separate batches.");
            }
            if (rgbCount > 0) {
                return ValidationResult.warn(
                        "Channels mode on an RGB base will change how the image renders. R/G/B will "
                                + "become separate display channels; we'll auto-configure them to recreate "
                                + "the RGB look on attach. Sidecar mode preserves the native RGB display if "
                                + "that matters to you.\n"
                                + "Ready to run on "
                                + picked.size()
                                + " image"
                                + (picked.size() == 1 ? "" : "s")
                                + ".");
            }
            return ValidationResult.ok("Ready to run in Channels mode on "
                    + picked.size()
                    + " image"
                    + (picked.size() == 1 ? "" : "s")
                    + " ("
                    + describeTypes(types)
                    + ").");
        } else {
            // Sidecar mode works for everything.
            return ValidationResult.ok("Ready to run in Sidecar mode on "
                    + picked.size()
                    + " image"
                    + (picked.size() == 1 ? "" : "s")
                    + ". Native display is preserved for every selected image.");
        }
    }

    private void applyValidation(ValidationResult v) {
        validationBanner.setText(v.message);
        switch (v.severity) {
            case OK -> validationBox.setStyle(okBannerStyle());
            case WARN -> validationBox.setStyle(warnBannerStyle());
            case ERROR -> validationBox.setStyle(errorBannerStyle());
        }
        runBtn.setDisable(v.severity == Severity.ERROR || v.severity == Severity.WARN_NO_RUN);
    }

    private List<ImageRow> pickedRows() {
        List<ImageRow> out = new ArrayList<>();
        for (ImageRow r : rows) {
            if (r.selectedProperty().get()) out.add(r);
        }
        return out;
    }

    private static String summariseNames(List<ImageRow> rows) {
        if (rows.isEmpty()) return "";
        int show = Math.min(3, rows.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(rows.get(i).getName()).append('"');
        }
        if (rows.size() > show) {
            sb.append(", and ").append(rows.size() - show).append(" more");
        }
        return sb.toString();
    }

    private static String describeTypes(Set<PixelType> types) {
        if (types.isEmpty()) return "unknown";
        List<String> parts = new ArrayList<>();
        for (PixelType t : types) parts.add(t == null ? "unknown" : t.toString());
        return String.join(", ", parts);
    }

    // ---------- background pixel-type detector ----------

    private void runMetaPopulator(Project<BufferedImage> project) {
        Thread t = new Thread(
                () -> {
                    for (ImageRow row : rows) {
                        if (Thread.currentThread().isInterrupted()) return;
                        ProjectImageEntry<BufferedImage> entry = row.entry;
                        String key = entry.getID();
                        ImageMeta m = metaCache.get(key);
                        if (m == null) {
                            m = readMeta(entry);
                            metaCache.put(key, m);
                        }
                        final ImageMeta fm = m;
                        Platform.runLater(() -> {
                            row.setMeta(fm);
                            revalidate();
                        });
                    }
                },
                "FiberDensityMap-MetaPopulator");
        t.setDaemon(true);
        t.start();
    }

    private static ImageMeta readMeta(ProjectImageEntry<BufferedImage> entry) {
        try {
            ImageData<BufferedImage> data = entry.readImageData();
            ImageServer<BufferedImage> server = data.getServer();
            ImageServerMetadata md = server.getMetadata();
            PixelCalibration cal = server.getPixelCalibration();
            return new ImageMeta(
                    server.getPixelType(),
                    server.isRGB(),
                    md.getWidth(),
                    md.getHeight(),
                    cal != null && cal.hasPixelSizeMicrons());
        } catch (Exception ex) {
            logger.warn("Could not read meta for entry {}: {}", entry.getImageName(), ex.getMessage());
            return ImageMeta.unknown();
        }
    }

    // ---------- banner styles ----------

    private static String emptyBannerStyle() {
        return "-fx-background-color: #f4f4f4; -fx-border-color: #ccc; -fx-border-radius: 4;"
                + " -fx-background-radius: 4;";
    }

    private static String okBannerStyle() {
        return "-fx-background-color: #e7f6e7; -fx-border-color: #4a9a4a; -fx-border-radius: 4;"
                + " -fx-background-radius: 4;";
    }

    private static String warnBannerStyle() {
        return "-fx-background-color: #fff5d6; -fx-border-color: #d4a82a; -fx-border-radius: 4;"
                + " -fx-background-radius: 4;";
    }

    private static String errorBannerStyle() {
        return "-fx-background-color: #fce0dc; -fx-border-color: #b04020; -fx-border-radius: 4;"
                + " -fx-background-radius: 4;";
    }

    // ---------- data classes ----------

    /**
     * One row in the image-picker table. Pixel-type / dimension / calibration
     * info populates asynchronously from the background populator; until then
     * those fields read {@code "..."} and the row is treated as "pending" by
     * the validator (which gates the Run button until every selected row has
     * meta loaded).
     */
    public static final class ImageRow {
        private final ProjectImageEntry<BufferedImage> entry;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        private final SimpleStringProperty name;
        private final SimpleStringProperty pixelTypeText = new SimpleStringProperty("...");
        private final SimpleStringProperty dimensionsText = new SimpleStringProperty("...");
        private final SimpleStringProperty calibrationText = new SimpleStringProperty("...");
        private ImageMeta meta; // null until populator runs

        public ImageRow(ProjectImageEntry<BufferedImage> entry) {
            this.entry = entry;
            this.name = new SimpleStringProperty(entry.getImageName());
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        public String getName() {
            return name.get();
        }

        public SimpleStringProperty nameProperty() {
            return name;
        }

        public String getPixelTypeText() {
            return pixelTypeText.get();
        }

        public SimpleStringProperty pixelTypeTextProperty() {
            return pixelTypeText;
        }

        public String getDimensionsText() {
            return dimensionsText.get();
        }

        public SimpleStringProperty dimensionsTextProperty() {
            return dimensionsText;
        }

        public String getCalibrationText() {
            return calibrationText.get();
        }

        public SimpleStringProperty calibrationTextProperty() {
            return calibrationText;
        }

        public ImageMeta getMeta() {
            return meta;
        }

        void setMeta(ImageMeta m) {
            this.meta = m;
            if (m == null || m == ImageMeta.UNKNOWN) {
                pixelTypeText.set("unknown");
                dimensionsText.set("unknown");
                calibrationText.set("unknown");
            } else {
                pixelTypeText.set(m.isRGB ? "uint8 RGB" : (m.pixelType != null ? m.pixelType.toString() : "?"));
                dimensionsText.set(String.format("%d x %d", m.width, m.height));
                calibrationText.set(m.hasPixelCalibration ? "Yes" : "No");
            }
        }
    }

    /** Cached pixel-type / dimensions / calibration summary for a project entry. */
    public static final class ImageMeta {
        public final PixelType pixelType;
        public final boolean isRGB;
        public final int width;
        public final int height;
        public final boolean hasPixelCalibration;

        public ImageMeta(PixelType pixelType, boolean isRGB, int width, int height, boolean hasPixelCalibration) {
            this.pixelType = pixelType;
            this.isRGB = isRGB;
            this.width = width;
            this.height = height;
            this.hasPixelCalibration = hasPixelCalibration;
        }

        public static final ImageMeta UNKNOWN = new ImageMeta(null, false, 0, 0, false);

        public static ImageMeta unknown() {
            return UNKNOWN;
        }
    }

    /** Validation severity. WARN_NO_RUN is reserved for future "pending" states. */
    public enum Severity {
        OK,
        WARN,
        WARN_NO_RUN,
        ERROR
    }

    public static final class ValidationResult {
        public final Severity severity;
        public final String message;

        private ValidationResult(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public static ValidationResult ok(String message) {
            return new ValidationResult(Severity.OK, message);
        }

        public static ValidationResult warn(String message) {
            return new ValidationResult(Severity.WARN, message);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(Severity.ERROR, message);
        }
    }
}
