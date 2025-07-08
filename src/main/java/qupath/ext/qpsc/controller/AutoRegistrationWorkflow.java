package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow for automatic registration of new slides using green box detection
 * and pre-saved microscope transforms.
 *
 * This eliminates the need for manual registration on each slide by:
 * 1. Detecting the green box in the macro image
 * 2. Applying the saved microscope transform
 * 3. Creating properly positioned tissue annotations
 *
 * @since 0.3.1
 */
public class AutoRegistrationWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(AutoRegistrationWorkflow.class);

    private static final double DEFAULT_MACRO_PIXEL_SIZE = 80.0; // microns

    /**
     * Configuration for auto-registration.
     */
    public record AutoRegistrationConfig(
            AffineTransformManager.TransformPreset microscopeTransform,
            GreenBoxDetector.DetectionParams greenBoxParams,
            MacroImageAnalyzer.ThresholdMethod tissueMethod,
            Map<String, Object> tissueParams,
            boolean createSingleBounds,
            double confidenceThreshold
    ) {}

    /**
     * Result of auto-registration.
     */
    public record RegistrationResult(
            AffineTransform compositeTransform,
            List<PathObject> tissueAnnotations,
            double confidence,
            String message
    ) {}

    /**
     * Performs automatic registration for the current image.
     * This should be called from the Existing Image workflow when auto-registration is enabled.
     *
     * @param gui QuPath GUI instance
     * @param config Auto-registration configuration
     * @return Registration result, or null if failed
     */
    public static RegistrationResult performAutoRegistration(QuPathGUI gui, AutoRegistrationConfig config) {
        logger.info("Starting auto-registration for current image");

        try {
            // 1. Get the macro image
            BufferedImage macroImage = retrieveMacroImage(gui);
            if (macroImage == null) {
                return new RegistrationResult(null, null, 0.0,
                        "No macro image found - manual registration required");
            }

            // 2. Detect green box
            var greenBoxResult = GreenBoxDetector.detectGreenBox(macroImage, config.greenBoxParams());
            if (greenBoxResult == null || greenBoxResult.getConfidence() < config.confidenceThreshold()) {
                return new RegistrationResult(null, null, 0.0,
                        "Green box detection failed or low confidence - manual registration required");
            }

            logger.info("Green box detected with confidence {}", greenBoxResult.getConfidence());

            // 3. Calculate the green box to main image transform
            int mainWidth = gui.getImageData().getServer().getWidth();
            int mainHeight = gui.getImageData().getServer().getHeight();
            double mainPixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();

            AffineTransform greenBoxToMain = GreenBoxDetector.calculateInitialTransform(
                    greenBoxResult.getDetectedBox(),
                    mainWidth,
                    mainHeight,
                    DEFAULT_MACRO_PIXEL_SIZE,
                    mainPixelSize
            );

            // 4. Get the saved microscope transform (macro to stage)
            AffineTransform macroToStage = config.microscopeTransform().getTransform();

            // 5. Compose transforms: greenBox->main combined with macro->stage
            // This gives us the transform from the current image position to stage coordinates
            AffineTransform compositeTransform = new AffineTransform(macroToStage);
            compositeTransform.concatenate(greenBoxToMain);

            logger.info("Composite transform created: {}", compositeTransform);

            // 6. Analyze tissue in macro image
            var tissueAnalysis = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(),
                    config.tissueMethod(),
                    config.tissueParams()
            );

            if (tissueAnalysis == null || tissueAnalysis.getTissueRegions().isEmpty()) {
                // Still successful but no tissue found
                return new RegistrationResult(compositeTransform, new ArrayList<>(),
                        greenBoxResult.getConfidence(), "Registration successful but no tissue detected");
            }

            // 7. Transform tissue regions from macro coordinates to main image coordinates
            List<PathObject> annotations = createTransformedAnnotations(
                    tissueAnalysis,
                    greenBoxToMain,
                    config.createSingleBounds(),
                    gui
            );

            // 8. Add annotations to the image
            var hierarchy = gui.getViewer().getHierarchy();
            for (PathObject annotation : annotations) {
                hierarchy.addObject(annotation);
            }
            gui.getViewer().repaint();

            logger.info("Auto-registration successful: {} tissue annotations created", annotations.size());

            return new RegistrationResult(
                    compositeTransform,
                    annotations,
                    greenBoxResult.getConfidence(),
                    String.format("Auto-registration successful (confidence %.2f)", greenBoxResult.getConfidence())
            );

        } catch (Exception e) {
            logger.error("Auto-registration failed", e);
            return new RegistrationResult(null, null, 0.0,
                    "Auto-registration error: " + e.getMessage());
        }
    }

    /**
     * Creates tissue annotations transformed to main image coordinates.
     */
    private static List<PathObject> createTransformedAnnotations(
            MacroImageAnalyzer.MacroAnalysisResult analysis,
            AffineTransform macroToMain,
            boolean createSingleBounds,
            QuPathGUI gui) {

        List<PathObject> annotations = new ArrayList<>();

        if (createSingleBounds) {
            // Transform the overall tissue bounds
            ROI transformedBounds = transformROI(analysis.getTissueBounds(), macroToMain);
            if (transformedBounds != null && isValidROI(transformedBounds, gui)) {
                PathObject annotation = PathObjects.createAnnotationObject(transformedBounds);
                annotation.setName("Tissue Region (auto-detected)");
                annotation.setPathClass(getOrCreateTissueClass(gui));
                annotations.add(annotation);
            }
        } else {
            // Transform individual tissue regions
            int count = 0;
            for (ROI macroROI : analysis.getTissueRegions()) {
                ROI transformedROI = transformROI(macroROI, macroToMain);
                if (transformedROI != null && isValidROI(transformedROI, gui)) {
                    PathObject annotation = PathObjects.createAnnotationObject(transformedROI);
                    annotation.setName("Tissue Region " + (++count) + " (auto-detected)");
                    annotation.setPathClass(getOrCreateTissueClass(gui));
                    annotations.add(annotation);
                }
            }
        }

        return annotations;
    }

    /**
     * Transforms a ROI using the given affine transform.
     */
    private static ROI transformROI(ROI roi, AffineTransform transform) {
        try {
            // Transform the bounding box corners
            double x = roi.getBoundsX();
            double y = roi.getBoundsY();
            double w = roi.getBoundsWidth();
            double h = roi.getBoundsHeight();

            Point2D.Double topLeft = new Point2D.Double(x, y);
            Point2D.Double bottomRight = new Point2D.Double(x + w, y + h);

            transform.transform(topLeft, topLeft);
            transform.transform(bottomRight, bottomRight);

            // Create transformed rectangle
            double newX = Math.min(topLeft.x, bottomRight.x);
            double newY = Math.min(topLeft.y, bottomRight.y);
            double newW = Math.abs(bottomRight.x - topLeft.x);
            double newH = Math.abs(bottomRight.y - topLeft.y);

            return ROIs.createRectangleROI(newX, newY, newW, newH, ImagePlane.getDefaultPlane());

        } catch (Exception e) {
            logger.error("Error transforming ROI", e);
            return null;
        }
    }

    /**
     * Validates that a ROI is within reasonable bounds.
     */
    private static boolean isValidROI(ROI roi, QuPathGUI gui) {
        if (roi == null) return false;

        int imageWidth = gui.getImageData().getServer().getWidth();
        int imageHeight = gui.getImageData().getServer().getHeight();

        // Check if ROI is at least partially within image bounds
        boolean intersectsImage = roi.getBoundsX() < imageWidth &&
                roi.getBoundsY() < imageHeight &&
                roi.getBoundsX() + roi.getBoundsWidth() > 0 &&
                roi.getBoundsY() + roi.getBoundsHeight() > 0;

        // Check minimum size (at least 100x100 pixels)
        boolean hasMinSize = roi.getBoundsWidth() > 100 && roi.getBoundsHeight() > 100;

        return intersectsImage && hasMinSize;
    }

    /**
     * Gets or creates the Tissue PathClass.
     */
    private static qupath.lib.objects.classes.PathClass getOrCreateTissueClass(QuPathGUI gui) {
        return gui.getAvailablePathClasses().stream()
                .filter(pc -> "Tissue".equals(pc.getName()))
                .findFirst()
                .orElse(PathObjects.createPathClass("Tissue"));
    }

    /**
     * Retrieves the macro image from the current image server.
     */
    private static BufferedImage retrieveMacroImage(QuPathGUI gui) {
        try {
            var imageData = gui.getImageData();
            if (imageData == null) return null;

            var associatedList = imageData.getServer().getAssociatedImageList();
            if (associatedList == null) return null;

            // Find macro image key
            String macroKey = null;
            for (String name : associatedList) {
                if (name.toLowerCase().contains("macro")) {
                    macroKey = name;
                    break;
                }
            }

            if (macroKey == null) return null;

            // Try to retrieve it
            Object image = imageData.getServer().getAssociatedImage(macroKey);
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

            // Try variations if needed
            if (macroKey.startsWith("Series ")) {
                String seriesOnly = macroKey.split("\\s*\\(")[0].trim();
                image = imageData.getServer().getAssociatedImage(seriesOnly);
                if (image instanceof BufferedImage) {
                    return (BufferedImage) image;
                }
            }

        } catch (Exception e) {
            logger.error("Error retrieving macro image", e);
        }

        return null;
    }

    /**
     * Shows a dialog for configuring auto-registration settings.
     */
    public static AutoRegistrationConfig showConfigDialog(
            AffineTransformManager transformManager,
            String currentMicroscope) {

        // This would show a simplified dialog for:
        // 1. Selecting which saved transform to use
        // 2. Green box detection parameters (or use defaults)
        // 3. Tissue detection parameters (or use defaults)
        // 4. Confidence threshold for accepting auto-registration

        // For now, return default config
        var transforms = transformManager.getTransformsForMicroscope(currentMicroscope);
        if (transforms.isEmpty()) {
            return null;
        }

        GreenBoxDetector.DetectionParams greenParams = new GreenBoxDetector.DetectionParams();

        Map<String, Object> tissueParams = Map.of(
                "brightnessMin", 0.2,
                "brightnessMax", 0.95,
                "minRegionSize", 1000
        );

        return new AutoRegistrationConfig(
                transforms.get(0), // Use first available transform
                greenParams,
                MacroImageAnalyzer.ThresholdMethod.COLOR_DECONVOLUTION,
                tissueParams,
                true, // Single bounds
                0.7  // Confidence threshold
        );
    }
}