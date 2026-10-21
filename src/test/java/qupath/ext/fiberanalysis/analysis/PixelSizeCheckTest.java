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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Images of a multi-image run analysed at different pixel sizes give numbers that do not compare;
 * the check finds that and the downsample that brings each image to the coarsest size.
 */
class PixelSizeCheckTest {

    private static PixelSizeCheck check(double downsample, double... px) {
        var images = new java.util.ArrayList<PixelSizeCheck.Image>();
        for (int i = 0; i < px.length; i++) images.add(new PixelSizeCheck.Image("img" + i, px[i]));
        return PixelSizeCheck.of(images, downsample);
    }

    @Test
    void sizesWithinTwoPercentAreOneSize() {
        assertThat(check(1, 0.1732, 0.1740, 0.1755).mixed()).isFalse();
        assertThat(check(1, 0.1732, 0.5).mixed()).isTrue();
        assertThat(check(1, 0.1732).mixed()).isFalse();
    }

    @Test
    void everyImageCanBeBroughtToTheCoarsestSize() {
        var c = check(2, 0.25, 0.5);
        assertThat(c.commonPixelUm()).isEqualTo(1.0);
        assertThat(c.downsampleFor(0.25)).isEqualTo(4.0);
        assertThat(c.downsampleFor(0.5)).isEqualTo(2.0);
        assertThat(c.analysedPixelSizes(true)).containsExactly(1.0, 1.0);
        assertThat(c.analysedPixelSizes(false)).containsExactly(0.5, 1.0);
    }

    @Test
    void imagesWithoutAPixelSizeAreNamedNotGuessed() {
        var c = check(1, 0.25, Double.NaN, 0.25);
        assertThat(c.mixed()).isFalse();
        assertThat(c.uncalibrated()).containsExactly("img1");
        assertThat(c.describe()).contains("0.25 um/px: 2 image(s) (img0, img2)").contains("no pixel size: img1");
    }

    @Test
    void glcmOffsetsThatDifferBetweenSizesAreReported() {
        // The defaults: {1, 2} px at 0.1732 um/px but {1} px at 0.5 um/px.
        String problem = PixelSizeCheck.offsetProblem("0.1, 0.2, 0.3", List.of(0.1732, 0.5), "GLCM distances");
        assertThat(problem)
                .isEqualTo("GLCM distances 0.1,0.2,0.3 um become 1,2 px at 0.1732 um/px; 1 px at 0.5 um/px.");
        assertThat(PixelSizeCheck.offsetProblem("0.1, 0.2, 0.3", List.of(0.5, 0.6), "GLCM distances"))
                .isNull();
    }

    @Test
    void theQuestionSaysWhyAndWhatEachChoiceDoes() {
        var c = check(1, 0.1732, 0.3464);
        String m = PixelSizePrompt.message(c, true, "Each at its own size leaves texture out: x.");
        assertThat(m).contains("0.1732 um/px: 1 image(s) (img0)").contains("0.3464 um/px: 1 image(s) (img1)");
        assertThat(m).contains("not comparable").contains("Analyse all at 0.3464 um/px");
        assertThat(m).contains("leaves texture out");
        assertThat(PixelSizePrompt.message(c, false, null)).contains("cannot bring them to one size");
    }

    /** A params record with a type-appropriate value in every component. */
    private static FiberAnalysisParams sampleParams() {
        var comps = FiberAnalysisParams.class.getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int k = 0; k < comps.length; k++) {
            types[k] = comps[k].getType();
            args[k] = types[k] == double.class
                    ? (Object) (k + 0.5)
                    : types[k] == int.class ? (Object) k : types[k] == boolean.class ? (Object) (k % 2 == 0) : "s" + k;
        }
        try {
            return FiberAnalysisParams.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void paramsCopyChangesOnlyTheNamedSetting() {
        var p = sampleParams();
        var q = p.withAnalysisDownsample(3.5).withTextureEnabled(!p.textureEnabled());
        assertThat(q.analysisDownsample()).isEqualTo(3.5);
        assertThat(q.textureEnabled()).isEqualTo(!p.textureEnabled());
        assertThat(q.withAnalysisDownsample(p.analysisDownsample()).withTextureEnabled(p.textureEnabled()))
                .isEqualTo(p);
    }
}
