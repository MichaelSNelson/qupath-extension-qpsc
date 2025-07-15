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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow for creating and saving microscope alignment transforms.
 * This workflow does NOT perform acquisition - it only creates the transform
 * that can be used later in the Existing Image workflow.
 *
 * The saved transform is a GENERAL macro-to-stage transform that works
 * regardless of where the green box appears in future macro images.
 *
 * @since 0.3.0
 * @author Mike Nelson
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
                    "No Image"
            ));
            return;
        }

        // Check for macro image
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (macroImage == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No macro image found in the current image. " +
                            "A macro image is required for alignment workflow.",
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

                    // Now show alignment dialog with proper parameters
                    return MacroImageController.showAlignmentDialog(gui, transformManager, microscopeName)
                            .thenApply(alignConfig -> {
                                if (alignConfig == null) {
                                    return null;
                                }

                                // Run detection NOW before project creation
                                MacroImageResults macroImageResults = performDetection(gui, alignConfig);

                                // Package everything together
                                return new CombinedConfig(sampleSetup, alignConfig, macroImageResults);
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
            MacroImageResults macroImageResults
    ) {}

    /**
     * Container for detection results that need to survive project creation.
     */
    private record MacroImageResults(
            GreenBoxDetector.DetectionResult greenBoxResult,
            MacroImageAnalyzer.MacroAnalysisResult tissueResult,
            AffineTransform greenBoxTransform,
            int macroWidth,
            int macroHeight
    ) {}

    /**
     * Performs all detection BEFORE project creation while macro image is still available.
     */
    private static MacroImageResults performDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config) {

        logger.info("Performing detection while macro image is available");
        // Capture macro dimensions while we still have access
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        int macroWidth = 0;
        int macroHeight = 0;

        if (macroImage != null) {
            macroWidth = macroImage.getWidth();
            macroHeight = macroImage.getHeight();
            logger.info("Captured macro dimensions: {}x{}", macroWidth, macroHeight);
        }

        GreenBoxDetector.DetectionResult greenBoxResult = null;
        AffineTransform greenBoxTransform = null;

        // Try green box detection if enabled
        if (config.useGreenBoxDetection()) {
            try {
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

        return new MacroImageResults(greenBoxResult, tissueResult, greenBoxTransform, macroWidth, macroHeight);
    }

    /**
     * Processes the alignment after detection is complete.
     */
    private static void processAlignmentWithProject(
            QuPathGUI gui,
            CombinedConfig combinedConfig,
            AffineTransformManager transformManager) {

        Platform.runLater(() -> {
            try {
                var sampleSetup = combinedConfig.sampleSetup();
                var alignConfig = combinedConfig.alignmentConfig();
                var detectionResults = combinedConfig.macroImageResults();

                // Import to project
                boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

                Map<String, Object> projectDetails;

                if (gui.getProject() == null) {
                    // Create new project
                    String imagePath = MinorFunctions.extractFilePath(
                            gui.getImageData().getServerPath()
                    );

                    if (imagePath == null) {
                        UIFunctions.notifyUserOfError(
                                "Cannot extract image path",
                                "Import Error"
                        );
                        return;
                    }

                    Project<BufferedImage> project = QPProjectFunctions.createProject(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName()
                    );

                    if (project == null) {
                        UIFunctions.notifyUserOfError(
                                "Failed to create project",
                                "Project Error"
                        );
                        return;
                    }

                    gui.setProject(project);

                    QPProjectFunctions.addImageToProject(
                            new File(imagePath),
                            project,
                            flipX,
                            flipY
                    );

                    gui.refreshProject();

                    // Find and open the entry
                    var entries = project.getImageList();
                    var newEntry = entries.stream()
                            .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                            .findFirst()
                            .orElse(null);

                    if (newEntry != null) {
                        gui.openImageEntry(newEntry);
                        logger.info("Reopened image with flips applied");
                    }

                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName(),
                            sampleSetup.modality()
                    );
                } else {
                    // Use existing project
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName(),
                            sampleSetup.modality()
                    );
                }

                // Create annotations from detection
                //createAnnotationsFromDetection(gui, detectionResults, flipX, flipY);
                runTissueDetectionScript(gui);

                // Get inverted axes settings
                boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                double macroPixelSize = DEFAULT_MACRO_PIXEL_SIZE;
                double mainPixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();
                // Create tiles for manual alignment
                String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
                String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

                // Create tiles for alignment
                createAlignmentTiles(gui, sampleSetup, tempTileDirectory,
                        modeWithIndex, invertedX, invertedY);

                // Setup transformation
                AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                mainPixelSize, invertedX, invertedY)
                        .thenAccept(alignmentTransform -> {
                            if (alignmentTransform == null) {
                                logger.info("Transform setup cancelled");
                                return;
                            }

                            logger.info("Alignment transform complete: {}", alignmentTransform);

                            // Convert from main image → stage to macro → stage
                            // The alignment transform maps: main image pixels → stage µm
                            // We need: macro pixels → stage µm
                            // First create the scaling difference
                            double scaleFactor = macroPixelSize / mainPixelSize;

                            // Create the correct macro-to-stage transform
                            AffineTransform macroToStageTransform = new AffineTransform();
                            macroToStageTransform.scale(scaleFactor, scaleFactor);
                            macroToStageTransform.concatenate(alignmentTransform);

                            // Now this is the general transform (no green box offset needed)
                            AffineTransform generalTransform = macroToStageTransform;

                            // Save the transform
                            saveGeneralTransform(gui, alignConfig, generalTransform,
                                    detectionResults, macroPixelSize, invertedX, invertedY,
                                    transformManager);
                        }).exceptionally(ex -> {
                            logger.error("Error in transform setup", ex);
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Transform setup failed: " + ex.getMessage(),
                                    "Transform Error"
                            ));
                            return null;
                        });

            } catch (Exception e) {
                logger.error("Error processing alignment", e);
                UIFunctions.notifyUserOfError(
                        "Failed to process alignment: " + e.getMessage(),
                        "Alignment Error"
                );
            }
        });
    }

    /**
     * Creates tiles for alignment based on available annotations.
     */
    private static void createAlignmentTiles(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) {

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
            boolean invertedY) {

        try {
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

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            UIFunctions.notifyUserOfError(
                    "Failed to create tiles: " + e.getMessage(),
                    "Tiling Error"
            );
        }
    }

    /**
     * Creates a general macro-to-stage transform from a specific alignment transform.
     *
     * The alignment transform maps full-res image coordinates to stage coordinates,
     * but it's specific to the green box position. This method creates a general
     * transform that works regardless of green box position.
     *
     * @param alignmentTransform The transform from the alignment process (specific to green box position)
     * @param greenBoxResult The green box detection result
     * @param macroPixelSize The pixel size of the macro image in microns
     * @return The general transform that maps macro coordinates to stage coordinates
     */
    private static AffineTransform createGeneralMacroToStageTransform(
            AffineTransform alignmentTransform,
            GreenBoxDetector.DetectionResult greenBoxResult,
            double macroPixelSize) {

        if (greenBoxResult == null || greenBoxResult.getDetectedBox() == null) {
            logger.warn("No green box detection available, returning alignment transform as-is");
            return alignmentTransform;
        }

        // Get the green box position in macro image pixels
        double greenBoxX_macro = greenBoxResult.getDetectedBox().getBoundsX();
        double greenBoxY_macro = greenBoxResult.getDetectedBox().getBoundsY();

        logger.info("Green box position in macro image: ({}, {}) pixels",
                greenBoxX_macro, greenBoxY_macro);

        // Convert green box position to stage units (microns)
        double greenBoxX_stage = greenBoxX_macro * macroPixelSize;
        double greenBoxY_stage = greenBoxY_macro * macroPixelSize;

        logger.info("Green box offset in stage coordinates: ({}, {}) µm",
                greenBoxX_stage, greenBoxY_stage);

        // The alignment transform maps: full-res image coords → stage coords
        // But it's specific to this green box position
        // To make it general, we need to add back the green box offset

        // Create the general transform
        AffineTransform generalTransform = new AffineTransform(alignmentTransform);

        // Add the green box offset to make it relative to macro image origin (0,0)
        // This gives us: macro image coords → stage coords
        generalTransform.translate(greenBoxX_stage, greenBoxY_stage);

        logger.info("Created general macro→stage transform: {}", generalTransform);

        return generalTransform;
    }

    /**
     * Saves the general transform with metadata.
     */
    private static void saveGeneralTransform(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            AffineTransform generalTransform,
            MacroImageResults macroImageResults,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            AffineTransformManager transformManager) {

        try {
            // Get configuration path
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();

            // Get the actual microscope name from config
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            String microscopeName = mgr.getString("microscope", "name");

            // Generate a default name if not provided
            String transformName = config.transformName();
            if (transformName == null || transformName.isBlank()) {
                transformName = microscopeName + "_Transform_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            }

            // Create transform preset with available data
            AffineTransformManager.TransformPreset preset =
                    new AffineTransformManager.TransformPreset(
                            transformName,
                            microscopeName,
                            "Standard", // Default mounting method
                            generalTransform,
                            "General macro-to-stage transform created via alignment workflow",
                            config.greenBoxParams()
                    );

            // Save using AffineTransformManager
            transformManager.savePreset(preset);

            logger.info("Saved general macro→stage transform: {}", transformName);

            // Save the transform name to preferences for future use
            QPPreferenceDialog.setSavedTransformName(transformName);

            // Notify user
            String finalTransformName = transformName;
            Platform.runLater(() -> {
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "Transform Saved",
                        "Successfully saved general alignment transform: " + finalTransformName +
                                "\n\nThis transform maps macro image coordinates to stage coordinates " +
                                "and can be used with any green box position in future workflows."
                );
            });

        } catch (Exception e) {
            logger.error("Failed to save transform", e);
            Platform.runLater(() -> {
                UIFunctions.notifyUserOfError(
                        "Failed to save transform: " + e.getMessage(),
                        "Save Error"
                );
            });
        }
    }

    /**
     * Creates annotations based on detection results.
     * Priority: Green box > Tissue bounds > Full image fallback
     */
    private static void createAnnotationsFromDetection(
            QuPathGUI gui,
            MacroImageResults macroImageResults,
            boolean flipX,
            boolean flipY) {

        logger.info("Creating annotations from detection results");

        var hierarchy = gui.getViewer().getHierarchy();
        var server = gui.getImageData().getServer();
        int imageWidth = server.getWidth();
        int imageHeight = server.getHeight();

        boolean createdAnnotation = false;

        // If green box detected, create scanned area from it
        if (macroImageResults.greenBoxResult() != null &&
                macroImageResults.greenBoxResult().getDetectedBox() != null) {

            logger.info("Creating scanned area from green box detection");

            // The green box is already in macro coordinates, we need to scale it to main image
            var greenBoxResult = macroImageResults.greenBoxResult();
            ROI greenBoxMacro = greenBoxResult.getDetectedBox();

            // Use the MacroAnalysisResult's scaling if available, otherwise calculate it
            double scaleX = (double) imageWidth / macroImageResults.macroWidth();
            double scaleY = (double) imageHeight / macroImageResults.macroHeight();

            // Scale the green box to main image coordinates
            double x = greenBoxMacro.getBoundsX() * scaleX;
            double y = greenBoxMacro.getBoundsY() * scaleY;
            double width = greenBoxMacro.getBoundsWidth() * scaleX;
            double height = greenBoxMacro.getBoundsHeight() * scaleY;

            // Apply flips to match the flipped image
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

            ROI flippedBox = ROIs.createRectangleROI(x, y, width, height,
                    ImagePlane.getDefaultPlane());

            PathObject greenBoxAnnotation = PathObjects.createAnnotationObject(flippedBox);
            greenBoxAnnotation.setName("Scanned Area (green box)");
            greenBoxAnnotation.setClassification("Scanned Area");

            hierarchy.addObject(greenBoxAnnotation);
            logger.info("Created green box annotation");
            createdAnnotation = true;

            // Now run tissue detection within the scanned area
            runTissueDetectionScript(gui);
        }
        // If no green box, but we have tissue bounds from macro, use that as a starting point
        else if (macroImageResults.tissueResult() != null &&
                macroImageResults.tissueResult().getTissueBounds() != null) {

            logger.info("No green box detected, creating approximate tissue area from macro analysis");

            var tissueResult = macroImageResults.tissueResult();
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
}