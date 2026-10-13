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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which window cores are labelled training examples, and why the rest
 * are not.
 *
 * <p>A window is an example for class C when its <b>core</b> -- not its
 * window -- is covered by C's annotations and by nothing else the user
 * selected. The window still supplies the context the measurement needs; the
 * core is the only part the label is claimed over. Anything less lets a window
 * that straddles two kinds of collagen be labelled as one of them.
 *
 * <p><b>Mixed cores are dropped, and there is no majority vote.</b> If a core
 * is not pure then the stride is too coarse for the geometry the user drew,
 * and a vote would hide that behind a plausible-looking label. The rejection
 * counts say so instead, by reason, so the dialog can report "4,812 cores
 * straddled two classes" rather than a smaller training set with no
 * explanation.
 *
 * <p>Geometry is in full-image pixels throughout, matching QuPath ROIs. Cores
 * come from {@link WindowGeometry#coreBox} in read-scale region-local pixels
 * and are converted here, so the labelling and the output detections land on
 * exactly the same boxes.
 */
public final class WindowLabelExtractor {

    private static final Logger logger = LoggerFactory.getLogger(WindowLabelExtractor.class);

    private static final GeometryFactory GF = new GeometryFactory();

    /** Why a core did or did not become a training example. */
    public enum Reason {
        /** Became a training example. */
        LABELLED,
        /** No selected class's annotation reaches this core. */
        OUTSIDE_ALL_CLASSES,
        /** Two or more selected classes reach this core. */
        MIXED_CLASSES,
        /** One class reaches the core but does not cover enough of it. */
        BELOW_PURITY,
        /** The window hangs off the region edge, so its features are biased. */
        PARTIAL_WINDOW
    }

    /**
     * One training annotation.
     *
     * @param className the selected class it belongs to
     * @param id        a stable id, used to count distinct annotations per
     *                  class and to permute labels at annotation level
     * @param geometry  its outline in full-image pixels
     */
    public record LabelledAnnotation(String className, long id, Geometry geometry) {}

    /**
     * Lattice and policy.
     *
     * @param classNames    selected classes, in the order that fixes class indices
     * @param gridW         windows across
     * @param gridH         windows down
     * @param windowPx      window side in read-scale pixels
     * @param stridePx      window stride in read-scale pixels; also the core side
     * @param regionW       analysed region width in read-scale pixels
     * @param regionH       analysed region height in read-scale pixels
     * @param offsetX       full-image x of the region origin
     * @param offsetY       full-image y of the region origin
     * @param downsample    read-scale pixels to full-image pixels
     * @param minCorePurity fraction of the core the class must cover, 0 to 1.
     *                      At 1.0 the test is exact coverage, which is both
     *                      the default and the cheap path.
     */
    public record Request(
            List<String> classNames,
            int gridW,
            int gridH,
            int windowPx,
            int stridePx,
            int regionW,
            int regionH,
            int offsetX,
            int offsetY,
            double downsample,
            double minCorePurity) {}

    /**
     * The labelling.
     *
     * @param classIndex          per core, the class index or -1 when unlabelled
     * @param annotationId        per core, the annotation it was labelled from, or -1
     * @param counts              how many cores fell to each reason
     * @param windowsPerClass     labelled cores per class name
     * @param annotationsPerClass distinct annotation ids contributing per class
     */
    public record Result(
            int[] classIndex,
            long[] annotationId,
            Map<Reason, Integer> counts,
            Map<String, Integer> windowsPerClass,
            Map<String, Set<Long>> annotationsPerClass) {

        /** @return the number of labelled cores */
        public int labelledCount() {
            return counts.getOrDefault(Reason.LABELLED, 0);
        }

        /**
         * @return a one-line-per-reason report of what was rejected and why,
         *         for the training dialog
         */
        public String rejectionSummary() {
            StringBuilder sb = new StringBuilder();
            for (Reason r : Reason.values()) {
                int c = counts.getOrDefault(r, 0);
                if (c == 0) continue;
                sb.append(String.format(
                        java.util.Locale.ROOT, "%-20s %,d%n", r.name().toLowerCase(), c));
            }
            return sb.toString().trim();
        }
    }

    private WindowLabelExtractor() {}

    /**
     * Label every core on the lattice.
     *
     * @param request     lattice and policy
     * @param annotations the selected classes' annotations; classes not in
     *                    {@code request.classNames()} are ignored
     * @return the labelling
     */
    public static Result extract(Request request, List<LabelledAnnotation> annotations) {
        int n = request.gridW() * request.gridH();
        int[] classIndex = new int[n];
        long[] annotationId = new long[n];
        java.util.Arrays.fill(classIndex, -1);
        java.util.Arrays.fill(annotationId, -1L);

        Map<Reason, Integer> counts = new EnumMap<>(Reason.class);
        Map<String, Integer> windowsPerClass = new LinkedHashMap<>();
        Map<String, Set<Long>> annotationsPerClass = new LinkedHashMap<>();
        for (String c : request.classNames()) {
            windowsPerClass.put(c, 0);
            annotationsPerClass.put(c, new LinkedHashSet<>());
        }

        Map<String, Integer> indexOfClass = new LinkedHashMap<>();
        for (int i = 0; i < request.classNames().size(); i++) {
            indexOfClass.put(request.classNames().get(i), i);
        }

        // One prepared geometry per annotation, indexed by envelope. Preparing
        // pays for itself immediately: the same outline is tested against
        // every core whose envelope overlaps it, which at a fine stride is
        // thousands of tests against one polygon.
        STRtree tree = new STRtree();
        List<Prepared> prepared = new ArrayList<>();
        for (LabelledAnnotation a : annotations) {
            if (a == null || a.geometry() == null || a.geometry().isEmpty()) continue;
            if (!indexOfClass.containsKey(a.className())) continue;
            Prepared p =
                    new Prepared(a.className(), a.id(), a.geometry(), PreparedGeometryFactory.prepare(a.geometry()));
            prepared.add(p);
            tree.insert(a.geometry().getEnvelopeInternal(), p);
        }
        tree.build();

        boolean exactCoverage = request.minCorePurity() >= 1.0;

        for (int iy = 0; iy < request.gridH(); iy++) {
            for (int ix = 0; ix < request.gridW(); ix++) {
                int idx = iy * request.gridW() + ix;

                if (WindowGeometry.isPartial(
                        ix, iy, request.regionW(), request.regionH(), request.windowPx(), request.stridePx())) {
                    // Truncated support biases every area-normalised feature,
                    // so a partial window is predicted on and never trained on.
                    bump(counts, Reason.PARTIAL_WINDOW);
                    continue;
                }

                WindowGeometry.Box core = WindowGeometry.coreBox(ix, iy, request.windowPx(), request.stridePx());
                double x0 = core.x() * request.downsample() + request.offsetX();
                double y0 = core.y() * request.downsample() + request.offsetY();
                double x1 = x0 + core.w() * request.downsample();
                double y1 = y0 + core.h() * request.downsample();
                Envelope env = new Envelope(x0, x1, y0, y1);

                @SuppressWarnings("unchecked")
                List<Prepared> candidates = tree.query(env);
                if (candidates.isEmpty()) {
                    bump(counts, Reason.OUTSIDE_ALL_CLASSES);
                    continue;
                }

                Geometry coreGeom = GF.toGeometry(env);
                List<Prepared> hits = new ArrayList<>(2);
                String onlyClass = null;
                boolean mixed = false;
                for (Prepared p : candidates) {
                    if (!p.prepared.intersects(coreGeom)) continue;
                    if (onlyClass == null) {
                        onlyClass = p.className;
                    } else if (!onlyClass.equals(p.className)) {
                        mixed = true;
                        break;
                    }
                    hits.add(p);
                }
                if (mixed) {
                    bump(counts, Reason.MIXED_CLASSES);
                    continue;
                }
                if (onlyClass == null) {
                    bump(counts, Reason.OUTSIDE_ALL_CLASSES);
                    continue;
                }

                long winner = coveringAnnotation(hits, coreGeom, exactCoverage, request.minCorePurity());
                if (winner < 0) {
                    bump(counts, Reason.BELOW_PURITY);
                    continue;
                }

                classIndex[idx] = indexOfClass.get(onlyClass);
                annotationId[idx] = winner;
                windowsPerClass.merge(onlyClass, 1, Integer::sum);
                annotationsPerClass.get(onlyClass).add(winner);
                bump(counts, Reason.LABELLED);
            }
        }

        logger.info(
                "Window labels: {} of {} cores labelled across {} class(es); rejections {}",
                counts.getOrDefault(Reason.LABELLED, 0),
                n,
                request.classNames().size(),
                counts);

        Map<String, Set<Long>> frozen = new LinkedHashMap<>();
        annotationsPerClass.forEach((k, v) -> frozen.put(k, Collections.unmodifiableSet(v)));
        return new Result(
                classIndex,
                annotationId,
                Collections.unmodifiableMap(counts),
                Collections.unmodifiableMap(windowsPerClass),
                Collections.unmodifiableMap(frozen));
    }

    /**
     * Which annotation the core should be attributed to, or -1 when the class
     * does not cover enough of it.
     *
     * <p>A single annotation covering the core wins outright. Otherwise the
     * core straddles two annotations of the same class, which is a legitimate
     * example -- the class is still unambiguous -- so the union is tested and
     * the largest contributor gets the attribution.
     */
    private static long coveringAnnotation(
            List<Prepared> hits, Geometry coreGeom, boolean exactCoverage, double minCorePurity) {
        if (hits.size() == 1) {
            Prepared p = hits.get(0);
            if (exactCoverage) {
                // covers(), not containsProperly(): a core that abuts the
                // annotation's edge exactly IS fully inside the class, and
                // containsProperly would throw away that whole rim.
                return p.prepared.covers(coreGeom) ? p.id : -1L;
            }
            return fraction(p.geometry, coreGeom) >= minCorePurity ? p.id : -1L;
        }

        List<Geometry> geoms = new ArrayList<>(hits.size());
        for (Prepared p : hits) geoms.add(p.geometry);
        Geometry union = UnaryUnionOp.union(geoms);
        boolean ok = exactCoverage ? union.covers(coreGeom) : fraction(union, coreGeom) >= minCorePurity;
        if (!ok) return -1L;

        long best = hits.get(0).id;
        double bestArea = -1.0;
        for (Prepared p : hits) {
            double a = intersectionArea(p.geometry, coreGeom);
            if (a > bestArea) {
                bestArea = a;
                best = p.id;
            }
        }
        return best;
    }

    private static double fraction(Geometry g, Geometry coreGeom) {
        double coreArea = coreGeom.getArea();
        if (coreArea <= 0) return 0.0;
        return intersectionArea(g, coreGeom) / coreArea;
    }

    private static double intersectionArea(Geometry g, Geometry coreGeom) {
        try {
            return g.intersection(coreGeom).getArea();
        } catch (RuntimeException ex) {
            // A self-intersecting hand-drawn outline can fail the overlay op.
            // Treat it as no coverage rather than losing the whole run.
            logger.warn("Core / annotation intersection failed, treating as uncovered: {}", ex.getMessage());
            return 0.0;
        }
    }

    private static void bump(Map<Reason, Integer> counts, Reason r) {
        counts.merge(r, 1, Integer::sum);
    }

    /** Annotation plus its prepared form, kept together in the spatial index. */
    private static final class Prepared {
        final String className;
        final long id;
        final Geometry geometry;
        final PreparedGeometry prepared;

        Prepared(String className, long id, Geometry geometry, PreparedGeometry prepared) {
            this.className = className;
            this.id = id;
            this.geometry = geometry;
            this.prepared = prepared;
        }
    }
}
