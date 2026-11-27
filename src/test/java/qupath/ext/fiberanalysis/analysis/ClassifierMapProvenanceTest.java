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

/** A classifier map file says which model made it, and what the user overrode to run it. */
class ClassifierMapProvenanceTest {

    @Test
    void theClassifierLineTravelsWithTheMapFile() {
        String line = "model colon-v1; training verdict REFUSE; PROVISIONAL (the model failed its own validation);"
                + " applied despite: stride 30 differs from the trained 25";
        var info = new DensityMapInfo(
                DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", null, null, line, List.of());
        String desc = SidecarDescription.format(info, DensityChannelSpec.defaultChannels());
        assertThat(desc).contains("\nClassifier: " + line + "\n");
        var back = SidecarDescription.parseInfo(desc);
        assertThat(back.classifier()).isEqualTo(line);
        assertThat(back.channels()).hasSize(DensityChannelSpec.defaultChannels().size());
    }

    @Test
    void aDensityMapHasNoClassifierLine() {
        var info =
                new DensityMapInfo(DensityLattice.blocks(4, 4, 10), 40, 40, 0.5, false, "img", null, null, List.of());
        String desc = SidecarDescription.format(info, DensityChannelSpec.defaultChannels());
        assertThat(desc).doesNotContain("Classifier:");
        assertThat(SidecarDescription.parseInfo(desc).classifier()).isNull();
    }
}
