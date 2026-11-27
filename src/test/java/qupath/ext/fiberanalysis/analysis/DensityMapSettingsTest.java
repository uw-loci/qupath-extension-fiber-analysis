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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapDialog.PositivityInput;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow.DensityMapJobSpec;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Density-map settings: what the dialog refuses, what the params file replays, and per-image lookups. */
class DensityMapSettingsTest {

    private static DensityMapJobSpec spec() {
        return new DensityMapJobSpec(
                15,
                50,
                "Raw intensity",
                "otsu",
                128,
                0.1,
                "none",
                1,
                4,
                1,
                1,
                false,
                0,
                null,
                false,
                "window",
                0,
                false,
                List.of(),
                false,
                List.of());
    }

    private static DensityMapJobSpec replay(Map<String, Object> params) throws IOException {
        File json = File.createTempFile("density_params", ".json");
        json.deleteOnExit();
        Files.writeString(json.toPath(), new com.google.gson.Gson().toJson(params));
        return DensityMapJobSpec.fromJson(json);
    }

    // ---- 5: existing fiber mask ----

    @Test
    void anExistingMaskInTheRunSettingsBlocksTheMap() {
        assertThat(FiberDensityMapDialog.segSourceProblem("existing"))
                .contains("Use existing fiber mask")
                .contains("Segment within extension");
        assertThat(FiberDensityMapDialog.segSourceProblem("internal")).isNull();
        assertThat(FiberDensityMapDialog.segSourceProblem(null)).isNull();
    }

    // ---- 23, 24, 28: the Run's window rules and the output mode travel with the run ----

    @Test
    void windowRulesAndOutputModeReplayFromTheParamsFile() throws Exception {
        var spec = spec().withRunWindowRules(5.0, 4.5)
                .withOutputMode(DensityMapJobSpec.OUTPUT_CHANNELS)
                .withRunThreshold(0.3, "pooled");
        assertThat(spec.minWindowCoveragePercent).isEqualTo(5.0);
        assertThat(spec.addsChannelEntries()).isTrue();
        var back = replay(spec.toOrderedMap());
        assertThat(back.minWindowCoveragePercent).isEqualTo(5.0);
        assertThat(back.minBranchUm).isEqualTo(4.5);
        assertThat(back.outputMode).isEqualTo(DensityMapJobSpec.OUTPUT_CHANNELS);
        assertThat(back.addsChannelEntries()).isTrue();
        assertThat(back.projectThresholdNorm).isEqualTo(0.3);

        var mapOnly =
                replay(spec().withOutputMode(DensityMapJobSpec.OUTPUT_MAP_FILE).toOrderedMap());
        assertThat(mapOnly.addsChannelEntries()).isFalse();
    }

    @Test
    void anOlderParamsFileReplaysTheWayItsMapWasMade() throws Exception {
        Map<String, Object> old = spec().toOrderedMap();
        old.remove("minWindowCoveragePercent");
        old.remove("minBranchUm");
        old.remove("outputMode");
        var back = replay(old);
        assertThat(back.minWindowCoveragePercent).isZero();
        assertThat(back.minBranchUm).isEqualTo(2.0);
        assertThat(back.addsChannelEntries()).isFalse();
    }

