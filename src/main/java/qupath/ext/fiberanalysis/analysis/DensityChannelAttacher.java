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
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Adds a map's channels to an image as a NEW project entry: the source image,
 * converted to float32 so map values are not cut to integers, followed by the
 * map's channels served at the image's size ({@link DensityChannelsServer}).
 * The source entry is never changed.
 *
 * <p>The new entry carries the source's metadata plus three keys of its own
 * ({@link #META_KIND}, {@link #META_SOURCE}, {@link #META_MAP}) so it can be
 * sorted and found in the project image list and the metadata browser. Adding
 * the same map again refreshes that entry rather than making another.
 */
public final class DensityChannelAttacher {

    private static final Logger logger = LoggerFactory.getLogger(DensityChannelAttacher.class);

    /** Metadata key: what the fiber extension added to this entry. */
    public static final String META_KIND = "Fiber analysis";
    /** Metadata key: the project image the channels were added to. */
    public static final String META_SOURCE = "Fiber source image";
    /** Metadata key: the map file the channels come from. */
    public static final String META_MAP = "Fiber map";

    /** {@link #META_KIND} value for density-map channels. */
    public static final String KIND_DENSITY = "Density channels";
    /** {@link #META_KIND} value for window-classifier channels. */
    public static final String KIND_CLASSIFIER = "Classifier channels";

    private static final String TITLE = "Fiber density channels";

    private DensityChannelAttacher() {}

    /**
     * Menu entry point: add the open image's density map as channels in a new
     * entry and open it.
     *
     * @param gui the QuPath window
     */
    public static void attachForCurrentImage(QuPathGUI gui) {
        if (gui == null) return;
        ImageData<BufferedImage> imageData = gui.getImageData();
        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> entry =
                project == null || imageData == null ? null : project.getEntry(imageData);
        if (entry == null) {
            Dialogs.showErrorMessage(TITLE, "Open a project image first.");
            return;
        }
        if (entry.getMetadata().containsKey(META_KIND)) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "This entry already holds fiber channels. Open \""
                            + entry.getMetadata().get(META_SOURCE) + "\" to add its map.");
            return;
        }
        Path map = DensitySidecar.sidecarPathFor(project, entry);
        if (!DensitySidecar.exists(map)) {
            Dialogs.showErrorMessage(
                    TITLE,
                    "No density map for this image yet. Run \"Project density map...\" first.\n\nExpected: " + map);
            return;
        }
        if (imageData.isChanged()) {
            Dialogs.showWarningNotification(
                    TITLE,
                    "The new entry copies this image's objects as last saved; unsaved changes are not included.");
        }
        addAndOpen(gui, project, entry, map, KIND_DENSITY, null);
    }

    /**
     * Add (or refresh) a channels entry on a background thread, then open it.
     *
     * @param gui     the QuPath window
     * @param project the project
     * @param source  the image the map was computed on
     * @param map     the map file
     * @param kind    {@link #KIND_DENSITY} or {@link #KIND_CLASSIFIER}
     * @param colours channel name to packed RGB for channels that should take a set colour, or null
     */
    public static void addAndOpen(
            QuPathGUI gui,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> source,
            Path map,
            String kind,
            Map<String, Integer> colours) {
        Thread t = new Thread(
                () -> {
                    try {
                        ProjectImageEntry<BufferedImage> added = addChannelsEntry(project, source, map, kind, colours);
                        HeadlessFx.runLater(() -> {
                            gui.refreshProject();
                            gui.openImageEntry(added);
                        });
                    } catch (Exception e) {
                        logger.error("Adding fiber channels for {} failed", source.getImageName(), e);
                        HeadlessFx.runLater(
                                () -> Dialogs.showErrorMessage(TITLE, "Could not add the channels: " + e.getMessage()));
                    }
                },
                "fiber-add-channels");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Create the channels entry for a map, or rebuild the one that already exists.
     *
     * @param project the project
     * @param source  the image the map was computed on
     * @param map     the map file
     * @param kind    {@link #KIND_DENSITY} or {@link #KIND_CLASSIFIER}
     * @param colours channel name to packed RGB, or null
     * @return the channels entry
     * @throws Exception if the image or map cannot be read, or the map does not fit the image
     */
    public static ProjectImageEntry<BufferedImage> addChannelsEntry(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> source,
            Path map,
            String kind,
            Map<String, Integer> colours)
            throws Exception {
        ProjectImageEntry<BufferedImage> existing = findChannelsEntry(project, source, map);
        ImageData<BufferedImage> sourceData = source.readImageData();
        ImageServer<BufferedImage> sourceServer = sourceData.getServer();
        DensityChannelsServer mapServer = DensityChannelsServerBuilder.build(map);
        if (!mapServer.getMapInfo().fits(sourceServer.getWidth(), sourceServer.getHeight())) {
            mapServer.close();
            throw new IllegalArgumentException(map.getFileName() + " was made for a "
                    + mapServer.getMapInfo().sourceWidth() + " x "
                    + mapServer.getMapInfo().sourceHeight()
                    + " image, not " + sourceServer.getWidth() + " x " + sourceServer.getHeight());
        }
        boolean rgb = sourceServer.isRGB();
        var builder = new TransformedServerBuilder(sourceServer);
        if (sourceServer.getPixelType() != PixelType.FLOAT32) builder.convertType(PixelType.FLOAT32);
        ImageServer<BufferedImage> merged = builder.concatChannels(
                        java.util.List.<ImageServer<BufferedImage>>of(mapServer))
                .build();

        ProjectImageEntry<BufferedImage> entry;
        ImageData<BufferedImage> data;
        if (existing != null) {
            // Keep the objects drawn on the channels entry itself.
            entry = existing;
            data = new ImageData<>(merged, existing.readImageData().getHierarchy(), sourceData.getImageType());
        } else {
            entry = project.addImage(merged.getBuilder());
            entry.setImageName(source.getImageName() + " [" + label(kind, map) + "]");
            entry.getMetadata().putAll(source.getMetadata());
            BufferedImage thumb = source.getThumbnail();
            if (thumb != null) entry.setThumbnail(thumb);
            data = new ImageData<>(merged, sourceData.getHierarchy(), sourceData.getImageType());
        }
        entry.getMetadata().put(META_KIND, kind);
        entry.getMetadata().put(META_SOURCE, source.getImageName());
        String threshold = source.getMetadata().get(ThresholdIdentity.META_DENSITY);
        if (KIND_DENSITY.equals(kind) && threshold != null)
            entry.getMetadata().put(ThresholdIdentity.META_DENSITY, threshold);
        entry.getMetadata().put(META_MAP, map.getFileName().toString());
        storeDisplay(data, sourceServer.nChannels(), rgb, colours);
        entry.saveImageData(data);
        project.syncChanges();
        merged.close();
        logger.info(
                "{} entry '{}' for {} ({} map channels)",
                existing == null ? "Added" : "Refreshed",
                entry.getImageName(),
                source.getImageName(),
                mapServer.nChannels());
        return entry;
    }

    /**
     * Rebuild a map's channels entry if one exists, so a re-run map with
     * different channels does not leave it stale.
     *
     * @param project the project
     * @param source  the image the map was computed on
     * @param map     the map file just written
     * @param kind    {@link #KIND_DENSITY} or {@link #KIND_CLASSIFIER}
     * @param colours channel name to packed RGB, or null
     */
    public static void refreshIfPresent(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> source,
            Path map,
            String kind,
            Map<String, Integer> colours) {
        if (project == null || findChannelsEntry(project, source, map) == null) return;
        try {
            addChannelsEntry(project, source, map, kind, colours);
        } catch (Exception e) {
            logger.warn("Could not refresh the channels entry for {}: {}", source.getImageName(), e.getMessage());
        }
    }

    /** @return the entry holding this source's channels from this map, or null */
    static ProjectImageEntry<BufferedImage> findChannelsEntry(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> source, Path map) {
        String file = map.getFileName().toString();
        for (var e : project.getImageList()) {
            var m = e.getMetadata();
            if (file.equals(m.get(META_MAP)) && source.getImageName().equals(m.get(META_SOURCE))) return e;
        }
        return null;
    }

    private static String label(String kind, Path map) {
        if (KIND_DENSITY.equals(kind)) return "fiber density";
        String name = map.getFileName().toString().replaceFirst("\\.ome\\.tif$", "");
        int us = name.lastIndexOf('_');
        return "classifier " + (us >= 0 ? name.substring(us + 1) : name);
    }

    /**
     * Save display settings: an RGB source keeps its colours (its first three
     * channels shown red, green and blue over 0-255); named map channels take
     * their colours but start hidden.
     */
    private static void storeDisplay(
            ImageData<BufferedImage> data, int sourceChannels, boolean rgb, Map<String, Integer> colours)
            throws IOException {
        ImageDisplay display = ImageDisplay.create(data);
        var channels = display.availableChannels();
        int[][] rgbLut = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
        for (int i = 0; i < channels.size(); i++) {
            var info = channels.get(i);
            if (rgb && i < 3) {
                if (info instanceof DirectServerChannelInfo direct) {
                    direct.setLUTColor(rgbLut[i][0], rgbLut[i][1], rgbLut[i][2]);
                }
                display.setMinMaxDisplay(info, 0f, 255f);
                display.setChannelSelected(info, true);
            } else if (i >= sourceChannels) {
                Integer c = colours == null ? null : colours.get(info.getName());
                if (c != null && info instanceof DirectServerChannelInfo direct) {
                    direct.setLUTColor(ColorTools.red(c), ColorTools.green(c), ColorTools.blue(c));
                }
                display.setChannelSelected(info, false);
            }
        }
        data.setProperty(ImageDisplay.class.getName(), display.toJSON(false));
    }
}
