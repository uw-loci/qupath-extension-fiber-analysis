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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;

/**
 * Finds, reads and describes saved window classifiers.
 *
 * <p>Two locations, project first: {@code <project>/classifiers/fiber-window/<id>/}
 * for a model that belongs with the data it was trained on, and
 * {@code ~/.qupath/fiber-window-classifiers/<id>/} for one the user wants
 * across projects. A project model shadows a user model of the same id,
 * because the project copy is the one that travels with the annotations that
 * produced it.
 *
 * <p>Reading only. The model is written by {@code train_window_classifier.py},
 * which owns the layout; this class knows how to find it and what its
 * metadata means. Parsing uses {@link ToNumberPolicy#LONG_OR_DOUBLE} -- Gson's
 * default turns every number into a double, and a model whose metadata says
 * {@code stride_px: 25.0} fails a geometry comparison against an int 25 that
 * it should pass.
 */
public final class WindowClassifierModelManager {

    private static final Logger logger = LoggerFactory.getLogger(WindowClassifierModelManager.class);

    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    /** Fractional pixel-size difference allowed before the geometry is a mismatch. */
    public static final double PIXEL_SIZE_TOLERANCE = 0.02;

    private WindowClassifierModelManager() {}

    /**
     * A saved model, as its metadata describes it.
     *
     * @param id           the folder name
     * @param directory    where it lives
     * @param projectLocal true when it came from the project rather than the user folder
     * @param classNames   predicted classes, in model index order
     * @param featureNames the columns the estimator consumes, in order
     * @param geometry     the geometry it was trained at
     * @param verdict      the training verdict: OK, WARN, REFUSE or BLOCK
     * @param createdUtc   when it was written
     * @param training     the training block, for the dialog's summary table
     */
    public record ModelInfo(
            String id,
            Path directory,
            boolean projectLocal,
            List<String> classNames,
            List<String> featureNames,
            Map<String, Object> geometry,
            String verdict,
            String createdUtc,
            Map<String, Object> training) {

        /**
         * @return true when the training run refused this model, so it may
         *         only be applied with an explicit acknowledgement and its
         *         objects carry a provisional flag
         */
        public boolean provisional() {
            return "REFUSE".equals(verdict) || "BLOCK".equals(verdict);
        }

        /** @return a one-line description for a list cell */
        public String label() {
            String acc = "";
            Object ba = training.get("balanced_accuracy");
            if (ba instanceof Number n) {
                acc = String.format(Locale.ROOT, ", balanced accuracy %.3f", n.doubleValue());
            }
            return String.format(
                    Locale.ROOT,
                    "%s (%d classes%s) [%s]%s",
                    id,
                    classNames.size(),
                    acc,
                    verdict == null ? "no verdict" : verdict,
                    projectLocal ? "" : " (user)");
        }
    }

    /** @param project the open project, or null
     *  @return the project-local classifier folder, or null when the project has no path */
    public static Path projectRoot(Project<?> project) {
        if (project == null || project.getPath() == null) return null;
        Path dir = project.getPath().getParent();
        if (dir == null) return null;
        return dir.resolve("classifiers").resolve("fiber-window");
    }

    /** @return the user-level classifier folder, outside any project */
    public static Path userRoot() {
        return Path.of(System.getProperty("user.home"), ".qupath", "fiber-window-classifiers");
    }

    /**
     * Where a newly trained model should be written.
     *
     * @param project     the open project, or null for the user folder
     * @param id          the model id
     * @param projectLocal true to write into the project
     * @return the directory to train into
     * @throws IOException when a project-local location was asked for and the
     *         project has no path on disk
     */
    public static Path resolveForWrite(Project<?> project, String id, boolean projectLocal) throws IOException {
        if (projectLocal) {
            Path root = projectRoot(project);
            if (root == null) {
                throw new IOException("This project has no folder on disk, so a classifier cannot be saved"
                        + " inside it. Save to the user folder instead.");
            }
            return root.resolve(sanitizeId(id));
        }
        return userRoot().resolve(sanitizeId(id));
    }

