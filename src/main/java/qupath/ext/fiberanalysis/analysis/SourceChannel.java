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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.regions.RegionRequest;

/**
 * Resolves the "Source channel" choice and writes the region PNG the Python
 * segmenter reads. Every caller that hands a region to Python goes through
 * here, so the preview, the run, the calibration and the density tiles all
 * see identical pixels.
 *
 * <p>Naming a channel matters for more than convenience: a multi-band 16-bit
 * PNG is truncated to 8 bits when Python loads it, because PIL has no 16-bit
 * colour mode. Extracting the one band the user asked for lets the region go
 * out as single-band 16-bit, which survives intact -- so a manual threshold
 * stays a real gray level on 16-bit sources.
 */
final class SourceChannel {

    private static final Logger logger = LoggerFactory.getLogger(SourceChannel.class);

    /** Choices that are computed from RGB by the Python side rather than being a band. */
    static final String RAW = "Raw intensity";

    static final String HUE = "Hue (HSV)";
    static final String SATURATION = "Saturation (HSV)";
    static final String VALUE = "Value (HSV)";

    private static final List<String> DERIVED = List.of(RAW, HUE, SATURATION, VALUE);

    private SourceChannel() {}

    /**
     * @param server image to list channels for; may be null
     * @return the four derived choices followed by the image's own channel
     *     names, so a multi-channel image can be reduced to one band
     */
    static List<String> choices(ImageServer<BufferedImage> server) {
        List<String> out = new ArrayList<>(DERIVED);
        if (server == null) return out;
        try {
            for (int i = 0; i < server.nChannels(); i++) {
                String name = server.getChannel(i).getName();
                if (name != null && !name.isBlank() && !out.contains(name)) out.add(name);
            }
        } catch (Exception ex) {
            logger.debug("Could not list channels: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * @param server image the choice applies to; may be null
     * @param choice a value from {@link #choices}
     * @return the band index to extract, or -1 when the choice is derived from
     *     RGB and the whole region must be sent
     */
    static int bandIndex(ImageServer<BufferedImage> server, String choice) {
        if (server == null || choice == null || DERIVED.contains(choice)) return -1;
        try {
            for (int i = 0; i < server.nChannels(); i++) {
                if (choice.equals(server.getChannel(i).getName())) return i;
            }
        } catch (Exception ex) {
            logger.debug("Could not resolve channel {}: {}", choice, ex.getMessage());
        }
        return -1;
    }

    /**
     * Largest value the segmenter can actually see, which bounds the manual
     * threshold. Single-band 16-bit survives the PNG round-trip; anything
     * multi-band is delivered as 8-bit.
     *
     * @param server image to measure; may be null
     * @param choice the selected source channel
     * @return 65535 when a 16-bit single band will be delivered, else 255
     */
    static int deliveredFullScale(ImageServer<BufferedImage> server, String choice) {
        if (server == null) return 255;
        try {
            if (server.getPixelType() != PixelType.UINT16 || server.isRGB()) return 255;
            boolean singleBand = server.nChannels() == 1 || bandIndex(server, choice) >= 0;
            return singleBand ? 65535 : 255;
        } catch (Exception ex) {
            logger.debug("Could not read pixel type: {}", ex.getMessage());
            return 255;
        }
    }

    /**
     * Reads a region and writes it where the Python segmenter expects it.
     *
     * @param server  image to read from
     * @param request region to read at downsample 1
     * @param choice  the selected source channel; a named channel is extracted
     *                as a single band at the source bit depth
     * @param outPng  file to write
     * @throws IOException if the region cannot be read or no PNG writer accepts it
     */
    static void writeRegionPng(ImageServer<BufferedImage> server, RegionRequest request, String choice, Path outPng)
            throws IOException {
        BufferedImage region = server.readRegion(request);
        if (region == null) {
            throw new IOException("server.readRegion returned null for " + request + " (image dimensions "
                    + server.getWidth() + "x" + server.getHeight() + ")");
        }

        int band = bandIndex(server, choice);
        // A single-channel image has exactly one band whatever the choice says,
        // so send it as one and keep its bit depth.
        if (band < 0 && server.nChannels() == 1) band = 0;

        BufferedImage toWrite = region;
        if (band >= 0) {
            BufferedImage single = extractBand(region, band, server.getPixelType());
            if (single != null) {
                toWrite = single;
            } else {
                logger.warn(
                        "Cannot extract band {} of pixel type {} as a single-band PNG; sending the whole region,"
                                + " which Python will load as 8-bit",
                        band,
                        server.getPixelType());
            }
        }

        // ImageIO.write returns false (no exception, no file written) when the
        // BufferedImage type has no matching PNG writer -- TYPE_CUSTOM rasters
        // from Bio-Formats for unusual pixel types are the usual culprit. Fall
        // back to a standard ARGB image so the Python side gets a real file.
        boolean wrote = ImageIO.write(toWrite, "PNG", outPng.toFile());
        if (!wrote) {
            logger.warn(
                    "ImageIO.write returned false for {} (type={}, {}x{}); converting via TYPE_INT_ARGB and retrying",
                    outPng.getFileName(),
                    toWrite.getType(),
                    toWrite.getWidth(),
                    toWrite.getHeight());
            BufferedImage converted =
                    new BufferedImage(toWrite.getWidth(), toWrite.getHeight(), BufferedImage.TYPE_INT_ARGB);
            converted.getGraphics().drawImage(toWrite, 0, 0, null);
            wrote = ImageIO.write(converted, "PNG", outPng.toFile());
            if (!wrote) {
                throw new IOException("PNG writer rejected region image at " + outPng + " (original type="
                        + toWrite.getType() + ", fallback TYPE_INT_ARGB also rejected). Region was "
                        + toWrite.getWidth() + "x" + toWrite.getHeight());
            }
        }
        if (!Files.exists(outPng) || Files.size(outPng) == 0) {
            throw new IOException("Region PNG missing or empty after write at " + outPng);
        }
    }

    /**
     * @return a grayscale copy of one band at the source bit depth, or null when
     *     the pixel type has no lossless single-band PNG representation
     */
    private static BufferedImage extractBand(BufferedImage region, int band, PixelType pixelType) {
        Raster raster = region.getRaster();
        if (band >= raster.getNumBands()) return null;
        int type;
        if (pixelType == PixelType.UINT8) {
            type = BufferedImage.TYPE_BYTE_GRAY;
        } else if (pixelType == PixelType.UINT16) {
            type = BufferedImage.TYPE_USHORT_GRAY;
        } else {
            return null;
        }
        int w = region.getWidth();
        int h = region.getHeight();
        BufferedImage out = new BufferedImage(w, h, type);
        int[] samples = raster.getSamples(0, 0, w, h, band, (int[]) null);
        out.getRaster().setSamples(0, 0, w, h, 0, samples);
        return out;
    }
}
