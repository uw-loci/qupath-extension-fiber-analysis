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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Pick a density or classifier map and show one channel as a coloured overlay
 * ({@link DensityOverlay}). Lists the open image's own maps first, then maps
 * computed on other project images of the same size -- other images of the
 * same slide, such as a birefringence map over the brightfield image.
 * A classifier's predicted-class channel is drawn in the class colours.
 * Non-modal; follows the open image.
 */
public final class DensityOverlayDialog {

    private static final Logger logger = LoggerFactory.getLogger(DensityOverlayDialog.class);
    static final String TITLE = "Fiber Analysis -- Density overlay";

    private final QuPathGUI qupath;
    private Stage stage;
    private ComboBox<Path> mapCombo;
    private ComboBox<String> channelCombo;
    private ComboBox<String> colourCombo;
    private ComboBox<String> brightnessCombo;
    private TextField minField;
    private TextField maxField;
    private Button autoBtn;
    private CheckBox hideBelowBox;
    private Label status;
    private Label thresholdLabel;
    private Label regionLabel;

    private DensityOverlay.Loaded loaded;
    private DensityOverlay.Loaded brightnessLoaded;
    private List<DensitySidecar.ChannelInfo> channelInfos = List.of();

    static final String NO_BRIGHTNESS = "None";

    /** 0-1 channels that make sense as brightness, in order of preference. */
    static final List<String> BRIGHTNESS_CHANNELS = List.of(
            "Order parameter",
            "Intensity coherence (no threshold)",
            DensityChannelSpec.forMaxProbability().channelName);

    /** Display text for each listed map. */
    private final java.util.Map<Path, String> labels = new java.util.HashMap<>();

