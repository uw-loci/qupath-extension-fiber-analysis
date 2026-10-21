/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.application.Platform;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisBatchDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;
import qupath.ext.fiberanalysis.analysis.FiberCalibrationRunner;
import qupath.ext.fiberanalysis.analysis.PixelSizePrompt;
import qupath.ext.fiberanalysis.analysis.ThresholdIdentity;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

/**
 * A batch over images of different pixel sizes: analysed at one common size their results compare;
 * at their own sizes texture is left out (its pixel offsets differ) with the reason recorded, and
 * the project check names the two pixel sizes. Calibration takes the same choice.
 *
 * <p>Args: {@code <projectDir> <outDir> <envBaseDir>}. Use a copy of a project: the scenario adds an
 * image entry with a doubled pixel size.
 */
public final class BatchPixelSizeScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        try {
            run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        } catch (Throwable t) {
            t.printStackTrace();
            check(false, "the scenario ran without an exception (" + t + ")");
        }
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(Path projectDir, Path outDir, Path envBase) throws Exception {
        Files.createDirectories(outDir);
        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project =
                ProjectIO.loadProject(projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> {
            qupath.setProject(project);
            return null;
        });
        FiberAnalysisPreferences.installPreferences();
        FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
        FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
        FiberAnalysisPreferences.useImagePixelSizeProperty().set(true);
        FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity");
        FiberAnalysisPreferences.thresholdMethodProperty().set("Otsu");
        FiberAnalysisPreferences.ridgeFilterProperty().set("None");
        FiberAnalysisPreferences.searchAreaProperty().set("all");
        FiberAnalysisPreferences.windowEnabledProperty().set(true);
        FiberAnalysisPreferences.windowSizeUmProperty().set(100.0);
        FiberAnalysisPreferences.windowOverlapPercentProperty().set(0);
        FiberAnalysisPreferences.windowObjectsProperty().set(false);
        FiberAnalysisPreferences.collagenObjectsProperty().set(false);
        FiberAnalysisPreferences.textureEnabledProperty().set(true);
        FiberAnalysisPreferences.glcmDistancesPxProperty().set("0.1,0.2,0.3");
        FiberAnalysisPreferences.jsonSidecarProperty().set(true);

        ProjectImageEntry<BufferedImage> fine = project.getImageList().get(0);
        double px = fine.readImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        // The same image declared at twice the pixel size: a coarser scan of the same slide.
        ProjectImageEntry<BufferedImage> coarse = project.addImage(fine.getServerBuilder());
        coarse.setImageName("coarse copy");
        var data = fine.readImageData();
        data.updateServerMetadata(new ImageServerMetadata.Builder(data.getServer().getMetadata())
                .pixelSizeMicrons(2 * px, 2 * px)
                .build());
        coarse.saveImageData(data);
        project.syncChanges();
        note(String.format("fine %.4f um/px, coarse %.4f um/px", px, 2 * px));
        List<ProjectImageEntry<BufferedImage>> both = List.of(fine, coarse);

        // ---- 1. a common pixel size ----------------------------------------------
        FiberAnalysisPreferences.outputDirProperty().set(outDir.resolve("common").toString());
        List<String> asked = new ArrayList<>();
        Thread batch = FiberAnalysisBatchDialog.runBatch(
                qupath, null, FiberAnalysisParams.fromPreferences(), both, (check, ownNote) -> {
                    asked.add(PixelSizePromptText.of(check, ownNote));
                    return PixelSizePrompt.Choice.COMMON;
                });
        batch.join();
        check(asked.size() == 1, "the batch asks once when the pixel sizes differ");
        if (!asked.isEmpty()) note("asked:\n" + asked.get(0));
        check(!asked.isEmpty() && asked.get(0).contains("leaves texture out"),
                "the question says texture would be left out at each image's own size");
        List<JsonObject> common = thresholdRecords(outDir.resolve("common"));
        check(common.size() == 2, "both images ran (" + common.size() + " threshold records)");
        for (JsonObject t : common) {
            note("identity: " + t.get("identity").getAsString());
            check(Math.abs(t.get("analysed_pixel_um").getAsDouble() - 2 * px) < 1e-9,
                    "analysed at the common pixel size " + 2 * px);
        }
        check(common.size() == 2
                        && common.get(0).getAsJsonObject("settings").get("analysis_downsample").getAsDouble()
                                != common.get(1).getAsJsonObject("settings").get("analysis_downsample").getAsDouble(),
                "the finer image gets a larger downsample");
        List<String> notes = runNotes(outDir.resolve("common"));
        check(notes.size() == 2 && notes.stream().allMatch(n -> n.contains("Analysed at the common pixel size")),
                "each run records the common-size decision");
        var labels = List.of(
                fine.getMetadata().get(ThresholdIdentity.META_ANNOTATIONS),
                coarse.getMetadata().get(ThresholdIdentity.META_ANNOTATIONS));
        check(ThresholdIdentity.parse(labels.get(0)).comparableWith(ThresholdIdentity.parse(labels.get(1))),
                "the two images' records compare");

        // ---- 2. each at its own size ----------------------------------------------
        FiberAnalysisPreferences.outputDirProperty().set(outDir.resolve("own").toString());
        batch = FiberAnalysisBatchDialog.runBatch(
                qupath, null, FiberAnalysisParams.fromPreferences(), both, (check, ownNote) -> PixelSizePrompt.Choice.OWN);
        batch.join();
        List<String> ownNotes = runNotes(outDir.resolve("own"));
        check(ownNotes.size() == 2 && ownNotes.stream().allMatch(n -> n.contains("Texture was left out")),
                "texture is left out, and each run says why");
        if (!ownNotes.isEmpty()) note("note: " + ownNotes.get(0).replace('\n', ' '));
        long textureWindows;
        try (Stream<Path> s = Files.walk(outDir.resolve("own"))) {
            textureWindows = s.filter(p -> p.getFileName().toString().equals("windows.json"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("\"texture\"");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        }
        check(textureWindows == 0, "no window carries texture values");
        var own = List.of(
                ThresholdIdentity.parse(fine.getMetadata().get(ThresholdIdentity.META_ANNOTATIONS)),
                ThresholdIdentity.parse(coarse.getMetadata().get(ThresholdIdentity.META_ANNOTATIONS)));
        check(!own.get(0).comparableWith(own.get(1)), "the two images' records no longer compare");

        // ---- 3. calibration -------------------------------------------------------
        var cfg = new FiberCalibrationRunner.CalibrationConfig();
        cfg.calibrationName = "pixel-sizes";
        cfg.sampleSize = 0;
        cfg.onMixedPixelSizes = (check, note) -> PixelSizePrompt.Choice.COMMON;
        var cal = FiberCalibrationRunner.run(project, cfg, null);
        JsonObject calParams = JsonParser.parseString(Files.readString(cal.calibrationFile))
                .getAsJsonObject()
                .getAsJsonObject("calibration_params");
        note("calibration params: " + calParams);
        check(calParams.has("common_pixel_um") && Math.abs(calParams.get("common_pixel_um").getAsDouble() - 2 * px) < 1e-9,
                "a calibration over both images can fit at the common size, and records it");
    }

    private static List<JsonObject> thresholdRecords(Path root) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(p -> p.getFileName().toString().equals("threshold.json")).sorted().toList())
                out.add(JsonParser.parseString(Files.readString(p)).getAsJsonObject());
        }
        return out;
    }

    private static List<String> runNotes(Path root) throws Exception {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(p -> p.getFileName().toString().equals("run_notes.txt")).sorted().toList())
                out.add(Files.readString(p));
        }
        return out;
    }

    /** The prompt's text without showing it. */
    private static final class PixelSizePromptText {
        static String of(qupath.ext.fiberanalysis.analysis.PixelSizeCheck check, String ownNote) {
            return check.describe() + (ownNote == null ? "" : "\n" + ownNote);
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
