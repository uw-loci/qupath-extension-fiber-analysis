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
import java.util.List;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Channels-mode follow-up to {@link FiberDensityMapWorkflow}: attach the
 * density-map sidecar onto the currently-open image as additional channels
 * via QuPath's {@link TransformedServerBuilder#concatChannels} machinery.
 *
 * <p>What attach does:
 *
 * <ol>
 *   <li>Locate the sidecar for the currently-open project entry.</li>
 *   <li>Open the sidecar with QuPath's standard {@code ImageServers.buildServer}.</li>
 *   <li>Build a concat'd server: source channels first, then sidecar channels.</li>
 *   <li>Wrap that in a new {@link ImageData} carrying the current image's
 *       hierarchy + image-type so annotations / detections survive the swap.</li>
 *   <li>Hand the new ImageData to the viewer via
 *       {@code viewer.setImageData(...)} -- mirrors what
 *       {@code QuPathGUI.openImageEntry} does on first open.</li>
 * </ol>
 *
 * <p>Caveats called out in earlier design discussion:
 *
 * <ul>
 *   <li>For RGB base images, concat forces the wrapped server to a non-RGB
 *       multi-channel pixel type and the RGB display path is lost. The
 *       user sees R/G/B as separate grey channels by default; we leave it
 *       to them to configure channel display colours (a future iteration
 *       can auto-set R/G/B per the spectral-unmixing pattern, but doing
 *       so reliably across QuPath display-API revisions is its own task).</li>
 *   <li>This is a SESSION-only attach. The project entry's stored
 *       ImageServerBuilder is untouched, so on the next time the user
 *       opens the entry they get the original server back. A future
 *       iteration adds a marker-file-based auto-reattach on image-open.</li>
 *   <li>Unsaved hierarchy changes survive because we copy the hierarchy
 *       reference into the new ImageData -- the old + new ImageData
 *       share the same hierarchy object until the viewer swap completes.</li>
 * </ul>
 */
public final class DensityChannelAttacher {

    private static final Logger logger = LoggerFactory.getLogger(DensityChannelAttacher.class);

    private DensityChannelAttacher() {}

    /**
     * Locate the sidecar for the currently-open image and attach its channels.
     * Returns true on success; surfaces an error dialog on the JavaFX thread
     * for the user-visible failure modes (no project, no entry, no sidecar).
     */
    public static boolean attachForCurrentImage(QuPathGUI gui) {
        if (gui == null) return false;
        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("Fiber density attach", "No image is open.");
            return false;
        }
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Fiber density attach",
                    "No project is open. The sidecar lives under "
                            + "<project>/fiber-analysis/density-maps/.");
            return false;
        }
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showErrorMessage(
                    "Fiber density attach", "Current image is not part of the open project.");
            return false;
        }
        Path sidecar = DensitySidecar.sidecarPathFor(project, entry);
        if (!DensitySidecar.exists(sidecar)) {
            Dialogs.showErrorMessage(
                    "Fiber density attach",
                    "No density sidecar for this image yet. Run \"Project density map...\" first.\n\n"
                            + "Expected: " + sidecar);
            return false;
        }
        try {
            attach(gui, imageData, sidecar);
            return true;
        } catch (Exception ex) {
            logger.error("Density attach failed", ex);
            Platform.runLater(() ->
                    Dialogs.showErrorMessage("Fiber density attach", "Attach failed: " + ex.getMessage()));
            return false;
        }
    }

    /**
     * Build the concat'd server and swap it into the viewer's ImageData.
     * Called from {@link #attachForCurrentImage(QuPathGUI)} and from the
     * workflow's post-Run hook in Channels mode; package-visible so other
     * pieces of the extension can drive the same attach without going
     * through the user-input checks.
     */
    static void attach(QuPathGUI gui, ImageData<BufferedImage> imageData, Path sidecar) throws Exception {
        if (!Files.isRegularFile(sidecar)) {
            throw new IOException("Sidecar not found: " + sidecar);
        }

        ImageServer<BufferedImage> sourceServer = imageData.getServer();
        ImageServer<BufferedImage> sidecarServer = ImageServers.buildServer(sidecar.toUri());

        ImageServer<BufferedImage> merged = new TransformedServerBuilder(sourceServer)
                .concatChannels(List.of(sidecarServer))
                .build();

        // Carry the hierarchy + image type into the new ImageData so the
        // user's annotations / detections survive the server swap.
        ImageData<BufferedImage> newImageData = new ImageData<>(
                merged, imageData.getHierarchy(), imageData.getImageType());

        final QuPathViewer viewer = gui.getViewer();
        if (viewer == null) {
            throw new IOException("No active QuPath viewer.");
        }
        Platform.runLater(() -> {
            try {
                viewer.setImageData(newImageData);
                logger.info(
                        "Attached density channels for {} ({} sidecar channels appended)",
                        sourceServer.getPath(),
                        sidecarServer.nChannels());
            } catch (IOException ex) {
                logger.error("viewer.setImageData failed during density attach", ex);
                Dialogs.showErrorMessage("Fiber density attach", "Setting the wrapped image failed: " + ex.getMessage());
            }
        });
    }
}
