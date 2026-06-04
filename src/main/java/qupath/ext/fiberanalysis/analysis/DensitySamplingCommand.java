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

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Menu command: for the currently open image, locate the density-map
 * sidecar (written by {@link FiberDensityMapWorkflow}), sample every
 * channel within each target object's ROI, and add the per-channel mean
 * back as a measurement on the object. Each measurement is keyed by the
 * channel name ({@code "Density: Fiber coverage (%)"} etc.), so a single
 * sampling run produces N new columns in the QuPath measurement table.
 *
 * <p>This is the Sidecar-mode equivalent of "Add intensity features" --
 * the base image's RGB display is untouched, and the user simply runs
 * this command after the density-map compute to get the per-object
 * numbers without any channel concat.
 *
 * <p>Targets, in priority order:
 * <ol>
 *   <li>Selected objects, if anything is selected.</li>
 *   <li>Otherwise, every annotation in the hierarchy.</li>
 * </ol>
 *
 * <p>Per-channel averaging: arithmetic mean of valid pixels (raw &gt; 0)
 * within the object's ROI, then promoted to real units via the per-channel
 * {@code scale + offset} recovered from the sidecar OME-XML description.
 * Mean-angle is treated as a regular scalar in v1; an axial-circular
 * averaging refinement is a follow-up.
 */
public final class DensitySamplingCommand {

    private static final Logger logger = LoggerFactory.getLogger(DensitySamplingCommand.class);

    /** Measurement-name prefix so the new columns are easy to spot in the table. */
    private static final String MEASUREMENT_PREFIX = "Density: ";

    private final QuPathGUI gui;

    public DensitySamplingCommand(QuPathGUI gui) {
        this.gui = gui;
    }

