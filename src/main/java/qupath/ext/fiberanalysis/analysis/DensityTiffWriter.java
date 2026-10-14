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
 * Writes the in-memory density grid to a tiled, pyramidal OME-TIFF sidecar
 * via Bio-Formats directly. Pixel type is {@code FLOAT32} so the values on
 * disk ARE the physical values -- a "Fiber coverage (%)" channel reads as
 * 0..100, "Order parameter" as 0..1, etc. No quantization step, no
 * scale/offset reapplication. {@code NaN} is the no-data sentinel.
 *
 * <p>Output layout:
 *
 * <ul>
 *   <li>float32 multi-channel, planar (one IFD per channel)</li>
 *   <li>Tile size 256x256</li>
 *   <li>Pyramid: native + halving levels while max-dim {@code >= 256}</li>
 *   <li>LZW compression (universally readable)</li>
 *   <li>Per-channel name = QuPath measurement-column name so the user sees
 *       the same string in the channel list and the measurement table</li>
 * </ul>
 *
 * <p>History: this used to be uint16 with a per-channel {@code scale +
 * offset} encoded in the OME-XML description. That made the on-disk file
 * compact but turned the channels into uninterpretable 0..65535 pictures
 * in QuPath's display path -- only our sampling command knew to reapply
 * the affine transform. Switching to float32 doubles the on-disk size but
 * makes the channels read as data everywhere downstream.
 */
public final class DensityTiffWriter {

    private static final Logger logger = LoggerFactory.getLogger(DensityTiffWriter.class);

    private static final int TILE_SIZE = 256;
    private static final String COMPRESSION_LZW = "LZW";

    private DensityTiffWriter() {}

    /**
     * Write a density-map sidecar.
     *
     * <p>The compact grid is drawn into a sidecar with the SAME pixel
     * dimensions as the source image, each value placed over the block
     * centred on the window that produced it ({@link DensityLattice}).
     * Matching source dimensions lets {@code TransformedServerBuilder.concatChannels}
     * attach directly without scale plumbing.
     *
     * @param outputPath  absolute path ending in {@code .ome.tif}
     * @param srcW        sidecar pixel width = source image width
     * @param srcH        sidecar pixel height = source image height
     * @param srcPxUm     source pixel size in microns
     * @param lattice     where the grid sits on the source
     * @param channels    channel descriptors in canonical order; must match
     *                    {@code grids.length}
     * @param grids       per-channel float buffers, {@code gridW * gridH}.
     *                    {@code NaN} = no data; everything else is the physical
     *                    value.
     * @param smoothInterpolation bilinear between window centres instead of
     *                    one flat block per window
     * @throws IOException if the write fails
     */
    public static void write(
            String outputPath,
            int srcW,
            int srcH,
            double srcPxUm,
            DensityLattice lattice,
            List<DensityChannelSpec> channels,
            float[][] grids,
            boolean smoothInterpolation)
            throws IOException {

        if (channels.size() != grids.length) {
            throw new IllegalArgumentException(
                    "channels/grids size mismatch: " + channels.size() + " / " + grids.length);
        }
        int cells = lattice.gridW() * lattice.gridH();
        for (float[] g : grids) {
            if (g.length != cells) {
                throw new IllegalArgumentException("grid length " + g.length + " != gridW*gridH " + cells);
            }
        }

        int nChannels = channels.size();
        double[] downsamples = computePyramidDownsamples(srcW, srcH);
        int numLevels = downsamples.length;

        long estimatedBytes = estimatePixelBytes(srcW, srcH, nChannels, downsamples);
        boolean bigTiff = estimatedBytes >= (Integer.MAX_VALUE - 1024L * 1024L * 100L);

        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        initializeMetadata(meta, srcW, srcH, nChannels, srcPxUm, channels, downsamples);

        logger.info(
                "Density OME-TIFF (float32): {}x{} (from {}, smooth={}), {} channels, {} levels, tile={}, compression={}, bigTiff={}",
                srcW,
                srcH,
                lattice,
                smoothInterpolation,
                nChannels,
                numLevels,
                TILE_SIZE,
                COMPRESSION_LZW,
                bigTiff);

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
            logger.warn("Could not delete existing sidecar at {}: {}", outputPath, delEx.getMessage());
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
                double d = downsamples[level];
                int levelW = Math.max(1, (int) (srcW / d));
                int levelH = Math.max(1, (int) (srcH / d));
                try {
                    tiffWriter.setSeries(0);
                    tiffWriter.setResolution(level);
                } catch (FormatException e) {
                    throw new IOException("Failed to set resolution level " + level, e);
                }

                for (int c = 0; c < nChannels; c++) {
                    IFD ifd = new IFD();
                    ifd.put(IFD.TILE_WIDTH, TILE_SIZE);
                    ifd.put(IFD.TILE_LENGTH, TILE_SIZE);

                    for (int yy = 0; yy < levelH; yy += TILE_SIZE) {
                        int hh = Math.min(TILE_SIZE, levelH - yy);
                        for (int xx = 0; xx < levelW; xx += TILE_SIZE) {
                            int ww = Math.min(TILE_SIZE, levelW - xx);
                            byte[] buf = packTile(lattice, grids[c], smoothInterpolation, xx, yy, ww, hh, d);
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
                logger.debug("Wrote density level {}/{} ({}x{})", level + 1, numLevels, levelW, levelH);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Density OME-TIFF write failed for " + outputPath, e);
        }
    }

    // ---- internals ----

    /**
     * Pack one output tile into big-endian float32 bytes. Each output pixel
     * is sampled at its centre in source coordinates, so every pyramid level
     * is drawn from the same geometry.
     */
    static byte[] packTile(
            DensityLattice lattice, float[] grid, boolean smooth, int xx, int yy, int ww, int hh, double downsample) {
        byte[] buf = new byte[ww * hh * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        for (int ty = 0; ty < hh; ty++) {
            double v = (yy + ty + 0.5) * downsample;
            for (int tx = 0; tx < ww; tx++) {
                double u = (xx + tx + 0.5) * downsample;
                bb.putFloat(smooth ? lattice.bilinear(grid, u, v) : lattice.nearest(grid, u, v));
            }
        }
        return buf;
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
                "Fiber density map",
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

        // Image-level description: float32 + NaN no-data. No quantization
        // block -- the on-disk values are already the physical values, so
        // there is nothing to round-trip.
        StringBuilder desc = new StringBuilder();
        desc.append("FiberAnalysis density-map sidecar. Pixel type: float32; NaN = no data.\n");
        desc.append("Channel values are physical (e.g. Fiber coverage (%) reads 0..100).\n");
        desc.append("Channels:\n");
        for (int c = 0; c < nChannels; c++) {
            DensityChannelSpec spec = channels.get(c);
            meta.setChannelID("Channel:0:" + c, series, c);
            meta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
            meta.setChannelName(spec.channelName, series, c);
            desc.append(String.format(
                    java.util.Locale.ROOT,
                    "  [%d] name=%s%s%n",
                    c,
                    spec.channelName,
                    spec.unit != null ? "; unit=" + spec.unit : ""));
        }
        meta.setImageDescription(desc.toString(), series);

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
