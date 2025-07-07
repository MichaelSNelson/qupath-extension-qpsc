package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.ui.MacroImageController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow for acquiring regions based on macro image analysis with
 * pre-saved affine transforms.
 *
 * @since 0.3.0
 */
public class MicroscopeAlignmentWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeAlignmentWorkflow.class);

    /**
     * Entry point for the macro-based acquisition workflow.
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

        // Initialize transform manager
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        AffineTransformManager transformManager = new AffineTransformManager(
                new java.io.File(configPath).getParent());

        // Get current microscope from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
        String microscopeName = mgr.getString("microscope", "name");

        // Show workflow dialog
        MacroImageController.showWorkflowDialog(gui, transformManager, microscopeName)
                .thenAccept(config -> {
                    if (config == null) {
                        logger.info("User cancelled macro workflow");
                        return;
                    }

                    // Process based on user's choice
                    processWorkflow(gui, config, transformManager);
                })
                .exceptionally(ex -> {
                    logger.error("Macro workflow failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(),
                            "Macro Workflow Error"));
                    return null;
                });
    }

    /**
     * Processes the workflow based on user configuration.
     */
    private static void processWorkflow(QuPathGUI gui,
                                        MacroImageController.WorkflowConfig config,
                                        AffineTransformManager transformManager) {

        // 1. FIRST analyze the macro image BEFORE any project operations
        logger.info("Analyzing macro image before project setup");

        MacroImageAnalyzer.MacroAnalysisResult analysis = MacroImageAnalyzer.analyzeMacroImage(
                gui.getImageData(),
                config.thresholdMethod(),
                config.thresholdParams()
        );

        if (analysis == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to analyze macro image. Ensure the image has an associated macro.",
                    "Analysis Error"));
            return;
        }

        logger.info("Macro image analysis successful, found {} tissue regions",
                analysis.getTissueRegions().size());

        // 2. Now set up the project (which may lose the macro image if flips are needed)
        logger.info("Setting up project for macro workflow");

        // Get flip preferences
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        Platform.runLater(() -> {
            try {
                // Log current state
                logger.info("Current image data before project setup: {}",
                        gui.getImageData() != null ? "Available" : "NULL");

                // Create/open project and add current image with flips
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

                // Wait for image to load and verify it's available
                Thread.sleep(1000);

                // Log current state after project setup
                logger.info("Current image data after project setup: {}",
                        gui.getImageData() != null ? "Available" : "NULL");

                // Verify image data is loaded
                if (gui.getImageData() == null) {
                    logger.error("Image data not available after project setup");

                    // Try to recover by checking if we have a matching image to open
                    var matchingImage = projectDetails.get("matchingImage");
                    if (matchingImage instanceof qupath.lib.projects.ProjectImageEntry) {
                        logger.info("Attempting to open matching image from project");
                        gui.openImageEntry((qupath.lib.projects.ProjectImageEntry<BufferedImage>) matchingImage);
                        Thread.sleep(1000);

                        if (gui.getImageData() == null) {
                            UIFunctions.notifyUserOfError(
                                    "Failed to load image after project setup. Please try reopening the image manually.",
                                    "Image Loading Error");
                            return;
                        }
                    } else {
                        UIFunctions.notifyUserOfError(
                                "Image data not available after project setup. Please try again.",
                                "Image Loading Error");
                        return;
                    }
                }

                // 3. Create annotations from macro analysis (already done before project setup)
                createAnnotationsFromMacro(gui, analysis, config);

                // 4. Get annotations that were just created
                List<PathObject> annotations = gui.getViewer().getHierarchy()
                        .getAnnotationObjects().stream()
                        .filter(a -> a.getPathClass() != null &&
                                "Tissue".equals(a.getPathClass().getName()))
                        .toList();

                if (annotations.isEmpty()) {
                    UIFunctions.notifyUserOfError(
                            "No tissue annotations found after macro analysis.",
                            "No Annotations");
                    return;
                }

                // 5. Now create tiles for the annotations
                logger.info("Creating tiles for {} annotations", annotations.size());

                // Get camera parameters from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                        QPPreferenceDialog.getMicroscopeConfigFileProperty());
                double pixelSize = mgr.getDouble("imagingMode", config.sampleSetup().modality(), "pixelSize_um");
                int cameraWidth = mgr.getInteger("imagingMode", config.sampleSetup().modality(), "detector", "width_px");
                int cameraHeight = mgr.getInteger("imagingMode", config.sampleSetup().modality(), "detector", "height_px");

                // Get macro pixel size from the original analysis (not from current image which might not have macro)
                double macroPixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                double frameWidthMicrons = pixelSize * cameraWidth;
                double frameHeightMicrons = pixelSize * cameraHeight;

                // Convert to QuPath pixels for tiling
                double frameWidthQP = frameWidthMicrons / macroPixelSize;
                double frameHeightQP = frameHeightMicrons / macroPixelSize;
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

                boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                // Build tiling request
                TilingRequest request = new TilingRequest.Builder()
                        .outputFolder(tempTileDirectory)
                        .modalityName(modeWithIndex)
                        .frameSize(frameWidthQP, frameHeightQP)
                        .overlapPercent(overlapPercent)
                        .annotations(annotations)
                        .invertAxes(invertedX, invertedY)
                        .createDetections(true)  // Important: create detection objects
                        .addBuffer(true)
                        .build();

                TilingUtilities.createTiles(request);
                logger.info("Created detection tiles for alignment");

                // 6. Now proceed with alignment
                CompletableFuture<AffineTransform> transformFuture;

                if (config.useExistingTransform() && config.selectedTransform() != null) {
                    // Use existing transform
                    AffineTransform existing = config.selectedTransform().getTransform();
                    logger.info("Using existing transform: {}", config.selectedTransform().getName());

                    // Validate transform with current stage bounds
                    if (validateTransformForCurrentSetup(existing, gui, analysis)) {
                        transformFuture = CompletableFuture.completedFuture(existing);
                    } else {
                        // Transform invalid - prompt for manual alignment
                        logger.warn("Existing transform failed validation, requesting manual alignment");
                        transformFuture = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                macroPixelSize, invertedX, invertedY);
                    }
                } else {
                    // Perform manual alignment
                    logger.info("Performing manual alignment for new transform");
                    transformFuture = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            macroPixelSize, invertedX, invertedY);
                }

                // 7. After transform is ready, save it and update preferences
                transformFuture.thenAccept(transform -> {
                    if (transform == null) {
                        logger.info("User cancelled transform setup");
                        return;
                    }

                    // Save transform if requested
                    if (config.saveTransform()) {
                        Platform.runLater(() -> {
                            MacroImageController.showSaveTransformDialog(config.transformName())
                                    .thenAccept(result -> {
                                        if (result != null) {
                                            AffineTransformManager.TransformPreset preset =
                                                    new AffineTransformManager.TransformPreset(
                                                            result.name(),
                                                            result.microscope(),
                                                            result.mountingMethod(),
                                                            transform,
                                                            result.notes()
                                                    );
                                            transformManager.saveTransform(preset);
                                            logger.info("Saved transform preset: {}", preset.getName());

                                            // Update the preference to use this transform
                                            QPPreferenceDialog.setSavedTransformName(preset.getName());

                                            // Notify user of success
                                            UIFunctions.notifyUserOfError(
                                                    "Microscope alignment saved successfully!\n\n" +
                                                            "Transform: " + preset.getName() + "\n" +
                                                            "This transform is now available for use in acquisition workflows.",
                                                    "Alignment Complete");
                                        } else {
                                            // Still update the preference if using existing transform
                                            if (config.useExistingTransform() && config.selectedTransform() != null) {
                                                QPPreferenceDialog.setSavedTransformName(
                                                        config.selectedTransform().getName());
                                            }
                                        }
                                    });
                        });
                    } else if (config.useExistingTransform() && config.selectedTransform() != null) {
                        // Update preference to use the selected existing transform
                        QPPreferenceDialog.setSavedTransformName(config.selectedTransform().getName());

                        Platform.runLater(() -> {
                            UIFunctions.notifyUserOfError(
                                    "Microscope alignment verified!\n\n" +
                                            "Using transform: " + config.selectedTransform().getName(),
                                    "Alignment Complete");
                        });
                    }

                    // Set the transform in the microscope controller for immediate use
                    MicroscopeController.getInstance().setCurrentTransform(transform);

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
     * Validates that a transform produces reasonable stage coordinates.
     */
    private static boolean validateTransformForCurrentSetup(AffineTransform transform,
                                                            QuPathGUI gui,
                                                            MacroImageAnalyzer.MacroAnalysisResult analysis) {
        try {
            // Get stage bounds from config
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            var xlimits = mgr.getSection("stage", "xlimit");
            var ylimits = mgr.getSection("stage", "ylimit");

            if (xlimits == null || ylimits == null) {
                logger.error("Stage limits not found in config");
                return false;
            }

            double xMin = ((Number) xlimits.get("low")).doubleValue();
            double xMax = ((Number) xlimits.get("high")).doubleValue();
            double yMin = ((Number) ylimits.get("low")).doubleValue();
            double yMax = ((Number) ylimits.get("high")).doubleValue();

            // Get image dimensions
            var server = gui.getImageData().getServer();

            return AffineTransformManager.validateTransform(
                    transform, server.getWidth(), server.getHeight(),
                    xMin, xMax, yMin, yMax);

        } catch (Exception e) {
            logger.error("Error validating transform", e);
            return false;
        }
    }

    /**
     * Requests manual alignment from the user.
     */
    private static CompletableFuture<AffineTransform> requestManualAlignment(
            QuPathGUI gui,
            MacroImageAnalyzer.MacroAnalysisResult analysis) {

        // Create temporary annotation to show tissue bounds
        ROI mainImageBounds = analysis.scaleToMainImage(analysis.getTissueBounds());
        PathObject tempAnnotation = PathObjects.createAnnotationObject(mainImageBounds);
        tempAnnotation.setName("Macro Tissue Bounds (Temporary)");


        Platform.runLater(() -> {
            gui.getViewer().getHierarchy().addObject(tempAnnotation);
            gui.getViewer().repaint();
        });

        // Get pixel size
        double pixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();
        boolean invertX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertY = QPPreferenceDialog.getInvertedYProperty();

        // Use existing alignment workflow
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        pixelSize, invertX, invertY)
                .whenComplete((transform, ex) -> {
                    // Remove temporary annotation
                    Platform.runLater(() -> {
                        gui.getViewer().getHierarchy().removeObject(tempAnnotation, true);
                        gui.getViewer().repaint();
                    });
                });
    }

    /**
     * Saves a new transform preset.
     */
    private static void saveNewTransform(AffineTransform transform,
                                         MacroImageController.WorkflowConfig config,
                                         AffineTransformManager manager) {
        Platform.runLater(() -> {
            MacroImageController.showSaveTransformDialog(config.transformName())
                    .thenAccept(result -> {
                        if (result != null) {
                            AffineTransformManager.TransformPreset preset =
                                    new AffineTransformManager.TransformPreset(
                                            result.name(),
                                            result.microscope(),
                                            result.mountingMethod(),
                                            transform,
                                            result.notes()
                                    );
                            manager.saveTransform(preset);
                            logger.info("Saved transform preset: {}", preset.getName());
                        }
                    });
        });
    }

    /**
     * Creates annotations in QuPath from macro analysis results.
     */
    private static void createAnnotationsFromMacro(QuPathGUI gui,
                                                   MacroImageAnalyzer.MacroAnalysisResult analysis,
                                                   MacroImageController.WorkflowConfig config) {
        Platform.runLater(() -> {
            var hierarchy = gui.getViewer().getHierarchy();

            if (config.createSingleBounds()) {
                // Create single bounding box annotation
                ROI bounds = analysis.scaleToMainImage(analysis.getTissueBounds());
                PathObject annotation = PathObjects.createAnnotationObject(bounds);
                annotation.setName("Macro Tissue Bounds");
                annotation.setClassification("Tissue");

                hierarchy.addObject(annotation);

                logger.info("Created single bounds annotation");

            } else {
                // Create individual region annotations
                List<ROI> regions = analysis.getTissueRegions();
                int count = 0;

                for (ROI macroROI : regions) {
                    ROI mainROI = analysis.scaleToMainImage(macroROI);

                    // Skip very small regions
                    if (mainROI.getBoundsWidth() < 100 || mainROI.getBoundsHeight() < 100) {
                        continue;
                    }

                    PathObject annotation = PathObjects.createAnnotationObject(mainROI);
                    annotation.setName("Macro Region " + (++count));
                    annotation.setClassification("Tissue");
                    hierarchy.addObject(annotation);
                }

                logger.info("Created {} region annotations", count);
            }

            gui.getViewer().repaint();
        });
    }

    /**
     * Continues with the acquisition workflow using the validated transform.
     */
    private static void continueWithAcquisition(QuPathGUI gui,
                                                AffineTransform transform,
                                                MacroImageController.WorkflowConfig config) {
        // Set the transform in the microscope controller
        MicroscopeController.getInstance().setCurrentTransform(transform);

        // Get sample info from config
        var sample = config.sampleSetup();

        // Get annotations marked as "Tissue"
        List<PathObject> annotations = gui.getViewer().getHierarchy()
                .getAnnotationObjects().stream()
                .filter(a -> a.getPathClass() != null &&
                        "Tissue".equals(a.getPathClass().getName()))
                .toList();

        if (annotations.isEmpty()) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No tissue annotations found. Please verify annotations were created correctly.",
                    "No Annotations"));
            return;
        }

        logger.info("Continuing acquisition with {} annotations", annotations.size());

        // The rest follows the existing workflow...
        // This would connect to the existing ExistingImageWorkflow logic
        // but skip the manual alignment step since we already have the transform

        Platform.runLater(() -> {
            qupath.fx.dialogs.Dialogs.showInfoNotification(
                    "Ready for Acquisition",
                    String.format("Ready to acquire %d regions using %s transform",
                            annotations.size(),
                            config.useExistingTransform() ? "saved" : "new"));
        });
    }
}