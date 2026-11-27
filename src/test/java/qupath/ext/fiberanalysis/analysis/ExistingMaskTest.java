package qupath.ext.fiberanalysis.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** The pixel-classifier and object-class fiber masks, read back pixel by pixel. */
class ExistingMaskTest {

    private static final int IMG_W = 400;
    private static final int IMG_H = 300;
    /** Full-resolution x range the fake classifier calls fiber. */
    private static final int FIBER_X0 = 100;

    private static final int FIBER_X1 = 200;

    @TempDir
    Path tmp;

    private static ImageData<BufferedImage> image() {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_BYTE_GRAY);
        return new ImageData<>(new WrappedBufferedImageServer("test", img));
    }

    private static Map<Integer, PathClass> labels() {
        Map<Integer, PathClass> labels = new LinkedHashMap<>();
        labels.put(0, PathClass.fromString("Background"));
        labels.put(1, PathClass.fromString("Fiber"));
        return labels;
    }

    /** A classifier that runs at downsample 2 and labels x in [100, 200) as Fiber. */
    private static PixelClassifier stripeClassifier(ChannelType type) {
        PixelClassifierMetadata meta = new PixelClassifierMetadata.Builder()
                .inputResolution(new PixelCalibration.Builder()
                        .pixelSizeMicrons(2.0, 2.0)
                        .build())
                .inputShape(64, 64)
                .setChannelType(type)
                .outputPixelType(PixelType.UINT8)
                .classificationLabels(labels())
                .build();
        return new PixelClassifier() {
            @Override
            public boolean supportsImage(ImageData<BufferedImage> imageData) {
                return true;
            }

            @Override
            public BufferedImage applyClassification(ImageData<BufferedImage> imageData, RegionRequest request) {
                double ds = request.getDownsample();
                int w = (int) Math.round(request.getWidth() / ds);
                int h = (int) Math.round(request.getHeight() / ds);
                boolean prob = type != ChannelType.CLASSIFICATION;
                BufferedImage out;
                if (prob) {
                    ColorModel cm = ColorModelFactory.getProbabilityColorModel8Bit(meta.getOutputChannels());
                    out = new BufferedImage(cm, cm.createCompatibleWritableRaster(w, h), false, null);
                } else {
                    out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                }
                WritableRaster r = out.getRaster();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        double fx = request.getX() + x * ds;
                        boolean fiber = fx >= FIBER_X0 && fx < FIBER_X1;
                        if (prob) {
                            // Fiber wins by a little, so argmax and the 0.5 cut agree.
                            r.setSample(x, y, 0, fiber ? 100 : 200);
                            r.setSample(x, y, 1, fiber ? 155 : 55);
                        } else {
                            r.setSample(x, y, 0, fiber ? 1 : 0);
                        }
                    }
                }
                return out;
            }

            @Override
            public PixelClassifierMetadata getMetadata() {
                return meta;
            }
        };
    }

    @Test
    void labelForMatchesTheClassName() {
        assertEquals(1, ExistingMask.labelFor(labels(), "Fiber"));
        assertNull(ExistingMask.labelFor(labels(), "fiber"));
        assertNull(ExistingMask.labelFor(labels(), ""));
    }

    @Test
    void suggestsAFiberOrCollagenClassFirst() {
        assertEquals("Collagen", ExistingMask.suggestFiberClass(List.of("Ignore*", "Stroma", "Collagen")));
        assertEquals("Stroma", ExistingMask.suggestFiberClass(List.of("Ignore*", "Stroma", "Other")));
        assertNull(ExistingMask.suggestFiberClass(List.of()));
    }

    @Test
    void refusesAClassTheClassifierDoesNotHave() {
        IOException e = assertThrows(
                IOException.class,
                () -> ExistingMask.forClassifier(stripeClassifier(ChannelType.CLASSIFICATION), "c", "Collagen"));
        assertTrue(e.getMessage().contains("Background, Fiber"), e.getMessage());
    }

    /** Fiber pixels expected in a region whose pixel centers fall in [FIBER_X0, FIBER_X1). */
    private static boolean expectedFiber(int regionX, double ds, int x) {
        double fx = regionX + (x + 0.5) * ds;
        return fx >= FIBER_X0 && fx < FIBER_X1;
    }

    private void assertStripe(ChannelType type, int regionX, int regionY, double ds) throws IOException {
        ImageData<BufferedImage> data = image();
        ExistingMask m = ExistingMask.forClassifier(stripeClassifier(type), "stripe", "Fiber");
        m.checkImage(data);
        int w = (int) Math.floor((IMG_W - regionX) / ds) - 1;
        int h = 40;
        Path png = tmp.resolve("m_" + type + "_" + ds + ".png");
        long fiber = m.write(data, regionX, regionY, ds, w, h, png);
        BufferedImage mask = ImageIO.read(png.toFile());
        assertEquals(w, mask.getWidth());
        assertEquals(h, mask.getHeight());
        long expected = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean want = expectedFiber(regionX, ds, x);
                if (want) expected++;
                assertEquals(
                        want ? 255 : 0,
                        mask.getRaster().getSample(x, y, 0),
                        type + " ds " + ds + " pixel (" + x + "," + y + ")");
            }
        }
        assertEquals(expected, fiber);
        assertTrue(expected > 0);
    }

    @Test
    void classifierMaskAtTheRegionsOwnScale() throws IOException {
        // Classifier at downsample 2; the region read at 1, 2 and 3.
        assertStripe(ChannelType.CLASSIFICATION, 50, 20, 1.0);
        assertStripe(ChannelType.CLASSIFICATION, 50, 20, 2.0);
        assertStripe(ChannelType.CLASSIFICATION, 30, 10, 3.0);
    }

    @Test
    void probabilityClassifiersUseTheFiberChannel() throws IOException {
        assertStripe(ChannelType.PROBABILITY, 50, 20, 1.0);
        assertStripe(ChannelType.MULTICLASS_PROBABILITY, 50, 20, 2.0);
    }

    @Test
    void objectMaskFillsOnlyTheFiberClass() throws IOException {
        ImageData<BufferedImage> data = image();
        data.getHierarchy()
                .addObject(PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(120, 40, 60, 30, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Fiber")));
        data.getHierarchy()
                .addObject(PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(200, 40, 20, 20, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Other")));
        ExistingMask m = ExistingMask.forObjects("Fiber");
        m.checkImage(data);
        Path png = tmp.resolve("objects.png");
        long fiber = m.write(data, 100, 20, 2.0, 70, 40, png);
        // 60 x 30 full-resolution pixels at downsample 2.
        assertEquals(30 * 15, fiber);
        BufferedImage mask = ImageIO.read(png.toFile());
        assertEquals(255, mask.getRaster().getSample((130 - 100) / 2, (50 - 20) / 2, 0));
        assertEquals(0, mask.getRaster().getSample((210 - 100) / 2, (50 - 20) / 2, 0));
    }

    @Test
    void objectMaskRefusesAnImageWithoutTheClass() {
        ExistingMask m = ExistingMask.forObjects("Fiber");
        IOException e = assertThrows(IOException.class, () -> m.checkImage(image()));
        assertTrue(e.getMessage().contains("Fiber"), e.getMessage());
    }

    @Test
    void provenanceNamesTheClassifierAndItsClass() throws IOException {
        Map<String, Object> p = ExistingMask.forClassifier(
                        stripeClassifier(ChannelType.CLASSIFICATION), "stripe", "Fiber")
                .provenance();
        assertEquals("Pixel classifier", p.get("source"));
        assertEquals("stripe", p.get("classifier"));
        assertEquals("Fiber", p.get("fiber_class"));
        assertEquals("CLASSIFICATION", p.get("output_type"));
    }
}