    /**
     * Reads run one at a time: two servers on the same OME-TIFF can share a
     * reader, and closing one under the other made the second read fail.
     */
    private final java.util.concurrent.ExecutorService reader =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fiber-density-overlay-read");
                t.setDaemon(true);
                return t;
            });

    private PathOverlay shown;
    private boolean loading;
    private boolean showWhenLoaded;
    private ChangeListener<ImageData<BufferedImage>> imageListener;

    /** @param qupath the QuPath window */
    public DensityOverlayDialog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Open the dialog for the current image. */
    public void show() {
        stage = new Stage();
        stage.setTitle(TITLE);
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);

        mapCombo = new ComboBox<>();
        mapCombo.setMaxWidth(Double.MAX_VALUE);
        mapCombo.setCellFactory(l -> new NameCell(labels));
        mapCombo.setButtonCell(new NameCell(labels));
        channelCombo = new ComboBox<>();
        channelCombo.setMaxWidth(Double.MAX_VALUE);
        colourCombo = new ComboBox<>(
                FXCollections.observableArrayList(ColorMaps.getColorMaps().keySet()));
        colourCombo.setMaxWidth(Double.MAX_VALUE);
        colourCombo
                .getSelectionModel()
                .select(colourCombo.getItems().stream()
                        .filter(n -> n.equalsIgnoreCase("Viridis"))
                        .findFirst()
                        .orElse(
                                colourCombo.getItems().isEmpty()
                                        ? null
                                        : colourCombo.getItems().get(0)));
        minField = new TextField();
        maxField = new TextField();
        minField.setPrefColumnCount(8);
        maxField.setPrefColumnCount(8);
        autoBtn = new Button("Auto (1-99%)");
        autoBtn.setTooltip(new javafx.scene.control.Tooltip(
                "The 1st to 99th percentile of the channel. An angle channel always gets its full period (0-180),"
                        + " so colours mean the same angle on every image."));
        autoBtn.setOnAction(e -> autoRange());
        hideBelowBox = new CheckBox("Hide values at or below the minimum");
        hideBelowBox.setTooltip(new javafx.scene.control.Tooltip(
                "Leave the lowest values undrawn instead of painting them in the first colour, so the image"
                        + " shows through where, for example, the slide background has no collagen."));
        brightnessCombo = new ComboBox<>();
        brightnessCombo.setMaxWidth(Double.MAX_VALUE);
        brightnessCombo.setTooltip(new javafx.scene.control.Tooltip(
                "Darken the colour where this 0-1 channel is low. With an angle channel and 'Order parameter',"
                        + " well-aligned fibers show bright colour and randomly oriented ones fade to black;"
                        + " with the predicted class and 'Max probability', uncertain windows fade."));
        status = new Label();
        status.setWrapText(true);
        status.setMaxWidth(420);
        thresholdLabel = new Label();
        thresholdLabel.setWrapText(true);
        thresholdLabel.setMaxWidth(420);
        thresholdLabel.setTooltip(new javafx.scene.control.Tooltip(
                "The threshold this map's fibers were segmented at. Maps cut at different thresholds, or with"
                        + " other segmentation settings, are not comparable."));

        regionLabel = new Label();
        regionLabel.setWrapText(true);
        regionLabel.setMaxWidth(420);
        regionLabel.setTooltip(new javafx.scene.control.Tooltip(
                "Where the map was computed. Outside a limited region the map has no values."));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.addRow(0, new Label("Map"), mapCombo);
        grid.addRow(1, new Label("Channel"), channelCombo);
        grid.addRow(2, new Label("Colour map"), colourCombo);
        grid.addRow(3, new Label("Range"), new HBox(6, minField, new Label("to"), maxField, autoBtn));
        grid.add(hideBelowBox, 1, 4);
        grid.addRow(5, new Label("Brightness from"), brightnessCombo);
        grid.addRow(6, new Label("Threshold"), thresholdLabel);
        grid.addRow(7, new Label("Region"), regionLabel);
        GridPane.setFillWidth(mapCombo, true);

        Label note = new Label("Drawn on QuPath's pixel-classification layer: the opacity slider and"
                + " 'Show pixel classification' (C) control it, and showing it replaces any pixel"
                + " classifier overlay. The image itself is not changed.");
        note.setWrapText(true);
        note.setMaxWidth(420);
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        Button showBtn = new Button("Show");
        showBtn.setDefaultButton(true);
        showBtn.setOnAction(e -> render());
        Button hideBtn = new Button("Hide");
        hideBtn.setOnAction(e -> hide());
        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, showBtn, hideBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, grid, note, status, buttons);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root));

        mapCombo.valueProperty().addListener((o, a, b) -> refreshChannels());
        channelCombo.valueProperty().addListener((o, a, b) -> {
            applyChannelDefaults();
            load(true);
        });
        brightnessCombo.valueProperty().addListener((o, a, b) -> loadBrightness());
        colourCombo.valueProperty().addListener((o, a, b) -> {
            if (shown != null) render();
        });
        hideBelowBox.selectedProperty().addListener((o, a, b) -> {
            if (shown != null) render();
        });
        imageListener = (o, a, b) -> {
            hide();
            refreshMaps();
        };
        qupath.imageDataProperty().addListener(imageListener);
        stage.setOnHidden(e -> {
            qupath.imageDataProperty().removeListener(imageListener);
            reader.shutdownNow();
        });

        refreshMaps();
        stage.show();
    }

    /** Maps on disk for one image: its density map, then any classifier maps. */
    static List<Path> mapsFor(Project<?> project, ProjectImageEntry<?> entry) {
        List<Path> out = new ArrayList<>();
        if (project == null || entry == null) return out;
        Path density = DensitySidecar.sidecarPathFor(project, entry);
        if (density != null && DensitySidecar.exists(density)) out.add(density);
        Path root = DensitySidecar.classifierMapsRoot(project);
        if (root != null && Files.isDirectory(root)) {
            String prefix = DensitySidecar.sanitize(entry.getImageName()) + "_";
            try (Stream<Path> s = Files.list(root)) {
                s.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.startsWith(prefix) && n.endsWith(".ome.tif");
                        })
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                logger.warn("Could not list classifier maps: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Every map in the project: the image's own first, then any other image's
     * whose source size matches, labelled with the image it came from.
     *
     * @param project   the project
     * @param entry     the image the maps would be shown on
     * @param width     its width
     * @param height    its height
     * @param labelsOut filled with a display label per map
     * @return the maps
     */
    public static List<Path> mapsForImage(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> entry,
            int width,
            int height,
            java.util.Map<Path, String> labelsOut) {
        List<Path> own = mapsFor(project, entry);
        for (Path p : own) labelsOut.put(p, p.getFileName().toString());
        List<Path> out = new ArrayList<>(own);
        for (ProjectImageEntry<BufferedImage> other : project.getImageList()) {
            if (other == entry) continue;
            for (Path p : mapsFor(project, other)) {
                if (own.contains(p) || out.contains(p)) continue;
                try {
                    DensityMapInfo info = DensitySidecar.readInfo(p);
                    if (!info.fits(width, height)) continue;
                    String from = info.sourceImage() != null ? info.sourceImage() : other.getImageName();
                    labelsOut.put(p, p.getFileName() + "  (from " + from + ")");
                    out.add(p);
                } catch (IOException e) {
                    logger.debug("Skipping map {}: {}", p.getFileName(), e.getMessage());
                }
            }
        }
        return out;
    }

    private void refreshMaps() {
        ImageData<BufferedImage> data = qupath.getImageData();
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project == null || data == null ? null : project.getEntry(data);
        mapCombo.getItems().clear();
        channelCombo.getItems().clear();
        if (entry == null) {
            status.setText("Open a project image first.");
            return;
        }
        int w = data.getServer().getWidth();
        int h = data.getServer().getHeight();
        status.setText("Looking for maps...");
        reader.submit(() -> {
            java.util.Map<Path, String> found = new java.util.HashMap<>();
            List<Path> maps = mapsForImage(project, entry, w, h, found);
            HeadlessFx.runLater(() -> {
                if (qupath.getImageData() != data) return;
                labels.clear();
                labels.putAll(found);
                mapCombo.getItems().setAll(maps);
                if (maps.isEmpty()) {
                    status.setText("No density or classifier map for this image, or for another image of the"
                            + " same size. Run 'Project density map...' or 'Apply window classifier...' first.");
                } else {
                    status.setText("");
                    mapCombo.getSelectionModel().select(0);
                }
            });
        });
    }

    private void refreshChannels() {
        Path map = mapCombo.getValue();
        channelCombo.getItems().clear();
        brightnessCombo.getItems().clear();
        loaded = null;
        brightnessLoaded = null;
        channelInfos = List.of();
        thresholdLabel.setText("");
        regionLabel.setText("");
        if (map == null) return;
        try {
            channelInfos = DensitySidecar.readChannelInfos(map);
            DensityMapInfo mapInfo = DensitySidecar.readInfo(map);
            thresholdLabel.setText(thresholdText(mapInfo.threshold()));
            regionLabel.setText(regionText(mapInfo.region()));
            brightnessCombo.getItems().add(NO_BRIGHTNESS);
            for (var info : channelInfos) {
                if (BRIGHTNESS_CHANNELS.contains(info.name))
                    brightnessCombo.getItems().add(info.name);
            }
            brightnessCombo.getSelectionModel().select(NO_BRIGHTNESS);
            for (var info : channelInfos) channelCombo.getItems().add(info.name);
            if (!channelCombo.getItems().isEmpty())
                channelCombo.getSelectionModel().select(0);
        } catch (IOException e) {
            status.setText("Could not read the map's channels: " + e.getMessage());
        }
    }

    /**
     * @param threshold a map's recorded threshold label, or null
     * @return the text the Threshold row shows
     */
    static String thresholdText(String threshold) {
        if (threshold == null) return "Not recorded (map made before 2026-10-21)";
        ThresholdIdentity t = ThresholdIdentity.parse(threshold);
        return t == null ? threshold : t.cut() + "\n" + t.source() + "\nsettings " + t.settings();
    }

    /**
     * @param region a map's recorded region, or null
     * @return the text the Region row shows
     */
    static String regionText(String region) {
        return region == null
                ? "Whole image (not recorded: maps made before the region option always cover the whole image)"
                : region;
    }

    /** The chosen channel's info, or null. */
    private DensitySidecar.ChannelInfo selectedInfo() {
        int i = channelCombo.getSelectionModel().getSelectedIndex();
        return i < 0 || i >= channelInfos.size() ? null : channelInfos.get(i);
    }

    /** True when the chosen channel is a predicted class and the map names its classes. */
    private boolean classChannelSelected() {
        return DensityOverlay.isClassChannel(selectedInfo())
                && channelInfos.stream().anyMatch(c -> c.name.startsWith("P("));
    }

    /**
     * An angle channel gets the cyclic colour map and, where the map has one,
     * order parameter as brightness; any other channel goes back to Viridis
     * and no brightness if it had the angle settings. A class channel has
     * no colour map or range to set.
     */
    private void applyChannelDefaults() {
        var info = selectedInfo();
        if (info == null) return;
        boolean classes = classChannelSelected();
        for (var control : List.of(colourCombo, minField, maxField, autoBtn, hideBelowBox)) control.setDisable(classes);
        if (info.isAxial()) {
            if (colourCombo.getItems().contains(CyclicColorMaps.CYCLIC)) colourCombo.setValue(CyclicColorMaps.CYCLIC);
            brightnessCombo.setValue(BRIGHTNESS_CHANNELS.stream()
                    .filter(brightnessCombo.getItems()::contains)
                    .findFirst()
                    .orElse(NO_BRIGHTNESS));
        } else {
            if (CyclicColorMaps.isCyclic(colourCombo.getValue())) {
                colourCombo.setValue(colourCombo.getItems().stream()
                        .filter(n -> n.equalsIgnoreCase("Viridis"))
                        .findFirst()
                        .orElse(colourCombo.getValue()));
            }
            brightnessCombo.setValue(NO_BRIGHTNESS);
        }
    }

    private void loadBrightness() {
        Path map = mapCombo.getValue();
        String name = brightnessCombo.getValue();
        brightnessLoaded = null;
        if (map == null || name == null || NO_BRIGHTNESS.equals(name)) {
            if (shown != null) render();
            return;
        }
        int ch = -1;
        for (var info : channelInfos) if (info.name.equals(name)) ch = info.index;
        if (ch < 0) return;
        final int channel = ch;
        reader.submit(() -> {
            try {
                DensityOverlay.Loaded b = DensityOverlay.load(map, channel);
                HeadlessFx.runLater(() -> {
                    if (!map.equals(mapCombo.getValue()) || !name.equals(brightnessCombo.getValue())) return;
                    brightnessLoaded = b;
                    if (shown != null) render();
                });
            } catch (Exception e) {
                logger.warn("Could not read brightness channel {} of {}", name, map.getFileName(), e);
                HeadlessFx.runLater(() -> status.setText("Could not read " + name + ": " + e.getMessage()));
            }
        });
    }

    private void load(boolean autoRangeAfter) {
        Path map = mapCombo.getValue();
        int ch = channelCombo.getSelectionModel().getSelectedIndex();
        loaded = null;
        if (map == null || ch < 0) return;
        loading = true;
        status.setText("Reading " + channelCombo.getValue() + "...");
        reader.submit(() -> {
            try {
                DensityOverlay.Loaded c = DensityOverlay.load(map, ch);
                HeadlessFx.runLater(() -> {
                    // The selection may have moved on while this was reading.
                    if (!map.equals(mapCombo.getValue())
                            || ch != channelCombo.getSelectionModel().getSelectedIndex()) {
                        return;
                    }
                    loaded = c;
                    loading = false;
                    var lat = c.info().lattice();
                    status.setText(String.format(
                                    Locale.ROOT,
                                    "%s: %d x %d cells of %d px (%s) for a %d x %d image.",
                                    channelCombo.getValue(),
                                    lat.gridW(),
                                    lat.gridH(),
                                    lat.cellPx(),
                                    c.info().smooth() ? "smooth" : "one value per cell",
                                    c.info().sourceWidth(),
                                    c.info().sourceHeight())
                            + (classChannelSelected()
                                    ? " Drawn in each class's colour; a window the model abstained on is not drawn."
                                    : ""));
                    if (autoRangeAfter) autoRange();
                    if (shown != null || showWhenLoaded) render();
                });
            } catch (Exception e) {
                logger.warn("Could not read channel {} of {}", ch, map.getFileName(), e);
                HeadlessFx.runLater(() -> {
                    loading = false;
                    showWhenLoaded = false;
                    status.setText("Could not read the map: " + e.getMessage());
                });
            }
        });
    }

    private void autoRange() {
        if (loaded == null || classChannelSelected()) return;
        double period = loaded.info().axialPeriod(loaded.channel());
        double[] r = period > 0 ? new double[] {0, period} : DensityOverlay.percentileRange(loaded.grid(), 1, 99);
        minField.setText(String.format(Locale.ROOT, "%.4g", r[0]));
        maxField.setText(String.format(Locale.ROOT, "%.4g", r[1]));
        if (shown != null) render();
    }

    private void render() {
        if (loaded == null) {
            if (loading) {
                // Show was pressed while the channel was still being read.
                showWhenLoaded = true;
                status.setText("Reading " + channelCombo.getValue() + "; it will be shown when ready.");
            } else {
                status.setText("Choose a map and channel first.");
            }
            return;
        }
        showWhenLoaded = false;
        DensityMapOverlay.Style style = classChannelSelected()
                ? DensityOverlay.classStyle(DensityOverlay.classNames(loaded.info()))
                : rangeStyle();
        if (style == null) return;
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            Dialogs.showErrorMessage(TITLE, "Open the image the map belongs to first.");
            return;
        }
        var server = viewer.getImageData().getServer();
        if (!loaded.info().fits(server.getWidth(), server.getHeight())) {
            status.setText("This map was made for a " + loaded.info().sourceWidth() + " x "
                    + loaded.info().sourceHeight() + " image; the open image is " + server.getWidth() + " x "
                    + server.getHeight() + ".");
            return;
        }
        boolean wantBrightness =
                !NO_BRIGHTNESS.equals(brightnessCombo.getValue()) && brightnessCombo.getValue() != null;
        shown = DensityOverlay.show(
                viewer, qupath.getOverlayOptions(), loaded, style, wantBrightness ? brightnessLoaded : null);
        if (style.isClasses()) {
            logger.info("Density overlay: {} in class colours {}", channelCombo.getValue(), style.classNames());
        } else {
            logger.info(
                    "Density overlay: {} [{}..{}] {}{}",
                    channelCombo.getValue(),
                    style.min(),
                    style.max(),
                    colourCombo.getValue(),
                    style.hideAtOrBelowMin() ? ", hiding values at or below the minimum" : "");
        }
    }

    /** The range and colour map from the controls, or null (with a status message) if they are not valid. */
    private DensityMapOverlay.Style rangeStyle() {
        double min;
        double max;
        try {
            min = Double.parseDouble(minField.getText().trim());
            max = Double.parseDouble(maxField.getText().trim());
        } catch (NumberFormatException e) {
            status.setText("The range must be two numbers.");
            return null;
        }
        if (!(max > min)) {
            status.setText("The upper end of the range must be above the lower end.");
            return null;
        }
        var cmap = ColorMaps.getColorMaps().get(colourCombo.getValue());
        if (cmap == null) return null;
        return DensityMapOverlay.Style.range(cmap, min, max, hideBelowBox.isSelected());
    }

    private void hide() {
        var viewer = qupath.getViewer();
        if (viewer != null) DensityOverlay.hide(viewer, shown);
        shown = null;
    }

    /** Shows a map's label rather than its full path. */
    private static final class NameCell extends ListCell<Path> {
        private final java.util.Map<Path, String> labels;

        NameCell(java.util.Map<Path, String> labels) {
            this.labels = labels;
        }

        @Override
        protected void updateItem(Path p, boolean empty) {
            super.updateItem(p, empty);
            setText(
                    empty || p == null
                            ? null
                            : labels.getOrDefault(p, p.getFileName().toString()));
        }
    }
}
