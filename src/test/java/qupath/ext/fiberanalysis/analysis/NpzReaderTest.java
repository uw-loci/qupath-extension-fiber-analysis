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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * density_tile.py has written a uint8 {@code density_mode} tag into every
 * tile since the per-pixel mode was added, and the reader refused the whole
 * file over it -- so the density map could not complete a single tile. The
 * first live run of the 0.3.10 tiling found it.
 */
class NpzReaderTest {

    @TempDir
    Path dir;

    private static byte[] npy(String descr, int n, byte[] data) {
        String header = "{'descr': '" + descr + "', 'fortran_order': False, 'shape': (" + n + ",), }";
        StringBuilder sb = new StringBuilder(header);
        while ((10 + sb.length() + 1) % 64 != 0) sb.append(' ');
        sb.append('\n');
        byte[] h = sb.toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0});
        out.write(h.length & 0xff);
        out.write((h.length >> 8) & 0xff);
        out.writeBytes(h);
        out.writeBytes(data);
        return out.toByteArray();
    }

    private Path npz(Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve("t.npz");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".npy"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return p;
    }

    @Test
    void aUint8TagNoLongerFailsTheFloatArraysBesideIt() throws IOException {
        byte[] f = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(1.5f)
                .putFloat(-2f)
                .array();
        Path p = npz(new java.util.LinkedHashMap<>(Map.of(
                "density_mode", npy("|u1", 6, "window".getBytes(StandardCharsets.US_ASCII)),
                "signal_density", npy("<f4", 2, f))));
        Map<String, NpzReader.Entry> m = NpzReader.read(p);
        assertThat(m.get("signal_density").asFloat32()).containsExactly(1.5f, -2f);
        assertThat(m.get("density_mode").size()).isEqualTo(6);
    }

    @Test
    void anAccessorRefusesTheWrongTypeInsteadOfReinterpretingBytes() throws IOException {
        Path p = npz(Map.of("tag", npy("|u1", 4, new byte[] {1, 2, 3, 4})));
        NpzReader.Entry e = NpzReader.read(p).get("tag");
        assertThatThrownBy(e::asFloat32).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(e::asInt32).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bigEndianAndNonNumericTypesAreRefused() {
        assertThatThrownBy(() -> NpzReader.itemSizeForDtype(">f4")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NpzReader.itemSizeForDtype("<U8")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NpzReader.itemSizeForDtype("|O")).isInstanceOf(IOException.class);
    }
}
