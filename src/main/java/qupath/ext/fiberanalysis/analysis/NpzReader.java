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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal reader for NumPy's npz format -- a zip of npy files, one per array.
 * Only the subset needed by the density-map pipeline is implemented:
 * little-endian float32 (C-order) and little-endian int32 arrays. Headers are
 * parsed via a tiny string-scan instead of pulling in a full Python-literal
 * evaluator -- the npy header dict is always single-line ASCII written by
 * numpy itself, so a regex-light scan is sufficient.
 *
 * <p>npy v1 layout (the only version numpy.savez_compressed emits): bytes
 * {@code 0x93 'N' 'U' 'M' 'P' 'Y'}, major (1), minor (0), uint16 header
 * length (little-endian), then header bytes (ASCII Python dict literal),
 * then the raw data. Higher versions use uint32 header length; not needed.
 */
public final class NpzReader {

    private NpzReader() {}

    /** Parsed npy entry: dtype + shape + raw bytes. */
    public static final class Entry {
        public final String dtype;
        public final int[] shape;
        public final byte[] data;

        Entry(String dtype, int[] shape, byte[] data) {
            this.dtype = dtype;
            this.shape = shape;
            this.data = data;
        }

        /** Number of elements (product of shape). */
        public int size() {
            int n = 1;
            for (int d : shape) n *= d;
            return n;
        }

        /** Convert little-endian float32 bytes to a float[]. Caller validates dtype. */
        public float[] asFloat32() {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[size()];
            bb.asFloatBuffer().get(out);
            return out;
        }

        /** Convert little-endian int32 bytes to an int[]. Caller validates dtype. */
        public int[] asInt32() {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[size()];
            bb.asIntBuffer().get(out);
            return out;
        }

        /** First scalar element (handy for the 0-d ints in the metadata block). */
        public int asScalarInt32() {
            return asInt32()[0];
        }
    }

    /** Read an npz file into a name -> Entry map. Strips the ".npy" suffix from keys. */
    public static Map<String, Entry> read(Path npzPath) throws IOException {
        Map<String, Entry> out = new HashMap<>();
        try (InputStream in = Files.newInputStream(npzPath);
                ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                String name = ze.getName();
                if (!name.endsWith(".npy")) continue;
                String key = name.substring(0, name.length() - 4);
                Entry e = readNpyEntry(zin);
                out.put(key, e);
            }
        }
        return out;
    }

    /** Parse one npy stream (header + data). Stream stays positioned at the next entry. */
    private static Entry readNpyEntry(InputStream in) throws IOException {
        byte[] magic = in.readNBytes(6);
        if (magic.length != 6
                || magic[0] != (byte) 0x93
                || magic[1] != 'N'
                || magic[2] != 'U'
                || magic[3] != 'M'
                || magic[4] != 'P'
                || magic[5] != 'Y') {
            throw new IOException("Not an npy stream (bad magic)");
        }
        int major = in.read();
        int minor = in.read();
        int headerLen;
        if (major == 1) {
            // uint16 little-endian
            int b0 = in.read();
            int b1 = in.read();
            headerLen = (b0 & 0xff) | ((b1 & 0xff) << 8);
        } else if (major == 2 || major == 3) {
            // uint32 little-endian
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            headerLen = (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
        } else {
            throw new IOException("Unsupported npy major version: " + major);
        }
        byte[] headerBytes = in.readNBytes(headerLen);
        String header = new String(headerBytes, java.nio.charset.StandardCharsets.US_ASCII);

        String dtype = extractString(header, "descr");
        int[] shape = extractShape(header);

        int itemSize = itemSizeForDtype(dtype);
        long total = 1L;
        for (int d : shape) total *= d;
        // numpy saves 0-d arrays as shape=() with size 1
        if (shape.length == 0) total = 1L;
        int byteCount = Math.toIntExact(total * itemSize);
        byte[] data = in.readNBytes(byteCount);
        if (data.length != byteCount) {
            throw new IOException("Short read for npy data: expected " + byteCount + " bytes, got " + data.length);
        }
        // Some npy entries pad the header to align data; numpy 1.x pads with
        // spaces, included in headerLen above. No extra skip required.
        // 0-d arrays from numpy come through with shape == [] but size 1.
        int[] effectiveShape = shape.length == 0 ? new int[] {1} : shape;
        return new Entry(dtype, effectiveShape, data);
    }

    private static int itemSizeForDtype(String dtype) throws IOException {
        if (dtype == null) throw new IOException("npy header missing descr");
        // dtype is something like '<f4', '<i4', '<i8'. We support the float32
        // and int32 forms used by density_tile.py.
        switch (dtype) {
            case "<f4":
                return 4;
            case "<i4":
                return 4;
            case "<i8":
                return 8;
            default:
                throw new IOException("Unsupported npy dtype: " + dtype + " (need '<f4' or '<i4')");
        }
    }

    /**
     * Extract a string value (single-quoted) from the npy header. Scans for
     * {@code 'key': '...'} and returns the contents. Returns null if absent.
     */
    private static String extractString(String header, String key) {
        String marker = "'" + key + "':";
        int i = header.indexOf(marker);
        if (i < 0) return null;
        int j = header.indexOf('\'', i + marker.length());
        if (j < 0) return null;
        int k = header.indexOf('\'', j + 1);
        if (k < 0) return null;
        return header.substring(j + 1, k);
    }

    /** Extract the {@code 'shape': (a, b, c)} tuple from the npy header. */
    private static int[] extractShape(String header) throws IOException {
        int i = header.indexOf("'shape':");
        if (i < 0) throw new IOException("npy header missing shape");
        int open = header.indexOf('(', i);
        int close = header.indexOf(')', open + 1);
        if (open < 0 || close < 0) throw new IOException("npy header malformed shape tuple");
        String tuple = header.substring(open + 1, close).trim();
        if (tuple.isEmpty()) return new int[0];
        // Tuple is comma-separated ints, possibly with a trailing comma for
        // single-element tuples ("(5,)").
        String[] parts = tuple.split(",");
        java.util.ArrayList<Integer> dims = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            dims.add(Integer.parseInt(t));
        }
        int[] out = new int[dims.size()];
        for (int n = 0; n < out.length; n++) out[n] = dims.get(n);
        return out;
    }
}
