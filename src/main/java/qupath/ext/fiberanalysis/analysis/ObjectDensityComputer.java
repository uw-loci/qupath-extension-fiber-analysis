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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Computes per-class object-presence density channels at the same grid
 * geometry as the fiber-density channels.
 *
 * <p>For each selected class:
 * <ol>
 *   <li>Rasterise every PathObject of that class into a binary mask at
 *       source resolution (1 = pixel is inside any object of the class).
 *       Annotations and detections both count; mask is the union of all
 *       their ROIs.</li>
 *   <li>Box-filter the mask: at each output cell (window-grid in window
 *       mode, source pixel in pixel mode), report the fraction of mask=1
 *       pixels in the local {@code windowPx x windowPx} neighborhood.</li>
 * </ol>
 *
 * <p>Box filtering uses a summed-area table (integral image) so the cost
 * is O(srcW*srcH) regardless of window size. Memory cost is one byte per
 * source pixel for the binary mask + one int per source pixel for the
 * integral table = 5 bytes per pixel per class.
 *
 * <p>The returned grid is float[gridH*gridW] with NaN where the local
 * neighborhood is entirely outside the slide (corner partial windows).
 * Values are in [0.0, 1.0] -- fraction of the local box covered by the
 * class. Multiply by 100 for percent-coverage.
 */
final class ObjectDensityComputer {

    private static final Logger logger = LoggerFactory.getLogger(ObjectDensityComputer.class);

    private ObjectDensityComputer() {}

    /**
     * Compute one density grid per class.
     *
     * @param imageData      source (used for its hierarchy + dimensions)
     * @param classNames     ordered class names to compute; one output per class
     * @param srcW           source image width in pixels
     * @param srcH           source image height in pixels
     * @param windowPx       neighborhood size in source pixels (box filter window)
     * @param stridePx       window stride, or the sampling step in pixel mode
     * @param gridW          output grid columns
     * @param gridH          output grid rows
     * @param centred        true in pixel mode: boxes centred on each step block
     * @return map from class name to a float[gridW*gridH] (NaN = no-data)
     */
    static Map<String, float[]> compute(
            ImageData<java.awt.image.BufferedImage> imageData,
            List<String> classNames,
            int srcW,
            int srcH,
            int windowPx,
            int stridePx,
            int gridW,
            int gridH,
            boolean centred) {

        Map<String, float[]> out = new LinkedHashMap<>();
        if (classNames == null || classNames.isEmpty()) return out;

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            logger.warn("No hierarchy on imageData; object-density channels will be all-NaN");
            for (String name : classNames) {
                float[] empty = new float[gridW * gridH];
                Arrays.fill(empty, Float.NaN);
                out.put(name, empty);
            }
            return out;
        }

        // Snapshot all annotations + detections once; partition by requested
        // class name. PathClass.toString() yields the user-visible class name
        // including nested-class colons ("Tumor: Stroma"), matching what the
        // class manager / measurement table show.
        Set<String> requested = new LinkedHashSet<>(classNames);
        Map<String, List<PathObject>> byClass = new LinkedHashMap<>();
        for (String n : classNames) byClass.put(n, new ArrayList<>());

        List<PathObject> all = new ArrayList<>();
        all.addAll(hierarchy.getAnnotationObjects());
        all.addAll(hierarchy.getDetectionObjects());
        for (PathObject po : all) {
            PathClass pc = po.getPathClass();
            String name = pc == null ? null : pc.toString();
            if (name != null && requested.contains(name)) {
                byClass.get(name).add(po);
            }
        }

        for (String name : classNames) {
            List<PathObject> members = byClass.get(name);
            if (members.isEmpty()) {
                logger.info("Object density: class '{}' has no objects -- channel will be all-NaN", name);
                float[] empty = new float[gridW * gridH];
                Arrays.fill(empty, Float.NaN);
                out.put(name, empty);
                continue;
            }

            byte[] mask = rasterizeMask(members, srcW, srcH);
            long maskPx = countNonZero(mask);
            logger.info(
                    "Object density '{}': {} object(s) rasterized -> {} mask px ({}%)",
                    name,
                    members.size(),
                    maskPx,
                    String.format(java.util.Locale.ROOT, "%.2f", 100.0 * maskPx / Math.max(1L, (long) srcW * srcH)));

            float[] grid = MaskBoxSampler.sample(mask, srcW, srcH, windowPx, stridePx, gridW, gridH, centred);
            out.put(name, grid);
        }
        return out;
    }

    /**
     * Rasterize each object's ROI into a binary mask via AWT Graphics2D.
     * Returns row-major byte[] sized srcW*srcH; 1 = inside any ROI, 0 = outside.
     */
    private static byte[] rasterizeMask(Collection<PathObject> objects, int srcW, int srcH) {
        // TYPE_BYTE_GRAY gives a directly-addressable byte[] backing array.
        BufferedImage img = new BufferedImage(srcW, srcH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            // Render at 1:1 pixel; no antialiasing -- we want hard membership.
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, srcW, srcH);
            g.setColor(Color.WHITE);
            for (PathObject po : objects) {
                ROI roi = po.getROI();
                if (roi == null || roi.isEmpty()) continue;
                Shape shape;
                try {
                    shape = RoiTools.getShape(roi);
                } catch (IllegalArgumentException ex) {
                    logger.debug("Could not convert ROI to Shape for object {}: {}", po, ex.getMessage());
                    continue;
                }
                g.fill(shape);
            }
        } finally {
            g.dispose();
        }
        return ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
    }

    private static long countNonZero(byte[] mask) {
        long c = 0;
        for (byte b : mask) if (b != 0) c++;
        return c;
    }
}
