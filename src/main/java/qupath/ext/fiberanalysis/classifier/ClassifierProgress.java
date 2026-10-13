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

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.HeadlessFx;

/**
 * A one-line progress window with a working Cancel, for the classifier's
 * long jobs.
 *
 * <p>Training spends most of its time inside the permutation null, which is
 * {@code n_perm x n_slides} model fits and can run for minutes. A dialog that
 * says only "working" leaves the user unable to tell a slow run from a hung
 * one, so the status line names the stage.
 *
 * <p>Falls back to logging when the JavaFX toolkit is not running, so the
 * same workflow code drives a headless harness run.
 */
final class ClassifierProgress {

    private static final Logger logger = LoggerFactory.getLogger(ClassifierProgress.class);

    private final boolean headless;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Stage stage;
    private Label statusLabel;

    ClassifierProgress(Window owner, String title) {
        this.headless = !HeadlessFx.isReady();
        if (headless) return;
        HeadlessFx.runLater(() -> {
            statusLabel = new Label("Starting...");
            statusLabel.setWrapText(true);
            statusLabel.setMinWidth(380);
            ProgressBar bar = new ProgressBar();
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            Button cancel = new Button("Cancel");
            cancel.setOnAction(e -> {
                cancelled.set(true);
                cancel.setDisable(true);
                statusLabel.setText("Cancelling after the current step...");
            });
            VBox box = new VBox(10, statusLabel, bar, cancel);
            box.setPadding(new Insets(14));
            stage = new Stage();
            stage.setTitle(title);
            if (owner != null) stage.initOwner(owner);
            stage.initModality(Modality.NONE);
            stage.setScene(new Scene(box, 420, 130));
            stage.setOnCloseRequest(e -> cancelled.set(true));
            stage.show();
        });
    }

    void status(String text) {
        if (headless) {
            logger.info("[window classifier] {}", text);
            return;
        }
        HeadlessFx.runLater(() -> {
            if (statusLabel != null) statusLabel.setText(text);
        });
    }

    boolean cancelled() {
        return cancelled.get();
    }

    void close() {
        if (headless) return;
        HeadlessFx.runLater(() -> {
            if (stage != null) stage.close();
        });
    }
}
