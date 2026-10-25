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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Annotation runs lay their windows on one grid from the image origin: every window detection of
 * an untiled and a tiled annotation starts on a multiple of the window size.
 *
 * <p>Args: {@code <image> <projectDir> <envBaseDir>}.
 */
public final class WindowGridAlignmentScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
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
        Path envBase = Path.of(args[2]);
        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        ImageServer<BufferedImage> src = ImageServers.buildServer(image.toUri().toString());
        ProjectImageEntry<BufferedImage> entry = project.addImage(src.getBuilder());
        entry.setImageName(image.getFileName().toString());
        ImageData<BufferedImage> data = entry.readImageData();
        data.setImageType(ImageData.ImageType.FLUORESCENCE);
        // Odd offsets on purpose. The large one is over the 64 MP tile budget.
        PathObject small = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(1237, 913, 2011, 1777, ImagePlane.getDefaultPlane()), PathClass.fromString("Tissue"));
        PathObject large = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(611, 397, 9301, 7203, ImagePlane.getDefaultPlane()), PathClass.fromString("Tissue"));
        entry.saveImageData(data);
        project.syncChanges();
        onFx(() -> {
            qupath.setProject(project);
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            FiberAnalysisPreferences.windowEnabledProperty().set(true);
            FiberAnalysisPreferences.windowSizeUmProperty().set(50.2);
            FiberAnalysisPreferences.windowOverlapPercentProperty().set(0);
            FiberAnalysisPreferences.windowObjectsProperty().set(true);
            FiberAnalysisPreferences.minWindowCoveragePercentProperty().set(0.0);
            FiberAnalysisPreferences.thresholdMethodProperty().set("Manual");
            FiberAnalysisPreferences.manualThresholdProperty().set(2000);
            FiberAnalysisPreferences.zoneModeProperty().set("whole");
            FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
            FiberAnalysisPreferences.straightnessEnabledProperty().set(false);
            FiberAnalysisPreferences.morphEnabledProperty().set(false);
            FiberAnalysisPreferences.textureEnabledProperty().set(false);
            FiberAnalysisPreferences.collagenObjectsProperty().set(false);
            return null;
        });
        double px = src.getPixelCalibration().getAveragedPixelSizeMicrons();
        int window = (int) Math.rint(50.2 / px);
        System.out.println("[....] pixel size " + px + " um, window " + window + " px");
        FiberAnalysisParams params = FiberAnalysisParams.fromPreferences();
        for (PathObject ann : List.of(small, large)) {
            ImageData<BufferedImage> d = entry.readImageData();
            PathObject a = PathObjects.createAnnotationObject(ann.getROI(), ann.getPathClass());
            d.getHierarchy().addObject(a);
            long t0 = System.nanoTime();
            Thread t = new FiberAnalysisWorkflow(null).runForAnnotations(params, List.of(a), d, qupath);
            if (t != null) t.join();
            onFx(() -> null);
            var dets = d.getHierarchy().getDetectionObjects();
            long off = dets.stream()
                    .filter(o -> Math.round(o.getROI().getBoundsX()) % window != 0
                            || Math.round(o.getROI().getBoundsY()) % window != 0)
                    .count();
            String which = ann == small ? "untiled annotation" : "tiled annotation";
            System.out.printf("[....] %s: %d window detections in %.1f s%n", which, dets.size(), (System.nanoTime() - t0) / 1e9);
            check(!dets.isEmpty(), which + " made window detections");
            check(off == 0, which + ": every window starts on the image-origin grid of " + window + " px (" + off + " off)");
            boolean covers = dets.stream().anyMatch(o -> o.getROI().getBoundsX() <= a.getROI().getBoundsX())
                    && dets.stream().anyMatch(o -> o.getROI().getBoundsY() <= a.getROI().getBoundsY());
            check(covers, which + ": windows still reach the annotation's top-left edge");
        }
    }

    private static void startFx() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        }
        if (!up.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
