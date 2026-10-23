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

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.fiberanalysis.analysis.AnnotationClassFilter;
import qupath.ext.fiberanalysis.analysis.DensityChannelSpec;
import qupath.ext.fiberanalysis.analysis.DensityLattice;
import qupath.ext.fiberanalysis.analysis.DensityMapInfo;
import qupath.ext.fiberanalysis.analysis.DensityTiffWriter;
import qupath.ext.fiberanalysis.analysis.NpzReader;
import qupath.ext.fiberanalysis.service.ApposeArrays;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;

/**
 * Drives the window classifier: features, context, labels, train, apply.
 *
 * <p>Holds no UI. The dialogs supply a {@link Request} and a progress sink
 * and get back a {@link TrainResult} or an {@link ApplyResult}; everything
 * that could refuse does so by returning a verdict rather than by throwing,
 * so the caller can show the full report either way. A refusal still produces
 * the report: refusing to predict is not refusing to inform.
 *
 * <p>The same feature path serves both directions. Training is that path plus
 * a label join, which is the whole reason there is one extractor rather than
 * two.
 */
public final class WindowClassifierWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(WindowClassifierWorkflow.class);

    private static final Gson GSON = new Gson();

    /**
     * Fraction of max heap the slide-wide feature matrix may occupy before a
     * run is refused. The matrix is the largest single allocation in the
     * pipeline and its size is knowable in advance, so running out of memory
     * two tiles from the end is avoidable.
     */
    private static final double MATRIX_HEAP_FRACTION = 0.35;

    private WindowClassifierWorkflow() {}

    /**
     * What to compute, for either direction.
     *
     * @param features       feature-extraction settings
     * @param contextRadii   neighbourhood radii in windows; empty for no context
     * @param minCorePurity  fraction of a core a class must cover to label it
     * @param workDir        scratch directory
     */
    public record Request(
            WindowFeatureMatrix.Spec features, List<Integer> contextRadii, double minCorePurity, Path workDir) {

        /** @return the radii as the Python side expects them */
        public String radiiCsv() {
            return contextRadii.stream()
                    .map(String::valueOf)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        }
    }

    /**
     * One image's features, after context.
     *
     * @param featureNames column order
     * @param rows         row-major (n_windows, n_features)
     * @param gridW        windows across
     * @param gridH        windows down
     * @param windowPx     window side in read-scale pixels
     * @param stridePx     window stride in read-scale pixels
     * @param partial      per window, true when clamped to the region edge
     */
    public record ImageFeatures(
            List<String> featureNames,
            float[] rows,
            int gridW,
            int gridH,
            int windowPx,
            int stridePx,
            boolean[] partial) {

        /** @return number of windows */
        public int windowCount() {
            return gridW * gridH;
        }

        /**
         * @param featureIndex column
         * @return that column as a slide-wide grid, for the raster or detections
         */
        public float[] column(int featureIndex) {
            int n = windowCount();
            int d = featureNames.size();
            float[] out = new float[n];
            for (int i = 0; i < n; i++) out[i] = rows[i * d + featureIndex];
            return out;
        }
    }

    /**
     * @param verdict      OK, WARN, REFUSE or BLOCK
     * @param summary      the multi-line report for the dialog
     * @param reportPath   report.json, written whatever the verdict
     * @param modelDir     where the model was saved, or null when blocked
     * @param rejections   per reason, how many cores were not labelled
     */
    public record TrainResult(
            String verdict, String summary, Path reportPath, Path modelDir, Map<String, Integer> rejections) {

        /** @return true when the model may be applied without an acknowledgement */
        public boolean usable() {
            return "OK".equals(verdict) || "WARN".equals(verdict);
        }
    }

    /**
     * @param classNames  model classes in index order
     * @param probability per class, a slide-wide grid
     * @param maxProba    top-1 probability per window
     * @param margin      top1 - top2 per window
     * @param novelty     novelty p per window, NaN when unavailable
     * @param abstained   1 where the window was abstained on
     * @param verdict     the TRAINING verdict, carried through
     * @param warnings    compatibility warnings the run proceeded despite
     * @param features    the features the predictions were made from
     */
    public record ApplyResult(
            List<String> classNames,
            float[][] probability,
            float[] maxProba,
            float[] margin,
            float[] novelty,
            float[] abstained,
            String verdict,
            List<String> warnings,
            ImageFeatures features) {}

    // ======================= features =======================

    /**
     * Compute one image's window features, with context.
     *
     * @param imageData the image
     * @param region    the region to cover, at downsample 1
     * @param request   what to compute
     * @param progress  status sink; may be null
     * @param cancelled polled between tiles; may be null
     * @return the features
     * @throws IOException on an extraction failure or a matrix too large for the heap
     * @throws InterruptedException if cancelled
     */
    public static ImageFeatures computeFeatures(
            ImageData<BufferedImage> imageData,
            RegionRequest region,
            Request request,
            Consumer<String> progress,
            BooleanSupplier cancelled)
            throws IOException, InterruptedException {

        ImageServer<BufferedImage> server = imageData.getServer();
        PixelCalibration cal = server.getPixelCalibration();
        if (cal == null || !cal.hasPixelSizeMicrons()) {
            throw new IOException("Image has no pixel calibration, so a window size in microns has no meaning.");
        }
        double pxUm = cal.getAveragedPixelSizeMicrons();

        WindowFeatureMatrix.Result base = WindowFeatureMatrix.compute(
                server, region, pxUm, request.features(), request.workDir(), progress, cancelled);
        checkMatrixFits(base.windowCount(), base.featureNames().size());

        float[] rows = interleave(base.columns(), base.windowCount());
        List<String> names = base.featureNames();

        if (!request.contextRadii().isEmpty()) {
            if (progress != null) progress.accept("Neighbourhood context...");
            ContextResult ctx =
                    addContext(rows, names, base.gridW(), base.gridH(), request.radiiCsv(), request.workDir());
            rows = ctx.rows();
            names = ctx.names();
        }
        return new ImageFeatures(
                names, rows, base.gridW(), base.gridH(), base.windowPx(), base.stridePx(), base.partial());
    }

    private record ContextResult(List<String> names, float[] rows) {}

    private static ContextResult addContext(
            float[] rows, List<String> names, int gridW, int gridH, String radiiCsv, Path workDir) throws IOException {
        int n = gridW * gridH;
        Path outNpz = workDir.resolve("context.npz");
        NDArray nd = matrix(rows, n, names.size());
        try {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("features", nd);
            in.put("feature_names", String.join(",", names));
            in.put("grid_h", gridH);
            in.put("grid_w", gridW);
            in.put("radii", radiiCsv);
            in.put("output_npz_path", outNpz.toString());
            Task task = runTask("window_context", in);
            List<String> combined = csv(String.valueOf(task.outputs.get("feature_names")));
            NpzReader.Entry feat = NpzReader.read(outNpz).get("features");
            if (feat == null) throw new IOException("window_context wrote no features array");
            if (feat.shape.length != 2 || feat.shape[0] != n || feat.shape[1] != combined.size()) {
                throw new IOException("window_context returned shape " + Arrays.toString(feat.shape) + " for " + n
                        + " windows and " + combined.size() + " features");
            }
            return new ContextResult(combined, feat.asFloat32());
        } finally {
            ApposeArrays.closeQuietly(nd);
        }
    }

    // ======================= labels =======================

    /**
     * Label one image's cores from its annotations.
     *
     * @param hierarchyObjects the image's annotations
     * @param classNames       the selected classes, in index order
     * @param features         that image's features
     * @param region           the region the features cover
     * @param minCorePurity    fraction of a core a class must cover
     * @return the labelling
     */
    public static WindowLabelExtractor.Result labelCores(
            List<PathObject> hierarchyObjects,
            List<String> classNames,
            ImageFeatures features,
            RegionRequest region,
            double minCorePurity) {

        Set<String> selected = new LinkedHashSet<>(classNames);
        List<WindowLabelExtractor.LabelledAnnotation> annotations = new ArrayList<>();
        long id = 0;
        for (PathObject obj : hierarchyObjects) {
            if (!obj.isAnnotation() || obj.getROI() == null) continue;
            String name = AnnotationClassFilter.displayName(obj);
            if (!selected.contains(name)) continue;
            Geometry geom = GeometryTools.roiToGeometry(obj.getROI());
            if (geom == null || geom.isEmpty()) continue;
            annotations.add(new WindowLabelExtractor.LabelledAnnotation(name, ++id, geom));
        }
        var req = new WindowLabelExtractor.Request(
                classNames,
                features.gridW(),
                features.gridH(),
                features.windowPx(),
                features.stridePx(),
                region.getWidth(),
                region.getHeight(),
                region.getX(),
                region.getY(),
                1.0,
                minCorePurity);
        return WindowLabelExtractor.extract(req, annotations);
    }

    // ======================= train =======================

    /**
     * One image's contribution to a training set.
     *
     * @param features the image's features
     * @param labels   the labelling
     * @param groupId  the slide id -- the cross-validation unit
     */
    public record TrainingContribution(ImageFeatures features, WindowLabelExtractor.Result labels, int groupId) {}

    /**
     * Pool the labelled rows and train.
     *
     * @param contributions one per image
     * @param classNames    classes in index order
     * @param geometry      the geometry recorded in the model and re-checked at apply
     * @param modelDir      where to save
     * @param options       training knobs; see the keys the script accepts
     * @return the verdict and the report
     * @throws IOException when the contributions disagree about the feature set,
     *         or no labelled window survives
     */
    public static TrainResult train(
            List<TrainingContribution> contributions,
            List<String> classNames,
            Map<String, Object> geometry,
            Path modelDir,
            Map<String, Object> options)
            throws IOException {

        if (contributions.isEmpty()) {
            throw new IOException("No images contributed any windows.");
        }
        List<String> names = contributions.get(0).features().featureNames();
        for (TrainingContribution c : contributions) {
            if (!names.equals(c.features().featureNames())) {
                throw new IOException("Images produced different feature sets; they cannot be pooled."
                        + " This happens when the segmentation settings or the window geometry differ"
                        + " between images.");
            }
        }
        int d = names.size();

        List<float[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        List<Integer> groups = new ArrayList<>();
        List<Integer> annotations = new ArrayList<>();
        Map<String, Integer> rejections = new LinkedHashMap<>();
        // Annotation ids are per image; offset them so two images' annotation
        // 1 are not treated as one region by the permutation null.
        int annotationOffset = 0;
        for (TrainingContribution c : contributions) {
            int n = c.features().windowCount();
            int maxAnn = 0;
            for (int i = 0; i < n; i++) {
                int cls = c.labels().classIndex()[i];
                if (cls < 0) continue;
                if (c.features().partial()[i]) continue;
                float[] row = new float[d];
                System.arraycopy(c.features().rows(), i * d, row, 0, d);
                rows.add(row);
                labels.add(cls);
                groups.add(c.groupId());
                int ann = (int) c.labels().annotationId()[i];
                maxAnn = Math.max(maxAnn, ann);
                annotations.add(annotationOffset + ann);
            }
            annotationOffset += maxAnn + 1;
            c.labels().counts().forEach((reason, count) -> rejections.merge(reason.name(), count, Integer::sum));
        }
        if (rows.isEmpty()) {
            throw new IOException("No core was fully inside exactly one of the selected classes."
                    + " Draw larger regions, or reduce the stride so a core fits inside them.");
        }
        logger.info(
                "Training set: {} windows, {} features, {} slides, {} annotations",
                rows.size(),
                d,
                groups.stream().distinct().count(),
                annotations.stream().distinct().count());

        NDArray xNd = null;
        NDArray yNd = null;
        NDArray gNd = null;
        NDArray aNd = null;
        try {
            xNd = matrix(flatten(rows, d), rows.size(), d);
            yNd = intVector(labels);
            gNd = intVector(groups);
            aNd = intVector(annotations);

            Map<String, Object> in = new LinkedHashMap<>(options == null ? Map.of() : options);
            in.put("features", xNd);
            in.put("labels", yNd);
            in.put("groups", gNd);
            in.put("annotations", aNd);
            in.put("feature_names", String.join(",", names));
            in.put("class_names", String.join(",", classNames));
            in.put("model_dir", modelDir.toString());
            in.put("geometry_json", GSON.toJson(geometry));

            Task task = runTask("train_window_classifier", in);
            String verdict = String.valueOf(task.outputs.getOrDefault("verdict", "UNKNOWN"));
            String summary = String.valueOf(task.outputs.getOrDefault("summary", ""));
            Object reportPath = task.outputs.get("report_path");
            return new TrainResult(
                    verdict,
                    summary,
                    reportPath == null ? null : Path.of(reportPath.toString()),
                    "BLOCK".equals(verdict) ? null : modelDir,
                    Map.copyOf(rejections));
        } finally {
            ApposeArrays.closeQuietly(xNd, yNd, gNd, aNd);
        }
    }

    // ======================= apply =======================

    /**
     * Score one image's windows.
     *
     * @param features      the image's features
     * @param modelDir      the saved model
     * @param geometry      the geometry the features were measured with
     * @param allowOverride let a geometry mismatch through, recorded
     * @param workDir       scratch directory
     * @return the per-window outputs
     * @throws IOException when the model refuses this data
     */
    public static ApplyResult apply(
            ImageFeatures features, Path modelDir, Map<String, Object> geometry, boolean allowOverride, Path workDir)
            throws IOException {

        int n = features.windowCount();
        Path outNpz = workDir.resolve("applied.npz");
        NDArray nd = matrix(features.rows(), n, features.featureNames().size());
        try {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("features", nd);
            in.put("feature_names", String.join(",", features.featureNames()));
            in.put("model_dir", modelDir.toString());
            in.put("geometry_json", GSON.toJson(geometry));
            in.put("allow_override", allowOverride);
            in.put("output_npz_path", outNpz.toString());

            Task task = runTask("apply_window_classifier", in);
            List<String> classNames = csv(String.valueOf(task.outputs.get("class_names")));
            String verdict = String.valueOf(task.outputs.getOrDefault("verdict", "UNKNOWN"));
            List<String> warnings = new ArrayList<>();
            Object warn = task.outputs.get("warnings");
            if (warn != null && !warn.toString().isBlank()) {
                warnings.addAll(List.of(warn.toString().split("\n")));
            }

            Map<String, NpzReader.Entry> npz = NpzReader.read(outNpz);
            float[] proba = entryFloats(npz, "proba", n * classNames.size());
            float[][] byClass = new float[classNames.size()][n];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < classNames.size(); c++) {
                    byClass[c][i] = proba[i * classNames.size() + c];
                }
            }
            // A partial window is predicted on -- the map should not have an
            // unclassified border -- but it is flagged, so a caller that
            // wants to hide the rim can.
            return new ApplyResult(
                    classNames,
                    byClass,
                    entryFloats(npz, "max_proba", n),
                    entryFloats(npz, "margin", n),
                    entryFloats(npz, "novelty_p", n),
                    intsAsFloats(npz, "abstained", n),
                    verdict,
                    List.copyOf(warnings),
                    features);
        } finally {
            ApposeArrays.closeQuietly(nd);
        }
    }

    // ======================= outputs =======================

    /**
     * Write the probability map: one float32 pixel per window, placed on the
     * source image by its description, the same format as a density map.
     *
     * @param result       the per-window outputs
     * @param outputPath   where to write; see
     *                     {@code DensitySidecar.classifierSidecarPathFor}
     * @param sourceImage  name of the image the windows were laid on
     * @param srcW         source image width
     * @param srcH         source image height
     * @param srcPxUm      source pixel size
     * @param regionX      x of the region the windows were laid on
     * @param regionY      y of that region
     * @param threshold    the {@code ThresholdIdentity} label the features were segmented at, or null
     * @return the channels written, in order, so the caller can colour them
     * @throws IOException if the sidecar cannot be written
     */
    public static List<DensityChannelSpec> writeProbabilityMap(
            ApplyResult result,
            Path outputPath,
            String sourceImage,
            int srcW,
            int srcH,
            double srcPxUm,
            int regionX,
            int regionY,
            String threshold)
            throws IOException {

        int n = result.features().windowCount();
        List<DensityChannelSpec> channels = new ArrayList<>();
        List<float[]> grids = new ArrayList<>();
        for (int c = 0; c < result.classNames().size(); c++) {
            channels.add(
                    DensityChannelSpec.forClassProbability(result.classNames().get(c)));
            grids.add(result.probability()[c]);
        }
        channels.add(DensityChannelSpec.forMaxProbability());
        grids.add(result.maxProba());
        if (result.classNames().size() > 1) {
            channels.add(DensityChannelSpec.forMargin());
            grids.add(result.margin());
        }
        if (hasAnyValue(result.novelty())) {
            channels.add(DensityChannelSpec.forNovelty());
            grids.add(result.novelty());
        }
        // The argmax index goes last and carries no unit: it is a label, not
        // a quantity. An abstained window is NaN here rather than carrying
        // the class the model declined to commit to.
        float[] predicted = new float[n];
        for (int i = 0; i < n; i++) {
            predicted[i] = result.abstained()[i] >= 0.5f ? Float.NaN : argmax(result.probability(), i);
        }
        channels.add(DensityChannelSpec.forPredictedClassIndex());
        grids.add(predicted);

        Files.createDirectories(outputPath.getParent());
        DensityLattice lattice = DensityLattice.windows(
                result.features().gridW(),
                result.features().gridH(),
                result.features().stridePx(),
                result.features().windowPx(),
                regionX,
                regionY);
        DensityTiffWriter.write(
                outputPath.toString(),
                new DensityMapInfo(lattice, srcW, srcH, srcPxUm, false, sourceImage, threshold, null, List.of()),
                channels,
                grids.toArray(new float[0][]));
        logger.info(
                "Classifier map: {} ({} channels over a {}x{} grid)",
                outputPath,
                channels.size(),
                result.features().gridW(),
                result.features().gridH());
        return List.copyOf(channels);
    }

    /**
     * Build the core detections for an apply result.
     *
     * @param result        the per-window outputs
     * @param region        the region the features cover
     * @param classifierId  the model id, stamped on every object
     * @param runId         this run's id, stamped on every object
     * @param provisional   true when the model failed its own validation gate
     * @param extraFeatures feature names to carry onto the objects as
     *                      measurements; names not in the matrix are ignored
     * @return one detection per predicted core
     */
    public static List<PathObject> coreDetections(
            ApplyResult result,
            RegionRequest region,
            String classifierId,
            String runId,
            boolean provisional,
            List<String> extraFeatures) {

        ImageFeatures f = result.features();
        Map<String, float[]> extras = new LinkedHashMap<>();
        for (String name : extraFeatures == null ? List.<String>of() : extraFeatures) {
            int idx = f.featureNames().indexOf(name);
            if (idx >= 0) extras.put(name, f.column(idx));
        }
        var geom = new ClassifiedCoreDetections.Geometry(
                result.classNames(),
                f.gridW(),
                f.gridH(),
                f.windowPx(),
                f.stridePx(),
                region.getX(),
                region.getY(),
                1.0,
                classifierId,
                runId,
                provisional);
        var grids = new ClassifiedCoreDetections.Grids(
                result.probability(),
                hasAnyValue(result.novelty()) ? result.novelty() : null,
                result.abstained(),
                extras);
        return ClassifiedCoreDetections.create(geom, grids);
    }

    /**
     * Per-class colours for the probability channels, taken from the
     * pathologist's own PathClasses so a channel renders in the colour that
     * class already has everywhere else in QuPath.
     *
     * @param classNames the model's classes
     * @return channel name to packed RGB
     */
    public static Map<String, Integer> probabilityChannelColors(List<String> classNames) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String name : classNames) {
            var pc = PathClass.fromString(name);
            if (pc != null) {
                out.put(DensityChannelSpec.forClassProbability(name).channelName, pc.getColor());
            }
        }
        return out;
    }

    private static boolean hasAnyValue(float[] values) {
        for (float v : values) {
            if (!Float.isNaN(v)) return true;
        }
        return false;
    }

    private static float argmax(float[][] byClass, int i) {
        int best = -1;
        float p = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < byClass.length; c++) {
            float v = byClass[c][i];
            if (Float.isNaN(v)) continue;
            if (v > p) {
                p = v;
                best = c;
            }
        }
        return best < 0 ? Float.NaN : best;
    }

    // ======================= plumbing =======================

    private static Task runTask(String script, Map<String, Object> inputs) throws IOException {
        Task task;
        try {
            task = ApposeFiberService.getInstance().runTask(script, inputs);
        } catch (Exception ex) {
            throw new IOException("Appose " + script + " failed: " + ex.getMessage(), ex);
        }
        Object ok = task.outputs.get("ok");
        if (ok == null || !"1".equals(ok.toString())) {
            throw new IOException(script + " did not complete: " + task.outputs.getOrDefault("error", "unknown"));
        }
        return task;
    }

    /** A float32 (rows, cols) array from row-major values; the caller closes it. */
    private static NDArray matrix(float[] values, int rows, int cols) {
        NDArray nd = ApposeArrays.allocate(NDArray.DType.FLOAT32, rows, cols);
        ApposeArrays.bytes(nd).asFloatBuffer().put(values, 0, rows * cols);
        return nd;
    }

    private static NDArray intVector(List<Integer> values) {
        return ApposeArrays.int32Vector(
                values.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Column-major grids to row-major rows, which is what the model wants. */
    private static float[] interleave(float[][] columns, int n) {
        int d = columns.length;
        float[] out = new float[(long) n * d > Integer.MAX_VALUE ? 0 : n * d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                out[i * d + j] = columns[j][i];
            }
        }
        return out;
    }

    private static float[] flatten(List<float[]> rows, int d) {
        float[] out = new float[rows.size() * d];
        for (int i = 0; i < rows.size(); i++) {
            System.arraycopy(rows.get(i), 0, out, i * d, d);
        }
        return out;
    }

    private static float[] entryFloats(Map<String, NpzReader.Entry> npz, String key, int expected) throws IOException {
        NpzReader.Entry e = npz.get(key);
        if (e == null) throw new IOException("Model output is missing '" + key + "'");
        float[] v = e.asFloat32();
        if (v.length != expected) {
            throw new IOException("Model output '" + key + "' has " + v.length + " values, expected " + expected);
        }
        return v;
    }

    private static float[] intsAsFloats(Map<String, NpzReader.Entry> npz, String key, int expected) throws IOException {
        NpzReader.Entry e = npz.get(key);
        if (e == null) throw new IOException("Model output is missing '" + key + "'");
        int[] v = e.asInt32();
        if (v.length != expected) {
            throw new IOException("Model output '" + key + "' has " + v.length + " values, expected " + expected);
        }
        float[] out = new float[expected];
        for (int i = 0; i < expected; i++) out[i] = v[i];
        return out;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return List.of();
        return List.of(s.split(","));
    }

    /**
     * Refuse a matrix that will not fit, before spending minutes filling it.
     *
     * @param nWindows  windows on the lattice
     * @param nFeatures columns
     * @throws IOException when the matrix would take too much of the heap
     */
    static void checkMatrixFits(int nWindows, int nFeatures) throws IOException {
        long bytes = (long) nWindows * nFeatures * Float.BYTES;
        long cap = (long) (Runtime.getRuntime().maxMemory() * MATRIX_HEAP_FRACTION);
        if (bytes > cap) {
            throw new IOException(String.format(
                    java.util.Locale.ROOT,
                    "The feature matrix for %,d windows x %d features would need %.1f GB, above the"
                            + " %.1f GB this session allows. Increase the stride, restrict the run to an"
                            + " annotation, or give QuPath more heap.",
                    nWindows,
                    nFeatures,
                    bytes / 1.0e9,
                    cap / 1.0e9));
        }
    }

    /** @return a scratch directory for one run, under the system temp area */
    public static Path createWorkDir() throws IOException {
        return Files.createTempDirectory("fiber-window-classifier-");
    }
}
