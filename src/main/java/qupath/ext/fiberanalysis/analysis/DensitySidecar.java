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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * {@code <project>/fiber-analysis/density-maps/<sanitized-name>_density.ome.tif},
 * one pixel per grid cell. Its description says where the grid sits on the
 * source image and lists the channels ({@link SidecarDescription}).
 */
public final class DensitySidecar {

    private static final Logger logger = LoggerFactory.getLogger(DensitySidecar.class);

    /** A channel's name and unit as the map file lists them. */
    public static final class ChannelInfo {
        public final int index;
        public final String name;
        public final String unit; // may be null

        public ChannelInfo(int index, String name, String unit) {
            this.index = index;
            this.name = name;
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

    /** Project-relative location of the classifier-map sidecar folder. */
    public static Path classifierMapsRoot(Project<?> project) {
        if (project == null || project.getPath() == null) return null;
        Path projDir = project.getPath().getParent();
        if (projDir == null) return null;
        return projDir.resolve("fiber-analysis").resolve("classifier-maps");
    }

    /**
     * Sidecar path for a window-classifier probability map.
     *
     * <p>A separate folder from the density maps, and a filename carrying the
     * classifier id: two classifiers applied to one image are two maps, and a
     * density run on the same image must not be able to overwrite either.
     *
     * @param project      the open project; {@code null} path yields {@code null}
     * @param entry        the image the map was computed for
     * @param classifierId the model id the map came from
     * @return the sidecar path, or {@code null} if the project has no path
     */
    public static Path classifierSidecarPathFor(Project<?> project, ProjectImageEntry<?> entry, String classifierId) {
        Path root = classifierMapsRoot(project);
        if (root == null || entry == null) return null;
        String id = sanitize(classifierId == null || classifierId.isBlank() ? "model" : classifierId);
        return root.resolve(sanitize(entry.getImageName()) + "_" + id + ".ome.tif");
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
     * @param sidecar a map file
     * @return its channel names and units
     * @throws IOException if it cannot be read or predates grid-resolution maps
     */
    public static List<ChannelInfo> readChannelInfos(Path sidecar) throws IOException {
        return readInfo(sidecar).channels();
    }

    /**
     * @param sidecar a density or classifier map file
     * @return where its grid sits on the source image, how to draw it, and its channels
     * @throws IOException if it cannot be read or predates grid-resolution maps
     */
    public static DensityMapInfo readInfo(Path sidecar) throws IOException {
        if (sidecar == null || !Files.exists(sidecar)) {
            throw new IOException("Map file not found: " + sidecar);
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
            throw new IOException("Bio-Formats could not open map file: " + sidecar, ex);
        }
        String desc;
        try {
            desc = meta.getImageDescription(0);
        } catch (Exception ex) {
            throw new IOException("Map file has no image description: " + sidecar, ex);
        }
        if (desc == null || desc.isBlank()) {
            throw new IOException("Map file image description is empty: " + sidecar);
        }
        try {
            return SidecarDescription.parseInfo(desc);
        } catch (IllegalArgumentException e) {
            throw new IOException(sidecar.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
