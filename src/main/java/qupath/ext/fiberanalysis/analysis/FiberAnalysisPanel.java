package qupath.ext.fiberanalysis.analysis;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;

/**
 * Results panel for the fiber-analysis extension.
 *
 * <p>Modelled on {@code qupath.ext.ppm.analysis.PPMPerpendicularityPanel}:
 * header buttons drive the single-overlay-slot controller, a status line
 * tracks run progress, and one collapsible {@link TitledPane} card is appended
 * per completed annotation.</p>
 *
 * <p>Public API used by {@code FiberAnalysisWorkflow} (which accesses the
 * panel reflectively as {@code Object}):</p>
 * <ul>
 *   <li>{@link #startRun(int)} -- called before the first annotation.</li>
 *   <li>{@link #appendResult(AnnotationResult)} -- called per completed
 *       annotation on the JavaFX thread. The reflective hook in
 *       {@code FiberAnalysisWorkflow} expects this exact method name.</li>
 *   <li>{@link #finishRun()} -- called after the last annotation.</li>
 *   <li>{@link #clear()} -- removes all cards.</li>
 * </ul>
 */
public class FiberAnalysisPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisPanel.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final VBox contentBox;
    private final Label titleLabel;
    private final Label statusLabel;

    /** Mirrors the most recently completed annotation's output dir for [Open folder]. */
    private Path lastAnnotationOutputDir;

    /** Total annotations in the current run, for the status line. */
    private int totalAnnotations;

    /** Annotations completed so far, for the status line. */
    private int completedAnnotations;

    /** Run start time, for the status line. */
    private LocalTime runStartedAt;

    public FiberAnalysisPanel() {
        setSpacing(8);
        setPadding(new Insets(10));

        // RUO marker in the panel header is visible in every screenshot of
        // the results pane. Pathologist-persona finding M2 (Phase 4).
        // Wraps so the bold "(research use only)" half is not clipped at the
        // default 550 px panel width.
        titleLabel = new Label("Fiber Analysis Results (research use only)");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        titleLabel.setAccessibleRoleDescription("heading");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        Button hideOverlayBtn = new Button("_Hide overlay");
        hideOverlayBtn.setMnemonicParsing(true);
        hideOverlayBtn.setMinWidth(Region.USE_PREF_SIZE);
        hideOverlayBtn.setTooltip(new Tooltip("Remove the currently-displayed fiber-analysis overlay."));
        hideOverlayBtn.setOnAction(
                e -> FiberAnalysisOverlayController.getInstance().clear());

        Button openFolderBtn = new Button("_Open folder");
        openFolderBtn.setMnemonicParsing(true);
        openFolderBtn.setMinWidth(Region.USE_PREF_SIZE);
        openFolderBtn.setTooltip(
                new Tooltip("Open the output directory for the most recent annotation in the OS file browser."));
        openFolderBtn.setOnAction(e -> openOutputFolder());

        Button exportBtn = new Button("_Export PNG...");
        exportBtn.setMnemonicParsing(true);
        exportBtn.setMinWidth(Region.USE_PREF_SIZE);
        exportBtn.setTooltip(
                new Tooltip("Snapshot the results panel and save it as a PNG for sharing or publication."));
        exportBtn.setOnAction(e -> exportAsPng());

        // Two-row header so the buttons never overlap the wrapped title at
        // small panel widths. Row 1 = title (wraps), row 2 = action buttons.
        HBox buttonRow = new HBox(8, hideOverlayBtn, openFolderBtn, exportBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Waiting for analysis...");
        statusLabel.setWrapText(true);
        statusLabel.setFont(Font.font("System", 11));

        contentBox = new VBox(10);
        contentBox.setPadding(new Insets(5));

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(titleLabel, buttonRow, statusLabel, new Separator(), scrollPane);
    }

    /** Resets the card list and starts the run timer. */
    public void startRun(int totalAnnotations) {
        this.totalAnnotations = Math.max(0, totalAnnotations);
        this.completedAnnotations = 0;
        this.runStartedAt = LocalTime.now();
        this.lastAnnotationOutputDir = null;
        contentBox.getChildren().clear();
        statusLabel.setText(String.format(
                Locale.ROOT,
                "Run started %s -- 0/%d annotations complete",
                TIME_FMT.format(runStartedAt),
                totalAnnotations));
    }

    /**
     * Appends one annotation card to the panel.
     *
     * <p>Method name is fixed by the {@code FiberAnalysisWorkflow} reflective
     * hook (see that class's class-level comment). Do not rename.
     *
     * <p>If {@link #startRun(int)} was not called first the panel auto-starts
     * a run on the first {@code appendResult}; this is how the Workflow's
     * reflective access path works (it does not call {@code startRun}).
     */
    public void appendResult(AnnotationResult result) {
        if (result == null) {
            return;
        }
        if (runStartedAt == null) {
            runStartedAt = LocalTime.now();
        }
        completedAnnotations++;
        if (totalAnnotations < completedAnnotations) {
            totalAnnotations = completedAnnotations;
        }
        if (result.outputDir() != null) {
            lastAnnotationOutputDir = result.outputDir();
        }
        contentBox.getChildren().add(buildCard(result));
        updateStatus(false);
    }

    /** Updates the status line to reflect run completion. */
    public void finishRun() {
        updateStatus(true);
    }

    /** Removes all cards and resets the status line. */
    public void clear() {
        contentBox.getChildren().clear();
        statusLabel.setText("Waiting for analysis...");
        totalAnnotations = 0;
        completedAnnotations = 0;
        runStartedAt = null;
        lastAnnotationOutputDir = null;
    }

    private void updateStatus(boolean finished) {
        if (runStartedAt == null) {
            statusLabel.setText("Waiting for analysis...");
            return;
        }
        String start = TIME_FMT.format(runStartedAt);
        if (finished) {
            double elapsedSec = Duration.between(runStartedAt, LocalTime.now()).toMillis() / 1000.0;
            statusLabel.setText(String.format(
                    Locale.ROOT,
                    "Run started %s -- %d/%d annotations complete in %.1f s",
                    start,
                    completedAnnotations,
                    totalAnnotations,
                    elapsedSec));
        } else {
            statusLabel.setText(String.format(
                    Locale.ROOT,
                    "Run started %s -- %d/%d annotations complete",
                    start,
                    completedAnnotations,
                    totalAnnotations));
        }
    }

    private TitledPane buildCard(AnnotationResult result) {
        VBox body = new VBox(6);
        body.setPadding(new Insets(8));

        // Per-card overlay buttons.
        HBox buttonRow = new HBox(8);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        // Overlay controls are selection-driven: picking a value (or pressing
        // a toggle) activates that overlay immediately, no separate "Show"
        // button. The active control glows so the user sees at a glance
        // which overlay is currently on the viewer. Switching to another
        // control (in this card or any other) auto-resets the previous
        // one via the controller's activeTokenProperty.
        addOverlayToggle(buttonRow, result, "Fiber mask", "fiber_mask");
        addOverlayToggle(buttonRow, result, "Straightness", "straightness");
        addFamilyOverlayCombo(buttonRow, result, "Morph", "morph:");
        addFamilyOverlayCombo(buttonRow, result, "GLCM", "glcm:");

        Button summaryBtn = new Button("Summary");
        summaryBtn.setTooltip(new Tooltip("Show the full text summary of every metric reported for this annotation."));
        summaryBtn.setOnAction(e ->
                Dialogs.showPlainMessage("Fiber Analysis Summary -- " + safeName(result), buildSummaryText(result)));
        buttonRow.getChildren().add(summaryBtn);

        body.getChildren().add(buttonRow);

        // Region / output-dir metadata line.
        String dirText = result.outputDir() == null
                ? "(no output dir)"
                : result.outputDir().getFileName().toString();
        Label metaLabel = new Label(String.format(
                Locale.ROOT,
                "Region: %d x %d px at (%d, %d) | Output: %s",
                result.regionW(),
                result.regionH(),
                result.regionOffsetX(),
                result.regionOffsetY(),
                dirText));
        metaLabel.setFont(Font.font("System", 10));
        metaLabel.setStyle("-fx-text-fill: #666666;");
        body.getChildren().add(metaLabel);

        // Per-family summary text.
        Map<String, Map<String, Double>> grouped = groupByFamily(result.summaryMetrics());
        if (!grouped.isEmpty()) {
            body.getChildren().add(new Separator());
        }
        for (Map.Entry<String, Map<String, Double>> entry : grouped.entrySet()) {
            body.getChildren().add(buildFamilyBlock(entry.getKey(), entry.getValue()));
        }

        String title = String.format(
                Locale.ROOT,
                "Annotation %d/%d: %s",
                result.index(),
                Math.max(totalAnnotations, result.index()),
                safeName(result));
        TitledPane card = new TitledPane(title, body);
        card.setExpanded(true);
        card.setAnimated(false);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /**
     * Adds a ToggleButton for an atomic overlay (Fiber mask, Straightness).
     * Pressing it activates the overlay; pressing again (or activating any
     * other control) deactivates it. The button glows while it owns the
     * active overlay.
     */
    private void addOverlayToggle(HBox row, AnnotationResult result, String label, String slot) {
        if (result.overlayPngs() == null) return;
        Path png = result.overlayPngs().get(slot);
        if (png == null) return;

        ToggleButton btn = new ToggleButton(label);
        btn.setTooltip(new Tooltip(overlayButtonTooltip(slot)));
        btn.setAccessibleText(label + " for annotation " + safeName(result));

        final String myToken = "annotation_" + result.index() + ":" + slot;
        final AtomicBoolean suppress = new AtomicBoolean(false);

        btn.setOnAction(e -> {
            if (suppress.get()) return;
            if (btn.isSelected()) {
                FiberAnalysisOverlayController.getInstance()
                        .show(
                                png,
                                result.regionOffsetX(),
                                result.regionOffsetY(),
                                result.regionW(),
                                result.regionH(),
                                myToken);
            } else {
                FiberAnalysisOverlayController.getInstance().clear();
            }
        });

        FiberAnalysisOverlayController.getInstance()
                .activeTokenProperty()
                .addListener((obs, oldT, newT) -> {
                    boolean active = myToken.equals(newT);
                    suppress.set(true);
                    try {
                        btn.setSelected(active);
                    } finally {
                        suppress.set(false);
                    }
                    applyActiveGlow(btn, active);
                });

        row.getChildren().add(btn);
    }

    private static String overlayButtonTooltip(String slot) {
        if (slot != null && slot.startsWith("glcm:")) {
            return "Display the per-window GLCM " + slot.substring(5) + " heatmap.";
        }
        switch (slot) {
            case "fiber_mask":
                return "Toggle the binary fiber-mask overlay on or off.";
            case "straightness":
                return "Toggle the per-window tortuosity / Radon straightness heatmap on or off.";
            default:
                return "Toggle this overlay on or off.";
        }
    }

    /**
     * Adds a "[label]: [property combo]" pair to the card's button row,
     * listing every {@code <prefix><prop>} slot the workflow actually
     * registered for this annotation. Used for both the GLCM family (six
     * texture properties) and the morphometrics family (~15 per-window
     * quantities). The combo carries a leading "(none)" item so the user
     * can dismiss the family overlay without opening another card.
     * Selecting any non-none value activates the overlay immediately; the
     * combo glows while it owns the active overlay. Switching to another
     * control (in this card or any other) resets this combo to "(none)".
     */
    private void addFamilyOverlayCombo(HBox row, AnnotationResult result, String label, String slotPrefix) {
        if (result.overlayPngs() == null) return;
        java.util.List<String> props = new java.util.ArrayList<>();
        for (String slot : result.overlayPngs().keySet()) {
            if (slot != null && slot.startsWith(slotPrefix)) {
                props.add(slot.substring(slotPrefix.length()));
            }
        }
        if (props.isEmpty()) return;

        final String none = "(none)";
        java.util.List<String> options = new java.util.ArrayList<>();
        options.add(none);
        options.addAll(props);

        Label familyLabel = new Label(label + ":");
        ComboBox<String> combo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(options));
        combo.setValue(none);
        combo.setTooltip(new Tooltip(
                "Choose which " + label + " per-window heatmap to display. Pick (none) to hide."));

        final int idx = result.index();
        final int ox = result.regionOffsetX();
        final int oy = result.regionOffsetY();
        final int rw = result.regionW();
        final int rh = result.regionH();
        final String myPrefix = "annotation_" + idx + ":" + slotPrefix;
        final AtomicBoolean suppress = new AtomicBoolean(false);

        combo.valueProperty().addListener((obs, oldV, newV) -> {
            if (suppress.get()) return;
            if (newV == null || none.equals(newV)) {
                String activeToken = FiberAnalysisOverlayController.getInstance().getActiveToken();
                if (activeToken != null && activeToken.startsWith(myPrefix)) {
                    FiberAnalysisOverlayController.getInstance().clear();
                }
                return;
            }
            String slot = slotPrefix + newV;
            Path png = result.overlayPngs().get(slot);
            if (png == null) return;
            String token = "annotation_" + idx + ":" + slot;
            FiberAnalysisOverlayController.getInstance().show(png, ox, oy, rw, rh, token);
        });

        FiberAnalysisOverlayController.getInstance()
                .activeTokenProperty()
                .addListener((obs, oldT, newT) -> {
                    boolean iAmActive = newT != null && newT.startsWith(myPrefix);
                    suppress.set(true);
                    try {
                        if (iAmActive) {
                            String prop = newT.substring(myPrefix.length());
                            combo.setValue(prop);
                        } else {
                            combo.setValue(none);
                        }
                    } finally {
                        suppress.set(false);
                    }
                    applyActiveGlow(combo, iAmActive);
                });

        row.getChildren().addAll(familyLabel, combo);
    }

    /**
     * Applies a soft blue drop-shadow glow to the supplied control while it
     * owns the active overlay -- gives the user a single visual cue for
     * "what is currently painted on the viewer" across all cards.
     */
    private static void applyActiveGlow(Node n, boolean active) {
        if (active) {
            DropShadow glow = new DropShadow();
            glow.setColor(Color.web("#3fa9f5"));
            glow.setRadius(14);
            glow.setSpread(0.55);
            n.setEffect(glow);
        } else {
            n.setEffect(null);
        }
    }

    private VBox buildFamilyBlock(String family, Map<String, Double> metrics) {
        VBox box = new VBox(2);
        Label header = new Label(familyHeader(family));
        header.setFont(Font.font("System", FontWeight.BOLD, 11));
        box.getChildren().add(header);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : metrics.entrySet()) {
            sb.append(String.format(Locale.ROOT, "  %s: %s%n", e.getKey(), formatValue(e.getValue())));
        }
        Label stats = new Label(sb.toString().trim());
        stats.setFont(Font.font("Monospaced", 11));
        box.getChildren().add(stats);
        return box;
    }

    private static String familyHeader(String family) {
        switch (family) {
            case "straightness":
                return "-- Straightness ----------------------------";
            case "morph":
                return "-- Morphometrics ---------------------------";
            case "texture":
                return "-- Texture (GLCM) --------------------------";
            default:
                return "-- " + family + " --";
        }
    }

    /**
     * Groups flat-keyed metrics by the prefix before the first dot. Keys
     * without a prefix go under "other". Within each group the metric name is
     * the part after the first dot.
     */
    private static Map<String, Map<String, Double>> groupByFamily(Map<String, Double> metrics) {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        if (metrics == null) return out;
        for (Map.Entry<String, Double> e : metrics.entrySet()) {
            String key = e.getKey();
            String family;
            String name;
            int dot = key.indexOf('.');
            if (dot > 0) {
                family = key.substring(0, dot);
                name = key.substring(dot + 1);
            } else {
                family = "other";
                name = key;
            }
            out.computeIfAbsent(family, k -> new TreeMap<>()).put(name, e.getValue());
        }
        return out;
    }

    private static String formatValue(Double v) {
        if (v == null || Double.isNaN(v)) return "n/a";
        double abs = Math.abs(v);
        if (abs == 0.0) return "0";
        if (abs >= 1000 || abs < 0.01) {
            return String.format(Locale.ROOT, "%.3g", v);
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String safeName(AnnotationResult r) {
        return r.annotationName() == null || r.annotationName().isEmpty() ? "(unnamed)" : r.annotationName();
    }

    private String buildSummaryText(AnnotationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Annotation ")
                .append(result.index())
                .append(": ")
                .append(safeName(result))
                .append('\n');
        sb.append("Region ")
                .append(result.regionW())
                .append('x')
                .append(result.regionH())
                .append(" at (")
                .append(result.regionOffsetX())
                .append(", ")
                .append(result.regionOffsetY())
                .append(")")
                .append('\n');
        if (result.outputDir() != null) {
            sb.append("Output: ").append(result.outputDir()).append('\n');
        }
        sb.append('\n');
        Map<String, Map<String, Double>> grouped = groupByFamily(result.summaryMetrics());
        for (Map.Entry<String, Map<String, Double>> family : grouped.entrySet()) {
            sb.append(familyHeader(family.getKey())).append('\n');
            for (Map.Entry<String, Double> m : family.getValue().entrySet()) {
                sb.append("  ")
                        .append(m.getKey())
                        .append(": ")
                        .append(formatValue(m.getValue()))
                        .append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void openOutputFolder() {
        Path dir = lastAnnotationOutputDir;
        Path parent = (dir != null && dir.getParent() != null) ? dir.getParent() : dir;
        if (parent == null || !parent.toFile().isDirectory()) {
            Dialogs.showWarningNotification(
                    "Fiber Analysis",
                    dir == null ? "No output folder yet -- run an analysis first." : "Folder not found: " + parent);
            return;
        }
        try {
            Desktop.getDesktop().open(parent.toFile());
        } catch (Exception ex) {
            logger.warn("Failed to open output folder: {}", ex.getMessage());
            Dialogs.showErrorMessage("Fiber Analysis", "Failed to open folder: " + ex.getMessage());
        }
    }

    private void exportAsPng() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export results panel as PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        if (lastAnnotationOutputDir != null) {
            File parent = (lastAnnotationOutputDir.getParent() != null)
                    ? lastAnnotationOutputDir.getParent().toFile()
                    : lastAnnotationOutputDir.toFile();
            if (parent.isDirectory()) {
                chooser.setInitialDirectory(parent);
            }
        }
        chooser.setInitialFileName("fiber_analysis_results.png");
        File target =
                chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            WritableImage img = contentBox.snapshot(null, null);
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", target);
            Dialogs.showInfoNotification("Fiber Analysis", "Saved: " + target.getAbsolutePath());
        } catch (IOException ex) {
            logger.error("Failed to export results panel: {}", ex.getMessage(), ex);
            Dialogs.showErrorMessage("Fiber Analysis", "Failed to save PNG: " + ex.getMessage());
        }
    }
}
