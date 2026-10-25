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
import org.locationtech.jts.geom.Geometry;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Runs one tiled L-shaped annotation, whose bounding box has an empty corner, and writes every
 * window detection with all its measurements to a TSV, so two builds can be compared.
 *
 * <p>Args: {@code <image> <projectDir> <envBaseDir> <out.tsv> [x,y,side;x,y,side]}.
 */
public final class TileSkipScenario {

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
        Path outTsv = Path.of(args[3]);
        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        ImageServer<BufferedImage> src = ImageServers.buildServer(image.toUri().toString());
        ProjectImageEntry<BufferedImage> entry = project.addImage(src.getBuilder());
        entry.setImageName(image.getFileName().toString());
        ImageData<BufferedImage> data = entry.readImageData();
        data.setImageType(ImageData.ImageType.FLUORESCENCE);
        entry.saveImageData(data);
        project.syncChanges();
        onFx(() -> {
            qupath.setProject(project);
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            FiberAnalysisPreferences.windowEnabledProperty().set(true);
            FiberAnalysisPreferences.windowSizeUmProperty().set(50.2);
            FiberAnalysisPreferences.windowOverlapPercentProperty().set(0);
            FiberAnalysisPreferences.windowObjectsProperty().set(true);
            FiberAnalysisPreferences.thresholdMethodProperty().set("Manual");
            FiberAnalysisPreferences.manualThresholdProperty().set(2000);
            FiberAnalysisPreferences.zoneModeProperty().set("whole");
            FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
            FiberAnalysisPreferences.textureEnabledProperty().set(true);
            FiberAnalysisPreferences.glcmDistancesPxProperty().set("2.5,10");
            FiberAnalysisPreferences.collagenObjectsProperty().set(false);
            return null;
        });
        FiberAnalysisParams params = FiberAnalysisParams.fromPreferences();
        // Bounding box 9301 x 7203 px, over the 64 MP tile budget; the top-right block is empty.
        // Optional 5th arg "x,y,side;x,y,side": separate squares, one annotation, so the tiles
        // between them hold no tissue.
        ROI shape = ROIs.createPolygonROI(
                new double[] {611, 9912, 9912, 4611, 4611, 611},
                new double[] {397, 397, 3397, 3397, 7600, 7600},
                ImagePlane.getDefaultPlane());
        if (args.length > 4) {
            Geometry g = null;
            for (String sq : args[4].split(";")) {
                String[] v = sq.split(",");
                double x = Double.parseDouble(v[0]), y = Double.parseDouble(v[1]), side = Double.parseDouble(v[2]);
                var r = ROIs.createRectangleROI(x, y, side, side, ImagePlane.getDefaultPlane()).getGeometry();
                g = g == null ? r : g.union(r);
            }
            shape = GeometryTools.geometryToROI(g, ImagePlane.getDefaultPlane());
        }
        PathObject a = PathObjects.createAnnotationObject(shape, PathClass.fromString("Tissue"));
        data.getHierarchy().addObject(a);
        long t0 = System.nanoTime();
        Thread t = new FiberAnalysisWorkflow(null).runForAnnotations(params, List.of(a), data, qupath);
        if (t != null) t.join();
        onFx(() -> null);
        double s = (System.nanoTime() - t0) / 1e9;
        var dets = new java.util.ArrayList<>(data.getHierarchy().getDetectionObjects());
        dets.sort(java.util.Comparator.comparingDouble((PathObject o) -> o.getROI().getBoundsY())
                .thenComparingDouble(o -> o.getROI().getBoundsX()));
        StringBuilder sb = new StringBuilder();
        for (PathObject d : dets) {
            sb.append((long) d.getROI().getBoundsX()).append('\t').append((long) d.getROI().getBoundsY());
            for (String n : d.getMeasurementList().getNames()) {
                sb.append('\t').append(n).append('=').append(d.getMeasurementList().get(n));
            }
            sb.append('\n');
        }
        Files.writeString(outTsv, sb.toString());
        System.out.printf("[....] %d window detections in %.1f s -> %s%n", dets.size(), s, outTsv);
        check(!dets.isEmpty(), "the tiled L-shaped annotation made window detections");
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
