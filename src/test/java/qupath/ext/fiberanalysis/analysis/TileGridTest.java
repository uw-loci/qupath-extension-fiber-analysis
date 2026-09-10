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

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tile geometry has to preserve the window lattice exactly: every window
 * position the untiled run would have produced must be produced by exactly one
 * tile, at the same coordinates. A gap silently drops tissue; a misalignment
 * silently shifts every metric.
 */
class TileGridTest {

    /** Window origins Python would emit for a region of this size. */
    private static Set<Long> windowsFor(int regionW, int regionH, int windowPx, int stride) {
        Set<Long> out = new HashSet<>();
        int hw = Math.max(0, (regionH - windowPx) / stride + 1);
        int ww = Math.max(0, (regionW - windowPx) / stride + 1);
        for (int iy = 0; iy < hw; iy++) {
            for (int ix = 0; ix < ww; ix++) {
                out.add(key(ix * stride, iy * stride));
            }
        }
        return out;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** Window origins the tiled run produces, in region-global coordinates. */
    private static Set<Long> windowsFromTiles(TileGrid grid, int windowPx, int stride) {
        Set<Long> out = new HashSet<>();
        for (TileGrid.Box b : grid.tiles()) {
            int hw = Math.max(0, (b.h() - windowPx) / stride + 1);
            int ww = Math.max(0, (b.w() - windowPx) / stride + 1);
            for (int iy = 0; iy < hw; iy++) {
                for (int ix = 0; ix < ww; ix++) {
                    out.add(key(b.x() + ix * stride, b.y() + iy * stride));
                }
            }
        }
        return out;
    }

    @Test
    void smallRegionIsNotTiled() {
        TileGrid grid = TileGrid.create(1000, 1000, 100, 50, 10_000_000);
        assertThat(grid.count()).isEqualTo(1);
        assertThat(grid.tiles().get(0)).isEqualTo(new TileGrid.Box(0, 0, 1000, 1000));
    }

    @Test
    void tileOriginsStayOnTheWindowLattice() {
        TileGrid grid = TileGrid.create(20000, 15000, 100, 50, 4_000_000);
        assertThat(grid.count()).isGreaterThan(1);
        for (TileGrid.Box b : grid.tiles()) {
            assertThat(b.x() % 50).as("tile x off the stride lattice").isZero();
            assertThat(b.y() % 50).as("tile y off the stride lattice").isZero();
        }
    }

    @Test
    void everyTileFitsTheBudget() {
        long budget = 4_000_000;
        TileGrid grid = TileGrid.create(20000, 15000, 100, 50, budget);
        for (TileGrid.Box b : grid.tiles()) {
            assertThat((long) b.w() * b.h()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void tiledRunCoversEveryWindowTheUntiledRunWouldHave() {
        int w = 8000;
        int h = 6000;
        int windowPx = 200;
        int stride = 100;
        TileGrid grid = TileGrid.create(w, h, windowPx, stride, 1_000_000);
        assertThat(grid.count()).isGreaterThan(1);
        Set<Long> expected = windowsFor(w, h, windowPx, stride);
        Set<Long> actual = windowsFromTiles(grid, windowPx, stride);
        assertThat(actual).as("tiling must not drop window positions").containsAll(expected);
    }

    @Test
    void nonOverlappingWindowsAlsoSurviveTiling() {
        int w = 5000;
        int h = 5000;
        int windowPx = 250;
        int stride = 250; // 0% overlap
        TileGrid grid = TileGrid.create(w, h, windowPx, stride, 1_000_000);
        Set<Long> expected = windowsFor(w, h, windowPx, stride);
        Set<Long> actual = windowsFromTiles(grid, windowPx, stride);
        assertThat(actual).containsAll(expected);
    }

    @Test
    void windowSizingMatchesPythonsBankersRounding() {
        // Halfway cases are the whole point: Python round(2.5)=2, round(3.5)=4.
        // Math.round would give 3 and 4, shifting the lattice by a pixel.
        assertThat(TileGrid.windowPx(2.5, 1.0)).isEqualTo(2);
        assertThat(TileGrid.windowPx(3.5, 1.0)).isEqualTo(4);
        assertThat(TileGrid.windowPx(50.0, 0.1732)).isEqualTo(289); // 288.68 -> 289
        assertThat(TileGrid.windowPx(0.1, 1.0)).isEqualTo(2); // floor of 2 enforced
    }

    @Test
    void strideMatchesPythonsOverlapClamp() {
        assertThat(TileGrid.stridePx(100, 0.0)).isEqualTo(100);
        assertThat(TileGrid.stridePx(100, 50.0)).isEqualTo(50);
        assertThat(TileGrid.stridePx(100, 99.0)).isEqualTo(5); // clamped to 95% overlap
        assertThat(TileGrid.stridePx(100, -10.0)).isEqualTo(100); // negative clamps to 0
    }

    @Test
    void rejectsNonsenseWindowGeometry() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> TileGrid.create(100, 100, 0, 50, 1000));
    }
}
