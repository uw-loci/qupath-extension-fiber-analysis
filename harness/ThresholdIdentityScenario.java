/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisDialog;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisParams;
import qupath.ext.fiberanalysis.analysis.FiberAnalysisWorkflow;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.analysis.ThresholdIdentity;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.ext.fiberanalysis.service.ApposeFiberService;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

/**
 * The preview cuts where the run will, and every result says which threshold it was cut at.
 *
 * <ol>
 *   <li>Preview with Otsu: by default it cuts at the run-wide value pooled over the run's
 *       annotations, and says so; unticked, it says the run will differ. The user's annotation
 *       selection survives the preview.
 *   <li>An annotation run writes its threshold identity to threshold.json, the entry's metadata and
 *       every detection, and warns when another image was cut differently.
 *   <li>A density map carries the identity in its file description.
 * </ol>
 *
 * <p>Args: {@code <projectDir> <outDir> <envBaseDir>}. Use a copy of a project: the scenario writes
 * entry metadata and adds an image entry.
 */
public final class ThresholdIdentityScenario {

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
            run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
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
    private static void run(Path projectDir, Path outDir, Path envBase) throws Exception {
        Files.createDirectories(outDir);
        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project =
                ProjectIO.loadProject(projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        FiberAnalysisPreferences.installPreferences();
        FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
        FiberAnalysisPreferences.analysisDownsampleProperty().set(1.0);
        FiberAnalysisPreferences.internalChannelProperty().set("Raw intensity");
        FiberAnalysisPreferences.thresholdMethodProperty().set("Otsu");
        FiberAnalysisPreferences.ridgeFilterProperty().set("None");
        FiberAnalysisPreferences.invertIntensityProperty().set(false);
        FiberAnalysisPreferences.rollingBallRadiusUmProperty().set(0.0);
        FiberAnalysisPreferences.searchAreaProperty().set("all");
        FiberAnalysisPreferences.windowEnabledProperty().set(true);
        FiberAnalysisPreferences.windowSizeUmProperty().set(100.0);
        FiberAnalysisPreferences.windowOverlapPercentProperty().set(0);
        FiberAnalysisPreferences.windowObjectsProperty().set(true);
        FiberAnalysisPreferences.collagenObjectsProperty().set(false);
        FiberAnalysisPreferences.textureEnabledProperty().set(false);
        FiberAnalysisPreferences.outputDirProperty().set(outDir.resolve("runs").toString());

        ProjectImageEntry<BufferedImage> entry = project.getImageList().get(0);
        // A second image entry that an earlier run cut at another threshold.
        ProjectImageEntry<BufferedImage> other = project.addImage(entry.getServerBuilder());
        other.setImageName("earlier run");
        other.getMetadata()
                .put(
                        ThresholdIdentity.META_ANNOTATIONS,
                        ThresholdIdentity.of(
                                        "Otsu", 128, 0.1, 0.0962, "pooled over 1 annotation(s) on 1 image(s), run old",
                                        "Raw intensity", "None", 1, 4, 1, false, 0, 1, 1)
                                .label());
        project.syncChanges();

        onFx(() -> {
            qupath.setProject(project);
            qupath.openImageEntry(entry);
            return null;
        });
        for (int i = 0; i < 100 && onFx(qupath::getImageData) == null; i++) Thread.sleep(100);
        ImageData<BufferedImage> data = (ImageData<BufferedImage>) onFx(qupath::getImageData);
        List<PathObject> anns = new ArrayList<>(data.getHierarchy().getAnnotationObjects());
        PathObject first = anns.get(0);
        onFx(() -> {
            data.getHierarchy().getSelectionModel().setSelectedObject(first);
            return null;
        });

        // ---- 1. the preview ---------------------------------------------------
        var params = FiberAnalysisParams.fromPreferences();
        ApposeFiberService.getInstance().initialize(msg -> {});
        var pooled = FiberAnalysisWorkflow.poolForRun(
                params, FiberAnalysisWorkflow.zoneRegions(params, data, anns), outDir.resolve("pool"), null);
        String runCut = String.format(Locale.ROOT, "%.4g", pooled.thresholdNorm());
        note("run-wide Otsu over " + anns.size() + " annotation(s): " + runCut);

        onFx(() -> {
            new FiberAnalysisDialog(qupath, null).show();
            return null;
        });
        Thread.sleep(1500);
        Stage dialog = stage("Fiber Analysis -- Run");
        check(dialog != null, "the run dialog opens");
        if (dialog == null) return;
        Button previewBtn = (Button) find(dialog.getScene().getRoot(), Button.class, "Preview segmentation...");
        onFx(() -> {
            previewBtn.fire();
            return null;
        });
        Stage preview = null;
        for (int i = 0; i < 50 && preview == null; i++) {
            Thread.sleep(200);
            preview = stage("Fiber segmentation preview");
        }
        check(preview != null, "the preview opens");
        if (preview == null) return;
        Parent pr = preview.getScene().getRoot();
        String cutLine = waitForLabel(pr, "Cut:", null, 300);
        note("preview: " + cutLine);
        check(cutLine != null && cutLine.contains(runCut) && cutLine.contains("as the run does"),
                "with Otsu the preview cuts at the run-wide value and says so");
        check(onFx(() -> data.getHierarchy().getSelectionModel().getSelectedObject() == first),
                "the user's annotation selection survives the preview");

        CheckBox runCutBox = (CheckBox) find(pr, CheckBox.class, "Cut at the run's threshold");
        onFx(() -> {
            runCutBox.setSelected(false);
            return null;
        });
        String local = waitForLabel(pr, "Cut:", "preview square only", 120);
        note("preview, unticked: " + local);
        Label localLabel = label(pr, "Cut:");
        check(local != null && !local.contains(runCut) && onFx(() -> localLabel.getStyle().contains("b35900")),
                "fitted on the preview square, the cut differs and is flagged");

        ComboBox<String> method = null;
        for (Node n : onFx(() -> new ArrayList<>(dialog.getScene().getRoot().lookupAll(".combo-box")))) {
            if (n instanceof ComboBox<?> c && onFx(() -> c.getItems().contains("Project Otsu (calibrated)")))
                method = (ComboBox<String>) c;
        }
        var methodF = method;
        onFx(() -> {
            methodF.setValue("Manual");
            return null;
        });
        String manual = waitForLabel(pr, "Cut:", "set by hand", 120);
        note("preview, Manual: " + manual);
        check(manual != null && manual.contains("gray level") && onFx(() -> !runCutBox.isVisible()),
                "with Manual the preview states the gray level and hides the run-threshold box");
        Stage previewF = preview;
        onFx(() -> {
            methodF.setValue("Otsu");
            previewF.close();
            dialog.close();
            return null;
        });
        Thread.sleep(500);
        check(onFx(() -> data.getHierarchy().getAnnotationObjects().stream()
                        .noneMatch(a -> a.getPathClass() != null
                                && "Fiber-Preview-Region".equals(a.getPathClass().getName()))),
                "closing the preview removes its marker");

        // ---- 2. an annotation run ---------------------------------------------
        params = FiberAnalysisParams.fromPreferences();
        var paramsF = params;
        Thread worker =
                onFx(() -> new FiberAnalysisWorkflow(null).runForAnnotations(paramsF, anns, data, qupath));
        if (worker != null) worker.join();
        Thread.sleep(1000);
        Path thresholdJson;
        try (Stream<Path> s = Files.walk(outDir.resolve("runs"))) {
            thresholdJson = s.filter(p -> p.getFileName().toString().equals("threshold.json"))
                    .findFirst()
                    .orElse(null);
        }
        check(thresholdJson != null, "the run writes threshold.json");
        if (thresholdJson == null) return;
        JsonObject t = JsonParser.parseString(Files.readString(thresholdJson)).getAsJsonObject();
        String identity = t.get("identity").getAsString();
        note("identity: " + identity);
        check(identity.startsWith("Otsu " + runCut + " | Otsu pooled over " + anns.size() + " annotation(s)"),
                "the identity names the cut and how it was pooled");
        check(identity.equals(entry.getMetadata().get(ThresholdIdentity.META_ANNOTATIONS)),
                "the image's entry metadata records it");
        List<PathObject> dets = onFx(() -> new ArrayList<>(data.getHierarchy().getDetectionObjects()));
        long stamped = dets.stream()
                .filter(d -> identity.equals(d.getMetadata().get(ThresholdIdentity.META_OBJECT)))
                .count();
        note(dets.size() + " detection(s), " + stamped + " stamped");
        check(!dets.isEmpty() && stamped == dets.size(), "every detection carries it");

        // ---- 3. a density map ---------------------------------------------------
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                100.0, 0.0, "Raw intensity", "otsu", 128, 0.1, "None", 1, 4, 1, 1.0, false, 0.0, null, false,
                "window", 0.0, false, List.of(), false, List.of());
        Thread dm = new FiberDensityMapWorkflow().runForEntries(spec, List.of(entry), project, qupath, null);
        if (dm != null) dm.join();
        Path map = DensitySidecar.sidecarPathFor(project, entry);
        String mapThreshold = DensitySidecar.readInfo(map).threshold();
        note("map threshold: " + mapThreshold);
        check(mapThreshold != null && mapThreshold.contains("pooled over 1 image(s)")
                        && mapThreshold.contains("density run"),
                "the density map's file description records its threshold");
        check(mapThreshold != null && mapThreshold.equals(entry.getMetadata().get(ThresholdIdentity.META_DENSITY)),
                "and so does the entry's metadata");
    }

