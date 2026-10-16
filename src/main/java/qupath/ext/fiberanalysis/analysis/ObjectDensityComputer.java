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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Per-class object-presence density channels on the fiber channels' lattice:
 * the fraction of each cell's box covered by the union of that class's
 * annotations and detections.
 *
 * <p>Tile by tile ({@link MaskBoxSampler}): each tile rasterises only the
 * objects whose bounds reach its rectangle, so memory is one tile's mask
 * whatever the slide size. Values are in [0, 1]; NaN where a window leaves
 * the slide (window mode).
 */
final class ObjectDensityComputer {

    private static final Logger logger = LoggerFactory.getLogger(ObjectDensityComputer.class);

    private ObjectDensityComputer() {}

    /**
     * @param imageData  source of the hierarchy
     * @param classNames class names, one channel each, in order
     * @param geo        the density map's tiling and lattice
     * @param srcW       image width
     * @param srcH       image height
     * @return class name to {@code float[gridW * gridH]}, NaN = no data
     * @throws IOException if a tile cannot be rendered
     */
    static Map<String, float[]> compute(
            ImageData<BufferedImage> imageData,
            List<String> classNames,
            FiberDensityMapWorkflow.Geometry geo,
            int srcW,
            int srcH)
            throws IOException {

        Map<String, float[]> out = new LinkedHashMap<>();
        if (classNames == null || classNames.isEmpty()) return out;
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        // PathClass.toString() is the user-visible name, nested colons
        // included ("Tumor: Stroma"), as the class list shows it.
        Set<String> requested = new LinkedHashSet<>(classNames);
        Map<String, List<Shaped>> byClass = new LinkedHashMap<>();
        for (String n : classNames) byClass.put(n, new ArrayList<>());
        if (hierarchy != null) {
            List<PathObject> all = new ArrayList<>(hierarchy.getAnnotationObjects());
            all.addAll(hierarchy.getDetectionObjects());
            for (PathObject po : all) {
                PathClass pc = po.getPathClass();
                String name = pc == null ? null : pc.toString();
                if (name == null || !requested.contains(name)) continue;
                Shaped s = Shaped.of(po);
                if (s != null) byClass.get(name).add(s);
            }
        }

        for (String name : classNames) {
            List<Shaped> members = byClass.get(name);
            if (members.isEmpty()) {
                logger.info("Object density: class '{}' has no objects -- channel will be all-NaN", name);
                float[] empty = new float[geo.gridW() * geo.gridH()];
                Arrays.fill(empty, Float.NaN);
                out.put(name, empty);
                continue;
            }
            logger.info("Object density '{}': {} object(s), rasterised per tile", name, members.size());
            out.put(name, MaskBoxSampler.sample((x, y, w, h) -> rasterize(members, x, y, w, h), geo, srcW, srcH));
        }
        return out;
    }

    /** An object's shape and bounds, converted once rather than once per tile. */
    private record Shaped(Shape shape, Rectangle2D bounds) {
        static Shaped of(PathObject po) {
            ROI roi = po.getROI();
            if (roi == null || roi.isEmpty()) return null;
            try {
                Shape shape = RoiTools.getShape(roi);
                return new Shaped(shape, shape.getBounds2D());
            } catch (IllegalArgumentException ex) {
                logger.debug("Could not convert ROI to Shape for object {}: {}", po, ex.getMessage());
                return null;
            }
        }
    }

    /** Hard-edged (no antialiasing) union of the shapes reaching one rectangle. */
    static byte[] rasterize(List<Shaped> shapes, int x, int y, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            // Java2D's default stroke control nudges coordinates for
            // non-antialiased fills, and the nudge is not translation-
            // invariant: a tile and the whole slide disagreed on edge pixels.
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            // An integer translation leaves Java2D's pixel-centre fill rule
            // exactly where a whole-slide raster would put it.
            g.translate(-x, -y);
            Rectangle2D tile = new Rectangle2D.Double(x, y, w, h);
            for (Shaped s : shapes) {
                if (s.bounds().intersects(tile)) g.fill(s.shape());
            }
        } finally {
            g.dispose();
        }
        return ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
    }

    /** Test seam: rasterise plain shapes. */
    static byte[] rasterizeShapes(List<Shape> shapes, int x, int y, int w, int h) {
        List<Shaped> s = new ArrayList<>();
        for (Shape sh : shapes) s.add(new Shaped(sh, sh.getBounds2D()));
        return rasterize(s, x, y, w, h);
    }
}
