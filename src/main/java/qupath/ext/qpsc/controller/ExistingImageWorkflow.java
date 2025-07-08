package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.ui.ExistingImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
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
 * ExistingImageWorkflow
 * <p>
 * Orchestrates the full workflow for region-targeted microscope acquisition based on an existing (macro/low-res) image in QuPath.
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

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

            // Store auto-registration preference
            boolean useAutoRegistration = userInput.useAutoRegistration();
            logger.info("User selected auto-registration: {}", useAutoRegistration);

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
                        // Continue with the rest of the workflow
                        continueWorkflowAfterProjectSetup(qupathGUI, projectDetails, sample,
                                invertedX, invertedY, useAutoRegistration, userInput.macroPixelSize());

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
     * Simple result holder for auto-registration attempt.
     */
    private record AutoRegResult(boolean success, String message, AffineTransform transform) {}

    /**
     * Continues the workflow after project setup and image import is complete.
     * This method should be called on the JavaFX thread.
     */
    private static void continueWorkflowAfterProjectSetup(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            boolean invertedX,
            boolean invertedY,
            boolean useAutoRegistration,
            double initialPixelSize) {

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

        // Get macro image pixel size (use initial value if valid)
        CompletableFuture<Double> pixelSizeFuture;
        if (initialPixelSize > 0 && initialPixelSize <= 10) {
            pixelSizeFuture = CompletableFuture.completedFuture(initialPixelSize);
        } else {
            pixelSizeFuture = getMacroPixelSizeOrPrompt(qupathGUI);
        }

        pixelSizeFuture.thenAccept(macroPixelSize -> {
                    if (macroPixelSize == null || macroPixelSize <= 0) {
                        logger.warn("Invalid pixel size. Aborting workflow.");
                        return;
                    }
                    logger.info("Final macro pixel size: {} µm", macroPixelSize);

                    // Check if we should attempt auto-registration
                    if (useAutoRegistration && shouldAttemptAutoRegistration(qupathGUI)) {
                        // Try auto-registration first
                        tryAutoRegistration(qupathGUI, sample.modality())
                                .thenAccept(autoRegResult -> {
                                    if (autoRegResult != null && autoRegResult.success()) {
                                        logger.info("Auto-registration successful: {}", autoRegResult.message());

                                        // Check if we have annotations after auto-registration
                                        List<PathObject> annotations = qupathGUI.getViewer().getHierarchy()
                                                .getAnnotationObjects().stream()
                                                .filter(o -> o.getPathClass() != null &&
                                                        "Tissue".equals(o.getPathClass().getName()))
                                                .collect(Collectors.toList());

                                        if (!annotations.isEmpty()) {
                                            // Use the transform from auto-registration
                                            MicroscopeController.getInstance().setCurrentTransform(autoRegResult.transform());

                                            // Continue with annotation check
                                            proceedWithAnnotationCheck(qupathGUI, projectDetails, sample,
                                                    macroPixelSize, invertedX, invertedY, annotations, autoRegResult.transform());
                                        } else {
                                            // Auto-registration succeeded but no tissue found
                                            logger.warn("Auto-registration successful but no tissue detected");
                                            Platform.runLater(() -> {
                                                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                                                alert.setTitle("No Tissue Detected");
                                                alert.setHeaderText("Auto-registration successful but no tissue found");
                                                alert.setContentText("Please create tissue annotations manually.");
                                                alert.showAndWait();
                                            });
                                            proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                                                    macroPixelSize, invertedX, invertedY);
                                        }
                                    } else {
                                        // Auto-registration failed
                                        logger.info("Auto-registration failed or was skipped: {}",
                                                autoRegResult != null ? autoRegResult.message() : "null result");
                                        proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                                                macroPixelSize, invertedX, invertedY);
                                    }
                                })
                                .exceptionally(ex -> {
                                    logger.error("Error during auto-registration", ex);
                                    proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                                            macroPixelSize, invertedX, invertedY);
                                    return null;
                                });
                    } else {
                        // Skip auto-registration
                        logger.info("Auto-registration disabled or not available");
                        proceedWithManualAnnotations(qupathGUI, projectDetails, sample,
                                macroPixelSize, invertedX, invertedY);
                    }
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(), "Workflow Error"));
                    logger.error("Workflow failed", ex);
                    return null;
                });
    }

    /**
     * Checks if auto-registration should be attempted based on image capabilities.
     */
    private static boolean shouldAttemptAutoRegistration(QuPathGUI gui) {
        try {
            var associatedImages = gui.getImageData().getServer().getAssociatedImageList();
            if (associatedImages != null) {
                boolean hasMacro = associatedImages.stream()
                        .anyMatch(name -> name.toLowerCase().contains("macro"));
                logger.info("Checking for macro image: {}", hasMacro ? "found" : "not found");
                return hasMacro;
            }
        } catch (Exception e) {
            logger.error("Error checking for macro image", e);
        }
        return false;
    }

    /**
     * Attempts auto-registration and returns the result.
     */
    private static CompletableFuture<AutoRegResult> tryAutoRegistration(
            QuPathGUI gui, String modality) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get microscope name from config
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                String microscopeName = mgr.getString("microscope", "name");

                // Get transform manager
                AffineTransformManager transformManager = new AffineTransformManager(
                        new File(configPath).getParent());

                // Check for saved transforms
                var transforms = transformManager.getTransformsForMicroscope(microscopeName);
                if (transforms.isEmpty()) {
                    logger.info("No saved transforms available for auto-registration");
                    return new AutoRegResult(false, "No saved transforms available", null);
                }

                // Get the most recent transform
                var selectedTransform = transforms.get(0);
                logger.info("Using saved transform: {}", selectedTransform.getName());

                // Create auto-registration config
                var greenBoxParams = new GreenBoxDetector.DetectionParams();

                Map<String, Object> tissueParams = new HashMap<>();
                tissueParams.put("brightnessMin", 0.2);
                tissueParams.put("brightnessMax", 0.95);
                tissueParams.put("minRegionSize", 1000);

                var config = new AutoRegistrationWorkflow.AutoRegistrationConfig(
                        selectedTransform,
                        greenBoxParams,
                        MacroImageAnalyzer.ThresholdMethod.COLOR_DECONVOLUTION,
                        tissueParams,
                        true,  // Single bounds
                        0.7    // Confidence threshold
                );

                // Perform auto-registration
                var result = AutoRegistrationWorkflow.performAutoRegistration(gui, config);

                if (result.confidence() > 0.7 && !result.tissueAnnotations().isEmpty()) {
                    logger.info("Auto-registration successful with confidence {}", result.confidence());
                    return new AutoRegResult(true, result.message(), result.compositeTransform());
                } else {
                    logger.info("Auto-registration confidence too low or no annotations created");
                    return new AutoRegResult(false, result.message(), null);
                }

            } catch (Exception e) {
                logger.error("Auto-registration failed", e);
                return new AutoRegResult(false, "Error: " + e.getMessage(), null);
            }
        });
    }

    /**
     * Proceeds with manual annotation creation.
     */
    private static void proceedWithManualAnnotations(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        // This is the existing workflow - create or detect annotations manually
        List<PathObject> annotations = collectOrCreateAnnotations(qupathGUI, macroPixelSize);

        // Need to get transform manually since auto-registration wasn't used
        proceedWithManualTransformAndAnnotationCheck(qupathGUI, projectDetails, sample,
                macroPixelSize, invertedX, invertedY, annotations);
    }

    /**
     * Proceeds with manual transform setup and annotation check.
     */
    private static void proceedWithManualTransformAndAnnotationCheck(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> annotations) {

        // Show annotation check dialog first
        CompletableFuture<Boolean> annotationCheckFuture = new CompletableFuture<>();
        UIFunctions.checkValidAnnotationsGUI(
                List.of("Tissue"),
                annotationCheckFuture::complete
        );

        annotationCheckFuture.thenAccept(proceed -> {
            if (!proceed) {
                logger.info("User cancelled at annotation verification step.");
                return;
            }

            // Refresh annotations to get the current state after user modifications
            List<PathObject> currentAnnotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                    .collect(Collectors.toList());

            if (currentAnnotations.isEmpty()) {
                logger.warn("No valid annotations found after user confirmation. Aborting workflow.");
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No valid tissue annotations found. Please create at least one annotation with 'Tissue' classification.",
                        "No Annotations"));
                return;
            }

            logger.info("Using {} annotation(s) after user confirmation", currentAnnotations.size());

            // Continue with tile creation and manual transform setup
            createTilesAndSetupTransform(qupathGUI, projectDetails, sample,
                    macroPixelSize, invertedX, invertedY, currentAnnotations);
        });
    }

    /**
     * Common workflow continuation after annotations are established and transform is known.
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
                List.of("Tissue"),
                annotationCheckFuture::complete
        );

        annotationCheckFuture.thenAccept(proceed -> {
            if (!proceed) {
                logger.info("User cancelled at annotation verification step.");
                return;
            }

            // Refresh annotations to get the current state after user modifications
            List<PathObject> currentAnnotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                    .collect(Collectors.toList());

            if (currentAnnotations.isEmpty()) {
                logger.warn("No valid annotations found after user confirmation. Aborting workflow.");
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No valid tissue annotations found. Please create at least one annotation with 'Tissue' classification.",
                        "No Annotations"));
                return;
            }

            logger.info("Using {} annotation(s) after user confirmation", currentAnnotations.size());

            // Continue with tile creation using the existing transform
            createTilesWithKnownTransform(qupathGUI, projectDetails, sample,
                    macroPixelSize, invertedX, invertedY, currentAnnotations, transform);
        });
    }

    /**
     * Creates tiles and sets up transform manually (no auto-registration).
     */
    private static void createTilesAndSetupTransform(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> currentAnnotations) {

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

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
            // Remove existing tiles and create new ones
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass() != null &&
                            o.getPathClass().toString().contains(modalityBase))
                    .forEach(QP::removeObject);
            QP.fireHierarchyUpdate();
            logger.info("Existing tiles should be removed at this point");

            // Build tiling request with CURRENT annotations
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthQP, frameHeightQP)
                    .overlapPercent(overlapPercent)
                    .annotations(currentAnnotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created tiles for {} annotations", currentAnnotations.size());

        } catch (IOException e) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to create tile configurations: " + e.getMessage(),
                    "Tiling Error"));
            return;
        }

        // Check for saved transform first
        AffineTransform savedTransform = AffineTransformManager.loadSavedTransformFromPreferences();
        if (savedTransform != null) {
            logger.info("Using saved microscope alignment, skipping manual alignment");

            // Transform tile configurations
            try {
                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                        tempTileDirectory, savedTransform);
                logger.info("Transformed tile configurations for directories: {}", modifiedDirs);
            } catch (IOException e) {
                logger.error("Failed to transform tile configurations", e);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Failed to transform tile coordinates: " + e.getMessage(),
                        "Transform Error"));
                return;
            }

            // Continue with rotation and acquisition
            proceedWithRotationAndAcquisition(currentAnnotations, savedTransform, sample,
                    project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);

        } else {
            // No saved transform - do manual alignment
            logger.info("No saved transform found, proceeding with manual alignment");

            AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            macroPixelSize, invertedX, invertedY)
                    .thenAccept(transform -> {
                        if (transform == null) {
                            logger.info("User cancelled affine transform step.");
                            return;
                        }

                        // Transform tile configurations
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

                        // Continue with rotation and acquisition
                        proceedWithRotationAndAcquisition(currentAnnotations, transform, sample,
                                project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);
                    });
        }
    }

    /**
     * Creates tiles when transform is already known (from auto-registration).
     */
    private static void createTilesWithKnownTransform(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            List<PathObject> currentAnnotations,
            AffineTransform transform) {

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
        String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
        String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

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
            // Remove existing tiles and create new ones
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass() != null &&
                            o.getPathClass().toString().contains(modalityBase))
                    .forEach(QP::removeObject);
            QP.fireHierarchyUpdate();
            logger.info("Existing tiles should be removed at this point");

            // Build tiling request with CURRENT annotations
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthQP, frameHeightQP)
                    .overlapPercent(overlapPercent)
                    .annotations(currentAnnotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created tiles for {} annotations", currentAnnotations.size());

            // Transform tile configurations with known transform
            List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                    tempTileDirectory, transform);
            logger.info("Transformed tile configurations for directories: {}", modifiedDirs);

        } catch (IOException e) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to create or transform tile configurations: " + e.getMessage(),
                    "Tiling Error"));
            return;
        }

        // Continue with rotation and acquisition using the known transform
        proceedWithRotationAndAcquisition(currentAnnotations, transform, sample,
                project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);
    }

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
     * Process all annotations: transform tiles, acquire, and queue stitching.
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

                    var result = CliExecutor.execComplexCommand(
                            300 * (rotationAngles != null ? rotationAngles.size() : 1), // Adjust timeout
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
                                1
                        );
                        logger.info("Stitching completed for annotation: {}, output: {}",
                                annotationName, outPath);
                    } catch (Exception e) {
                        logger.error("Stitching failed for annotation: " + annotationName, e);
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Stitching failed for " + annotationName + ": " + e.getMessage(),
                                "Stitching Error"));
                    }
                }
            }, STITCH_EXECUTOR);

            acquisitionFutures.add(acquisitionFuture);
        }

        // When all acquisitions complete, handle cleanup
        CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    logger.info("All acquisitions completed");
                    String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                    if ("Delete".equals(handling)) {
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    } else if ("Zip".equals(handling)) {
                        UtilityFunctions.zipTilesAndMove(tempTileDirectory);
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error in acquisition workflow", ex);
                    return null;
                });
    }

    /**
     * Try to get the macro image pixel size from the open image. Prompt the user only if not found or out of range.
     */
    private static CompletableFuture<Double> getMacroPixelSizeOrPrompt(QuPathGUI qupathGUI) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        double macroPixelSize = Double.NaN;
        var imageData = qupathGUI.getImageData();
        if (imageData != null && imageData.getServer() != null) {
            try {
                macroPixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                logger.info("Pixel size found in image metadata: {}", macroPixelSize);
            } catch (Exception e) {
                macroPixelSize = Double.NaN;
            }
        }
        if (!Double.isNaN(macroPixelSize) && macroPixelSize > 0 && macroPixelSize <= 10) {
            future.complete(macroPixelSize);
        } else {
            logger.info("No valid macro pixel size in metadata. Prompting user...");
            ExistingImageController.requestPixelSizeDialog(macroPixelSize)
                    .thenAccept(future::complete)
                    .exceptionally(ex -> {
                        future.complete(null);
                        return null;
                    });
        }
        return future;
    }

    /**
     * Collects annotations from the image, running tissue detection script or prompting the user if none found.
     */
    private static List<PathObject> collectOrCreateAnnotations(QuPathGUI qupathGUI, double macroPixelSize) {
        // Try to get annotations
        List<PathObject> annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                .collect(Collectors.toList());

        if (annotations.isEmpty()) {
            // Run tissue detection script if configured
            String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
            if (tissueScript != null && !tissueScript.isBlank()) {
                logger.info("No annotations found. Running tissue detection script: {}", tissueScript);
                try {
                    Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                    String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                            tissueScript,
                            String.valueOf(macroPixelSize),
                            scriptPaths.get("jsonTissueClassfierPathString")
                    );
                    qupathGUI.runScript(null, modifiedScript);
                } catch (Exception e) {
                    UIFunctions.notifyUserOfError("Tissue detection script failed: " + e.getMessage(),
                            "Script Error");
                    logger.error("Tissue detection script error", e);
                }
                // Refresh annotations after script
                annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                        .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                        .collect(Collectors.toList());
            }

            // If still empty, prompt the user
            if (annotations.isEmpty()) {
                logger.info("Still no annotations. Prompting user to draw/select tissue regions.");
                ExistingImageController.promptForAnnotations();
                // Return empty list instead of null to continue workflow
                return new ArrayList<>();
            }
        }

        logger.info("Found {} tissue annotation(s) in image.", annotations.size());
        return annotations;
    }
}