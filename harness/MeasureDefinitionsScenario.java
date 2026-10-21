/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import com.google.gson.JsonElement;
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
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;
import qupath.ext.fiberanalysis.analysis.FiberCalibrationRunner;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;

/**
 * fiberlib 0.4.0's measurement definitions on a real annotation run: gap
 * widths, GLCM on fiber pairs only, the zone-fitted (and, tiled, pooled)
 * threshold, and per-window HDM over zone pixels.
 *
 * <p>Args: {@code <projectDir> <outDir> <envBaseDir>}. Run once untiled and
 * once with {@code -Dfiber.tileBudgetPx=1000000} in JDK_JAVA_OPTIONS to force
 * tiling; each run writes {@code summary.json} for comparing the two.
 */
public final class MeasureDefinitionsScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Path envBase = Path.of(args[2]);
        Files.createDirectories(outDir);
        try {
            run(projectDir, outDir, envBase);
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
        startFx();
        onFx(QuPathGUI::createHiddenInstance);
        note("tile budget: " + System.getProperty("fiber.tileBudgetPx", "default"));
        Project<BufferedImage> project =
                ProjectIO.loadProject(projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        FiberAnalysisPreferences.installPreferences();
        FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
        FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
        FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity");
        FiberAnalysisPreferences.thresholdMethodProperty().set("Otsu");
        FiberAnalysisPreferences.invertIntensityProperty().set(false);
        FiberAnalysisPreferences.windowEnabledProperty().set(true);
        FiberAnalysisPreferences.windowSizeUmProperty().set(100.0);
        FiberAnalysisPreferences.windowOverlapPercentProperty().set(50);
        FiberAnalysisPreferences.windowObjectsProperty().set(false);
        FiberAnalysisPreferences.collagenObjectsProperty().set(false);
        FiberAnalysisPreferences.jsonSidecarProperty().set(true);
        FiberAnalysisPreferences.morphEnabledProperty().set(true);
        FiberAnalysisPreferences.gapAnalysisProperty().set(true);
        FiberAnalysisPreferences.hdmProperty().set(true);
        FiberAnalysisPreferences.straightnessEnabledProperty().set(true);
        FiberAnalysisPreferences.textureEnabledProperty().set(true);
        FiberAnalysisPreferences.outputDirProperty().set(outDir.toString());
        var params = FiberAnalysisParams.fromPreferences();

        var entry = project.getImageList().get(0);
        var data = entry.readImageData();
        List<PathObject> anns = new ArrayList<>(data.getHierarchy().getAnnotationObjects());
        note("image " + entry.getImageName() + ", " + anns.size() + " annotation(s), zone mode " + params.zoneMode());
        long t0 = System.currentTimeMillis();
        Thread worker = onFx(() -> new FiberAnalysisWorkflow(null).runForAnnotations(params, anns, data, null));
        if (worker != null) worker.join();
        note(String.format("analysis took %.1f s", (System.currentTimeMillis() - t0) / 1000.0));

        List<Path> results;
        try (Stream<Path> s = Files.walk(outDir)) {
            results = s.filter(p -> p.getFileName().toString().equals("results.json")
                            && !p.toString().contains("/tiles/"))
                    .sorted()
                    .toList();
        }
        check(results.size() == anns.size(), "one results.json per annotation (" + results.size() + ")");
        JsonObject summary = new JsonObject();
        java.util.Set<Double> cuts = new java.util.TreeSet<>();
        for (Path r : results) {
            String ann = r.getParent().getFileName().toString();
            JsonObject res = JsonParser.parseString(Files.readString(r)).getAsJsonObject();
            // A tiled run writes the merged scalars flattened under "summary".
            boolean tiled = res.has("summary");
            JsonObject flat = tiled ? res.getAsJsonObject("summary") : res;
            JsonObject morph = tiled
                    ? flat
                    : (res.has("morphometrics") ? res.getAsJsonObject("morphometrics") : new JsonObject());
            String mp = tiled ? "morphometrics." : "";
            double gapMean = num(morph.get(mp + "gap_mean_um"));
            double gapMax = num(morph.get(mp + "gap_max_um"));
            double hdm = num(morph.get(mp + "hdm_coverage"));
            double fiberInZone = num(flat.get("fiber_in_zone_pixels"));
            double zonePx = num(flat.get("zone_pixels"));
            check(Math.abs(hdm - fiberInZone / zonePx) < 1e-9, ann + ": HDM is fiber in zone / zone pixels");
            double cut = tiled
                    ? num(flat.get("parameters.project_threshold_norm"))
                    : num(res.getAsJsonObject("parameters").get("project_threshold_norm"));
            cuts.add(cut);
            note(String.format(
                    "%s: gap width mean %.2f um, max %.2f um; HDM %.4f; fiber in zone %.0f of %.0f px",
                    ann, gapMean, gapMax, hdm, fiberInZone, zonePx));
            check(Double.isFinite(gapMean) && gapMean > 0 && gapMax >= gapMean,
                    ann + ": gap widths are reported (max >= mean > 0)");

            Path windows = r.getParent().resolve("windows.json");
            int n = 0, interiorEqual = 0, edgeAbove = 0, below = 0, glcmFinite = 0, glcmNaN = 0;
            if (Files.exists(windows)) {
                JsonObject wj = JsonParser.parseString(Files.readString(windows)).getAsJsonObject();
                for (JsonElement e : wj.getAsJsonArray("windows")) {
                    JsonObject w = e.getAsJsonObject();
                    if (!w.has("included") || !w.get("included").getAsBoolean()) continue;
                    double h = num(w.get("hdm"));
                    double cov = num(w.get("fiber_coverage_percent"));
                    if (!Double.isFinite(h) || !Double.isFinite(cov)) continue;
                    n++;
                    if (Math.abs(h - cov / 100.0) < 1e-6) interiorEqual++;
                    else if (h > cov / 100.0) edgeAbove++;
                    else below++;
                    if (w.has("texture")) {
                        double c = num(w.getAsJsonObject("texture").get("contrast"));
                        if (Double.isFinite(c)) glcmFinite++;
                        else glcmNaN++;
                    }
                }
            }
            note(String.format(
                    "%s: %d windows; HDM = coverage/100 in %d, above it (zone edge) in %d, below in %d;"
                            + " GLCM contrast finite in %d, NaN in %d",
                    ann, n, interiorEqual, edgeAbove, below, glcmFinite, glcmNaN));
            check(n > 0 && below == 0, ann + ": per-window HDM is never below coverage / 100");
            check(glcmFinite > 0, ann + ": GLCM contrast is computed on fiber pairs");

            JsonObject a = new JsonObject();
            a.addProperty("gap_mean_um", gapMean);
            a.addProperty("gap_max_um", gapMax);
            a.addProperty("hdm_coverage", hdm);
            a.addProperty("fiber_in_zone_pixels", fiberInZone);
            a.addProperty("zone_pixels", zonePx);
            a.addProperty("windows", n);
            a.addProperty("hdm_edge_windows", edgeAbove);
            summary.add(ann, a);
        }
        Path thresholdJson;
        try (Stream<Path> s = Files.walk(outDir)) {
            thresholdJson = s.filter(p -> p.getFileName().toString().equals("threshold.json"))
                    .findFirst()
                    .orElse(null);
        }
        double runCut = thresholdJson == null
                ? Double.NaN
                : JsonParser.parseString(Files.readString(thresholdJson))
                        .getAsJsonObject()
                        .get("threshold_normalised")
                        .getAsDouble();
        note("run-wide threshold " + runCut + "; annotations cut at " + cuts);
        check(cuts.size() == 1 && Math.abs(cuts.iterator().next() - runCut) < 1e-12,
                "every annotation cuts at the one run-wide threshold recorded in threshold.json");
        summary.addProperty("run_threshold", runCut);

        if (System.getProperty("fiber.harness.calibrate") != null) {
            var cfg = new FiberCalibrationRunner.CalibrationConfig();
            cfg.calibrationName = "harness";
            cfg.sampleSize = 0;
            cfg.segChannel = params.internalChannel();
            cfg.ridgeFilter = params.ridgeFilter();
            cfg.borderZoneUm = params.borderZoneUm();
            cfg.zoneMode = params.zoneMode();
            cfg.analysisDownsample = params.analysisDownsample();
            var cal = FiberCalibrationRunner.run(project, cfg, null);
            JsonObject calJson = JsonParser.parseString(Files.readString(cal.calibrationFile)).getAsJsonObject();
            JsonObject calParams = calJson.getAsJsonObject("calibration_params");
            note(String.format(
                    "calibration: %.5f from %d sample(s); params %s",
                    cal.thresholdNormalised, cal.regionsUsed, calParams));
            check(Math.abs(cal.thresholdNormalised - runCut) / runCut < 0.05,
                    "a calibration on the same annotations lands within 5% of the run-wide threshold");
            check(calParams.has("zone_mode") && calParams.has("analysis_downsample") && calParams.has("pixel_sizes_um")
                            && !calParams.has("median_pixel_size_um_used"),
                    "the calibration records the zone, downsample and per-image pixel sizes it fitted with");
        }

        Files.writeString(outDir.resolve("summary.json"), summary.toString());
        note("summary: " + outDir.resolve("summary.json"));
    }

    private static double num(JsonElement e) {
        return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
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
