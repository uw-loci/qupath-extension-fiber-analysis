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
 * Pick a density or classifier map for the open image and show one channel as
 * a coloured overlay ({@link DensityOverlay}). Non-modal; follows the open image.
 */
public final class DensityOverlayDialog {

    private static final Logger logger = LoggerFactory.getLogger(DensityOverlayDialog.class);
    static final String TITLE = "Fiber Analysis -- Density overlay";

    private final QuPathGUI qupath;
    private Stage stage;
    private ComboBox<Path> mapCombo;
    private ComboBox<String> channelCombo;
    private ComboBox<String> colourCombo;
    private TextField minField;
    private TextField maxField;
    private Label status;

    private DensityOverlay.Channel loaded;

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
        mapCombo.setCellFactory(l -> new NameCell());
        mapCombo.setButtonCell(new NameCell());
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
        Button autoBtn = new Button("Auto (1-99%)");
        autoBtn.setOnAction(e -> autoRange());
        status = new Label();
        status.setWrapText(true);
        status.setMaxWidth(420);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.addRow(0, new Label("Map"), mapCombo);
        grid.addRow(1, new Label("Channel"), channelCombo);
        grid.addRow(2, new Label("Colour map"), colourCombo);
        grid.addRow(3, new Label("Range"), new HBox(6, minField, new Label("to"), maxField, autoBtn));
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
        channelCombo.valueProperty().addListener((o, a, b) -> load(true));
        colourCombo.valueProperty().addListener((o, a, b) -> {
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

    private void refreshMaps() {
        ImageData<BufferedImage> data = qupath.getImageData();
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project == null || data == null ? null : project.getEntry(data);
        List<Path> maps = mapsFor(project, entry);
        mapCombo.getItems().setAll(maps);
        if (maps.isEmpty()) {
            status.setText(
                    entry == null
                            ? "Open a project image first."
                            : "No density or classifier map for this image yet. Run 'Project density map...'"
                                    + " or 'Apply window classifier...' first.");
            channelCombo.getItems().clear();
        } else {
            mapCombo.getSelectionModel().select(0);
        }
    }

    private void refreshChannels() {
        Path map = mapCombo.getValue();
        channelCombo.getItems().clear();
        loaded = null;
        if (map == null) return;
        try {
            for (var info : DensitySidecar.readChannelInfos(map))
                channelCombo.getItems().add(info.name);
            if (!channelCombo.getItems().isEmpty())
                channelCombo.getSelectionModel().select(0);
        } catch (IOException e) {
            status.setText("Could not read the map's channels: " + e.getMessage());
        }
    }

    private void load(boolean autoRangeAfter) {
        Path map = mapCombo.getValue();
        int ch = channelCombo.getSelectionModel().getSelectedIndex();
        loaded = null;
        if (map == null || ch < 0) return;
        status.setText("Reading " + channelCombo.getValue() + "...");
        reader.submit(() -> {
            try {
                DensityOverlay.Channel c = DensityOverlay.read(map, ch);
                HeadlessFx.runLater(() -> {
                    // The selection may have moved on while this was reading.
                    if (!map.equals(mapCombo.getValue())
                            || ch != channelCombo.getSelectionModel().getSelectedIndex()) {
                        return;
                    }
                    loaded = c;
                    status.setText(String.format(
                            Locale.ROOT,
                            "%s: %d x %d for a %d x %d image.",
                            channelCombo.getValue(),
                            c.width(),
                            c.height(),
                            c.fullWidth(),
                            c.fullHeight()));
                    if (autoRangeAfter) autoRange();
                    if (shown != null) render();
                });
            } catch (Exception e) {
                logger.warn("Could not read channel {} of {}", ch, map.getFileName(), e);
                HeadlessFx.runLater(() -> status.setText("Could not read the map: " + e.getMessage()));
            }
        });
    }

    private void autoRange() {
        if (loaded == null) return;
        double[] r = DensityOverlay.percentileRange(loaded.values(), 1, 99);
        minField.setText(String.format(Locale.ROOT, "%.4g", r[0]));
        maxField.setText(String.format(Locale.ROOT, "%.4g", r[1]));
        if (shown != null) render();
    }

    private void render() {
        if (loaded == null) {
            status.setText("Choose a map and channel first.");
            return;
        }
        double min;
        double max;
        try {
            min = Double.parseDouble(minField.getText().trim());
            max = Double.parseDouble(maxField.getText().trim());
        } catch (NumberFormatException e) {
            status.setText("The range must be two numbers.");
            return;
        }
        if (!(max > min)) {
            status.setText("The upper end of the range must be above the lower end.");
            return;
        }
        var cmap = ColorMaps.getColorMaps().get(colourCombo.getValue());
        if (cmap == null) return;
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            Dialogs.showErrorMessage(TITLE, "Open the image the map belongs to first.");
            return;
        }
        BufferedImage img = DensityOverlay.colour(loaded, cmap, min, max);
        shown = DensityOverlay.show(viewer, qupath.getOverlayOptions(), loaded, img);
        logger.info("Density overlay: {} [{}..{}] {}", channelCombo.getValue(), min, max, colourCombo.getValue());
    }

    private void hide() {
        var viewer = qupath.getViewer();
        if (viewer != null) DensityOverlay.hide(viewer, shown);
        shown = null;
    }

    /** Shows a map's file name rather than its full path. */
    private static final class NameCell extends ListCell<Path> {
        @Override
        protected void updateItem(Path p, boolean empty) {
            super.updateItem(p, empty);
            setText(empty || p == null ? null : p.getFileName().toString());
        }
    }
}
