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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.GeometryTools;

/**
 * Which part of an image a density map is computed in: the whole image, or the
 * windows that touch a set of area annotations. Cells outside are left NaN; the
 * grid itself always covers the whole image.
 */
public final class DensityRegion {

    /** How the annotations are chosen. */
    public enum Mode {
        /** Every window of the image. */
        WHOLE_IMAGE("whole", "Whole image"),
        /** The annotations selected in the open image, found by ID on a re-run. */
        SELECTED("selected", "Inside selected annotations"),
        /** Every area annotation, whatever its class. */
        ANY_ANNOTATION("any", "Inside every area annotation"),
        /** Area annotations of the chosen classes. */
        CLASSES("classes", "Inside area annotations of class:");

        /** Code stored in the run parameters. */
        public final String code;
        /** Label shown in the dialog. */
        public final String label;

        Mode(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /**
         * @param code a stored code
         * @return its mode; whole image for null or an unknown code
         */
        public static Mode fromCode(String code) {
            for (Mode m : values()) if (m.code.equalsIgnoreCase(code == null ? "" : code.trim())) return m;
            return WHOLE_IMAGE;
        }
    }

    private static final DensityRegion WHOLE = new DensityRegion(Mode.WHOLE_IMAGE, List.of(), List.of(), List.of());

    public final Mode mode;
    /** Class names, for {@link Mode#CLASSES}. */
    public final List<String> classes;
    /** Annotation IDs, for {@link Mode#SELECTED}. */
    public final List<String> annotationIds;
    /** Annotation names or classes as shown, for {@link Mode#SELECTED}; for the record only. */
    public final List<String> annotationNames;

    private DensityRegion(Mode mode, List<String> classes, List<String> ids, List<String> names) {
        this.mode = mode;
        this.classes = List.copyOf(classes);
        this.annotationIds = List.copyOf(ids);
        this.annotationNames = List.copyOf(names);
    }

    /** @return the whole image */
    public static DensityRegion wholeImage() {
        return WHOLE;
    }

    /** @return every area annotation */
    public static DensityRegion anyAnnotation() {
        return new DensityRegion(Mode.ANY_ANNOTATION, List.of(), List.of(), List.of());
    }

    /**
     * @param classes class names; {@link AnnotationClassFilter#UNCLASSIFIED} matches annotations with no class
     * @return area annotations of those classes
     * @throws IllegalArgumentException if no class is given
     */
    public static DensityRegion ofClasses(Collection<String> classes) {
        if (classes == null || classes.isEmpty()) throw new IllegalArgumentException("Choose at least one class");
        return new DensityRegion(Mode.CLASSES, new ArrayList<>(new LinkedHashSet<>(classes)), List.of(), List.of());
    }

    /**
     * @param annotations the selected annotations; non-area objects are ignored
     * @return those annotations, by ID
     * @throws IllegalArgumentException if none is an area annotation
     */
    public static DensityRegion selected(Collection<? extends PathObject> annotations) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (PathObject o : annotations) {
            if (!isArea(o)) continue;
            ids.add(o.getID().toString());
            names.add(
                    o.getName() != null && !o.getName().isBlank() ? o.getName() : AnnotationClassFilter.displayName(o));
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one area annotation");
        return new DensityRegion(Mode.SELECTED, List.of(), ids, names);
    }

    /**
     * @param mode    stored mode code
     * @param classes stored classes
     * @param ids     stored annotation IDs
     * @param names   stored annotation names
     * @return the region they describe; whole image when a mode lacks what it needs
     */
    public static DensityRegion fromStored(String mode, List<String> classes, List<String> ids, List<String> names) {
        Mode m = Mode.fromCode(mode);
        if (m == Mode.CLASSES && (classes == null || classes.isEmpty())) return WHOLE;
        if (m == Mode.SELECTED && (ids == null || ids.isEmpty())) return WHOLE;
        if (m == Mode.WHOLE_IMAGE) return WHOLE;
        return new DensityRegion(
                m,
                classes == null ? List.of() : classes,
                ids == null ? List.of() : ids,
                names == null ? List.of() : names);
    }

    /** @return true when every window is computed */
    public boolean isWholeImage() {
        return mode == Mode.WHOLE_IMAGE;
    }

    /** @return what the region is, in words, with no per-image counts */
    public String describe() {
        return switch (mode) {
            case WHOLE_IMAGE -> "whole image";
            case ANY_ANNOTATION -> "every area annotation";
            case CLASSES -> "area annotations of class " + String.join(", ", classes);
            case SELECTED ->
                "selected annotations (" + annotationIds.size() + "): " + String.join(", ", annotationNames);
        };
    }

    /**
     * Adds this region to a run's parameter map.
     *
     * @param m the map
     */
    public void putInto(Map<String, Object> m) {
        m.put("regionMode", mode.code);
        m.put("regionDescription", describe());
        m.put("regionClasses", classes);
        m.put("regionAnnotationIds", annotationIds);
        m.put("regionAnnotationNames", annotationNames);
    }

