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
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Counting and time-left behaviour behind the run dialog's progress line. */
class ProgressEstimatorTest {

    @Test
    void reportsCountsBeforeItCanEstimate() {
        ProgressEstimator est = new ProgressEstimator(900);
        assertThat(est.statusText("tiles")).isEqualTo("0 of 900 tiles - collecting timing data...");
        est.unitCompleted();
        // One completion gives no INTERVAL, so still no estimate -- this is the
        // case that would otherwise report startup cost as the per-unit cost.
        assertThat(est.statusText("tiles")).isEqualTo("1 of 900 tiles - collecting timing data...");
        assertThat(est.secondsPerUnit()).isNaN();
    }

    @Test
    void estimatesFromIntervalsOnceTwoUnitsAreDone() throws Exception {
        ProgressEstimator est = new ProgressEstimator(10);
        est.unitCompleted();
        Thread.sleep(60);
        est.unitCompleted();
        assertThat(est.secondsPerUnit()).isGreaterThan(0.0);
        assertThat(est.statusText("tiles")).startsWith("2 of 10 tiles - ~");
        assertThat(est.statusText("tiles")).endsWith(" remaining");
    }

    @Test
    void startupCostIsExcludedFromThePerUnitMean() throws Exception {
        // Simulates the real failure: a long pause before unit 1 (Appose env
        // start, scipy import) followed by fast units. elapsed/completed would
        // charge that pause to every remaining unit.
        ProgressEstimator est = new ProgressEstimator(100);
        Thread.sleep(300); // "startup"
        est.unitCompleted();
        Thread.sleep(20);
        est.unitCompleted();
        Thread.sleep(20);
        est.unitCompleted();
        // Mean interval should reflect the ~20ms cadence, not the 300ms startup.
        assertThat(est.secondsPerUnit()).isLessThan(0.15);
    }

    @Test
    void countsAndFractionTrackCompletion() {
        ProgressEstimator est = new ProgressEstimator(4);
        assertThat(est.fraction()).isEqualTo(0.0);
        est.unitCompleted();
        est.unitCompleted();
        assertThat(est.completed()).isEqualTo(2);
        assertThat(est.remaining()).isEqualTo(2);
        assertThat(est.fraction()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void unknownTotalStaysIndeterminate() {
        ProgressEstimator est = new ProgressEstimator(0);
        est.unitCompleted();
        assertThat(est.fraction()).isEqualTo(-1);
        assertThat(est.secondsRemaining()).isNaN();
        assertThat(est.statusText("tiles")).isEqualTo("1 tiles - collecting timing data...");
    }

    @Test
    void formatsDurationsByMagnitude() {
        assertThat(ProgressEstimator.formatDuration(9)).isEqualTo("9s");
        assertThat(ProgressEstimator.formatDuration(125)).isEqualTo("2m 05s");
        assertThat(ProgressEstimator.formatDuration(3723)).isEqualTo("1h 02m 03s");
        assertThat(ProgressEstimator.formatDuration(-5)).isEqualTo("0s");
    }
}
