/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Pattern lifted from qupath-extension-ppm/src/main/java/qupath/ext/ppm/ui/PythonConsoleWindow.java
 * (Apache-2.0, same author/lab). Behaviour is identical except for the window
 * title; if you fix a bug here please mirror it back to PPM (and QP-CAT, and
 * the DL pixel classifier -- they all share this idiom).
 */
package qupath.ext.fiberanalysis.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

/**
 * Singleton JavaFX window displaying live Python process output from the
 * fiber-analysis Appose service.
 *
 * <p>Messages are buffered from the moment {@link #appendMessage(String)} is
 * first called, even before the window is created or shown. When the user opens
 * the console, all buffered history is immediately visible.
 *
 * <p>Thread safety: {@link #appendMessage(String)} can be called from any
 * thread. Messages are queued in a lock-free {@link ConcurrentLinkedQueue} and
 * flushed to the JavaFX TextArea via coalesced {@code Platform.runLater()}
 * calls.
 *
 * <p><b>Wiring:</b> {@code ApposeFiberService} attaches its
 * {@code pythonService.debug(...)} handler to {@link #appendMessage(String)} so
 * every Python stderr line lands here.
 */
public class PythonConsoleWindow {

    private static final Logger logger = LoggerFactory.getLogger(PythonConsoleWindow.class);

    private static final int MAX_BUFFER_LINES = 10_000;
    private static final int TRIM_AMOUNT = 2_000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean flushPending = new AtomicBoolean(false);
    private static final LinkedList<String> lineBuffer = new LinkedList<>();

    private static PythonConsoleWindow instance;

    private Stage stage;
    private TextArea textArea;
    private CheckBox autoScrollCheck;

    private PythonConsoleWindow() {
        // singleton
    }

    /** Lazy singleton. Must be invoked on the JavaFX Application Thread. */
    public static synchronized PythonConsoleWindow getInstance() {
        if (instance == null) {
            instance = new PythonConsoleWindow();
            instance.createWindow();
        }
        return instance;
    }

    /**
     * Appends a message to the console buffer. Thread-safe. Pre-show messages
     * are retained, so opening the console late still shows the full history.
     */
    public static void appendMessage(String msg) {
        if (msg == null) return;
        String timestamp = LocalTime.now().format(TIME_FMT);
        messageQueue.add("[" + timestamp + "] " + msg);
        if (flushPending.compareAndSet(false, true)) {
            // Headless guard: in `QuPath script` mode the JavaFX toolkit was
            // never started, so Platform.runLater throws ISE. The queue is
            // still useful (a later show() would flush it), but we must not
            // bubble the failure -- this method is called from Appose's
            // background thread to log every Python debug line, and we
            // don't want to crash the entire run on a logging call.
            if (qupath.ext.fiberanalysis.analysis.HeadlessFx.isReady()) {
                Platform.runLater(PythonConsoleWindow::flushQueue);
            } else {
                // Reset so subsequent calls keep trying once FX is up.
                flushPending.set(false);
            }
        }
    }

    private static void flushQueue() {
        flushPending.set(false);
        String msg;
        while ((msg = messageQueue.poll()) != null) {
            lineBuffer.add(msg);
        }
        if (lineBuffer.size() > MAX_BUFFER_LINES) {
            int toRemove = lineBuffer.size() - MAX_BUFFER_LINES + TRIM_AMOUNT;
            for (int i = 0; i < toRemove && !lineBuffer.isEmpty(); i++) {
                lineBuffer.removeFirst();
            }
        }
        if (instance != null && instance.textArea != null) {
            instance.rebuildTextArea();
        }
    }

    private void rebuildTextArea() {
        StringBuilder sb = new StringBuilder();
        for (String line : lineBuffer) {
            sb.append(line).append('\n');
        }
        textArea.setText(sb.toString());
        if (autoScrollCheck != null && autoScrollCheck.isSelected()) {
            textArea.positionCaret(textArea.getLength());
        }
    }

    private void createWindow() {
        stage = new Stage();
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
        }
        stage.setTitle("Fiber Analysis -- Python Console");
        stage.setWidth(800);
        stage.setHeight(500);

        textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(Font.font("monospace", 12));

        Button clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> {
            lineBuffer.clear();
            messageQueue.clear();
            textArea.clear();
        });

        autoScrollCheck = new CheckBox("Auto-scroll");
        autoScrollCheck.setSelected(true);

        Button saveBtn = new Button("Save to file...");
        saveBtn.setOnAction(e -> saveToFile());

        ToolBar toolbar = new ToolBar(clearBtn, autoScrollCheck, saveBtn);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(textArea);
        BorderPane.setMargin(textArea, new Insets(2));

        stage.setScene(new Scene(root));

        // Hide on close instead of destroying -- the buffer keeps growing.
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
        });

        if (!lineBuffer.isEmpty()) {
            rebuildTextArea();
        }
    }

    /** Shows the console window and brings it to front. */
    public void show() {
        if (stage != null) {
            stage.show();
            stage.toFront();
        }
    }

    private void saveToFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Python console log");
        chooser.setInitialFileName("fiber_python_console.log");
        chooser.getExtensionFilters()
                .addAll(
                        new FileChooser.ExtensionFilter("Log files", "*.log"),
                        new FileChooser.ExtensionFilter("Text files", "*.txt"),
                        new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : lineBuffer) {
                sb.append(line).append(System.lineSeparator());
            }
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            logger.info("Python console log saved to: {}", file.getAbsolutePath());
        } catch (IOException ex) {
            logger.error("Failed to save console log: {}", ex.getMessage());
        }
    }
}
