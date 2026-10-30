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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow;
import qupath.ext.fiberanalysis.analysis.SourceChannel;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/**
 * A dim, multi-channel 16-bit image through the real run: Raw intensity keeps all
 * 16 bits (a manual cut of 100 finds fibers at 200-470 counts), full-scale GLCM
 * warns, and a gray range that fits the fibers gives real texture.
 *
 * <p>Args: {@code <image> <projectDir> <outDir> <envBaseDir>}.
 */
public final class GlcmRangeScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        try {
            scenario(args);
        } catch (Throwable t) {
            t.printStackTrace();
            check(false, "the scenario ran without an exception (" + t + ")");
        }
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void scenario(String[] args) throws Exception {
        Path image = Path.of(args[0]);
        Path projectDir = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Path envBase = Path.of(args[3]);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await(60, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var src = ImageServers.buildServer(image.toUri().toString())) {
            entry = project.addImage(src.getBuilder());
        }
        entry.setImageName(image.getFileName().toString());
        ImageData<BufferedImage> data = entry.readImageData();
        data.setImageType(ImageData.ImageType.FLUORESCENCE);
        entry.saveImageData(data);
        project.syncChanges();
        var server = data.getServer();
        System.out.println("[....] image " + server.nChannels() + " channels, " + server.getPixelType() + ", RGB "
                + server.isRGB());
        check(server.nChannels() == 3 && !server.isRGB(), "the test image is multi-channel and not RGB");

        try {
            SourceChannel.writeRegionPng(
                    server,
                    RegionRequest.createInstance(server.getPath(), 1.0, 0, 0, 64, 64),
                    "Hue (HSV)",
                    outDir.resolve("hue.png"));
            check(false, "a saved Hue choice is refused on separate channels");
        } catch (IOException e) {
            check(e.getMessage().contains("needs an RGB image"), "a saved Hue choice is refused: " + e.getMessage());
        }

        onFx(() -> {
            qupath.setProject(project);
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            FiberAnalysisPreferences.windowEnabledProperty().set(true);
            FiberAnalysisPreferences.windowSizeUmProperty().set(25.0);
            FiberAnalysisPreferences.windowOverlapPercentProperty().set(0);
            FiberAnalysisPreferences.windowObjectsProperty().set(true);
            FiberAnalysisPreferences.thresholdMethodProperty().set("Manual");
            FiberAnalysisPreferences.manualThresholdProperty().set(100);
            FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity");
            FiberAnalysisPreferences.zoneModeProperty().set("whole");
            FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
            FiberAnalysisPreferences.textureEnabledProperty().set(true);
            FiberAnalysisPreferences.glcmDistancesPxProperty().set("0.5,1");
            FiberAnalysisPreferences.collagenObjectsProperty().set(false);
            FiberAnalysisPreferences.outputDirProperty().set(outDir.toString());
            return null;
        });

        PathObject a = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(20, 20, 560, 560, ImagePlane.getDefaultPlane()));
        data.getHierarchy().addObject(a);

        double fullContrast = run(qupath, data, a, 0, 0, outDir);
        check(notes(outDir).contains("GLCM texture is coarse"), "a full-scale run warns: " + notes(outDir));
        check(fullContrast < 0.05, "on full scale the dim fibers read as flat (contrast " + fullContrast + ")");

        clearNotes(outDir);
        double fitContrast = run(qupath, data, a, 200, 470, outDir);
        check(!notes(outDir).contains("GLCM texture is coarse"), "a run on a fitted range does not warn");
        check(fitContrast > 1.0, "on a fitted range the texture is real (contrast " + fitContrast + ")");
    }

    private static double run(
            QuPathGUI qupath, ImageData<BufferedImage> data, PathObject a, int min, int max, Path outDir)
            throws Exception {
        onFx(() -> {
            FiberAnalysisPreferences.glcmGrayMinProperty().set(min);
            FiberAnalysisPreferences.glcmGrayMaxProperty().set(max);
            return null;
        });
        FiberAnalysisParams params = FiberAnalysisParams.fromPreferences();
        data.getHierarchy().removeObjects(data.getHierarchy().getDetectionObjects(), false);
        Thread t = new FiberAnalysisWorkflow(null).runForAnnotations(params, List.of(a), data, qupath);
        if (t != null) t.join();
        onFx(() -> null);
        double sum = 0;
        int n = 0;
        for (PathObject d : data.getHierarchy().getDetectionObjects()) {
            double v = d.getMeasurementList().get("Contrast (GLCM)");
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        System.out.println("[....] gray range " + min + " to " + max + ": " + n + " windows with texture");
        check(n > 0, "the run found fibers and made windows (range " + min + " to " + max + ")");
        return n == 0 ? Double.NaN : sum / n;
    }

    private static String notes(Path outDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var files = Files.walk(outDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().equals("run_notes.txt")).toList()) {
                sb.append(Files.readString(p));
            }
        }
        return sb.toString();
    }

    private static void clearNotes(Path outDir) throws IOException {
        try (var files = Files.walk(outDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().equals("run_notes.txt")).toList()) {
                Files.delete(p);
            }
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
