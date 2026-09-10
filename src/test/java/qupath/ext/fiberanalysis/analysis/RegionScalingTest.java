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

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * The boundary mask is rendered at the read scale while the ROI is in
 * full-image coordinates. Getting the scale/translate order wrong puts the
 * annotation in the wrong place with no error, so it is pinned here.
 */
class RegionScalingTest {

    private static BufferedImage renderMask(ROI roi, int regionX, int regionY, int maskW, int maskH, double downsample)
            throws IOException {
        Path png = Files.createTempFile("boundary", ".png");
        try {
            FiberAnalysisWorkflow.rasterisePolygonMask(roi, regionX, regionY, maskW, maskH, downsample, png);
            return ImageIO.read(png.toFile());
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private static boolean inside(BufferedImage mask, int x, int y) {
        return (mask.getRGB(x, y) & 0xFF) > 127;
    }

    @Test
    void fullResolutionPlacesTheShapeAtTheRegionOrigin() throws Exception {
        // ROI at image (100,100)-(200,200); region starts at (100,100).
        ROI roi = ROIs.createRectangleROI(100, 100, 100, 100, ImagePlane.getDefaultPlane());
        BufferedImage mask = renderMask(roi, 100, 100, 100, 100, 1.0);
        assertThat(inside(mask, 50, 50)).isTrue();
        assertThat(inside(mask, 0, 0)).isTrue();
    }

    @Test
    void downsampleShrinksTheShapeAndKeepsItAtTheOrigin() throws Exception {
        // Same ROI read at 4x: a 100 px square becomes 25 px in the mask.
        ROI roi = ROIs.createRectangleROI(100, 100, 100, 100, ImagePlane.getDefaultPlane());
        BufferedImage mask = renderMask(roi, 100, 100, 25, 25, 4.0);
        assertThat(inside(mask, 12, 12)).isTrue();
        assertThat(inside(mask, 0, 0)).isTrue();
        assertThat(inside(mask, 24, 24)).isTrue();
    }

    @Test
    void offsetIsAppliedBeforeTheScale() throws Exception {
        // The ordering trap: an ROI 400 px into the region must land at 100 px
        // in a 4x mask. Scaling before translating would put it at 1300/4.
        ROI roi = ROIs.createRectangleROI(500, 500, 40, 40, ImagePlane.getDefaultPlane());
        BufferedImage mask = renderMask(roi, 100, 100, 200, 200, 4.0);
        assertThat(inside(mask, 105, 105)).isTrue(); // (500-100)/4 = 100, plus a few px in
        assertThat(inside(mask, 5, 5)).isFalse(); // region origin is background
    }
}
