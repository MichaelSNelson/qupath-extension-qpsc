package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import java.util.HashMap;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow with integrated alignment selection functionality.
 * <p>
 * This workflow now supports two paths:
 * Path A: Use existing alignment with green box detection
 * Path B: Create new alignment through manual annotation workflow
 *
 * @author Mike Nelson
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Single-threaded executor for stitching to ensure only one stitching job runs at a time
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entrypoint: launches the full workflow with all necessary user dialogs and background steps.
     */
    public static void run() {
        logger.info("Starting Existing Image Workflow...");

        QuPathGUI qupathGUI = QuPathGUI.getInstance();

        // First show the existing image dialog to get user preferences
        ExistingImageController.showDialog().thenAccept(userInput -> {
            if (userInput == null) {
                logger.info("Existing image dialog cancelled.");
                return;
            }

            // Store user's macro pixel size
            double macroPixelSize = userInput.macroPixelSize();

            // Continue with sample setup
            SampleSetupController.showDialog().thenAccept(sample -> {
                if (sample == null) {
                    logger.info("Sample setup cancelled.");
                    return;
                }

                logger.info("Sample info received: {}", sample);

                // Import current image into a QuPath project
                boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();
                logger.info("Importing image to project: flippedX={}, flippedY={}", flippedX, flippedY);

                // Check if we have an image open first
                var currentImageData = qupathGUI.getImageData();
                if (currentImageData == null) {
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "No image is currently open. Please open an image first.",
                            "No Image"));
                    return;
                }

                // Handle project creation/opening synchronously on FX thread
                Platform.runLater(() -> {
                    try {
                        Map<String, Object> projectDetails;

                        if (qupathGUI.getProject() == null) {
                            logger.info("Creating new project and importing image");

                            // Extract the current image path before creating project
                            var serverPathFull = currentImageData.getServerPath();
                            logger.info(serverPathFull);
                            String imagePath = MinorFunctions.extractFilePath(serverPathFull);

                            if (imagePath == null) {
                                UIFunctions.notifyUserOfError(
                                        "Cannot extract image path from current image.",
                                        "Import Error");
                                return;
                            }

                            // Create the project first
                            Project<BufferedImage> project = QPProjectFunctions.createProject(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName()
                            );

                            if (project == null) {
                                UIFunctions.notifyUserOfError(
                                        "Failed to create project.",
                                        "Project Error");
                                return;
                            }

                            // Add the image with flips
                            QPProjectFunctions.addImageToProject(
                                    new File(imagePath),
                                    project,
                                    flippedX,
                                    flippedY
                            );

                            // Set the project in QuPath
                            qupathGUI.setProject(project);

                            // Find and open the newly added image
                            var entries = project.getImageList();
                            var imageEntry = entries.stream()
                                    .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                    .findFirst()
                                    .orElse(null);

                            if (imageEntry != null) {
                                qupathGUI.openImageEntry(imageEntry);
                                logger.info("Opened flipped image in project");
                            } else {
                                UIFunctions.notifyUserOfError(
                                        "Failed to find image in project after import.",
                                        "Import Error");
                                return;
                            }

                            // Get project details
                            projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName(),
                                    sample.modality()
                            );

                        } else {
                            logger.info("Project exists, checking if image needs to be added");

                            // Check if current image is in the project
                            var project = qupathGUI.getProject();
                            var projectEntry = project.getEntry(currentImageData);

                            if (projectEntry == null) {
                                // Image not in project - add it with flips
                                logger.info("Adding current image to existing project with flips");
                                String imagePath = MinorFunctions.extractFilePath(currentImageData.getServerPath());
                                if (imagePath != null) {
                                    QPProjectFunctions.addImageToProject(
                                            new File(imagePath),
                                            project,
                                            flippedX,
                                            flippedY
                                    );

                                    // Refresh and reopen the image to apply flips
                                    qupathGUI.refreshProject();

                                    // Find and open the newly added entry
                                    var entries = project.getImageList();
                                    var newEntry = entries.stream()
                                            .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                            .findFirst()
                                            .orElse(null);

                                    if (newEntry != null) {
                                        qupathGUI.openImageEntry(newEntry);
                                        logger.info("Reopened image with flips applied");
                                    }
                                }
                            } else {
                                logger.info("Image already in project");
                            }

                            // Get project information for existing project
                            projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName(),
                                    sample.modality()
                            );
                        }

                        // Wait a moment for the image to fully load and display
                        Thread.sleep(500);

                        // Verify we have a properly loaded image before continuing
                        if (qupathGUI.getImageData() == null) {
                            UIFunctions.notifyUserOfError(
                                    "No image data available after project setup. Workflow cannot continue.",
                                    "Image Error");
                            return;
                        }

                        logger.info("Project setup complete, continuing workflow");
                        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                        // Continue with the alignment selection workflow
                        continueWorkflowAfterProjectSetup(qupathGUI, projectDetails, sample,
                                invertedX, invertedY, macroPixelSize);

                    } catch (Exception e) {
                        logger.error("Failed during project setup", e);
                        UIFunctions.notifyUserOfError(
                                "Failed to create/open project: " + e.getMessage(),
                                "Project Error");
                    }
                });
            }).exceptionally(ex -> {
                logger.info("Sample setup failed: {}", ex.getMessage());
                return null;
            });
        }).exceptionally(ex -> {
            logger.info("Existing image dialog failed: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * Continues the workflow after project setup and image import is complete.
     * Now includes alignment selection dialog.
     *
     * @param qupathGUI The QuPath GUI instance
     * @param projectDetails Map containing project information from setup
     * @param sample Sample setup configuration including name, folder, and modality
     * @param invertedX Whether X axis is inverted for stage coordinates
     * @param invertedY Whether Y axis is inverted for stage coordinates
     * @param macroPixelSize The pixel size of the macro image in microns
     */
    private static void continueWorkflowAfterProjectSetup(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            boolean invertedX,
            boolean invertedY,
            double macroPixelSize) {

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

        // Validate pixel size
        if (macroPixelSize <= 0 || macroPixelSize > 100) {
            logger.warn("Invalid macro pixel size: {}. Aborting workflow.", macroPixelSize);
            UIFunctions.notifyUserOfError("Invalid pixel size: " + macroPixelSize + " microns. Cannot continue.", "Pixel Size Error");
            return;
        }

        logger.info("Using macro pixel size: {} microns", macroPixelSize);

        // Show alignment selection dialog
        AlignmentSelectionController.showDialog(qupathGUI, sample.modality())
                .thenAccept(alignmentChoice -> {
                    if (alignmentChoice == null) {
                        logger.info("Alignment selection cancelled");
                        return;
                    }

                    if (alignmentChoice.useExistingAlignment()) {
                        // Path A: Use existing alignment
                        proceedWithExistingAlignment(qupathGUI, projectDetails, sample,
                                macroPixelSize, invertedX, invertedY, alignmentChoice);
                    } else {
                        // Path B: Create new alignment (original workflow)
                        proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                                macroPixelSize, invertedX, invertedY);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error in alignment selection", ex);
                    // Fall back to manual workflow
                    proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                            macroPixelSize, invertedX, invertedY);
                    return null;
                });
    }

    /**
     * Proceeds with existing alignment (Path A from the workflow description).
     * Uses saved transform and green box detection.
     */
    private static void proceedWithExistingAlignment(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            AlignmentSelectionController.AlignmentChoice alignmentChoice) {

        logger.info("Using existing alignment: {}", alignmentChoice.selectedTransform().getName());

        // Get the saved transform
        AffineTransform savedTransform = alignmentChoice.selectedTransform().getTransform();

        // Set it in the microscope controller
        MicroscopeController.getInstance().setCurrentTransform(savedTransform);

        // Get macro image for green box detection
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(qupathGUI);
        if (macroImage == null) {
            logger.error("No macro image available for green box detection");
            UIFunctions.notifyUserOfError(
                    "No macro image found. Cannot use existing alignment without macro image.",
                    "Macro Image Required");
            return;
        }

        // Show green box preview with the saved parameters
        GreenBoxDetector.DetectionParams greenBoxParams = alignmentChoice.selectedTransform().getGreenBoxParams();
        if (greenBoxParams == null) {
            // Use default parameters if none saved
            greenBoxParams = new GreenBoxDetector.DetectionParams();
        }

        GreenBoxPreviewController.showPreviewDialog(macroImage, greenBoxParams)
                .thenAccept(greenBoxResult -> {
                    if (greenBoxResult == null) {
                        logger.info("Green box detection cancelled");
                        return;
                    }

                    if (greenBoxResult.getConfidence() < 0.7) {
                        logger.warn("Green box confidence too low: {}", greenBoxResult.getConfidence());
                        UIFunctions.notifyUserOfError(
                                "Green box detection confidence too low. Please use manual alignment instead.",
                                "Detection Failed");
                        return;
                    }

                    // Calculate the offset of the green box within the macro image
                    ROI greenBox = greenBoxResult.getDetectedBox();
                    double greenBoxX = greenBox.getBoundsX();
                    double greenBoxY = greenBox.getBoundsY();

                    logger.info("Green box detected at macro coordinates: ({}, {})", greenBoxX, greenBoxY);

                    // The green box offset in stage coordinates
                    double offsetX = greenBoxX * macroPixelSize;
                    double offsetY = greenBoxY * macroPixelSize;

                    // Create adjusted transform that includes the green box offset
                    AffineTransform adjustedTransform = new AffineTransform(savedTransform);
                    adjustedTransform.translate(-offsetX, -offsetY);

                    logger.info("Adjusted transform with green box offset: {}", adjustedTransform);

                    // Collect or create annotations
                    List<PathObject> annotations = collectOrCreateAnnotations(qupathGUI, macroPixelSize);

                    if (annotations.isEmpty()) {
                        logger.warn("No annotations found or created");
                        return;
                    }

                    // Check if refinement is requested
                    if (alignmentChoice.refinementRequested()) {
                        // Single tile refinement
                        proceedWithSingleTileRefinement(qupathGUI, projectDetails, sample,
                                macroPixelSize, invertedX, invertedY, annotations, adjustedTransform);
                    } else {
                        // Trust the calculated position
                        proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                                macroPixelSize, invertedX, invertedY, annotations, adjustedTransform);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error in green box detection", ex);
                    UIFunctions.notifyUserOfError(
                            "Error detecting green box: " + ex.getMessage(),
                            "Detection Error");
                    return null;
                });
    }

    /**
     * Collects existing annotations or runs tissue detection to create them.
     *
     * @param qupathGUI The QuPath GUI instance
     * @param macroPixelSize The pixel size of the macro image
     * @return List of annotations to use for acquisition
     */
    private static List<PathObject> collectOrCreateAnnotations(QuPathGUI qupathGUI, double macroPixelSize) {
        // First try to get existing annotations
        List<PathObject> annotations = QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing annotations", annotations.size());
            return annotations;
        }

        // If no annotations, run tissue detection script
        logger.info("No annotations found, running tissue detection");

        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
        if (tissueScript != null && !tissueScript.isBlank()) {
            try {
                // Get the pixel size for the script
                double pixelSize = qupathGUI.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                // Prepare the script with proper parameters
                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                        tissueScript,
                        String.valueOf(pixelSize),
                        scriptPaths.get("jsonTissueClassfierPathString")
                );

                // Run the script
                qupathGUI.runScript(null, modifiedScript);
                logger.info("Tissue detection script completed");

                // Refresh annotations after script
                annotations = QP.getAnnotationObjects().stream()
                        .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                        .filter(ann -> ann.getPathClass() != null &&
                                Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                        .collect(Collectors.toList());

                logger.info("Found {} annotations after tissue detection", annotations.size());

            } catch (Exception e) {
                logger.error("Error running tissue detection script", e);
            }
        } else {
            logger.info("No tissue detection script configured");
        }

        // If still no annotations, prompt user
        if (annotations.isEmpty()) {
            Platform.runLater(() -> {
                UIFunctions.notifyUserOfError(
                        "No valid annotations found. Please create annotations with one of these classes:\n" +
                                String.join(", ", VALID_ANNOTATION_CLASSES),
                        "No Annotations");
            });
        }

        return annotations;
    }

    /**
     * Performs single tile refinement for existing alignment.
     * This is simpler than the full multi-tile refinement used in manual alignment.
     */
    private static void proceedWithSingleTileRefinement(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> annotations,
            AffineTransform initialTransform) {

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

        // Create tiles for refinement
        createTilesForAnnotations(qupathGUI, annotations, sample, tempTileDirectory,
                modeWithIndex, macroPixelSize, invertedX, invertedY);

        // Set the initial transform
        MicroscopeController.getInstance().setCurrentTransform(initialTransform);

        // Prompt for single tile selection
        UIFunctions.promptTileSelectionDialogAsync(
                "Select a tile for alignment refinement.\n" +
                        "The microscope will move to approximately the correct position.\n" +
                        "You can then fine-tune the alignment if needed."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                logger.info("Tile selection cancelled, using initial transform");
                proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                        macroPixelSize, invertedX, invertedY, annotations, initialTransform);
                return;
            }

            try {
                // Get tile coordinates
                double[] tileCoords = {
                        selectedTile.getROI().getCentroidX(),
                        selectedTile.getROI().getCentroidY()
                };

                // Transform to stage coordinates using initial transform
                double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(
                        tileCoords, initialTransform);

                logger.info("Moving to tile '{}' at stage position: {}",
                        selectedTile.getName(), Arrays.toString(stageCoords));

                // Move stage
                MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                // Show refinement dialog
                boolean refined = UIFunctions.stageToQuPathAlignmentGUI2();

                if (refined) {
                    // Get the refined position
                    double[] refinedCoords = MicroscopeController.getInstance().getStagePositionXY();

                    // Calculate refined transform
                    AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                            initialTransform, tileCoords, refinedCoords);

                    logger.info("Refined transform: {}", refinedTransform);

                    // Continue with refined transform
                    proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                            macroPixelSize, invertedX, invertedY, annotations, refinedTransform);
                } else {
                    // User cancelled refinement, use initial transform
                    logger.info("Refinement cancelled, using initial transform");
                    proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                            macroPixelSize, invertedX, invertedY, annotations, initialTransform);
                }

            } catch (Exception e) {
                logger.error("Error during single tile refinement", e);
                UIFunctions.notifyUserOfError(
                        "Refinement failed: " + e.getMessage(),
                        "Refinement Error");
                // Fall back to initial transform
                proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                        macroPixelSize, invertedX, invertedY, annotations, initialTransform);
            }
        });
    }

    /**
     * Creates tiles for the given annotations.
     * Extracted to reduce code duplication.
     */
    private static void createTilesForAnnotations(
            QuPathGUI qupathGUI,
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        // Get camera parameters from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());
        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
        int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
        int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to QuPath pixels for tiling
        double frameWidthQP = frameWidthMicrons / macroPixelSize;
        double frameHeightQP = frameHeightMicrons / macroPixelSize;
        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        try {
            // Remove existing tiles
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass() != null &&
                            o.getPathClass().toString().contains(modalityBase))
                    .forEach(QP::removeObject);
            QP.fireHierarchyUpdate();

            // Build tiling request
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthQP, frameHeightQP)
                    .overlapPercent(overlapPercent)
                    .annotations(annotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created tiles for {} annotations", annotations.size());

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            throw new RuntimeException("Failed to create tile configurations: " + e.getMessage(), e);
        }
    }

    /**
     * Proceeds with the manual annotation workflow (Path B).
     * This is the original workflow when no existing alignment is used.
     */
    private static void proceedWithManualAnnotations(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        logger.info("Proceeding with manual annotation workflow");

        // Collect or create annotations
        List<PathObject> annotations = collectOrCreateAnnotations(qupathGUI, macroPixelSize);

        if (annotations.isEmpty()) {
            logger.warn("No annotations available for manual workflow");
            return;
        }

        // Create tiles and setup manual transform
        createTilesAndSetupTransform(qupathGUI, projectDetails, sample,
                macroPixelSize, invertedX, invertedY, annotations);
    }

    /**
     * Creates tiles and sets up transform manually (no auto-registration).
     * This follows the original workflow pattern.
     */
    private static void createTilesAndSetupTransform(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> currentAnnotations) {

        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

        // Create tiles
        createTilesForAnnotations(qupathGUI, currentAnnotations, sample, tempTileDirectory,
                modeWithIndex, macroPixelSize, invertedX, invertedY);

        // Setup affine transformation with user interaction
        AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        macroPixelSize, invertedX, invertedY)
                .thenAccept(transform -> {
                    if (transform == null) {
                        logger.info("Transform setup cancelled");
                        return;
                    }

                    logger.info("Transform setup complete: {}", transform);

                    // Set the transform
                    MicroscopeController.getInstance().setCurrentTransform(transform);

                    // Save transform for future use if configured
                    if (PersistentPreferences.getSaveTransformDefault()) {
                        try {
                            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                            AffineTransformManager manager = new AffineTransformManager(
                                    new File(configPath).getParent());

                            String transformName = "Transform_" + sample.sampleName() + "_" +
                                    new Date().getTime();

                            // Get the actual microscope name from config
                            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                            String microscopeName = mgr.getString("microscope", "name");

                            // Create a TransformPreset and save it
                            AffineTransformManager.TransformPreset preset =
                                    new AffineTransformManager.TransformPreset(
                                            transformName,
                                            microscopeName, // Use actual microscope name, not modality
                                            "Manual", // mounting method
                                            transform,
                                            "Created during manual alignment for " + sample.modality(),
                                            null); // No green box params for manual alignment

                            manager.savePreset(preset);

                            // Update preferences with the saved transform name
                            QPPreferenceDialog.setSavedTransformName(transformName);

                            logger.info("Saved transform: {}", transformName);
                        } catch (Exception e) {
                            logger.error("Failed to save transform", e);
                        }
                    }

                    // Continue with annotation check
                    proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                            macroPixelSize, invertedX, invertedY, currentAnnotations, transform);
                })
                .exceptionally(ex -> {
                    logger.error("Error in transform setup", ex);
                    UIFunctions.notifyUserOfError(
                            "Transform setup failed: " + ex.getMessage(),
                            "Setup Error");
                    return null;
                });
    }

    /**
     * Common workflow continuation after annotations are established and transform is known.
     * This performs the final annotation check and starts acquisition.
     */
    private static void proceedWithAnnotationCheck(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> annotations,
            AffineTransform transform) {

        // Show annotation check dialog
        CompletableFuture<Boolean> annotationCheckFuture = new CompletableFuture<>();
        UIFunctions.checkValidAnnotationsGUI(
                Arrays.asList(VALID_ANNOTATION_CLASSES),
                annotationCheckFuture::complete
        );

        annotationCheckFuture.thenAccept(proceed -> {
            if (!proceed) {
                logger.info("User cancelled at annotation verification step.");
                return;
            }

            // Refresh annotations to get the current state after user modifications
            List<PathObject> currentAnnotations = QP.getAnnotationObjects().stream()
                    .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                    .filter(ann -> ann.getPathClass() != null &&
                            Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                    .collect(Collectors.toList());

            if (currentAnnotations.isEmpty()) {
                logger.warn("No valid annotations found after user confirmation. Aborting workflow.");
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No valid annotations found. Please create at least one annotation with a valid class.",
                        "No Annotations"));
                return;
            }

            logger.info("Using {} annotation(s) after user confirmation", currentAnnotations.size());

            // Save image data
            try {
                QPProjectFunctions.saveCurrentImageData();
            } catch (IOException e) {
                logger.warn("Failed to save image data: {}", e.getMessage());
            }

            // Update tiles for the confirmed annotations
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            createTilesForAnnotations(qupathGUI, currentAnnotations, sample, tempTileDirectory,
                    modeWithIndex, macroPixelSize, invertedX, invertedY);

            // Start acquisition with the tiles
            startAcquisition(qupathGUI, projectDetails, sample, currentAnnotations, transform);
        });
    }

    /**
     * Starts the acquisition process using the established transform and annotations.
     */
    private static void startAcquisition(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            List<PathObject> annotations,
            AffineTransform transform) {

        logger.info("Starting acquisition with {} annotations", annotations.size());

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

        // Transform tile configurations from QuPath to stage coordinates
        try {
            List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                    tempTileDirectory, transform);
            logger.info("Transformed tile configurations for directories: {}", modifiedDirs);
        } catch (IOException e) {
            logger.error("Failed to transform tile configurations", e);
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to transform tile coordinates: " + e.getMessage(),
                    "Transform Error"));
            return;
        }

        // Get pixel size from configuration
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());
        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");

        // Continue with rotation and acquisition
        proceedWithRotationAndAcquisition(annotations, transform, sample,
                project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);
    }

    /**
     * Handles rotation selection and acquisition for the annotations.
     */
    private static void proceedWithRotationAndAcquisition(
            List<PathObject> annotations,
            AffineTransform transform,
            SampleSetupController.SampleSetupResult sample,
            Project<BufferedImage> project,
            String tempTileDirectory,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI qupathGUI) {

        // Get rotation angles based on modality
        logger.info("Checking rotation requirements for modality: {}", sample.modality());
        RotationManager rotationManager = new RotationManager(sample.modality());

        rotationManager.getRotationAngles(sample.modality())
                .thenAccept(rotationAngles -> {
                    logger.info("Rotation angles returned: {}", rotationAngles);

                    // Process annotations with already-transformed coordinates
                    processAnnotations(annotations, transform, sample, project,
                            tempTileDirectory, modeWithIndex, pixelSize,
                            qupathGUI, rotationAngles, rotationManager);
                })
                .exceptionally(ex -> {
                    logger.error("Rotation angle selection failed", ex);
                    return null;
                });
    }

    /**
     * Process all annotations: launch acquisition and queue stitching.
     * This version handles rotation by processing each angle separately.
     */
    private static void processAnnotations(
            List<PathObject> annotations,
            AffineTransform transform,
            SampleSetupController.SampleSetupResult sample,
            Project<BufferedImage> project,
            String tempTileDirectory,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI qupathGUI,
            List<Double> rotationAngles,
            RotationManager rotationManager) {

        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();

        // Launch acquisition for each annotation (with all angles handled internally)
        List<CompletableFuture<Void>> acquisitionFutures = new ArrayList<>();

        for (PathObject annotation : annotations) {
            String annotationName = annotation.getName();
            logger.info("Starting acquisition for annotation: {} with angles: {}",
                    annotationName, rotationAngles);

            CompletableFuture<Void> acquisitionFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<String> cliArgs = new ArrayList<>(List.of(
                            res.getString("command.acquisitionWorkflow"),
                            configFileLocation,
                            projectsFolder,
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName
                    ));

                    // Add angles parameter if rotation is needed
                    if (rotationAngles != null && !rotationAngles.isEmpty()) {
                        String anglesStr = rotationAngles.stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ", "(", ")"));
                        cliArgs.add(anglesStr);
                    }

                    int baseTimeout = 300; // 5 minutes base
                    int timeoutSeconds = baseTimeout;
                    if (rotationAngles != null && !rotationAngles.isEmpty()) {
                        timeoutSeconds = baseTimeout * rotationAngles.size();
                    }

                    var result = CliExecutor.execComplexCommand(
                            timeoutSeconds,
                            "tiles done",
                            cliArgs.toArray(new String[0])
                    );

                    if (result.exitCode() != 0) {
                        throw new RuntimeException("Acquisition failed for " + annotationName +
                                ": " + result.stderr());
                    }
                    logger.info("Acquisition completed for annotation: {}", annotationName);
                    return null;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            // Queue stitching after acquisition completes
            acquisitionFuture.thenRunAsync(() -> {
                if (rotationAngles != null && !rotationAngles.isEmpty()) {
                    // Stitch each angle separately
                    for (Double angle : rotationAngles) {
                        String angleSuffix = rotationManager.getAngleSuffix(sample.modality(), angle);
                        try {
                            logger.info("Queuing stitching for annotation: {} at angle: {}",
                                    annotationName, angle);
                            String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                    projectsFolder,
                                    sample.sampleName(),
                                    modeWithIndex + angleSuffix,
                                    annotationName,
                                    qupathGUI,
                                    project,
                                    String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                    pixelSize,
                                    1  // downsample
                            );
                            logger.info("Stitching completed for annotation: {} at angle: {}, output: {}",
                                    annotationName, angle, outPath);
                        } catch (Exception e) {
                            logger.error("Stitching failed for annotation: {} at angle: {}",
                                    annotationName, angle, e);
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Stitching failed for " + annotationName + " at angle " + angle +
                                            ": " + e.getMessage(),
                                    "Stitching Error"));
                        }
                    }
                } else {
                    // No rotation - single stitch
                    try {
                        logger.info("Queuing stitching for annotation: {}", annotationName);
                        String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                projectsFolder,
                                sample.sampleName(),
                                modeWithIndex,
                                annotationName,
                                qupathGUI,
                                project,
                                String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                pixelSize,
                                1  // downsample
                        );
                        logger.info("Stitching completed for annotation: {}, output: {}",
                                annotationName, outPath);
                    } catch (Exception e) {
                        logger.error("Stitching failed for annotation: {}", annotationName, e);
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Stitching failed for " + annotationName + ": " + e.getMessage(),
                                "Stitching Error"));
                    }
                }
            }, STITCH_EXECUTOR);

            acquisitionFutures.add(acquisitionFuture);
        }

        // Wait for all acquisitions to complete
        CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    logger.info("All acquisitions and stitching completed");
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Workflow Complete");
                        alert.setHeaderText("Acquisition workflow completed successfully");
                        alert.setContentText("All annotations have been acquired and stitched.");
                        alert.showAndWait();
                    });
                })
                .exceptionally(ex -> {
                    logger.error("Error in acquisition workflow", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Acquisition workflow failed: " + ex.getMessage(),
                            "Workflow Error"));
                    return null;
                });
    }
}