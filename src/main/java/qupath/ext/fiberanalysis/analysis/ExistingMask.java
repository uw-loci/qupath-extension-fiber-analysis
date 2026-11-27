package qupath.ext.fiberanalysis.analysis;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import qupath.lib.classifiers.pixel.PixelClassificationImageServer;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * The fiber mask for "Use existing fiber mask": from a project pixel classifier, from the objects of one
 * class, or from a mask file of the whole image. Classifier and object masks are written per region as
 * 0/255 PNGs sized like the region image; a mask file is cut per region in Python.
 */
final class ExistingMask {

    static final String CLASSIFIER = "Pixel classifier";
    static final String OBJECT_CLASS = "Object class";
    static final String FILE = "File on disk";

    /** Output pixels read per classifier request, so a large region never needs one huge raster. */
    private static final int BLOCK_PX = 2048;

    private final String source;
    private final PixelClassifier classifier;
    private final String classifierName;
    private final ChannelType outputType;
    private final boolean uint8Output;
    private final int fiberLabel;
    private final int channelCount;
    private final PathClass fiberClass;
    private final Path maskFile;

    private ImageData<BufferedImage> serverData;
    private ImageServer<BufferedImage> classifierServer;
    private List<ROI> fiberRois;

    private ExistingMask(
            String source,
            PixelClassifier classifier,
            String classifierName,
            int fiberLabel,
            PathClass fiberClass,
            Path maskFile) {
        this.source = source;
        this.classifier = classifier;
        this.classifierName = classifierName;
        this.fiberLabel = fiberLabel;
        this.fiberClass = fiberClass;
        this.maskFile = maskFile;
        if (classifier != null) {
            this.outputType = classifier.getMetadata().getOutputType();
            this.uint8Output = classifier.getMetadata().getOutputPixelType() == PixelType.UINT8;
            this.channelCount = classifier.getMetadata().getOutputChannels().size();
        } else {
            this.outputType = null;
            this.uint8Output = false;
            this.channelCount = 0;
        }
    }

    /**
     * Resolves the mask source of a run. Returns null when the run segments within the extension.
     *
     * @param p run parameters
     * @param project the project the run was started from (pixel classifiers live there); may be null
     * @return the mask source
     * @throws IOException with a message for the user when the source cannot be used
     */
    static ExistingMask open(FiberAnalysisParams p, Project<BufferedImage> project) throws IOException {
        if (!"existing".equalsIgnoreCase(p.segSource())) return null;
        String source = p.maskSource() == null ? "" : p.maskSource();
        switch (source) {
            case CLASSIFIER: {
                String name = p.classifierName();
                if (name == null || name.isBlank()) {
                    throw new IOException("Use existing fiber mask: choose a pixel classifier.");
                }
                if (project == null) {
                    throw new IOException(
                            "Use existing fiber mask: pixel classifiers are read from the project, and no project is open.");
                }
                PixelClassifier classifier;
                try {
                    classifier = project.getPixelClassifiers().get(name);
                } catch (Exception e) {
                    throw new IOException("Could not load the pixel classifier '" + name + "': " + e.getMessage(), e);
                }
                if (classifier == null) {
                    throw new IOException("The project has no pixel classifier named '" + name + "'.");
                }
                return forClassifier(classifier, name, p.objectClass());
            }
            case OBJECT_CLASS: {
                String want = p.objectClass();
                if (want == null || want.isBlank()) {
                    throw new IOException("Use existing fiber mask: choose the class of the fiber objects.");
                }
                return forObjects(want);
            }
            case FILE: {
                String f = p.maskFile();
                if (f == null || f.isBlank()) {
                    throw new IOException("Use existing fiber mask: choose a mask file.");
                }
                Path path = Path.of(f);
                if (!Files.isRegularFile(path)) {
                    throw new IOException("The mask file does not exist: " + f);
                }
                return new ExistingMask(FILE, null, null, -1, null, path);
            }
            default:
                throw new IOException("Unknown mask source '" + source + "'.");
        }
    }

    /**
     * A mask source from a pixel classifier.
     *
     * @param classifier the classifier
     * @param name its name in the project, for messages
     * @param fiberClass the class that marks fibers
     * @return the mask source
     * @throws IOException when the classifier gives no classes or lacks the fiber class
     */
    static ExistingMask forClassifier(PixelClassifier classifier, String name, String fiberClass) throws IOException {
        ChannelType type = classifier.getMetadata().getOutputType();
        if (type != ChannelType.CLASSIFICATION
                && type != ChannelType.PROBABILITY
                && type != ChannelType.MULTICLASS_PROBABILITY) {
            throw new IOException("The pixel classifier '" + name + "' outputs " + type
                    + ", not classes or probabilities, so it cannot give a fiber mask.");
        }
        Map<Integer, PathClass> labels = classifier.getMetadata().getClassificationLabels();
        Integer label = labelFor(labels, fiberClass);
        if (label == null) {
            throw new IOException("Fiber class '" + (fiberClass == null ? "" : fiberClass)
                    + "' is not one of the classes of the pixel classifier '" + name + "' ("
                    + describe(labels.values()) + "). Choose the class that marks fibers.");
        }
        return new ExistingMask(CLASSIFIER, classifier, name, label, null, null);
    }

