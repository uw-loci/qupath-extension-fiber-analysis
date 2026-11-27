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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The density map's Java-to-Python contract: the tile inputs Java builds, run through the real
 * {@code density_tile.py}, give back exactly the grid Java's lattice expects. Skipped when no
 * {@code python3} with numpy, scipy, scikit-image and Pillow is on the PATH.
 */
class DensityTileInputsTest {

    private static final Path PKG_ROOT = Path.of("src/main/resources/qupath/ext/fiberanalysis");

    private static FiberDensityMapWorkflow.DensityMapJobSpec spec(double windowUm, double overlapPercent) {
        return new FiberDensityMapWorkflow.DensityMapJobSpec(
                windowUm,
                overlapPercent,
                "Raw intensity",
                "manual",
                120,
                0.1,
                "none",
                1,
                2,
                1,
                0,
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

    /**
     * Halfway cases: 30 px at 25 % overlap (stride 22.5) and 29 px at 50 % (14.5). Java's
     * Math.round and Python's round used to land on different strides, and the image failed with
     * a grid-shape mismatch. The widths are chosen so the two strides give different grids.
     */
    @ParameterizedTest
    @CsvSource({"15.0, 25, 140, 100, 30, 22", "14.5, 50, 71, 60, 29, 14"})
    void pythonReturnsTheGridJavaExpects(
            double windowUm, double overlap, int w, int h, int windowPx, int stridePx, @TempDir Path tmp)
            throws Exception {
        assumeTrue(pythonWithScience(), "python3 with numpy/scipy/scikit-image/Pillow is not on the PATH");
        double pxUm = 0.5;
        var spec = spec(windowUm, overlap);
        var geo = FiberDensityMapWorkflow.geometry(spec, pxUm, w, h, 8);
        assertThat(geo.windowPx()).isEqualTo(windowPx);
        assertThat(geo.cellPx()).isEqualTo(stridePx);

        Path png = tmp.resolve("tile.png");
        ImageIO.write(stripes(w, h), "png", png.toFile());
        Path npz = tmp.resolve("tile.npz");
        Map<String, Object> in = FiberDensityMapWorkflow.buildTileInputs(spec, pxUm, geo, png, w, h, 0, 0, w, h, npz);
        assertThat(in).containsEntry("window_px", windowPx).containsEntry("stride_px", stridePx);
        assertThat(in).doesNotContainKeys("window_size_um", "window_overlap_percent");

        runDensityTile(in, tmp);

        List<DensityChannelSpec> channels = DensityChannelSpec.defaultChannels();
        float[][] accum = new float[channels.size()][geo.gridW() * geo.gridH()];
        for (float[] a : accum) Arrays.fill(a, Float.NaN);
        // Throws when the returned grid_shape is not the one the lattice expects.
        FiberDensityMapWorkflow.accumulateTile(
                accum, channels, npz, 0, 0, geo.gridW(), geo.gridH(), geo.cellsIn(w), geo.cellsIn(h));
        assertThat(geo.gridW()).isEqualTo((w - windowPx) / stridePx + 1);
    }

    @Test
    void theRunsWindowRulesAreSent(@TempDir Path tmp) throws Exception {
        var spec = spec(15, 50).withRunWindowRules(7.5, 3.0);
        var geo = FiberDensityMapWorkflow.geometry(spec, 0.5, 200, 200, 8);
        Map<String, Object> in = FiberDensityMapWorkflow.buildTileInputs(
                spec, 0.5, geo, tmp.resolve("t.png"), 200, 200, 0, 0, 200, 200, tmp.resolve("t.npz"));
        assertThat(in).containsEntry("min_window_coverage_percent", 7.5).containsEntry("min_branch_um", 3.0);
    }

    private static void runDensityTile(Map<String, Object> in, Path tmp) throws Exception {
        Path inputs = tmp.resolve("inputs.json");
        Files.writeString(inputs, new com.google.gson.Gson().toJson(in), StandardCharsets.UTF_8);
        Path driver = tmp.resolve("driver.py");
        Files.writeString(
                driver,
                String.join(
                        "\n",
                        "import json, sys, types",
                        "g = json.load(open(sys.argv[1]))",
                        "task = types.SimpleNamespace(outputs={})",
                        "g['task'] = task",
                        "g['__name__'] = '__appose__'",
                        "exec(compile(open(sys.argv[2]).read(), sys.argv[2], 'exec'), g)",
                        "print('RESULT', task.outputs.get('ok'), task.outputs.get('error'))",
                        ""),
                StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(
                        "python3",
                        driver.toString(),
                        inputs.toString(),
                        PKG_ROOT.resolve("scripts/density_tile.py")
                                .toAbsolutePath()
                                .toString())
                .redirectErrorStream(true);
        pb.environment().put("PYTHONPATH", PKG_ROOT.toAbsolutePath().toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(out).as(out).contains("RESULT 1 None");
    }

    private static BufferedImage stripes(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.getRaster().setSample(x, y, 0, ((x + y) % 9) < 4 ? 200 : 20);
        return img;
    }

    private static boolean pythonWithScience() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import numpy, scipy, skimage, PIL")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
