package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class ImageProcessing {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessing.class);
    /**
     * Detects the actual data bounds in an Ocus40 image by identifying and excluding white padding.
     * This is necessary because Ocus40 scanners add asymmetric white padding when creating pyramidal images.
     *
     * <p>The method runs a pixel classifier to detect white background regions, then creates an inverse
     * annotation representing the actual data area. This handles the common case where padding is added
     * to accommodate tile boundaries at different pyramid levels.</p>
     *
     * <p><b>Important:</b> This method operates on the image as it exists in QuPath, which means
     * any flips applied during import have already been performed. The returned bounds are in
     * the flipped coordinate system, which matches the green box detection coordinates.</p>
     *
     * @param gui The QuPath GUI instance with already-flipped image
     * @param scriptDirectory Directory containing the WhiteBackground.json classifier
     * @return Rectangle representing the actual data bounds (x, y, width, height) in flipped coordinates,
     *         or null if detection fails
     */
    public static Rectangle detectOcus40DataBounds(QuPathGUI gui, String scriptDirectory) {
        logger.info("Detecting Ocus40 data bounds using white background classifier");

        try {
            // Build the classifier path
            Path classifierPath = Paths.get(scriptDirectory, "WhiteBackground.json");
            if (!Files.exists(classifierPath)) {
                logger.error("WhiteBackground.json not found at: {}", classifierPath);
                return null;
            }

            // Build the detection script
            String script = String.format(
                    "resetSelection()\n" +
                            "createAnnotationsFromPixelClassifier(\"%s\", 1000000.0, 0, \"SELECT_NEW\")\n" +
                            "whitespace = getAnnotationObjects().findAll{it.getPathClass().toString().contains(\"Other\")}\n" +
                            "makeInverseAnnotation()\n" +
                            "removeObjects(whitespace, true)\n" +
                            "getSelectedObjects().each{it.setPathClass(getPathClass(\"Bounds\"))}\n",
                    classifierPath.toString().replace("\\", "\\\\")
            );

            logger.debug("Running white background detection script");

            // Store current annotations to restore later if needed
            var hierarchy = gui.getViewer().getHierarchy();
            var existingAnnotations = new ArrayList<>(hierarchy.getAnnotationObjects());

            // Run the script
            gui.runScript(null, script);

            // Find the Bounds annotation
            var boundsAnnotation = hierarchy.getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null &&
                            "Bounds".equals(ann.getPathClass().getName()))
                    .findFirst()
                    .orElse(null);

            if (boundsAnnotation == null) {
                logger.error("No Bounds annotation created by white background detection");
                return null;
            }

            // Get the bounds
            ROI boundsROI = boundsAnnotation.getROI();
            Rectangle dataBounds = new Rectangle(
                    (int) boundsROI.getBoundsX(),
                    (int) boundsROI.getBoundsY(),
                    (int) boundsROI.getBoundsWidth(),
                    (int) boundsROI.getBoundsHeight()
            );

            logger.info("Detected data bounds: x={}, y={}, width={}, height={}",
                    dataBounds.x, dataBounds.y, dataBounds.width, dataBounds.height);

            // Calculate padding amounts for logging
            int imageWidth = gui.getImageData().getServer().getWidth();
            int imageHeight = gui.getImageData().getServer().getHeight();

            int leftPadding = dataBounds.x;
            int topPadding = dataBounds.y;
            int rightPadding = imageWidth - (dataBounds.x + dataBounds.width);
            int bottomPadding = imageHeight - (dataBounds.y + dataBounds.height);

            logger.info("Detected padding - Left: {}, Top: {}, Right: {}, Bottom: {}",
                    leftPadding, topPadding, rightPadding, bottomPadding);

            // Remove the Bounds annotation to clean up
            hierarchy.removeObject(boundsAnnotation, true);

            // Fire update to refresh the viewer
            hierarchy.fireHierarchyChangedEvent(gui.getViewer());

            return dataBounds;

        } catch (Exception e) {
            logger.error("Error detecting Ocus40 data bounds", e);
            return null;
        }
    }
}