    /**
     * Every readable model, project copies first and shadowing user copies of
     * the same id.
     *
     * @param project the open project, or null
     * @return the models, in list order for a picker
     */
    public static List<ModelInfo> list(Project<?> project) {
        Map<String, ModelInfo> byId = new LinkedHashMap<>();
        for (ModelInfo m : scan(userRoot(), false)) {
            byId.put(m.id(), m);
        }
        for (ModelInfo m : scan(projectRoot(project), true)) {
            byId.put(m.id(), m); // project shadows user
        }
        List<ModelInfo> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> {
            if (a.projectLocal() != b.projectLocal()) return a.projectLocal() ? -1 : 1;
            return a.id().compareToIgnoreCase(b.id());
        });
        return out;
    }

    private static List<ModelInfo> scan(Path root, boolean projectLocal) {
        List<ModelInfo> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try {
                    out.add(read(dir, projectLocal));
                } catch (IOException ex) {
                    logger.warn("Skipping {}: {}", dir, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            logger.warn("Could not list classifiers under {}: {}", root, ex.getMessage());
        }
        return out;
    }

    /**
     * Read one model's metadata.
     *
     * @param directory    the model folder
     * @param projectLocal whether it came from the project
     * @return what the metadata says
     * @throws IOException when the folder is not a saved classifier
     */
    public static ModelInfo read(Path directory, boolean projectLocal) throws IOException {
        Path metaPath = directory.resolve("metadata.json");
        if (!Files.isRegularFile(metaPath)) {
            throw new IOException("no metadata.json; not a saved fiber window classifier");
        }
        JsonObject meta;
        try (Reader reader = Files.newBufferedReader(metaPath)) {
            meta = GSON.fromJson(reader, JsonObject.class);
        }
        if (meta == null) {
            throw new IOException("metadata.json is empty");
        }
        if (!Files.isRegularFile(directory.resolve("model.joblib"))) {
            throw new IOException("metadata.json is present but model.joblib is not");
        }
        return new ModelInfo(
                directory.getFileName().toString(),
                directory,
                projectLocal,
                strings(meta, "class_names"),
                strings(meta, "feature_names"),
                flatten(meta.getAsJsonObject("geometry")),
                meta.has("verdict") && !meta.get("verdict").isJsonNull()
                        ? meta.get("verdict").getAsString()
                        : null,
                meta.has("created_utc") ? meta.get("created_utc").getAsString() : "",
                flatten(meta.getAsJsonObject("training")));
    }

    /**
     * Java-side pre-check of whether a model may score this data, so the
     * dialog can say so before an Appose round trip.
     *
     * <p>Advisory only -- {@code wmodel.check_compatibility} runs again on the
     * Python side and is the one that refuses. Having both is deliberate:
     * this one exists to put the answer in front of the user while they can
     * still change it.
     *
     * @param model        the model
     * @param featureNames the columns about to be handed to it
     * @param geometry     the geometry those columns were measured with
     * @return problems, worst first; empty when everything matches
     */
    public static List<String> checkCompatibility(
            ModelInfo model, List<String> featureNames, Map<String, Object> geometry) {
        List<String> problems = new ArrayList<>();

        // A forest's saved columns include the appended missingness
        // indicators; compatibility is about the measured features.
        List<String> trained = model.featureNames();
        List<String> measured =
                trained.stream().filter(n -> !n.endsWith("_isnan")).toList();
        if (!measured.equals(featureNames)) {
            List<String> missing =
                    measured.stream().filter(n -> !featureNames.contains(n)).toList();
            List<String> extra =
                    featureNames.stream().filter(n -> !measured.contains(n)).toList();
            if (!missing.isEmpty() || !extra.isEmpty()) {
                problems.add("Feature set does not match: "
                        + (missing.isEmpty() ? "" : "missing " + missing + " ")
                        + (extra.isEmpty() ? "" : "unexpected " + extra));
            } else {
                problems.add("Same features in a different order; a model scores columns by position.");
            }
        }

        Double trainedPx = asDouble(model.geometry().get("pixel_size_um"));
        Double havePx = asDouble(geometry.get("pixel_size_um"));
        if (trainedPx != null && havePx != null && trainedPx > 0) {
            double rel = Math.abs(havePx - trainedPx) / trainedPx;
            if (rel > PIXEL_SIZE_TOLERANCE) {
                problems.add(String.format(
                        Locale.ROOT,
                        "Pixel size differs: trained at %.4f um/px, this image is %.4f um/px."
                                + " Lacunarity, gaps and texture are computed over box sizes in PIXELS,"
                                + " so a window of the same size in microns is not the same measurement.",
                        trainedPx,
                        havePx));
            }
        }
        for (String key : new String[] {"window_um", "stride_px", "core_px"}) {
            Double a = asDouble(model.geometry().get(key));
            Double b = asDouble(geometry.get(key));
            if (a != null && b != null && Math.abs(a - b) > 1e-9) {
                problems.add(String.format(Locale.ROOT, "%s differs: trained %s, now %s", key, a, b));
            }
        }
        Object trainedHash = model.geometry().get("segmentation_hash");
        Object haveHash = geometry.get("segmentation_hash");
        if (trainedHash != null && haveHash != null && !trainedHash.equals(haveHash)) {
            problems.add("Segmentation settings differ from the ones the model was trained on" + " (" + trainedHash
                    + " vs " + haveHash + "), so the fiber masks are not the same masks.");
        }
        return problems;
    }

    /** @param id a user-supplied model name
     *  @return a filesystem-safe folder name */
    public static String sanitizeId(String id) {
        if (id == null || id.isBlank()) return "model";
        String s = id.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        s = s.replaceAll("[.]+$", "");
        if (s.isBlank()) return "model";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static List<String> strings(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(key);
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (e != null && !e.isJsonNull()) out.add(e.getAsString());
        }
        return List.copyOf(out);
    }

    /** Flatten a metadata block to plain Java values; nested objects keep their JSON form. */
    private static Map<String, Object> flatten(JsonObject obj) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (obj == null) return out;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                out.put(e.getKey(), null);
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                out.put(e.getKey(), v.getAsNumber());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                out.put(e.getKey(), v.getAsBoolean());
            } else if (v.isJsonPrimitive()) {
                out.put(e.getKey(), v.getAsString());
            } else {
                out.put(e.getKey(), v.toString());
            }
        }
        return out;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