    @Test
    void badWindowRulesAndModesAreRefused() {
        assertThatThrownBy(() -> spec().withRunWindowRules(-1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec().withOutputMode("Channels")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWindowHelpNamesTheSettingsTheMapUses() {
        assertThat(FiberDensityMapDialog.windowGridHelp(15, 50, 5, 5))
                .contains("15 um windows")
                .contains("50 % overlap")
                .contains("coverage per window 5 %")
                .contains("branch length 5 um");
    }

    // ---- 25: positivity channel by name ----

    @Test
    void positivityFindsItsChannelByNameInEachImage() throws Exception {
        var dapi = new PixelPositivitySpec(0, "DAPI", PixelPositivitySpec.OP_GT, 500);
        assertThat(PixelPositivityComputer.resolveBand(List.of("DAPI", "CD8"), dapi))
                .isZero();
        // Same name, another position: the name wins over the stored index.
        assertThat(PixelPositivityComputer.resolveBand(List.of("CD8", "Opal", "DAPI"), dapi))
                .isEqualTo(2);
        assertThatThrownBy(() -> PixelPositivityComputer.resolveBand(List.of("CD8", "Opal"), dapi))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no channel named 'DAPI'")
                .hasMessageContaining("CD8, Opal");
        // Two of the name: the stored band decides when it holds that name, else it is refused.
        assertThat(PixelPositivityComputer.resolveBand(List.of("DAPI", "DAPI"), dapi))
                .isZero();
        var second = new PixelPositivitySpec(5, "DAPI", PixelPositivitySpec.OP_GT, 500);
        assertThatThrownBy(() -> PixelPositivityComputer.resolveBand(List.of("DAPI", "DAPI"), second))
                .hasMessageContaining("more than one channel");
        // An unnamed channel reads the way the dialog lists it.
        var unnamed = new PixelPositivitySpec(1, "Channel 1", PixelPositivitySpec.OP_LT, 3);
        assertThat(PixelPositivityComputer.resolveBand(List.of("", ""), unnamed))
                .isEqualTo(1);
    }

    // ---- 28: invalid positivity rows block the run ----

    @Test
    void invalidPositivityRowsBlockTheRun() {
        assertThat(FiberDensityMapDialog.positivityProblem(false, List.of(new PositivityInput(null, ""))))
                .isNull();
        assertThat(FiberDensityMapDialog.positivityProblem(true, List.of())).contains("no rule");
        assertThat(FiberDensityMapDialog.positivityProblem(true, List.of(new PositivityInput(null, "5"))))
                .contains("row 1")
                .contains("choose a channel");
        assertThat(FiberDensityMapDialog.positivityProblem(
                        true, List.of(new PositivityInput("DAPI", "5"), new PositivityInput("CD8", " "))))
                .contains("row 2")
                .contains("enter a threshold");
        assertThat(FiberDensityMapDialog.positivityProblem(true, List.of(new PositivityInput("DAPI", "1,5"))))
                .contains("not a number");
        assertThat(FiberDensityMapDialog.positivityProblem(true, List.of(new PositivityInput("DAPI", "NaN"))))
                .contains("not a number");
        assertThat(FiberDensityMapDialog.positivityProblem(true, List.of(new PositivityInput("DAPI", " 2.5e3 "))))
                .isNull();
    }

    // ---- 28: no objects of a class is 0, not no data ----

    @Test
    void aClassWithNoObjectsReadsZeroWhereTheCellHasData() throws Exception {
        var geo = FiberDensityMapWorkflow.Geometry.window(100, 80, 20, 10, 16);
        var out = ObjectDensityComputer.compute(new PathObjectHierarchy(), List.of("Tumor"), geo, 100, 80);
        float[] grid = out.get("Tumor");
        assertThat(grid).hasSize(geo.gridW() * geo.gridH());
        for (float v : grid) assertThat(v).isZero();
    }

    // ---- 26: completion reports what was written ----

    @Test
    void completionRunsWithNoImagesWhenNothingCanRun() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<Object> seen = new ArrayList<>();
        Thread t = new FiberDensityMapWorkflow().runForEntries(spec(), List.of(), null, null, made -> {
            seen.add(made);
            done.countDown();
        });
        assertThat(t).isNull();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly(List.of());
    }

    @Test
    void onlyAnImageThisRunWroteHasItsChannelsEntryOpened() {
        Map<String, String> channelsEntries = Map.of("open", "open [fiber density]");
        assertThat(FiberDensityMapDialog.channelsEntryToOpen(List.of("open", "b"), "open", channelsEntries::get))
                .isEqualTo("open [fiber density]");
        // The open image failed: its old channels entry (stale map) is not opened.
        assertThat(FiberDensityMapDialog.channelsEntryToOpen(List.of("b"), "open", channelsEntries::get))
                .isNull();
        assertThat(FiberDensityMapDialog.channelsEntryToOpen(List.of("b"), null, channelsEntries::get))
                .isNull();
    }
}
