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
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

/**
 * Shared matching for the "Annotations of class..." filter: one definition of
 * how a {@link PathClass} is named in the picker and how a persisted CSV of
 * those names is matched back against objects.
 */
final class AnnotationClassFilter {

    /** Picker entry standing for objects with no class assigned. */
    static final String UNCLASSIFIED = "Unclassified";

    private AnnotationClassFilter() {}

    /**
     * @param obj object to name (may be null)
     * @return the object's full class name, including derived-class colons
     *     ("Tumor: Stroma"), or {@link #UNCLASSIFIED} when it has no class
     */
    static String displayName(PathObject obj) {
        PathClass pc = obj == null ? null : obj.getPathClass();
        return displayName(pc);
    }

    /**
     * @param pc class to name (may be null)
     * @return the full class name, or {@link #UNCLASSIFIED} for null / the null class
     */
    static String displayName(PathClass pc) {
        return (pc == null || pc == PathClass.NULL_CLASS) ? UNCLASSIFIED : pc.toString();
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
     * @param obj object to test (may be null)
     * @param wanted class names from {@link #parse}
     * @return true when the object's class is wanted; unclassified objects match
     *     only the {@link #UNCLASSIFIED} entry. A bare name ("Stroma") also
     *     matches a derived class ("Tumor: Stroma") so hand-typed filters work.
     */
    static boolean matches(PathObject obj, Set<String> wanted) {
        if (obj == null || wanted.isEmpty()) return false;
        PathClass pc = obj.getPathClass();
        if (pc == null || pc == PathClass.NULL_CLASS) {
            return wanted.contains(UNCLASSIFIED);
        }
        if (wanted.contains(pc.toString())) return true;
        String name = pc.getName();
        return name != null && wanted.contains(name);
    }
}
