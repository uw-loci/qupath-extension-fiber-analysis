/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.service.ApposeFiberService;

/**
 * Minimal setup/rebuild dialog for the Fiber Analysis Appose environment.
 *
 * <p>Shows current env status (built / not built) and a "Rebuild environment"
 * button. Rebuild = shut down the running Python service, delete the on-disk
 * env directory, and re-run {@code initialize()} which will re-extract
 * fiberlib and run {@code pixi install}.
 *
 * <p>This is the simpler of the two dialog patterns called for in the spec.
 * If a richer wizard is needed later, model it on
 * {@code qupath.ext.ppm.ui.SetupEnvironmentDialog}.
 */
public class SetupEnvironmentDialog {

    private static final Logger logger = LoggerFactory.getLogger(SetupEnvironmentDialog.class);

    private final Stage stage;
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    private final Button buildButton = new Button("Build environment");
    private final Button rebuildButton = new Button("Rebuild environment");
    private final Button closeButton = new Button("Close");

    public SetupEnvironmentDialog(Window owner) {
        this.stage = new Stage();
        stage.setTitle("Fiber Analysis Environment");
        stage.initModality(Modality.NONE);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setResizable(false);

        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);
        progressBar.setProgress(-1); // indeterminate when shown

        Label header = new Label("Fiber Analysis Python environment");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label pathLabel = new Label("Location: " + ApposeFiberService.getEnvironmentPath());
        pathLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        statusLabel.setStyle("-fx-text-fill: #444; -fx-font-size: 12px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(420);

        buildButton.setOnAction(e -> runBuild(false));
        rebuildButton.setOnAction(e -> runBuild(true));
        closeButton.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, buildButton, rebuildButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, header, pathLabel, statusLabel, progressBar, buttons);
        root.setPadding(new Insets(16));
        root.setPrefWidth(460);

        refreshStatus();
        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    private void refreshStatus() {
        boolean built = ApposeFiberService.isEnvironmentBuilt();
        statusLabel.setText(
                built
                        ? "Status: environment appears built. Click Rebuild to force a clean rebuild."
                        : "Status: environment NOT built yet. Click Build to download dependencies "
                                + "(this can take several minutes the first time).");
        buildButton.setDisable(built);
    }

    private void runBuild(boolean forceRebuild) {
        buildButton.setDisable(true);
        rebuildButton.setDisable(true);
        closeButton.setDisable(true);
        progressBar.setVisible(true);

        Thread t = new Thread(
                () -> {
                    try {
                        ApposeFiberService svc = ApposeFiberService.getInstance();
                        if (forceRebuild) {
                            updateStatus("Shutting down running Python service...");
                            svc.shutdown();
                            updateStatus("Deleting on-disk environment...");
                            svc.deleteEnvironment();
                        }
                        svc.initialize(this::updateStatus);
                        Platform.runLater(() -> {
                            statusLabel.setText("Setup complete. fiberlib " + svc.getInstalledFiberlibVersion());
                            progressBar.setVisible(false);
                            rebuildButton.setDisable(false);
                            closeButton.setDisable(false);
                            refreshStatus();
                        });
                    } catch (Exception ex) {
                        logger.error("Setup failed", ex);
                        Platform.runLater(() -> {
                            statusLabel.setText("Setup failed: " + ex.getMessage());
                            progressBar.setVisible(false);
                            buildButton.setDisable(false);
                            rebuildButton.setDisable(false);
                            closeButton.setDisable(false);
                        });
                    }
                },
                "FiberAnalysis-Setup");
        t.setDaemon(true);
        t.start();
    }

    private void updateStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }
}
