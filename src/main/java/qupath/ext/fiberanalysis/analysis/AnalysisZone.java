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
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * The analysis zone around an annotation, as geometry, matching
 * {@code dilation.compute_border_zone_mask} (holes filled first). Computed
 * once per annotation from the whole outline, so a tile sees the same zone an
 * untiled run does even where the annotation's nearest edge is in another tile.
 */
final class AnalysisZone {

    private AnalysisZone() {}

    /**
     * @param annotation the annotation's geometry
     * @param mode       "whole", "inside", "outside" or "both"
     * @param d          zone width, same units as the geometry
     * @return the zone
     */
    static Geometry geometry(Geometry annotation, String mode, double d) {
        Geometry filled = fillHoles(annotation);
        return switch (mode == null ? "outside" : mode) {
            case "whole", "annotation" -> filled;
            case "inside" -> filled.difference(filled.buffer(-d));
            case "both" -> filled.buffer(d).difference(filled.buffer(-d));
            default -> filled.buffer(d).difference(filled);
        };
    }

    /**
     * @param annotation the annotation's ROI
     * @param mode       "whole", "inside", "outside" or "both"
     * @param d          zone width, full-resolution pixels
     * @return the zone as a ROI on the annotation's plane
     */
    static ROI roi(ROI annotation, String mode, double d) {
        return GeometryTools.geometryToROI(geometry(annotation.getGeometry(), mode, d), annotation.getImagePlane());
    }

    private static Geometry fillHoles(Geometry g) {
        List<Geometry> shells = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (part instanceof Polygon p) {
                shells.add(g.getFactory().createPolygon(p.getExteriorRing().getCoordinates()));
            } else if (part.getDimension() == 2) {
                shells.add(part);
            }
        }
        return shells.isEmpty() ? g : g.getFactory().buildGeometry(shells).union();
    }
}
