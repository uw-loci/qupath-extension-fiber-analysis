/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.classifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a saved classifier, and deciding whether it may score this data. */
class WindowClassifierModelManagerTest {

    private static final String META = """
            {
             "schema_version": 1,
             "created_utc": "2026-10-13T12:00:00Z",
             "model_kind": "hist_gradient_boosting",
             "class_names": ["Dense collagen", "Loose collagen"],
             "feature_names": ["coverage_fraction", "order_parameter", "width_mean_um"],
             "geometry": {
              "window_um": 50.0,
              "pixel_size_um": 0.5,
              "stride_px": 25,
              "core_px": 25,
              "segmentation_hash": "abc123"
             },
             "training": {"balanced_accuracy": 0.871, "n_slides": 5},
             "verdict": "OK"
            }
            """;

    private static Path writeModel(Path dir, String metaJson) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), metaJson);
        Files.writeString(dir.resolve("model.joblib"), "not really a pickle");
        return dir;
    }

    private static Map<String, Object> geometry(double pixelSizeUm, Object strideP) {
        return Map.of(
                "window_um",
                50.0,
                "pixel_size_um",
                pixelSizeUm,
                "stride_px",
                strideP,
                "core_px",
                25,
                "segmentation_hash",
                "abc123");
    }

    private static final List<String> FEATURES = List.of("coverage_fraction", "order_parameter", "width_mean_um");

    @Test
    void readingAModelRecoversItsClassesFeaturesAndVerdict(@TempDir Path tmp) throws IOException {
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("colon-v1"), META), true);
        assertThat(info.id()).isEqualTo("colon-v1");
        assertThat(info.classNames()).containsExactly("Dense collagen", "Loose collagen");
        assertThat(info.featureNames()).isEqualTo(FEATURES);
        assertThat(info.verdict()).isEqualTo("OK");
        assertThat(info.provisional()).isFalse();
        assertThat(info.projectLocal()).isTrue();
        assertThat(info.label()).contains("colon-v1", "2 classes", "0.871", "OK");
    }

    @Test
    void aMetadataFileWithoutItsModelIsNotAModel(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("half");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), META);
        assertThatThrownBy(() -> WindowClassifierModelManager.read(dir, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("model.joblib");
    }

    @Test
    void anArbitraryFolderIsRejectedRatherThanPartlyRead(@TempDir Path tmp) {
        assertThatThrownBy(() -> WindowClassifierModelManager.read(tmp, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a saved fiber window classifier");
    }

    @Test
    void aWholeNumberInTheMetadataDoesNotReadAsADifferentNumber(@TempDir Path tmp) throws IOException {
        // Gson's default turns every JSON number into a double, so a metadata
        // stride of 25 would arrive as 25.0 and could be compared against an
        // int 25 and called a mismatch. LONG_OR_DOUBLE is why it is not.
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("m"), META), true);
        assertThat(info.geometry().get("stride_px")).isInstanceOf(Number.class);
        assertThat(WindowClassifierModelManager.checkCompatibility(info, FEATURES, geometry(0.5, 25)))
                .isEmpty();
        assertThat(WindowClassifierModelManager.checkCompatibility(info, FEATURES, geometry(0.5, 25.0)))
                .isEmpty();
    }

    @Test
    void amissingFeatureIsNamedNotJustCounted(@TempDir Path tmp) throws IOException {
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("m"), META), true);
        var problems = WindowClassifierModelManager.checkCompatibility(
                info, List.of("coverage_fraction", "order_parameter"), geometry(0.5, 25));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("missing", "width_mean_um");
    }

    @Test
    void theSameFeaturesInADifferentOrderAreStillAMismatch(@TempDir Path tmp) throws IOException {
        // A model scores columns by position, so a reordering reads one
        // measurement as another and nothing downstream could notice.
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("m"), META), true);
        var problems = WindowClassifierModelManager.checkCompatibility(
                info, List.of("order_parameter", "coverage_fraction", "width_mean_um"), geometry(0.5, 25));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("different order", "by position");
    }

    @Test
    void aForestsMissingnessIndicatorsAreNotTreatedAsMeasuredFeatures(@TempDir Path tmp) throws IOException {
        String forest = META.replace(
                "\"feature_names\": [\"coverage_fraction\", \"order_parameter\", \"width_mean_um\"]",
                "\"feature_names\": [\"coverage_fraction\", \"order_parameter\", \"width_mean_um\","
                        + " \"coverage_fraction_isnan\", \"order_parameter_isnan\", \"width_mean_um_isnan\"]");
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("rf"), forest), true);
        assertThat(WindowClassifierModelManager.checkCompatibility(info, FEATURES, geometry(0.5, 25)))
                .isEmpty();
    }

    @Test
    void pixelSizeIsAllowedToDriftTwoPercentAndNoMore(@TempDir Path tmp) throws IOException {
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("m"), META), true);
        assertThat(WindowClassifierModelManager.checkCompatibility(info, FEATURES, geometry(0.505, 25)))
                .isEmpty();
        var problems = WindowClassifierModelManager.checkCompatibility(info, FEATURES, geometry(1.0, 25));
        assertThat(problems).hasSize(1);
        // The message has to say WHY matching microns is not enough, because
        // "window size matches" is exactly what a user will reply.
        assertThat(problems.get(0)).contains("0.5000", "1.0000", "box sizes in PIXELS");
    }

    @Test
    void differentSegmentationSettingsMeanDifferentMasks(@TempDir Path tmp) throws IOException {
        var info = WindowClassifierModelManager.read(writeModel(tmp.resolve("m"), META), true);
        var geom = new java.util.LinkedHashMap<String, Object>(geometry(0.5, 25));
        geom.put("segmentation_hash", "def456");
        var problems = WindowClassifierModelManager.checkCompatibility(info, FEATURES, geom);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("abc123", "def456", "not the same masks");
    }

    @Test
    void aRefusedModelIsProvisionalWhereverItTravels(@TempDir Path tmp) throws IOException {
        var refused = WindowClassifierModelManager.read(
                writeModel(tmp.resolve("bad"), META.replace("\"OK\"", "\"REFUSE\"")), true);
        assertThat(refused.provisional()).isTrue();
        var blocked = WindowClassifierModelManager.read(
                writeModel(tmp.resolve("worse"), META.replace("\"OK\"", "\"BLOCK\"")), true);
        assertThat(blocked.provisional()).isTrue();
        var warned = WindowClassifierModelManager.read(
                writeModel(tmp.resolve("meh"), META.replace("\"OK\"", "\"WARN\"")), true);
        assertThat(warned.provisional()).isFalse();
    }

    @Test
    void anIdBecomesAFolderNameWithoutEscapingTheFolder() {
        assertThat(WindowClassifierModelManager.sanitizeId("colon v1")).isEqualTo("colon_v1");
        assertThat(WindowClassifierModelManager.sanitizeId("../../etc")).isEqualTo(".._.._etc");
        assertThat(WindowClassifierModelManager.sanitizeId("  ")).isEqualTo("model");
        assertThat(WindowClassifierModelManager.sanitizeId(null)).isEqualTo("model");
        assertThat(WindowClassifierModelManager.sanitizeId("a".repeat(100))).hasSize(60);
    }

    @Test
    void savingIntoAProjectlessProjectIsRefusedWithSomewhereElseToPut(@TempDir Path tmp) {
        assertThatThrownBy(() -> WindowClassifierModelManager.resolveForWrite(null, "m", true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("user folder");
        assertThat(WindowClassifierModelManager.userRoot()).isNotNull();
    }
}