    private static Stage stage(String titlePrefix) throws Exception {
        return onFx(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith(titlePrefix)) return s;
            }
            return null;
        });
    }

    private static Node find(Parent root, Class<?> type, String text) throws Exception {
        return onFx(() -> {
            for (Node n : root.lookupAll("*")) {
                if (type.isInstance(n)
                        && n instanceof javafx.scene.control.Labeled l
                        && text.equals(l.getText())) return n;
            }
            throw new IllegalStateException("no " + type.getSimpleName() + " '" + text + "'");
        });
    }

    private static Label label(Parent root, String prefix) throws Exception {
        return onFx(() -> {
            for (Node n : root.lookupAll(".label")) {
                if (n instanceof Label l && l.getText() != null && l.getText().startsWith(prefix)) return l;
            }
            return null;
        });
    }

    /** Waits for a label starting with a prefix (and containing some text, if given). */
    private static String waitForLabel(Parent root, String prefix, String containing, int seconds)
            throws Exception {
        for (int i = 0; i < seconds * 5; i++) {
            Label l = label(root, prefix);
            String text = l == null ? null : onFx(l::getText);
            if (text != null && (containing == null || text.contains(containing))) return text;
            Thread.sleep(200);
        }
        Label s = label(root, "Fiber:");
        note("timed out; last status: " + (s == null ? "-" : onFx(s::getText)));
        return null;
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
