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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectPredicates;
import qupath.lib.objects.classes.PathClass;

/**
 * Adapter between the class names the dialogs persist as CSV and QuPath's
 * {@link PathObjectPredicates#exactClassification} matching. Only the name
 * round-trip lives here; the matching rule is QuPath's.
 */
final class AnnotationClassFilter {

    /**
     * Picker entry standing for objects with no class. This is QuPath's own
     * name for the null class, so it stays in step with the class list, the
     * measurement table and the annotation pane.
     */
    static final String UNCLASSIFIED = PathClass.NULL_CLASS.toString();

    private AnnotationClassFilter() {}

    /**
     * @param obj object to name (may be null)
     * @return the object's class name as QuPath displays it
     */
    static String displayName(PathObject obj) {
        return displayName(obj == null ? null : obj.getPathClass());
    }

    /**
     * @param pc class to name (may be null)
     * @return {@code pc.toString()}, or {@link #UNCLASSIFIED} for null / the null class
     */
    static String displayName(PathClass pc) {
        return pc == null ? UNCLASSIFIED : pc.toString();
    }

    /**
     * @param csv comma-separated class names as persisted by the dialogs
     * @return the trimmed, non-empty names; empty when the CSV is blank
     */
    static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String tok : csv.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * @param names class names from {@link #parse}; {@link #UNCLASSIFIED} selects
     *     objects with no class
     * @return a predicate accepting objects in one of those classes; matches
     *     nothing when {@code names} is empty
     */
    static Predicate<PathObject> predicate(Set<String> names) {
        if (names == null || names.isEmpty()) return obj -> false;
        PathClass[] classes = names.stream()
                // fromString("Unclassified") is a real class of that name, not the
                // null class, so the sentinel has to map to null explicitly.
                .map(n -> UNCLASSIFIED.equals(n) ? null : PathClass.fromString(n))
                .toArray(PathClass[]::new);
        return PathObjectPredicates.exactClassification(classes);
    }
}