    /**
     * A mask source from the objects of one class.
     *
     * @param fiberClass the class of the fiber objects
     * @return the mask source
     */
    static ExistingMask forObjects(String fiberClass) {
        return new ExistingMask(OBJECT_CLASS, null, null, -1, PathClass.fromString(fiberClass), null);
    }

    /**
     * The classifier label (pixel value, or channel number for probabilities) of a class name.
     *
     * @param labels the classifier's labels
     * @param className the class name
     * @return the label, or null when the classifier has no such class
     */
    static Integer labelFor(Map<Integer, PathClass> labels, String className) {
        if (className == null || className.isBlank()) return null;
        for (Map.Entry<Integer, PathClass> e : labels.entrySet()) {
            if (e.getValue() != null && className.equals(e.getValue().toString())) return e.getKey();
        }
        return null;
    }

    /**
     * The class to offer first for a classifier: one whose name mentions fiber or collagen, else the first
     * class that is not an "Ignore" class.
     *
     * @param classNames the classifier's class names, in label order
     * @return the suggested class, or null when there are none
     */
    static String suggestFiberClass(List<String> classNames) {
        for (String n : classNames) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.contains("fiber") || l.contains("fibre") || l.contains("collagen")) return n;
        }
        for (String n : classNames) {
            if (!n.toLowerCase(Locale.ROOT).startsWith("ignore")) return n;
        }
        return classNames.isEmpty() ? null : classNames.get(0);
    }

    private static String describe(Collection<PathClass> classes) {
        return classes.stream()
                .filter(Objects::nonNull)
                .map(PathClass::toString)
                .collect(Collectors.joining(", "));
    }

    /** @return true when {@link #write} makes the region mask (classifier or objects), false for a mask file */
    boolean writesMask() {
        return !FILE.equals(source);
    }

    /** @return the mask file, for the file source */
    Path maskFile() {
        return maskFile;
    }

    /** @return the progress line shown while the mask of a region is made */
    String progressText() {
        return CLASSIFIER.equals(source) ? "Applying the pixel classifier..." : "Drawing the fiber objects...";
    }

    /** @return a short description for the log and the run notes */
    String describe() {
        switch (source) {
            case CLASSIFIER:
                return "pixel classifier '" + classifierName + "', class '"
                        + classifier.getMetadata().getClassificationLabels().get(fiberLabel) + "'";
            case OBJECT_CLASS:
                return "objects of class '" + fiberClass + "'";
            default:
                return "mask file " + maskFile;
        }
    }

    /**
     * Checks the source against one image before any region is read.
     *
     * @param data the image
     * @throws IOException when the classifier cannot run on it or the image has no objects of the class
     */
    void checkImage(ImageData<BufferedImage> data) throws IOException {
        if (CLASSIFIER.equals(source) && !classifier.supportsImage(data)) {
            throw new IOException("The pixel classifier '" + classifierName
                    + "' cannot run on this image (its channels or pixel size do not match what it was trained on).");
        }
        if (OBJECT_CLASS.equals(source) && rois(data).isEmpty()) {
            throw new IOException(
                    "The image has no objects of class '" + fiberClass + "' with an area to use as the fiber mask.");
        }
    }

    /**
     * Writes the fiber mask of one region as a 0/255 PNG.
     *
     * @param data the image
     * @param regionX region origin, full-resolution pixels
     * @param regionY region origin, full-resolution pixels
     * @param downsample full-resolution pixels per region pixel
     * @param width region width in region pixels (the region image's width)
     * @param height region height in region pixels
     * @param out the PNG to write
     * @return the number of fiber pixels
     * @throws IOException when the classifier or the write fails
     */
    long write(
            ImageData<BufferedImage> data, int regionX, int regionY, double downsample, int width, int height, Path out)
            throws IOException {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        if (CLASSIFIER.equals(source)) {
            fillFromClassifier(data, regionX, regionY, downsample, mask);
        } else if (OBJECT_CLASS.equals(source)) {
            fillFromObjects(data, regionX, regionY, downsample, mask);
        } else {
            throw new IllegalStateException("A mask file is cut in Python, not written here");
        }
        long fiber = 0;
        Raster r = mask.getRaster();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            r.getSamples(0, y, width, 1, 0, row);
            for (int v : row) if (v != 0) fiber++;
        }
        javax.imageio.ImageIO.write(mask, "PNG", out.toFile());
        return fiber;
    }

    private synchronized ImageServer<BufferedImage> classifierServer(ImageData<BufferedImage> data) {
        if (classifierServer == null || serverData != data) {
            classifierServer = new PixelClassificationImageServer(data, classifier);
            serverData = data;
        }
        return classifierServer;
    }

    private void fillFromClassifier(
            ImageData<BufferedImage> data, int regionX, int regionY, double ds, BufferedImage mask) throws IOException {
        ImageServer<BufferedImage> server = classifierServer(data);
        double cds = server.getDownsampleForResolution(0);
        int imgW = server.getWidth();
        int imgH = server.getHeight();
        int w = mask.getWidth();
        int h = mask.getHeight();
        var out = mask.getRaster();
        for (int by = 0; by < h; by += BLOCK_PX) {
            int bh = Math.min(BLOCK_PX, h - by);
            for (int bx = 0; bx < w; bx += BLOCK_PX) {
                int bw = Math.min(BLOCK_PX, w - bx);
                // This block in full-resolution pixels, clipped to the image.
                int fx0 = (int) Math.floor(regionX + bx * ds);
                int fy0 = (int) Math.floor(regionY + by * ds);
                int fx1 = Math.min(imgW, (int) Math.ceil(regionX + (bx + bw) * ds));
                int fy1 = Math.min(imgH, (int) Math.ceil(regionY + (by + bh) * ds));
                fx0 = Math.max(0, fx0);
                fy0 = Math.max(0, fy0);
                if (fx1 <= fx0 || fy1 <= fy0) continue;
                BufferedImage cls = server.readRegion(
                        RegionRequest.createInstance(server.getPath(), cds, fx0, fy0, fx1 - fx0, fy1 - fy0));
                if (cls == null) continue;
                Raster in = cls.getRaster();
                int cw = in.getWidth();
                int ch = in.getHeight();
                int bands = in.getNumBands();
                double[] px = new double[bands];
                for (int y = 0; y < bh; y++) {
                    // Nearest neighbor: the center of this region pixel in the classifier output.
                    double fy = regionY + (by + y + 0.5) * ds;
                    int sy = (int) Math.floor((fy - fy0) / cds);
                    if (sy < 0 || sy >= ch) continue;
                    for (int x = 0; x < bw; x++) {
                        double fx = regionX + (bx + x + 0.5) * ds;
                        int sx = (int) Math.floor((fx - fx0) / cds);
                        if (sx < 0 || sx >= cw) continue;
                        in.getPixel(sx, sy, px);
                        if (isFiber(px)) out.setSample(bx + x, by + y, 0, 255);
                    }
                }
            }
        }
    }

    private boolean isFiber(double[] px) {
        switch (outputType) {
            case CLASSIFICATION:
                return (int) px[0] == fiberLabel;
            case MULTICLASS_PROBABILITY:
                return fiberLabel < px.length && px[fiberLabel] >= (uint8Output ? 127.5 : 0.5);
            case PROBABILITY:
            default: {
                if (fiberLabel >= px.length) return false;
                int best = 0;
                for (int i = 1; i < Math.min(px.length, Math.max(1, channelCount)); i++) {
                    if (px[i] > px[best]) best = i;
                }
                return best == fiberLabel && px[fiberLabel] > 0;
            }
        }
    }

    private synchronized List<ROI> rois(ImageData<BufferedImage> data) {
        if (fiberRois == null || serverData != data) {
            List<ROI> list = new ArrayList<>();
            for (PathObject o : data.getHierarchy().getAllObjects(false)) {
                ROI roi = o.getROI();
                if (roi != null && roi.isArea() && fiberClass.equals(o.getPathClass())) list.add(roi);
            }
            fiberRois = list;
            serverData = data;
        }
        return fiberRois;
    }

    private void fillFromObjects(
            ImageData<BufferedImage> data, int regionX, int regionY, double ds, BufferedImage mask) {
        double fx1 = regionX + mask.getWidth() * ds;
        double fy1 = regionY + mask.getHeight() * ds;
        Graphics2D g = mask.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            AffineTransform at = AffineTransform.getScaleInstance(1.0 / ds, 1.0 / ds);
            at.translate(-regionX, -regionY);
            g.setTransform(at);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            for (ROI roi : rois(data)) {
                if (roi.getBoundsX() > fx1
                        || roi.getBoundsY() > fy1
                        || roi.getBoundsX() + roi.getBoundsWidth() < regionX
                        || roi.getBoundsY() + roi.getBoundsHeight() < regionY) continue;
                Shape s = roi.getShape();
                if (s != null) g.fill(s);
            }
        } finally {
            g.dispose();
        }
    }
}
