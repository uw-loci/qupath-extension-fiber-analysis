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

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A model stores the segmentation it was trained with, pooled threshold
 * included, and Apply segments new slides with exactly that. A model that
 * re-read the preferences would cut a new slide at whatever its own tiles'
 * histograms said -- different from training, and different tile to tile.
 */
class SegmentationSpecTest {

    private static SegmentationSpec otsu() {
        return new SegmentationSpec("Raw intensity", "otsu", 128, 0.1, null, "frangi", 1.0, 4.0, 0.5, 2.0, true, 12.5);
    }

    @Test
    void storedSettingsSurviveTheModelMetadataRoundTrip() {
        SegmentationSpec pooled = otsu().withPooledThreshold(0.0762);
        // As the model manager writes and reads metadata.json.
        var gson = new GsonBuilder()
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                .create();
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("segmentation", pooled.toMap());
        @SuppressWarnings("unchecked")
        Map<String, Object> back = gson.fromJson(gson.toJson(geometry), Map.class);
        SegmentationSpec restored = SegmentationSpec.fromMap((Map<?, ?>) back.get("segmentation"));
        assertThat(restored).isEqualTo(pooled);
        assertThat(restored.hash()).isEqualTo(pooled.hash());
    }

    @Test
    void poolingChangesOnlyTheThreshold() {
        SegmentationSpec s = otsu();
        SegmentationSpec p = s.withPooledThreshold(0.25);
        assertThat(p.thresholdMethod()).isEqualTo("project_otsu");
        assertThat(p.projectThresholdNorm()).isEqualTo(0.25);
        assertThat(p.hasGlobalThreshold()).isTrue();
        assertThat(p.toInputs(0.5)).containsEntry("project_threshold_norm", 0.25);
        Map<String, Object> a = new LinkedHashMap<>(s.toMap());
        Map<String, Object> b = new LinkedHashMap<>(p.toMap());
        for (String k : new String[] {"threshold_method", "project_threshold_norm"}) {
            a.remove(k);
            b.remove(k);
        }
        assertThat(b).isEqualTo(a);
        assertThat(p.hash()).isNotEqualTo(s.hash());
    }

    @Test
    void aPerRegionMethodIsDescribedAsPooledNotAsPerTile() {
        assertThat(otsu().thresholdDescription()).contains("pooled");
        assertThat(otsu().thresholdDescription()).doesNotContain("per tile");
    }

    @Test
    void anIncompleteStoredBlockIsRefusedNotGuessed() {
        Map<String, Object> m = new LinkedHashMap<>(otsu().toMap());
        m.remove("sigma_max_um");
        assertThatThrownBy(() -> SegmentationSpec.fromMap(m))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sigma_max_um");
    }

    @Test
    void aRidgeModelTrainedBeforeTheFixedResponseScaleIsRefused() {
        Map<String, Object> m = new LinkedHashMap<>(otsu().toMap());
        assertThat(m).containsEntry("response_scale", "absolute");
        assertThat(SegmentationSpec.staleScaleProblem(m)).isNull();
        m.remove("response_scale");
        assertThat(SegmentationSpec.staleScaleProblem(m)).contains("Train the model again");
        // Without a ridge filter the scale never changed.
        m.put("ridge_filter", "none");
        assertThat(SegmentationSpec.staleScaleProblem(m)).isNull();
    }

    @Test
    void theRidgeResponseCutoffIsStoredAndOldModelsReadTheDefault() {
        var manual = new SegmentationSpec("Raw intensity", "manual", 128, 0.25, null, "sato", 1, 4, 1, 2, false, 0);
        var back = SegmentationSpec.fromMap(manual.toMap());
        assertThat(back.manualResponseThreshold()).isEqualTo(0.25);
        assertThat(back.thresholdDescription()).isEqualTo("manual (ridge response 0.250)");
        Map<String, Object> old = new LinkedHashMap<>(manual.toMap());
        old.remove("manual_response_threshold");
        assertThat(SegmentationSpec.fromMap(old).manualResponseThreshold()).isEqualTo(0.1);
        // The cutoff is part of the fingerprint only where it is used.
        var other = new SegmentationSpec("Raw intensity", "manual", 128, 0.5, null, "sato", 1, 4, 1, 2, false, 0);
        assertThat(other.hash()).isNotEqualTo(manual.hash());
        assertThat(new SegmentationSpec("Raw intensity", "otsu", 128, 0.5, null, "sato", 1, 4, 1, 2, false, 0).hash())
                .isEqualTo(new SegmentationSpec("Raw intensity", "otsu", 128, 0.25, null, "sato", 1, 4, 1, 2, false, 0)
                        .hash());
    }
}
