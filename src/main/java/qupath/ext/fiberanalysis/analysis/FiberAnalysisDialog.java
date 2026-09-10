package qupath.ext.fiberanalysis.analysis;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.controlsfx.control.CheckComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.ui.SectionBuilder;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;

/**
 * Configuration dialog for the fiber-analysis extension.
 *
 * <p>Implements the 7-section layout from {@code 02_ui_design.md} (Search area,
 * Fiber segmentation, Window analysis, Straightness, Morphometrics, Texture,
 * Output) as collapsible {@link TitledPane} sections built via
 * {@link SectionBuilder}, stacked inside a {@link ScrollPane}.</p>
 *
 * <p>On {@code [Run]} the dialog validates inline (highlights invalid controls
 * and shows an error label), reads defaults from
 * {@link FiberAnalysisPreferences}, builds a {@link FiberAnalysisParams}, and
 * delegates to {@link FiberAnalysisWorkflow#runForAnnotations} with the
 * currently-selected annotations.</p>
 *
 * <p>{@code [Save defaults]} writes the current control state back into
 * {@link FiberAnalysisPreferences} (which auto-persists via
 * {@code PathPrefs.createPersistentPreference}); {@code [Cancel]} closes the
 * dialog.</p>
 */
public final class FiberAnalysisDialog {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisDialog.class);
    private static final Duration TOOLTIP_DELAY = Duration.millis(400);
    private static final String ERROR_STYLE = "-fx-border-color: #cc3300; -fx-border-width: 2; -fx-border-radius: 3;";
    private static final String INDENT_STYLE = "-fx-padding: 0 0 0 16;";

    private final QuPathGUI gui;
    private final FiberAnalysisPanel panel;

    // -- Section 1 --
    private ComboBox<String> searchAreaCombo;
    private CheckComboBox<String> classFilterCombo;
    private Label classFilterLabel;
    private Label selectionLabel; // live count, refreshed on hierarchy selection change
    private PathObjectSelectionListener selectionListener;
    private PathObjectHierarchy listenerHierarchy;
    private Spinner<Double> borderZoneSpinner;
    private ToggleGroup zoneModeGroup;
    private RadioButton zoneInside;
    private RadioButton zoneOutside;
    private RadioButton zoneBoth;
    private CheckBox useImagePixelSizeCheck;
    private Spinner<Double> pixelSizeOverrideSpinner;

    // -- Section 2 --
    private ToggleGroup segSourceGroup;
    private RadioButton segInternalRadio;
    private RadioButton segExistingRadio;
    private ComboBox<String> internalChannelCombo;
    private ComboBox<String> thresholdMethodCombo;
    private Spinner<Integer> manualThresholdSpinner;
    private Label manualThreshLabel;
    private ComboBox<String> ridgeFilterCombo;
    private Spinner<Double> sigmaMinSpinner;
    private Spinner<Double> sigmaMaxSpinner;
    private Spinner<Double> sigmaStepSpinner;
    private Spinner<Double> minFiberAreaSpinner;
    private ComboBox<String> calibrationCombo;
    private CheckBox invertIntensityCheck;
    private Spinner<Double> rollingBallSpinner;
    private ToggleGroup maskSourceGroup;
    private RadioButton maskSourceClassifierRadio;
    private RadioButton maskSourceObjectClassRadio;
    private RadioButton maskSourceFileRadio;
    private ComboBox<String> classifierNameCombo;
    private ComboBox<String> objectClassCombo;
    private TextField maskFileField;
    private CheckBox collagenObjectsCheck;

    // -- Section 3 --
    private CheckBox windowEnabledCheck;
    private Spinner<Double> windowSizeSpinner;
    private Spinner<Integer> windowOverlapSpinner;
    private CheckBox windowObjectsCheck;
    private Spinner<Double> minWindowCoverageSpinner;

    // -- Section 4 --
    private CheckBox straightnessEnabledCheck;
    private CheckBox tortuosityCheck;
    private CheckBox radonCheck;
    private Spinner<Double> minBranchLengthSpinner;

    // -- Section 5 --
    private CheckBox morphEnabledCheck;
    private CheckBox branchpointsCheck;
    private CheckBox endpointsCheck;
    private CheckBox lengthCheck;
    private CheckBox curvatureCheck;
    private CheckBox hdmCheck;
    private CheckBox lacunarityCheck;
    private CheckBox fractalDimensionCheck;
    private CheckBox gapAnalysisCheck;
    private TextField lacBoxSizesField;
    private TextField fractalBoxSizesField;

    // -- Section 6 --
    private CheckBox textureEnabledCheck;
    private Spinner<Integer> quantLevelsSpinner;
    private TextField glcmDistancesField;
    private CheckBox contrastCheck;
    private CheckBox correlationCheck;
    private CheckBox energyCheck;
    private CheckBox homogeneityCheck;
    private CheckBox entropyCheck;
    private CheckBox dissimilarityCheck;

    // -- Section 7 --
    private TextField outputDirField;
    private CheckBox fiberMaskOverlayCheck;
    private CheckBox straightnessHeatmapCheck;
    private CheckBox glcmHeatmapCheck;
    private ComboBox<String> glcmHeatmapProperty;
    private CheckBox morphSummaryCheck;
    private CheckBox jsonSidecarCheck;
    private CheckBox emitNpzCheck;
    private CheckBox addWindowObjectsToHierarchy; // read-only mirror of windowObjectsCheck

    // -- Footer --
    private Label errorLabel;

    public FiberAnalysisDialog(QuPathGUI gui, FiberAnalysisPanel panel) {
        this.gui = gui;
        this.panel = panel;
    }

    /**
     * Convenience entry point used by {@code FiberAnalysisExtension}. Constructs
     * the dialog with the supplied (singleton) results panel and shows it.
     *
     * <p>The Extension creates {@link FiberAnalysisPanel} once at install time
     * and registers it under QuPath's analysis pane, then passes the same
     * instance into every dialog invocation so per-annotation cards always land
     * in the same panel.
     */
    public static void showDialog(QuPathGUI gui, FiberAnalysisPanel panel) {
        new FiberAnalysisDialog(gui, panel).show();
    }

    /** Builds and shows the dialog as a non-modal {@link Stage}. */
    public void show() {
        // Make sure prefs are installed (idempotent).
        FiberAnalysisPreferences.installPreferences();

        Stage dialog = new Stage();
        // RUO marker: visible in the window title bar and in any screenshot the
        // user takes of the dialog. Pathologist-persona finding M2 (Phase 4).
        dialog.setTitle("Fiber Analysis -- Run (research use only)");
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }

        VBox sections = new VBox(10);
        sections.setPadding(new Insets(10));

        sections.getChildren().add(buildHeader());
        sections.getChildren().add(buildSearchAreaSection());
        sections.getChildren().add(buildSegmentationSection());
        sections.getChildren().add(buildWindowSection());
        sections.getChildren().add(buildStraightnessSection());
        sections.getChildren().add(buildMorphSection());
        sections.getChildren().add(buildTextureSection());
        sections.getChildren().add(buildOutputSection());

        ScrollPane scrollPane = new ScrollPane(sections);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Footer.
        errorLabel = new Label("");
        errorLabel.setStyle("-fx-text-fill: #cc3300; -fx-font-weight: bold;");
        errorLabel.setWrapText(true);

        // Mnemonic-bearing labels: alt+S / alt+C / alt+R activate the buttons on
        // platforms that honour mnemonics (Windows, Linux); on macOS they show as
        // plain text but the Enter/Escape/F1 accelerators below still apply.
        Button saveDefaultsBtn = new Button("_Save defaults");
        saveDefaultsBtn.setMnemonicParsing(true);
        saveDefaultsBtn.setTooltip(
                installDelay(new Tooltip("Write the current control values to QuPath preferences so they\n"
                        + "load as defaults the next time you open this dialog.")));
        saveDefaultsBtn.setOnAction(e -> {
            saveDefaults();
            Dialogs.showInfoNotification("Fiber Analysis", "Defaults saved.");
        });

        Button cancelBtn = new Button("_Cancel");
        cancelBtn.setMnemonicParsing(true);
        cancelBtn.setCancelButton(true); // Escape -> Cancel
        cancelBtn.setOnAction(e -> {
            detachSelectionListener();
            dialog.close();
        });

        Button runBtn = new Button("_Run");
        runBtn.setMnemonicParsing(true);
        runBtn.setDefaultButton(true);
        runBtn.setTooltip(installDelay(
                new Tooltip("Run fiber analysis on the currently-selected annotations using the controls above.\n"
                        + "Research use only -- do not use for clinical decisions.")));
        runBtn.setOnAction(e -> {
            FiberAnalysisParams params = validateAndBuild();
            if (params == null) {
                return;
            }
            ImageData<BufferedImage> imageData = currentImageData();
            if (imageData == null) {
                showError("No image data available. Open an image and try again.");
                return;
            }
            List<PathObject> annotations = resolveAnnotationsForRun(params, imageData);
            if (annotations.isEmpty()) {
                showError(noAnnotationsMessage(params));
                return;
            }
            // Persist current control state on Run so the values the user
            // actually ran with become the next session's defaults. Without
            // this, only the explicit "Save defaults" button persists -- so
            // someone who configures + Runs (the common case) gets reset to
            // factory defaults on restart.
            try {
                saveDefaults();
            } catch (Exception persistEx) {
                logger.warn("Could not auto-persist preferences on Run: {}", persistEx.getMessage());
            }
            detachSelectionListener();
            dialog.close();
            try {
                new FiberAnalysisWorkflow(panel).runForAnnotations(params, annotations, imageData, gui);
            } catch (Exception ex) {
                logger.error("Failed to launch fiber analysis workflow", ex);
                Dialogs.showErrorMessage("Fiber Analysis", "Failed to launch analysis: " + ex.getMessage());
            }
        });

        Button helpBtn = new Button("?");
        helpBtn.setTooltip(installDelay(new Tooltip("Open the user guide for the fiber-analysis extension. (F1)")));
        // The "?" glyph is not a meaningful label for screen readers; supply one.
        helpBtn.setAccessibleText("Help");
        helpBtn.setAccessibleHelp("Open the fiber-analysis user guide. Keyboard shortcut: F1.");
        helpBtn.setOnAction(e -> openHelp());

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footerButtons = new HBox(8, saveDefaultsBtn, footerSpacer, cancelBtn, runBtn, helpBtn);
        footerButtons.setAlignment(Pos.CENTER_LEFT);
        footerButtons.setPadding(new Insets(10));

        VBox footer = new VBox(4, errorLabel, footerButtons);
        footer.setPadding(new Insets(0, 10, 10, 10));

        VBox root = new VBox(scrollPane, new Separator(), footer);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 640, 750);
        // F1 opens the user guide from anywhere in the dialog. Escape is handled
        // by cancelBtn.setCancelButton(true). Enter is handled by runBtn.setDefaultButton.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F1) {
                openHelp();
                ev.consume();
            }
        });
        dialog.setScene(scene);
        // Live selection-count listener: registers on the current hierarchy so the
        // header label updates as the user selects/deselects annotations in the
        // viewer with this (non-modal) dialog open. Unregistered on close.
        attachSelectionListener();
        // Closing via the window X behaves exactly like Cancel -- the Stage has no
        // half-saved state (Run closes the dialog itself, Save defaults persists
        // synchronously), so this is just defensive symmetry.
        dialog.setOnCloseRequest(ev -> {
            detachSelectionListener();
            dialog.close();
        });
        dialog.setOnHidden(ev -> detachSelectionListener());
        // Set a sensible minimum so resizing down doesn't clip the section titles.
        dialog.setMinWidth(520);
        dialog.setMinHeight(420);
        dialog.setResizable(true);
        dialog.show();
    }

    // ------------------------------------------------------------
    // Header
    // ------------------------------------------------------------

    private VBox buildHeader() {
        VBox box = new VBox(2);
        box.setPadding(new Insets(0, 0, 4, 0));
        String imageName = "(no image open)";
        String pixelSizeText = "";
        if (gui != null && gui.getImageData() != null) {
            ImageData<?> data = gui.getImageData();
            try {
                imageName = data.getServer().getMetadata().getName();
                double px = data.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                if (!Double.isNaN(px) && px > 0) {
                    pixelSizeText = String.format(Locale.ROOT, "Pixel size: %.3f um/px", px);
                } else {
                    pixelSizeText = "Pixel size: unknown";
                }
            } catch (Exception ex) {
                logger.debug("Could not read image metadata: {}", ex.getMessage());
            }
        }
        Label imageLabel = new Label("Image: " + imageName + "   " + pixelSizeText);
        imageLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        imageLabel.setWrapText(true);
        // selectionLabel is a class field so the selection listener (attached in
        // show() after the scene is built) can refresh the text on every viewer
        // selection change. Without the listener the count would stay frozen at
        // the value captured at dialog-open time.
        selectionLabel = new Label("");
        selectionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        selectionLabel.setWrapText(true);
        refreshSelectionLabel();
        box.getChildren().addAll(imageLabel, selectionLabel);
        return box;
    }

    /**
     * Upper bound for the manual threshold: the largest value the segmenter can
     * actually see for the current image.
     *
     * <p>This is the bit depth that survives the region PNG round-trip, not the
     * server's advertised depth. PIL has no 16-bit RGB mode, so a multi-channel
     * 16-bit source reaches the segmenter as 8-bit and its usable range really
     * is 0-255.
     *
     * @return 65535 for single-channel 16-bit sources, else 255
     */
    private int sourceFullScale() {
        try {
            if (gui == null || gui.getImageData() == null) return 255;
            var server = gui.getImageData().getServer();
            if (server == null || server.isRGB()) return 255;
            if (server.getPixelType() == qupath.lib.images.servers.PixelType.UINT16 && server.nChannels() == 1) {
                return 65535;
            }
        } catch (Exception ex) {
            logger.debug("Could not read pixel type: {}", ex.getMessage());
        }
        return 255;
    }

    /**
     * Points the manual-threshold spinner and its label at the current image's
     * usable range. Called at build time and whenever the selection refreshes,
     * so switching images does not leave a 16-bit bound on an 8-bit image.
     */
    private void refreshManualThresholdRange() {
        if (manualThresholdSpinner == null) return;
        int max = sourceFullScale();
        var factory = (SpinnerValueFactory.IntegerSpinnerValueFactory) manualThresholdSpinner.getValueFactory();
        factory.setMax(max);
        if (manualThresholdSpinner.getValue() != null && manualThresholdSpinner.getValue() > max) {
            factory.setValue(max);
        }
        if (manualThreshLabel != null) {
            manualThreshLabel.setText("Manual threshold (0-" + max + "):");
        }
    }

    private void refreshSelectionLabel() {
        if (selectionLabel == null) return;
        int selectedCount = selectedAnnotations().size();
        String txt = selectedCount == 1 ? "Selected annotations: 1" : "Selected annotations: " + selectedCount;
        if (Platform.isFxApplicationThread()) {
            selectionLabel.setText(txt);
        } else {
            Platform.runLater(() -> selectionLabel.setText(txt));
        }
    }

    private void attachSelectionListener() {
        if (gui == null || gui.getImageData() == null) return;
        try {
            PathObjectHierarchy h = gui.getImageData().getHierarchy();
            if (h == null) return;
            // Class field so detach can use the same reference.
            selectionListener = (primary, previous, allSelected) -> {
                refreshSelectionLabel();
                refreshManualThresholdRange();
            };
            h.getSelectionModel().addPathObjectSelectionListener(selectionListener);
            listenerHierarchy = h;
        } catch (Exception ex) {
            logger.debug("Could not attach selection listener: {}", ex.getMessage());
        }
    }

    private void detachSelectionListener() {
        if (selectionListener == null || listenerHierarchy == null) return;
        try {
            listenerHierarchy.getSelectionModel().removePathObjectSelectionListener(selectionListener);
        } catch (Exception ex) {
            logger.debug("Could not detach selection listener: {}", ex.getMessage());
        }
        selectionListener = null;
        listenerHierarchy = null;
    }

    // ------------------------------------------------------------
    // Section 1 -- Search area (expanded)
    // ------------------------------------------------------------

    private TitledPane buildSearchAreaSection() {
        GridPane grid = baseGrid();
        int row = 0;

        // -- Search area selector --
        Label searchAreaLabel = new Label("Search area:");
        searchAreaCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Selected annotations", "All annotations in image", "Annotations of class..."));
        searchAreaCombo.setValue(searchAreaLabelFromCode(
                FiberAnalysisPreferences.searchAreaProperty().get()));
        applyTooltip(
                searchAreaCombo,
                "Which annotations to analyse. 'Selected annotations' uses your current viewer selection;"
                        + " 'All annotations in image' includes every annotation on the current image;"
                        + " 'Annotations of class...' filters by the class names you enter below.");
        grid.add(searchAreaLabel, 0, row);
        grid.add(searchAreaCombo, 1, row);
        row++;

        classFilterLabel = new Label("Annotation class(es):");
        classFilterLabel.setStyle(INDENT_STYLE);
        classFilterCombo = new CheckComboBox<>();
        classFilterCombo.setMaxWidth(Double.MAX_VALUE);
        FXUtils.installSelectAllOrNoneMenu(classFilterCombo);
        classFilterCombo.getCheckModel().getCheckedItems().addListener((javafx.collections.ListChangeListener<String>)
                c -> updateClassFilterTitle());
        applyTooltip(
                classFilterCombo,
                "Pick one or more QuPath classes. Only annotations whose class matches a checked entry"
                        + " are analysed. 'Unclassified' matches annotations with no class assigned.");
        Button classRefreshBtn = new Button("Refresh");
        classRefreshBtn.setTooltip(installDelay(
                new Tooltip("Re-scan the current image (and project as fallback) for class names. Use after"
                        + " drawing new annotations or changing classes.")));
        classRefreshBtn.setOnAction(e -> populateClassFilter());
        HBox classBox = new HBox(6, classFilterCombo, classRefreshBtn);
        HBox.setHgrow(classFilterCombo, Priority.ALWAYS);
        classBox.setAlignment(Pos.CENTER_LEFT);
        BooleanBinding classFilterDisabled = searchAreaCombo.valueProperty().isNotEqualTo("Annotations of class...");
        classFilterLabel.disableProperty().bind(classFilterDisabled);
        classFilterCombo.disableProperty().bind(classFilterDisabled);
        classRefreshBtn.disableProperty().bind(classFilterDisabled);
        grid.add(classFilterLabel, 0, row);
        grid.add(classBox, 1, row);
        row++;
        populateClassFilter();

        Label borderZoneLabel = new Label("Border zone width (um):");
        borderZoneSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1, 500, FiberAnalysisPreferences.borderZoneUmProperty().get(), 5));
        borderZoneSpinner.setEditable(true);
        applyTooltip(
                borderZoneSpinner,
                "Distance from the annotation boundary within which fibers are analysed (1-500 um)."
                        + " Default 50 um mirrors the PPM workflow.");
        grid.add(borderZoneLabel, 0, row);
        grid.add(borderZoneSpinner, 1, row);
        row++;

        Label zoneModeLabel = new Label("Zone mode:");
        zoneModeGroup = new ToggleGroup();
        zoneInside = new RadioButton("inside");
        zoneInside.setToggleGroup(zoneModeGroup);
        // Make the screen-reader announcement self-explanatory; the visible label
        // is just "inside" but in isolation that is ambiguous.
        zoneInside.setAccessibleText("Zone mode: inside the annotation");
        zoneOutside = new RadioButton("outside");
        zoneOutside.setToggleGroup(zoneModeGroup);
        zoneOutside.setAccessibleText("Zone mode: outside the annotation (stromal side)");
        zoneBoth = new RadioButton("both");
        zoneBoth.setToggleGroup(zoneModeGroup);
        zoneBoth.setAccessibleText("Zone mode: both sides of the annotation boundary");
        String zoneTip = "Which side of the boundary to analyse. 'outside' = stromal side (most common),"
                + " 'inside' = inside the annotation, 'both' = a symmetric band.";
        applyTooltip(zoneInside, zoneTip);
        applyTooltip(zoneOutside, zoneTip);
        applyTooltip(zoneBoth, zoneTip);
        String defaultZone = FiberAnalysisPreferences.zoneModeProperty().get();
        switch (defaultZone == null ? "outside" : defaultZone) {
            case "inside":
                zoneInside.setSelected(true);
                break;
            case "both":
                zoneBoth.setSelected(true);
                break;
            default:
                zoneOutside.setSelected(true);
                break;
        }
        HBox zoneBox = new HBox(15, zoneInside, zoneOutside, zoneBoth);
        grid.add(zoneModeLabel, 0, row);
        grid.add(zoneBox, 1, row);
        row++;

        useImagePixelSizeCheck = new CheckBox("Use image pixel size (uncheck to override)");
        useImagePixelSizeCheck.setSelected(
                FiberAnalysisPreferences.useImagePixelSizeProperty().get());
        applyTooltip(
                useImagePixelSizeCheck,
                "Read pixel size from the QuPath image. Uncheck to force a value -- useful when"
                        + " the image has no calibration.");
        grid.add(useImagePixelSizeCheck, 0, row, 2, 1);
        row++;

        Label overrideLabel = new Label("Override pixel size (um/px):");
        overrideLabel.setStyle(INDENT_STYLE);
        pixelSizeOverrideSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.05,
                10.0,
                FiberAnalysisPreferences.pixelSizeOverrideUmProperty().get(),
                0.05));
        pixelSizeOverrideSpinner.setEditable(true);
        applyTooltip(
                pixelSizeOverrideSpinner,
                "Pixel size used by all analysis steps when the image is uncalibrated (um per pixel).");
        overrideLabel.disableProperty().bind(useImagePixelSizeCheck.selectedProperty());
        pixelSizeOverrideSpinner.disableProperty().bind(useImagePixelSizeCheck.selectedProperty());
        grid.add(overrideLabel, 0, row);
        grid.add(pixelSizeOverrideSpinner, 1, row);
        row++;

        return SectionBuilder.createSection("1. Search area", true, grid);
    }

    // ------------------------------------------------------------
    // Section 2 -- Fiber segmentation (expanded)
    // ------------------------------------------------------------

    private TitledPane buildSegmentationSection() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(5));

        segSourceGroup = new ToggleGroup();
        segInternalRadio = new RadioButton("Segment within extension");
        segInternalRadio.setToggleGroup(segSourceGroup);
        segInternalRadio.setAccessibleText("Segmentation source: segment within extension");
        segExistingRadio = new RadioButton("Use existing fiber mask");
        segExistingRadio.setToggleGroup(segSourceGroup);
        segExistingRadio.setAccessibleText("Segmentation source: use existing fiber mask");
        String sourceTip = "Choose whether this extension segments fibers from the raw image or"
                + " consumes a fiber mask you have already produced.";
        applyTooltip(segInternalRadio, sourceTip);
        applyTooltip(segExistingRadio, sourceTip);
        boolean internalDefault = "internal"
                .equalsIgnoreCase(FiberAnalysisPreferences.segSourceProperty().get());
        segInternalRadio.setSelected(internalDefault);
        segExistingRadio.setSelected(!internalDefault);
        HBox sourceBox = new HBox(15, new Label("Source:"), segInternalRadio, segExistingRadio);
        sourceBox.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(sourceBox);

        // -- Internal segmenter sub-group --
        Label internalHeader = new Label("Internal segmenter");
        internalHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        content.getChildren().add(internalHeader);

        GridPane internalGrid = baseGrid();
        int row = 0;

        internalChannelCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Raw intensity", "Hue (HSV)", "Saturation (HSV)", "Value (HSV)", "Let me pick channel..."));
        internalChannelCombo.setValue(
                FiberAnalysisPreferences.internalChannelProperty().get());
        applyTooltip(
                internalChannelCombo,
                "Scalar channel the internal segmenter operates on. For brightfield/Picrosirius,"
                        + " 'Value' is a sensible default; switch to 'Raw intensity' for single-channel fluorescence.");
        internalGrid.add(new Label("Source channel:"), 0, row);
        internalGrid.add(internalChannelCombo, 1, row);
        row++;

        thresholdMethodCombo = new ComboBox<>(
                FXCollections.observableArrayList("Otsu", "Triangle", "Manual", "Project Otsu (calibrated)"));
        thresholdMethodCombo.setValue(
                FiberAnalysisPreferences.thresholdMethodProperty().get());
        applyTooltip(
                thresholdMethodCombo,
                "Threshold algorithm applied to the source channel. Otsu adapts to image content"
                        + " (per-annotation); Triangle suits skewed histograms; Manual lets you set the"
                        + " cutoff. 'Project Otsu (calibrated)' uses a single threshold derived once across"
                        + " a subsample of project regions -- use this for cross-image comparisons. Run"
                        + " 'Calibrate threshold...' from the batch dialog first.");
        internalGrid.add(new Label("Threshold method:"), 0, row);
        internalGrid.add(thresholdMethodCombo, 1, row);
        row++;

        Label calibrationLabel = new Label("Calibration:");
        calibrationLabel.setStyle(INDENT_STYLE);
        calibrationCombo = new ComboBox<>();
        calibrationCombo.setMaxWidth(Double.MAX_VALUE);
        applyTooltip(
                calibrationCombo,
                "Which named calibration to use for 'Project Otsu (calibrated)'. Calibrations are"
                        + " saved under <project>/fiber-analysis/calibration_<name>.json by the"
                        + " 'Calibrate threshold...' button in the batch dialog.");
        Button calRefreshBtn = new Button("Refresh");
        calRefreshBtn.setOnAction(e -> populateCalibrationCombo());
        BooleanBinding calDisabled = thresholdMethodCombo.valueProperty().isNotEqualTo("Project Otsu (calibrated)");
        calibrationLabel.disableProperty().bind(calDisabled);
        calibrationCombo.disableProperty().bind(calDisabled);
        calRefreshBtn.disableProperty().bind(calDisabled);
        HBox calBox = new HBox(6, calibrationCombo, calRefreshBtn);
        HBox.setHgrow(calibrationCombo, Priority.ALWAYS);
        calBox.setAlignment(Pos.CENTER_LEFT);
        internalGrid.add(calibrationLabel, 0, row);
        internalGrid.add(calBox, 1, row);
        row++;
        populateCalibrationCombo();

        manualThreshLabel = new Label("Manual threshold:");
        manualThreshLabel.setStyle(INDENT_STYLE);
        manualThresholdSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0,
                sourceFullScale(),
                FiberAnalysisPreferences.manualThresholdProperty().get(),
                1));
        manualThresholdSpinner.setEditable(true);
        applyTooltip(
                manualThresholdSpinner,
                "Cutoff in the source image's own gray levels -- on a 16-bit image type 4500 to cut at"
                        + " 4500. Pixels at or above this become fiber. The same value means the same"
                        + " brightness in every region and every image.\n\nWith a ridge filter selected the"
                        + " filter response has no image units, so the value is read as that same fraction"
                        + " of full scale (4500 of 65535 = 6.9%) of the response's own range.");
        refreshManualThresholdRange();
        BooleanBinding manualDisabled = thresholdMethodCombo.valueProperty().isNotEqualTo("Manual");
        manualThreshLabel.disableProperty().bind(manualDisabled);
        manualThresholdSpinner.disableProperty().bind(manualDisabled);
        internalGrid.add(manualThreshLabel, 0, row);
        internalGrid.add(manualThresholdSpinner, 1, row);
        row++;

        ridgeFilterCombo = new ComboBox<>(FXCollections.observableArrayList("None", "Frangi", "Sato", "Meijering"));
        ridgeFilterCombo.setValue(FiberAnalysisPreferences.ridgeFilterProperty().get());
        applyTooltip(
                ridgeFilterCombo,
                "Optional vesselness filter applied before thresholding to enhance line-like fiber structures."
                        + " Use Frangi for tubular fibers; Sato/Meijering are alternatives."
                        + " 'None' = pure intensity threshold.");
        internalGrid.add(new Label("Ridge filter:"), 0, row);
        internalGrid.add(ridgeFilterCombo, 1, row);
        row++;

        Label sigmaHeader = new Label("Sigma range (um):");
        sigmaHeader.setStyle(INDENT_STYLE);
        sigmaMinSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.1, 50.0, FiberAnalysisPreferences.sigmaMinProperty().get(), 0.5));
        sigmaMinSpinner.setEditable(true);
        sigmaMinSpinner.setPrefWidth(80);
        applyTooltip(
                sigmaMinSpinner,
                "Smallest fiber half-width in microns the ridge filter looks for."
                        + " Java converts to pixels using each image's measured pixel size.");
        sigmaMaxSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.1, 50.0, FiberAnalysisPreferences.sigmaMaxProperty().get(), 0.5));
        sigmaMaxSpinner.setEditable(true);
        sigmaMaxSpinner.setPrefWidth(80);
        applyTooltip(
                sigmaMaxSpinner,
                "Largest fiber half-width in microns the ridge filter looks for. The filter sweeps"
                        + " from min to max in fixed steps. Java converts to pixels per image.");
        sigmaStepSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.05, 10.0, FiberAnalysisPreferences.sigmaStepProperty().get(), 0.25));
        sigmaStepSpinner.setEditable(true);
        sigmaStepSpinner.setPrefWidth(80);
        applyTooltip(sigmaStepSpinner, "Step size in microns for the sigma sweep. Smaller = more sensitive, slower.");
        // Explicit Min / Max / Step labels: the v0.1 inline 'to' / 'step' labels
        // were getting clipped to '...' between the spinners on Windows.
        Label sigmaMinLbl = new Label("Min:");
        Label sigmaMaxLbl = new Label("Max:");
        Label sigmaStepLbl = new Label("Step:");
        HBox sigmaBox =
                new HBox(6, sigmaMinLbl, sigmaMinSpinner, sigmaMaxLbl, sigmaMaxSpinner, sigmaStepLbl, sigmaStepSpinner);
        sigmaBox.setAlignment(Pos.CENTER_LEFT);
        BooleanBinding ridgeDisabled = ridgeFilterCombo.valueProperty().isEqualTo("None");
        sigmaHeader.disableProperty().bind(ridgeDisabled);
        sigmaMinLbl.disableProperty().bind(ridgeDisabled);
        sigmaMaxLbl.disableProperty().bind(ridgeDisabled);
        sigmaStepLbl.disableProperty().bind(ridgeDisabled);
        sigmaMinSpinner.disableProperty().bind(ridgeDisabled);
        sigmaMaxSpinner.disableProperty().bind(ridgeDisabled);
        sigmaStepSpinner.disableProperty().bind(ridgeDisabled);
        internalGrid.add(sigmaHeader, 0, row);
        internalGrid.add(sigmaBox, 1, row);
        row++;

        minFiberAreaSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0, 1000.0, FiberAnalysisPreferences.minFiberAreaUm2Property().get(), 0.5));
        minFiberAreaSpinner.setEditable(true);
        applyTooltip(
                minFiberAreaSpinner,
                "Connected fiber components smaller than this area are removed. 0 disables size"
                        + " filtering. Java converts to pixels^2 per image.");
        internalGrid.add(new Label("Min fiber area (um2):"), 0, row);
        internalGrid.add(minFiberAreaSpinner, 1, row);
        row++;

        invertIntensityCheck = new CheckBox("Invert intensity (DAB / dark-fiber-on-bright)");
        invertIntensityCheck.setSelected(
                FiberAnalysisPreferences.invertIntensityProperty().get());
        applyTooltip(
                invertIntensityCheck,
                "Flip bright<->dark before thresholding. Enable for DAB or other chromogenic"
                        + " brightfield staining where collagen appears DARKER than background.");
        internalGrid.add(invertIntensityCheck, 0, row, 2, 1);
        row++;

        Label rollingBallLabel = new Label("Rolling-ball radius (um):");
        rollingBallSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0,
                50.0,
                FiberAnalysisPreferences.rollingBallRadiusUmProperty().get(),
                0.5));
        rollingBallSpinner.setEditable(true);
        applyTooltip(
                rollingBallSpinner,
                "Background-subtract using a morphological top-hat with this radius in microns."
                        + " 0 disables. Use ~2x typical fiber width to flatten uneven illumination."
                        + " Java converts to pixels per image.");
        internalGrid.add(rollingBallLabel, 0, row);
        internalGrid.add(rollingBallSpinner, 1, row);
        row++;

        content.getChildren().add(internalGrid);

        // Preview button -- opens a live segmentation preview window that
        // tracks the spinners above. Lets the user tune sigma / threshold /
        // invert / rolling-ball quickly without waiting for a full
        // per-annotation analysis run.
        Button previewBtn = new Button("Preview segmentation...");
        previewBtn.setTooltip(
                installDelay(new Tooltip("Open a live preview window. Reads a small region around the viewer center"
                        + " (or selected annotation) and shows the magenta fiber-mask overlay using"
                        + " the current Section 2 settings. Updates as you change spinners.")));
        previewBtn.setOnAction(e -> FiberSegmentationPreviewWindow.show(
                gui,
                internalChannelCombo,
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
                borderZoneSpinner));
        previewBtn.disableProperty().bind(segInternalRadio.selectedProperty().not());
        HBox previewRow = new HBox(previewBtn);
        previewRow.setAlignment(Pos.CENTER_LEFT);
        previewRow.setStyle(INDENT_STYLE);
        content.getChildren().add(previewRow);

        // -- Existing fiber mask sub-group --
        Label existingHeader = new Label("Existing fiber mask");
        existingHeader.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 2 0;");
        content.getChildren().add(existingHeader);

        GridPane existingGrid = baseGrid();
        int erow = 0;

        // Mask source: three mutually-exclusive options, each enabling its
        // own row below. Radio-group communicates the exclusivity natively;
        // a 3-item dropdown was hiding that constraint and required an extra
        // click to reveal the other two choices.
        maskSourceGroup = new ToggleGroup();
        maskSourceClassifierRadio = new RadioButton("Pixel classifier");
        maskSourceClassifierRadio.setToggleGroup(maskSourceGroup);
        maskSourceObjectClassRadio = new RadioButton("Object class");
        maskSourceObjectClassRadio.setToggleGroup(maskSourceGroup);
        maskSourceFileRadio = new RadioButton("File on disk");
        maskSourceFileRadio.setToggleGroup(maskSourceGroup);
        String savedMaskSource = FiberAnalysisPreferences.maskSourceProperty().get();
        switch (savedMaskSource == null ? "" : savedMaskSource) {
            case "Object class":
                maskSourceObjectClassRadio.setSelected(true);
                break;
            case "File on disk":
                maskSourceFileRadio.setSelected(true);
                break;
            case "Pixel classifier":
            default:
                maskSourceClassifierRadio.setSelected(true);
                break;
        }
        String maskSourceTip = "Where to read the existing fiber mask from. Pick a QuPath pixel-classifier output,"
                + " a thresholded object class, or a .tif/.npy file you produced elsewhere.";
        applyTooltip(maskSourceClassifierRadio, maskSourceTip);
        applyTooltip(maskSourceObjectClassRadio, maskSourceTip);
        applyTooltip(maskSourceFileRadio, maskSourceTip);
        HBox maskSourceBox = new HBox(15, maskSourceClassifierRadio, maskSourceObjectClassRadio, maskSourceFileRadio);
        maskSourceBox.setAlignment(Pos.CENTER_LEFT);
        existingGrid.add(new Label("Mask source:"), 0, erow);
        existingGrid.add(maskSourceBox, 1, erow);
        erow++;

        Label classifierLabel = new Label("Classifier name:");
        classifierLabel.setStyle(INDENT_STYLE);
        List<String> classifierNames = listPixelClassifiers();
        classifierNameCombo = new ComboBox<>(FXCollections.observableArrayList(
                classifierNames.isEmpty() ? List.of("(none available)") : classifierNames));
        String prefClassifier =
                FiberAnalysisPreferences.classifierNameProperty().get();
        classifierNameCombo.setValue(
                classifierNames.contains(prefClassifier)
                        ? prefClassifier
                        : classifierNameCombo.getItems().get(0));
        applyTooltip(
                classifierNameCombo,
                "Name of a pixel classifier in the current project whose 'fiber' channel will be used.");
        BooleanBinding classifierDisabled =
                maskSourceClassifierRadio.selectedProperty().not();
        classifierLabel.disableProperty().bind(classifierDisabled);
        classifierNameCombo.disableProperty().bind(classifierDisabled);
        existingGrid.add(classifierLabel, 0, erow);
        existingGrid.add(classifierNameCombo, 1, erow);
        erow++;

        Label objectClassLabel = new Label("Object class:");
        objectClassLabel.setStyle(INDENT_STYLE);
        List<String> pathClassNames = listPathClasses();
        objectClassCombo = new ComboBox<>(FXCollections.observableArrayList(
                pathClassNames.isEmpty() ? List.of("(none available)") : pathClassNames));
        String prefObjClass = FiberAnalysisPreferences.objectClassProperty().get();
        objectClassCombo.setValue(
                pathClassNames.contains(prefObjClass)
                        ? prefObjClass
                        : objectClassCombo.getItems().get(0));
        applyTooltip(objectClassCombo, "PathClass whose objects will be rasterised to a binary mask.");
        BooleanBinding objectDisabled =
                maskSourceObjectClassRadio.selectedProperty().not();
        objectClassLabel.disableProperty().bind(objectDisabled);
        objectClassCombo.disableProperty().bind(objectDisabled);
        existingGrid.add(objectClassLabel, 0, erow);
        existingGrid.add(objectClassCombo, 1, erow);
        erow++;

        Label maskFileLabel = new Label("Mask file:");
        maskFileLabel.setStyle(INDENT_STYLE);
        maskFileField =
                new TextField(FiberAnalysisPreferences.maskFileProperty().get());
        maskFileField.setPrefColumnCount(28);
        applyTooltip(
                maskFileField,
                "Path to a .tif or .npy file containing a binary fiber mask matching the analysed image dimensions.");
        Button browseMaskBtn = new Button("Browse...");
        browseMaskBtn.setAccessibleText("Browse for fiber mask file");
        browseMaskBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose fiber-mask file");
            chooser.getExtensionFilters()
                    .addAll(
                            new FileChooser.ExtensionFilter("Mask files", "*.tif", "*.tiff", "*.npy"),
                            new FileChooser.ExtensionFilter("All files", "*.*"));
            File f = chooser.showOpenDialog(
                    maskFileField.getScene() == null
                            ? null
                            : maskFileField.getScene().getWindow());
            if (f != null) {
                maskFileField.setText(f.getAbsolutePath());
            }
        });
        BooleanBinding fileDisabled = maskSourceFileRadio.selectedProperty().not();
        maskFileLabel.disableProperty().bind(fileDisabled);
        maskFileField.disableProperty().bind(fileDisabled);
        browseMaskBtn.disableProperty().bind(fileDisabled);
        HBox maskFileBox = new HBox(6, maskFileField, browseMaskBtn);
        maskFileBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(maskFileField, Priority.ALWAYS);
        existingGrid.add(maskFileLabel, 0, erow);
        existingGrid.add(maskFileBox, 1, erow);
        erow++;

        content.getChildren().add(existingGrid);

        // Inverse-disable: each sub-grid is disabled when its radio is NOT selected.
        internalGrid.disableProperty().bind(segInternalRadio.selectedProperty().not());
        internalHeader
                .disableProperty()
                .bind(segInternalRadio.selectedProperty().not());
        existingGrid.disableProperty().bind(segExistingRadio.selectedProperty().not());
        existingHeader
                .disableProperty()
                .bind(segExistingRadio.selectedProperty().not());

        // -- Section 2 follow-on: turn the segmented mask into hierarchy --
        // One detection per fiber blob (mask split into connected components),
        // classed CollagenAnalysis, parented under the source annotation.
        // Bold "Create" mirrors the per-window checkbox styling in Section 3.
        Label cBold = new Label("Create");
        cBold.setStyle("-fx-font-weight: bold;");
        Label cRest = new Label(" collagen detection objects (split, classed CollagenAnalysis)");
        HBox collagenLabel = new HBox(cBold, cRest);
        collagenLabel.setAlignment(Pos.CENTER_LEFT);
        collagenObjectsCheck = new CheckBox();
        collagenObjectsCheck.setGraphic(collagenLabel);
        collagenObjectsCheck.setAccessibleText(
                "Create collagen detection objects, split into connected components, classed CollagenAnalysis");
        collagenObjectsCheck.setSelected(
                FiberAnalysisPreferences.collagenObjectsProperty().get());
        applyTooltip(
                collagenObjectsCheck,
                "After segmentation, trace the fiber mask into one detection object per connected blob,"
                        + " parented under the source annotation, classified as CollagenAnalysis."
                        + " On by default. The same parameters that drive analysis drive these objects --"
                        + " filtering / classifying them downstream is the easy way to act on the segmented fibers.");
        content.getChildren().add(collagenObjectsCheck);

        return SectionBuilder.createSection("2. Fiber segmentation", true, content);
    }

    // ------------------------------------------------------------
    // Section 3 -- Window analysis (expanded)
    // ------------------------------------------------------------

    private TitledPane buildWindowSection() {
        GridPane grid = baseGrid();
        int row = 0;

        windowEnabledCheck = new CheckBox("Enable moving-window analysis");
        windowEnabledCheck.setSelected(
                FiberAnalysisPreferences.windowEnabledProperty().get());
        applyTooltip(
                windowEnabledCheck,
                "Divide the analysed zone into a grid of windows and report per-window straightness,"
                        + " morphometric, and texture metrics. Disable to get one set of metrics per"
                        + " annotation only.");
        grid.add(windowEnabledCheck, 0, row, 2, 1);
        row++;

        Label sizeLabel = new Label("Window size (um):");
        sizeLabel.setStyle(INDENT_STYLE);
        windowSizeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1.0, 200.0, FiberAnalysisPreferences.windowSizeUmProperty().get(), 1.0));
        windowSizeSpinner.setEditable(true);
        applyTooltip(
                windowSizeSpinner,
                "Side length of each square window. Smaller windows resolve finer variation;"
                        + " window count grows quadratically.");
        grid.add(sizeLabel, 0, row);
        grid.add(windowSizeSpinner, 1, row);
        row++;

        Label overlapLabel = new Label("Window overlap (%):");
        overlapLabel.setStyle(INDENT_STYLE);
        windowOverlapSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 90, FiberAnalysisPreferences.windowOverlapPercentProperty().get(), 5));
        windowOverlapSpinner.setEditable(true);
        applyTooltip(
                windowOverlapSpinner,
                "Overlap between adjacent windows. 0 = tiling, 50 = half-overlap (denser heatmaps, slower).");
        grid.add(overlapLabel, 0, row);
        grid.add(windowOverlapSpinner, 1, row);
        row++;

        // Highlight the verb "Create" so users notice this option -- with
        // detection objects off, no density-map source exists, which is the
        // entire point of the toggle for project-wide density mapping.
        // CheckBox.text is plain string only, so the bold+regular mix is set
        // as the graphic. We use an HBox of two Labels rather than a TextFlow
        // because TextFlow claims the full GridPane-cell width and balloons
        // its computed height; HBox stays one-row at the labels' natural size.
        Label createBold = new Label("Create");
        createBold.setStyle("-fx-font-weight: bold;");
        Label createRest = new Label(" per-window detection objects");
        HBox createLabel = new HBox(createBold, createRest);
        createLabel.setAlignment(Pos.CENTER_LEFT);
        windowObjectsCheck = new CheckBox();
        windowObjectsCheck.setGraphic(createLabel);
        // Screen-reader text -- the bold-vs-regular distinction is purely
        // visual, so expose the plain string here.
        windowObjectsCheck.setAccessibleText("Create per-window detection objects");
        windowObjectsCheck.setStyle(INDENT_STYLE);
        windowObjectsCheck.setSelected(
                FiberAnalysisPreferences.windowObjectsProperty().get());
        applyTooltip(
                windowObjectsCheck,
                "Add one rectangular detection object per non-empty window carrying its per-window metrics."
                        + " Off by default -- 15 um windows can produce thousands of objects per annotation,"
                        + " but those objects are exactly what you need to build a Density Map across a project.");
        grid.add(windowObjectsCheck, 0, row, 2, 1);
        row++;

        Label coverageLabel = new Label("Min coverage per window (%):");
        coverageLabel.setStyle(INDENT_STYLE);
        minWindowCoverageSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0,
                100.0,
                FiberAnalysisPreferences.minWindowCoveragePercentProperty().get(),
                1.0));
        minWindowCoverageSpinner.setEditable(true);
        applyTooltip(
                minWindowCoverageSpinner,
                "Windows with fiber coverage below this percentage are excluded from per-window"
                        + " detection objects AND rendered as transparent cells in every heatmap."
                        + " Suppresses the misleading 'near-zero' tiles at the corners of rounded"
                        + " annotations where the window-grid bounding box extends past the actual"
                        + " analysis zone. 5% is a sensible default; set 0 to keep every window.");
        grid.add(coverageLabel, 0, row);
        grid.add(minWindowCoverageSpinner, 1, row);
        row++;

        BooleanBinding windowDisabled = windowEnabledCheck.selectedProperty().not();
        sizeLabel.disableProperty().bind(windowDisabled);
        windowSizeSpinner.disableProperty().bind(windowDisabled);
        overlapLabel.disableProperty().bind(windowDisabled);
        windowOverlapSpinner.disableProperty().bind(windowDisabled);
        windowObjectsCheck.disableProperty().bind(windowDisabled);
        coverageLabel.disableProperty().bind(windowDisabled);
        minWindowCoverageSpinner.disableProperty().bind(windowDisabled);

        return SectionBuilder.createSection("3. Window analysis", true, grid);
    }

    // ------------------------------------------------------------
    // Section 4 -- Straightness (collapsed)
    // ------------------------------------------------------------

    private TitledPane buildStraightnessSection() {
        GridPane grid = baseGrid();
        int row = 0;

        straightnessEnabledCheck = new CheckBox("Enable straightness analysis");
        straightnessEnabledCheck.setSelected(
                FiberAnalysisPreferences.straightnessEnabledProperty().get());
        applyTooltip(
                straightnessEnabledCheck,
                "Measure how curly vs. taut the fibers are (skeleton tortuosity per window,"
                        + " Radon transform per ROI).");
        grid.add(straightnessEnabledCheck, 0, row, 2, 1);
        row++;

        tortuosityCheck = new CheckBox("Per-window skeleton tortuosity");
        tortuosityCheck.setStyle(INDENT_STYLE);
        tortuosityCheck.setSelected(
                FiberAnalysisPreferences.tortuosityOnProperty().get());
        applyTooltip(
                tortuosityCheck,
                "Per-window chord/arc ratio along the skeleton, CT-FIRE convention."
                        + " 1.0 = perfectly straight; lower = wavier.");
        grid.add(tortuosityCheck, 0, row, 2, 1);
        row++;

        radonCheck = new CheckBox("Per-ROI Radon scalar (peak/mean, FWHM)");
        radonCheck.setStyle(INDENT_STYLE);
        radonCheck.setSelected(FiberAnalysisPreferences.radonOnProperty().get());
        applyTooltip(
                radonCheck,
                "Per-ROI Radon transform scalar: peak-to-mean ratio and FWHM at the dominant angle."
                        + " Captures how aligned the bulk fibers are in this ROI.");
        grid.add(radonCheck, 0, row, 2, 1);
        row++;

        Label minBranchLabel = new Label("Min branch length (um):");
        minBranchLabel.setStyle(INDENT_STYLE);
        minBranchLengthSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.5, 50.0, FiberAnalysisPreferences.minBranchUmProperty().get(), 0.5));
        minBranchLengthSpinner.setEditable(true);
        applyTooltip(
                minBranchLengthSpinner,
                "Skeleton branches shorter than this are dropped as noise. Default 5 um matches"
                        + " CT-FIRE / Curve-Align defaults.");
        grid.add(minBranchLabel, 0, row);
        grid.add(minBranchLengthSpinner, 1, row);
        row++;

        BooleanBinding straightnessDisabled =
                straightnessEnabledCheck.selectedProperty().not();
        tortuosityCheck.disableProperty().bind(straightnessDisabled);
        radonCheck.disableProperty().bind(straightnessDisabled);
        minBranchLabel.disableProperty().bind(straightnessDisabled);
        minBranchLengthSpinner.disableProperty().bind(straightnessDisabled);

        return SectionBuilder.createSection("4. Straightness", false, grid);
    }

    // ------------------------------------------------------------
    // Section 5 -- Morphometrics (collapsed)
    // ------------------------------------------------------------

    private TitledPane buildMorphSection() {
        VBox content = new VBox(6);
        content.setPadding(new Insets(5));

        morphEnabledCheck = new CheckBox("Enable morphometrics");
        morphEnabledCheck.setSelected(
                FiberAnalysisPreferences.morphEnabledProperty().get());
        applyTooltip(
                morphEnabledCheck,
                "Native reimplementation of TWOMBLI's morphometric panel (Wershof et al.,"
                        + " Mol Syst Biol 2021). No FIJI plugins are bundled.");
        content.getChildren().add(morphEnabledCheck);

        GridPane checks = baseGrid();
        checks.setStyle(INDENT_STYLE);
        branchpointsCheck = new CheckBox("Branch-point count");
        branchpointsCheck.setSelected(
                FiberAnalysisPreferences.branchpointsProperty().get());
        applyTooltip(branchpointsCheck, "Count skeleton branch points (junctions where 3+ skeleton pixels meet).");
        endpointsCheck = new CheckBox("Endpoint count");
        endpointsCheck.setSelected(FiberAnalysisPreferences.endpointsProperty().get());
        applyTooltip(endpointsCheck, "Count skeleton endpoints (free fiber tips).");
        lengthCheck = new CheckBox("Total fiber length");
        lengthCheck.setSelected(FiberAnalysisPreferences.lengthProperty().get());
        applyTooltip(lengthCheck, "Sum of all skeleton segment lengths, in um.");
        curvatureCheck = new CheckBox("Mean curvature");
        curvatureCheck.setSelected(FiberAnalysisPreferences.curvatureProperty().get());
        applyTooltip(curvatureCheck, "Mean absolute curvature along skeleton segments, in 1/um.");
        hdmCheck = new CheckBox("HDM coverage");
        hdmCheck.setSelected(FiberAnalysisPreferences.hdmProperty().get());
        applyTooltip(
                hdmCheck, "High-Density-Matrix coverage: fraction of the search-zone area occupied by fiber pixels.");
        lacunarityCheck = new CheckBox("Lacunarity");
        lacunarityCheck.setSelected(
                FiberAnalysisPreferences.lacunarityProperty().get());
        applyTooltip(
                lacunarityCheck,
                "Lacunarity (gappiness) at multiple box sizes. Higher = more clustered/gapped fiber distribution.");
        fractalDimensionCheck = new CheckBox("Fractal dimension");
        fractalDimensionCheck.setSelected(
                FiberAnalysisPreferences.fractalProperty().get());
        applyTooltip(
                fractalDimensionCheck,
                "Box-counting fractal dimension. Expect values between 1.0 (line) and 2.0 (filled plane);"
                        + " collagen networks typically 1.4-1.8.");
        gapAnalysisCheck = new CheckBox("Gap analysis");
        gapAnalysisCheck.setSelected(
                FiberAnalysisPreferences.gapAnalysisProperty().get());
        applyTooltip(
                gapAnalysisCheck,
                "Distribution of gap sizes between fibers (largest inscribed circle in fiber-free regions).");

        checks.add(branchpointsCheck, 0, 0);
        checks.add(endpointsCheck, 1, 0);
        checks.add(lengthCheck, 0, 1);
        checks.add(curvatureCheck, 1, 1);
        checks.add(hdmCheck, 0, 2);
        checks.add(lacunarityCheck, 1, 2);
        checks.add(fractalDimensionCheck, 0, 3);
        checks.add(gapAnalysisCheck, 1, 3);

        content.getChildren().add(checks);

        GridPane sizesGrid = baseGrid();
        sizesGrid.setStyle(INDENT_STYLE);
        Label lacLabel = new Label("Lacunarity box sizes (um):");
        lacBoxSizesField =
                new TextField(FiberAnalysisPreferences.lacBoxSizesPxProperty().get());
        lacBoxSizesField.setPrefColumnCount(20);
        applyTooltip(
                lacBoxSizesField,
                "Comma-separated box sizes for the gliding-box lacunarity computation, in microns."
                        + " Java converts each value to integer pixels per image.");
        Label fdLabel = new Label("Fractal box sizes (um):");
        fractalBoxSizesField = new TextField(
                FiberAnalysisPreferences.fractalBoxSizesPxProperty().get());
        fractalBoxSizesField.setPrefColumnCount(20);
        applyTooltip(
                fractalBoxSizesField,
                "Comma-separated box sizes for box-counting fractal dimension, in microns. More sizes ="
                        + " better fit, slower. Java converts each value to integer pixels per image.");

        sizesGrid.add(lacLabel, 0, 0);
        sizesGrid.add(lacBoxSizesField, 1, 0);
        sizesGrid.add(fdLabel, 0, 1);
        sizesGrid.add(fractalBoxSizesField, 1, 1);

        lacLabel.disableProperty().bind(lacunarityCheck.selectedProperty().not());
        lacBoxSizesField
                .disableProperty()
                .bind(lacunarityCheck.selectedProperty().not());
        fdLabel.disableProperty().bind(fractalDimensionCheck.selectedProperty().not());
        fractalBoxSizesField
                .disableProperty()
                .bind(fractalDimensionCheck.selectedProperty().not());

        content.getChildren().add(sizesGrid);

        BooleanBinding morphDisabled = morphEnabledCheck.selectedProperty().not();
        checks.disableProperty().bind(morphDisabled);
        sizesGrid.disableProperty().bind(morphDisabled);

        return SectionBuilder.createSection("5. Morphometrics (TWOMBLI-derived)", false, content);
    }

    // ------------------------------------------------------------
    // Section 6 -- Texture / GLCM (collapsed)
    // ------------------------------------------------------------

    private TitledPane buildTextureSection() {
        VBox content = new VBox(6);
        content.setPadding(new Insets(5));

        textureEnabledCheck = new CheckBox("Enable GLCM texture");
        textureEnabledCheck.setSelected(
                FiberAnalysisPreferences.textureEnabledProperty().get());
        applyTooltip(
                textureEnabledCheck,
                "Per-window Gray-Level Co-occurrence Matrix (GLCM) features capturing fiber-density"
                        + " heterogeneity (Haralick).");
        content.getChildren().add(textureEnabledCheck);

        GridPane numericGrid = baseGrid();
        numericGrid.setStyle(INDENT_STYLE);
        quantLevelsSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                8, 256, FiberAnalysisPreferences.quantLevelsProperty().get(), 8));
        quantLevelsSpinner.setEditable(true);
        applyTooltip(
                quantLevelsSpinner,
                "Number of gray levels the source channel is quantised to before GLCM."
                        + " 16 is the scikit-image default; higher = finer texture, slower.");
        numericGrid.add(new Label("Quantization levels:"), 0, 0);
        numericGrid.add(quantLevelsSpinner, 1, 0);

        glcmDistancesField =
                new TextField(FiberAnalysisPreferences.glcmDistancesPxProperty().get());
        glcmDistancesField.setPrefColumnCount(14);
        applyTooltip(
                glcmDistancesField,
                "Comma-separated offsets at which co-occurrence is computed, in microns."
                        + " Java converts each to integer pixels per image (min 1 px). Captures"
                        + " texture at multiple scales.");
        numericGrid.add(new Label("GLCM distance(s) (um):"), 0, 1);
        numericGrid.add(glcmDistancesField, 1, 1);
        content.getChildren().add(numericGrid);

        Label propLabel = new Label("Properties:");
        propLabel.setStyle(INDENT_STYLE);
        content.getChildren().add(propLabel);

        GridPane propGrid = baseGrid();
        propGrid.setStyle(INDENT_STYLE);
        contrastCheck = new CheckBox("contrast");
        contrastCheck.setSelected(FiberAnalysisPreferences.contrastProperty().get());
        applyTooltip(contrastCheck, "GLCM contrast: variance of intensity differences. High = sharp edges.");
        correlationCheck = new CheckBox("correlation");
        correlationCheck.setSelected(
                FiberAnalysisPreferences.correlationProperty().get());
        applyTooltip(
                correlationCheck,
                "GLCM correlation: linear dependence of neighbouring intensities. High = predictable pattern.");
        energyCheck = new CheckBox("energy");
        energyCheck.setSelected(FiberAnalysisPreferences.energyProperty().get());
        applyTooltip(
                energyCheck,
                "GLCM energy (angular second moment): uniformity of the co-occurrence matrix."
                        + " High = repetitive structure.");
        homogeneityCheck = new CheckBox("homogeneity");
        homogeneityCheck.setSelected(
                FiberAnalysisPreferences.homogeneityProperty().get());
        applyTooltip(
                homogeneityCheck,
                "GLCM homogeneity (inverse difference moment): closeness of distribution to its diagonal.");
        entropyCheck = new CheckBox("entropy");
        entropyCheck.setSelected(FiberAnalysisPreferences.entropyProperty().get());
        applyTooltip(entropyCheck, "GLCM entropy: randomness of intensity distribution. High = disorganised.");
        dissimilarityCheck = new CheckBox("dissimilarity");
        dissimilarityCheck.setSelected(
                FiberAnalysisPreferences.dissimilarityProperty().get());
        applyTooltip(dissimilarityCheck, "GLCM dissimilarity: linear contrast variant; less sensitive to outliers.");
        propGrid.add(contrastCheck, 0, 0);
        propGrid.add(correlationCheck, 1, 0);
        propGrid.add(energyCheck, 0, 1);
        propGrid.add(homogeneityCheck, 1, 1);
        propGrid.add(entropyCheck, 0, 2);
        propGrid.add(dissimilarityCheck, 1, 2);
        content.getChildren().add(propGrid);

        BooleanBinding textureDisabled = textureEnabledCheck.selectedProperty().not();
        numericGrid.disableProperty().bind(textureDisabled);
        propLabel.disableProperty().bind(textureDisabled);
        propGrid.disableProperty().bind(textureDisabled);

        return SectionBuilder.createSection("6. Texture (GLCM / Haralick)", false, content);
    }

    // ------------------------------------------------------------
    // Section 7 -- Output (expanded)
    // ------------------------------------------------------------

    private TitledPane buildOutputSection() {
        VBox content = new VBox(6);
        content.setPadding(new Insets(5));

        Label outDirLabel = new Label("Output directory:");
        String prefDir = FiberAnalysisPreferences.outputDirProperty().get();
        outputDirField = new TextField(prefDir == null ? "" : prefDir);
        outputDirField.setPrefColumnCount(28);
        outputDirField.setPromptText("(blank -> <project>/fiber-analysis/<run-id>/)");
        applyTooltip(
                outputDirField,
                "Folder where each run writes a timestamped subdirectory with overlays, results.json,"
                        + " params.json, and (optionally) per-window CSV. Leave blank to use"
                        + " <project>/fiber-analysis/ when a project is open.");
        Button browseDirBtn = new Button("Browse...");
        browseDirBtn.setAccessibleText("Browse for output directory");
        browseDirBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose output directory");
            // Initial dir priority: the user's typed value, then the project's
            // fiber-analysis/ folder (created if needed), then the user's home.
            File initial = null;
            String existing = outputDirField.getText();
            if (existing != null && !existing.isBlank()) {
                File f = new File(existing);
                if (f.isDirectory()) initial = f;
            }
            if (initial == null
                    && gui != null
                    && gui.getProject() != null
                    && gui.getProject().getPath() != null) {
                java.nio.file.Path projDir = gui.getProject().getPath().getParent();
                if (projDir != null) {
                    java.nio.file.Path fa = projDir.resolve("fiber-analysis");
                    try {
                        if (!java.nio.file.Files.isDirectory(fa)) {
                            java.nio.file.Files.createDirectories(fa);
                        }
                        initial = fa.toFile();
                    } catch (Exception ex) {
                        // Fall through to home dir.
                        initial = projDir.toFile();
                    }
                }
            }
            if (initial != null && initial.isDirectory()) {
                chooser.setInitialDirectory(initial);
            }
            File f = chooser.showDialog(
                    outputDirField.getScene() == null
                            ? null
                            : outputDirField.getScene().getWindow());
            if (f != null) {
                outputDirField.setText(f.getAbsolutePath());
            }
        });
        HBox outDirBox = new HBox(6, outputDirField, browseDirBtn);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        outDirBox.setAlignment(Pos.CENTER_LEFT);
        GridPane dirGrid = baseGrid();
        dirGrid.add(outDirLabel, 0, 0);
        dirGrid.add(outDirBox, 1, 0);
        content.getChildren().add(dirGrid);

        fiberMaskOverlayCheck = new CheckBox("Write fiber-mask overlay PNG");
        fiberMaskOverlayCheck.setSelected(
                FiberAnalysisPreferences.fiberMaskOverlayProperty().get());
        applyTooltip(
                fiberMaskOverlayCheck,
                "Save the segmented fiber mask as a PNG that can be re-displayed via the results panel.");
        content.getChildren().add(fiberMaskOverlayCheck);

        straightnessHeatmapCheck = new CheckBox("Write straightness heatmap PNG");
        straightnessHeatmapCheck.setSelected(
                FiberAnalysisPreferences.straightnessHeatmapProperty().get());
        applyTooltip(
                straightnessHeatmapCheck, "Save a viridis heatmap of per-window tortuosity / Radon scalar values.");
        content.getChildren().add(straightnessHeatmapCheck);

        glcmHeatmapCheck = new CheckBox("Write GLCM heatmap PNG");
        glcmHeatmapCheck.setSelected(
                FiberAnalysisPreferences.glcmHeatmapProperty().get());
        applyTooltip(glcmHeatmapCheck, "Save a per-window heatmap PNG of the selected GLCM property.");

        glcmHeatmapProperty = new ComboBox<>(FXCollections.observableArrayList(
                "contrast", "correlation", "energy", "homogeneity", "entropy", "dissimilarity"));
        glcmHeatmapProperty.setValue(
                FiberAnalysisPreferences.glcmHeatmapPropProperty().get());
        applyTooltip(glcmHeatmapProperty, "Which GLCM property to render as a heatmap (only one PNG per run).");
        HBox glcmRow = new HBox(8, glcmHeatmapCheck, new Label("Property:"), glcmHeatmapProperty);
        glcmRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(glcmRow);

        morphSummaryCheck = new CheckBox("Write morphometric summary text");
        morphSummaryCheck.setSelected(
                FiberAnalysisPreferences.morphSummaryProperty().get());
        applyTooltip(
                morphSummaryCheck,
                "Render a text summary card of the morphometric metrics (shown as a button in the results panel).");
        content.getChildren().add(morphSummaryCheck);

        jsonSidecarCheck = new CheckBox("Write results.json sidecar");
        jsonSidecarCheck.setSelected(
                FiberAnalysisPreferences.jsonSidecarProperty().get());
        applyTooltip(
                jsonSidecarCheck,
                "Write the full per-annotation result dictionary as results.json next to the overlays.");
        content.getChildren().add(jsonSidecarCheck);

        emitNpzCheck = new CheckBox("Write per-window arrays (.npz)");
        emitNpzCheck.setSelected(FiberAnalysisPreferences.emitNpzProperty().get());
        applyTooltip(
                emitNpzCheck,
                "Save the per-window numeric arrays (one row per window, columns per metric) as a"
                        + " compressed NumPy .npz file. Useful for downstream Python analysis.");
        content.getChildren().add(emitNpzCheck);

        addWindowObjectsToHierarchy = new CheckBox("Add per-window detection objects to hierarchy");
        addWindowObjectsToHierarchy.setDisable(true);
        applyTooltip(
                addWindowObjectsToHierarchy,
                "Mirrors the 'Create per-window detection objects' toggle in section 3."
                        + " Disable there to skip adding objects.");
        if (windowObjectsCheck != null) {
            addWindowObjectsToHierarchy.selectedProperty().bind(windowObjectsCheck.selectedProperty());
        }
        content.getChildren().add(addWindowObjectsToHierarchy);

        return SectionBuilder.createSection("7. Output", true, content);
    }

    // ------------------------------------------------------------
    // Validation + params build
    // ------------------------------------------------------------

    private FiberAnalysisParams validateAndBuild() {
        clearError();
        clearInvalidStyles();

        List<String> errors = new ArrayList<>();

        if (!useImagePixelSizeCheck.isSelected()) {
            Double v = pixelSizeOverrideSpinner.getValue();
            if (v == null || v <= 0) {
                markInvalid(pixelSizeOverrideSpinner);
                errors.add("Override pixel size must be > 0.");
            }
        }

        if (!"None".equals(ridgeFilterCombo.getValue()) && segInternalRadio.isSelected()) {
            double smin = sigmaMinSpinner.getValue();
            double smax = sigmaMaxSpinner.getValue();
            double sstep = sigmaStepSpinner.getValue();
            if (smin > smax) {
                markInvalid(sigmaMinSpinner);
                markInvalid(sigmaMaxSpinner);
                errors.add("Sigma min must be <= sigma max.");
            }
            if (sstep <= 0) {
                markInvalid(sigmaStepSpinner);
                errors.add("Sigma step must be > 0.");
            }
        }

        if (segExistingRadio.isSelected() && maskSourceFileRadio.isSelected()) {
            String txt = maskFileField.getText();
            if (txt == null || txt.isBlank() || !Files.exists(Paths.get(txt))) {
                markInvalid(maskFileField);
                errors.add("Mask file path is empty or does not exist.");
            }
        }
        if (segInternalRadio.isSelected()
                && "Project Otsu (calibrated)".equals(thresholdMethodCombo.getValue())
                && (calibrationCombo.getValue() == null
                        || calibrationCombo.getValue().isBlank())) {
            markInvalid(calibrationCombo);
            errors.add("Project Otsu selected but no calibration is available."
                    + " Run 'Calibrate threshold...' from the batch dialog first.");
        }

        String outDir = outputDirField.getText();
        if ((outDir == null || outDir.isBlank()) && (gui == null || gui.getProject() == null)) {
            // No project open and no explicit output dir set -- ask the user to pick one.
            markInvalid(outputDirField);
            errors.add("Output directory is required when no QuPath project is open.");
        }

        if (lacunarityCheck.isSelected()
                && morphEnabledCheck.isSelected()
                && parseDoubleList(lacBoxSizesField.getText()).isEmpty()) {
            markInvalid(lacBoxSizesField);
            errors.add("Lacunarity box sizes must be a comma-separated list of positive numbers (microns).");
        }
        if (fractalDimensionCheck.isSelected()
                && morphEnabledCheck.isSelected()
                && parseDoubleList(fractalBoxSizesField.getText()).isEmpty()) {
            markInvalid(fractalBoxSizesField);
            errors.add("Fractal box sizes must be a comma-separated list of positive numbers (microns).");
        }
        if (textureEnabledCheck.isSelected()
                && parseDoubleList(glcmDistancesField.getText()).isEmpty()) {
            markInvalid(glcmDistancesField);
            errors.add("GLCM distances must be a comma-separated list of positive numbers (microns).");
        }

        if (!straightnessEnabledCheck.isSelected()
                && !morphEnabledCheck.isSelected()
                && !textureEnabledCheck.isSelected()) {
            errors.add("Enable at least one analysis family (Straightness, Morphometrics, or Texture).");
        }

        if (!errors.isEmpty()) {
            showError(String.join("\n", errors));
            return null;
        }

        String zoneMode = zoneInside.isSelected() ? "inside" : zoneBoth.isSelected() ? "both" : "outside";
        String segSource = segInternalRadio.isSelected() ? "internal" : "existing";
        double pixelOverride = useImagePixelSizeCheck.isSelected()
                ? FiberAnalysisPreferences.pixelSizeOverrideUmProperty().get()
                : pixelSizeOverrideSpinner.getValue();

        String searchAreaCode = searchAreaCodeFromLabel(searchAreaCombo.getValue());
        String classFilterCsv = classFilterCheckedAsCsv();
        if ("class".equals(searchAreaCode) && classFilterCsv.isBlank()) {
            showError("Check at least one class in 'Annotation class(es)'.");
            return null;
        }

        // Field order matches qupath.ext.fiberanalysis.analysis.FiberAnalysisParams exactly.
        return new FiberAnalysisParams(
                // Section 1
                searchAreaCode,
                classFilterCsv,
                borderZoneSpinner.getValue(),
                zoneMode,
                useImagePixelSizeCheck.isSelected(),
                pixelOverride,
                // Section 2
                segSource,
                internalChannelCombo.getValue(),
                thresholdMethodCombo.getValue(),
                manualThresholdSpinner.getValue(),
                ridgeFilterCombo.getValue(),
                sigmaMinSpinner.getValue(),
                sigmaMaxSpinner.getValue(),
                sigmaStepSpinner.getValue(),
                minFiberAreaSpinner.getValue(),
                calibrationCombo.getValue() == null ? "" : calibrationCombo.getValue(),
                invertIntensityCheck.isSelected(),
                rollingBallSpinner.getValue(),
                selectedMaskSourceValue(),
                nullIfPlaceholder(classifierNameCombo.getValue()),
                nullIfPlaceholder(objectClassCombo.getValue()),
                maskFileField.getText(),
                // Section 3
                windowEnabledCheck.isSelected(),
                windowSizeSpinner.getValue(),
                windowOverlapSpinner.getValue(),
                windowObjectsCheck.isSelected(),
                minWindowCoverageSpinner.getValue(),
                // Section 4
                straightnessEnabledCheck.isSelected(),
                tortuosityCheck.isSelected(),
                radonCheck.isSelected(),
                minBranchLengthSpinner.getValue(),
                // Section 5
                morphEnabledCheck.isSelected(),
                branchpointsCheck.isSelected(),
                endpointsCheck.isSelected(),
                lengthCheck.isSelected(),
                curvatureCheck.isSelected(),
                hdmCheck.isSelected(),
                lacunarityCheck.isSelected(),
                fractalDimensionCheck.isSelected(),
                gapAnalysisCheck.isSelected(),
                lacBoxSizesField.getText(),
                fractalBoxSizesField.getText(),
                // Section 6
                textureEnabledCheck.isSelected(),
                quantLevelsSpinner.getValue(),
                glcmDistancesField.getText(),
                contrastCheck.isSelected(),
                correlationCheck.isSelected(),
                energyCheck.isSelected(),
                homogeneityCheck.isSelected(),
                entropyCheck.isSelected(),
                dissimilarityCheck.isSelected(),
                // Section 7
                outDir,
                fiberMaskOverlayCheck.isSelected(),
                straightnessHeatmapCheck.isSelected(),
                glcmHeatmapCheck.isSelected(),
                glcmHeatmapProperty.getValue(),
                morphSummaryCheck.isSelected(),
                jsonSidecarCheck.isSelected(),
                emitNpzCheck.isSelected(),
                collagenObjectsCheck.isSelected());
    }

    /**
     * Writes the current control state back into {@link FiberAnalysisPreferences}.
     * The preference {@code Property}s are backed by {@code PathPrefs}, so
     * calling {@code .set()} auto-persists.
     */
    private void saveDefaults() {
        FiberAnalysisPreferences.searchAreaProperty().set(searchAreaCodeFromLabel(searchAreaCombo.getValue()));
        FiberAnalysisPreferences.classFilterProperty().set(classFilterCheckedAsCsv());
        FiberAnalysisPreferences.borderZoneUmProperty().set(borderZoneSpinner.getValue());
        FiberAnalysisPreferences.zoneModeProperty()
                .set(zoneInside.isSelected() ? "inside" : zoneBoth.isSelected() ? "both" : "outside");
        FiberAnalysisPreferences.useImagePixelSizeProperty().set(useImagePixelSizeCheck.isSelected());
        FiberAnalysisPreferences.pixelSizeOverrideUmProperty().set(pixelSizeOverrideSpinner.getValue());

        FiberAnalysisPreferences.segSourceProperty().set(segInternalRadio.isSelected() ? "internal" : "existing");
        FiberAnalysisPreferences.internalChannelProperty().set(internalChannelCombo.getValue());
        FiberAnalysisPreferences.thresholdMethodProperty().set(thresholdMethodCombo.getValue());
        FiberAnalysisPreferences.manualThresholdProperty().set(manualThresholdSpinner.getValue());
        FiberAnalysisPreferences.ridgeFilterProperty().set(ridgeFilterCombo.getValue());
        FiberAnalysisPreferences.sigmaMinProperty().set(sigmaMinSpinner.getValue());
        FiberAnalysisPreferences.sigmaMaxProperty().set(sigmaMaxSpinner.getValue());
        FiberAnalysisPreferences.sigmaStepProperty().set(sigmaStepSpinner.getValue());
        FiberAnalysisPreferences.minFiberAreaUm2Property().set(minFiberAreaSpinner.getValue());
        FiberAnalysisPreferences.projectCalibrationNameProperty()
                .set(calibrationCombo.getValue() == null ? "" : calibrationCombo.getValue());
        FiberAnalysisPreferences.invertIntensityProperty().set(invertIntensityCheck.isSelected());
        FiberAnalysisPreferences.rollingBallRadiusUmProperty().set(rollingBallSpinner.getValue());
        FiberAnalysisPreferences.maskSourceProperty().set(selectedMaskSourceValue());
        FiberAnalysisPreferences.classifierNameProperty().set(emptyIfNull(classifierNameCombo.getValue()));
        FiberAnalysisPreferences.objectClassProperty().set(emptyIfNull(objectClassCombo.getValue()));
        FiberAnalysisPreferences.maskFileProperty().set(emptyIfNull(maskFileField.getText()));

        FiberAnalysisPreferences.windowEnabledProperty().set(windowEnabledCheck.isSelected());
        FiberAnalysisPreferences.windowSizeUmProperty().set(windowSizeSpinner.getValue());
        FiberAnalysisPreferences.windowOverlapPercentProperty().set(windowOverlapSpinner.getValue());
        FiberAnalysisPreferences.windowObjectsProperty().set(windowObjectsCheck.isSelected());
        FiberAnalysisPreferences.collagenObjectsProperty().set(collagenObjectsCheck.isSelected());
        FiberAnalysisPreferences.minWindowCoveragePercentProperty().set(minWindowCoverageSpinner.getValue());

        FiberAnalysisPreferences.straightnessEnabledProperty().set(straightnessEnabledCheck.isSelected());
        FiberAnalysisPreferences.tortuosityOnProperty().set(tortuosityCheck.isSelected());
        FiberAnalysisPreferences.radonOnProperty().set(radonCheck.isSelected());
        FiberAnalysisPreferences.minBranchUmProperty().set(minBranchLengthSpinner.getValue());

        FiberAnalysisPreferences.morphEnabledProperty().set(morphEnabledCheck.isSelected());
        FiberAnalysisPreferences.branchpointsProperty().set(branchpointsCheck.isSelected());
        FiberAnalysisPreferences.endpointsProperty().set(endpointsCheck.isSelected());
        FiberAnalysisPreferences.lengthProperty().set(lengthCheck.isSelected());
        FiberAnalysisPreferences.curvatureProperty().set(curvatureCheck.isSelected());
        FiberAnalysisPreferences.hdmProperty().set(hdmCheck.isSelected());
        FiberAnalysisPreferences.lacunarityProperty().set(lacunarityCheck.isSelected());
        FiberAnalysisPreferences.fractalProperty().set(fractalDimensionCheck.isSelected());
        FiberAnalysisPreferences.gapAnalysisProperty().set(gapAnalysisCheck.isSelected());
        FiberAnalysisPreferences.lacBoxSizesPxProperty().set(lacBoxSizesField.getText());
        FiberAnalysisPreferences.fractalBoxSizesPxProperty().set(fractalBoxSizesField.getText());

        FiberAnalysisPreferences.textureEnabledProperty().set(textureEnabledCheck.isSelected());
        FiberAnalysisPreferences.quantLevelsProperty().set(quantLevelsSpinner.getValue());
        FiberAnalysisPreferences.glcmDistancesPxProperty().set(glcmDistancesField.getText());
        FiberAnalysisPreferences.contrastProperty().set(contrastCheck.isSelected());
        FiberAnalysisPreferences.correlationProperty().set(correlationCheck.isSelected());
        FiberAnalysisPreferences.energyProperty().set(energyCheck.isSelected());
        FiberAnalysisPreferences.homogeneityProperty().set(homogeneityCheck.isSelected());
        FiberAnalysisPreferences.entropyProperty().set(entropyCheck.isSelected());
        FiberAnalysisPreferences.dissimilarityProperty().set(dissimilarityCheck.isSelected());

        FiberAnalysisPreferences.outputDirProperty().set(emptyIfNull(outputDirField.getText()));
        FiberAnalysisPreferences.fiberMaskOverlayProperty().set(fiberMaskOverlayCheck.isSelected());
        FiberAnalysisPreferences.straightnessHeatmapProperty().set(straightnessHeatmapCheck.isSelected());
        FiberAnalysisPreferences.glcmHeatmapProperty().set(glcmHeatmapCheck.isSelected());
        FiberAnalysisPreferences.glcmHeatmapPropProperty().set(glcmHeatmapProperty.getValue());
        FiberAnalysisPreferences.morphSummaryProperty().set(morphSummaryCheck.isSelected());
        FiberAnalysisPreferences.jsonSidecarProperty().set(jsonSidecarCheck.isSelected());
        FiberAnalysisPreferences.emitNpzProperty().set(emitNpzCheck.isSelected());
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private List<PathObject> selectedAnnotations() {
        if (gui == null || gui.getImageData() == null) {
            return List.of();
        }
        try {
            return gui.getImageData().getHierarchy().getSelectionModel().getSelectedObjects().stream()
                    .filter(o -> o != null && o.isAnnotation())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.debug("Could not read selection: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Reads checked items off {@link #classFilterCombo} and returns a CSV. The
     * CSV is what we persist + what {@code parseClassFilter} reads back. A
     * sentinel value {@code "Unclassified"} represents annotations with no
     * class assigned (parity with QuIET's RenderedConfigPane convention).
     */
    private String classFilterCheckedAsCsv() {
        if (classFilterCombo == null) return "";
        var checked = classFilterCombo.getCheckModel().getCheckedItems();
        if (checked == null || checked.isEmpty()) return "";
        return String.join(", ", checked);
    }

    /**
     * Populates {@link #classFilterCombo} from (1) the current image's
     * hierarchy annotations -- including in-memory, unsaved ones -- and (2) the
     * project's available {@code PathClass} list as a fallback. Preserves the
     * persisted CSV checks across refresh.
     */
    private void populateClassFilter() {
        if (classFilterCombo == null) return;
        Set<String> previouslyChecked =
                new java.util.LinkedHashSet<>(classFilterCombo.getCheckModel().getCheckedItems());
        if (previouslyChecked.isEmpty()) {
            // First-time load: seed from persisted preference CSV.
            String saved = FiberAnalysisPreferences.classFilterProperty().get();
            if (saved != null && !saved.isBlank()) {
                for (String tok : saved.split(",")) {
                    String t = tok.trim();
                    if (!t.isEmpty()) previouslyChecked.add(t);
                }
            }
        }
        classFilterCombo.getCheckModel().clearChecks();
        classFilterCombo.getItems().clear();

        java.util.TreeSet<String> classNames = new java.util.TreeSet<>();
        boolean hasUnclassified = false;
        try {
            if (gui != null && gui.getImageData() != null && gui.getImageData().getHierarchy() != null) {
                for (PathObject ann : gui.getImageData().getHierarchy().getAnnotationObjects()) {
                    PathClass pc = ann.getPathClass();
                    if (pc == null || pc == PathClass.NULL_CLASS) {
                        hasUnclassified = true;
                    } else {
                        classNames.add(AnnotationClassFilter.displayName(pc));
                    }
                }
            }
            // Also include project-level class list so users can pre-select
            // classes that exist project-wide but happen not to be on the
            // current image yet.
            if (gui != null && gui.getProject() != null) {
                for (PathClass pc : gui.getProject().getPathClasses()) {
                    if (pc == null || pc == PathClass.NULL_CLASS) continue;
                    classNames.add(AnnotationClassFilter.displayName(pc));
                }
            }
            // Last fallback: the global available-class list shown in QuPath's
            // annotation pane.
            if (classNames.isEmpty() && gui != null && gui.getAvailablePathClasses() != null) {
                for (PathClass pc : gui.getAvailablePathClasses()) {
                    if (pc == null || pc == PathClass.NULL_CLASS) continue;
                    classNames.add(AnnotationClassFilter.displayName(pc));
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not populate class filter: {}", ex.getMessage());
        }
        if (hasUnclassified && !classNames.contains(AnnotationClassFilter.UNCLASSIFIED)) {
            classFilterCombo.getItems().add(AnnotationClassFilter.UNCLASSIFIED);
        }
        classFilterCombo.getItems().addAll(classNames);
        for (String name : previouslyChecked) {
            int idx = classFilterCombo.getItems().indexOf(name);
            if (idx >= 0) classFilterCombo.getCheckModel().check(idx);
        }
        updateClassFilterTitle();
    }

    /**
     * Keeps the class picker's button text honest. ControlsFX shows a non-null
     * title INSTEAD of the checked items, so the title is cleared unless it is
     * summarising an all-or-nothing state.
     */
    private void updateClassFilterTitle() {
        if (classFilterCombo == null) return;
        int checked = classFilterCombo.getCheckModel().getCheckedItems().size();
        int total = classFilterCombo.getItems().size();
        if (checked == 0) {
            classFilterCombo.setTitle(total == 0 ? "No classes found" : "None checked");
        } else if (checked == total) {
            classFilterCombo.setTitle("All classes");
        } else {
            classFilterCombo.setTitle(null);
        }
    }

    /**
     * Populates {@link #calibrationCombo} from
     * {@code <project>/fiber-analysis/calibration_*.json}. Files are listed by
     * their {@code <name>} suffix; the persisted preference seeds the initial
     * selection if it still exists.
     */
    private void populateCalibrationCombo() {
        if (calibrationCombo == null) return;
        String previous = calibrationCombo.getValue();
        if (previous == null || previous.isBlank()) {
            previous = FiberAnalysisPreferences.projectCalibrationNameProperty().get();
        }
        calibrationCombo.getItems().clear();
        try {
            if (gui != null && gui.getProject() != null && gui.getProject().getPath() != null) {
                java.nio.file.Path projDir = gui.getProject().getPath().getParent();
                if (projDir != null) {
                    java.nio.file.Path faDir = projDir.resolve("fiber-analysis");
                    if (java.nio.file.Files.isDirectory(faDir)) {
                        try (var stream = java.nio.file.Files.list(faDir)) {
                            stream.filter(p -> {
                                        String fn = p.getFileName().toString();
                                        return fn.startsWith("calibration_") && fn.endsWith(".json");
                                    })
                                    .forEach(p -> {
                                        String fn = p.getFileName().toString();
                                        String name =
                                                fn.substring("calibration_".length(), fn.length() - ".json".length());
                                        calibrationCombo.getItems().add(name);
                                    });
                        }
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Could not enumerate calibrations: {}", ex.getMessage());
        }
        if (previous != null
                && !previous.isBlank()
                && calibrationCombo.getItems().contains(previous)) {
            calibrationCombo.setValue(previous);
        } else if (!calibrationCombo.getItems().isEmpty()) {
            calibrationCombo.setValue(calibrationCombo.getItems().get(0));
        }
    }

    /**
     * Returns the currently-selected Mask source as the same string value the
     * old ComboBox emitted, so the preference key and the {@code FiberAnalysisParams}
     * carrier are unchanged.
     */
    private String selectedMaskSourceValue() {
        if (maskSourceObjectClassRadio != null && maskSourceObjectClassRadio.isSelected()) return "Object class";
        if (maskSourceFileRadio != null && maskSourceFileRadio.isSelected()) return "File on disk";
        return "Pixel classifier";
    }

    private static String searchAreaCodeFromLabel(String label) {
        if (label == null) return "selected";
        switch (label) {
            case "All annotations in image":
                return "all";
            case "Annotations of class...":
                return "class";
            default:
                return "selected";
        }
    }

    private static String searchAreaLabelFromCode(String code) {
        if (code == null) return "Selected annotations";
        switch (code) {
            case "all":
                return "All annotations in image";
            case "class":
                return "Annotations of class...";
            default:
                return "Selected annotations";
        }
    }

    /**
     * Resolves the run-time annotation list based on the Search area combo. The
     * result is computed at Run time (not at dialog-open), so the user can keep
     * the dialog open and adjust their viewer selection or class assignments
     * before clicking Run.
     */
    private List<PathObject> resolveAnnotationsForRun(FiberAnalysisParams params, ImageData<BufferedImage> imageData) {
        if (imageData == null || imageData.getHierarchy() == null) {
            return List.of();
        }
        String mode = params.searchArea() == null ? "selected" : params.searchArea();
        List<PathObject> all = imageData.getHierarchy().getAnnotationObjects().stream()
                .filter(o -> o != null && o.isAnnotation())
                .collect(Collectors.toList());
        switch (mode) {
            case "all":
                return all;
            case "class":
                var wanted = AnnotationClassFilter.predicate(AnnotationClassFilter.parse(params.classFilter()));
                return all.stream().filter(wanted).collect(Collectors.toList());
            case "selected":
            default:
                return selectedAnnotations();
        }
    }

    private static String noAnnotationsMessage(FiberAnalysisParams params) {
        String mode = params.searchArea() == null ? "selected" : params.searchArea();
        switch (mode) {
            case "all":
                return "This image has no annotations. Draw at least one annotation and try again.";
            case "class":
                return "No annotations matched class filter \"" + params.classFilter() + "\".\n"
                        + "Check the class names (case-sensitive) and try again.";
            case "selected":
            default:
                return "No annotations selected. Select one or more annotations\n"
                        + "in the QuPath viewer (or change Search area) and try again.";
        }
    }

    @SuppressWarnings("unchecked")
    private ImageData<BufferedImage> currentImageData() {
        if (gui == null) {
            return null;
        }
        try {
            return (ImageData<BufferedImage>) gui.getImageData();
        } catch (Exception e) {
            logger.debug("No BufferedImage image data: {}", e.getMessage());
            return null;
        }
    }

    private List<String> listPixelClassifiers() {
        try {
            if (gui != null && gui.getProject() != null) {
                return new ArrayList<>(gui.getProject().getPixelClassifiers().getNames());
            }
        } catch (Exception e) {
            logger.debug("Could not list pixel classifiers: {}", e.getMessage());
        }
        return List.of();
    }

    private List<String> listPathClasses() {
        try {
            if (gui != null && gui.getAvailablePathClasses() != null) {
                return gui.getAvailablePathClasses().stream()
                        .filter(pc -> pc != null
                                && pc.getName() != null
                                && !pc.getName().isEmpty())
                        .map(pc -> pc.getName())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            logger.debug("Could not list path classes: {}", e.getMessage());
        }
        return List.of();
    }

    private static List<Double> parseDoubleList(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Double> out = new ArrayList<>();
        for (String tok : text.split(",")) {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            try {
                double v = Double.parseDouble(tok);
                if (v <= 0) return List.of();
                out.add(v);
            } catch (NumberFormatException nfe) {
                return List.of();
            }
        }
        return out;
    }

    private static String nullIfPlaceholder(String value) {
        if (value == null) return null;
        if ("(none available)".equals(value)) return null;
        return value;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static GridPane baseGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(5));
        return grid;
    }

    private static <N extends javafx.scene.control.Control> N applyTooltip(N control, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(TOOLTIP_DELAY);
        tip.setWrapText(true);
        tip.setMaxWidth(360);
        control.setTooltip(tip);
        return control;
    }

    private static Tooltip installDelay(Tooltip tip) {
        tip.setShowDelay(TOOLTIP_DELAY);
        tip.setWrapText(true);
        tip.setMaxWidth(360);
        return tip;
    }

    private void showError(String message) {
        Platform.runLater(() -> errorLabel.setText(message));
    }

    private void clearError() {
        errorLabel.setText("");
    }

    private void clearInvalidStyles() {
        for (javafx.scene.control.Control c : new javafx.scene.control.Control[] {
            pixelSizeOverrideSpinner,
            sigmaMinSpinner,
            sigmaMaxSpinner,
            sigmaStepSpinner,
            maskFileField,
            outputDirField,
            lacBoxSizesField,
            fractalBoxSizesField,
            glcmDistancesField
        }) {
            if (c != null) {
                c.setStyle("");
            }
        }
    }

    private void markInvalid(javafx.scene.control.Control control) {
        if (control != null) {
            control.setStyle(ERROR_STYLE);
        }
    }

    private void openHelp() {
        // Try in order: (1) the per-user copy the extension extracts at startup
        // and advertises via a system property, (2) the documentation folder
        // beside the JAR (~/QuPath/v0.7/extensions/../documentation when shipped),
        // (3) fall back to an info notification telling the user where to look.
        //
        // A CWD-relative candidate used to sit between (1) and (2), fed by a
        // startup mirror that wrote documentation/fiber-analysis.md into whatever
        // directory QuPath was launched from. That mirror littered unrelated repos
        // and has been removed, so nothing writes there any more.
        String rel = "documentation/fiber-analysis.md";
        List<Path> candidates = new ArrayList<>();
        String published = System.getProperty("qupath.ext.fiberanalysis.docPath");
        if (published != null && !published.isBlank()) {
            candidates.add(Path.of(published));
        }
        try {
            java.net.URL loc = FiberAnalysisDialog.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            Path jarPath = Path.of(loc.toURI());
            Path jarDir = jarPath.getParent();
            if (jarDir != null) {
                candidates.add(jarDir.resolve(rel));
                Path parent = jarDir.getParent();
                if (parent != null) {
                    candidates.add(parent.resolve(rel));
                }
            }
        } catch (Exception ignore) {
            // Locating the JAR is best-effort.
        }
        for (Path candidate : candidates) {
            try {
                java.io.File f = candidate.toFile();
                if (f.exists() && java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(f);
                    return;
                }
            } catch (Exception ignore) {
                // Try the next candidate.
            }
        }
        Dialogs.showInfoNotification(
                "Fiber Analysis", "User guide not found on disk. See '" + rel + "' in the extension repository.");
    }
}
