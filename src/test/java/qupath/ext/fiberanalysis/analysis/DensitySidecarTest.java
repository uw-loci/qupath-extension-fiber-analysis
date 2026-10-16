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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Channel names read back from a sidecar's description. They name the
 * measurements the sampling command writes and the overlay's channel list;
 * the parser used to keep only the first letter, so "Skeleton length" and
 * "Signal density" both became "S" and one overwrote the other.
 */
class DensitySidecarTest {

    /** The description exactly as DensityTiffWriter writes it. */
    private static String written(List<DensityChannelSpec> channels) {
        StringBuilder desc = new StringBuilder();
        desc.append("FiberAnalysis density-map sidecar. Pixel type: float32; NaN = no data.\n");
        desc.append("Channel values are physical (e.g. Fiber coverage (%) reads 0..100).\n");
        desc.append("Channels:\n");
        for (int c = 0; c < channels.size(); c++) {
            DensityChannelSpec spec = channels.get(c);
            desc.append(String.format(
                    java.util.Locale.ROOT,
                    "  [%d] name=%s%s%n",
                    c,
                    spec.channelName,
                    spec.unit != null ? "; unit=" + spec.unit : ""));
        }
        return desc.toString();
    }

    @Test
    void everyChannelNameRoundTripsWhole() {
        var channels = new java.util.ArrayList<>(DensityChannelSpec.defaultChannels());
        channels.add(DensityChannelSpec.forObjectClass("Tumor: Stroma"));
        channels.add(DensityChannelSpec.forPixelPositivity(
                new PixelPositivitySpec(0, "Channel 1", PixelPositivitySpec.OP_GT, 2000)));
        channels.add(DensityChannelSpec.forClassProbability("Dense collagen"));
        var infos = SidecarDescription.parse(written(channels));
        assertThat(infos).hasSize(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            assertThat(infos.get(i).name).isEqualTo(channels.get(i).channelName);
            assertThat(infos.get(i).unit).isEqualTo(channels.get(i).unit);
            assertThat(infos.get(i).scale).isEqualTo(1.0);
        }
    }

    @Test
    void namesAreDistinctSoNoMeasurementOverwritesAnother() {
        var infos = SidecarDescription.parse(written(DensityChannelSpec.defaultChannels()));
        assertThat(infos.stream().map(i -> i.name).distinct().count()).isEqualTo(infos.size());
    }

    @Test
    void legacyScaleAndOffsetRowsStillParse() {
        String desc = "  [0] name=Fiber coverage (%); scale=0.5; offset=-1.25; unit=%\n"
                + "  [1] name=HDM; scale=2; offset=0\n";
        var infos = SidecarDescription.parse(desc);
        assertThat(infos.get(0).name).isEqualTo("Fiber coverage (%)");
        assertThat(infos.get(0).scale).isEqualTo(0.5);
        assertThat(infos.get(0).offset).isEqualTo(-1.25);
        assertThat(infos.get(0).unit).isEqualTo("%");
        assertThat(infos.get(1).name).isEqualTo("HDM");
        assertThat(infos.get(1).scale).isEqualTo(2.0);
    }
}
