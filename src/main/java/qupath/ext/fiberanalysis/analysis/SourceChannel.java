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
 * <p>Only RGB images go out with several bands. PIL keeps just the high byte
 * of a multi-band 16-bit PNG, so every other image goes out as one band at its
 * own bit depth: the band the user named, or the brightest band at each pixel
 * for Raw intensity and Value. A manual threshold therefore stays a real gray
 * level on 16-bit sources.
 */
public final class SourceChannel {

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
     * @return the derived choices followed by the image's own channel names;
     *     no Hue or Saturation on a multi-channel image that is not RGB
     */
    static List<String> choices(ImageServer<BufferedImage> server) {
        List<String> out = new ArrayList<>(DERIVED);
        if (server == null) return out;
        try {
            if (!server.isRGB() && server.nChannels() > 1) out.removeAll(List.of(HUE, SATURATION));
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
     * threshold.
     *
     * @param server image to measure; may be null
     * @param choice the selected source channel
     * @return 65535 for a 16-bit image, else 255
     */
    static int deliveredFullScale(ImageServer<BufferedImage> server, String choice) {
        if (server == null) return 255;
        try {
            return server.getPixelType() == PixelType.UINT16 ? 65535 : 255;
        } catch (Exception ex) {
            logger.debug("Could not read pixel type: {}", ex.getMessage());
            return 255;
        }
    }

    /**
     * Size of a written region, in read-scale pixels.
     *
     * @param width  pixels actually written
     * @param height pixels actually written
     */
    public record WrittenSize(int width, int height) {}

    /**
     * Reads a region and writes it where the Python segmenter expects it.
     *
     * <p>Returns the size actually written rather than leaving the caller to
     * recompute it. Servers do not agree on how a downsampled read rounds --
     * {@code AbstractTileableImageServer} uses {@code Math.round} while
     * {@code AffineTransformImageServer} truncates -- so any second derivation
     * can disagree with the file by a pixel, and the boundary mask then fails
     * to line up with the region.
     *
     * @param server  image to read from
     * @param request region to read at downsample 1
     * @param choice  the selected source channel; a named channel, or the
     *                brightest band for Raw intensity and Value, goes out as one
     *                band at the source bit depth
     * @param outPng  file to write
     * @return the written width and height
     * @throws IOException if the region cannot be read or no PNG writer accepts it
     */
    public static WrittenSize writeRegionPng(
            ImageServer<BufferedImage> server, RegionRequest request, String choice, Path outPng) throws IOException {
        BufferedImage region = server.readRegion(request);
        if (region == null) {
            throw new IOException("server.readRegion returned null for " + request + " (image dimensions "
                    + server.getWidth() + "x" + server.getHeight() + ")");
        }

        BufferedImage toWrite = imageToSend(server, region, choice);

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
        return new WrittenSize(toWrite.getWidth(), toWrite.getHeight());
    }

    /** @return the whole region for a derived choice on RGB, else one band at the source bit depth */
    private static BufferedImage imageToSend(ImageServer<BufferedImage> server, BufferedImage region, String choice)
            throws IOException {
        int band = bandIndex(server, choice);
        // A single-channel image has exactly one band whatever the choice says.
        if (band < 0 && server.nChannels() == 1) band = 0;
        if (band < 0 && server.isRGB()) return region;
        PixelType type = server.getPixelType();
        if (type != PixelType.UINT8 && type != PixelType.UINT16) {
            throw new IOException("Fiber analysis reads 8- and 16-bit images; "
                    + server.getMetadata().getName()
                    + " is " + type + ". Convert it to 16-bit, or analyze the image it was made from.");
        }
        if (band >= 0) return extractBand(region, band, type);
        if (HUE.equals(choice) || SATURATION.equals(choice)) {
            throw new IOException(
                    choice + " needs an RGB image, and " + server.getMetadata().getName()
                            + " has separate channels. Choose Raw intensity or one of its channels as the source channel.");
        }
        return brightestBand(region, type);
    }

    /**
     * @param region    multi-band image
     * @param pixelType its pixel type, UINT8 or UINT16
     * @return a grayscale image of the largest band value at each pixel
     */
    static BufferedImage brightestBand(BufferedImage region, PixelType pixelType) {
        Raster raster = region.getRaster();
        int w = region.getWidth();
        int h = region.getHeight();
        int[] max = new int[w * h];
        for (int b = 0; b < raster.getNumBands(); b++) {
            int[] samples = raster.getSamples(0, 0, w, h, b, (int[]) null);
            for (int i = 0; i < max.length; i++) {
                if (samples[i] > max[i]) max[i] = samples[i];
            }
        }
        return grayImage(w, h, max, pixelType);
    }

    /** @return a grayscale copy of one band at the source bit depth */
    private static BufferedImage extractBand(BufferedImage region, int band, PixelType pixelType) {
        int w = region.getWidth();
        int h = region.getHeight();
        return grayImage(w, h, region.getRaster().getSamples(0, 0, w, h, band, (int[]) null), pixelType);
    }

    private static BufferedImage grayImage(int w, int h, int[] samples, PixelType pixelType) {
        int type = pixelType == PixelType.UINT16 ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY;
        BufferedImage out = new BufferedImage(w, h, type);
        out.getRaster().setSamples(0, 0, w, h, 0, samples);
        return out;
    }
}
