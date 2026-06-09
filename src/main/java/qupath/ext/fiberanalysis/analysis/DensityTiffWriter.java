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
 * Writes the in-memory uint16 density grid to a tiled, pyramidal OME-TIFF
 * sidecar via Bio-Formats directly. Mirrors the
 * {@code qupath.ext.basicstitching.assembly.direct.DirectTiffOutputWriter}
 * pattern -- we skip QuPath's {@code OMEPyramidWriter} because the density
 * grid is in memory as {@code short[][]} (one buffer per channel), so the
 * intermediate BufferedImage / ImageServer hop would be pure overhead.
 *
 * <p>Output layout:
 *
 * <ul>
 *   <li>uint16 multi-channel, planar (one IFD per channel)</li>
 *   <li>Tile size 256x256 (small enough to be cheap to read at any zoom)</li>
 *   <li>Pyramid: native + halving levels while max-dim {@code >= 256}</li>
 *   <li>LZW compression (universally readable; deflate is the more efficient
 *       alternative, but the gain on small sparse density grids is
 *       marginal and LZW's read compatibility is broader)</li>
 *   <li>Per-channel name = QuPath measurement-column name (matches the
 *       sampling-command's measurement names so the user's mental model
 *       is consistent across "look at the channel" and "sample into
 *       measurements")</li>
 *   <li>{@code scale} and {@code offset} are encoded in the per-channel
 *       description so a downstream consumer can recover the original
 *       float values via {@code real = raw * scale + offset} (raw value
 *       0 is the no-data sentinel)</li>
 * </ul>
 */
public final class DensityTiffWriter {

    private static final Logger logger = LoggerFactory.getLogger(DensityTiffWriter.class);

    private static final int TILE_SIZE = 256;
    private static final String COMPRESSION_LZW = "LZW";

    private DensityTiffWriter() {}

    /**
     * Per-channel quantization spec: linear map of valid float values into
     * {@code [1, 65535]}. {@code real = raw * scale + offset}; raw 0 is the
     * no-data sentinel.
     */
    public static final class ChannelQuant {
        public final double scale;
        public final double offset;

        public ChannelQuant(double scale, double offset) {
            this.scale = scale;
            this.offset = offset;
        }
    }

    /**
     * Write a density-map sidecar.
     *
     * <p>The compact density grid ({@code gridW x gridH}) is upsampled on
     * the fly into a sidecar with the SAME pixel dimensions as the source
     * image ({@code srcW x srcH}) via nearest-neighbour replication: each
     * grid cell {@code (gx, gy)} covers source pixels
     * {@code [gx*stridePx, (gx+1)*stridePx) x [gy*stridePx, (gy+1)*stridePx)}.
     * Why match source dims rather than save the compact grid:
     *
     * <ul>
     *   <li>When the user opens the sidecar standalone (File &gt; Open) it
     *       renders at the same visual scale as the source instead of a
     *       small thumbnail in the upper-left.</li>
     *   <li>{@code Attach density channels} can concat directly onto the
     *       source server -- {@code TransformedServerBuilder.concatChannels}
     *       requires matching pixel dimensions.</li>
     *   <li>{@code Sample fiber density} reads sidecar pixels in identity
     *       source-pixel coords; no scale conversion required.</li>
     * </ul>
     *
     * LZW compresses the replicated blocks very efficiently so the on-disk
     * cost over a 68 x 68 sidecar is modest (~10-20x rather than the
     * 900x naive uncompressed).
     *
     * @param outputPath  absolute path ending in {@code .ome.tif}
     * @param srcW        sidecar pixel width = source image width
     * @param srcH        sidecar pixel height = source image height
     * @param srcPxUm     source pixel size in microns (also the sidecar's
     *                    physical pixel size)
     * @param gridW       compact density-grid width (windows across)
     * @param gridH       compact density-grid height (windows down)
     * @param stridePx    source-pixel stride between density-grid cells
     *                    (= {@code window_px - overlap_px}); used as the
     *                    nearest-neighbour upsample factor
     * @param channels    channel descriptors in canonical order; must match
     *                    {@code grids.length}.
     * @param grids       per-channel uint16 buffers, length
     *                    {@code gridW * gridH}. Sentinel value 0 = no data.
     * @param quants      per-channel quantization spec; stored in OME-XML
     *                    so the original float units round-trip via
     *                    {@code real = raw * scale + offset}.
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
            short[][] grids,
            List<ChannelQuant> quants,
            boolean smoothInterpolation)
            throws IOException {

        if (channels.size() != grids.length || channels.size() != quants.size()) {
            throw new IllegalArgumentException("channels/grids/quants size mismatch: " + channels.size() + " / "
                    + grids.length + " / " + quants.size());
        }
        if (stridePx <= 0) {
            throw new IllegalArgumentException("stridePx must be positive, got " + stridePx);
        }
        for (short[] g : grids) {
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
        initializeMetadata(meta, srcW, srcH, nChannels, srcPxUm, channels, quants, downsamples);

        logger.info(
                "Density OME-TIFF: {}x{} (from {}x{} grid, stride={}, smooth={}), {} channels, {} levels, tile={}, compression={}, bigTiff={}",
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
            // Planar channels, NOT interleaved (each channel is its own IFD plane).
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
     * Pack a tile of source-coord pixels into big-endian uint16 bytes,
     * resolving each output pixel back to its compact-grid cell.
     *
     * <p>At pyramid level 0 ({@code downsample = 1}) the source-pixel
     * coordinate {@code sx, sy} maps to grid cell
     * {@code (sx / stridePx, sy / stridePx)} (clamped to grid bounds).
     * At deeper levels (downsample &gt; 1) the source coord is scaled up
     * first via the level's downsample, then the same grid mapping applies.
     */
    private static byte[] packTile(
            short[] grid, int gridW, int gridH, int stridePx, int xx, int yy, int ww, int hh, double downsample) {
        byte[] buf = new byte[ww * hh * 2];
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
                bb.putShort(grid[rowBase + gx]);
            }
        }
        return buf;
    }

    /**
     * Bilinear-with-sentinel-skip variant of {@link #packTile}. Each grid cell
     * is treated as a sample at its centre (source pixel
     * {@code gx*stridePx + stridePx/2}), and each output pixel is interpolated
     * from the four surrounding centres.
     *
     * <p>Sentinel-aware: raw value 0 means "no data" and must NOT be
     * interpolated against (otherwise the ring boundary would bleed
     * grey-toward-zero into the corners). We accumulate weights only over
     * valid (non-zero) corners and renormalise; if all four are zero we
     * emit zero (preserves the transparent / no-data shape).
     */
    private static byte[] packTileBilinear(
            short[] grid, int gridW, int gridH, int stridePx, int xx, int yy, int ww, int hh, double downsample) {
        byte[] buf = new byte[ww * hh * 2];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        // Cell centre offset: cell gx is the sample at source pixel
        // gx*stridePx + stridePx/2. Source pixel sx maps to fractional cell
        // coord fx = (sx - stridePx/2) / stridePx = sx/stridePx - 0.5.
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
                int v00 = grid[rowBase0 + gx0] & 0xFFFF;
                int v01 = grid[rowBase0 + gx1] & 0xFFFF;
                int v10 = grid[rowBase1 + gx0] & 0xFFFF;
                int v11 = grid[rowBase1 + gx1] & 0xFFFF;
                double w00 = wx0 * wy0;
                double w01 = wx1 * wy0;
                double w10 = wx0 * wy1;
                double w11 = wx1 * wy1;
                double sum = 0.0;
                double wsum = 0.0;
                if (v00 != 0) {
                    sum += v00 * w00;
                    wsum += w00;
                }
                if (v01 != 0) {
                    sum += v01 * w01;
                    wsum += w01;
                }
                if (v10 != 0) {
                    sum += v10 * w10;
                    wsum += w10;
                }
                if (v11 != 0) {
                    sum += v11 * w11;
                    wsum += w11;
                }
                int outv;
                if (wsum <= 0.0) {
                    outv = 0;
                } else {
                    int v = (int) Math.round(sum / wsum);
                    if (v < 1) v = 1; // never collapse a valid mix back to the no-data sentinel
                    if (v > 65535) v = 65535;
                    outv = v;
                }
                bb.putShort((short) outv);
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
            bytes += w * h * nChannels * 2L;
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
            List<ChannelQuant> quants,
            double[] downsamples) {
        int series = 0;

        meta.setImageID("Image:0", series);
        meta.setImageName("Fiber density map", series);
        meta.setPixelsID("Pixels:0", series);
        meta.setPixelsBigEndian(Boolean.TRUE, series);
        meta.setPixelsDimensionOrder(DimensionOrder.XYCZT, series);
        meta.setPixelsType(PixelType.UINT16, series);

        meta.setPixelsSizeX(new PositiveInteger(width), series);
        meta.setPixelsSizeY(new PositiveInteger(height), series);
        meta.setPixelsSizeZ(new PositiveInteger(1), series);
        meta.setPixelsSizeT(new PositiveInteger(1), series);
        meta.setPixelsSizeC(new PositiveInteger(nChannels), series);
        meta.setPixelsInterleaved(Boolean.FALSE, series);

        // Image-level description carries the per-channel quantization spec.
        // setChannelDescription is not exposed on IMetadata in our Bio-Formats
        // version, so we encode everything in a single greppable image
        // description block instead. Downstream readers parse out the
        // {channel_index, scale, offset, unit} per row.
        StringBuilder desc = new StringBuilder();
        desc.append("FiberAnalysis density-map sidecar. Pixel raw=0 is the no-data sentinel.\n");
        desc.append("Recover real values per channel via: real = raw * scale + offset.\n");
        desc.append("Channels:\n");
        for (int c = 0; c < nChannels; c++) {
            DensityChannelSpec spec = channels.get(c);
            ChannelQuant q = quants.get(c);
            meta.setChannelID("Channel:0:" + c, series, c);
            meta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
            meta.setChannelName(spec.channelName, series, c);
            desc.append(String.format(
                    java.util.Locale.ROOT,
                    "  [%d] name=%s; scale=%.10g; offset=%.10g%s%n",
                    c,
                    spec.channelName,
                    q.scale,
                    q.offset,
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
