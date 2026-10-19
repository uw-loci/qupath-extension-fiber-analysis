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
import qupath.ext.fiberanalysis.analysis.DensityChannelAttacher;
import qupath.ext.fiberanalysis.analysis.DensityMapInfo;
import qupath.ext.fiberanalysis.analysis.DensityMapOverlay;
import qupath.ext.fiberanalysis.analysis.DensityMapReader;
import qupath.ext.fiberanalysis.analysis.DensityOverlayDialog;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapDialog;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.analysis.PixelPositivitySpec;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.gui.QuPathGUI;
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
        note("window " + windowPx + " px, stride " + stride + " px");
        note(String.format("map files: window %.1f MB, pixel %.1f MB (the image is %d x %d)",
                Files.size(win) / 1e6, Files.size(pix) / 1e6, w, h));
        check(Files.size(win) < 50_000_000L && Files.size(pix) < 50_000_000L,
                "the map files are stored at grid resolution, not image resolution");

        try (DensityMapReader wr = DensityMapReader.open(win);
                DensityMapReader pr = DensityMapReader.open(pix)) {
            DensityMapInfo wi = wr.info();
            DensityMapInfo pi = pr.info();
            List<String> names = new ArrayList<>();
            for (var ch : wi.channels()) names.add(ch.name);
            note("channels: " + names);
            note("window grid " + wi.lattice() + "; pixel grid " + pi.lattice());
            int sig = wi.channelIndex("Signal density (no threshold)");
            int coh = wi.channelIndex("Intensity coherence (no threshold)");
            check(sig >= 0 && coh >= 0, "the map carries both threshold-free channels");
            check(wi.fits(w, h) && pi.fits(w, h), "both maps say they belong to this image's size");
            check(wi.lattice().cellPx() == stride && wi.lattice().windowPx() == windowPx,
                    "the window map's cells are the stride and its windows the window size");
            check(wi.lattice().x0() == (windowPx - stride) / 2.0, "the window map's cells are centred on their windows");

            // A window the tiling skipped would be a NaN cell.
            float[] ws = wr.readChannel(sig);
            float[] wc = wr.readChannel(coh);
            long nan = 0;
            double sMin = Double.MAX_VALUE, sMax = -Double.MAX_VALUE, cMin = Double.MAX_VALUE, cMax = -Double.MAX_VALUE;
            for (int i = 0; i < ws.length; i++) {
                if (Float.isNaN(ws[i])) {
                    nan++;
                    continue;
                }
                sMin = Math.min(sMin, ws[i]);
                sMax = Math.max(sMax, ws[i]);
                if (!Float.isNaN(wc[i])) {
                    cMin = Math.min(cMin, wc[i]);
                    cMax = Math.max(cMax, wc[i]);
                }
            }
            check(nan == 0, "window mode: no gaps (" + nan + " NaN of " + ws.length + " cells)");
            check(sMin >= 0 && sMax <= 1, String.format("signal density within [0, 1] (%.4f .. %.4f)", sMin, sMax));
            check(cMin >= 0 && cMax <= 1, String.format("coherence within [0, 1] (%.4f .. %.4f)", cMin, cMax));

            float[] ps = pr.readChannel(sig);
            long pnan = 0;
            for (float v : ps) if (Float.isNaN(v)) pnan++;
            check(pnan == 0, "pixel mode: a value in every cell (" + pnan + " NaN)");
            check(pi.lattice().covers(0.5, 0.5) && pi.lattice().covers(w - 0.5, h - 0.5),
                    "pixel mode: the grid reaches every corner of the image");

            int objCh = wi.channelIndex("Object density: " + CLASS);
            int posCh = -1;
            for (int c = 0; c < names.size(); c++) if (names.get(c).contains(">")) posCh = c;
            check(objCh >= 0 && posCh >= 0, "both optional channels are in the map (" + objCh + ", " + posCh + ")");
            float[] wObj = wr.readChannel(objCh);
            float[] pObj = pr.readChannel(objCh);
            float[] wPos = wr.readChannel(posCh);
            // Object density: a window wholly inside the rectangle reads 1, one
            // far from both annotations reads 0, in both modes.
            int cxIn = RECT[0] + 300 + windowPx / 2;
            int cyIn = RECT[1] + 300 + windowPx / 2;
            check(wi.sample(wObj, cxIn, cyIn) == 1.0f && pi.sample(pObj, cxIn, cyIn) == 1.0f,
                    "object density is 1 inside the annotation (window and per-pixel)");
            check(wi.sample(wObj, 500, 7000) == 0.0f && pi.sample(pObj, 500, 7000) == 0.0f,
                    "object density is 0 far from any annotation (window and per-pixel)");
            // Pixel positivity against a direct count from the image itself.
            int gridW = wi.lattice().gridW();
            int gridH = wi.lattice().gridH();
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
                    double got = wPos[gy * gridW + gx];
                    worst = Math.max(worst, Math.abs(expect - got));
                    nPos++;
                }
            }
            check(worst < 1e-6, String.format(
                    "pixel positivity matches a direct count from the image (worst %.2e over %d windows)",
                    worst, nPos));

            // Alignment: at each window's centre the window map must agree
            // with the independently computed centred (pixel-mode) map, and
            // agree better there than half a window up and to the left.
            int inset = (windowPx - stride) / 2;
            double errCentre = 0;
            double errOld = 0;
            int n = 0;
            for (int gy = 2; gy < gridH - 2; gy += Math.max(1, gridH / 25)) {
                for (int gx = 2; gx < gridW - 2; gx += Math.max(1, gridW / 25)) {
                    int cx = gx * stride + windowPx / 2;
                    int cy = gy * stride + windowPx / 2;
                    double wv = wi.sample(ws, cx, cy);
                    errCentre += Math.abs(wv - pi.sample(ps, cx, cy));
                    errOld += Math.abs(wv - pi.sample(ps, cx - inset, cy - inset));
                    n++;
                }
            }
            errCentre /= n;
            errOld /= n;
            note(String.format(
                    "|window - centred| at the window centre %.5f; half a window up-left %.5f (%d windows)",
                    errCentre, errOld, n));
            check(errCentre < errOld, "the window map sits over the tissue that produced it");
        }

        try {
            channelsEntryScenario(project, entry, w, h);
        } catch (Exception e) {
            e.printStackTrace();
            check(false, "the channels-entry scenario ran without an exception (" + e + ")");
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
        // Maps are listed in the background; wait for the channel list first.
        for (int i = 0; i < 300 && onFx(() -> channelCombo.getItems().isEmpty()); i++) Thread.sleep(100);
        onFx(() -> {
            channelCombo.getSelectionModel().select("Signal density (no threshold)");
            return null;
        });
        // Wait for the background read to fill the range fields.
        var fields = onFx(() -> new ArrayList<>(stage.getScene().getRoot().lookupAll(".text-field")));
        var minField = (javafx.scene.control.TextField) fields.get(0);
        // The status names the channel once that channel (not an earlier one) is read.
        var statusLabel = onFx(() -> stage.getScene().getRoot().lookupAll(".label").stream()
                .map(n -> (javafx.scene.control.Label) n)
                .filter(l -> l.getMaxWidth() == 420 && l.isWrapText() && !l.getText().startsWith("Drawn on"))
                .findFirst()
                .orElse(null));
        long t0 = System.nanoTime();
        for (int i = 0; i < 1200; i++) {
            String st = statusLabel == null ? "" : onFx(statusLabel::getText);
            if (st.startsWith("Signal density (no threshold):") && !onFx(minField::getText).isBlank()) break;
            Thread.sleep(100);
        }
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
        for (int i = 0; i < 200 && !(onFx(viewer::getCustomPixelLayerOverlay) instanceof DensityMapOverlay); i++)
            Thread.sleep(50);
        var overlay = onFx(viewer::getCustomPixelLayerOverlay);
        check(overlay instanceof DensityMapOverlay, "the map is on the viewer's pixel layer");
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

        // Another image of the same slide (same size, different entry) can
        // show this image's map; here a second entry for the same file.
        var project = onFx(qupath::getProject);
        var twin = project.addImage(entry.getServerBuilder());
        twin.setImageName(entry.getImageName() + " (second view)");
        project.syncChanges();
        var listed = new java.util.HashMap<Path, String>();
        var maps = DensityOverlayDialog.mapsForImage(
                project, twin, server.getWidth(), server.getHeight(), listed);
        String label = maps.isEmpty() ? "" : listed.get(maps.get(0));
        note("maps offered for the second view: " + listed.values());
        check(!maps.isEmpty() && label.contains("(from " + entry.getImageName() + ")"),
                "a map made on another image of the same size is offered, labelled with its source");
        project.removeImage(twin, true);
        project.syncChanges();
    }

    /** "Add density channels as a new image": a new entry, saved, tagged, reopened as after a restart. */
    private static void channelsEntryScenario(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry, int w, int h) throws Exception {
        var builderBefore = entry.getServerBuilder();
        int imagesBefore = project.getImageList().size();
        Path map = DensitySidecar.sidecarPathFor(project, entry);
        var added = DensityChannelAttacher.addChannelsEntry(
                project, entry, map, DensityChannelAttacher.KIND_DENSITY, null);
        check(added != entry && project.getImageList().size() == imagesBefore + 1,
                "a new project entry holds the channels (" + added.getImageName() + ")");
        check(entry.getServerBuilder().equals(builderBefore), "the source entry's image is unchanged");
        var md = added.getMetadata();
        note("new entry metadata: " + md);
        check(DensityChannelAttacher.KIND_DENSITY.equals(md.get(DensityChannelAttacher.META_KIND))
                        && entry.getImageName().equals(md.get(DensityChannelAttacher.META_SOURCE))
                        && map.getFileName().toString().equals(md.get(DensityChannelAttacher.META_MAP)),
                "the new entry is tagged in the project metadata (kind, source image, map file)");
        var again = DensityChannelAttacher.addChannelsEntry(
                project, entry, map, DensityChannelAttacher.KIND_DENSITY, null);
        check(again.getID().equals(added.getID()) && project.getImageList().size() == imagesBefore + 1,
                "adding the same map again refreshes that entry instead of making another");

        // Reopen from disk, as QuPath would after a restart.
        Project<BufferedImage> reloaded = ProjectIO.loadProject(project.getPath().toFile(), BufferedImage.class);
        var reopened = reloaded.getImageList().stream()
                .filter(e -> e.getID().equals(added.getID()))
                .findFirst()
                .orElse(null);
        check(reopened != null, "the channels entry is in the saved project");
        if (reopened == null) return;
        ImageData<BufferedImage> data = reopened.readImageData();
        ImageServer<BufferedImage> server = data.getServer();
        ImageServer<BufferedImage> source = entry.getServerBuilder().build();
        try (DensityMapReader reader = DensityMapReader.open(map)) {
            DensityMapInfo info = reader.info();
            int sig = info.channelIndex("Signal density (no threshold)");
            float[] grid = reader.readChannel(sig);
            int srcCh = source.nChannels();
            check(server.nChannels() == srcCh + info.channels().size() && server.getWidth() == w && server.getHeight() == h,
                    "the reopened entry has the image's channels then the map's, at the image's size ("
                            + server.nChannels() + ")");
            check(server.getPixelType() == qupath.lib.images.servers.PixelType.FLOAT32,
                    "it is float32, so map values are not cut to integers");
            double worst = 0;
            int fractional = 0;
            int n = 0;
            for (int k = 1; k <= 9; k++) {
                int x = w * k / 10;
                int y = h * ((k * 7) % 10 + 1) / 12;
                float expect = info.sample(grid, x + 0.5, y + 0.5);
                if (!Float.isFinite(expect)) continue;
                Raster r = server.readRegion(RegionRequest.createInstance(server.getPath(), 1.0, x, y, 1, 1))
                        .getRaster();
                Raster s0 = source.readRegion(RegionRequest.createInstance(source.getPath(), 1.0, x, y, 1, 1))
                        .getRaster();
                worst = Math.max(worst, Math.abs(r.getSampleDouble(0, 0, srcCh + sig) - expect));
                worst = Math.max(worst, Math.abs(r.getSampleDouble(0, 0, 0) - s0.getSampleDouble(0, 0, 0)));
                if (expect != Math.rint(expect)) fractional++;
                n++;
            }
            check(n > 0 && worst < 1e-6 && fractional > 0, String.format(
                    "map and image values read back exactly through the channels entry (worst %.2e over %d points,"
                            + " %d fractional)", worst, n, fractional));
        } finally {
            server.close();
            source.close();
        }
    }

    private static void finish() {
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }


    private static Path run(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry, String mode,
            double stepUm, String threshold) throws Exception {
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                WINDOW_UM, OVERLAP, "Raw intensity", threshold, 2000, "none", 1.0, 4.0, 1.0, 1.0, false, 0.0, null,
                false, mode, stepUm, true, List.of(CLASS), true,
                List.of(new PixelPositivitySpec(0, "Channel 1", PixelPositivitySpec.OP_GT, POS_THRESHOLD)));
        long t0 = System.nanoTime();
        Thread t = new FiberDensityMapWorkflow().runForEntries(spec, List.of(entry), project, null);
        t.join();
        Path out = project.getPath().getParent().resolve("fiber-analysis/density-maps")
                .resolve(DensitySidecar.sanitize(entry.getImageName())
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
