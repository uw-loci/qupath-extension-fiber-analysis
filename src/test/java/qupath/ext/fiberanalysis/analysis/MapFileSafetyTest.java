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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.RegionRequest;

class MapFileSafetyTest {

    @Test
    void aCopiedEntryKeepsItsSuffix() {
        String base = "MetroHealth_FNA_PDAC_4_20x_30636_102874_7.0_biref_014.ome.tif";
        assertThat(MapFileName.safe(base + " (1)")).isEqualTo(base + "_(1)");
        assertThat(MapFileName.safe(base + " (1)")).isNotEqualTo(MapFileName.safe(base));
    }

    @Test
    void aVeryLongNameIsCutWithAHashSoTwoNamesStayApart() {
        String a = "x".repeat(250) + "_a";
        String b = "x".repeat(250) + "_b";
        String sa = MapFileName.safe(a);
        assertThat(sa).hasSize(MapFileName.MAX_LENGTH).matches("x{191}_[0-9a-f]{8}");
        assertThat(sa).isNotEqualTo(MapFileName.safe(b));
        assertThat(MapFileName.safe(a)).isEqualTo(sa);
        assertThat(MapFileName.safe("y".repeat(200))).isEqualTo("y".repeat(200));
    }

    @Test
    void aTileThatCannotBeReadStopsTheImage(@TempDir Path dir) {
        var server = new WrappedBufferedImageServer("broken", new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)) {
            @Override
            public BufferedImage readRegion(RegionRequest request) throws IOException {
                throw new IOException("disk full");
            }
        };
        RegionRequest req = RegionRequest.createInstance(server.getPath(), 1.0, 0, 0, 32, 32);
        assertThatThrownBy(() -> FiberDensityMapWorkflow.writeTileImage(
                        server, req, "Raw intensity", dir.resolve("tile.png"), 7540, 15080))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("(7540, 15080)")
                .hasMessageContaining(dir.toString())
                .hasMessageContaining("disk full")
                .hasMessageContaining("No map was written");
    }
}
