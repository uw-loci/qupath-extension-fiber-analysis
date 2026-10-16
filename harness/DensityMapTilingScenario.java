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
import java.awt.image.Raster;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import qupath.ext.fiberanalysis.analysis.DensityOverlayDialog;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapDialog;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.analysis.PixelPositivitySpec;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.ROIs;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.RegionRequest;

/**
 * The density map on a real whole image, through the real tiled path.
 *
 * <p>Args: {@code <image> <projectDir> <outDir> <envBaseDir>}. The env base is
 * a private Appose location, so this run never re-stages the environment a
 * running QuPath is using.
 */
public final class DensityMapTilingScenario {

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
    static final String CLASS = "Dense";
    static final int[] RECT = {2000, 2000, 2000, 1500};
    static final double POS_THRESHOLD = 2000;

    public static void main(String[] args) throws Exception {
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
            FiberAnalysisPreferences.windowSizeUmProperty().set(WINDOW_UM);
            FiberAnalysisPreferences.windowOverlapPercentProperty().set((int) OVERLAP);
            return null;
        });

        if (System.getenv("OVERLAY_ONLY") != null) {
            // Re-check only the overlay against the maps a previous run left.
            Project<BufferedImage> existing = ProjectIO.loadProject(
                    projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
            onFx(() -> {
                qupath.setProject(existing);
                return null;
            });
            note("OVERLAY_ONLY: reusing " + projectDir);
            try {
                overlayScenario(qupath, existing.getImageList().get(0), outDir);
            } catch (Exception e) {
                check(false, "the overlay scenario ran without an exception (" + e + ")");
            }
            finish();
        }
        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        ImageServer<BufferedImage> src = ImageServers.buildServer(image.toUri().toString());
        ProjectImageEntry<BufferedImage> entry = project.addImage(src.getBuilder());
        entry.setImageName(image.getFileName().toString());
        ImageData<BufferedImage> data = entry.readImageData();
        data.setImageType(ImageData.ImageType.FLUORESCENCE);
        PathClass dense = PathClass.fromString(CLASS);
        data.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(RECT[0], RECT[1], RECT[2], RECT[3], ImagePlane.getDefaultPlane()), dense));
        data.getHierarchy().addObject(PathObjects.createAnnotationObject(
                ROIs.createEllipseROI(6000, 1000, 2500, 1800, ImagePlane.getDefaultPlane()), dense));
        entry.saveImageData(data);
        project.syncChanges();
        onFx(() -> {
            qupath.setProject(project);
            return null;
        });
        int w = src.getWidth();
        int h = src.getHeight();
        double px = src.getPixelCalibration().getAveragedPixelSizeMicrons();
        note("image " + w + "x" + h + " at " + px + " um/px, " + src.nChannels() + " channel(s), max heap "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");

        dialogScenario(qupath, outDir);

        Path window = run(project, entry, "window", 0.0, "Otsu");
        if (!Files.isRegularFile(window)) finish();
        Path win = Files.copy(window, outDir.resolve("window_density.ome.tif"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path pixel = run(project, entry, "pixel", 0.0, "Triangle");
        if (!Files.isRegularFile(pixel)) finish();
        Path pix = Files.copy(pixel, outDir.resolve("pixel_density.ome.tif"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        int windowPx = (int) Math.round(WINDOW_UM / px);
        int stride = (int) Math.round(windowPx * (1.0 - OVERLAP / 100.0));
        int gridW = (w - windowPx) / stride + 1;
        int gridH = (h - windowPx) / stride + 1;
        int spanW = (gridW - 1) * stride + windowPx;
        int spanH = (gridH - 1) * stride + windowPx;
        note("window " + windowPx + " px, stride " + stride + " px, " + gridW + "x" + gridH + " windows");

        try (ImageServer<BufferedImage> ws = ImageServers.buildServer(win.toUri().toString());
                ImageServer<BufferedImage> ps = ImageServers.buildServer(pix.toUri().toString())) {
            List<String> names = new ArrayList<>();
            for (int c = 0; c < ws.nChannels(); c++) names.add(ws.getChannel(c).getName());
            note("channels: " + names);
            int sig = names.indexOf("Signal density (no threshold)");
            int coh = names.indexOf("Intensity coherence (no threshold)");
            check(sig >= 0 && coh >= 0, "the sidecar carries both threshold-free channels");
            check(ws.getWidth() == w && ws.getHeight() == h, "the sidecar matches the source dimensions");

            // Every pixel inside the window union must have a signal value: a
            // window the tiling skipped shows up as a NaN stripe at a seam.
            int ds = 4;
            Raster wr = ws.readRegion(RegionRequest.createInstance(ws.getPath(), ds, 0, 0, spanW, spanH)).getRaster();
            long nan = 0;
            long total = 0;
            double sMin = Double.MAX_VALUE;
            double sMax = -Double.MAX_VALUE;
            double cMin = Double.MAX_VALUE;
            double cMax = -Double.MAX_VALUE;
            for (int y = 0; y < wr.getHeight(); y++) {
                for (int x = 0; x < wr.getWidth(); x++) {
                    double s = wr.getSampleDouble(x, y, sig);
                    double c = wr.getSampleDouble(x, y, coh);
                    total++;
                    if (Double.isNaN(s)) {
                        nan++;
                        continue;
                    }
                    sMin = Math.min(sMin, s);
                    sMax = Math.max(sMax, s);
                    if (!Double.isNaN(c)) {
                        cMin = Math.min(cMin, c);
                        cMax = Math.max(cMax, c);
                    }
                }
            }
            check(nan == 0, "window mode: no gaps inside the windows (" + nan + " NaN of " + total + " px)");
            check(sMin >= 0 && sMax <= 1, String.format("signal density within [0, 1] (%.4f .. %.4f)", sMin, sMax));
            check(cMin >= 0 && cMax <= 1, String.format("coherence within [0, 1] (%.4f .. %.4f)", cMin, cMax));

            Raster pr = ps.readRegion(RegionRequest.createInstance(ps.getPath(), ds, 0, 0, w, h)).getRaster();
            long pnan = 0;
            for (int y = 0; y < pr.getHeight(); y++)
                for (int x = 0; x < pr.getWidth(); x++) if (Double.isNaN(pr.getSampleDouble(x, y, sig))) pnan++;
            check(pnan == 0, "pixel mode: a value everywhere, edges included (" + pnan + " NaN)");

            int objCh = names.indexOf("Object density: " + CLASS);
            int posCh = -1;
            for (int c = 0; c < names.size(); c++) if (names.get(c).contains(">")) posCh = c;
            check(objCh >= 0 && posCh >= 0, "both optional channels are in the sidecar (" + objCh + ", " + posCh + ")");
            // Object density: a window wholly inside the rectangle reads 1, one
            // far from both annotations reads 0, in both modes.
            int gIn = (RECT[0] + 300) / stride;
            int gInY = (RECT[1] + 300) / stride;
            int cxIn = gIn * stride + windowPx / 2;
            int cyIn = gInY * stride + windowPx / 2;
            check(sample(ws, cxIn, cyIn, objCh) == 1.0 && sample(ps, cxIn, cyIn, objCh) == 1.0,
                    "object density is 1 inside the annotation (window and per-pixel)");
            check(sample(ws, 500, 7000, objCh) == 0.0 && sample(ps, 500, 7000, objCh) == 0.0,
                    "object density is 0 far from any annotation (window and per-pixel)");
            // Pixel positivity against a direct count from the image itself.
            double worst = 0;
            int nPos = 0;
            for (int gy = 3; gy < gridH - 3; gy += Math.max(1, gridH / 6)) {
                for (int gx = 3; gx < gridW - 3; gx += Math.max(1, gridW / 6)) {
                    Raster r = src.readRegion(RegionRequest.createInstance(
                                    src.getPath(), 1.0, gx * stride, gy * stride, windowPx, windowPx))
                            .getRaster();
                    long n = 0;
                    for (int y = 0; y < windowPx; y++)
                        for (int x = 0; x < windowPx; x++) if (r.getSampleDouble(x, y, 0) > POS_THRESHOLD) n++;
                    double expect = (double) n / ((double) windowPx * windowPx);
                    double got = sample(ws, gx * stride + windowPx / 2, gy * stride + windowPx / 2, posCh);
                    worst = Math.max(worst, Math.abs(expect - got));
                    nPos++;
                }
            }
            check(worst < 1e-6, String.format(
                    "pixel positivity matches a direct count from the image (worst %.2e over %d windows)",
                    worst, nPos));

            // Alignment, live: at each window's CENTRE the window map must
            // agree with the independently computed centred (pixel-mode) map,
            // and agree better there than where the old writer drew it.
            int inset = (windowPx - stride) / 2;
            double errCentre = 0;
            double errOld = 0;
            int n = 0;
            for (int gy = 2; gy < gridH - 2; gy += Math.max(1, gridH / 25)) {
                for (int gx = 2; gx < gridW - 2; gx += Math.max(1, gridW / 25)) {
                    int cx = gx * stride + windowPx / 2;
                    int cy = gy * stride + windowPx / 2;
                    double wv = sample(ws, cx, cy, sig);
                    double pc = sample(ps, cx, cy, sig);
                    double po = sample(ps, cx - inset, cy - inset, sig);
                    errCentre += Math.abs(wv - pc);
                    errOld += Math.abs(wv - po);
                    n++;
                }
            }
            errCentre /= n;
            errOld /= n;
            note(String.format(
                    "|window - centred| at the window centre %.5f; at the old drawn position %.5f (%d windows)",
                    errCentre, errOld, n));
            check(errCentre < errOld, "the window map sits over the tissue that produced it");
        }

        try {
            overlayScenario(qupath, entry, outDir);
        } catch (Exception e) {
            check(false, "the overlay scenario ran without an exception (" + e + ")");
        }
        finish();
    }

    /** The density map as a pixel-layer overlay, driven through its dialog. */
    private static void overlayScenario(QuPathGUI qupath, ProjectImageEntry<BufferedImage> entry, Path outDir)
            throws Exception {
        onFx(() -> {
            qupath.openImageEntry(entry);
            return null;
        });
        for (int i = 0; i < 100 && onFx(qupath::getImageData) == null; i++) Thread.sleep(100);
        var data = onFx(qupath::getImageData);
        check(data != null, "the image is open in the hidden viewer");
        if (data == null) return;
        int channelsBefore = data.getServer().nChannels();

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
        if (stage == null) return;
        @SuppressWarnings("unchecked")
        var combos = onFx(() -> new ArrayList<>(stage.getScene().getRoot().lookupAll(".combo-box")));
        @SuppressWarnings("unchecked")
        var mapCombo = (javafx.scene.control.ComboBox<Path>) combos.get(0);
        @SuppressWarnings("unchecked")
        var channelCombo = (javafx.scene.control.ComboBox<String>) combos.get(1);
        check(onFx(() -> mapCombo.getItems().size()) >= 1, "the dialog finds this image's density map");
        onFx(() -> {
            channelCombo.getSelectionModel().select("Signal density (no threshold)");
            return null;
        });
        // Wait for the background read to fill the range fields.
        var fields = onFx(() -> new ArrayList<>(stage.getScene().getRoot().lookupAll(".text-field")));
        var minField = (javafx.scene.control.TextField) fields.get(0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1200 && onFx(() -> minField.getText().isBlank()); i++) Thread.sleep(100);
        boolean read = !onFx(minField::getText).isBlank();
        check(read, String.format("the channel is read and an automatic range is filled in (%.1f s)",
                (System.nanoTime() - t0) / 1e9));
        if (!read) {
            note("selected channel: " + onFx(() -> channelCombo.getSelectionModel().getSelectedIndex() + " "
                    + channelCombo.getValue() + " of " + channelCombo.getItems()));
            for (var n : onFx(() -> new ArrayList<>(stage.getScene().getRoot().lookupAll(".label")))) {
                String t = onFx(() -> ((javafx.scene.control.Label) n).getText());
                if (t != null && !t.isBlank()) note("label: " + t);
            }
            for (var f : fields) note("field: '" + onFx(((javafx.scene.control.TextField) f)::getText) + "'");
            return;
        }
        onFx(() -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof javafx.scene.control.Button b && "Show".equals(b.getText())) b.fire();
            }
            return null;
        });
        var viewer = onFx(qupath::getViewer);
        var overlay = onFx(viewer::getCustomPixelLayerOverlay);
        check(overlay instanceof BufferedImageOverlay,
                "the map is on the viewer's pixel layer");
        if (overlay == null) return;
        check(onFx(() -> qupath.getImageData().getServer().nChannels()) == channelsBefore,
                "the image server is unchanged (" + channelsBefore + " channel(s))");

        // Composite for a human to look at: the image thumbnail with the
        // overlay painted over it by its own paint method.
        var server = data.getServer();
        double ds = Math.max(server.getWidth(), server.getHeight()) / 1600.0;
        BufferedImage base = server.readRegion(RegionRequest.createInstance(
                server.getPath(), ds, 0, 0, server.getWidth(), server.getHeight()));
        int bw = base.getWidth(), bh = base.getHeight();
        BufferedImage comp = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
        var raster = base.getRaster();
        double[] range = {Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int y = 0; y < bh; y += 4)
            for (int x = 0; x < bw; x += 4) {
                double v = raster.getSampleDouble(x, y, 0);
                range[0] = Math.min(range[0], v);
                range[1] = Math.max(range[1], v);
            }
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++) {
                int g = (int) Math.max(0, Math.min(255,
                        255 * (raster.getSampleDouble(x, y, 0) - range[0]) / (0.3 * (range[1] - range[0]) + 1e-9)));
                comp.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        var g2 = comp.createGraphics();
        g2.scale(1 / ds, 1 / ds);
        g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.6f));
        var region = ImageRegion.createInstance(0, 0, server.getWidth(), server.getHeight(), 0, 0);
        onFx(() -> {
            overlay.paintOverlay(g2, region, ds, data, true);
            return null;
        });
        g2.dispose();
        ImageIO.write(comp, "png", outDir.resolve("overlay_composite.png").toFile());
        note("overlay composite: " + outDir.resolve("overlay_composite.png"));

        onFx(() -> {
            for (Node n : stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof javafx.scene.control.Button b && "Hide".equals(b.getText())) b.fire();
            }
            return null;
        });
        check(onFx(viewer::getCustomPixelLayerOverlay) == null, "Hide removes it");
    }

    private static void finish() {
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static double sample(ImageServer<BufferedImage> s, int x, int y, int band) throws Exception {
        BufferedImage img = s.readRegion(RegionRequest.createInstance(s.getPath(), 1.0, x, y, 1, 1));
        return img.getRaster().getSampleDouble(0, 0, band);
    }

    private static Path run(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry, String mode,
            double stepUm, String threshold) throws Exception {
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                WINDOW_UM, OVERLAP, "Raw intensity", threshold, 2000, "none", 1.0, 4.0, 1.0, 1.0, false, 0.0, null,
                false, false, mode, stepUm, true, List.of(CLASS), true,
                List.of(new PixelPositivitySpec(0, "Channel 1", PixelPositivitySpec.OP_GT, POS_THRESHOLD)));
        long t0 = System.nanoTime();
        Thread t = new FiberDensityMapWorkflow().runForEntries(spec, List.of(entry), project, null);
        t.join();
        Path out = project.getPath().getParent().resolve("fiber-analysis/density-maps")
                .resolve(qupath.ext.fiberanalysis.analysis.DensitySidecar.sanitize(entry.getImageName())
                        + "_density.ome.tif");
        check(Files.isRegularFile(out), mode + " mode wrote a sidecar");
        // Only the suffix: an image name can itself contain ".ome.tif".
        String base = out.getFileName().toString();
        base = base.substring(0, base.length() - ".ome.tif".length());
        Path params = out.getParent().resolve(base + "_params.json");
        String json = Files.isRegularFile(params) ? Files.readString(params) : "";
        check(json.contains("\"thresholdSource\"") && json.contains("project_otsu"),
                mode + " mode: one pooled " + threshold + " threshold for every tile, recorded in the provenance");
        if (json.contains("\"thresholdSource\"")) {
            check(json.contains(threshold + " pooled"), mode + " mode: the provenance names the method (" + threshold + ")");
        }
        note(String.format("%s mode took %.1f s", mode, (System.nanoTime() - t0) / 1e9));
        return out;
    }

    private static void dialogScenario(QuPathGUI qupath, Path outDir) throws Exception {
        onFx(() -> {
            new FiberDensityMapDialog(qupath).show();
            return null;
        });
        Thread.sleep(1500);
        Stage stage = onFx(() -> {
            for (Window win : Window.getWindows()) {
                if (win instanceof Stage s && s.getTitle() != null && s.getTitle().contains("Project density map")) return s;
            }
            return null;
        });
        check(stage != null, "the density-map dialog opens");
        if (stage == null) return;
        Spinner<?> spinner = onFx(() -> (Spinner<?>) stage.getScene().getRoot().lookupAll(".spinner").stream()
                .findFirst().orElse(null));
        RadioButton pixelRadio = onFx(() -> {
            for (Node node : stage.getScene().getRoot().lookupAll(".radio-button")) {
                if (node instanceof RadioButton r && r.getText().startsWith("Per-pixel")) return r;
            }
            return null;
        });
        check(spinner != null && pixelRadio != null, "the dialog has the output-step control and the per-pixel choice");
        if (spinner == null || pixelRadio == null) return;
        check(onFx(spinner::isDisabled), "the output step is disabled in per-window mode");
        onFx(() -> {
            pixelRadio.setSelected(true);
            return null;
        });
        check(!onFx(spinner::isDisabled), "the output step is enabled in per-pixel mode");
        BufferedImage shot = onFx(() -> SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null));
        ImageIO.write(shot, "png", outDir.resolve("density_dialog.png").toFile());
        note("dialog snapshot: " + outDir.resolve("density_dialog.png"));
        onFx(() -> {
            stage.close();
            return null;
        });
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