    /**
     * The annotations this region picks in one image, joined.
     *
     * @param geometry  the annotations' union, in full-resolution pixels
     * @param count     annotations joined
     * @param missing   selected annotation IDs not found in the image ({@link Mode#SELECTED} only)
     */
    public record Resolved(Geometry geometry, int count, int missing) {

        /**
         * @param pixelUm the image's pixel size, microns
         * @return the record written into the map file
         */
        public String label(DensityRegion region, double pixelUm) {
            String s = String.format(
                    Locale.ROOT,
                    "%s; %d annotation(s), %.4g um^2",
                    region.describe(),
                    count,
                    geometry.getArea() * pixelUm * pixelUm);
            return missing > 0 ? s + "; " + missing + " selected annotation(s) not found" : s;
        }
    }

    /**
     * @param hierarchy an image's objects
     * @return the matching area annotations joined, or null for the whole image; a count of 0
     *     when nothing matched
     */
    public Resolved resolve(PathObjectHierarchy hierarchy) {
        if (isWholeImage()) return null;
        Predicate<PathObject> pick =
                switch (mode) {
                    case CLASSES -> AnnotationClassFilter.predicate(Set.copyOf(classes));
                    case SELECTED -> {
                        Set<String> wanted = Set.copyOf(annotationIds);
                        yield o -> wanted.contains(o.getID().toString());
                    }
                    default -> o -> true;
                };
        List<Geometry> parts = new ArrayList<>();
        Set<String> found = new java.util.HashSet<>();
        for (PathObject o : hierarchy.getAnnotationObjects()) {
            if (!isArea(o) || !pick.test(o)) continue;
            parts.add(o.getROI().getGeometry());
            found.add(o.getID().toString());
        }
        int missing = mode == Mode.SELECTED ? annotationIds.size() - found.size() : 0;
        if (parts.isEmpty()) return new Resolved(new GeometryFactory().createPolygon(), 0, missing);
        return new Resolved(GeometryTools.union(parts), parts.size(), missing);
    }

    private static boolean isArea(PathObject o) {
        return o != null && o.isAnnotation() && o.hasROI() && o.getROI().isArea();
    }

    /**
     * Tests source rectangles against a region quickly.
     */
    static final class Footprints {
        private final Geometry geometry;
        private final PreparedGeometry prepared;
        private final Envelope envelope;
        private final GeometryFactory factory;

        Footprints(Geometry geometry) {
            this.geometry = geometry;
            this.prepared = PreparedGeometryFactory.prepare(geometry);
            this.envelope = geometry.getEnvelopeInternal();
            this.factory = geometry.getFactory();
        }

        /** @return true when the rectangle {@code [x, x+w) x [y, y+h)} touches the region's interior */
        boolean touches(double x, double y, double w, double h) {
            Envelope r = new Envelope(x, x + w, y, y + h);
            if (!envelope.intersects(r)) return false;
            Geometry box = factory.toGeometry(r);
            // A rectangle that only shares an edge with the region covers none of it.
            return prepared.intersects(box)
                    && (prepared.containsProperly(box)
                            || geometry.intersection(box).getArea() > 0);
        }

        /**
         * Sets every cell whose footprint misses the region to NaN, in every channel.
         *
         * @param accum    channels, {@code gridW * gridH} each
         * @param gridW    cells across
         * @param gridH    cells down
         * @param cellPx   distance between cell origins
         * @param footPx   footprint side: the window in window mode, the cell in pixel mode
         * @return cells kept
         */
        long mask(float[][] accum, int gridW, int gridH, int cellPx, int footPx) {
            long kept = 0;
            for (int j = 0; j < gridH; j++) {
                double y0 = (double) j * cellPx;
                Envelope rowEnv = new Envelope(0, (double) gridW * cellPx + footPx, y0, y0 + footPx);
                Geometry row = envelope.intersects(rowEnv) ? geometry.intersection(factory.toGeometry(rowEnv)) : null;
                PreparedGeometry rowPrep = row == null || row.isEmpty() ? null : PreparedGeometryFactory.prepare(row);
                Envelope rowBox = rowPrep == null ? null : row.getEnvelopeInternal();
                for (int i = 0; i < gridW; i++) {
                    boolean keep = false;
                    if (rowPrep != null) {
                        double x0 = (double) i * cellPx;
                        Envelope c = new Envelope(x0, x0 + footPx, y0, y0 + footPx);
                        if (rowBox.intersects(c)) {
                            Geometry cell = factory.toGeometry(c);
                            keep = rowPrep.intersects(cell)
                                    && (rowPrep.containsProperly(cell)
                                            || row.intersection(cell).getArea() > 0);
                        }
                    }
                    if (keep) {
                        kept++;
                    } else {
                        int k = j * gridW + i;
                        for (float[] ch : accum) ch[k] = Float.NaN;
                    }
                }
            }
            return kept;
        }
    }
}
