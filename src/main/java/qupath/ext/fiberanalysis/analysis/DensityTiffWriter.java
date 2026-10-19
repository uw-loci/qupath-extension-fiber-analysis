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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.TiffWriter;
import loci.formats.tiff.IFD;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a density or classifier grid to a tiled OME-TIFF via Bio-Formats:
 * one float32 pixel per grid cell, one plane per channel, NaN for no data,
 * values in physical units. The description ({@link SidecarDescription}) says
 * where the grid sits on the source image, so readers draw it over the image
 * at any zoom without a full-resolution copy on disk.
 *
 * <p>Large grids get halving pyramid levels, each the NaN-aware mean of the
 * cells below it. LZW compression; channel names are the measurement names.
 */
public final class DensityTiffWriter {

    private static final Logger logger = LoggerFactory.getLogger(DensityTiffWriter.class);

    private static final int TILE_SIZE = 256;
    private static final String COMPRESSION_LZW = "LZW";

    private DensityTiffWriter() {}

    /**
     * Write a map file.
     *
     * @param outputPath absolute path ending in {@code .ome.tif}
     * @param info       placement on the source image, render mode and source image name
     *                   (its channel list is ignored)
     * @param channels   channel descriptors in file order; must match {@code grids.length}
     * @param grids      per-channel values, {@code gridW * gridH}, NaN = no data
     * @throws IOException if the write fails
     */
    public static void write(String outputPath, DensityMapInfo info, List<DensityChannelSpec> channels, float[][] grids)
            throws IOException {

        if (channels.size() != grids.length) {
            throw new IllegalArgumentException(
                    "channels/grids size mismatch: " + channels.size() + " / " + grids.length);
        }
        DensityLattice lattice = info.lattice();
        int gridW = lattice.gridW();
        int gridH = lattice.gridH();
        for (float[] g : grids) {
            if (g.length != gridW * gridH) {
                throw new IllegalArgumentException("grid length " + g.length + " != gridW*gridH " + gridW * gridH);
            }
        }

        int nChannels = channels.size();
        double[] downsamples = computePyramidDownsamples(gridW, gridH);
        int numLevels = downsamples.length;
        long estimatedBytes = estimatePixelBytes(gridW, gridH, nChannels, downsamples);
        boolean bigTiff = estimatedBytes >= (Integer.MAX_VALUE - 1024L * 1024L * 100L);

        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        initializeMetadata(
                meta,
                gridW,
                gridH,
                nChannels,
                info.sourcePixelUm() * lattice.cellPx(),
                SidecarDescription.format(info, channels),
                channels,
                downsamples);

        logger.info(
                "Map OME-TIFF (float32): {}x{} cells of {} px on a {}x{} image (smooth={}), {} channels, {} levels",
                gridW,
                gridH,
                lattice.cellPx(),
                info.sourceWidth(),
                info.sourceHeight(),
                info.smooth(),
                nChannels,
                numLevels);

        // Delete any pre-existing file at the target path before opening the
        // writer. Bio-Formats' OME-TIFF writers, when handed an existing
        // file, parse its OME-XML and use the stored channel array to seed
        // their internal teardown state -- but PyramidOMETiffWriter.close()
        // then iterates one element past the end of that array, throwing
        // "ArrayIndexOutOfBoundsException: Index N out of bounds for length N"
        // after the actual write has finished. Writing into a fresh file
        // avoids the seeded state entirely.
        try {
            Files.deleteIfExists(java.nio.file.Path.of(outputPath));
        } catch (IOException delEx) {
            logger.warn("Could not delete existing map at {}: {}", outputPath, delEx.getMessage());
        }

        try (ImageWriter imageWriter = new ImageWriter()) {
            imageWriter.setWriteSequentially(true);
            imageWriter.setMetadataRetrieve(meta);

            ((TiffWriter) imageWriter.getWriter(outputPath)).setBigTiff(bigTiff);
            imageWriter.setCompression(COMPRESSION_LZW);
            try {
                imageWriter.setId(outputPath);
            } catch (FormatException e) {
                throw new IOException("Failed to open OME-TIFF writer for " + outputPath, e);
            }
            TiffWriter tiffWriter = (TiffWriter) imageWriter.getWriter();
            tiffWriter.setInterleaved(false);

            for (int level = 0; level < numLevels; level++) {
                int d = (int) downsamples[level];
                int levelW = Math.max(1, gridW / d);
                int levelH = Math.max(1, gridH / d);
                try {
                    tiffWriter.setSeries(0);
                    tiffWriter.setResolution(level);
                } catch (FormatException e) {
                    throw new IOException("Failed to set resolution level " + level, e);
                }
                for (int c = 0; c < nChannels; c++) {
                    float[] plane = d == 1 ? grids[c] : blockMean(grids[c], gridW, gridH, d, levelW, levelH);
                    IFD ifd = new IFD();
                    ifd.put(IFD.TILE_WIDTH, TILE_SIZE);
                    ifd.put(IFD.TILE_LENGTH, TILE_SIZE);
                    for (int yy = 0; yy < levelH; yy += TILE_SIZE) {
                        int hh = Math.min(TILE_SIZE, levelH - yy);
                        for (int xx = 0; xx < levelW; xx += TILE_SIZE) {
                            int ww = Math.min(TILE_SIZE, levelW - xx);
                            byte[] buf = packTile(plane, levelW, xx, yy, ww, hh);
                            try {
                                tiffWriter.saveBytes(c, buf, ifd, xx, yy, ww, hh);
                            } catch (FormatException e) {
                                throw new IOException(
                                        "saveBytes failed at level=" + level + " ch=" + c + " (x=" + xx + ",y=" + yy
                                                + ")",
                                        e);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Map OME-TIFF write failed for " + outputPath, e);
        }
    }

    // ---- internals ----

    /** Big-endian float32 bytes of one tile of a row-major plane. */
    static byte[] packTile(float[] plane, int planeW, int xx, int yy, int ww, int hh) {
        byte[] buf = new byte[ww * hh * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        for (int ty = 0; ty < hh; ty++) {
            int row = (yy + ty) * planeW + xx;
            for (int tx = 0; tx < ww; tx++) bb.putFloat(plane[row + tx]);
        }
        return buf;
    }

    /** Mean of the finite values in each {@code d x d} block; NaN where a block has none. */
    static float[] blockMean(float[] grid, int gridW, int gridH, int d, int outW, int outH) {
        float[] out = new float[outW * outH];
        for (int oy = 0; oy < outH; oy++) {
            for (int ox = 0; ox < outW; ox++) {
                double sum = 0;
                int n = 0;
                for (int y = oy * d; y < Math.min(gridH, (oy + 1) * d); y++) {
                    for (int x = ox * d; x < Math.min(gridW, (ox + 1) * d); x++) {
                        float v = grid[y * gridW + x];
                        if (Float.isFinite(v)) {
                            sum += v;
                            n++;
                        }
                    }
                }
                out[oy * outW + ox] = n == 0 ? Float.NaN : (float) (sum / n);
            }
        }
        return out;
    }

    private static double[] computePyramidDownsamples(int width, int height) {
        java.util.ArrayList<Double> levels = new java.util.ArrayList<>();
        levels.add(1.0);
        double d = 2.0;
        while (Math.max(width, height) / d >= 256.0) {
            levels.add(d);
            d *= 2.0;
        }
        double[] out = new double[levels.size()];
        for (int i = 0; i < out.length; i++) out[i] = levels.get(i);
        return out;
    }

    private static long estimatePixelBytes(int width, int height, int nChannels, double[] downsamples) {
        long bytes = 0;
        for (double d : downsamples) {
            long w = Math.max(1L, (long) (width / d));
            long h = Math.max(1L, (long) (height / d));
            bytes += w * h * nChannels * 4L; // float32 = 4 bytes/sample
        }
        return bytes;
    }

    private static void initializeMetadata(
            IMetadata meta,
            int width,
            int height,
            int nChannels,
            double pixelSizeUm,
            String description,
            List<DensityChannelSpec> channels,
            double[] downsamples) {
        int series = 0;

        // Populate baseline OME fields via Bio-Formats' helper FIRST -- without
        // this, internal channel-array slots may not be fully allocated when
        // PyramidOMETiffWriter.close() iterates them at end-of-write, producing
        // an "ArrayIndexOutOfBoundsException: Index N out of bounds for length N"
        // after the data write completes but during writer teardown.
        MetadataTools.populateMetadata(
                meta,
                series,
                "Fiber analysis map",
                false, // littleEndian -> big-endian; we override below for clarity
                DimensionOrder.XYCZT.getValue(),
                PixelType.FLOAT.getValue(),
                width,
                height,
                1, // sizeZ
                nChannels,
                1, // sizeT
                1); // samplesPerPixel (planar)

        meta.setPixelsBigEndian(Boolean.TRUE, series);
        meta.setPixelsInterleaved(Boolean.FALSE, series);

        for (int c = 0; c < nChannels; c++) {
            meta.setChannelID("Channel:0:" + c, series, c);
            meta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
            meta.setChannelName(channels.get(c).channelName, series, c);
        }
        meta.setImageDescription(description, series);

        if (pixelSizeUm > 0 && !Double.isNaN(pixelSizeUm)) {
            meta.setPixelsPhysicalSizeX(new Length(pixelSizeUm, UNITS.MICROMETER), series);
            meta.setPixelsPhysicalSizeY(new Length(pixelSizeUm, UNITS.MICROMETER), series);
        }

        for (int level = 0; level < downsamples.length; level++) {
            double d = downsamples[level];
            int w = Math.max(1, (int) (width / d));
            int h = Math.max(1, (int) (height / d));
            ((IPyramidStore) meta).setResolutionSizeX(new PositiveInteger(w), series, level);
            ((IPyramidStore) meta).setResolutionSizeY(new PositiveInteger(h), series, level);
        }
    }
}
