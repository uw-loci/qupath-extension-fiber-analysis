package qupath.ext.fiberanalysis.analysis;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.BufferedImageOverlay;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;

/**
 * Renders a per-annotation overlay PNG (fiber mask, straightness heatmap, GLCM
 * heatmap, or morphometric heatmap) as a viewer overlay aligned to the analyzed
 * region.
 *
 * <p>Mirrors {@code qupath.ext.ppm.analysis.PPMOrientationOverlayController}:
 * one overlay at a time, lives in QuPath's custom pixel-layer slot, honors the
 * master opacity slider. Toggling on a different overlay (different annotation
 * or different metric on the same annotation) replaces the previous overlay.</p>
 */
public final class FiberAnalysisOverlayController {

    private static final Logger logger = LoggerFactory.getLogger(FiberAnalysisOverlayController.class);

    private static FiberAnalysisOverlayController instance;

    public static synchronized FiberAnalysisOverlayController getInstance() {
        if (instance == null) {
            instance = new FiberAnalysisOverlayController();
            instance.attachImageChangeListener();
        }
        return instance;
    }

    private BufferedImageOverlay currentOverlay;
    private QuPathViewer currentViewer;
    private String currentToken;
    private boolean imageListenerAttached;

    /**
     * JavaFX property that broadcasts the currently-active overlay token (or
     * {@code null} when no overlay is showing). Panel cards subscribe to it
     * so each control (ToggleButton, ComboBox) can light up / un-light itself
     * when the active overlay changes -- the user gets one source of truth
     * for "what is currently on screen" without per-control Show buttons.
     */
    private final ReadOnlyStringWrapper activeTokenProperty = new ReadOnlyStringWrapper(this, "activeToken", null);

    public ReadOnlyStringProperty activeTokenProperty() {
        return activeTokenProperty.getReadOnlyProperty();
    }

    private FiberAnalysisOverlayController() {}

    /**
     * Registers a listener on {@code QuPathGUI.imageDataProperty()} so an
     * overlay painted for image A is removed when the user switches to image
     * B. Without this the BufferedImageOverlay stays installed on the
     * viewer's custom-pixel-layer slot, painting the old PNG at the original
     * pixel offsets over a totally different image. Idempotent: re-runs are
     * cheap no-ops because the JavaFX property listener is added exactly once.
     */
    private void attachImageChangeListener() {
        if (imageListenerAttached) return;
        Platform.runLater(() -> {
            QuPathGUI qupath = QuPathGUI.getInstance();
            if (qupath == null) return;
            qupath.imageDataProperty().addListener((obs, oldData, newData) -> {
                if (oldData != newData) {
                    clear();
                }
            });
            imageListenerAttached = true;
        });
    }

    /**
     * Returns the token of the currently-displayed overlay, or null if none.
     * Used by the panel to keep its toggle UI consistent.
     */
    public synchronized String getActiveToken() {
        return currentToken;
    }

    /**
     * Shows the given overlay PNG anchored at the analyzed region's bounds.
     *
     * @param pngPath  absolute path to the overlay PNG
     * @param offsetX  region top-left x in full-res image pixels
     * @param offsetY  region top-left y
     * @param regionW  region width in pixels
     * @param regionH  region height in pixels
     * @param token    caller-supplied identifier (e.g. "annotation_3:fiber_mask")
     *                 recorded so the panel can tell which overlay is active
     */
    public void show(Path pngPath, int offsetX, int offsetY, int regionW, int regionH, String token) {
        if (pngPath == null || !Files.exists(pngPath)) {
            logger.warn("Fiber analysis overlay PNG not found: {}", pngPath);
            clear();
            return;
        }
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath == null) {
            logger.warn("QuPathGUI not available; cannot show fiber analysis overlay");
            return;
        }
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null) {
            logger.warn("No active viewer; cannot show fiber analysis overlay");
            return;
        }

        BufferedImage img;
        try (InputStream in = Files.newInputStream(pngPath)) {
            img = ImageIO.read(in);
        } catch (Exception e) {
            logger.warn("Failed to read fiber analysis overlay PNG {}: {}", pngPath, e.getMessage());
            return;
        }
        if (img == null) {
            logger.warn("ImageIO returned null for fiber analysis overlay PNG {}", pngPath);
            return;
        }

        ImageRegion region = ImageRegion.createInstance(
                offsetX,
                offsetY,
                regionW,
                regionH,
                ImagePlane.getDefaultPlane().getZ(),
                ImagePlane.getDefaultPlane().getT());

        BufferedImageOverlay overlay = new BufferedImageOverlay(qupath.getOverlayOptions(), region, img);

        synchronized (this) {
            currentOverlay = overlay;
            currentViewer = viewer;
            currentToken = token;
        }

        final QuPathViewer v = viewer;
        Platform.runLater(() -> {
            v.setCustomPixelLayerOverlay(overlay);
            v.repaint();
            activeTokenProperty.set(token);
        });

        logger.info(
                "Showing fiber analysis overlay {} at region ({},{}) {}x{}",
                pngPath.getFileName(),
                offsetX,
                offsetY,
                regionW,
                regionH);
    }

    /**
     * Removes the overlay from the viewer, if one is installed by this
     * controller. Safe to call multiple times.
     */
    public void clear() {
        final BufferedImageOverlay overlay;
        final QuPathViewer viewer;
        synchronized (this) {
            overlay = currentOverlay;
            viewer = currentViewer;
            currentOverlay = null;
            currentViewer = null;
            currentToken = null;
        }
        if (overlay != null && viewer != null) {
            Platform.runLater(() -> {
                if (viewer.getCustomPixelLayerOverlay() == overlay) {
                    viewer.resetCustomPixelLayerOverlay();
                    viewer.repaint();
                }
                activeTokenProperty.set(null);
            });
        } else {
            Platform.runLater(() -> activeTokenProperty.set(null));
        }
    }
}
