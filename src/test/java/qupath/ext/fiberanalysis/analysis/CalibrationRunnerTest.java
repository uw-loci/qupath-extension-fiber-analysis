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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** The calibration fits the run it is made for, stops on cancel, and records what a rerun needs. */
class CalibrationRunnerTest {

    private static ImageData<BufferedImage> image(double pixelUm) {
        var server = new WrappedBufferedImageServer("img", new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB));
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                .pixelSizeMicrons(pixelUm, pixelUm)
                .build());
        return new ImageData<>(server);
    }

    private static PathObject box() {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(64, 64, 96, 96, ImagePlane.getDefaultPlane()));
    }

    private static List<FiberCalibrationRunner.Sample> pool() {
        ImageData<BufferedImage> data = image(0.25);
        PathObject ann = box();
        data.getHierarchy().addObject(ann);
        return List.of(new FiberCalibrationRunner.Sample("img", data, ann));
    }

    private static FiberCalibrationRunner.CalibrationConfig config() {
        FiberCalibrationRunner.CalibrationConfig cfg =
                FiberCalibrationRunner.CalibrationConfig.fromRun(TestParams.with(Map.of(
                        "zoneMode", "inside",
                        "analysisDownsample", 2.0,
                        "borderZoneUm", 20.0,
                        "useImagePixelSize", true,
                        "internalChannel", "Raw intensity",
                        "ridgeFilter", "None",
                        "sigmaMinUm", 0.5,
                        "sigmaMaxUm", 3.0,
                        "sigmaStepUm", 0.5)));
        cfg.calibrationName = "test";
        return cfg;
    }

    private static final PooledThreshold.Result FITTED = new PooledThreshold.Result(0.1, "otsu", 1, 4, 1000, List.of());

    private static FiberCalibrationRunner.ProgressCallback cancelWhen(AtomicBoolean flag) {
        return new FiberCalibrationRunner.ProgressCallback() {
            @Override
            public void update(String message, int completed, int total) {}

            @Override
            public boolean isCancelled() {
                return flag.get();
            }
        };
    }

    @Test
    void theConfigTakesZoneDownsampleAndSigmasFromTheRun() {
        FiberCalibrationRunner.CalibrationConfig cfg = config();
        assertThat(cfg.zoneMode).isEqualTo("inside");
        assertThat(cfg.analysisDownsample).isEqualTo(2.0);
        assertThat(cfg.borderZoneUm).isEqualTo(20.0);
        assertThat(cfg.sigmaMinUm).isEqualTo(0.5);
        assertThat(cfg.sigmaMaxUm).isEqualTo(3.0);
        assertThat(cfg.sigmaStepUm).isEqualTo(0.5);
    }

    @Test
    void theSavedCalibrationRecordsAndRebuildsEverySettingTheRerunNeeds(@TempDir Path proj) throws Exception {
        FiberCalibrationRunner.CalibrationConfig cfg = config();
        AtomicInteger calls = new AtomicInteger();
        var result = FiberCalibrationRunner.fitAndSave(
                proj, null, pool(), cfg, cancelWhen(new AtomicBoolean()), (regions, max, work, msg, cancelled) -> {
                    calls.incrementAndGet();
                    assertThat(regions.get(0).downsample()).isEqualTo(2.0);
                    return FITTED;
                });
        assertThat(calls).hasValue(1);
        assertThat(result.calibrationFile).exists();

        String txt = Files.readString(proj.resolve("fiber-analysis/calibration_test_params.txt"));
        assertThat(txt).contains("zoneMode = inside", "analysisDownsample = 2.0", "pixelSizes = own");

        FiberCalibrationRunner.CalibrationConfig back =
                FiberCalibrationRunner.CalibrationConfig.fromCalibrationFile(result.calibrationFile);
        assertThat(back.zoneMode).isEqualTo("inside");
        assertThat(back.analysisDownsample).isEqualTo(2.0);
        assertThat(back.borderZoneUm).isEqualTo(20.0);
        assertThat(back.sigmaMinUm).isEqualTo(0.5);
        assertThat(back.useImagePixelSize).isTrue();

        // And the run made from it is not refused.
        assertThat(CalibrationCheck.mismatch(
                        "test",
                        proj,
                        new CalibrationCheck.Target(
                                "Raw intensity", "None", false, 1.0, 0.5, 3.0, 0.5, "inside", 20.0, 2.0, 0.5)))
                .isNull();
    }

    @Test
    void theRerunReproducesTheCommonPixelSizeChoice(@TempDir Path proj) throws Exception {
        Path f = Files.createDirectories(proj.resolve("fiber-analysis")).resolve("calibration_c.json");
        Files.writeString(
                f,
                "{\"name\": \"c\", \"calibration_params\": {\"zone_mode\": \"both\", \"analysis_downsample\": 4.0,"
                        + " \"common_pixel_um\": 1.0}}");
        var check = PixelSizeCheck.of(
                List.of(new PixelSizeCheck.Image("a", 0.25), new PixelSizeCheck.Image("b", 0.5)), 2.0);
        var cfg = FiberCalibrationRunner.CalibrationConfig.fromCalibrationFile(f);
        assertThat(cfg.zoneMode).isEqualTo("both");
        assertThat(cfg.analysisDownsample).isEqualTo(4.0);
        assertThat(cfg.onMixedPixelSizes.apply(check, null)).isEqualTo(PixelSizePrompt.Choice.COMMON);

        Files.writeString(f, "{\"calibration_params\": {\"mixed_pixel_sizes\": true}}");
        assertThat(FiberCalibrationRunner.CalibrationConfig.fromCalibrationFile(f)
                        .onMixedPixelSizes
                        .apply(check, null))
                .isEqualTo(PixelSizePrompt.Choice.OWN);

        // A calibration made on images of one size cannot be reproduced on mixed ones.
        Files.writeString(f, "{\"calibration_params\": {}}");
        assertThat(FiberCalibrationRunner.CalibrationConfig.fromCalibrationFile(f)
                        .onMixedPixelSizes
                        .apply(check, null))
                .isEqualTo(PixelSizePrompt.Choice.CANCEL);
    }

    @Test
    void theRerunTemplateReadsTheWholeCalibrationFile() throws Exception {
        String template;
        try (var in = FiberCalibrationRunner.class.getResourceAsStream(
                "/qupath/ext/fiberanalysis/rerun/rerun_calibration.groovy.template")) {
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(template).contains("CalibrationConfig.fromCalibrationFile(");
    }

    @Test
    void aCancelBeforePoolingFitsAndWritesNothing(@TempDir Path proj) {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> FiberCalibrationRunner.fitAndSave(
                        proj, null, pool(), config(), cancelWhen(new AtomicBoolean(true)), (r, m, w, p, c) -> {
                            calls.incrementAndGet();
                            return FITTED;
                        }))
                .isInstanceOf(CancellationException.class);
        assertThat(calls).hasValue(0);
        assertThat(proj.resolve("fiber-analysis/calibration_test.json")).doesNotExist();
    }

    @Test
    void aCancelDuringPoolingWritesNothingEvenIfTheFitFinishes(@TempDir Path proj) throws Exception {
        Path calFile = proj.resolve("fiber-analysis/calibration_test.json");
        AtomicBoolean cancel = new AtomicBoolean();
        assertThatThrownBy(() -> FiberCalibrationRunner.fitAndSave(
                        proj, null, pool(), config(), cancelWhen(cancel), (r, m, w, p, c) -> {
                            cancel.set(true);
                            return FITTED;
                        }))
                .isInstanceOf(CancellationException.class);
        assertThat(calFile).doesNotExist();
        assertThat(proj.resolve("fiber-analysis/calibration_test_params.txt")).doesNotExist();
    }

    @Test
    void poolingStopsBeforePythonOnceCanceled(@TempDir Path dir) {
        ImageData<BufferedImage> data = image(0.5);
        PathObject ann = box();
        var region =
                new PooledThreshold.ZoneRegion(data.getServer(), ann.getROI(), new int[] {0, 0, 256, 256}, 1.0, 0.5);
        AtomicBoolean cancel = new AtomicBoolean();
        assertThatThrownBy(() -> PooledThreshold.poolZones(
                        List.of(region),
                        "whole",
                        0.0,
                        FiberAnalysisWorkflow.segmentationSettings(TestParams.with(Map.of(
                                "internalChannel", "Raw intensity",
                                "ridgeFilter", "None",
                                "thresholdMethod", "Otsu"))),
                        dir.resolve("work"),
                        16,
                        msg -> cancel.set(true),
                        cancel::get))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void thePreviewMarkerIsNotPooled() {
        PathObjectHierarchy h = new PathObjectHierarchy();
        PathObject real = box();
        PathObject marker = box();
        marker.setPathClass(PathClass.fromString("Fiber-Preview-Region"));
        h.addObjects(List.of(real, marker));
        assertThat(FiberCalibrationRunner.poolableAnnotations(h, null)).containsExactly(real);
    }

    @Test
    void anExistingCalibrationIsFoundByItsFileName(@TempDir Path proj) throws Exception {
        assertThat(FiberCalibrationRunner.calibrationFile(proj, "tumor stroma"))
                .isEqualTo(proj.resolve("fiber-analysis").resolve("calibration_tumor_stroma.json"));
    }

    @Test
    void theRecordedFileIsValidJson(@TempDir Path proj) throws Exception {
        var result = FiberCalibrationRunner.fitAndSave(
                proj, null, pool(), config(), cancelWhen(new AtomicBoolean()), (r, m, w, p, c) -> FITTED);
        JsonObject cp = new Gson()
                .fromJson(Files.readString(result.calibrationFile), JsonObject.class)
                .getAsJsonObject("calibration_params");
        assertThat(cp.get("zone_mode").getAsString()).isEqualTo("inside");
        assertThat(cp.get("use_image_pixel_size").getAsBoolean()).isTrue();
        assertThat(cp.get("pixel_sizes_um").getAsJsonArray().get(0).getAsDouble())
                .isEqualTo(0.25);
    }
}
