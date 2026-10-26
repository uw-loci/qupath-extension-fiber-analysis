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

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/** The image-name part of every map file's name. */
public final class MapFileName {

    /** Longest name {@link #safe} returns; Windows allows 255 characters per file name. */
    static final int MAX_LENGTH = 200;

    /**
     * The file-safe form of an image name that every map file is named by:
     * each run of {@code \ / : * ? " < > |} or white space becomes {@code _}, and
     * trailing dots and spaces are removed, the same as QuIET's {@code {imageSafe}}.
     * A name longer than {@value #MAX_LENGTH} characters is cut and ends in
     * {@code _} plus 8 hex digits of a hash of the whole name, so two long names
     * that share a beginning still get different files.
     *
     * @param name an image name
     * @return the safe name; {@code image} if nothing is left
     */
    public static String safe(String name) {
        if (name == null || name.isBlank()) return "image";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        s = s.replaceAll("[. ]+$", "");
        if (s.isBlank()) return "image";
        if (s.length() > MAX_LENGTH) {
            CRC32 crc = new CRC32();
            crc.update(name.getBytes(StandardCharsets.UTF_8));
            s = s.substring(0, MAX_LENGTH - 9) + String.format(Locale.ROOT, "_%08x", crc.getValue());
        }
        return s;
    }

    private MapFileName() {}
}
