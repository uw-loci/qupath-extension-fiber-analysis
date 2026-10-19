/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import qupath.ext.fiberanalysis.analysis.DensityOverlayDialog;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.ext.fiberanalysis.analysis.DensityMapOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/**
 * The density overlay on a real RGB brightfield slide, through QuPath's own
 * opacity slider and its "Show pixel classification" toggle (the C key).
 *
 * <p>Args: {@code <image> <projectDir> <outDir> <envBaseDir>}. The project is
 * created new; an existing one is reused with the map it already holds. Every
 * render is the viewer's own paint method, so the PNGs are what QuPath draws.
 */
public final class RgbOverlayScenario {

    private static int failures = 0;

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    static final double WINDOW_UM = 25.0;
    static final double OVERLAP = 75.0;
    static final String CHANNEL = "Signal density (no threshold)";
    static final int VIEW_W = 1600;
    static final int VIEW_H = 1200;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            check(false, "the scenario ran without an exception (" + t + ")");
            finish();
        }
    }

    private static void run(String[] args) throws Exception {
        Path image = Path.of(args[0]);
        Path projectDir = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Path envBase = Path.of(args[3]);
        Files.createDirectories(outDir);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        note("hidden QuPathGUI up, QuPath " + QuPathGUI.getVersion());
        onFx(() -> {
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            return null;
        });

        Project<BufferedImage> project;
        ProjectImageEntry<BufferedImage> entry;
        Path qpproj = projectDir.resolve("project.qpproj");
        if (Files.isRegularFile(qpproj)) {
            project = ProjectIO.loadProject(qpproj.toFile(), BufferedImage.class);
            entry = project.getImageList().get(0);
            note("reusing " + qpproj);
        } else {
            Files.createDirectories(projectDir);
            project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
            ImageServer<BufferedImage> src = ImageServers.buildServer(image.toUri().toString());
            entry = project.addImage(src.getBuilder());
            entry.setImageName(image.getFileName().toString());
            ImageData<BufferedImage> data = entry.readImageData();
            data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_E);
            entry.saveImageData(data);
            project.syncChanges();
            src.close();
        }
        final Project<BufferedImage> proj = project;
        onFx(() -> {
            qupath.setProject(proj);
            return null;
        });

        Path map = DensitySidecar.sidecarPathFor(project, entry);
        if (!Files.isRegularFile(map)) {
            // Brightfield: saturation picks out stained tissue on a white
            // background without inverting.
            var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                    WINDOW_UM, OVERLAP, "Saturation (HSV)", "Otsu", 0, "none", 1.0, 4.0, 1.0, 1.0, false, 0.0,
                    null, true, "window", 0.0, false, List.of(), false, List.of());
            long t0 = System.nanoTime();
            Thread t = new FiberDensityMapWorkflow().runForEntries(spec, List.of(entry), project, null);
            t.join();
            note(String.format("density map took %.1f s", (System.nanoTime() - t0) / 1e9));
        }
        check(Files.isRegularFile(map), "the slide has a density map (" + map.getFileName() + ")");
        if (!Files.isRegularFile(map)) finish();

        onFx(() -> {
            qupath.openImageEntry(entry);
            return null;
        });
        for (int i = 0; i < 300 && onFx(qupath::getImageData) == null; i++) Thread.sleep(100);
        ImageData<BufferedImage> data = onFx(qupath::getImageData);
        check(data != null, "the slide is open in the hidden viewer");
        if (data == null) finish();
        var server = data.getServer();
        note("slide " + server.getWidth() + "x" + server.getHeight() + ", " + server.nChannels()
                + " channel(s), RGB=" + server.isRGB() + ", max heap "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        check(server.isRGB(), "the slide is an RGB image");

        QuPathViewer viewer = onFx(qupath::getViewer);
        onFx(() -> {
            viewer.getView().resize(VIEW_W, VIEW_H);
            viewer.zoomToFit();
            return null;
        });
        note("viewer " + onFx(() -> viewer.getView().getWidth()) + "x" + onFx(() -> viewer.getView().getHeight()) + " at downsample "
                + String.format("%.2f", onFx(viewer::getDownsampleFactor)));

        var options = qupath.getOverlayOptions();
        // The toolbar is never shown, so it has no skin and a scene lookup cannot
        // reach its items; read them directly.
        Slider slider = onFx(() -> (Slider) qupath.getToolBar().getItems().stream()
                .filter(n -> "opacitySlider".equals(n.getId()))
                .findFirst()
                .orElse(null));
        check(slider != null, "the toolbar's opacity slider is found");
        // A script sets the same property the slider is bound to.
        java.util.function.DoubleConsumer setOpacity = slider != null
                ? slider::setValue
                : v -> options.setOpacity((float) v);
        var cAction = qupath.getOverlayActions().SHOW_PIXEL_CLASSIFICATION;
        KeyCombination accel = onFx(cAction::getAccelerator);
        check(accel != null && accel.match(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "c", "c", javafx.scene.input.KeyCode.C,
                        false, false, false, false)),
                "\"Show pixel classification\" is bound to the C key (" + accel + ")");
        Runnable pressC = onFx(() -> qupath.getStage().getScene().getAccelerators().get(accel));
        note(pressC != null
                ? "C is registered on the main window's scene; pressing it runs that accelerator"
                : "C is not on the scene's accelerator map; firing the action instead");
        Runnable toggleC = pressC != null ? pressC : () -> cAction.setSelected(!cAction.isSelected());

        onFx(() -> {
            setOpacity.accept(1.0);
            options.setShowPixelClassification(true);
            return null;
        });
        BufferedImage plain = render(viewer, outDir.resolve("rgb_0_no_overlay.png"));
        // Over tissue only: the white background and the viewer's margins are
        // grey whatever the display does. A slide shown as grey channels reads 0.
        check(chroma(plain) > 5, String.format(
                "with no overlay the slide renders in colour (mean |R-B| over tissue %.1f)", chroma(plain)));

        // Show the map through its dialog.
        onFx(() -> {
            new DensityOverlayDialog(qupath).show();
            return null;
        });
        Thread.sleep(1500);
        Stage stage = onFx(() -> {
            for (Window win : Window.getWindows()) {
                if (win instanceof Stage s && "Fiber Analysis -- Density overlay".equals(s.getTitle())) return s;
            }
            return null;
        });
        check(stage != null, "the overlay dialog opens");
        if (stage == null) finish();
        var combos = onFx(() -> new ArrayList<>(stage.getScene().getRoot().lookupAll(".combo-box")));
        @SuppressWarnings("unchecked")
        var channelCombo = (ComboBox<String>) combos.get(1);
        // Maps are listed in the background; wait for the channel list first.
        for (int i = 0; i < 300 && onFx(() -> channelCombo.getItems().isEmpty()); i++) Thread.sleep(100);
        onFx(() -> {
            channelCombo.getSelectionModel().select(CHANNEL);
            return null;
        });
        var minField = (TextField) onFx(() -> stage.getScene().getRoot().lookupAll(".text-field").iterator().next());
        long t0 = System.nanoTime();
        for (int i = 0; i < 1200 && onFx(() -> minField.getText().isBlank()); i++) Thread.sleep(100);
        check(!onFx(minField::getText).isBlank(), String.format(
                "the channel is read and a range filled in (%.1f s)", (System.nanoTime() - t0) / 1e9));
        clickButton(stage, "Show");
        for (int i = 0; i < 200 && !(onFx(viewer::getCustomPixelLayerOverlay) instanceof DensityMapOverlay); i++)
            Thread.sleep(50);
        check(onFx(viewer::getCustomPixelLayerOverlay) instanceof DensityMapOverlay, "the map is on the pixel layer");
        check(onFx(() -> qupath.getImageData().getServer().isRGB()), "the slide is still an RGB image with the overlay on");

        BufferedImage full = render(viewer, outDir.resolve("rgb_1_opacity_100.png"));
        onFx(() -> {
            setOpacity.accept(0.5);
            return null;
        });
        check(Math.abs(options.getOpacity() - 0.5) < 1e-6, "moving the slider sets the overlay opacity (0.5)");
        BufferedImage half = render(viewer, outDir.resolve("rgb_2_opacity_50.png"));
        onFx(() -> {
            setOpacity.accept(0.0);
            return null;
        });
        BufferedImage none = render(viewer, outDir.resolve("rgb_3_opacity_0.png"));
        double dFull = diff(full, plain);
        double dHalf = diff(half, plain);
        double dNone = diff(none, plain);
        note(String.format("mean change from the plain slide: opacity 1.0 %.2f, 0.5 %.2f, 0.0 %.2f", dFull, dHalf, dNone));
        check(dFull > 5, "at full opacity the map visibly changes the view");
        check(dHalf > 0.3 * dFull && dHalf < 0.7 * dFull, "at half opacity the change is about half");
        check(dNone < 0.5, "at zero opacity the view is the plain slide");

        onFx(() -> {
            setOpacity.accept(1.0);
            toggleC.run();
            return null;
        });
        check(!options.getShowPixelClassification(), "C turns \"Show pixel classification\" off");
        BufferedImage off = render(viewer, outDir.resolve("rgb_4_c_off.png"));
        check(diff(off, plain) < 0.5, String.format(
                "with C off the overlay is hidden (mean change %.2f)", diff(off, plain)));
        onFx(() -> {
            toggleC.run();
            return null;
        });
        check(options.getShowPixelClassification(), "C again turns it back on");
        BufferedImage on = render(viewer, outDir.resolve("rgb_5_c_on.png"));
        check(diff(on, full) < 0.5, String.format(
                "and the overlay returns as it was (mean change %.2f)", diff(on, full)));

        clickButton(stage, "Hide");
        check(onFx(viewer::getCustomPixelLayerOverlay) == null, "Hide removes it");
        note("renders in " + outDir);
        finish();
    }

    /** The viewer's own paint method into an image, after its tiles have loaded. */
    private static BufferedImage render(QuPathViewer viewer, Path png) throws Exception {
        Method paint = QuPathViewer.class.getDeclaredMethod("paintViewer", java.awt.Graphics.class, int.class, int.class);
        paint.setAccessible(true);
        BufferedImage img = null;
        BufferedImage prev = null;
        // Tiles arrive asynchronously; repaint until two passes agree.
        for (int pass = 0; pass < 20; pass++) {
            BufferedImage cur = new BufferedImage(VIEW_W, VIEW_H, BufferedImage.TYPE_INT_RGB);
            onFx(() -> {
                viewer.repaintEntireImage();
                Graphics2D g = cur.createGraphics();
                paint.invoke(viewer, g, VIEW_W, VIEW_H);
                g.dispose();
                return null;
            });
            img = cur;
            if (prev != null && diff(prev, cur) < 0.01) break;
            prev = cur;
            Thread.sleep(1500);
        }
        ImageIO.write(img, "png", png.toFile());
        return img;
    }

    private static double diff(BufferedImage a, BufferedImage b) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2)
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                sum += Math.abs(((p >> 16) & 255) - ((q >> 16) & 255))
                        + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                        + Math.abs((p & 255) - (q & 255));
                n += 3;
            }
        return (double) sum / n;
    }

    private static double chroma(BufferedImage a) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2)
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int total = r + g + b;
                if (total <= 60 || total >= 600) continue;
                sum += Math.abs(r - b);
                n++;
            }
        return n == 0 ? 0 : (double) sum / n;
    }

    private static void clickButton(Stage stage, String text) throws Exception {
        onFx(() -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText())) b.fire();
            }
            return null;
        });
    }

    private static void finish() {
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
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
