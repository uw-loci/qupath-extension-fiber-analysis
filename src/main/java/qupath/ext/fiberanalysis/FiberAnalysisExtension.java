/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisBatchDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisOverlayController;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisPanel;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.ui.PythonConsoleWindow;
import qupath.ext.fiberanalysis.ui.SetupEnvironmentDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Testbed QuPath extension for fiber-shape analysis on segmented collagen fibers.
 *
 * <p>Registers two top-level menu actions under {@code Extensions > Fiber Analysis}:
 * <ul>
 *     <li><b>Run analysis...</b> -- opens the configuration dialog and dispatches
 *     an analysis pass against the currently selected annotations.</li>
 *     <li><b>Setup environment...</b> -- builds / rebuilds the bundled Appose
 *     pixi environment that ships with this extension.</li>
 * </ul>
 *
 * <p>The Appose service is initialized lazily on first action invocation so the
 * ~500 MB first-time pixi build does not run at QuPath startup.
 */
public class FiberAnalysisExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisExtension.class);

    private static final String EXTENSION_NAME = "Fiber Analysis";
    private static final String EXTENSION_DESCRIPTION =
            "Testbed: fiber straightness, TWOMBLI-derived morphometrics, and GLCM texture "
                    + "on segmented collagen fibers.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

    private static final String DOC_RESOURCE = "/qupath/ext/fiberanalysis/documentation/fiber-analysis.md";
    private static final String DOC_RELATIVE_PATH = "documentation/fiber-analysis.md";
    private static final String DOC_INSTALL_SUBDIR = "QuPath/v0.7/extensions/fiber-analysis-docs";

    private boolean installed = false;

    /**
     * Singleton results panel. Created at install time and surfaced in its own
     * non-modal Stage on first appendResult (pattern mirrored from
     * {@code PPMPerpendicularityWorkflow.ensureResultWindow} --
     * {@code qupath-extension-ppm/src/main/java/qupath/ext/ppm/analysis/PPMPerpendicularityWorkflow.java:1928-1941}).
     * Held on the Extension so the Dialog can hand the same instance to the
     * Workflow without reaching into QuPath internals.
     */
    private static FiberAnalysisPanel resultPanel;

    private static Stage resultStage;

    /** Returns the singleton results panel, creating it if needed. */
    public static synchronized FiberAnalysisPanel getOrCreatePanel() {
        if (resultPanel == null) {
            resultPanel = new FiberAnalysisPanel();
        }
        return resultPanel;
    }

    /**
     * Ensures the results-panel Stage is showing and brought to front. Must be
     * called on the JavaFX thread. Mirrors PPM's ensureResultWindow pattern.
     * Synchronised to satisfy SpotBugs LI_LAZY_INIT_UPDATE_STATIC.
     */
    public static synchronized void ensureResultWindow(QuPathGUI qupath) {
        FiberAnalysisPanel panel = getOrCreatePanel();
        // Build the Stage + Scene exactly once. If the user hides the window
        // (clicks the X) we re-show the same Stage instead of constructing a
        // new Scene -- the panel is already the root of the original Scene
        // and JavaFX refuses to re-parent a Node that still belongs to a
        // Scene. Without this guard, the second Run after closing the window
        // throws IllegalArgumentException("... already set as root of another
        // scene").
        if (resultStage == null) {
            resultStage = new Stage();
            resultStage.setTitle("Fiber Analysis Results");
            resultStage.setScene(new Scene(panel, 550, 600));
            if (qupath != null && qupath.getStage() != null) {
                resultStage.initOwner(qupath.getStage());
            }
            // Hide on close (like the Python console window) so the next Run
            // can re-show the existing stage rather than rebuilding it.
            // Also clear any active overlay so a stale heatmap painted for
            // one image does not stay on the viewer after the user closes
            // the result window.
            resultStage.setOnCloseRequest(e -> {
                e.consume();
                FiberAnalysisOverlayController.getInstance().clear();
                resultStage.hide();
            });
        }
        resultStage.show();
        resultStage.toFront();
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) {
            logger.debug("Fiber Analysis extension already installed; skipping");
            return;
        }
        installed = true;

        logger.info("Installing extension: {}", EXTENSION_NAME);

        // Register persistent preferences (idempotent if called twice in dev mode).
        FiberAnalysisPreferences.installPreferences();

        // Extract the bundled user-guide markdown to two locations:
        //   1. A stable per-user directory (so it survives between QuPath launches
        //      regardless of where the JVM was started from).
        //   2. A best-effort copy at <cwd>/documentation/fiber-analysis.md so the
        //      dialog's existing relative-path-based Help button finds it without
        //      requiring a dialog edit. If the cwd is not writable (e.g. the
        //      QuPath bin directory under Program Files), the copy is skipped
        //      silently and users still have option 1 via the log message.
        extractBundledDocumentation();

        // Build the menu on the FX thread.
        Platform.runLater(() -> addMenuItems(qupath));
    }

    /**
     * Copies the bundled {@code fiber-analysis.md} resource out of the JAR to a
     * stable per-user location, and best-effort to a CWD-relative copy that the
     * existing Help button (which uses {@code Path.of("documentation/fiber-analysis.md")})
     * can pick up. The per-user location is logged so users can find the doc
     * even if the CWD copy fails.
     */
    private void extractBundledDocumentation() {
        // 1. Stable per-user location.
        Path userDocsDir = Paths.get(System.getProperty("user.home"), DOC_INSTALL_SUBDIR);
        Path userDocPath = userDocsDir.resolve("fiber-analysis.md");
        try {
            Files.createDirectories(userDocsDir);
            try (InputStream in = FiberAnalysisExtension.class.getResourceAsStream(DOC_RESOURCE)) {
                if (in == null) {
                    logger.warn("Bundled documentation resource not found: {}", DOC_RESOURCE);
                    return;
                }
                Files.copy(in, userDocPath, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Extracted bundled user guide to {} (open this file for the full doc)", userDocPath);
                System.setProperty(
                        "qupath.ext.fiberanalysis.docPath",
                        userDocPath.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            logger.warn("Could not extract bundled user guide to {}: {}", userDocPath, e.getMessage());
            return;
        }

        // 2. Best-effort CWD copy (so the dialog's existing relative-path Help
        //    button works without editing the dialog). Failures here are silent
        //    because the CWD is often not writable (Program Files on Windows).
        try {
            Path cwdDocPath = Paths.get(DOC_RELATIVE_PATH);
            Path cwdDocDir = cwdDocPath.getParent();
            if (cwdDocDir != null) {
                Files.createDirectories(cwdDocDir);
            }
            Files.copy(userDocPath, cwdDocPath, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Also mirrored user guide to CWD-relative {}", cwdDocPath);
        } catch (IOException e) {
            logger.debug(
                    "Could not mirror user guide to CWD-relative path (this is fine; "
                            + "the per-user copy at {} is the canonical location): {}",
                    userDocPath,
                    e.getMessage());
        }
    }

    private void addMenuItems(QuPathGUI qupath) {
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem runItem = new MenuItem("Run analysis... (research use only)");
        runItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        runItem.setOnAction(e -> {
            logger.info("Opening Fiber Analysis configuration dialog");
            try {
                // Phase 5 fix (B1 + B2): drop the multi-agent reflective scaffold.
                // Direct-instantiate the dialog with the singleton results panel
                // (created lazily here so the panel only exists when the user
                // first opens the dialog -- nothing is added to the QuPath UI
                // until then).
                FiberAnalysisPanel panel = getOrCreatePanel();
                new FiberAnalysisDialog(qupath, panel).show();
            } catch (Exception ex) {
                logger.error("Failed to open Fiber Analysis dialog", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to open dialog: " + ex.getMessage());
            }
        });

        MenuItem batchItem = new MenuItem("Run on project images...");
        batchItem.disableProperty().bind(qupath.projectProperty().isNull());
        batchItem.setOnAction(e -> {
            logger.info("Opening Fiber Analysis batch dialog");
            try {
                FiberAnalysisPanel panel = getOrCreatePanel();
                new FiberAnalysisBatchDialog(qupath, panel).show();
            } catch (Exception ex) {
                logger.error("Failed to open Fiber Analysis batch dialog", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to open batch dialog: " + ex.getMessage());
            }
        });

        // Whole-slide density-map output across multiple project images. Writes
        // a uint16 pyramid OME-TIFF sidecar per image; the user picks whether
        // to attach it as channels (changes how the base image renders) or
        // keep it as a separate sidecar with sampling commands (preserves the
        // native display). Validation rules for the type / mode combos live
        // in FiberDensityMapDialog; this menu only needs an open project.
        MenuItem densityMapItem = new MenuItem("Project density map...");
        densityMapItem.disableProperty().bind(qupath.projectProperty().isNull());
        densityMapItem.setOnAction(e -> {
            logger.info("Opening Fiber Analysis project-density-map dialog");
            try {
                new qupath.ext.fiberanalysis.analysis.FiberDensityMapDialog(qupath).show();
            } catch (Exception ex) {
                logger.error("Failed to open Fiber Analysis density-map dialog", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to open dialog: " + ex.getMessage());
            }
        });

        MenuItem setupItem = new MenuItem("Setup environment...");
        setupItem.setOnAction(e -> {
            logger.info("Opening Fiber Analysis setup environment dialog");
            SetupEnvironmentDialog dlg = new SetupEnvironmentDialog(qupath != null ? qupath.getStage() : null);
            dlg.show();
        });

        MenuItem pyConsoleItem = new MenuItem("Python console");
        pyConsoleItem.setOnAction(e -> {
            logger.debug("Opening Fiber Analysis Python console window");
            PythonConsoleWindow.getInstance().show();
        });

        extensionMenu
                .getItems()
                .addAll(runItem, batchItem, densityMapItem, new SeparatorMenuItem(), setupItem, pyConsoleItem);
        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }
}
