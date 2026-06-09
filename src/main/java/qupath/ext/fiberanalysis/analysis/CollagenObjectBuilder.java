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
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Traces a per-annotation fiber mask PNG into split connected-component
 * detection objects, classed as {@code CollagenAnalysis}.
 *
 * <p>The fiber mask is the analysis-zone-intersected segmentation result the
 * Python pipeline writes as {@code fiber_mask_overlay.png} (RGBA; alpha = 200
 * where fiber, 0 elsewhere). Detections are returned in image-pixel
 * coordinates so the caller can add them to the hierarchy in one shot; the
 * hierarchy resolves the source annotation as parent by containment.
 *
 * <p>Each detection carries an {@code Area um^2} measurement plus optional
 * provenance keys ({@code run_id}, {@code params_hash}, {@code annotation_index})
 * for downstream querying.
 */
final class CollagenObjectBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CollagenObjectBuilder.class);

    /** Standard class name for the produced detections. */
    static final String CLASS_NAME = "CollagenAnalysis";

    /** Alpha threshold: render.py writes 200 for fiber, 0 for background. */
    private static final double ALPHA_THRESHOLD = 100.0;

    private CollagenObjectBuilder() {}

    /**
     * Build split collagen detections from the fiber-mask PNG.
     *
     * @param imageData      source image (used for server path on the RegionRequest)
     * @param maskPng        path to fiber_mask_overlay.png
     * @param regionOffsetX  region X offset in image-pixel coordinates
     * @param regionOffsetY  region Y offset in image-pixel coordinates
     * @param annotationIndex 0-based annotation index in the run (for measurement provenance)
     * @param runId          Python-generated UUID for this annotation run
     * @param paramsHash     parameters hash for this run
     * @param pixelSizeUm    image pixel size in microns (for area measurement; pass <=0 to skip)
     * @param minAreaUm2     minimum component area in square microns to keep (use 0 to keep all)
     * @return list of detection objects, possibly empty (never null)
     */
    static List<PathObject> build(
            ImageData<BufferedImage> imageData,
            Path maskPng,
            int regionOffsetX,
            int regionOffsetY,
            int annotationIndex,
            String runId,
            String paramsHash,
            double pixelSizeUm,
            double minAreaUm2) {

        BufferedImage img;
        try {
            img = ImageIO.read(maskPng.toFile());
        } catch (IOException e) {
            logger.warn("Failed to read fiber mask PNG {}: {}", maskPng, e.getMessage());
            return Collections.emptyList();
        }
        if (img == null) {
            logger.warn("Fiber mask PNG {} could not be decoded", maskPng);
            return Collections.emptyList();
        }

        int w = img.getWidth();
        int h = img.getHeight();
        // PNG written by render.py is RGBA so getRGB() yields ARGB; alpha is in the
        // top byte. Background pixels have alpha 0; fiber pixels have alpha 200.
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        float[] alpha = new float[w * h];
        boolean anyFiber = false;
        for (int i = 0; i < argb.length; i++) {
            int a = (argb[i] >>> 24) & 0xFF;
            alpha[i] = a;
            if (a > ALPHA_THRESHOLD) {
                anyFiber = true;
            }
        }
        if (!anyFiber) {
            logger.info("No fiber pixels found in {}; no collagen detections to create", maskPng.getFileName());
            return Collections.emptyList();
        }

        SimpleImage simple = SimpleImages.createFloatImage(alpha, w, h);

        ImageServer<BufferedImage> server = imageData.getServer();
        RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, regionOffsetX, regionOffsetY, w, h);
        ROI traced = ContourTracing.createTracedROI(simple, ALPHA_THRESHOLD, Double.POSITIVE_INFINITY, request);
        if (traced == null || traced.isEmpty()) {
            return Collections.emptyList();
        }

        // Single-component multi-polygon traces back as one ROI; splitROI peels
        // off each disjoint piece. Tiny shapes can fall below minAreaUm2 and
        // get filtered before becoming PathObjects -- avoids dust polluting
        // the hierarchy when segmentation leaves single-pixel noise.
        List<ROI> components = RoiTools.splitROI(traced);
        if (components == null || components.isEmpty()) {
            return Collections.emptyList();
        }

        PathClass cls = PathClass.fromString(CLASS_NAME);
        double pxAreaUm2 = pixelSizeUm > 0 ? pixelSizeUm * pixelSizeUm : 0.0;

        List<PathObject> detections = new ArrayList<>(components.size());
        int dropped = 0;
        for (ROI compROI : components) {
            if (compROI == null || compROI.isEmpty()) continue;
            double areaPx2 = compROI.getArea();
            double areaUm2 = pxAreaUm2 > 0 ? areaPx2 * pxAreaUm2 : Double.NaN;
            if (minAreaUm2 > 0 && pxAreaUm2 > 0 && areaUm2 < minAreaUm2) {
                dropped++;
                continue;
            }
            PathObject det = PathObjects.createDetectionObject(compROI, cls);
            if (!Double.isNaN(areaUm2)) {
                det.getMeasurements().put("Area um^2", areaUm2);
            }
            det.getMeasurements().put("Area px^2", areaPx2);
            if (runId != null && !runId.isBlank()) {
                det.getMetadata().put("run_id", runId);
            }
            if (paramsHash != null && !paramsHash.isBlank()) {
                det.getMetadata().put("params_hash", paramsHash);
            }
            det.getMetadata().put("annotation_index", Integer.toString(annotationIndex));
            detections.add(det);
        }

        if (dropped > 0) {
            logger.info(
                    "Filtered {} sub-{} um^2 collagen components from {}", dropped, minAreaUm2, maskPng.getFileName());
        }
        return detections;
    }
}
