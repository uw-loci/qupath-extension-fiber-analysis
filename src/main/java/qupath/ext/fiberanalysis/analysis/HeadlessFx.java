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

import javafx.application.Platform;

/**
 * Tiny utility for "headless-tolerant" JavaFX dispatch.
 *
 * <p>The fiber-analysis workflows are normally driven by the GUI dialog and
 * post progress / dialogs back via {@code Platform.runLater}. They can also
 * be driven by a Groovy script under {@code QuPath script ...}, in which
 * case the JavaFX toolkit is never started and any direct
 * {@code Platform.runLater} throws {@code IllegalStateException: Toolkit
 * not initialized}.
 *
 * <p>{@link #runLater(Runnable)} is a drop-in replacement: defers to
 * {@code Platform.runLater} when the toolkit is up, silently skips the
 * runnable otherwise. Every runnable we hand to it is GUI work (a dialog,
 * a label update), so dropping it in headless mode is exactly the
 * intended behaviour.
 *
 * <p>{@link #isReady()} returns the same boolean; callers use it to decide
 * whether to instantiate JavaFX {@code Control}s at all (eagerly creating
 * a {@code new Label(...)} in a field initializer triggers the JavaFX
 * {@code Control.<clinit>} which throws the same ISE).
 */
public final class HeadlessFx {

    private static volatile Boolean cached;

    private HeadlessFx() {}

    /**
     * True iff the JavaFX toolkit is up. Result is cached after the first
     * call -- the toolkit's lifecycle is single-shot in practice; we accept
     * a stale "false" for the rare case of a test that starts FX
     * mid-process.
     */
    public static boolean isReady() {
        Boolean c = cached;
        if (c != null) return c;
        boolean ok;
        try {
            // isFxApplicationThread is documented to be safe before startup.
            if (Platform.isFxApplicationThread()) {
                ok = true;
            } else {
                // Probe with a no-op runLater; throws ISE iff not started.
                Platform.runLater(() -> {});
                ok = true;
            }
        } catch (IllegalStateException notReady) {
            ok = false;
        } catch (Throwable t) {
            ok = false;
        }
        cached = ok;
        return ok;
    }

    /**
     * {@code Platform.runLater} when FX is up; no-op otherwise. The
     * runnable is treated as GUI-side work that has no meaning in
     * headless mode (e.g. showing a dialog, updating a progress label).
     */
    public static void runLater(Runnable r) {
        if (r == null) return;
        if (!isReady()) return;
        Platform.runLater(r);
    }
}
