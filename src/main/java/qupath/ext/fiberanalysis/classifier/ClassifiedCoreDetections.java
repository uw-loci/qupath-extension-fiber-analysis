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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Turns a window-classifier probability grid into one detection per window
 * core.
 *
 * <p><b>The ROI is the core box, not the window box.</b> At 87.5% overlap a
 * full-window rectangle per window puts 64 overlapping objects over every
 * core-sized patch of tissue, which is an unreadable viewer and a measurement
 * table nobody can sort. Cores tile the plane exactly
 * ({@link WindowGeometry#coreBox}), so the layer comes out as a clean
 * selectable mosaic at the stride's resolution while each prediction still
 * carries the full window's context behind it.
 *
 * <p>The PathClass is the predicted class itself, so the objects inherit
 * whatever colour the pathologist gave that class in their own class list.
 * That is the payoff of the open-set design: nothing here knows or cares what
 * the classes are called.
 *
 * <p>Input grids use the same {@code float[gridW * gridH]} contract as the
 * density sidecar, so the workflow builds the arrays once and hands the same
 * references to {@link qupath.ext.fiberanalysis.analysis.DensityTiffWriter}
 * and to this class. {@code NaN} is the no-prediction sentinel in both; a
 * cell that is NaN in every class column produces no detection at all.
 */
public final class ClassifiedCoreDetections {

    private static final Logger logger = LoggerFactory.getLogger(ClassifiedCoreDetections.class);

    /**
     * Soft ceiling on how many core detections one run may add to a
     * hierarchy. Past this the caller should offer raster-only instead: the
     * raster carries the same information at the same resolution and costs a
     * single image server.
     */
    public static final int DEFAULT_MAX_DETECTIONS = 500_000;

    private ClassifiedCoreDetections() {}

    /**
     * Everything the detection layer needs that is not a per-window number.
     *
     * @param classNames    model class names, indexing the probability grids
     * @param gridW         windows across
     * @param gridH         windows down
     * @param windowPx      window side in read-scale pixels
     * @param stridePx      window stride in read-scale pixels; also the core side
     * @param offsetX       full-image x of the analysed region's origin
     * @param offsetY       full-image y of the analysed region's origin
     * @param downsample    read-scale pixels to full-image pixels
     * @param classifierId  model id, stamped on every object
     * @param runId         this application's run id, stamped on every object
     * @param provisional   true when the model failed its own validation gate
     *                      and the user chose to apply it anyway
     */
    public record Geometry(
            List<String> classNames,
            int gridW,
            int gridH,
            int windowPx,
            int stridePx,
            int offsetX,
            int offsetY,
            double downsample,
            String classifierId,
            String runId,
            boolean provisional) {}

    /**
     * Per-window grids, all of length {@code gridW * gridH}, all optional
     * except the probabilities.
     *
     * @param probabilityByClass one grid per class, in {@code classNames} order
     * @param novelty            novelty p-value grid, or null
     * @param abstained          1.0 where the window was abstained on, or null
     * @param extraMeasurements  measurement name to grid, for feature columns
     *                           the user asked to carry through; may be empty
     */
    public record Grids(
            float[][] probabilityByClass, float[] novelty, float[] abstained, Map<String, float[]> extraMeasurements) {}

    /**
     * @param gridW windows across
     * @param gridH windows down
     * @return how many detections {@link #create} would produce at most
     */
    public static long estimateCount(int gridW, int gridH) {
        return (long) Math.max(0, gridW) * (long) Math.max(0, gridH);
    }

    /**
     * @param gridW windows across
     * @param gridH windows down
     * @param budget maximum acceptable detection count
     * @return true when the core layer would exceed the budget
     */
    public static boolean exceedsBudget(int gridW, int gridH, int budget) {
        return estimateCount(gridW, gridH) > budget;
    }

    /**
     * Human-readable explanation of a budget overrun, for the dialog that has
     * to offer raster-only instead.
     *
     * @param gridW  windows across
     * @param gridH  windows down
     * @param budget maximum acceptable detection count
     * @return a message naming the count, the budget and the alternative
     */
    public static String budgetMessage(int gridW, int gridH, int budget) {
        return String.format(
                java.util.Locale.ROOT,
                "This run would create %,d core detections (%d x %d windows), above the %,d"
                        + " limit. The probability raster carries the same predictions at the same"
                        + " resolution without filling the hierarchy -- run raster-only, or increase"
                        + " the stride.",
                estimateCount(gridW, gridH),
                gridW,
                gridH,
                budget);
    }

    /**
     * Build the core detections.
     *
     * @param geom  lattice, class names and provenance
     * @param grids per-window values
     * @return one detection per window core that has a prediction, in
     *         row-major grid order
     * @throws IllegalArgumentException when a grid is the wrong length or the
     *         class count does not match the probability grids
     */
    public static List<PathObject> create(Geometry geom, Grids grids) {
        int n = geom.gridW() * geom.gridH();
        int nClasses = geom.classNames().size();
        if (nClasses < 1) {
            throw new IllegalArgumentException("Need at least one class name");
        }
        if (grids.probabilityByClass().length != nClasses) {
            throw new IllegalArgumentException("probabilityByClass has " + grids.probabilityByClass().length
                    + " grids for " + nClasses + " classes");
        }
        requireLength(grids.novelty(), n, "novelty");
        requireLength(grids.abstained(), n, "abstained");
        for (int c = 0; c < nClasses; c++) {
            requireLength(
                    grids.probabilityByClass()[c], n, "P(" + geom.classNames().get(c) + ")");
        }
        Map<String, float[]> extras =
                grids.extraMeasurements() == null ? Map.of() : new LinkedHashMap<>(grids.extraMeasurements());
        for (Map.Entry<String, float[]> e : extras.entrySet()) {
            requireLength(e.getValue(), n, e.getKey());
        }

        // Resolve the PathClasses once. PathClass.fromString interns, so this
        // is really about not doing the lookup per window.
        PathClass[] classes = new PathClass[nClasses];
        for (int c = 0; c < nClasses; c++) {
            classes[c] = PathClass.fromString(geom.classNames().get(c));
        }

        List<PathObject> out = new ArrayList<>();
        int noPrediction = 0;
        int abstainedCount = 0;
        for (int iy = 0; iy < geom.gridH(); iy++) {
            for (int ix = 0; ix < geom.gridW(); ix++) {
                int idx = iy * geom.gridW() + ix;

                int best = -1;
                double p1 = Double.NEGATIVE_INFINITY;
                double p2 = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < nClasses; c++) {
                    float p = grids.probabilityByClass()[c][idx];
                    if (Float.isNaN(p)) continue;
                    if (p > p1) {
                        p2 = p1;
                        p1 = p;
                        best = c;
                    } else if (p > p2) {
                        p2 = p;
                    }
                }
                if (best < 0) {
                    noPrediction++;
                    continue;
                }

                boolean abstain = grids.abstained() != null && grids.abstained()[idx] >= 0.5f;
                if (abstain) abstainedCount++;

                WindowGeometry.Box core = WindowGeometry.coreBox(ix, iy, geom.windowPx(), geom.stridePx());
                int cx = (int) Math.round(core.x() * geom.downsample()) + geom.offsetX();
                int cy = (int) Math.round(core.y() * geom.downsample()) + geom.offsetY();
                int cw = Math.max(1, (int) Math.round(core.w() * geom.downsample()));
                int ch = Math.max(1, (int) Math.round(core.h() * geom.downsample()));

                ROI rect = ROIs.createRectangleROI(cx, cy, cw, ch, ImagePlane.getDefaultPlane());
                // An abstained core is deliberately left unclassified: the
                // model declined to call it, and giving it the argmax class
                // anyway is exactly the overconfidence abstention exists to
                // avoid. The probabilities are still attached, so nothing is
                // lost -- only the colour on the viewer is withheld.
                PathObject det = abstain
                        ? PathObjects.createDetectionObject(rect)
                        : PathObjects.createDetectionObject(rect, classes[best]);

                for (int c = 0; c < nClasses; c++) {
                    float p = grids.probabilityByClass()[c][idx];
                    if (!Float.isNaN(p)) {
                        det.getMeasurementList().put("P(" + geom.classNames().get(c) + ")", p);
                    }
                }
                det.getMeasurementList().put("Max probability", p1);
                if (nClasses > 1 && p2 > Double.NEGATIVE_INFINITY) {
                    det.getMeasurementList().put("Margin (top1 - top2)", p1 - p2);
                }
                if (grids.novelty() != null && !Float.isNaN(grids.novelty()[idx])) {
                    det.getMeasurementList().put("Novelty p", grids.novelty()[idx]);
                }
                if (grids.abstained() != null) {
                    det.getMeasurementList().put("Abstained", abstain ? 1.0 : 0.0);
                }
                det.getMeasurementList().put("Window grid x", ix);
                det.getMeasurementList().put("Window grid y", iy);
                for (Map.Entry<String, float[]> e : extras.entrySet()) {
                    float v = e.getValue()[idx];
                    if (!Float.isNaN(v)) {
                        det.getMeasurementList().put(e.getKey(), v);
                    }
                }
                if (geom.provisional()) {
                    // Travels with the object through GeoJSON export and into
                    // any measurement table, so a map from a model that failed
                    // its own gate cannot be mistaken for one that passed.
                    det.getMeasurementList().put("Provisional", 1.0);
                }

                if (geom.classifierId() != null && !geom.classifierId().isEmpty()) {
                    det.getMetadata().put("fiber_analysis.classifier_id", geom.classifierId());
                }
                if (geom.runId() != null && !geom.runId().isEmpty()) {
                    det.getMetadata().put("fiber_analysis.run_id", geom.runId());
                }
                if (geom.provisional()) {
                    det.getMetadata().put("fiber_analysis.provisional", "true");
                }
                out.add(det);
            }
        }
        logger.info(
                "Core detections: {} created, {} cells with no prediction, {} abstained (grid {}x{}, core {} px)",
                out.size(),
                noPrediction,
                abstainedCount,
                geom.gridW(),
                geom.gridH(),
                geom.stridePx());
        return out;
    }

    private static void requireLength(float[] grid, int n, String what) {
        if (grid != null && grid.length != n) {
            throw new IllegalArgumentException(what + " grid has length " + grid.length + ", expected " + n);
        }
    }
}
