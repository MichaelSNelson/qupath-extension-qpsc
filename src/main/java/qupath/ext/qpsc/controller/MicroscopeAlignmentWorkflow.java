package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.ui.MacroImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.utilities.MacroImageUtility;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow for creating and saving microscope alignment transforms.
 * This workflow does NOT perform acquisition - it only creates the transform
 * that can be used later in the Existing Image workflow.
 *
 * @since 0.3.0
 */
public class MicroscopeAlignmentWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeAlignmentWorkflow.class);

    // Default macro pixel size if not available from metadata
    private static final double DEFAULT_MACRO_PIXEL_SIZE = 80.0; // microns

    /**
     * Entry point for the microscope alignment workflow.
     * Creates or refines an affine transform between macro and main images.
     */
    public static void run() {
        logger.info("Starting Microscope Alignment Workflow...");

        QuPathGUI gui = QuPathGUI.getInstance();

        // Check prerequisites
        if (gui.getImageData() == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No image is currently open. Please open an image first.",
                    "No Image"));
            return;
        }

        // Check for macro image
        boolean hasMacro = false;
        try {
            var associatedImages = gui.getImageData().getServer().getAssociatedImageList();
            if (associatedImages != null) {
                hasMacro = associatedImages.stream()
                        .anyMatch(name -> name.toLowerCase().contains("macro"));
            }
        } catch (Exception e) {
            logger.error("Error checking for macro image", e);
        }

        if (!hasMacro) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "This image does not have an associated macro image.\n" +
                            "The alignment workflow requires a macro image to establish the transform.",
                    "No Macro Image"));
            return;
        }

        // Initialize transform manager
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        AffineTransformManager transformManager = new AffineTransformManager(
                new File(configPath).getParent());

        // Get current microscope from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
        String microscopeName = mgr.getString("microscope", "name");

        // First, show sample setup dialog to get modality
        SampleSetupController.showDialog()
                .thenCompose(sampleSetup -> {
                    if (sampleSetup == null) {
                        logger.info("User cancelled at sample setup");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Now show alignment dialog
                    return MacroImageController.showAlignmentDialog(gui, transformManager, microscopeName)
                            .thenApply(alignConfig -> {
                                if (alignConfig == null) {
                                    return null;
                                }
                                // Package both configs together
                                return new CombinedConfig(sampleSetup, alignConfig);
                            });
                })
                .thenAccept(combinedConfig -> {
                    if (combinedConfig == null) {
                        logger.info("User cancelled alignment workflow");
                        return;
                    }

                    // Process the alignment with project setup
                    processAlignmentWithProject(gui, combinedConfig, transformManager);
                })
                .exceptionally(ex -> {
                    logger.error("Alignment workflow failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(),
                            "Alignment Error"));
                    return null;
                });
    }

    /**
     * Container for both sample setup and alignment config.
     */
    private record CombinedConfig(
            SampleSetupController.SampleSetupResult sampleSetup,
            MacroImageController.AlignmentConfig alignmentConfig
    ) {}

    /**
     * Processes the alignment with proper project setup.
     */
    private static void processAlignmentWithProject(
            QuPathGUI gui,
            CombinedConfig config,
            AffineTransformManager transformManager) {

        Platform.runLater(() -> {
            try {
                // 1. Create/open project and import image with flips
                boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

                logger.info("Setting up project with flips: X={}, Y={}", flipX, flipY);

                Map<String, Object> projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                        gui,
                        config.sampleSetup().projectsFolder().getAbsolutePath(),
                        config.sampleSetup().sampleName(),
                        config.sampleSetup().modality(),
                        flipX,
                        flipY
                );

                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
                String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
                String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

                // Wait for image to fully load
                Thread.sleep(1000);

                // Verify image data is loaded
                if (gui.getImageData() == null) {
                    UIFunctions.notifyUserOfError(
                            "Image data not available after project setup.",
                            "Image Loading Error");
                    return;
                }

                // 2. Get image parameters
                double mainPixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();
                boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                // 3. Process green box detection if enabled
                AffineTransform initialTransform = null;
                if (config.alignmentConfig().useGreenBoxDetection()) {
                    initialTransform = attemptGreenBoxDetection(gui, config.alignmentConfig());
                }

                // 4. Create tissue annotations
                createTissueAnnotations(gui, config.alignmentConfig(), initialTransform);

                // 5. Create tiles for alignment
                createAlignmentTiles(gui, config.sampleSetup(), tempTileDirectory,
                        modeWithIndex, invertedX, invertedY);

                // 6. Start alignment process
                CompletableFuture<AffineTransform> transformFuture;

                if (config.alignmentConfig().useExistingTransform() &&
                        config.alignmentConfig().selectedTransform() != null) {
                    // Refine existing transform
                    logger.info("Refining existing transform: {}",
                            config.alignmentConfig().selectedTransform().getName());
                    AffineTransform existing = config.alignmentConfig().selectedTransform().getTransform();

                    MicroscopeController.getInstance().setCurrentTransform(existing);

                    transformFuture = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            mainPixelSize, invertedX, invertedY);

                } else if (initialTransform != null) {
                    // Use green box transform as starting point
                    logger.info("Using green box detection as initial transform");

                    MicroscopeController.getInstance().setCurrentTransform(initialTransform);

                    transformFuture = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            mainPixelSize, invertedX, invertedY);

                } else {
                    // Full manual alignment
                    logger.info("Starting manual alignment from scratch");

                    transformFuture = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            mainPixelSize, invertedX, invertedY);
                }

                // Handle the result
                transformFuture.thenAccept(transform -> {
                    if (transform == null) {
                        logger.info("User cancelled transform creation");
                        return;
                    }

                    // Save the transform if requested
                    if (config.alignmentConfig().saveTransform()) {
                        saveTransform(transform, config.alignmentConfig(), transformManager);
                    }

                    // Update preferences to use this transform
                    if (config.alignmentConfig().saveTransform() &&
                            config.alignmentConfig().transformName() != null &&
                            !config.alignmentConfig().transformName().isEmpty()) {
                        QPPreferenceDialog.setSavedTransformName(config.alignmentConfig().transformName());
                    }

                    // Notify user of success
                    Platform.runLater(() -> {
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                                javafx.scene.control.Alert.AlertType.INFORMATION);
                        alert.setTitle("Alignment Complete");
                        alert.setHeaderText("Microscope alignment completed successfully!");
                        alert.setContentText(
                                "The transform is now available for use in the 'Existing Image' workflow.\n\n" +
                                        (config.alignmentConfig().saveTransform() ?
                                                "Transform saved as: " + config.alignmentConfig().transformName() : ""));
                        alert.showAndWait();
                    });

                    logger.info("Microscope alignment workflow completed successfully");
                });

            } catch (Exception e) {
                logger.error("Alignment workflow failed", e);
                UIFunctions.notifyUserOfError(
                        "Workflow error: " + e.getMessage(),
                        "Alignment Error");
            }
        });
    }

    /**
     * Attempts green box detection on the macro image and returns an initial transform.
     * Uses the detection parameters from the alignment configuration to find the green
     * bounding box that indicates the scanned region.
     *
     * @param gui The QuPath GUI instance
     * @param config The alignment configuration containing green box detection parameters
     * @return AffineTransform mapping macro coordinates to main image coordinates based on
     *         the detected green box, or null if detection fails or confidence is too low
     */
    private static AffineTransform attemptGreenBoxDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config) {

        try {
            // Get macro image
            BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);

            if (macroImage == null) {
                logger.warn("Could not retrieve macro image for green box detection");
                return null;
            }

            // Run green box detection
            var greenBoxResult = GreenBoxDetector.detectGreenBox(macroImage, config.greenBoxParams());

            if (greenBoxResult != null && greenBoxResult.getConfidence() > 0.7) {
                logger.info("Green box detected with confidence {}", greenBoxResult.getConfidence());

                // Calculate transform
                int mainWidth = gui.getImageData().getServer().getWidth();
                int mainHeight = gui.getImageData().getServer().getHeight();
                double mainPixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                AffineTransform transform = GreenBoxDetector.calculateInitialTransform(
                        greenBoxResult.getDetectedBox(),
                        mainWidth,
                        mainHeight,
                        DEFAULT_MACRO_PIXEL_SIZE,
                        mainPixelSize
                );

                logger.info("Green box detection successful, created initial transform");
                return transform;
            }

        } catch (Exception e) {
            logger.error("Error during green box detection", e);
        }

        return null;
    }


    /**
     * Creates tissue annotations based on macro analysis or green box.
     */
    private static void createTissueAnnotations(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            AffineTransform greenBoxTransform) {

        // Clear existing tissue annotations
        var toRemove = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getPathClass() != null &&
                        "Tissue".equals(a.getPathClass().getName()))
                .toList();

        for (var annotation : toRemove) {
            gui.getViewer().getHierarchy().removeObject(annotation, true);
        }

        if (greenBoxTransform != null) {
            // Create annotation for the entire image area when green box is detected
            int width = gui.getImageData().getServer().getWidth();
            int height = gui.getImageData().getServer().getHeight();

            ROI roi = ROIs.createRectangleROI(0, 0, width, height, ImagePlane.getDefaultPlane());

            PathObject annotation = PathObjects.createAnnotationObject(roi);
            annotation.setName("Full Image Area (from green box)");
            annotation.setPathClass(gui.getAvailablePathClasses().stream()
                    .filter(pc -> "Tissue".equals(pc.getName()))
                    .findFirst()
                    .orElse(null));

            gui.getViewer().getHierarchy().addObject(annotation);
            logger.info("Created full image annotation from green box detection");

        } else {
            // Run tissue analysis on macro
            var analysis = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(),
                    config.thresholdMethod(),
                    config.thresholdParams()
            );

            if (analysis != null) {
                // Scale tissue bounds to main image
                // Note: This is approximate since we don't have the exact transform yet
                ROI tissueBounds = analysis.scaleToMainImage(analysis.getTissueBounds());

                PathObject annotation = PathObjects.createAnnotationObject(tissueBounds);
                annotation.setName("Tissue Region (from macro analysis)");
                annotation.setPathClass(gui.getAvailablePathClasses().stream()
                        .filter(pc -> "Tissue".equals(pc.getName()))
                        .findFirst()
                        .orElse(null));

                gui.getViewer().getHierarchy().addObject(annotation);
                logger.info("Created tissue annotation from macro analysis");
            } else {
                // Create default annotation
                createDefaultTissueAnnotation(gui);
            }
        }

        gui.getViewer().repaint();
        //save data
        try {
            QPProjectFunctions.saveCurrentImageData();
        } catch (IOException e) {
            logger.warn("Failed to save annotations: {}", e.getMessage());
        }
    }

    /**
     * Creates a default tissue annotation when analysis fails.
     */
    private static void createDefaultTissueAnnotation(QuPathGUI gui) {
        double imageWidth = gui.getImageData().getServer().getWidth();
        double imageHeight = gui.getImageData().getServer().getHeight();

        double margin = Math.min(imageWidth, imageHeight) * 0.1;
        ROI roi = ROIs.createRectangleROI(
                margin, margin,
                imageWidth - 2 * margin,
                imageHeight - 2 * margin,
                ImagePlane.getDefaultPlane()
        );

        PathObject annotation = PathObjects.createAnnotationObject(roi);
        annotation.setName("Tissue Region (adjust as needed)");
        annotation.setPathClass(gui.getAvailablePathClasses().stream()
                .filter(pc -> "Tissue".equals(pc.getName()))
                .findFirst()
                .orElse(null));

        gui.getViewer().getHierarchy().addObject(annotation);
        logger.info("Created default tissue annotation");
    }

    /**
     * Creates detection tiles for alignment based on selected modality.
     */
    private static void createAlignmentTiles(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) throws IOException {

        // Get annotations
        var annotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getPathClass() != null &&
                        "Tissue".equals(a.getPathClass().getName()))
                .toList();

        if (annotations.isEmpty()) {
            logger.warn("No tissue annotations found for tiling");
            return;
        }

        // Get camera parameters from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double pixelSize = mgr.getDouble("imagingMode", sampleSetup.modality(), "pixelSize_um");
        int cameraWidth = mgr.getInteger("imagingMode", sampleSetup.modality(), "detector", "width_px");
        int cameraHeight = mgr.getInteger("imagingMode", sampleSetup.modality(), "detector", "height_px");

        double mainPixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to QuPath pixels
        double frameWidthQP = frameWidthMicrons / mainPixelSize;
        double frameHeightQP = frameHeightMicrons / mainPixelSize;
        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        logger.info("Creating tiles: frame size {}x{} QP pixels ({}x{} µm) for modality {}",
                frameWidthQP, frameHeightQP, frameWidthMicrons, frameHeightMicrons, sampleSetup.modality());

        // Build tiling request
        TilingRequest request = new TilingRequest.Builder()
                .outputFolder(tempTileDirectory)
                .modalityName(modeWithIndex)
                .frameSize(frameWidthQP, frameHeightQP)
                .overlapPercent(overlapPercent)
                .annotations(annotations)
                .invertAxes(invertedX, invertedY)
                .createDetections(true)  // Important for alignment
                .addBuffer(true)
                .build();

        TilingUtilities.createTiles(request);
        logger.info("Created detection tiles for alignment");
    }

    /**
     * Saves the alignment transform along with associated metadata to the transform manager.
     * Includes the green box detection parameters used during alignment for future reuse.
     *
     * @param transform The affine transform to save
     * @param config The alignment configuration containing transform name and parameters
     * @param transformManager The transform manager instance for persistence
     */
    private static void saveTransform(
            AffineTransform transform,
            MacroImageController.AlignmentConfig config,
            AffineTransformManager transformManager) {

        String microscopeName = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        ).getString("microscope", "name");
        // Get stage limits from config for validation
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        var xlimits = mgr.getSection("stage", "xlimit");
        var ylimits = mgr.getSection("stage", "ylimit");

        if (xlimits != null && ylimits != null) {
            double xMin = ((Number) xlimits.get("low")).doubleValue();
            double xMax = ((Number) xlimits.get("high")).doubleValue();
            double yMin = ((Number) ylimits.get("low")).doubleValue();
            double yMax = ((Number) ylimits.get("high")).doubleValue();
            // Get image dimensions from QuPath
            var gui = QuPathGUI.getInstance();
            if (gui.getImageData() != null) {
                // Use the green box params from config, or defaults if not present
                GreenBoxDetector.DetectionParams greenBoxParams =
                        config.useGreenBoxDetection() ? config.greenBoxParams() : new GreenBoxDetector.DetectionParams();

                AffineTransformManager.TransformPreset preset =
                        new AffineTransformManager.TransformPreset(
                                config.transformName(),
                                microscopeName,
                                "Standard",
                                transform,
                                "Created via alignment workflow",
                                greenBoxParams
                        );
                if (!AffineTransformManager.validateTransform(
                        transform,
                        gui.getImageData().getServer().getWidth(),
                        gui.getImageData().getServer().getHeight(),
                        xMin, xMax, yMin, yMax)) {
                    logger.warn("Transform validation failed - saving anyway");
                    // Could show warning dialog here
                }
                transformManager.savePreset(preset);
                logger.info("Saved transform preset: {}", preset.getName());
            }
        }
    }
}