/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javax.imageio.ImageIO;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.fiberanalysis.analysis.DensityMapInfo;
import qupath.ext.fiberanalysis.analysis.DensityMapReader;
import qupath.ext.fiberanalysis.analysis.DensityRegion;
import qupath.ext.fiberanalysis.analysis.DensitySidecar;
import qupath.ext.fiberanalysis.analysis.FiberDensityMapWorkflow;
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/**
 * An annotation-limited density map on a real biref image: the same values as a
 * whole-image map where it computed, NaN elsewhere, faster, recorded in the
 * map file, and placed by QuIET exactly like a whole-image map.
 *
 * <p>Args: {@code <image> <projectDir> <outDir> <envBaseDir> <quietJar>}.
 */
public final class DensityRegionScenario {

    private static int failures = 0;

    static final double WINDOW_UM = 15.0;
    static final String COVERAGE = "Fiber coverage (%)";
    static final String PATTERN = "{project}/fiber-analysis/density-maps/{imageSafe}_density.ome.tif";

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + what);
        if (!condition) failures++;
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) {
        try {
            scenario(args);
        } catch (Throwable t) {
            // Without this the JavaFX thread keeps the JVM alive and the run never ends.
            t.printStackTrace();
            check(false, "the scenario ran without an exception (" + t + ")");
            finish();
        }
    }

    private static void scenario(String[] args) throws Exception {
        Path image = Path.of(args[0]);
        Path projectDir = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Path envBase = Path.of(args[3]);
        Path quietJar = Path.of(args[4]);
        Files.createDirectories(outDir);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        onFx(() -> {
            FiberAnalysisPreferences.setEnvBaseDir(envBase.toString());
            return null;
        });

        Files.createDirectories(projectDir);
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        ImageServer<BufferedImage> src = ImageServers.buildServer(image.toUri().toString());
        ProjectImageEntry<BufferedImage> entry = project.addImage(src.getBuilder());
        entry.setImageName(image.getFileName().toString());
        ImageData<BufferedImage> data = entry.readImageData();
        data.setImageType(ImageData.ImageType.FLUORESCENCE);
        int w = src.getWidth();
        int h = src.getHeight();
        double px = src.getPixelCalibration().getAveragedPixelSizeMicrons();
        // An irregular "tissue" region (two overlapping ellipses), a second class that must stay out,
        // and a small annotation used for the selected mode.
        PathObject tissueA = PathObjects.createAnnotationObject(
                ROIs.createEllipseROI(1500, 1200, 3600, 2600, ImagePlane.getDefaultPlane()), PathClass.fromString("Tissue"));
        PathObject tissueB = PathObjects.createAnnotationObject(
                ROIs.createEllipseROI(4200, 2400, 2400, 2200, ImagePlane.getDefaultPlane()), PathClass.fromString("Tissue"));
        PathObject other = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(8000, 5500, 1200, 1200, ImagePlane.getDefaultPlane()), PathClass.fromString("Other"));
        other.setName("Reference 1");
        data.getHierarchy().addObjects(List.of(tissueA, tissueB, other));
        entry.saveImageData(data);
        project.syncChanges();
        onFx(() -> {
            qupath.setProject(project);
            return null;
        });
        note("image " + w + "x" + h + " at " + px + " um/px");

        Path mapPath = DensitySidecar.sidecarPathFor(project, entry);

        // 1. Whole image and 2. inside the Tissue class, both at one fixed cut so the values compare.
        long tWhole = run(project, entry, DensityRegion.wholeImage(), "manual");
        Path whole = Files.copy(mapPath, outDir.resolve("whole.ome.tif"), StandardCopyOption.REPLACE_EXISTING);
        long tTissue = run(project, entry, DensityRegion.ofClasses(List.of("Tissue")), "manual");
        Path tissue = Files.copy(mapPath, outDir.resolve("tissue.ome.tif"), StandardCopyOption.REPLACE_EXISTING);
        double tissueFrac = 0;

        Geometry region = tissueA.getROI().getGeometry().union(tissueB.getROI().getGeometry());
        try (DensityMapReader wr = DensityMapReader.open(whole);
                DensityMapReader tr = DensityMapReader.open(tissue)) {
            DensityMapInfo wi = wr.info();
            DensityMapInfo ti = tr.info();
            check(wi.lattice().equals(ti.lattice()) && ti.fits(w, h),
                    "the limited map has the whole-image grid and placement (" + ti.lattice() + ")");
            check(wi.region() != null && wi.region().equals("whole image"), "the whole map records 'Region: whole image'");
            check(ti.region() != null && ti.region().startsWith("area annotations of class Tissue; 2 annotation(s)"),
                    "the limited map records its region: " + ti.region());
            int gw = ti.lattice().gridW();
            int gh = ti.lattice().gridH();
            int cell = ti.lattice().cellPx();
            int win = ti.lattice().windowPx();
            long expected = 0;
            long wrongNan = 0;
            long wrongValue = 0;
            long leaked = 0;
            for (int c = 0; c < ti.channels().size(); c++) {
                float[] a = wr.readChannel(c);
                float[] b = tr.readChannel(c);
                for (int j = 0; j < gh; j++) {
                    for (int i = 0; i < gw; i++) {
                        int k = j * gw + i;
                        // Independent of the code under test: does this window overlap the region?
                        Geometry box = region.getFactory()
                                .toGeometry(new Envelope(i * cell, i * cell + win, j * cell, j * cell + win));
                        boolean in = region.intersects(box) && region.intersection(box).getArea() > 0;
                        if (c == 0 && in) expected++;
                        if (!in) {
                            if (!Float.isNaN(b[k])) leaked++;
                        } else if (Float.isNaN(a[k]) != Float.isNaN(b[k])) {
                            wrongNan++;
                        } else if (!Float.isNaN(a[k]) && Float.compare(a[k], b[k]) != 0) {
                            wrongValue++;
                        }
                    }
                }
            }
            tissueFrac = expected / (double) ((long) gw * gh);
            note(String.format("%d of %d windows touch the region (%.1f%%)", expected, (long) gw * gh, 100 * tissueFrac));
            check(leaked == 0, "every window outside the region is NaN, in every channel (" + leaked + " leaked)");
            check(wrongNan == 0 && wrongValue == 0,
                    "inside the region every value equals the whole-image map's (" + wrongNan + " NaN mismatches, "
                            + wrongValue + " value mismatches)");
        }
        double speedup = tWhole / (double) Math.max(1, tTissue);
        note(String.format("whole image %.1f s, inside Tissue %.1f s: %.2fx faster", tWhole / 1e9, tTissue / 1e9, speedup));
        check(tTissue < tWhole, "the limited run is faster than the whole-image run");

        // 3. QuIET 1.4.0 reads the limited map from its file pattern and places it on the same cells.
        quietCheck(quietJar, project, entry, tissue, outDir, region);

        // 4. The run record and the re-run script carry the region.
        // Only the suffix: the image name itself contains ".ome.tif".
        String base = mapPath.getFileName().toString();
        base = base.substring(0, base.length() - ".ome.tif".length());
        String params = Files.readString(mapPath.getParent().resolve(base + "_params.json"));
        String rerun = Files.readString(mapPath.getParent().resolve(base + "_rerun.groovy"));
        check(params.contains("\"regionMode\": \"classes\"") || params.contains("\"regionMode\":\"classes\""),
                "the params JSON records regionMode classes");
        check(params.contains("Tissue"), "the params JSON records the class");
        check(rerun.contains("//   area annotations of class Tissue; 2 annotation(s)"),
                "the re-run script names the region");
        var replay = FiberDensityMapWorkflow.DensityMapJobSpec.fromJson(
                mapPath.getParent().resolve(base + "_params.json").toFile());
        check(replay.region.mode == DensityRegion.Mode.CLASSES && replay.region.classes.equals(List.of("Tissue")),
                "the re-run replays the region");

        // 5. Selected annotations, found by ID, with a pooled Otsu fitted inside them.
        long tSel = run(project, entry, DensityRegion.selected(List.of(other)), "otsu");
        try (DensityMapReader sr = DensityMapReader.open(mapPath)) {
            DensityMapInfo si = sr.info();
            check(si.region() != null && si.region().startsWith("selected annotations (1): Reference 1; 1 annotation(s)"),
                    "a selected-annotation map records which annotation: " + si.region());
            String thr = si.threshold();
            check(thr != null && thr.contains("inside selected annotations"),
                    "the pooled threshold was fitted inside the selection: " + thr);
            float[] cov = sr.readChannel(si.channelIndex(COVERAGE));
            int gw = si.lattice().gridW();
            int cell = si.lattice().cellPx();
            long kept = 0;
            boolean outside = false;
            for (int k = 0; k < cov.length; k++) {
                if (Float.isNaN(cov[k])) continue;
                kept++;
                int i = k % gw;
                int j = k / gw;
                if (i * cell + si.lattice().windowPx() <= 8000 || i * cell >= 9200
                        || j * cell + si.lattice().windowPx() <= 5500 || j * cell >= 6700) outside = true;
            }
            check(kept > 0 && !outside, "the selected-annotation map has values only on windows touching it (" + kept + ")");
        }
        note(String.format("selected annotation run %.1f s", tSel / 1e9));

        // 6. An image with no matching annotation is skipped, not failed, and writes nothing.
        Files.deleteIfExists(mapPath);
        run(project, entry, DensityRegion.ofClasses(List.of("NoSuchClass")), "manual");
        check(!Files.exists(mapPath), "an image with no matching annotation writes no map");

        note(String.format("RESULT speedup %.2fx at %.1f%% of windows in the region", speedup, 100 * tissueFrac));
        finish();
    }

    private static void quietCheck(Path jar, Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry,
            Path tissueMap, Path outDir, Geometry region) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(
                new URL[] {jar.toUri().toURL()}, DensityRegionScenario.class.getClassLoader())) {
            Path mapPath = DensitySidecar.sidecarPathFor(project, entry);
            Files.copy(tissueMap, mapPath, StandardCopyOption.REPLACE_EXISTING);
            Class<?> sourceType = cl.loadClass("qupath.ext.quiet.export.MapOverlaySource");
            Object source = sourceType.getMethod("ofImageFiles", Project.class, String.class, String.class)
                    .invoke(null, project, PATTERN, COVERAGE);
            ImageData<BufferedImage> data = entry.readImageData();
            Object layer = sourceType.getMethod("open", ImageData.class).invoke(source, data);
            Method values = layer.getClass().getDeclaredMethod(
                    "values", int.class, int.class, int.class, int.class, double.class, int.class, int.class);
            values.setAccessible(true);
            try (DensityMapReader r = DensityMapReader.open(mapPath)) {
                DensityMapInfo info = r.info();
                int gw = info.lattice().gridW();
                int gh = info.lattice().gridH();
                int cell = info.lattice().cellPx();
                check(info.lattice().windowPx() == cell, "the check map has no window overlap, so cells are windows");
                float[] ours = r.readChannel(info.channelIndex(COVERAGE));
                // One output pixel per cell: QuIET samples each at the cell's centre.
                float[] theirs = (float[]) values.invoke(layer, 0, 0, gw * cell, gh * cell, (double) cell, gw, gh);
                long nanDiff = 0;
                long valDiff = 0;
                for (int k = 0; k < ours.length; k++) {
                    if (Float.isNaN(ours[k]) != Float.isNaN(theirs[k])) nanDiff++;
                    else if (!Float.isNaN(ours[k]) && Math.abs(ours[k] - theirs[k]) > 1e-4) valDiff++;
                }
                check(nanDiff == 0 && valDiff == 0,
                        "QuIET reads the limited map from '" + PATTERN + "' onto the same cells (" + nanDiff
                                + " NaN and " + valDiff + " value differences of " + ours.length + ")");

                // A picture to look at: the biref image near the region's edge, the map over it, the outline.
                var env = region.getEnvelopeInternal();
                int x = (int) env.getMinX() - 300;
                int y = (int) (env.getMinY() + env.getHeight() / 2) - 900;
                int side = 1800;
                double ds = 2.0;
                int out = (int) (side / ds);
                Method paint = layer.getClass().getMethod("paint", int.class, int.class, int.class, int.class,
                        double.class, int.class, int.class, ColorMaps.ColorMap.class, double.class, double.class,
                        boolean.class);
                BufferedImage overlay = (BufferedImage) paint.invoke(layer, x, y, side, side, ds, out, out,
                        ColorMaps.getDefaultColorMap(), 0.0, 60.0, false);
                BufferedImage raw = data.getServer().readRegion(
                        RegionRequest.createInstance(data.getServer().getPath(), ds, x, y, side, side));
                BufferedImage pic = new BufferedImage(out * 2, out, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = pic.createGraphics();
                BufferedImage gray = toGray(raw);
                g.drawImage(gray, 0, 0, out, out, null);
                g.drawImage(gray, out, 0, out, out, null);
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.6f));
                g.drawImage(overlay, out, 0, out, out, null);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                var shape = qupath.lib.roi.GeometryTools.geometryToShape(region);
                for (int panel = 0; panel < 2; panel++) {
                    Graphics2D gp = (Graphics2D) g.create(panel * out, 0, out, out);
                    gp.scale(1 / ds, 1 / ds);
                    gp.translate(-x, -y);
                    gp.setStroke(new BasicStroke((float) (2 * ds)));
                    gp.setColor(Color.WHITE);
                    gp.draw(shape);
                    gp.dispose();
                }
                g.dispose();
                Path png = outDir.resolve("region_edge_quiet.png");
                ImageIO.write(pic, "png", png.toFile());
                note("picture: " + png + " (left: biref with the region outline; right: QuIET's coverage map over it)");
            }
            if (layer instanceof AutoCloseable c) c.close();
        }
    }

    private static BufferedImage toGray(BufferedImage raw) {
        var r = raw.getRaster();
        int w = raw.getWidth();
        int h = raw.getHeight();
        float[] s = r.getSamples(0, 0, w, h, 0, (float[]) null);
        float[] sorted = s.clone();
        java.util.Arrays.sort(sorted);
        float lo = sorted[(int) (sorted.length * 0.01)];
        float hi = sorted[(int) (sorted.length * 0.995)];
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        var o = out.getRaster();
        for (int i = 0; i < s.length; i++) {
            int v = (int) Math.max(0, Math.min(255, 255 * (s[i] - lo) / Math.max(1e-6f, hi - lo)));
            o.setSample(i % w, i / w, 0, v);
        }
        return out;
    }

    /** @return wall time in nanoseconds */
    private static long run(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry,
            DensityRegion region, String threshold) throws Exception {
        var spec = new FiberDensityMapWorkflow.DensityMapJobSpec(
                WINDOW_UM, 0, "Raw intensity", threshold, 2000, 0.1, "none", 1.0, 4.0, 1.0, 1.0, false, 0.0, null,
                false, "window", 0, false, List.of(), false, List.of())
                .withRegion(region);
        long t0 = System.nanoTime();
        Thread t = new FiberDensityMapWorkflow().runForEntries(spec, List.of(entry), project, null);
        t.join();
        long dt = System.nanoTime() - t0;
        note(String.format("%s (%s): %.1f s", region.describe(), threshold, dt / 1e9));
        return dt;
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
