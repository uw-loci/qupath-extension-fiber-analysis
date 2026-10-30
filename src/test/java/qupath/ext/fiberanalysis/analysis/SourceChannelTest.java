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

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.PixelType;

class SourceChannelTest {

    @Test
    void theBrightestBandKeepsAllSixteenBitsThroughThePng(@TempDir Path dir) throws Exception {
        WritableRaster raster = Raster.createBandedRaster(DataBuffer.TYPE_USHORT, 2, 1, 3, new Point());
        raster.setPixel(0, 0, new int[] {300, 470, 200});
        raster.setPixel(1, 0, new int[] {13600, 4, 65535});
        ColorModel cm = new ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                false,
                false,
                java.awt.Transparency.OPAQUE,
                DataBuffer.TYPE_USHORT);
        BufferedImage region = new BufferedImage(cm, raster, false, null);

        BufferedImage gray = SourceChannel.brightestBand(region, PixelType.UINT16);
        assertThat(gray.getRaster().getNumBands()).isEqualTo(1);

        Path png = dir.resolve("region.png");
        assertThat(ImageIO.write(gray, "PNG", png.toFile())).isTrue();
        Raster back = ImageIO.read(png.toFile()).getRaster();
        assertThat(back.getSample(0, 0, 0)).isEqualTo(470);
        assertThat(back.getSample(1, 0, 0)).isEqualTo(65535);
    }
}
