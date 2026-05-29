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

import java.nio.file.Path;
import java.util.Map;

/**
 * Result bundle for a single analysed annotation. Produced by
 * {@link FiberAnalysisWorkflow} and consumed by the results panel
 * (UI/UX Designer's {@code FiberAnalysisPanel}).
 *
 * @param index            zero-based annotation index within this run
 * @param annotationName   display name of the source annotation
 * @param outputDir        per-annotation output folder on disk
 * @param overlayPngs      map of overlay-key -> PNG path
 *                         (keys: {@code fiber_mask}, {@code straightness}, {@code glcm}, {@code morphometrics})
 * @param summaryMetrics   flat dotted-key map of scalar summary metrics
 *                         (e.g. {@code "straightness.mean_tortuosity"} -> 1.18)
 * @param regionOffsetX    image-coordinate X offset of the analysed region
 * @param regionOffsetY    image-coordinate Y offset of the analysed region
 * @param regionW          width of the analysed region in pixels
 * @param regionH          height of the analysed region in pixels
 */
public record AnnotationResult(
        int index,
        String annotationName,
        Path outputDir,
        Map<String, Path> overlayPngs,
        Map<String, Double> summaryMetrics,
        int regionOffsetX,
        int regionOffsetY,
        int regionW,
        int regionH) {}
