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

import java.awt.image.BufferedImage;
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
import qupath.ext.fiberanalysis.analysis.DensityChannelAttacher;
import qupath.ext.fiberanalysis.analysis.DensitySamplingCommand;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisBatchDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisOverlayController;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisPanel;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapDialog;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.ui.PythonConsoleWindow;
import qupath.ext.fiberanalysis.ui.SetupEnvironmentDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

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
        FiberAnalysisPreferences.installPreferencePane(qupath);

        // Extract the bundled user-guide markdown to a stable per-user directory
        // and publish its absolute path via a system property, which is what the
        // dialog's Help button reads first.
        //
        // This used to ALSO mirror the file to <cwd>/documentation/fiber-analysis.md.
        // That path is relative, so it resolved against the JVM's working directory
        // and created a stray documentation/ folder in whatever repo QuPath was
        // launched from -- the QuPath bin dir, the monorepo root, an unrelated
        // extension checkout. Those strays then got committed by accident. The
        // mirror existed only to feed a fallback in openHelp() that the per-user
        // path already covers, so it is gone.
        extractBundledDocumentation();

        // Build the menu on the FX thread.
        Platform.runLater(() -> {
            addMenuItems(qupath);
            installAutoReattachHook(qupath);
        });
    }

    /**
     * Installs a listener on {@link QuPathGUI#imageDataProperty()} that
     * checks for an auto-reattach marker beside the just-opened image's
     * density sidecar and, if present, attaches the density channels via
     * {@link DensityChannelAttacher}. Idempotent: re-entrant calls
     * triggered by our own setImageData are skipped because the wrapped
     * server's type contains "concat".
     */
    private void installAutoReattachHook(QuPathGUI qupath) {
        qupath.imageDataProperty().addListener((obs, oldData, newData) -> {
            if (newData == null) return;
            // Skip if this is the post-attach event (the new ImageData wraps
            // a ConcatChannelsImageServer). String-match the server type
            // because the class itself is package-private in qupath-core.
            try {
                String type = newData.getServer().getServerType();
                if (type != null && type.toLowerCase().contains("concat")) return;
            } catch (Exception ignored) {
                // best-effort; fall through to the attach attempt
            }
            Project<BufferedImage> project = qupath.getProject();
            if (project == null) return;
            ProjectImageEntry<BufferedImage> entry = project.getEntry(newData);
            if (entry == null) return;
            Path sidecar = DensitySidecar.sidecarPathFor(project, entry);
            if (sidecar == null || !DensitySidecar.exists(sidecar) || !DensitySidecar.autoReattachEnabled(sidecar)) {
                return;
            }
            logger.info("Auto-reattach: density sidecar marker present, attaching for {}", entry.getImageName());
            // Schedule on a microtask so the imageDataProperty listener
            // chain returns before we trigger another setImageData. Calling
            // setImageData inside a listener-on-imageDataProperty is allowed
            // in QuPath, but defers a frame so the UI stays responsive.
            Platform.runLater(() -> DensityChannelAttacher.attachForCurrentImage(qupath, false));
        });
        logger.info("Density auto-reattach hook installed");
    }

    /**
     * Copies the bundled {@code fiber-analysis.md} resource out of the JAR to a
     * stable per-user location under the user's home directory, and publishes the
     * absolute path as the {@code qupath.ext.fiberanalysis.docPath} system
     * property. {@code FiberAnalysisDialog.openHelp()} reads that property first.
     * <p>
     * Everything written here is under {@code user.home} -- never relative to the
     * working directory, which changes with however QuPath was launched.
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
        // a float32 pyramid OME-TIFF sidecar per image; the user picks whether
        // to attach it as channels (changes how the base image renders) or
        // keep it as a separate sidecar with sampling commands (preserves the
        // native display). Validation rules for the type / mode combos live
        // in FiberDensityMapDialog; this menu only needs an open project.
        MenuItem densityMapItem = new MenuItem("Project density map...");
        densityMapItem.disableProperty().bind(qupath.projectProperty().isNull());
        densityMapItem.setOnAction(e -> {
            logger.info("Opening Fiber Analysis project-density-map dialog");
            try {
                new FiberDensityMapDialog(qupath).show();
            } catch (Exception ex) {
                logger.error("Failed to open Fiber Analysis density-map dialog", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to open dialog: " + ex.getMessage());
            }
        });

        // Sidecar-mode companion: reads the density-map sidecar for the
        // currently open image and adds per-channel mean measurements onto
        // selected objects (or all annotations if nothing is selected).
        // Equivalent to the QuPath built-in "Add intensity features" but
        // pulls from the sidecar instead of an attached channel, so the
        // base RGB display stays native.
        MenuItem sampleDensityItem = new MenuItem("Sample fiber density into measurements");
        sampleDensityItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        sampleDensityItem.setOnAction(e -> {
            logger.info("Running Fiber density sampling command");
            try {
                new DensitySamplingCommand(qupath).run();
            } catch (Exception ex) {
                logger.error("Density sampling command failed to launch", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to launch sampling: " + ex.getMessage());
            }
        });

        // Channels-mode follow-up: attach the density sidecar onto the open
        // image as extra channels. Per design discussion this is a session-
        // only attach; the project's stored server builder is untouched, so
        // the next time the image is opened the user sees the original
        // server back. Auto-reattach on open is a later iteration.
        MenuItem attachDensityItem = new MenuItem("Attach density channels");
        attachDensityItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        attachDensityItem.setOnAction(e -> {
            logger.info("Running Fiber density attach-as-channels command");
            try {
                DensityChannelAttacher.attachForCurrentImage(qupath);
            } catch (Exception ex) {
                logger.error("Density attach command failed to launch", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed to launch attach: " + ex.getMessage());
            }
        });

        // Opt-out for the cross-session auto-reattach hook. Deletes the
        // <sidecar>.attach marker so future image-opens get the original
        // server. The sidecar itself stays on disk for the sampling command
        // and manual reattach.
        MenuItem stopAutoReattachItem = new MenuItem("Stop auto-reattaching density channels");
        stopAutoReattachItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        stopAutoReattachItem.setOnAction(e -> {
            try {
                Project<BufferedImage> project = qupath.getProject();
                if (project == null) {
                    Dialogs.showErrorMessage(EXTENSION_NAME, "No project is open.");
                    return;
                }
                ProjectImageEntry<BufferedImage> entry = project.getEntry(qupath.getImageData());
                if (entry == null) {
                    Dialogs.showErrorMessage(EXTENSION_NAME, "Current image is not part of the open project.");
                    return;
                }
                Path sidecar = DensitySidecar.sidecarPathFor(project, entry);
                if (sidecar == null || !DensitySidecar.exists(sidecar)) {
                    Dialogs.showErrorMessage(EXTENSION_NAME, "No density sidecar for this image -- nothing to do.");
                    return;
                }
                boolean removed = DensitySidecar.clearAutoReattachMarker(sidecar);
                if (removed) {
                    Dialogs.showInfoNotification(
                            EXTENSION_NAME, "Auto-reattach disabled for this image. Sidecar TIFF kept on disk.");
                } else {
                    Dialogs.showInfoNotification(
                            EXTENSION_NAME, "Auto-reattach was not enabled for this image (no marker found).");
                }
            } catch (Exception ex) {
                logger.error("Stop-auto-reattach failed", ex);
                Dialogs.showErrorMessage(EXTENSION_NAME, "Failed: " + ex.getMessage());
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
                .addAll(
                        runItem,
                        batchItem,
                        densityMapItem,
                        sampleDensityItem,
                        attachDensityItem,
                        stopAutoReattachItem,
                        new SeparatorMenuItem(),
                        setupItem,
                        pyConsoleItem);
        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }
}
