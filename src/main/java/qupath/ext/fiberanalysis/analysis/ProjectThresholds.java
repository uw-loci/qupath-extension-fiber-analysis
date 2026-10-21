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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Records a result's {@link ThresholdIdentity} on its project entry and compares it with the others. */
final class ProjectThresholds {

    private static final Logger logger = LoggerFactory.getLogger(ProjectThresholds.class);

    private ProjectThresholds() {}

    /**
     * @param project   a project, or null
     * @param imageData an image
     * @return the image's entry, or null outside a project
     */
    static ProjectImageEntry<BufferedImage> entryOf(
            Project<BufferedImage> project, ImageData<BufferedImage> imageData) {
        if (project == null || imageData == null) return null;
        try {
            return project.getEntry(imageData);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Stores the identity on the entries and saves the project.
     *
     * @param project the project
     * @param entries the entries the result belongs to
     * @param key     {@link ThresholdIdentity#META_ANNOTATIONS} or {@link ThresholdIdentity#META_DENSITY}
     * @param id      the identity
     */
    static void record(
            Project<BufferedImage> project,
            Collection<ProjectImageEntry<BufferedImage>> entries,
            String key,
            ThresholdIdentity id) {
        if (project == null || entries.isEmpty()) return;
        for (ProjectImageEntry<BufferedImage> e : entries) e.getMetadata().put(key, id.label());
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.warn("Could not save the threshold record in the project: {}", e.getMessage());
        }
    }

    /**
     * @param project the project
     * @param exclude entries to leave out
     * @param key     metadata key
     * @return image entries' recorded labels by entry name; channels entries this extension made are
     *     left out, since they copy their source's metadata
     */
    static Map<String, String> others(
            Project<BufferedImage> project, Collection<ProjectImageEntry<BufferedImage>> exclude, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (project == null) return out;
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (exclude.contains(e) || e.getMetadata().containsKey(DensityChannelAttacher.META_KIND)) continue;
            String v = e.getMetadata().get(key);
            if (v != null && !v.isBlank()) out.put(e.getImageName(), v);
        }
        return out;
    }

    /**
     * Logs and shows a warning when the results recorded across the project are not all comparable.
     *
     * @param project the project, or null
     * @param entries the entries the run that just finished covered
     * @param key     {@link ThresholdIdentity#META_ANNOTATIONS} or {@link ThresholdIdentity#META_DENSITY}
     * @param title   notification title
     */
    static void warnIfDiffers(
            Project<BufferedImage> project,
            Collection<ProjectImageEntry<BufferedImage>> entries,
            String key,
            String title) {
        if (project == null || entries.isEmpty()) return;
        java.util.List<String> names =
                entries.stream().map(ProjectImageEntry::getImageName).toList();
        var labels = others(project, java.util.List.of(), key);
        String warning = ThresholdIdentity.incomparableWarning(labels, names, true);
        if (warning == null) return;
        logger.warn("{}", warning);
        String brief = ThresholdIdentity.incomparableWarning(labels, names, false);
        if (HeadlessFx.isReady()) HeadlessFx.runLater(() -> Dialogs.showWarningNotification(title, brief));
    }
}