    public void run() {
        ImageData<BufferedImage> imageData = gui != null ? gui.getImageData() : null;
        if (imageData == null) {
            Dialogs.showErrorMessage("Fiber density sampling", "No image is open.");
            return;
        }
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Fiber density sampling",
                    "No project is open. The sampling command reads the sidecar from "
                            + "<project>/fiber-analysis/density-maps/.");
            return;
        }
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showErrorMessage("Fiber density sampling", "Current image is not part of the open project.");
            return;
        }

        Path sidecar = DensitySidecar.sidecarPathFor(project, entry);
        if (!DensitySidecar.exists(sidecar)) {
            Dialogs.showErrorMessage(
                    "Fiber density sampling",
                    "No density sidecar for this image yet. Run \"Project density map...\" first.\n\n"
                            + "Expected: " + sidecar);
            return;
        }

        // Resolve target objects on the current image.
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        List<PathObject> targets = resolveTargets(hierarchy);
        if (targets.isEmpty()) {
            Dialogs.showErrorMessage(
                    "Fiber density sampling",
                    "No objects to sample. Either select one or more objects, or add annotations first.");
            return;
        }

        Thread worker = new Thread(
                () -> sampleAsync(imageData, sidecar, targets), "FiberDensitySampling-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void sampleAsync(ImageData<BufferedImage> imageData, Path sidecar, List<PathObject> targets) {
        try {
            sample(imageData, sidecar, targets);
        } catch (Exception e) {
            logger.error("Density sampling failed", e);
            final String msg = e.getMessage();
            Platform.runLater(() -> Dialogs.showErrorMessage("Fiber density sampling", "Failed: " + msg));
        }
    }

    private void sample(ImageData<BufferedImage> imageData, Path sidecar, List<PathObject> targets) throws Exception {
        ImageServer<BufferedImage> sourceServer = imageData.getServer();
        double sourcePxUm = sourcePixelSizeUm(sourceServer);
        if (sourcePxUm <= 0 || Double.isNaN(sourcePxUm)) {
            throw new IOException("Source image has no pixel calibration; cannot align sidecar.");
        }

        List<DensitySidecar.ChannelInfo> channelInfos = DensitySidecar.readChannelInfos(sidecar);
        if (channelInfos.isEmpty()) {
            throw new IOException("Sidecar has no parseable channel descriptors; cannot sample.");
        }

        try (ImageServer<BufferedImage> sidecarServer = ImageServers.buildServer(sidecar.toUri())) {
            PixelCalibration sidecarCal = sidecarServer.getPixelCalibration();
            double sidecarPxUm = sidecarCal != null && sidecarCal.hasPixelSizeMicrons()
                    ? sidecarCal.getAveragedPixelSizeMicrons()
                    : sourcePxUm;
            double srcToSidecar = sourcePxUm / sidecarPxUm; // multiply source-pixel coords by this to get sidecar-pixel coords
            int sidecarW = sidecarServer.getWidth();
            int sidecarH = sidecarServer.getHeight();
            int nChannels = sidecarServer.nChannels();
            if (channelInfos.size() != nChannels) {
                logger.warn(
                        "Channel-info count ({}) differs from sidecar channel count ({}); using min(),"
                                + " missing channels will be skipped",
                        channelInfos.size(),
                        nChannels);
            }
            int channelsToSample = Math.min(channelInfos.size(), nChannels);

            int done = 0;
            for (PathObject obj : targets) {
                ROI roi = obj.getROI();
                if (roi == null) {
                    done++;
                    continue;
                }

                // ROI bbox in source pixel coords; project to sidecar coords.
                double sx0 = roi.getBoundsX() * srcToSidecar;
                double sy0 = roi.getBoundsY() * srcToSidecar;
                double sw = roi.getBoundsWidth() * srcToSidecar;
                double sh = roi.getBoundsHeight() * srcToSidecar;
                int x0 = clamp((int) Math.floor(sx0), 0, sidecarW - 1);
                int y0 = clamp((int) Math.floor(sy0), 0, sidecarH - 1);
                int x1 = clamp((int) Math.ceil(sx0 + sw), x0 + 1, sidecarW);
                int y1 = clamp((int) Math.ceil(sy0 + sh), y0 + 1, sidecarH);
                int rw = x1 - x0;
                int rh = y1 - y0;
                if (rw <= 0 || rh <= 0) {
                    done++;
                    continue;
                }

                RegionRequest req = RegionRequest.createInstance(
                        sidecarServer.getPath(), 1.0, x0, y0, rw, rh);
                BufferedImage tile;
                try {
                    tile = sidecarServer.readRegion(req);
                } catch (IOException ex) {
                    logger.warn("Sidecar read failed for object {}: {}", obj, ex.getMessage());
                    done++;
                    continue;
                }
                if (tile == null) {
                    done++;
                    continue;
                }

                // Per-pixel polygon test: ROI is in source coords, so transform
                // each sidecar pixel back to source coords and ask the ROI's
                // shape if it contains. For axis-aligned bboxes (Rectangle ROIs)
                // this collapses to "include every pixel".
                Shape sourceShape = roi.getShape();
                boolean isRect = sourceShape != null
                        && roi.getRoiName() != null
                        && "Rectangle".equalsIgnoreCase(roi.getRoiName());
                double sidecarToSrc = sidecarPxUm / sourcePxUm;

                long[] sumPerChannel = new long[channelsToSample];
                long[] countPerChannel = new long[channelsToSample];

                int tileW = tile.getWidth();
                int tileH = tile.getHeight();
                // Read all channels in one pass via separate getSamples calls;
                // this is cheap on the tiny sidecar tile and avoids a per-pixel
                // n-channel decode loop.
                int[][] perChannelSamples = new int[channelsToSample][];
                for (int c = 0; c < channelsToSample; c++) {
                    perChannelSamples[c] = tile.getRaster().getSamples(0, 0, tileW, tileH, c, (int[]) null);
                }

                for (int ty = 0; ty < tileH; ty++) {
                    // Sidecar pixel (x0+tx, y0+ty) maps to source physical coords
                    // ((x0+tx) * sidecarPxUm, (y0+ty) * sidecarPxUm), which in
                    // source pixels is (sidecar * sidecarToSrc).
                    double srcY = (y0 + ty + 0.5) * sidecarToSrc;
                    for (int tx = 0; tx < tileW; tx++) {
                        if (!isRect) {
                            double srcX = (x0 + tx + 0.5) * sidecarToSrc;
                            if (sourceShape != null && !sourceShape.contains(srcX, srcY)) continue;
                        }
                        int idx = ty * tileW + tx;
                        for (int c = 0; c < channelsToSample; c++) {
                            int raw = perChannelSamples[c][idx];
                            if (raw <= 0) continue; // sentinel
                            sumPerChannel[c] += raw;
                            countPerChannel[c] += 1L;
                        }
                    }
                }

                // Write per-channel measurements on this object.
                for (int c = 0; c < channelsToSample; c++) {
                    String name = MEASUREMENT_PREFIX + channelInfos.get(c).name;
                    double real;
                    if (countPerChannel[c] == 0L) {
                        real = Double.NaN;
                    } else {
                        double meanRaw = (double) sumPerChannel[c] / (double) countPerChannel[c];
                        real = meanRaw * channelInfos.get(c).scale + channelInfos.get(c).offset;
                    }
                    obj.getMeasurementList().put(name, real);
                }
                done++;
            }

            // QuPath caches the measurement table; tell it the hierarchy changed.
            imageData.getHierarchy().fireObjectMeasurementsChangedEvent(this, targets);
            final int doneF = done;
            final int chF = channelsToSample;
            Platform.runLater(() -> Dialogs.showInfoNotification(
                    "Fiber density sampling",
                    "Sampled " + chF + " channel(s) into " + doneF + " object measurement(s)."));
        }
    }

    private static List<PathObject> resolveTargets(PathObjectHierarchy hierarchy) {
        List<PathObject> selected = hierarchy.getSelectionModel().getSelectedObjects().stream()
                .filter(o -> o != null && o.hasROI())
                .toList();
        if (!selected.isEmpty()) return new ArrayList<>(selected);
        return new ArrayList<>(PathObjectTools.getFlattenedObjectList(hierarchy.getRootObject(), null, false).stream()
                .filter(PathObject::isAnnotation)
                .filter(PathObject::hasROI)
                .toList());
    }

    private static double sourcePixelSizeUm(ImageServer<BufferedImage> server) {
        PixelCalibration cal = server.getPixelCalibration();
        if (cal == null || !cal.hasPixelSizeMicrons()) return -1;
        return cal.getAveragedPixelSizeMicrons();
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
