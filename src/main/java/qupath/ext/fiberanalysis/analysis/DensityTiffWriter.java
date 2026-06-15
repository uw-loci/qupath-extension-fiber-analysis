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
     * <p>The compact density grid ({@code gridW x gridH}) is upsampled on
     * the fly into a sidecar with the SAME pixel dimensions as the source
     * image ({@code srcW x srcH}) via nearest-neighbour replication: each
     * grid cell {@code (gx, gy)} covers source pixels
     * {@code [gx*stridePx, (gx+1)*stridePx) x [gy*stridePx, (gy+1)*stridePx)}.
     * Matching source dimensions lets {@code TransformedServerBuilder.concatChannels}
     * attach directly without scale plumbing.
     *
     * @param outputPath  absolute path ending in {@code .ome.tif}
     * @param srcW        sidecar pixel width = source image width
     * @param srcH        sidecar pixel height = source image height
     * @param srcPxUm     source pixel size in microns
     * @param gridW       compact density-grid width (windows across)
     * @param gridH       compact density-grid height (windows down)
     * @param stridePx    source-pixel stride between density-grid cells
     *                    (= {@code window_px - overlap_px}); nearest-neighbour
     *                    upsample factor
     * @param channels    channel descriptors in canonical order; must match
     *                    {@code grids.length}
     * @param grids       per-channel float buffers, length {@code gridW * gridH}.
     *                    {@code NaN} = no data; everything else is the physical
     *                    value.
     */
    public static void write(
            String outputPath,
            int srcW,
            int srcH,
            double srcPxUm,
            int gridW,
            int gridH,
            int stridePx,
            List<DensityChannelSpec> channels,
            float[][] grids,
            boolean smoothInterpolation)
            throws IOException {

        if (channels.size() != grids.length) {
            throw new IllegalArgumentException(
                    "channels/grids size mismatch: " + channels.size() + " / " + grids.length);
        }
        if (stridePx <= 0) {
            throw new IllegalArgumentException("stridePx must be positive, got " + stridePx);
        }
        for (float[] g : grids) {
            if (g.length != gridW * gridH) {
                throw new IllegalArgumentException("grid length " + g.length + " != gridW*gridH " + (gridW * gridH));
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
                "Density OME-TIFF (float32): {}x{} (from {}x{} grid, stride={}, smooth={}), {} channels, {} levels, tile={}, compression={}, bigTiff={}",
                srcW,
                srcH,
                gridW,
                gridH,
                stridePx,
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
                            byte[] buf = smoothInterpolation
                                    ? packTileBilinear(grids[c], gridW, gridH, stridePx, xx, yy, ww, hh, d)
                                    : packTile(grids[c], gridW, gridH, stridePx, xx, yy, ww, hh, d);
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
     * Pack a tile of source-coord pixels into big-endian float32 bytes,
     * resolving each output pixel back to its compact-grid cell. {@code NaN}
     * grid cells pass straight through to the output (no-data sentinel).
     */
    private static byte[] packTile(
            float[] grid, int gridW, int gridH, int stridePx, int xx, int yy, int ww, int hh, double downsample) {
        byte[] buf = new byte[ww * hh * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        for (int ty = 0; ty < hh; ty++) {
            int sy = (int) Math.round((yy + ty) * downsample);
            int gy = sy / stridePx;
            if (gy < 0) gy = 0;
            if (gy >= gridH) gy = gridH - 1;
            int rowBase = gy * gridW;
            for (int tx = 0; tx < ww; tx++) {
                int sx = (int) Math.round((xx + tx) * downsample);
                int gx = sx / stridePx;
                if (gx < 0) gx = 0;
                if (gx >= gridW) gx = gridW - 1;
                bb.putFloat(grid[rowBase + gx]);
            }
        }
        return buf;
    }

    /**
     * Bilinear-with-NaN-skip variant of {@link #packTile}. Each grid cell
     * is treated as a sample at its centre; each output pixel is interpolated
     * from the four surrounding centres, skipping NaN corners and
     * renormalising weights. If all four corners are NaN, the output is NaN.
     */
    private static byte[] packTileBilinear(
            float[] grid, int gridW, int gridH, int stridePx, int xx, int yy, int ww, int hh, double downsample) {
        byte[] buf = new byte[ww * hh * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        double halfStride = 0.5;
        for (int ty = 0; ty < hh; ty++) {
            int sy = (int) Math.round((yy + ty) * downsample);
            double fy = (double) sy / stridePx - halfStride;
            int gy0 = (int) Math.floor(fy);
            int gy1 = gy0 + 1;
            double wy1 = fy - gy0;
            double wy0 = 1.0 - wy1;
            if (gy0 < 0) {
                gy0 = 0;
                wy0 = 1.0;
                wy1 = 0.0;
                gy1 = 0;
            } else if (gy1 >= gridH) {
                gy1 = gridH - 1;
                wy1 = 0.0;
                wy0 = 1.0;
                gy0 = gy1;
            }
            int rowBase0 = gy0 * gridW;
            int rowBase1 = gy1 * gridW;
            for (int tx = 0; tx < ww; tx++) {
                int sx = (int) Math.round((xx + tx) * downsample);
                double fx = (double) sx / stridePx - halfStride;
                int gx0 = (int) Math.floor(fx);
                int gx1 = gx0 + 1;
                double wx1 = fx - gx0;
                double wx0 = 1.0 - wx1;
                if (gx0 < 0) {
                    gx0 = 0;
                    wx0 = 1.0;
                    wx1 = 0.0;
                    gx1 = 0;
                } else if (gx1 >= gridW) {
                    gx1 = gridW - 1;
                    wx1 = 0.0;
                    wx0 = 1.0;
                    gx0 = gx1;
                }
                float v00 = grid[rowBase0 + gx0];
                float v01 = grid[rowBase0 + gx1];
                float v10 = grid[rowBase1 + gx0];
                float v11 = grid[rowBase1 + gx1];
                double w00 = wx0 * wy0;
                double w01 = wx1 * wy0;
                double w10 = wx0 * wy1;
                double w11 = wx1 * wy1;
                double sum = 0.0;
                double wsum = 0.0;
                if (!Float.isNaN(v00)) {
                    sum += v00 * w00;
                    wsum += w00;
                }
                if (!Float.isNaN(v01)) {
                    sum += v01 * w01;
                    wsum += w01;
                }
                if (!Float.isNaN(v10)) {
                    sum += v10 * w10;
                    wsum += w10;
                }
                if (!Float.isNaN(v11)) {
                    sum += v11 * w11;
                    wsum += w11;
                }
                float outv = wsum <= 0.0 ? Float.NaN : (float) (sum / wsum);
                bb.putFloat(outv);
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
