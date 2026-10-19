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

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

/**
 * Reads grid cells from a map file: one channel whole, or a block of cells
 * with every channel. The file is opened with QuPath's own image servers.
 */
public final class DensityMapReader implements AutoCloseable {

    /** Rows per read when a whole channel is assembled, so only one channel is held at a time. */
    static final int STRIP_ROWS = 512;

    private final ImageServer<BufferedImage> server;
    private final DensityMapInfo info;

    private DensityMapReader(ImageServer<BufferedImage> server, DensityMapInfo info) {
        this.server = server;
        this.info = info;
    }

    /**
     * @param path a map file
     * @return a reader; close it when done
     * @throws IOException if the file cannot be opened or predates grid-resolution maps
     */
    public static DensityMapReader open(Path path) throws IOException {
        DensityMapInfo info = DensitySidecar.readInfo(path);
        try {
            ImageServer<BufferedImage> server =
                    ImageServers.buildServer(path.toUri().toString());
            DensityLattice lat = info.lattice();
            if (server.getWidth() != lat.gridW() || server.getHeight() != lat.gridH()) {
                server.close();
                throw new IOException(path.getFileName() + " is " + server.getWidth() + "x" + server.getHeight()
                        + " but its description says " + lat.gridW() + "x" + lat.gridH());
            }
            return new DensityMapReader(server, info);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not open " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** @return the map's placement, render mode and channels */
    public DensityMapInfo info() {
        return info;
    }

    /**
     * @param channel channel index
     * @return the whole channel, {@code gridW * gridH}, NaN = no data
     * @throws IOException if the read fails
     */
    public float[] readChannel(int channel) throws IOException {
        int w = info.lattice().gridW();
        int h = info.lattice().gridH();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y += STRIP_ROWS) {
            int hh = Math.min(STRIP_ROWS, h - y);
            Raster r = read(0, y, w, hh);
            float[] strip = r.getSamples(0, 0, w, hh, channel, (float[]) null);
            System.arraycopy(strip, 0, out, y * w, strip.length);
        }
        return out;
    }

    /**
     * @param gx first cell column
     * @param gy first cell row
     * @param w  columns
     * @param h  rows
     * @return {@code [channel][row * w + column]}, NaN = no data
     * @throws IOException if the read fails
     */
    public float[][] readBlock(int gx, int gy, int w, int h) throws IOException {
        Raster r = read(gx, gy, w, h);
        float[][] out = new float[info.channels().size()][];
        int bands = Math.min(out.length, r.getNumBands());
        for (int c = 0; c < out.length; c++) {
            out[c] = c < bands ? r.getSamples(0, 0, w, h, c, (float[]) null) : nanArray(w * h);
        }
        return out;
    }

    private Raster read(int x, int y, int w, int h) throws IOException {
        return server.readRegion(RegionRequest.createInstance(server.getPath(), 1.0, x, y, w, h))
                .getRaster();
    }

    private static float[] nanArray(int n) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, Float.NaN);
        return a;
    }

    @Override
    public void close() throws IOException {
        try {
            server.close();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
