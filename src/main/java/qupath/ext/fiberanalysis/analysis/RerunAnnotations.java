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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * Records which annotations a run analyzed, by name and outline bounds, and
 * finds the same ones again for {@code rerun.groovy}.
 *
 * <p>A named annotation is found by its name (the one with the same bounds
 * first, when names repeat); an unnamed one by its bounds. A rerun therefore
 * analyzes the annotations the run did, never every annotation on the image.
 */
public final class RerunAnnotations {

    /** Bounds may differ by this much, in pixels, and still match. */
    private static final double TOLERANCE_PX = 0.5;

    private RerunAnnotations() {}

    /**
     * @param annotations the run's annotations, in run order
     * @return a JSON array with each one's name (or null) and ROI bounds
     */
    static String describe(List<PathObject> annotations) {
        JsonArray out = new JsonArray();
        if (annotations != null) {
            for (PathObject ann : annotations) {
                ROI roi = ann.getROI();
                if (roi == null) continue;
                JsonObject o = new JsonObject();
                String name = ann.getName();
                o.addProperty("name", name == null || name.isBlank() ? null : name);
                o.addProperty("x", roi.getBoundsX());
                o.addProperty("y", roi.getBoundsY());
                o.addProperty("w", roi.getBoundsWidth());
                o.addProperty("h", roi.getBoundsHeight());
                out.add(o);
            }
        }
        return new Gson().toJson(out);
    }

    /**
     * Finds the recorded annotations on the image again.
     *
     * @param annotations the image's annotations
     * @param recorded    the JSON {@link #describe} wrote
     * @return the matching annotations, in the recorded order
     * @throws IllegalArgumentException when nothing was recorded or a recorded annotation is not
     *     on the image any more, naming it
     */
    public static List<PathObject> select(Collection<PathObject> annotations, String recorded) {
        JsonArray records = recorded == null || recorded.isBlank()
                ? new JsonArray()
                : new Gson().fromJson(recorded, JsonArray.class);
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException(
                    "This rerun script names no annotations, so it cannot tell which ones the run analyzed.");
        }
        List<PathObject> candidates = new ArrayList<>();
        for (PathObject a : annotations) {
            if (a != null
                    && a.isAnnotation()
                    && a.getROI() != null
                    && !FiberSegmentationPreviewWindow.isPreviewMarker(a)) candidates.add(a);
        }
        Set<PathObject> chosen = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (JsonElement el : records) {
            JsonObject r = el.getAsJsonObject();
            String name =
                    r.has("name") && !r.get("name").isJsonNull() ? r.get("name").getAsString() : null;
            PathObject match = find(candidates, chosen, name, r, true);
            if (match == null && name != null) match = find(candidates, chosen, name, r, false);
            if (match == null) missing.add(label(name, r));
            else chosen.add(match);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The run's annotations are not all on the image any more."
                    + " Not found: " + String.join("; ", missing)
                    + ". Restore them, or start a new run from the Fiber Analysis dialog.");
        }
        return new ArrayList<>(chosen);
    }

    private static PathObject find(
            List<PathObject> candidates, Set<PathObject> chosen, String name, JsonObject r, boolean withBounds) {
        for (PathObject a : candidates) {
            String aName = a.getName() == null || a.getName().isBlank() ? null : a.getName();
            if (chosen.contains(a) || !Objects.equals(aName, name)) continue;
            if (!withBounds || sameBounds(a, r)) return a;
        }
        return null;
    }

    private static boolean sameBounds(PathObject a, JsonObject r) {
        ROI roi = a.getROI();
        return close(roi.getBoundsX(), r, "x")
                && close(roi.getBoundsY(), r, "y")
                && close(roi.getBoundsWidth(), r, "w")
                && close(roi.getBoundsHeight(), r, "h");
    }

    private static boolean close(double v, JsonObject r, String key) {
        return r.has(key) && Math.abs(v - r.get(key).getAsDouble()) <= TOLERANCE_PX;
    }

    private static String label(String name, JsonObject r) {
        return String.format(
                Locale.ROOT,
                "%s at (%.0f, %.0f) size %.0f x %.0f",
                name == null ? "an unnamed annotation" : "\"" + name + "\"",
                r.get("x").getAsDouble(),
                r.get("y").getAsDouble(),
                r.get("w").getAsDouble(),
                r.get("h").getAsDouble());
    }
}
