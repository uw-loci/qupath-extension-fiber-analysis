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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import loci.common.services.ServiceFactory;
import loci.formats.ImageReader;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Helpers for locating + reading the density-map sidecar that
 * {@link FiberDensityMapWorkflow} writes per image.
 *
 * <p>The sidecar is an OME-TIFF at
 * {@code <project>/fiber-analysis/density-maps/<sanitized-name>_density.ome.tif}.
 * Per-channel quantization is encoded in the OME-XML image description as a
 * plain-text block (one row per channel, {@code [N] name=...; scale=...; offset=...; unit=...}).
 * Parsed back here for use by the sampling command.
 */
public final class DensitySidecar {

    private static final Logger logger = LoggerFactory.getLogger(DensitySidecar.class);

    /** Per-channel descriptor read back from the sidecar's OME-XML description. */
    public static final class ChannelInfo {
        public final int index;
        public final String name;
        public final double scale;
        public final double offset;
        public final String unit; // may be null

        public ChannelInfo(int index, String name, double scale, double offset, String unit) {
            this.index = index;
            this.name = name;
            this.scale = scale;
            this.offset = offset;
            this.unit = unit;
        }
    }

    private DensitySidecar() {}

    /**
     * Mirror of {@link FiberDensityMapWorkflow}'s sanitize -- the two MUST
     * agree, otherwise the sampling command cannot find the sidecar a run
     * produced. Kept here so future callers can locate sidecars without
     * reaching into the workflow class.
     */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) return "image";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        s = s.replaceAll("[. ]+$", "");
        if (s.isBlank()) return "image";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    /** Project-relative location of the density-map sidecar folder. */
    public static Path densityMapsRoot(Project<?> project) {
        if (project == null || project.getPath() == null) return null;
        Path projDir = project.getPath().getParent();
        if (projDir == null) return null;
        return projDir.resolve("fiber-analysis").resolve("density-maps");
    }

    /** Sidecar path for a project entry; {@code null} if project has no path. */
    public static Path sidecarPathFor(Project<?> project, ProjectImageEntry<?> entry) {
        Path root = densityMapsRoot(project);
        if (root == null || entry == null) return null;
        return root.resolve(sanitize(entry.getImageName()) + "_density.ome.tif");
    }

    /** True iff the sidecar exists on disk and is non-empty. */
    public static boolean exists(Path sidecar) {
        try {
            return sidecar != null && Files.isRegularFile(sidecar) && Files.size(sidecar) > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Parse the per-channel quantization block from the sidecar's OME-XML
     * image description. Matches rows like
     * {@code  [3] name=Skeleton length (um); scale=1.23e-5; offset=0.0; unit=um}.
     * Returns the list in channel-index order; missing or malformed rows are
     * skipped (logged at warn).
     */
    public static List<ChannelInfo> readChannelInfos(Path sidecar) throws IOException {
        if (sidecar == null || !Files.exists(sidecar)) {
            throw new IOException("Density sidecar not found: " + sidecar);
        }

        IMetadata meta;
        try {
            ServiceFactory factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            meta = service.createOMEXMLMetadata();
        } catch (Exception ex) {
            throw new IOException("Could not create OMEXML metadata service", ex);
        }

        try (ImageReader reader = new ImageReader()) {
            reader.setMetadataStore(meta);
            reader.setId(sidecar.toString());
        } catch (Exception ex) {
            throw new IOException("Bio-Formats could not open sidecar: " + sidecar, ex);
        }

        String desc;
        try {
            desc = meta.getImageDescription(0);
        } catch (Exception ex) {
            throw new IOException("Sidecar has no Image:0 description", ex);
        }
        if (desc == null || desc.isBlank()) {
            throw new IOException("Sidecar image description is empty -- cannot recover quantization");
        }
        return parseChannelInfos(desc);
    }

    /** Visible for tests: extract channel rows from the OME-XML description block. */
    static List<ChannelInfo> parseChannelInfos(String desc) {
        // Row shape:  [<idx>] name=<name>; scale=<scale>; offset=<offset>[; unit=<unit>]
        // Name may contain spaces and parentheses; scale/offset are
        // %.10g-formatted (positive or negative, with optional exponent).
        Pattern p = Pattern.compile(
                "\\[(\\d+)]\\s*name=(.+?);\\s*scale=([\\-+0-9.eE]+);\\s*offset=([\\-+0-9.eE]+)(?:;\\s*unit=([^\\r\\n;]+))?",
                Pattern.MULTILINE);
        Matcher m = p.matcher(desc);
        List<ChannelInfo> out = new ArrayList<>();
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                String name = m.group(2).trim();
                double scale = Double.parseDouble(m.group(3));
                double offset = Double.parseDouble(m.group(4));
                String unit = m.group(5);
                if (unit != null) unit = unit.trim();
                out.add(new ChannelInfo(idx, name, scale, offset, unit));
            } catch (NumberFormatException ex) {
                logger.warn("Skipping malformed sidecar channel row: {}", m.group(0));
            }
        }
        out.sort((a, b) -> Integer.compare(a.index, b.index));
        return out;
    }

    /**
     * Average the valid pixels of one channel across an entire BufferedImage
     * tile. {@code raw=0} is treated as no-data (sentinel) and skipped. Returns
     * NaN when every pixel in the tile is no-data for this channel.
     */
    public static double meanValidChannel(BufferedImage tile, int channelIndex) {
        if (tile == null) return Double.NaN;
        int w = tile.getWidth();
        int h = tile.getHeight();
        int[] samples = tile.getRaster().getSamples(0, 0, w, h, channelIndex, (int[]) null);
        long sum = 0L;
        long count = 0L;
        for (int v : samples) {
            if (v <= 0) continue; // sentinel 0 = no data
            sum += v;
            count++;
        }
        if (count == 0) return Double.NaN;
        return (double) sum / (double) count;
    }
}
