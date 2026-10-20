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

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Reading a map through blocks of cells -- the channels server's tiles and the
 * sampling command -- gives exactly what sampling the whole grid gives.
 */
class DensityGridSamplingTest {

    private static final int GW = 20;
    private static final int GH = 15;

    private static float[] randomAngles(long seed) {
        var rnd = new SplittableRandom(seed);
        float[] g = new float[GW * GH];
        for (int i = 0; i < g.length; i++) g[i] = rnd.nextInt(10) == 0 ? Float.NaN : (float) (180 * rnd.nextDouble());
        return g;
    }

    private static float[] randomGrid(long seed) {
        var rnd = new SplittableRandom(seed);
        float[] g = new float[GW * GH];
        for (int i = 0; i < g.length; i++) g[i] = rnd.nextInt(10) == 0 ? Float.NaN : (float) rnd.nextDouble();
        return g;
    }

    private static DensityMapInfo info(boolean smooth) {
        // 20 px windows every 8 px, laid from (3, 5): cells start at (9, 11).
        return new DensityMapInfo(
                DensityLattice.windows(GW, GH, 8, 20, 3, 5),
                200,
                150,
                0.5,
                smooth,
                "img",
                List.of(
                        new DensitySidecar.ChannelInfo(0, "A", null, 0),
                        new DensitySidecar.ChannelInfo(1, "Angle", "deg", 180)));
    }

    /** Blocks cut from in-memory grids, as the reader would return them. */
    private static DensityChannelsServer.BlockSource blocks(float[][] grids) {
        return (gx, gy, w, h) -> {
            float[][] out = new float[grids.length][w * h];
            for (int c = 0; c < grids.length; c++)
                for (int y = 0; y < h; y++) System.arraycopy(grids[c], (gy + y) * GW + gx, out[c], y * w, w);
            return out;
        };
    }

    private static float[][] read(DensityChannelsServer.BlockSource src, int[] b) {
        try {
            return src.read(b[0], b[1], b[2], b[3]);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aTileReadThroughABlockMatchesTheWholeGrid() throws Exception {
        float[][] grids = {randomGrid(1), randomAngles(2)};
        int[][] tiles = {
            {0, 0, 200, 150, 50, 38},
            {40, 30, 64, 64, 64, 64},
            {41, 37, 23, 17, 23, 17},
            {150, 100, 50, 50, 25, 25},
            {0, 0, 9, 11, 9, 11}
        };
        for (boolean smooth : new boolean[] {false, true}) {
            DensityMapInfo info = info(smooth);
            for (int[] t : tiles) {
                int outW = t[4];
                int outH = t[5];
                float[][] banks = new float[2][outW * outH];
                for (float[] b : banks) Arrays.fill(b, Float.NaN);
                DensityChannelsServer.fillTile(info, blocks(grids), banks, t[0], t[1], t[2], t[3], outW, outH);
                double sx = (double) t[2] / outW;
                double sy = (double) t[3] / outH;
                for (int c = 0; c < 2; c++) {
                    for (int oy = 0; oy < outH; oy++) {
                        for (int ox = 0; ox < outW; ox++) {
                            float expect = info.sample(c, grids[c], t[0] + (ox + 0.5) * sx, t[1] + (oy + 0.5) * sy);
                            assertThat(Float.floatToIntBits(banks[c][oy * outW + ox]))
                                    .as(
                                            "smooth=%s tile=%s channel %d at (%d, %d)",
                                            smooth, Arrays.toString(t), c, ox, oy)
                                    .isEqualTo(Float.floatToIntBits(expect));
                        }
                    }
                }
            }
        }
    }

    @Test
    void anObjectOverWholeCellsGetsTheMeanOfThoseCells() {
        DensityMapInfo info = info(false);
        float[][] grid = {randomGrid(3), randomGrid(4)};
        Arrays.fill(grid[0], 0f);
        // Cells (2..4, 3..5) hold 1..9; the ROI covers exactly those cells.
        double sum = 0;
        int k = 1;
        for (int gy = 3; gy <= 5; gy++)
            for (int gx = 2; gx <= 4; gx++) {
                grid[0][gy * GW + gx] = k;
                sum += k++;
            }
        var roi = ROIs.createRectangleROI(9 + 2 * 8, 11 + 3 * 8, 24, 24, ImagePlane.getDefaultPlane());
        int[] b = DensitySamplingCommand.blockFor(info, roi);
        float[][] block = read(blocks(grid), b);
        double[] means = DensitySamplingCommand.sampleRoi(info, block, b[0], b[1], b[2], b[3], roi);
        assertThat(means[0]).isCloseTo(sum / 9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void anObjectSmallerThanTheSamplingStepTakesTheValueAtItsCentroid() {
        DensityMapInfo info = info(false);
        float[][] grid = {randomGrid(5), randomGrid(6)};
        Arrays.fill(grid[0], 7f);
        grid[0][4 * GW + 6] = 3f;
        var roi = ROIs.createRectangleROI(9 + 6 * 8 + 3, 11 + 4 * 8 + 3, 1, 1, ImagePlane.getDefaultPlane());
        int[] b = DensitySamplingCommand.blockFor(info, roi);
        double[] means = DensitySamplingCommand.sampleRoi(info, read(blocks(grid), b), b[0], b[1], b[2], b[3], roi);
        assertThat(means[0]).isEqualTo(3.0);
    }

    @Test
    void anObjectsMeanAngleIsAnAxialMeanNotAPlainOne() {
        DensityMapInfo info = info(false);
        float[][] grid = {randomGrid(7), new float[GW * GH]};
        Arrays.fill(grid[1], Float.NaN);
        // Two cells at 175 and 5 degrees: the plain mean is 90, the orientation is 0.
        grid[1][3 * GW + 2] = 175f;
        grid[1][3 * GW + 3] = 5f;
        var roi = ROIs.createRectangleROI(9 + 2 * 8, 11 + 3 * 8, 16, 8, ImagePlane.getDefaultPlane());
        int[] b = DensitySamplingCommand.blockFor(info, roi);
        double[] means = DensitySamplingCommand.sampleRoi(info, read(blocks(grid), b), b[0], b[1], b[2], b[3], roi);
        double fromZero = Math.min(means[1], 180 - means[1]);
        assertThat(fromZero).isLessThan(1e-6);
    }

    @Test
    void pyramidLevelsAverageAnAngleAsAnAngle() {
        float[] grid = {175f, 5f, 170f, 10f};
        float[] mean = DensityLattice.blockMean(grid, 2, 2, 2, 1, 1, 180);
        assertThat(Math.min(mean[0], 180 - mean[0])).isLessThan(1e-4f);
        float[] plain = DensityLattice.blockMean(grid, 2, 2, 2, 1, 1, 0);
        assertThat(plain[0]).isEqualTo(90f);
    }

    @Test
    void anObjectOffTheGridHasNoBlock() {
        var roi = ROIs.createRectangleROI(500, 500, 10, 10, ImagePlane.getDefaultPlane());
        assertThat(DensitySamplingCommand.blockFor(info(false), roi)).isNull();
    }
}
