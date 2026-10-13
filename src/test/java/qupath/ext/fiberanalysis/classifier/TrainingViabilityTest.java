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

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.fiberanalysis.classifier.TrainingViability.ClassTally;
import qupath.ext.fiberanalysis.classifier.TrainingViability.Level;

/** The pre-flight gate: what cannot be learned, refused before the wait. */
class TrainingViabilityTest {

    private static ClassTally healthy(String name) {
        return new ClassTally(name, 400, 8, 5);
    }

    @Test
    void aHealthyTrainingSetPassesWithNothingToSay() {
        var rep = TrainingViability.check(List.of(healthy("Dense"), healthy("Loose")), 5);
        assertThat(rep.level()).isEqualTo(Level.OK);
        assertThat(rep.findings()).isEmpty();
        assertThat(rep.blocked()).isFalse();
        assertThat(rep.summary()).isEqualTo("Training set looks viable.");
    }

    @Test
    void twoSlidesCannotSupportGroupedCrossValidation() {
        var rep = TrainingViability.check(List.of(healthy("Dense"), healthy("Loose")), 2);
        assertThat(rep.blocked()).isTrue();
        assertThat(rep.at(Level.BLOCK)).hasSize(1);
        assertThat(rep.at(Level.BLOCK).get(0).message()).contains("Only 2 slide(s)");
        // The remedy has to say that more regions on the same slide will not
        // help, because that is the natural thing to try next and it is wrong.
        assertThat(rep.at(Level.BLOCK).get(0).remedy()).contains("same slide will not help");
    }

    @Test
    void aClassWithTooFewWindowsIsNamedWithItsCountAndTheThreshold() {
        var rep = TrainingViability.check(List.of(healthy("Dense"), new ClassTally("Rare", 12, 4, 3)), 5);
        assertThat(rep.blocked()).isTrue();
        assertThat(rep.at(Level.BLOCK).get(0).message()).contains("'Rare' has 12 labelled window(s)", "minimum of 30");
    }

    @Test
    void aClassOnOneSlideIsBlockedBecauseNoFoldCouldTestIt() {
        var rep = TrainingViability.check(List.of(healthy("Dense"), new ClassTally("Focal", 300, 6, 1)), 5);
        assertThat(rep.blocked()).isTrue();
        assertThat(rep.at(Level.BLOCK).get(0).message()).contains("'Focal' appears on 1 slide(s)");
        assertThat(rep.at(Level.BLOCK).get(0).remedy()).contains("no examples of it left to test");
    }

    @Test
    void oneHugeAnnotationPerClassWarnsWithoutBlocking() {
        var rep = TrainingViability.check(
                List.of(new ClassTally("Dense", 400, 1, 3), new ClassTally("Loose", 400, 2, 3)), 3);
        assertThat(rep.level()).isEqualTo(Level.WARN);
        assertThat(rep.blocked()).isFalse();
        assertThat(rep.at(Level.WARN)).hasSize(2);
        assertThat(rep.at(Level.WARN).get(0).remedy()).contains("learn that region rather than the class");
    }

    @Test
    void oneClassAloneIsNothingToSeparate() {
        var rep = TrainingViability.check(List.of(healthy("Dense"), new ClassTally("Empty", 0, 0, 0)), 5);
        assertThat(rep.blocked()).isTrue();
        assertThat(rep.at(Level.BLOCK))
                .extracting(TrainingViability.Finding::message)
                .anyMatch(m -> m.contains("Fewer than two classes have any labelled windows"));
    }

    @Test
    void extremeImbalanceWarnsAndNamesTheMetricItForces() {
        var rep = TrainingViability.check(
                List.of(new ClassTally("Common", 10_000, 20, 5), new ClassTally("Rare", 40, 5, 3)), 5);
        assertThat(rep.level()).isEqualTo(Level.WARN);
        assertThat(rep.at(Level.WARN).get(0).message()).contains("250:1");
        assertThat(rep.at(Level.WARN).get(0).remedy()).contains("balanced accuracy");
    }

    @Test
    void exactlyAtEveryThresholdIsAcceptable() {
        // The boundaries are inclusive -- 30 windows, 3 annotations, 2 slides,
        // 3 groups and 20:1 all pass. A test here because an off-by-one would
        // refuse a training set that the documented minimum says is fine.
        var rep = TrainingViability.check(List.of(new ClassTally("A", 600, 3, 2), new ClassTally("B", 30, 3, 2)), 3);
        assertThat(rep.level()).isEqualTo(Level.OK);
    }

    @Test
    void blocksAreListedBeforeWarnsInTheSummary() {
        var rep = TrainingViability.check(
                List.of(new ClassTally("Dense", 400, 1, 3), new ClassTally("Rare", 5, 1, 1)), 2);
        assertThat(rep.findings().get(0).level()).isEqualTo(Level.BLOCK);
        assertThat(rep.findings().get(rep.findings().size() - 1).level()).isEqualTo(Level.WARN);
        assertThat(rep.summary()).startsWith("[BLOCK]").contains("[WARN]");
    }
}
