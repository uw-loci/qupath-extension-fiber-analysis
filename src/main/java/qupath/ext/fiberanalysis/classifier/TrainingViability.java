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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a training set can support a model, before any feature is
 * computed.
 *
 * <p>Feature extraction over a slide is minutes of work. Every condition
 * checked here is knowable from the annotations alone, so a run that cannot
 * produce a defensible number is refused at the dialog rather than after the
 * wait. The Python side re-checks the same conditions on the assembled matrix
 * ({@code fiberlib/wsplits.py check_viability}) and adds the per-feature
 * checks that need the numbers; this is the early half of one gate, not a
 * second opinion.
 *
 * <p><b>The thresholds are shared with {@code wsplits.py} and must stay in
 * step with it.</b> They are not tuning knobs: each is the point below which a
 * slide-level estimate stops existing rather than merely getting noisier.
 *
 * <p>Nothing here imports JavaFX or {@code qupath.lib}, so it is unit-tested
 * directly.
 */
public final class TrainingViability {

    /** A class needs this many labelled windows before it can be modelled at all. */
    public static final int MIN_WINDOWS_PER_CLASS = 30;

    /** ...and this many separate annotations, or it describes one hand-drawn region. */
    public static final int MIN_ANNOTATIONS_PER_CLASS = 3;

    /** ...and must appear on this many slides, or it cannot be validated at slide level. */
    public static final int MIN_GROUPS_PER_CLASS = 2;

    /** Fewer slides than this and grouped cross-validation is not possible. */
    public static final int MIN_GROUPS = 3;

    /** Above this ratio the headline metric is forced to balanced accuracy. */
    public static final double MAX_IMBALANCE_RATIO = 20.0;

    private TrainingViability() {}

    /** Verdict levels, worst first. */
    public enum Level {
        /** Training is refused. */
        BLOCK,
        /** Training proceeds, but something about the result will be weaker than it looks. */
        WARN,
        /** Nothing to report. */
        OK
    }

    /**
     * One thing wrong with the training set.
     *
     * @param level   how bad it is
     * @param message what is wrong, with the number that failed and the threshold
     * @param remedy  what the user can do about it
     */
    public record Finding(Level level, String message, String remedy) {

        @Override
        public String toString() {
            return level + ": " + message + " " + remedy;
        }
    }

    /**
     * What one class contributes to the training set.
     *
     * @param className   the PathClass name as QuPath displays it
     * @param windows     labelled window cores assigned to this class
     * @param annotations distinct annotations the windows came from
     * @param groups      distinct slides the annotations came from
     */
    public record ClassTally(String className, int windows, int annotations, int groups) {}

    /**
     * The verdict.
     *
     * @param level    the worst level among the findings
     * @param findings everything wrong, BLOCKs first
     */
    public record Report(Level level, List<Finding> findings) {

        /** @return true when training is refused */
        public boolean blocked() {
            return level == Level.BLOCK;
        }

        /** @return the findings at exactly one level */
        public List<Finding> at(Level wanted) {
            return findings.stream().filter(f -> f.level() == wanted).toList();
        }

        /**
         * @return a multi-line summary for a dialog, or a single line saying
         *         the training set is fine
         */
        public String summary() {
            if (findings.isEmpty()) {
                return "Training set looks viable.";
            }
            StringBuilder sb = new StringBuilder();
            for (Finding f : findings) {
                sb.append(f.level() == Level.BLOCK ? "[BLOCK] " : "[WARN] ")
                        .append(f.message())
                        .append('\n')
                        .append("  -> ")
                        .append(f.remedy())
                        .append("\n\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Run the check.
     *
     * @param tallies  one entry per class the user selected for training.
     *                 Classes with zero windows may be included; they are
     *                 reported rather than silently dropped.
     * @param nGroups  distinct slides across the whole training set
     * @return the verdict
     */
    public static Report check(Collection<ClassTally> tallies, int nGroups) {
        List<Finding> blocks = new ArrayList<>();
        List<Finding> warns = new ArrayList<>();

        if (nGroups < MIN_GROUPS) {
            blocks.add(new Finding(
                    Level.BLOCK,
                    String.format(
                            Locale.ROOT,
                            "Only %d slide(s) in the training set; grouped cross-validation needs at least %d.",
                            nGroups,
                            MIN_GROUPS),
                    "Annotate regions on more slides. Adding more regions to the same slide will not help,"
                            + " because regions on one slide are not independent of each other."));
        }

        int live = 0;
        int largest = 0;
        int smallest = Integer.MAX_VALUE;
        for (ClassTally t : tallies) {
            if (t.windows() > 0) {
                live++;
                largest = Math.max(largest, t.windows());
                smallest = Math.min(smallest, t.windows());
            }
            if (t.windows() < MIN_WINDOWS_PER_CLASS) {
                blocks.add(new Finding(
                        Level.BLOCK,
                        String.format(
                                Locale.ROOT,
                                "Class '%s' has %d labelled window(s), below the minimum of %d.",
                                t.className(),
                                t.windows(),
                                MIN_WINDOWS_PER_CLASS),
                        "Draw more or larger regions for it, reduce the stride so each region yields more"
                                + " window cores, or drop the class from the training set."));
            }
            if (t.groups() < MIN_GROUPS_PER_CLASS) {
                blocks.add(new Finding(
                        Level.BLOCK,
                        String.format(
                                Locale.ROOT,
                                "Class '%s' appears on %d slide(s), below the minimum of %d.",
                                t.className(),
                                t.groups(),
                                MIN_GROUPS_PER_CLASS),
                        "It cannot be validated at slide level: the fold holding out its only slide would"
                                + " have no examples of it left to test. Annotate it on another slide."));
            }
            if (t.annotations() < MIN_ANNOTATIONS_PER_CLASS) {
                warns.add(new Finding(
                        Level.WARN,
                        String.format(
                                Locale.ROOT,
                                "Class '%s' comes from %d annotation(s), below %d.",
                                t.className(),
                                t.annotations(),
                                MIN_ANNOTATIONS_PER_CLASS),
                        "Its windows mostly describe one hand-drawn region, so the model may learn that"
                                + " region rather than the class."));
            }
        }

        if (live < 2) {
            blocks.add(new Finding(
                    Level.BLOCK,
                    "Fewer than two classes have any labelled windows; there is nothing to separate.",
                    "Select at least two classes that both have annotations covering whole window cores."));
        } else {
            double ratio = largest / (double) smallest;
            if (ratio > MAX_IMBALANCE_RATIO) {
                warns.add(new Finding(
                        Level.WARN,
                        String.format(
                                Locale.ROOT,
                                "Class counts are imbalanced %.0f:1 (largest %d, smallest %d).",
                                ratio,
                                largest,
                                smallest),
                        "Accuracy will look good by predicting the majority, so the headline metric is"
                                + " balanced accuracy. Annotate more of the rarer class if you can."));
            }
        }

        List<Finding> all = new ArrayList<>(blocks);
        all.addAll(warns);
        Level level = !blocks.isEmpty() ? Level.BLOCK : (!warns.isEmpty() ? Level.WARN : Level.OK);
        return new Report(level, List.copyOf(all));
    }
}
