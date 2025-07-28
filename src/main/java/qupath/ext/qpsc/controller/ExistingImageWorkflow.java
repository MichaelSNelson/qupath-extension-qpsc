package qupath.ext.qpsc.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.util.Duration;
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
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import qupath.fx.dialogs.Dialogs;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow - Handles acquisition workflows for images already open in QuPath.
 * <p>
 * Supports two paths:
 * - Path A: Use existing saved alignment (slide-specific or general transform)
 * - Path B: Create new alignment through manual annotation workflow
 *
 * @author Mike Nelson
 * @version 2.2 - Fixed tile coordinate transformation to stage coordinates
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Single-threaded executor for stitching operations
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the workflow. Follows the sequence:
     * 1. Check for open image
     * 2. Sample setup dialog
     * 3. Check for existing slide-specific alignment (NEW)
     * 4. Alignment selection or skip if using existing
     * 5. Path-specific dialogs and project creation
     * 6. Acquisition and stitching
     */
    public static void run() {
        logger.info("************************************");
        logger.info("Starting Existing Image Workflow...");
        logger.info("************************************");
        QuPathGUI gui = QuPathGUI.getInstance();

        // Verify we have an open image
        if (gui.getImageData() == null) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No image is currently open. Please open an image first.",
                            "No Image")
            );
            return;
        }

        // Step 1: Sample Setup Dialog
        SampleSetupController.showDialog()
                .thenCompose(sample -> {
                    if (sample == null) {
                        logger.info("Sample setup cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Step 2: Check for existing slide-specific alignment
                    return checkForSlideSpecificAlignment(gui, sample)
                            .thenCompose(slideAlignment -> {
                                if (slideAlignment != null) {
                                    // Fast path: Use existing slide alignment
                                    return handleExistingSlideAlignment(gui, sample, slideAlignment);
                                } else {
                                    // Normal path: Continue with alignment selection
                                    return continueWithAlignmentSelection(gui, sample);
                                }
                            });
                })
                .exceptionally(ex -> {
                    // Check if this is a cancellation - if so, handle gracefully
                    if (ex.getCause() instanceof CancellationException) {
                        logger.info("User cancelled the workflow");
                        return null;
                    }

                    logger.error("Alignment workflow failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(),
                            "Alignment Error"));
                    return null;
                });
    }

    /**
     * Check if there's an existing slide-specific alignment and ask user if they want to use it
     */
    private static CompletableFuture<AffineTransform> checkForSlideSpecificAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        // Try to load slide-specific alignment
        AffineTransform slideTransform;

        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            // Project is already open
            slideTransform = AffineTransformManager.loadSlideAlignment(project, sample.sampleName());
        } else {
            // No project yet, check if one would exist
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(projectDir, sample.sampleName());
            } else {
                slideTransform = null;
            }
        }

        if (slideTransform != null) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment Found");
                alert.setHeaderText("Found existing alignment for slide '" + sample.sampleName() + "'");
                alert.setContentText("Would you like to use the existing slide alignment?\n\n" +
                        "Choose 'Yes' to proceed directly to acquisition.\n" +
                        "Choose 'No' to create a new alignment.");

                ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
                ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
                alert.getButtonTypes().setAll(yesButton, noButton);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == yesButton) {
                    logger.info("User chose to use existing slide alignment");
                    future.complete(slideTransform);
                } else {
                    logger.info("User chose to create new alignment");
                    future.complete(null);
                }
            });
        } else {
            future.complete(null);
        }

        return future;
    }

    /**
     * Fast path when using existing slide alignment
     */
    private static CompletableFuture<Void> handleExistingSlideAlignment(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            AffineTransform slideTransform) {

        logger.info("Using existing slide-specific alignment");

        // Set the transform
        MicroscopeController.getInstance().setCurrentTransform(slideTransform);

        // Setup or get project
        return setupProject(gui, sample)
                .thenCompose(projectInfo -> {
                    if (projectInfo == null) {
                        return CompletableFuture.completedFuture(null);
                    }

                    // Get macro pixel size from preferences
                    String macroPixelSizeStr = PersistentPreferences.getMacroImagePixelSizeInMicrons();
                    double macroPixelSize = 80.0; // default
                    try {
                        macroPixelSize = Double.parseDouble(macroPixelSizeStr);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid macro pixel size in preferences, using default: {}", macroPixelSize);
                    }

                    // Create or validate annotations
                    List<PathObject> annotations = ensureAnnotationsExist(gui, macroPixelSize);
                    if (annotations.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    // Proceed directly to acquisition
                    return continueToAcquisition(gui, sample, projectInfo,
                            annotations, macroPixelSize, slideTransform);
                });
    }

    /**
     * Normal path with alignment selection
     */
    private static CompletableFuture<Void> continueWithAlignmentSelection(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        // Continue with normal alignment selection dialog
        return AlignmentSelectionController.showDialog(gui, sample.modality())
                .thenApply(alignment -> alignment != null ? new WorkflowContext(sample, alignment) : null)
                .thenCompose(context -> {
                    if (context == null) {
                        logger.info("Workflow cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Path-specific handling
                    if (context.alignment.useExistingAlignment()) {
                        return handleExistingAlignmentPath(gui, context);
                    } else {
                        return handleManualAlignmentPath(gui, context);
                    }
                });
    }

    /**
     * Handle Path A: Using existing alignment
     */
    private static CompletableFuture<Void> handleExistingAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path A: Using existing alignment");

        // Check macro image availability
        if (!MacroImageUtility.isMacroImageAvailable(gui)) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No macro image found. Cannot use existing alignment.",
                            "Macro Image Required")
            );
            return CompletableFuture.completedFuture(null);
        }

        // Get macro pixel size
        double pixelSize;
        try {
            pixelSize = MacroImageUtility.getMacroPixelSize();
        } catch (IllegalStateException e) {
            UIFunctions.notifyUserOfError(
                    e.getMessage() + "\n\nThe workflow cannot continue without the macro image pixel size.",
                    "Configuration Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        // Get the original macro image
        BufferedImage originalMacroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (originalMacroImage == null) {
            UIFunctions.notifyUserOfError(
                    "Cannot retrieve macro image",
                    "Macro Image Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        // IMPORTANT: Crop the macro image first
        MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage);
        BufferedImage croppedMacroImage = croppedResult.getCroppedImage();

        // Then apply flips if configured
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
        BufferedImage displayMacroImage = croppedMacroImage;

        if (flipX || flipY) {
            displayMacroImage = MacroImageUtility.flipMacroImage(croppedMacroImage, flipX, flipY);
            logger.info("Applied flips to cropped macro image: X={}, Y={}", flipX, flipY);
        }

        // Get green box detection parameters
        GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();

        // If we have a saved transform with green box params, use those
        if (context.alignment.selectedTransform() != null &&
                context.alignment.selectedTransform().getGreenBoxParams() != null) {
            params = context.alignment.selectedTransform().getGreenBoxParams();
            logger.info("Using saved green box parameters from transform");
        }

        // Save macro dimensions (cropped dimensions)
        int macroWidth = croppedMacroImage.getWidth();
        int macroHeight = croppedMacroImage.getHeight();

        // Show enhanced green box preview with update button
        return GreenBoxPreviewController.showPreviewDialog(displayMacroImage, params)
                .thenApply(greenBoxResult -> {
                    if (greenBoxResult == null) return null;
                    // Note: greenBoxResult contains coordinates in the displayed (cropped+flipped) image space
                    return new AlignmentContext(context, pixelSize, greenBoxResult,
                            macroWidth, macroHeight,
                            croppedResult.getCropOffsetX(),
                            croppedResult.getCropOffsetY());
                })
                .thenCompose(alignContext -> {
                    if (alignContext == null) return CompletableFuture.completedFuture(null);

                    // Now create/setup project
                    return setupProject(gui, alignContext.context.sample)
                            .thenCompose(projectInfo -> {
                                if (projectInfo == null) return CompletableFuture.completedFuture(null);

                                // Continue with existing alignment workflow
                                return processExistingAlignment(gui, alignContext, projectInfo);
                            });
                });
    }

    /**
     * Handle Path B: Manual alignment
     */
    private static CompletableFuture<Void> handleManualAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path B: Manual alignment");

        // Get macro pixel size
        double pixelSize;
        try {
            pixelSize = MacroImageUtility.getMacroPixelSize();
        } catch (IllegalStateException e) {
            UIFunctions.notifyUserOfError(
                    e.getMessage() + "\n\nThe workflow cannot continue without the macro image pixel size.",
                    "Configuration Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        // Create/setup project
        return setupProject(gui, context.sample)
                .thenApply(projectInfo -> {
                    if (projectInfo == null) return null;
                    return new ManualAlignmentContext(context, pixelSize, projectInfo);
                })
                .thenCompose(manualContext -> {
                    if (manualContext == null) return CompletableFuture.completedFuture(null);

                    // Continue with manual alignment workflow
                    return processManualAlignment(gui, manualContext);
                });
    }

    /**
     * Setup project - create new or use existing
     */
    private static CompletableFuture<ProjectInfo> setupProject(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<ProjectInfo> future = new CompletableFuture<>();

        // Run on JavaFX thread since QPProjectFunctions manipulates GUI
        Platform.runLater(() -> {
            try {
                Map<String, Object> projectDetails;
                boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();

                if (gui.getProject() == null) {
                    logger.info("Creating new project");
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            gui,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality(),
                            flippedX,
                            flippedY
                    );
                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                    if (macroImage != null) {
                        projectDetails.put("macroWidth", macroImage.getWidth());
                        projectDetails.put("macroHeight", macroImage.getHeight());
                        logger.info("Saved macro dimensions to project: {} x {}",
                                macroImage.getWidth(), macroImage.getHeight());
                    }
                } else {
                    logger.info("Using existing project");
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality()
                    );

                    // Handle flips for existing image if needed
                    var imageData = gui.getImageData();
                    if (imageData != null && (flippedX || flippedY)) {
                        String imagePath = MinorFunctions.extractFilePath(imageData.getServerPath());
                        if (imagePath != null) {
                            QPProjectFunctions.addImageToProject(
                                    new File(imagePath),
                                    gui.getProject(),
                                    flippedX,
                                    flippedY
                            );

                            // Refresh and reopen the image
                            gui.refreshProject();
                            var entries = gui.getProject().getImageList();
                            var newEntry = entries.stream()
                                    .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                    .findFirst()
                                    .orElse(null);
                            if (newEntry != null) {
                                gui.openImageEntry(newEntry);
                            }
                        }
                    }
                }

                // Give GUI time to update
                PauseTransition pause = new PauseTransition(Duration.millis(500));
                pause.setOnFinished(e -> {
                    future.complete(new ProjectInfo(projectDetails));
                });
                pause.play();

            } catch (Exception e) {
                logger.error("Failed to setup project", e);
                UIFunctions.notifyUserOfError(
                        "Failed to setup project: " + e.getMessage(),
                        "Project Error"
                );
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Processes the existing alignment workflow after green box detection.
     * Creates a full-resolution to stage transform by combining the detected green box
     * with the saved general macro-to-stage transform.
     *
     * <p>This method handles the critical transformation chain:
     * <pre>
     * Full-res pixels → Macro (flipped/cropped) → Stage micrometers
     * </pre>
     *
     * <p>The saved transform expects macro coordinates in the displayed (flipped/cropped) coordinate system,
     * which is exactly what we have after green box detection.
     *
     * @param gui QuPath GUI instance
     * @param alignContext Context containing alignment settings and green box detection results
     * @param projectInfo Project information including paths and settings
     * @return CompletableFuture that completes when alignment processing is done
     */
    private static CompletableFuture<Void> processExistingAlignment(
            QuPathGUI gui, AlignmentContext alignContext, ProjectInfo projectInfo) {

        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");

        // Get the base transform (macro→stage)
        AffineTransform macroToStageTransform = alignContext.context.alignment.selectedTransform().getTransform();
        logger.info("Loaded macro→stage transform: {}", macroToStageTransform);

        // Get or create annotations first
        List<PathObject> annotations = ensureAnnotationsExist(gui, alignContext.pixelSize);
        if (annotations.isEmpty()) {
            logger.warn("No annotations available");
            return CompletableFuture.completedFuture(null);
        }

        // Create full-res→stage transform using current green box
        ROI greenBox = alignContext.greenBoxResult.getDetectedBox();
        int fullResWidth = gui.getImageData().getServer().getWidth();
        int fullResHeight = gui.getImageData().getServer().getHeight();

        logger.info("Green box detected at ({}, {}, {}, {}) in displayed (cropped+flipped) macro",
                greenBox.getBoundsX(), greenBox.getBoundsY(),
                greenBox.getBoundsWidth(), greenBox.getBoundsHeight());

        // Create full-res to macro transform
        double scaleX = greenBox.getBoundsWidth() / fullResWidth;
        double scaleY = greenBox.getBoundsHeight() / fullResHeight;

        AffineTransform fullResToMacro = new AffineTransform();
        fullResToMacro.translate(greenBox.getBoundsX(), greenBox.getBoundsY());
        fullResToMacro.scale(scaleX, scaleY);

        logger.info("FullRes→Macro transform: {}", fullResToMacro);

        // Test this transform
        double[] origin = {0, 0};
        double[] corner = {fullResWidth, fullResHeight};
        double[] originInMacro = new double[2];
        double[] cornerInMacro = new double[2];
        fullResToMacro.transform(origin, 0, originInMacro, 0, 1);
        fullResToMacro.transform(corner, 0, cornerInMacro, 0, 1);
        logger.info("Transform verification: full-res (0,0) → macro {}, full-res ({},{}) → macro {}",
                Arrays.toString(originInMacro), fullResWidth, fullResHeight, Arrays.toString(cornerInMacro));

        // Now combine with macro→stage
        AffineTransform fullResToStage = new AffineTransform(macroToStageTransform);
        fullResToStage.concatenate(fullResToMacro);

        logger.info("Created full-res→stage transform: {}", fullResToStage);

        // Get stage limits from configuration
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double stageXMin = mgr.getDouble("stage", "xlimit", "low");
        double stageXMax = mgr.getDouble("stage", "xlimit", "high");
        double stageYMin = mgr.getDouble("stage", "ylimit", "low");
        double stageYMax = mgr.getDouble("stage", "ylimit", "high");

        logger.info("Stage limits from config - X: [{}, {}], Y: [{}, {}]",
                stageXMin, stageXMax, stageYMin, stageYMax);

        // Validate the transform
        boolean isValid = TransformationFunctions.validateTransform(
                fullResToStage,
                fullResWidth,
                fullResHeight,
                stageXMin, stageXMax,
                stageYMin, stageYMax
        );

        if (!isValid) {
            logger.error("Transform validation failed - produces out-of-bounds coordinates!");

            // Test some specific points to show the user what's wrong
            double[][] testPoints = {
                    {fullResWidth/2, fullResHeight/2},  // Center
                    {0, 0},                              // Top-left
                    {fullResWidth, fullResHeight}       // Bottom-right
            };

            StringBuilder errorDetails = new StringBuilder();
            for (double[] point : testPoints) {
                double[] stagePoint = TransformationFunctions.transformQuPathFullResToStage(point, fullResToStage);
                errorDetails.append(String.format("Full-res (%.0f, %.0f) → Stage (%.1f, %.1f)\n",
                        point[0], point[1], stagePoint[0], stagePoint[1]));
            }

            final String details = errorDetails.toString();
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "The alignment transform would produce coordinates outside stage limits.\n\n" +
                            "Stage limits: X[" + stageXMin + " to " + stageXMax + "], Y[" + stageYMin + " to " + stageYMax + "]\n\n" +
                            "Sample transform results:\n" + details + "\n" +
                            "Please create a new alignment for this slide.",
                    "Invalid Transform"
            ));
            return CompletableFuture.completedFuture(null);
        }

        // Set this as the current transform
        MicroscopeController.getInstance().setCurrentTransform(fullResToStage);

        // IMPORTANT: We will transform tiles AFTER they are created, not the TileConfiguration files
        // The TileConfiguration files should remain in QuPath coordinates for stitching

        // Check if we have a slide-specific alignment already
        AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(
                project, alignContext.context.sample.sampleName());

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment");

            // Ask user if they want to use it or refine it
            CompletableFuture<Boolean> useExisting = new CompletableFuture<>();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment");
                alert.setHeaderText("Found existing alignment for this slide");
                alert.setContentText("Would you like to use the existing slide alignment or create a new one?");

                ButtonType useButton = new ButtonType("Use Existing");
                ButtonType refineButton = new ButtonType("Create New");
                alert.getButtonTypes().setAll(useButton, refineButton);

                Optional<ButtonType> result = alert.showAndWait();
                useExisting.complete(result.isPresent() && result.get() == useButton);
            });

            return useExisting.thenCompose(useExistingAlignment -> {
                if (useExistingAlignment) {
                    // Use the existing slide alignment
                    MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                    logger.info("Using existing slide-specific alignment");

                    // Skip refinement and continue directly to acquisition
                    return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                            annotations, alignContext.pixelSize, slideTransform);
                } else {
                    // User wants to create new alignment, continue with normal flow
                    return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                            annotations, fullResToStage);
                }
            });
        } else {
            // No existing slide alignment, proceed with normal flow
            return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                    annotations, fullResToStage);
        }
    }

    /**
     * Helper method to handle the alignment refinement process
     */
    private static CompletableFuture<Void> proceedWithAlignmentRefinement(
            QuPathGUI gui,
            AlignmentContext alignContext,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            AffineTransform fullResToStageTransform) {

        MicroscopeController.getInstance().setCurrentTransform(fullResToStageTransform);

        logger.info("Green box detected at ({}, {}, {}, {})",
                alignContext.greenBoxResult.getDetectedBox().getBoundsX(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsY(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsWidth(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsHeight());

        // Create tiles for all annotations
        String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
        String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        createTilesForAnnotations(annotations, alignContext.context.sample, tempTileDir,
                modeWithIndex, alignContext.pixelSize, invertedX, invertedY);

        if (alignContext.context.alignment.refinementRequested()) {
            // Do single-tile refinement (tiles already exist)
            return performSingleTileRefinement(gui, alignContext, projectInfo, annotations, fullResToStageTransform)
                    .thenCompose(refinedTransform -> {
                        if (refinedTransform == null) refinedTransform = fullResToStageTransform;
                        return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                                annotations, alignContext.pixelSize, refinedTransform);
                    });
        } else {
            // Continue without refinement
            // Save this as the slide-specific transform
            Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
            AffineTransformManager.saveSlideAlignment(
                    project,
                    alignContext.context.sample.sampleName(),
                    alignContext.context.sample.modality(),
                    fullResToStageTransform
            );

            return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                    annotations, alignContext.pixelSize, fullResToStageTransform);
        }
    }

    /**
     * Process manual alignment workflow
     */
    private static CompletableFuture<Void> processManualAlignment(
            QuPathGUI gui, ManualAlignmentContext context) {

        // Get or create annotations
        List<PathObject> annotations = ensureAnnotationsExist(gui, context.pixelSize);

        if (annotations.isEmpty()) {
            logger.warn("No annotations available for manual workflow");
            return CompletableFuture.completedFuture(null);
        }

        // Create initial tiles for alignment
        String tempTileDir = (String) context.projectInfo.details.get("tempTileDirectory");
        String modeWithIndex = (String) context.projectInfo.details.get("imagingModeWithIndex");
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        createTilesForAnnotations(annotations, context.context.sample, tempTileDir,
                modeWithIndex, context.pixelSize, invertedX, invertedY);

        // Setup manual transform
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        context.pixelSize, invertedX, invertedY)
                .thenCompose(transform -> {
                    if (transform == null) {
                        logger.info("Transform setup cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // The transform from AffineTransformationController is already full-res to stage
                    MicroscopeController.getInstance().setCurrentTransform(transform);

                    // Save the slide-specific transform
                    Project<BufferedImage> project = (Project<BufferedImage>) context.projectInfo.details.get("currentQuPathProject");
                    AffineTransformManager.saveSlideAlignment(
                            project,
                            context.context.sample.sampleName(),
                            context.context.sample.modality(),
                            transform
                    );

                    // Offer to save the GENERAL transform (need to extract macro to stage portion)
                    // This would require knowing the green box position, which we don't have in manual mode
                    // So we skip this for now

                    return continueToAcquisition(gui, context.context.sample,
                            context.projectInfo, annotations, context.pixelSize, transform);
                });
    }

    /**
     * Ensure annotations exist - either collect existing or create new ones
     * Always returns current valid annotations with names
     */
    private static List<PathObject> ensureAnnotationsExist(
            QuPathGUI gui, double macroPixelSize) {

        // Get existing annotations
        List<PathObject> annotations = getCurrentValidAnnotations();

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing annotations", annotations.size());
            ensureAnnotationNames(annotations);
            return annotations;
        }

        // Run tissue detection if configured
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();

        // If no script is configured, prompt user to select one
        if (tissueScript == null || tissueScript.isBlank()) {
            tissueScript = promptForTissueDetectionScript();

        }

        if (tissueScript != null && !tissueScript.isBlank()) {
            try {
                double pixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                        tissueScript,
                        String.valueOf(pixelSize),
                        scriptPaths.get("jsonTissueClassfierPathString")
                );

                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Re-collect annotations
                annotations = getCurrentValidAnnotations();

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
            }
        }

        if (annotations.isEmpty()) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No valid annotations found. Please create annotations with one of these classes:\n" +
                                    String.join(", ", VALID_ANNOTATION_CLASSES),
                            "No Annotations")
            );
        } else {
            ensureAnnotationNames(annotations);
        }

        return annotations;
    }

    /**
     * Prompt user to select a tissue detection script
     */
    private static String promptForTissueDetectionScript() {
        CompletableFuture<String> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // First check if user wants to use tissue detection
            var useDetection = Dialogs.showYesNoDialog(
                    "Tissue Detection",
                    "Would you like to run automatic tissue detection?\n\n" +
                            "This will create annotations for tissue regions."
            );

            if (!useDetection) {
                future.complete(null);
                return;
            }

            // Show file chooser
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Select Tissue Detection Script");
            fileChooser.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("Groovy Scripts", "*.groovy"),
                    new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
            );

            // Set initial directory if possible
            String lastScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
            if (lastScript != null && !lastScript.isBlank()) {
                File lastFile = new File(lastScript);
                if (lastFile.getParentFile() != null && lastFile.getParentFile().exists()) {
                    fileChooser.setInitialDirectory(lastFile.getParentFile());
                }
            }

            File selectedFile = fileChooser.showOpenDialog(null);
            if (selectedFile != null) {
                future.complete(selectedFile.getAbsolutePath());
            } else {
                future.complete(null);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error selecting tissue detection script", e);
            return null;
        }
    }

    /**
     * Get current valid annotations from the image
     */
    private static List<PathObject> getCurrentValidAnnotations() {
        return QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());
    }

    /**
     * Ensure all annotations have names (auto-generate if needed)
     */
    private static void ensureAnnotationNames(List<PathObject> annotations) {
        int unnamedCount = 0;
        for (PathObject ann : annotations) {
            if (ann.getName() == null || ann.getName().trim().isEmpty()) {
                String className = ann.getPathClass() != null ?
                        ann.getPathClass().getName() : "Annotation";

                // Create a unique name based on position
                String name = String.format("%s_%d_%d",
                        className,
                        Math.round(ann.getROI().getCentroidX()),
                        Math.round(ann.getROI().getCentroidY()));

                ann.setName(name);
                logger.info("Auto-named annotation: {}", name);
                unnamedCount++;
            }
        }

        if (unnamedCount > 0) {
            logger.info("Auto-named {} annotations", unnamedCount);
        }
    }

    /**
     * Create tiles for the given annotations - tiles remain in QuPath coordinates
     */
    private static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
        int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
        int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

        // Get the actual image pixel size to determine correct scaling
        QuPathGUI gui = QuPathGUI.getInstance();
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        logger.info("Image pixel size: {} µm, Macro pixel size: {} µm, Camera pixel size: {} µm",
                imagePixelSize, macroPixelSize, pixelSize);

        // Calculate frame dimensions in image pixels
        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to image pixels (not macro pixels if they're different)
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        logger.info("Frame size in pixels: {} x {} (from {} x {} µm)",
                frameWidthPixels, frameHeightPixels, frameWidthMicrons, frameHeightMicrons);

        try {
            // Remove existing tiles
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass() != null &&
                            o.getPathClass().toString().contains(modalityBase))
                    .forEach(QP::removeObject);
            QP.fireHierarchyUpdate();

            // Validate tile size before creating tiles
            for (PathObject ann : annotations) {
                if (ann.getROI() != null) {
                    double annWidth = ann.getROI().getBoundsWidth();
                    double annHeight = ann.getROI().getBoundsHeight();
                    double tilesX = Math.ceil(annWidth / (frameWidthPixels * (1 - overlapPercent/100.0)));
                    double tilesY = Math.ceil(annHeight / (frameHeightPixels * (1 - overlapPercent/100.0)));
                    double totalTiles = tilesX * tilesY;

                    if (totalTiles > 10000) {
                        logger.error("Annotation '{}' would require {} tiles ({} x {}). This is too many!",
                                ann.getName(), totalTiles, tilesX, tilesY);
                        throw new RuntimeException(String.format(
                                "Annotation '%s' would require %.0f tiles. Maximum allowed is 10000.\n" +
                                        "This usually indicates incorrect pixel size settings.\n" +
                                        "Annotation size: %.0f x %.0f pixels\n" +
                                        "Tile size: %.0f x %.0f pixels",
                                ann.getName(), totalTiles, annWidth, annHeight,
                                frameWidthPixels, frameHeightPixels));
                    }
                }
            }

            // Create new tiles
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthPixels, frameHeightPixels)
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
            throw new RuntimeException("Failed to create tiles: " + e.getMessage(), e);
        }
    }

    /**
     * Perform single-tile refinement
     */
    private static CompletableFuture<AffineTransform> performSingleTileRefinement(
            QuPathGUI gui,
            AlignmentContext alignContext,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            AffineTransform initialTransform) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        logger.info("Starting single-tile refinement with full-res→stage transform");

        // Select tile for refinement (tiles should already exist)
        UIFunctions.promptTileSelectionDialogAsync(
                "Select a tile for alignment refinement.\n" +
                        "The microscope will move to approximately the correct position.\n" +
                        "You can then fine-tune the alignment if needed."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                future.complete(initialTransform);
                return;
            }

            try {
                // Get tile coordinates in full-res pixels
                double[] tileCoords = {
                        selectedTile.getROI().getCentroidX(),
                        selectedTile.getROI().getCentroidY()
                };

                logger.info("Selected tile '{}' at full-res coordinates: ({}, {})",
                        selectedTile.getName(), tileCoords[0], tileCoords[1]);

                // Transform directly to stage using full-res→stage transform
                double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                        tileCoords, initialTransform
                );

                logger.info("Tile coordinates map to stage: ({}, {})", stageCoords[0], stageCoords[1]);

                // Get stage bounds from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                        QPPreferenceDialog.getMicroscopeConfigFileProperty());

                double stageXMin = mgr.getDouble("stage", "xlimit", "low");
                double stageXMax = mgr.getDouble("stage", "xlimit", "high");
                double stageYMin = mgr.getDouble("stage", "ylimit", "low");
                double stageYMax = mgr.getDouble("stage", "ylimit", "high");

                // Verify stage bounds for this specific coordinate
                logger.info("Stage bounds check:");
                logger.info("  X: {} (limits: {} to {}) - {}",
                        stageCoords[0], stageXMin, stageXMax,
                        (stageCoords[0] >= stageXMin && stageCoords[0] <= stageXMax) ? "OK" : "OUT OF BOUNDS!");
                logger.info("  Y: {} (limits: {} to {}) - {}",
                        stageCoords[1], stageYMin, stageYMax,
                        (stageCoords[1] >= stageYMin && stageCoords[1] <= stageYMax) ? "OK" : "OUT OF BOUNDS!");

                if (stageCoords[0] < stageXMin || stageCoords[0] > stageXMax ||
                        stageCoords[1] < stageYMin || stageCoords[1] > stageYMax) {
                    Platform.runLater(() -> {
                        UIFunctions.notifyUserOfError(
                                String.format("Selected tile is outside stage bounds!\n" +
                                                "Tile would move to: (%.1f, %.1f)\n" +
                                                "Stage limits: X: %.0f to %.0f, Y: %.0f to %.0f",
                                        stageCoords[0], stageCoords[1],
                                        stageXMin, stageXMax, stageYMin, stageYMax),
                                "Stage Bounds Error"
                        );
                    });
                    future.complete(initialTransform);
                    return;
                }

                logger.info("Moving to tile '{}' at stage position: ({}, {})",
                        selectedTile.getName(), stageCoords[0], stageCoords[1]);

                MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                boolean refined = UIFunctions.stageToQuPathAlignmentGUI2();

                if (refined) {
                    double[] refinedStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                    logger.info("Refined stage position: ({}, {})", refinedStageCoords[0], refinedStageCoords[1]);

                    // Calculate the refined transform
                    AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                            initialTransform, tileCoords, refinedStageCoords
                    );

                    logger.info("Saving refined slide-specific transform");

                    // Save the slide-specific alignment
                    Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
                    AffineTransformManager.saveSlideAlignment(
                            project,
                            alignContext.context.sample.sampleName(),
                            alignContext.context.sample.modality(),
                            refinedTransform
                    );

                    future.complete(refinedTransform);
                } else {
                    logger.info("Refinement cancelled - using initial transform");

                    // Still save the initial transform as slide-specific
                    Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
                    AffineTransformManager.saveSlideAlignment(
                            project,
                            alignContext.context.sample.sampleName(),
                            alignContext.context.sample.modality(),
                            initialTransform
                    );

                    future.complete(initialTransform);
                }

            } catch (Exception e) {
                logger.error("Error during refinement", e);
                Platform.runLater(() -> {
                    UIFunctions.notifyUserOfError(
                            "Error during refinement: " + e.getMessage(),
                            "Refinement Error"
                    );
                });
                future.complete(initialTransform);
            }
        });

        return future;
    }

    /**
     * Offer to save transform for future use
     */
    private static CompletableFuture<Boolean> offerToSaveTransform(
            AffineTransform transform, String modality) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            var result = Dialogs.showYesNoDialog(
                    "Save Transform",
                    "Would you like to save this transform for future use?"
            );

            if (result) {
                TextInputDialog nameDialog = new TextInputDialog();
                nameDialog.setTitle("Save Transform");
                nameDialog.setHeaderText("Enter a name for this transform");
                nameDialog.setContentText("Transform name:");

                Optional<String> name = nameDialog.showAndWait();
                if (name.isPresent() && !name.get().trim().isEmpty()) {
                    try {
                        // Get microscope name from config
                        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                        String microscopeName = mgr.getString("microscope", "name");

                        // Create transform manager
                        AffineTransformManager transformManager = new AffineTransformManager(
                                new File(configPath).getParent());

                        // Create and save preset
                        AffineTransformManager.TransformPreset preset =
                                new AffineTransformManager.TransformPreset(
                                        name.get(),
                                        microscopeName,
                                        "Standard", // mounting method
                                        transform,
                                        "Created via manual alignment",
                                        null // no green box params for manual alignment
                                );

                        transformManager.savePreset(preset);
                        logger.info("Transform saved as: {}", name.get());
                        future.complete(true);
                    } catch (Exception e) {
                        logger.error("Failed to save transform", e);
                        UIFunctions.notifyUserOfError(
                                "Failed to save transform: " + e.getMessage(),
                                "Save Error"
                        );
                        future.complete(false);
                    }
                } else {
                    future.complete(false);
                }
            } else {
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Continue to acquisition phase
     */
    private static CompletableFuture<Void> continueToAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            double macroPixelSize,
            AffineTransform transform) {

        // Validate annotations
        CompletableFuture<Boolean> validationFuture = new CompletableFuture<>();

        UIFunctions.checkValidAnnotationsGUI(
                Arrays.asList(VALID_ANNOTATION_CLASSES),
                userConfirmed -> validationFuture.complete(userConfirmed)
        );

        return validationFuture.thenCompose(confirmed -> {
            if (!confirmed) {
                logger.info("User cancelled annotation validation");
                return CompletableFuture.completedFuture(null);
            }

            // Get rotation angles with exposure times
            RotationManager rotationManager = new RotationManager(sample.modality());

            return rotationManager.getRotationTicksWithExposure(sample.modality())
                    .thenCompose(angleExposures -> {
                        // Extract just the angles for logging
                        List<Double> rotationAngles = angleExposures.stream()
                                .map(ae -> ae.ticks)
                                .collect(Collectors.toList());

                        logger.info("Rotation angles: {}", rotationAngles);
                        logger.info("With exposures: {}", angleExposures);

                        // IMPORTANT: Regenerate tiles for current annotations before acquisition
                        String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
                        String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");

                        List<PathObject> currentAnnotations = ensureAnnotationsReadyForAcquisition(
                                gui, sample, tempTileDir, modeWithIndex, macroPixelSize, transform);

                        if (currentAnnotations.isEmpty()) {
                            logger.warn("No annotations available for acquisition after tile regeneration");
                            return CompletableFuture.completedFuture(null);
                        }

                        // Process annotations
                        return processAnnotationsForAcquisition(
                                gui, sample, projectInfo, currentAnnotations,
                                macroPixelSize, transform, angleExposures, rotationManager
                        );
                    });
        });
    }    /**
     * Delete all tiles for a given modality
     */
    private static void deleteAllTiles(QuPathGUI gui, String modality) {
        String modalityBase = modality.replaceAll("(_\\d+)$", "");

        List<PathObject> tilesToRemove = gui.getViewer().getHierarchy().getDetectionObjects().stream()
                .filter(o -> o.getPathClass() != null &&
                        o.getPathClass().toString().contains(modalityBase))
                .collect(Collectors.toList());

        if (!tilesToRemove.isEmpty()) {
            tilesToRemove.forEach(tile ->
                    gui.getViewer().getHierarchy().removeObject(tile, false));
            gui.getViewer().getHierarchy().fireHierarchyChangedEvent(gui.getViewer());
            logger.info("Removed {} existing tiles for modality: {}", tilesToRemove.size(), modalityBase);
        }
    }

    /**
     * Ensure annotations are ready for acquisition - regenerate tiles and transform coordinates to stage
     */
    private static List<PathObject> ensureAnnotationsReadyForAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            AffineTransform fullResToStage) {

        logger.info("Preparing annotations for acquisition...");

        // Get current valid annotations
        List<PathObject> currentAnnotations = getCurrentValidAnnotations();

        if (currentAnnotations.isEmpty()) {
            logger.warn("No valid annotations found for acquisition");
            return currentAnnotations;
        }

        // Ensure all have names
        ensureAnnotationNames(currentAnnotations);

        // Delete ALL existing tiles to avoid duplicates
        deleteAllTiles(gui, sample.modality());

        // CRITICAL: Clean up stale folders from previous tile creation
        cleanupStaleFolders(tempTileDirectory, currentAnnotations);

        // Get settings
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        // Create fresh tiles for all current annotations
        createTilesForAnnotations(currentAnnotations, sample, tempTileDirectory,
                modeWithIndex, macroPixelSize, invertedX, invertedY);

        // CRITICAL: Transform the TileConfiguration files to stage coordinates
        try {
            logger.info("Transforming tile configurations in: {}", tempTileDirectory);
            List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                    tempTileDirectory, fullResToStage);
            logger.info("Transformed tile configurations for directories: {}", modifiedDirs);
        } catch (IOException e) {
            logger.error("Failed to transform tile configurations", e);
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to transform tile coordinates to stage coordinates: " + e.getMessage(),
                    "Transform Error"
            ));
            return Collections.emptyList();
        }

        logger.info("Created and transformed tiles for {} annotations", currentAnnotations.size());

        return currentAnnotations;
    }

    /**
     * Clean up folders from previous tile creation that no longer have corresponding annotations
     */
    private static void cleanupStaleFolders(String tempTileDirectory, List<PathObject> currentAnnotations) {
        try {
            File tempDir = new File(tempTileDirectory);
            if (!tempDir.exists() || !tempDir.isDirectory()) {
                return;
            }

            // Get current annotation names
            Set<String> currentAnnotationNames = currentAnnotations.stream()
                    .map(PathObject::getName)
                    .filter(name -> name != null && !name.trim().isEmpty())
                    .collect(Collectors.toSet());

            // Check each subdirectory
            File[] subdirs = tempDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    String dirName = subdir.getName();

                    // Skip "bounds" directory (used by BoundingBoxWorkflow)
                    if ("bounds".equals(dirName)) {
                        continue;
                    }

                    // If this directory doesn't match any current annotation, delete it
                    if (!currentAnnotationNames.contains(dirName)) {
                        logger.info("Removing stale folder from previous tile creation: {}", dirName);
                        deleteDirectoryRecursively(subdir);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error cleaning up stale folders", e);
        }
    }

    /**
     * Recursively delete a directory and all its contents
     */
    private static void deleteDirectoryRecursively(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        if (!dir.delete()) {
            logger.warn("Failed to delete: {}", dir.getAbsolutePath());
        }
    }

    /**
     * Processes annotations for acquisition and stitching in a sequential manner.
     * Each annotation is fully acquired before moving to the next to prevent
     * hardware conflicts from concurrent stage movements.
     *
     * <p>The workflow for each annotation is:
     * <ol>
     *   <li>Launch acquisition via CLI</li>
     *   <li>Monitor progress until completion</li>
     *   <li>Perform stitching (can overlap with next acquisition)</li>
     *   <li>Import results to project</li>
     * </ol>
     *
     * <p>While acquisitions are sequential, stitching operations can run in parallel
     * using the dedicated STITCH_EXECUTOR to maximize throughput.
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @param projectInfo Project details including paths
     * @param annotations List of annotations to acquire
     * @param macroPixelSize Pixel size of the macro image in micrometers
     * @param transform Affine transform from full-res to stage coordinates
     * @param angleExposures List of rotation angles for multi-angle acquisition
     * @param rotationManager Manager for rotation-specific settings
     * @return CompletableFuture that completes when all acquisitions and stitching are done
     */
    private static CompletableFuture<Void> processAnnotationsForAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            double macroPixelSize,
            AffineTransform transform,
            List<RotationManager.TickExposure> angleExposures,
            RotationManager rotationManager) {

        String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();
        String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFile);
        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");

        // Get current annotations right before acquisition (one more time to be safe)
        List<PathObject> currentAnnotations = getCurrentValidAnnotations();

        if (currentAnnotations.isEmpty()) {
            logger.warn("No valid annotations available for acquisition");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("No Annotations");
                alert.setHeaderText("No annotations available for acquisition");
                alert.setContentText("No annotations with valid classes found. Please create annotations with one of these classes:\n" +
                        String.join(", ", VALID_ANNOTATION_CLASSES));
                alert.showAndWait();
            });
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Found {} valid annotations for acquisition", currentAnnotations.size());

        // Track all stitching futures separately
        List<CompletableFuture<Void>> stitchingFutures = new ArrayList<>();

        // Process each annotation sequentially
        CompletableFuture<Object> acquisitionChain = CompletableFuture.completedFuture(null);

        for (int i = 0; i < currentAnnotations.size(); i++) {
            final PathObject annotation = currentAnnotations.get(i);
            final int annotationIndex = i + 1;
            final int totalAnnotations = currentAnnotations.size();

            acquisitionChain = acquisitionChain.thenCompose(v -> {
                logger.info("Processing annotation {} of {}: {}",
                        annotationIndex, totalAnnotations, annotation.getName());

                // Update progress on UI
                Platform.runLater(() -> {
                    String message = String.format("Acquiring annotation %d of %d: %s",
                            annotationIndex, totalAnnotations, annotation.getName());
                    Dialogs.showInfoNotification("Acquisition Progress", message);
                });

                // Perform acquisition (blocking) with angle exposures
                return performAnnotationAcquisition(
                        annotation, sample, projectsFolder, modeWithIndex,
                        configFile, angleExposures
                ).thenCompose(acquisitionResult -> {
                    if (!acquisitionResult) {
                        logger.error("Acquisition failed for annotation: {}", annotation.getName());
                        return CompletableFuture.completedFuture(null);
                    }

                    // Extract just angles for stitching
                    List<Double> rotationAngles = angleExposures.stream()
                            .map(ae -> ae.ticks)
                            .collect(Collectors.toList());

                    // Launch stitching asynchronously (non-blocking)
                    CompletableFuture<Void> stitchFuture = performAnnotationStitching(
                            annotation, sample, projectsFolder, modeWithIndex,
                            rotationAngles, rotationManager, pixelSize, gui, project
                    );
                    stitchingFutures.add(stitchFuture);

                    // Return immediately to continue with next acquisition
                    return CompletableFuture.completedFuture(null);
                });
            }).exceptionally(ex -> {
                logger.error("Error processing annotation: " + annotation.getName(), ex);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Error processing annotation " + annotation.getName() + ": " + ex.getMessage(),
                        "Acquisition Error"
                ));
                return null;
            });
        }

        // After all acquisitions are done, wait for all stitching to complete
        return acquisitionChain.thenCompose(v -> {
            logger.info("All acquisitions completed. Waiting for {} stitching operations to complete...",
                    stitchingFutures.size());

            return CompletableFuture.allOf(stitchingFutures.toArray(new CompletableFuture[0]));
        }).thenRun(() -> {
            logger.info("All acquisitions and stitching completed");

            // Handle tile cleanup
            String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
            String handling = QPPreferenceDialog.getTileHandlingMethodProperty();

            Platform.runLater(() -> {
                if ("Delete".equals(handling)) {
                    UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                    logger.info("Deleted temporary tiles");
                } else if ("Zip".equals(handling)) {
                    UtilityFunctions.zipTilesAndMove(tempTileDir);
                    UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                    logger.info("Zipped and archived temporary tiles");
                }

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Workflow Complete");
                alert.setHeaderText("Acquisition workflow completed successfully");
                alert.setContentText("All annotations have been acquired and stitched.");
                alert.showAndWait();
            });
        });
    }
    /**
     * Performs acquisition for a single annotation.
     * This is a blocking operation that waits for the CLI to complete.
     *
     * @return CompletableFuture with true if successful, false otherwise
     */
    private static CompletableFuture<Boolean> performAnnotationAcquisition(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            String configFile,
            List<RotationManager.TickExposure> tickExposures) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> cliArgs = new ArrayList<>(Arrays.asList(
                        res.getString("command.acquisitionWorkflow"),
                        configFile,
                        projectsFolder,
                        sample.sampleName(),
                        modeWithIndex,
                        annotation.getName()
                ));

                // Add rotation angles with exposures if present
                if (tickExposures != null && !tickExposures.isEmpty()) {
                    String anglesStr = tickExposures.stream()
                            .map(ae -> ae.ticks + "," + ae.exposureMs)
                            .collect(Collectors.joining(" ", "(", ")"));
                    cliArgs.add(anglesStr);
                }

                logger.info("Starting acquisition with args: {}", cliArgs);

                int timeoutSeconds = 60;
                if (tickExposures != null && !tickExposures.isEmpty()) {
                    timeoutSeconds = 60 * tickExposures.size();
                }

                CliExecutor.ExecResult result = CliExecutor.execComplexCommand(
                        timeoutSeconds,
                        res.getString("acquisition.cli.progressRegex"),
                        cliArgs.toArray(new String[0])
                );

                if (result.exitCode() != 0) {
                    String errorDetails = String.valueOf(result.stderr());
                    if (errorDetails == null || errorDetails.trim().isEmpty()) {
                        errorDetails = "No error details available from acquisition script";
                    }
                    logger.error("Acquisition failed with exit code {} for annotation '{}'. Error: {}",
                            result.exitCode(), annotation.getName(), errorDetails);

                    String finalErrorDetails = errorDetails;
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Acquisition failed for " + annotation.getName() + ":\n\n" + finalErrorDetails,
                            "Acquisition Error"
                    ));
                    return false;
                }

                logger.info("Acquisition completed successfully for {}", annotation.getName());
                return true;

            } catch (Exception e) {
                logger.error("Acquisition failed for {}", annotation.getName(), e);
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = "Unknown error during acquisition";
                }

                final String finalErrorMessage = errorMessage;
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "Acquisition failed for " + annotation.getName() + ":\n\n" + finalErrorMessage,
                                "Acquisition Error"
                        )
                );
                return false;
            }
        });
    }
    /**
     * Performs stitching for a single annotation.
     * This runs asynchronously on the STITCH_EXECUTOR, which is single-threaded
     * to ensure only one stitching operation runs at a time (memory constraint).
     *
     * @return CompletableFuture that completes when stitching is done
     */
    private static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            List<Double> rotationAngles,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        if (rotationAngles != null && !rotationAngles.isEmpty()) {
            // Stitch each angle SEQUENTIALLY (not in parallel)
            CompletableFuture<Void> stitchChain = CompletableFuture.completedFuture(null);

            for (Double angle : rotationAngles) {
                stitchChain = stitchChain.thenCompose(v ->
                        stitchAnnotationAngle(
                                annotation, angle, sample, projectsFolder,
                                modeWithIndex, rotationManager, pixelSize, gui, project
                        )
                );
            }

            return stitchChain;
        } else {
            // Single stitch
            return stitchAnnotation(
                    annotation, null, sample, projectsFolder,
                    modeWithIndex, null, pixelSize, gui, project
            );
        }
    }

    /**
     * Stitch a single annotation/angle combination
     */
    private static CompletableFuture<Void> stitchAnnotation(
            PathObject annotation,
            Double angle,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        return CompletableFuture.runAsync(() -> {
            try {
                String annotationName = annotation.getName();
                String modalityName = modeWithIndex;

                if (angle != null && rotationManager != null) {
                    modalityName += rotationManager.getAngleSuffix(sample.modality(), angle);
                }

                logger.info("Stitching {} for modality {}", annotationName, modalityName);

                // The key fix: For ExistingImageWorkflow without rotation, the tiles are directly
                // in the annotation folder, so we need to pass the parent folder (modeWithIndex)
                // and use the annotation name as the matching string
                String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                        projectsFolder,
                        sample.sampleName(),
                        modalityName,
                        annotationName,
                        annotationName,  // Use annotation name as matching string
                        gui,
                        project,
                        String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                        pixelSize,
                        1  // downsample
                );

                logger.info("Stitching completed: {}", outPath);

            } catch (Exception e) {
                logger.error("Stitching failed for {}", annotation.getName(), e);
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "Stitching failed for " + annotation.getName() + ": " + e.getMessage(),
                                "Stitching Error"
                        )
                );
            }
        }, STITCH_EXECUTOR);
    }

    /**
     * Convenience method for stitching with angle
     */
    private static CompletableFuture<Void> stitchAnnotationAngle(
            PathObject annotation,
            Double angle,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        return stitchAnnotation(
                annotation, angle, sample, projectsFolder,
                modeWithIndex, rotationManager, pixelSize, gui, project
        );
    }

    // Helper classes for context passing

    private static class WorkflowContext {
        final SampleSetupController.SampleSetupResult sample;
        final AlignmentSelectionController.AlignmentChoice alignment;

        WorkflowContext(SampleSetupController.SampleSetupResult sample,
                        AlignmentSelectionController.AlignmentChoice alignment) {
            this.sample = sample;
            this.alignment = alignment;
        }
    }

    private static class AlignmentContext {
        final WorkflowContext context;
        final double pixelSize;
        final GreenBoxDetector.DetectionResult greenBoxResult;
        final int macroWidth;
        final int macroHeight;
        final int cropOffsetX;
        final int cropOffsetY;

        AlignmentContext(WorkflowContext context, double pixelSize,
                         GreenBoxDetector.DetectionResult greenBoxResult,
                         int macroWidth, int macroHeight,
                         int cropOffsetX, int cropOffsetY) {
            this.context = context;
            this.pixelSize = pixelSize;
            this.greenBoxResult = greenBoxResult;
            this.macroWidth = macroWidth;
            this.macroHeight = macroHeight;
            this.cropOffsetX = cropOffsetX;
            this.cropOffsetY = cropOffsetY;
        }
    }

    private static class ManualAlignmentContext {
        final WorkflowContext context;
        final double pixelSize;
        final ProjectInfo projectInfo;

        ManualAlignmentContext(WorkflowContext context, double pixelSize, ProjectInfo projectInfo) {
            this.context = context;
            this.pixelSize = pixelSize;
            this.projectInfo = projectInfo;
        }
    }

    private static class ProjectInfo {
        final Map<String, Object> details;

        ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }
    }
}