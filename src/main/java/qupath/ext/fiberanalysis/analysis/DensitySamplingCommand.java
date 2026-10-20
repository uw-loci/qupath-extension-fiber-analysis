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
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
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
 * <p>Per-channel averaging: the mean of the map's values, drawn as the map
 * says (nearest or smooth), at points every quarter cell inside the ROI;
 * no-data points are skipped. Mean angle is averaged as an axial angle, so
 * 1 and 179 degrees average to 0, not 90.
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
                    "No density sidecar for this image yet. Run \"Project density map...\" first.\n\n" + "Expected: "
                            + sidecar);
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

        Thread worker = new Thread(() -> sampleAsync(imageData, sidecar, targets), "FiberDensitySampling-Worker");
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
        try (DensityMapReader reader = DensityMapReader.open(sidecar)) {
            DensityMapInfo info = reader.info();
            if (!info.fits(sourceServer.getWidth(), sourceServer.getHeight())) {
                throw new IOException("The map was made for a " + info.sourceWidth() + " x " + info.sourceHeight()
                        + " image; this one is " + sourceServer.getWidth() + " x " + sourceServer.getHeight() + ".");
            }
            int nChannels = info.channels().size();
            int done = 0;
            for (PathObject obj : targets) {
                ROI roi = obj.getROI();
                if (roi == null) {
                    done++;
                    continue;
                }
                int[] b = blockFor(info, roi);
                double[] means;
                if (b == null) {
                    means = new double[nChannels];
                    java.util.Arrays.fill(means, Double.NaN);
                } else {
                    means = sampleRoi(info, reader.readBlock(b[0], b[1], b[2], b[3]), b[0], b[1], b[2], b[3], roi);
                }
                for (int c = 0; c < nChannels; c++) {
                    obj.getMeasurementList()
                            .put(MEASUREMENT_PREFIX + info.channels().get(c).name, means[c]);
                }
                done++;
            }

            // QuPath caches the measurement table; tell it the hierarchy changed.
            imageData.getHierarchy().fireObjectMeasurementsChangedEvent(this, targets);
            final int doneF = done;
            Platform.runLater(() -> Dialogs.showInfoNotification(
                    "Fiber density sampling",
                    "Sampled " + nChannels + " channel(s) into " + doneF + " object measurement(s)."));
        }
    }

    /**
     * @param info the map
     * @param roi  an object's ROI
     * @return {@code {gx, gy, w, h}}: the cells under the ROI's bounds plus one on each
     *     side, or null when the ROI misses the grid
     */
    static int[] blockFor(DensityMapInfo info, ROI roi) {
        DensityLattice lat = info.lattice();
        int cell = lat.cellPx();
        int gx0 = Math.max(0, (int) Math.floor((roi.getBoundsX() - lat.x0()) / cell) - 1);
        int gy0 = Math.max(0, (int) Math.floor((roi.getBoundsY() - lat.y0()) / cell) - 1);
        int gx1 = Math.min(
                lat.gridW() - 1, (int) Math.floor((roi.getBoundsX() + roi.getBoundsWidth() - lat.x0()) / cell) + 1);
        int gy1 = Math.min(
                lat.gridH() - 1, (int) Math.floor((roi.getBoundsY() + roi.getBoundsHeight() - lat.y0()) / cell) + 1);
        if (gx1 < gx0 || gy1 < gy0) return null;
        return new int[] {gx0, gy0, gx1 - gx0 + 1, gy1 - gy0 + 1};
    }

    /**
     * Mean of each channel over a ROI, drawn the way the map says (nearest or
     * smooth) at points every quarter cell inside it; an axial angle is
     * averaged as an angle. A ROI smaller than that takes the value at its
     * centroid. NaN (no data) points are skipped.
     *
     * @param info  the map
     * @param block {@code [channel][row * bw + column]} for the cells from {@code (gx, gy)}
     * @param gx    first block column
     * @param gy    first block row
     * @param bw    block columns
     * @param bh    block rows
     * @param roi   the object's ROI, source pixels
     * @return per-channel means, NaN where nothing inside had a value
     */
    static double[] sampleRoi(DensityMapInfo info, float[][] block, int gx, int gy, int bw, int bh, ROI roi) {
        DensityLattice lat = info.lattice();
        int cell = lat.cellPx();
        DensityLattice sub =
                new DensityLattice(bw, bh, cell, lat.windowPx(), lat.originX() + gx * cell, lat.originY() + gy * cell);
        Means means = new Means(info, block.length);
        double step = Math.max(1.0, cell / 4.0);
        boolean any = false;
        for (double v = roi.getBoundsY() + step / 2; v < roi.getBoundsY() + roi.getBoundsHeight(); v += step) {
            for (double u = roi.getBoundsX() + step / 2; u < roi.getBoundsX() + roi.getBoundsWidth(); u += step) {
                if (!roi.contains(u, v)) continue;
                any = true;
                means.add(sub, block, u, v);
            }
        }
        if (!any) means.add(sub, block, roi.getCentroidX(), roi.getCentroidY());
        return means.result();
    }

    /** Running per-channel means; an axial channel accumulates its angle's cosine and sine. */
    private static final class Means {
        private final DensityMapInfo info;
        private final double[] sum;
        private final double[] sumSin;
        private final long[] count;

        Means(DensityMapInfo info, int n) {
            this.info = info;
            this.sum = new double[n];
            this.sumSin = new double[n];
            this.count = new long[n];
        }

        void add(DensityLattice sub, float[][] block, double u, double v) {
            for (int c = 0; c < block.length; c++) {
                double period = info.axialPeriod(c);
                float val = sub.sample(block[c], u, v, info.smooth(), period);
                if (!Float.isFinite(val)) continue;
                if (period > 0) {
                    sum[c] += Math.cos(2 * Math.PI * val / period);
                    sumSin[c] += Math.sin(2 * Math.PI * val / period);
                } else {
                    sum[c] += val;
                }
                count[c]++;
            }
        }

        double[] result() {
            double[] out = new double[sum.length];
            for (int c = 0; c < out.length; c++) {
                double period = info.axialPeriod(c);
                if (count[c] == 0) out[c] = Double.NaN;
                else out[c] = period > 0 ? DensityLattice.axialAngle(sumSin[c], sum[c], period) : sum[c] / count[c];
            }
            return out;
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
}
