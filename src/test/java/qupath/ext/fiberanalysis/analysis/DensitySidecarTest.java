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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A map file's description, written and read back: the placement every
 * reader (the overlay, the channels server, sampling, QuIET) draws the grid
 * by, and the channel names. The parser once kept only the first letter of
 * each name, so "Skeleton length" and "Signal density" both became "S".
 */
class DensitySidecarTest {

    private static DensityMapInfo info(DensityLattice lat, boolean smooth, String source) {
        return new DensityMapInfo(lat, 16952, 18225, 0.1732, smooth, source, List.of());
    }

    @Test
    void everyChannelNameRoundTripsWhole() {
        var channels = new java.util.ArrayList<>(DensityChannelSpec.defaultChannels());
        channels.add(DensityChannelSpec.forObjectClass("Tumor: Stroma"));
        channels.add(DensityChannelSpec.forPixelPositivity(
                new PixelPositivitySpec(0, "Channel 1", PixelPositivitySpec.OP_GT, 2000)));
        channels.add(DensityChannelSpec.forClassProbability("Dense collagen"));
        String desc = SidecarDescription.format(info(DensityLattice.blocks(4, 4, 10), false, "a"), channels);
        var infos = SidecarDescription.parse(desc);
        assertThat(infos).hasSize(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            assertThat(infos.get(i).name).isEqualTo(channels.get(i).channelName);
            assertThat(infos.get(i).unit).isEqualTo(channels.get(i).unit);
        }
    }

    @Test
    void namesAreDistinctSoNoMeasurementOverwritesAnother() {
        String desc = SidecarDescription.format(
                info(DensityLattice.blocks(4, 4, 10), false, "a"), DensityChannelSpec.defaultChannels());
        var infos = SidecarDescription.parse(desc);
        assertThat(infos.stream().map(i -> i.name).distinct().count()).isEqualTo(infos.size());
    }

    @Test
    void thePlacementRoundTripsSoTheGridLandsWhereItWasComputed() {
        // The MH_FNA run: 25 um windows at 0.1732 um/px, 75% overlap.
        var lat = DensityLattice.windows(467, 503, 36, 144, 0, 0);
        String desc = SidecarDescription.format(
                info(lat, true, "MetroHealth_FNA_PDAC_2 90.0; a name with a semicolon"),
                DensityChannelSpec.defaultChannels());
        assertThat(desc).contains("x0=54; y0=54; grid=467x503; render=bilinear");
        DensityMapInfo back = SidecarDescription.parseInfo(desc);
        assertThat(back.lattice()).isEqualTo(lat);
        assertThat(back.lattice().x0()).isEqualTo(54.0);
        assertThat(back.sourceWidth()).isEqualTo(16952);
        assertThat(back.sourceHeight()).isEqualTo(18225);
        assertThat(back.sourcePixelUm()).isEqualTo(0.1732);
        assertThat(back.smooth()).isTrue();
        assertThat(back.sourceImage()).isEqualTo("MetroHealth_FNA_PDAC_2 90.0; a name with a semicolon");
        assertThat(back.channels()).hasSize(DensityChannelSpec.defaultChannels().size());
    }

    @Test
    void aHalfPixelInsetAndARegionOriginSurviveTheRoundTrip() {
        // An odd window - cell difference puts the cells on half pixels; an
        // annotation-scope classifier map starts at the annotation.
        var lat = DensityLattice.windows(10, 7, 36, 145, 1000, 2000);
        DensityMapInfo back =
                SidecarDescription.parseInfo(SidecarDescription.format(info(lat, false, null), List.of()));
        assertThat(back.lattice().x0()).isEqualTo(1054.5);
        assertThat(back.lattice().y0()).isEqualTo(2054.5);
        assertThat(back.lattice()).isEqualTo(lat);
        assertThat(back.smooth()).isFalse();
        assertThat(back.sourceImage()).isNull();
    }

    @Test
    void aFullResolutionMapFromBeforeTheChangeIsRefusedWithAReason() {
        String old = "FiberAnalysis density-map sidecar. Pixel type: float32; NaN = no data.\n"
                + "Channels:\n  [0] name=Fiber coverage (%); unit=%\n";
        assertThatThrownBy(() -> SidecarDescription.parseInfo(old))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Re-run");
    }
}
