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

import java.util.Locale;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;

/** Asks what to do when the images of a multi-image run have different pixel sizes. */
public final class PixelSizePrompt {

    private static final Logger logger = LoggerFactory.getLogger(PixelSizePrompt.class);

    /** What the user chose. */
    public enum Choice {
        /** Analyse every image at the coarsest pixel size. */
        COMMON,
        /** Analyse each image at its own pixel size. */
        OWN,
        /** Do not run. */
        CANCEL
    }

    /** Why the numbers differ, said the same way everywhere. */
    static final String WHY = "Fiber measurements depend on resolution: fine fibers merge at a coarse pixel size,"
            + " and coverage, skeleton length, branch counts, gap widths and texture shift with it. Results from"
            + " different pixel sizes are not comparable.";

    private PixelSizePrompt() {}

    /**
     * Shows the question and waits for the answer; callable from any thread. Without a display the
     * answer is {@link Choice#OWN}, logged.
     *
     * @param owner         owner window, or null
     * @param title         dialog title
     * @param check         the images' pixel sizes
     * @param commonOffered true when the run can bring every image to one pixel size
     * @param ownNote       what happens additionally when each keeps its own size, or null
     * @return the choice
     */
    public static Choice ask(Window owner, String title, PixelSizeCheck check, boolean commonOffered, String ownNote) {
        String text = message(check, commonOffered, ownNote);
        if (!HeadlessFx.isReady()) {
            logger.warn("{}\n(no display: analysing each image at its own pixel size)", text);
            return Choice.OWN;
        }
        FutureTask<Choice> task = new FutureTask<>(() -> show(owner, title, check, commonOffered, text));
        if (Platform.isFxApplicationThread()) task.run();
        else Platform.runLater(task);
        try {
            return task.get();
        } catch (Exception e) {
            logger.warn("Pixel-size question failed: {}", e.getMessage());
            return Choice.CANCEL;
        }
    }

    /** @return the question's text */
    static String message(PixelSizeCheck check, boolean commonOffered, String ownNote) {
        StringBuilder sb = new StringBuilder("The selected images have different pixel sizes:\n");
        sb.append(check.describe()).append("\n\n").append(WHY).append("\n\n");
        if (commonOffered) {
            sb.append(String.format(
                    Locale.ROOT,
                    "Analyse all at %s um/px reads every image at that size (a larger analysis downsample on the"
                            + " finer ones), so their results compare. Each at its own size keeps the detail of the"
                            + " finer images, but their numbers are not comparable with the coarser ones.",
                    PixelSizeCheck.um(check.commonPixelUm())));
        } else {
            sb.append("This run reads every image at full resolution, so it cannot bring them to one size.");
        }
        if (ownNote != null && !ownNote.isBlank()) sb.append("\n\n").append(ownNote);
        if (!check.uncalibrated().isEmpty()) {
            sb.append("\n\nImages with no pixel size use the pixel-size override; set their pixel size"
                    + " (Image > Set pixel size) to include them properly.");
        }
        return sb.toString();
    }

    private static Choice show(Window owner, String title, PixelSizeCheck check, boolean commonOffered, String text) {
        ButtonType common = new ButtonType(
                "Analyse all at " + PixelSizeCheck.um(check.commonPixelUm()) + " um/px", ButtonBar.ButtonData.OK_DONE);
        ButtonType own =
                new ButtonType(commonOffered ? "Each at its own size" : "Run anyway", ButtonBar.ButtonData.OTHER);
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(560);
        var builder = Dialogs.builder()
                .title(title)
                .owner(owner)
                .content(body)
                .width(600)
                .resizable();
        builder = commonOffered
                ? builder.buttons(common, own, ButtonType.CANCEL)
                : builder.buttons(own, ButtonType.CANCEL);
        var answer = builder.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == common) return Choice.COMMON;
        if (answer == own) return Choice.OWN;
        return Choice.CANCEL;
    }
}
