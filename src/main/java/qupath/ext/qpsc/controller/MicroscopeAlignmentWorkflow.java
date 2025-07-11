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
import java.util.Arrays;
import java.util.List;
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

    // Valid annotation classes for acquisition
    private static final List<String> VALID_ANNOTATION_CLASSES =
            Arrays.asList("Tissue", "Scanned Area", "Bounding Box");

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
                            "The alignment workflow requires a macro image to establish the transform.\n\n" +
                            "Please use a slide image with an embedded macro/label image.",
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

                    // Now show alignment dialog - THIS IS WHERE DETECTION HAPPENS
                    return MacroImageController.showAlignmentDialog(gui, transformManager, microscopeName)
                            .thenApply(alignConfig -> {
                                if (alignConfig == null) {
                                    return null;
                                }

                                // Run detection NOW before project creation
                                DetectionResults detectionResults = performDetection(gui, alignConfig);

                                // Package everything together
                                return new CombinedConfig(sampleSetup, alignConfig, detectionResults);
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
     * Container for sample setup, alignment config, and detection results.
     */
    private record CombinedConfig(
            SampleSetupController.SampleSetupResult sampleSetup,
            MacroImageController.AlignmentConfig alignmentConfig,
            DetectionResults detectionResults
    ) {}

    /**
     * Container for detection results that need to survive project creation.
     */
    private record DetectionResults(
            GreenBoxDetector.DetectionResult greenBoxResult,
            MacroImageAnalyzer.MacroAnalysisResult tissueResult,
            AffineTransform greenBoxTransform
    ) {}

    /**
     * Simple record to hold both transform and detection result
     */
    private record GreenBoxDetectionResult(
            AffineTransform transform,
            GreenBoxDetector.DetectionResult detectionResult
    ) {}

    /**
     * Performs all detection BEFORE project creation while macro image is still available.
     */
    private static DetectionResults performDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config) {

        logger.info("Performing detection while macro image is available");

        GreenBoxDetector.DetectionResult greenBoxResult = null;
        AffineTransform greenBoxTransform = null;

        // Try green box detection if enabled
        if (config.useGreenBoxDetection()) {
            try {
                BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                if (macroImage != null) {
                    greenBoxResult = GreenBoxDetector.detectGreenBox(macroImage, config.greenBoxParams());

                    if (greenBoxResult != null && greenBoxResult.getConfidence() > 0.7) {
                        logger.info("Green box detected with confidence {}", greenBoxResult.getConfidence());

                        // Calculate transform
                        int mainWidth = gui.getImageData().getServer().getWidth();
                        int mainHeight = gui.getImageData().getServer().getHeight();
                        double mainPixelSize = gui.getImageData().getServer()
                                .getPixelCalibration().getAveragedPixelSizeMicrons();

                        greenBoxTransform = GreenBoxDetector.calculateInitialTransform(
                                greenBoxResult.getDetectedBox(),
                                mainWidth,
                                mainHeight,
                                DEFAULT_MACRO_PIXEL_SIZE,
                                mainPixelSize
                        );
                    }
                }
            } catch (Exception e) {
                logger.error("Error during green box detection", e);
            }
        }

        // Try tissue detection
        MacroImageAnalyzer.MacroAnalysisResult tissueResult = null;
        try {
            tissueResult = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(),
                    config.thresholdMethod(),
                    config.thresholdParams()
            );

            if (tissueResult != null) {
                logger.info("Tissue analysis found {} regions", tissueResult.getTissueRegions().size());
            }
        } catch (Exception e) {
            logger.error("Error during tissue analysis", e);
        }

        return new DetectionResults(greenBoxResult, tissueResult, greenBoxTransform);
    }

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

                // 3. Create annotations using pre-detected results
                createTissueAnnotations(gui, config.detectionResults(), flipX, flipY);

                // Wait for tissue detection script to complete
                Thread.sleep(2000);  // Give time for script execution

                // 4. Create tiles for alignment - specifically on tissue annotations
                createAlignmentTiles(gui, config.sampleSetup(), tempTileDirectory,
                        modeWithIndex, invertedX, invertedY);

                // 5. Start alignment process
                CompletableFuture<AffineTransform> transformFuture;
                AffineTransform initialTransform = config.detectionResults().greenBoxTransform();

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
                        alert.setContentText("The transform has been saved and can be used \n in the Existing Image workflow.");
                        alert.showAndWait();
                    });
                });

            } catch (Exception e) {
                logger.error("Error in alignment workflow", e);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Error during alignment: " + e.getMessage(),
                        "Alignment Error"));
            }
        });
    }

    /**
     * Creates tissue annotations based on pre-detected results and tissue detection script.
     */
    private static void createTissueAnnotations(
            QuPathGUI gui,
            DetectionResults detectionResults,
            boolean flipX,
            boolean flipY) {

        // Clear existing tissue-related annotations
        var toRemove = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        VALID_ANNOTATION_CLASSES.contains(a.getClassification()))
                .toList();

        for (var annotation : toRemove) {
            gui.getViewer().getHierarchy().removeObject(annotation, true);
        }

        int imageWidth = gui.getImageData().getServer().getWidth();
        int imageHeight = gui.getImageData().getServer().getHeight();
        boolean createdAnnotation = false;

        // Create green box annotation if available (defines the scanned area)
        if (detectionResults.greenBoxResult() != null) {
            logger.info("Creating annotation from green box detection");

            // Green box creates full image annotation representing the scanned area
            ROI roi = ROIs.createRectangleROI(0, 0, imageWidth, imageHeight, ImagePlane.getDefaultPlane());
            PathObject annotation = PathObjects.createAnnotationObject(roi);
            annotation.setName("Scanned Area (from green box)");
            annotation.setClassification("Scanned Area");

            gui.getViewer().getHierarchy().addObject(annotation);
            logger.info("Created scanned area annotation from green box");
            createdAnnotation = true;

            // Now run tissue detection within the scanned area
            runTissueDetectionScript(gui);
        }
        // If no green box, but we have tissue bounds from macro, use that as a starting point
        else if (detectionResults.tissueResult() != null && detectionResults.tissueResult().getTissueBounds() != null) {
            logger.info("No green box detected, creating approximate tissue area from macro analysis");

            var tissueResult = detectionResults.tissueResult();
            ROI tissueBounds = tissueResult.scaleToMainImage(tissueResult.getTissueBounds());

            if (tissueBounds != null) {
                // Apply flips to match the flipped image
                double x = tissueBounds.getBoundsX();
                double y = tissueBounds.getBoundsY();
                double width = tissueBounds.getBoundsWidth();
                double height = tissueBounds.getBoundsHeight();

                if (flipX) {
                    x = imageWidth - (x + width);
                }
                if (flipY) {
                    y = imageHeight - (y + height);
                }

                // Ensure bounds are within image
                x = Math.max(0, x);
                y = Math.max(0, y);
                width = Math.min(imageWidth - x, width);
                height = Math.min(imageHeight - y, height);

                ROI flippedBounds = ROIs.createRectangleROI(x, y, width, height,
                        ImagePlane.getDefaultPlane());

                PathObject searchArea = PathObjects.createAnnotationObject(flippedBounds);
                searchArea.setName("Search Area (from macro)");
                searchArea.setClassification("Scanned Area");

                gui.getViewer().getHierarchy().addObject(searchArea);
                logger.info("Created search area annotation for tissue detection");
                createdAnnotation = true;

                // Run tissue detection within this area
                runTissueDetectionScript(gui);
            }
        }

        // Create full image fallback only if nothing else worked
        if (!createdAnnotation) {
            logger.warn("No detection results available, creating full image annotation");

            ROI roi = ROIs.createRectangleROI(0, 0, imageWidth, imageHeight,
                    ImagePlane.getDefaultPlane());

            PathObject annotation = PathObjects.createAnnotationObject(roi);
            annotation.setName("Full Image (no detection)");
            annotation.setClassification("Bounding Box");

            gui.getViewer().getHierarchy().addObject(annotation);
            logger.info("Created full image annotation as fallback");

            // Still try tissue detection on the full image
            runTissueDetectionScript(gui);
        }

        gui.getViewer().repaint();

        // Save data
        try {
            QPProjectFunctions.saveCurrentImageData();
        } catch (IOException e) {
            logger.warn("Failed to save annotations: {}", e.getMessage());
        }
    }

    /**
     * Runs the tissue detection script if configured.
     * The script should detect tissue within any existing "Scanned Area" annotations.
     */
    private static void runTissueDetectionScript(QuPathGUI gui) {
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured");
            return;
        }

        logger.info("Running tissue detection script");

        try {
            // Get the pixel size for the script
            double pixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();

            // If we have a scanned area annotation, select it first so tissue detection
            // runs within it
            var scannedAreas = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null &&
                            ("Scanned Area".equals(a.getClassification()) ||
                                    "Bounding Box".equals(a.getClassification())))
                    .toList();

            if (!scannedAreas.isEmpty()) {
                // Select the scanned area annotations
                gui.getViewer().getHierarchy().getSelectionModel().selectObjects(scannedAreas);
                logger.info("Selected {} scanned area annotations for tissue detection", scannedAreas.size());
            }

            // Prepare the script with proper parameters
            Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
            String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                    tissueScript,
                    String.valueOf(pixelSize),
                    scriptPaths.get("jsonTissueClassfierPathString")
            );

            // Run the script
            gui.runScript(null, modifiedScript);
            logger.info("Tissue detection script completed");

            // Clear selection
            gui.getViewer().getHierarchy().getSelectionModel().clearSelection();

            // Log results
            var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null &&
                            "Tissue".equals(a.getClassification()))
                    .toList();
            logger.info("Found {} tissue annotations after script", tissueAnnotations.size());

        } catch (Exception e) {
            logger.error("Error running tissue detection script", e);
        }
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

        // First, try to get tissue annotations specifically
        var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        "Tissue".equals(a.getClassification()))
                .toList();

        // If we have tissue annotations, use those for tiling
        if (!tissueAnnotations.isEmpty()) {
            logger.info("Found {} tissue annotations for tiling", tissueAnnotations.size());
            createTilesForAnnotations(gui, tissueAnnotations, sampleSetup, tempTileDirectory,
                    modeWithIndex, invertedX, invertedY);
            return;
        }

        // Otherwise fall back to any valid annotation class
        logger.info("No tissue annotations found, checking for other valid annotation types");
        var annotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        VALID_ANNOTATION_CLASSES.contains(a.getClassification()))
                .toList();

        if (annotations.isEmpty()) {
            logger.warn("No annotations found for tiling. Looking for classes: {}",
                    VALID_ANNOTATION_CLASSES);
            return;
        }

        logger.info("Found {} annotations for tiling (non-tissue)", annotations.size());
        createTilesForAnnotations(gui, annotations, sampleSetup, tempTileDirectory,
                modeWithIndex, invertedX, invertedY);
    }

    /**
     * Helper method to create tiles for given annotations.
     */
    private static void createTilesForAnnotations(
            QuPathGUI gui,
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) throws IOException {

        // Remove existing tiles from previous runs of this modality
        String modalityBase = sampleSetup.modality().replaceAll("(_\\d+)$", "");
        gui.getViewer().getHierarchy().getDetectionObjects().stream()
                .filter(o -> o.getClassification() != null &&
                        o.getClassification().contains(modalityBase))
                .toList()  // Collect to list first to avoid concurrent modification
                .forEach(o -> gui.getViewer().getHierarchy().removeObject(o, false));
        gui.getViewer().getHierarchy().fireHierarchyChangedEvent(gui.getViewer());
        logger.info("Removed existing tiles for modality base: {}", modalityBase);

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
                }

                transformManager.savePreset(preset);
                logger.info("Saved transform preset: {}", preset.getName());
            }
        }
    }
}