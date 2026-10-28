/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import qupath.ext.fiberanalysis.analysis.DensityMapReader;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.analysis.MapFileName;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/**
 * Density-map file names keep a copied entry's " (1)", and a tile that cannot
 * be read fails its image instead of leaving a gap.
 *
 * <p>Args: {@code <goodImage> <brokenImage> <projectDir> <envBaseDir>}. The
 * broken image is a copy of the good one with one compressed tile overwritten.
 */
public final class MapNameAndTileFailureScenario {

    private static int failures = 0;

    static final String BASE = "MetroHealth_FNA_PDAC_4_20x_30636_102874_7.0_biref_014.ome.tif";

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        Path good = Path.of(args[0]);
        Path broken = Path.of(args[1]);
        Path projectDir = Path.of(args[2]);
        Path envBase = Path.of(args[3]);

        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await(60, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        FutureTask<QuPathGUI> start = new FutureTask<>(() -> {
            QuPathGUI qupath = QuPathGUI.createHiddenInstance();
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            return qupath;
        });
        Platform.runLater(start);
        start.get(120, TimeUnit.SECONDS);

        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        var copy = add(project, good, BASE + " (1)");
        var original = add(project, good, BASE);
        var bad = add(project, broken, "broken_tile.ome.tif");
        project.syncChanges();

        // Same settings as the MH_FNA2 full run's maps.
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                10.0, 0.0, "Raw intensity", "manual", 4500, 0.1, "None", 1.0, 4.0, 1.0, 1.0, false, 0.0, null,
                false, "window", 0.0, false, List.of(), false, List.of());
        Path maps = projectDir.resolve("fiber-analysis").resolve("density-maps");

        new FiberDensityMapWorkflow().runForEntries(spec, List.of(copy, original), project, null).join();
        Path copyMap = maps.resolve(MapFileName.safe(copy.getImageName()) + "_density.ome.tif");
        Path origMap = maps.resolve(MapFileName.safe(original.getImageName()) + "_density.ome.tif");
        note("map files: " + copyMap.getFileName() + " | " + origMap.getFileName());
        check(copyMap.getFileName().toString().equals(BASE + "_(1)_density.ome.tif"),
                "the copied entry's map keeps its (1)");
        check(Files.isRegularFile(copyMap) && Files.isRegularFile(origMap) && !copyMap.equals(origMap),
                "the entry and its copy get two map files");
        if (Files.isRegularFile(copyMap) && Files.isRegularFile(origMap)) {
            try (var a = DensityMapReader.open(copyMap);
                    var b = DensityMapReader.open(origMap)) {
                // Coverage is NaN where a window holds no fiber; ridge count is
                // defined in every computed window, so a skipped tile shows as NaN.
                int ch = a.info().channelIndex("Ridge count");
                float[] ga = a.readChannel(ch);
                int finite = 0;
                for (float v : ga) if (!Float.isNaN(v)) finite++;
                note("ridge count: " + finite + " of " + ga.length + " cells hold a value");
                check(finite == ga.length, "every cell of the whole-image map was computed (no tile gap)");
                boolean same = true;
                for (int c = 0; c < a.info().channels().size(); c++) {
                    same &= java.util.Arrays.equals(a.readChannel(c), b.readChannel(c));
                }
                check(same, "the two maps of the same pixels agree in every channel");
            }
        }

        new FiberDensityMapWorkflow().runForEntries(spec, List.of(bad), project, null).join();
        Path badMap = maps.resolve(MapFileName.safe(bad.getImageName()) + "_density.ome.tif");
        check(!Files.exists(badMap), "no map is written for the image with an unreadable tile");

        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ProjectImageEntry<BufferedImage> add(Project<BufferedImage> project, Path image, String name)
            throws Exception {
        try (var server = ImageServers.buildServer(image.toUri().toString())) {
            ProjectImageEntry<BufferedImage> entry = project.addImage(server.getBuilder());
            entry.setImageName(name);
            return entry;
        }
    }
}
