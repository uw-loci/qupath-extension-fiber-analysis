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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks completed work units and estimates the time left.
 *
 * <p>The estimate is a rolling mean of unit-to-unit intervals, NOT
 * {@code elapsed / completed}. The latter folds in everything that happened
 * before the first unit finished -- Appose environment startup, the Python
 * import of scipy and scikit-image, a modal dialog the user left open -- and
 * divides it into every remaining unit, which produces absurd figures early on
 * and never fully recovers. Interval sampling starts at the SECOND completion,
 * so startup cost is excluded by construction. (Same reasoning, and the same
 * hard-won failure, as QPSC's DualProgressDialog.)
 *
 * <p>Not thread-safe; call from one worker thread and read on the FX thread
 * only through the text accessors, which take a consistent snapshot.
 */
final class ProgressEstimator {

    /** Intervals kept for the rolling mean. Enough to be stable, few enough to track a changing tile cost. */
    private static final int WINDOW = 50;

    private final int total;
    private final Deque<Long> intervals = new ArrayDeque<>();
    private int completed;
    private long lastCompletionNanos;

    /**
     * @param total number of units this run will process; 0 or less means unknown
     */
    ProgressEstimator(int total) {
        this.total = total;
    }

    /** Records one completed unit. */
    synchronized void unitCompleted() {
        long now = System.nanoTime();
        if (completed > 0) {
            intervals.addLast(now - lastCompletionNanos);
            if (intervals.size() > WINDOW) intervals.removeFirst();
        }
        lastCompletionNanos = now;
        completed++;
    }

    synchronized int completed() {
        return completed;
    }

    int total() {
        return total;
    }

    synchronized int remaining() {
        return Math.max(0, total - completed);
    }

    /**
     * @return completed/total in [0,1], or -1 when the total is unknown (the
     *     value JavaFX ProgressBar reads as indeterminate)
     */
    synchronized double fraction() {
        if (total <= 0) return -1;
        return Math.min(1.0, (double) completed / (double) total);
    }

    /**
     * @return mean seconds per unit over the sampled interval window, or NaN
     *     before two units have completed
     */
    synchronized double secondsPerUnit() {
        if (intervals.isEmpty()) return Double.NaN;
        long sum = 0;
        for (long v : intervals) sum += v;
        return (sum / (double) intervals.size()) / 1_000_000_000.0;
    }

    /**
     * @return seconds until completion, or NaN while still collecting samples
     */
    synchronized double secondsRemaining() {
        double per = secondsPerUnit();
        if (Double.isNaN(per) || total <= 0) return Double.NaN;
        return per * remaining();
    }

    /**
     * @return a status line naming the count and the estimate, honest about not
     *     yet having one -- an early guess from a single sample is worse than
     *     saying nothing
     */
    synchronized String statusText(String unitPlural) {
        String counts = total > 0
                ? String.format("%,d of %,d %s", completed, total, unitPlural)
                : String.format("%,d %s", completed, unitPlural);
        double rem = secondsRemaining();
        if (Double.isNaN(rem)) {
            return counts + " - collecting timing data...";
        }
        return counts + " - ~" + formatDuration(rem) + " remaining";
    }

    /**
     * @param seconds duration to render; negative is clamped to zero
     * @return {@code 1h 02m 03s} / {@code 2m 05s} / {@code 9s}
     */
    static String formatDuration(double seconds) {
        long s = Math.round(Math.max(0, seconds));
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
        if (m > 0) return String.format("%dm %02ds", m, sec);
        return String.format("%ds", sec);
    }
}
