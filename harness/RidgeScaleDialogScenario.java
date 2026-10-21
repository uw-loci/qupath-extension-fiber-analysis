/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;

/**
 * The run dialog guides a ridge-filter user: the Manual control becomes a
 * response cutoff on the fixed 0-1 scale, a note explains the scale, and a
 * calibration made with other settings (or before the fixed scale) is flagged.
 *
 * <p>Args: {@code <projectDir> <outDir>}.
 */
public final class RidgeScaleDialogScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        try {
            run(Path.of(args[0]), Path.of(args[1]));
        } catch (Throwable t) {
            t.printStackTrace();
            check(false, "the scenario ran without an exception (" + t + ")");
        }
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    private static void run(Path projectDir, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        // A calibration fitted with Sato before ridge responses had a fixed scale.
        Files.writeString(
                projectDir.resolve("fiber-analysis").resolve("calibration_old-sato.json"),
                "{\"threshold_normalised\": 0.4, \"calibration_params\": {\"seg_channel\": \"Raw intensity\","
                        + " \"ridge_filter\": \"Sato\", \"invert_intensity\": false, \"rolling_ball_radius_um\": 0.0,"
                        + " \"sigma_min_um\": 1.0, \"sigma_max_um\": 4.0, \"sigma_step_um\": 1.0}}");

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project =
                ProjectIO.loadProject(projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> {
            qupath.setProject(project);
            qupath.openImageEntry(project.getImageList().get(0));
            return null;
        });
        for (int i = 0; i < 100 && onFx(qupath::getImageData) == null; i++) Thread.sleep(100);
        onFx(() -> {
            new FiberAnalysisDialog(qupath, null).show();
            return null;
        });
        Thread.sleep(1500);
        Stage stage = onFx(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith("Fiber Analysis -- Run"))
                    return s;
            }
            return null;
        });
        check(stage != null, "the run dialog opens");
        if (stage == null) return;
        Parent root = stage.getScene().getRoot();
        List<Node> combos = onFx(() -> new ArrayList<>(root.lookupAll(".combo-box")));
        ComboBox<String> ridge = null, method = null, calibration = null, channel = null;
        for (Node n : combos) {
            if (!(n instanceof ComboBox<?> c)) continue;
            var items = onFx(() -> new ArrayList<>(c.getItems()));
            if (items.contains("Frangi")) ridge = (ComboBox<String>) c;
            else if (items.contains("Project Otsu (calibrated)")) method = (ComboBox<String>) c;
            else if (items.contains("old-sato")) calibration = (ComboBox<String>) c;
            else if (items.contains("Raw intensity")) channel = (ComboBox<String>) c;
        }
        check(ridge != null && method != null && calibration != null && channel != null,
                "the ridge filter, threshold method, calibration and channel controls are found");
        if (ridge == null || method == null || calibration == null || channel == null) return;
        Spinner<Integer> grayCut = null;
        Spinner<Double> responseCut = null;
        for (Node n : onFx(() -> new ArrayList<>(root.lookupAll(".spinner")))) {
            if (!(n instanceof Spinner<?> sp)) continue;
            var f = sp.getValueFactory();
            if (f instanceof SpinnerValueFactory.DoubleSpinnerValueFactory d && d.getMax() == 1.0 && d.getAmountToStepBy() == 0.01)
                responseCut = (Spinner<Double>) sp;
            else if (f instanceof SpinnerValueFactory.IntegerSpinnerValueFactory i && i.getMax() >= 255 && i.getMin() == 0
                    && grayCut == null && i.getMax() <= 65535)
                grayCut = (Spinner<Integer>) sp;
        }
        check(grayCut != null && responseCut != null, "both Manual controls exist");
        if (grayCut == null || responseCut == null) return;

        var ridgeF = ridge;
        var methodF = method;
        var calF = calibration;
        var chanF = channel;
        var grayF = grayCut;
        var respF = responseCut;
        onFx(() -> {
            chanF.setValue("Raw intensity");
            methodF.setValue("Manual");
            ridgeF.setValue("None");
            return null;
        });
        check(onFx(() -> grayF.isVisible() && !respF.isVisible()) && label(root, "Manual threshold (0-") != null,
                "with no ridge filter the Manual cut is a gray level");
        check(label(root, "With a ridge filter") == null, "the ridge note is hidden with no ridge filter");

        onFx(() -> {
            ridgeF.setValue("Sato");
            return null;
        });
        check(onFx(() -> !grayF.isVisible() && respF.isVisible()) && label(root, "Response cutoff (0-1):") != null,
                "with a ridge filter the Manual cut becomes a ridge response cutoff on 0-1");
        String noteText = label(root, "With a ridge filter");
        note("note: " + noteText);
        check(noteText != null, "a note explains the fixed response scale");

        onFx(() -> {
            methodF.setValue("Project Otsu (calibrated)");
            calF.setValue("old-sato");
            return null;
        });
        String warnOld = label(root, "Calibration 'old-sato'");
        note("warning: " + warnOld);
        check(warnOld != null && warnOld.contains("before ridge responses had a fixed scale"),
                "a ridge calibration from before the fixed scale is flagged");
        snapshot(ridge, outDir.resolve("ridge_guidance.png"));

        onFx(() -> {
            calF.setValue("harness");
            return null;
        });
        String warnMismatch = label(root, "Calibration 'harness'");
        note("warning: " + warnMismatch);
        check(warnMismatch != null && warnMismatch.contains("ridge filter None"),
                "a calibration fitted without the ridge filter is flagged with the setting that differs");

        onFx(() -> {
            ridgeF.setValue("None");
            return null;
        });
        check(label(root, "Calibration 'harness'") == null, "the warning clears once the settings match");
    }

    /** The visible label whose text starts with a prefix, or null. */
    private static String label(Parent root, String prefix) throws Exception {
        return onFx(() -> {
            for (Node n : root.lookupAll(".label")) {
                if (n instanceof Label l && l.getText() != null && l.getText().startsWith(prefix) && l.isVisible())
                    return l.getText();
            }
            return null;
        });
    }

    /** Saves the section that holds a control, for a person to look at. */
    private static void snapshot(Node inside, Path png) throws Exception {
        onFx(() -> {
            Node n = inside;
            while (n != null && !(n instanceof TitledPane)) n = n.getParent();
            Node target = n == null ? inside.getScene().getRoot() : n;
            var img = target.snapshot(null, null);
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", png.toFile());
            return null;
        });
        note("snapshot: " + png);
    }

    private static void startFx() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        }
        if (!up.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
