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
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import qupath.fx.dialogs.Dialogs;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow - Handles acquisition workflows for images already open in QuPath.
 * <p>
 * Supports two paths:
 * - Path A: Use existing saved alignment (slide-specific or general transform)
 * - Path B: Create new alignment through manual annotation workflow
 *
 * @author Mike Nelson
 * @version 3.0 - Updated to use socket-based communication
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
     * 2. Ensure microscope server connection
     * 3. Sample setup dialog
     * 4. Check for existing slide-specific alignment
     * 5. Alignment selection or skip if using existing
     * 6. Path-specific dialogs and project creation
     * 7. Acquisition and stitching
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

        // Ensure connection to microscope server
        try {
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for stage control");
                MicroscopeController.getInstance().connect();
            }
        } catch (IOException e) {
            logger.error("Failed to connect to microscope server: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Cannot connect to microscope server.\nPlease check server is running and try again.",
                    "Connection Error"
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
        AffineTransform slideTransform = null;

        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            // Project is already open
            slideTransform = AffineTransformManager.loadSlideAlignment(project, sample.sampleName());
        } else {
            // No project yet, check if one would exist
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(projectDir, sample.sampleName());
            }
        }

        if (slideTransform != null) {
            AffineTransform finalSlideTransform = slideTransform;
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment Found");
                alert.setHeaderText("Found existing alignment for slide '" + sample.sampleName() + "'");
                alert.setContentText("Would you like to use the existing slide alignment?\n\n" +
                        "Choose 'Yes' to proceed directly to acquisition.\n" +
                        "Choose 'No' to select a different alignment or create a new one.");

                ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
                ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
                alert.getButtonTypes().setAll(yesButton, noButton);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == yesButton) {
                    logger.info("User chose to use existing slide alignment");
                    future.complete(finalSlideTransform);
                } else {
                    logger.info("User chose to select different alignment");
                    future.complete(null);
                }
            });
        } else {
            logger.info("No slide-specific alignment found for sample: {}", sample.sampleName());
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
            // Extract the scanner name from the transform mounting method
            String scannerName = context.alignment.selectedTransform().getMountingMethod();
            logger.info("Using scanner '{}' from transform mounting method", scannerName);

            // Try to find the scanner config file
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            File configDir = new File(configPath).getParentFile();
            File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

            if (scannerConfigFile.exists()) {
                // Load the scanner configuration directly to avoid singleton caching
                Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
                Double macroPixelSizeValue = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixelSize_um");

                if (macroPixelSizeValue != null && macroPixelSizeValue > 0) {
                    pixelSize = macroPixelSizeValue;
                    logger.info("Loaded macro pixel size {} µm from scanner config: {}",
                            pixelSize, scannerConfigFile.getName());
                } else {
                    throw new IllegalStateException(
                            "Scanner '" + scannerName + "' has no valid macro pixel size configured. " +
                                    "Please add 'macro: pixelSize_um:' to the scanner configuration."
                    );
                }
            } else {
                // Fallback to preferences if no scanner config found
                logger.warn("Scanner config file not found: {}. Using default from preferences.",
                        scannerConfigFile.getAbsolutePath());
                pixelSize = MacroImageUtility.getMacroPixelSize();
            }
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
        MacroImageUtility.CroppedMacroResult croppedResult;

        // Extract scanner name from transform
        String scannerName = context.alignment.selectedTransform().getMountingMethod();
        logger.info("Processing macro image for scanner: {}", scannerName);

        // Try to load scanner-specific configuration
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        File configDir = new File(configPath).getParentFile();
        File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

        if (scannerConfigFile.exists()) {
            // Load the scanner configuration directly
            Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());

            // Check if scanner requires cropping
            Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requiresCropping");

            if (requiresCropping != null && requiresCropping) {
                // Get slide bounds from scanner config
                Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMin");
                Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMax");
                Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMin");
                Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMax");

                if (xMin != null && xMax != null && yMin != null && yMax != null) {
                    croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage, xMin, xMax, yMin, yMax);
                    logger.info("Cropped macro image using bounds from {}: X[{}-{}], Y[{}-{}]",
                            scannerName, xMin, xMax, yMin, yMax);
                } else {
                    logger.warn("Scanner '{}' requires cropping but bounds are not properly configured", scannerName);
                    croppedResult = new MacroImageUtility.CroppedMacroResult(
                            originalMacroImage, originalMacroImage.getWidth(), originalMacroImage.getHeight(), 0, 0);
                }
            } else {
                // No cropping needed
                croppedResult = new MacroImageUtility.CroppedMacroResult(
                        originalMacroImage, originalMacroImage.getWidth(), originalMacroImage.getHeight(), 0, 0);
                logger.info("Scanner '{}' does not require cropping", scannerName);
            }
        } else {
            // Fallback to default cropping
            logger.warn("Scanner config file not found: {}. Using default cropping.", scannerConfigFile.getAbsolutePath());
            croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage);
        }

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
            // For manual alignment, try to get from preferences first
            String savedScanner = PersistentPreferences.getSelectedScanner();
            if (savedScanner != null && !savedScanner.isEmpty()) {
                // Try to load scanner-specific config
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                File configDir = new File(configPath).getParentFile();
                File scannerConfigFile = new File(configDir, "config_" + savedScanner + ".yml");

                if (scannerConfigFile.exists()) {
                    Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
                    Double macroPixelSizeValue = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixelSize_um");

                    if (macroPixelSizeValue != null && macroPixelSizeValue > 0) {
                        pixelSize = macroPixelSizeValue;
                        logger.info("Using macro pixel size {} µm from scanner '{}' config",
                                pixelSize, savedScanner);
                    } else {
                        pixelSize = MacroImageUtility.getMacroPixelSize();
                    }
                } else {
                    pixelSize = MacroImageUtility.getMacroPixelSize();
                }
            } else {
                pixelSize = MacroImageUtility.getMacroPixelSize();
            }
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

                    // Check if we need to handle flips for the current image
                    var imageData = gui.getImageData();
                    if (imageData != null && (flippedX || flippedY)) {
                        // First check if this image is already properly in the project
                        ProjectImageEntry<BufferedImage> currentEntry = null;

                        try {
                            currentEntry = gui.getProject().getEntry(imageData);
                        } catch (Exception e) {
                            logger.debug("Could not get project entry for current image: {}", e.getMessage());
                        }

                        // Only add the image if it's not already in the project
                        if (currentEntry == null) {
                            logger.info("Current image not in project, adding with flips");
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
                        } else {
                            logger.info("Current image already exists in project, no need to re-add");
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
                        if (refinedTransform == null) {
                            // User requested new alignment from scratch
                            logger.info("User requested new alignment - switching to manual workflow");

                            // Create manual alignment context
                            ManualAlignmentContext manualContext = new ManualAlignmentContext(
                                    alignContext.context,
                                    alignContext.pixelSize,
                                    projectInfo
                            );

                            // Switch to manual alignment workflow
                            return processManualAlignment(gui, manualContext);
                        } else {
                            // Continue with the refined (or initial) transform
                            return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                                    annotations, alignContext.pixelSize, refinedTransform);
                        }
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
     * Delete all tiles for a given modality
     */
    private static void deleteAllTiles(QuPathGUI gui, String modality) {
        var hierarchy = gui.getViewer().getHierarchy();
        int totalDetections = hierarchy.getDetectionObjects().size();

        String modalityBase = modality.replaceAll("(_\\d+)$", "");

        List<PathObject> tilesToRemove = hierarchy.getDetectionObjects().stream()
                .filter(o -> o.getPathClass() != null &&
                        o.getPathClass().toString().contains(modalityBase))
                .collect(Collectors.toList());

        if (!tilesToRemove.isEmpty()) {
            logger.info("Removing {} of {} total detections for modality: {}",
                    tilesToRemove.size(), totalDetections, modalityBase);

            // If we're removing most detections, it's faster to clear all and re-add the few we want to keep
            if (tilesToRemove.size() > totalDetections * 0.8) {
                List<PathObject> toKeep = hierarchy.getDetectionObjects().stream()
                        .filter(o -> !tilesToRemove.contains(o))
                        .collect(Collectors.toList());

                QP.removeDetections();
                if (!toKeep.isEmpty()) {
                    hierarchy.addObjects(toKeep);
                }
                logger.info("Cleared all detections and re-added {} objects", toKeep.size());
            } else {
                // Use batch removal for better performance
                hierarchy.removeObjects(tilesToRemove, true);
            }
        }
    }


    /**
     * Performs single-tile refinement of the alignment transform.
     *
     * This method allows the user to fine-tune the alignment by:
     * 1. Selecting a tile in QuPath
     * 2. Moving the microscope to the estimated position based on the initial transform
     * 3. Manually adjusting the stage position to match the tile
     * 4. Updating the transform based on the refined position
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
                        "The microscope will move to the estimated position for this tile.\n" +
                        "You will then manually adjust the stage position to match."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                // User cancelled tile selection
                future.complete(initialTransform);
                return;
            }

            Platform.runLater(() -> {
                try {
                    // Get tile coordinates in full-res pixels
                    double[] tileCoords = {
                            selectedTile.getROI().getCentroidX(),
                            selectedTile.getROI().getCentroidY()
                    };

                    logger.info("Selected tile '{}' at full-res coordinates: ({}, {})",
                            selectedTile.getName(), tileCoords[0], tileCoords[1]);

                    // Transform to estimated stage position using initial transform
                    double[] estimatedStageCoords = TransformationFunctions.transformQuPathFullResToStage(
                            tileCoords, initialTransform
                    );

                    logger.info("Estimated stage position for tile: ({}, {})",
                            estimatedStageCoords[0], estimatedStageCoords[1]);

                    // Get stage bounds from config
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                            QPPreferenceDialog.getMicroscopeConfigFileProperty());

                    double stageXMin = mgr.getDouble("stage", "xlimit", "low");
                    double stageXMax = mgr.getDouble("stage", "xlimit", "high");
                    double stageYMin = mgr.getDouble("stage", "ylimit", "low");
                    double stageYMax = mgr.getDouble("stage", "ylimit", "high");

                    // Verify stage bounds
                    if (estimatedStageCoords[0] < stageXMin || estimatedStageCoords[0] > stageXMax ||
                            estimatedStageCoords[1] < stageYMin || estimatedStageCoords[1] > stageYMax) {

                        UIFunctions.notifyUserOfError(
                                String.format("Selected tile is outside stage bounds!\n" +
                                                "Estimated position: (%.1f, %.1f)\n" +
                                                "Stage limits: X: %.0f to %.0f, Y: %.0f to %.0f\n\n" +
                                                "Please select a different tile or create a new alignment.",
                                        estimatedStageCoords[0], estimatedStageCoords[1],
                                        stageXMin, stageXMax, stageYMin, stageYMax),
                                "Stage Bounds Error"
                        );
                        future.complete(initialTransform);
                        return;
                    }

                    // Center the QuPath viewer on the selected tile
                    var viewer = gui.getViewer();
                    if (viewer != null && selectedTile.getROI() != null) {
                        double cx = selectedTile.getROI().getCentroidX();
                        double cy = selectedTile.getROI().getCentroidY();
                        viewer.setCenterPixelLocation(cx, cy);

                        // Select the tile to highlight it
                        viewer.getHierarchy().getSelectionModel().setSelectedObject(selectedTile);
                    }

                    // Move to estimated position
                    logger.info("Moving to estimated position for tile '{}'", selectedTile.getName());
                    MicroscopeController.getInstance().moveStageXY(
                            estimatedStageCoords[0], estimatedStageCoords[1]);

                    // Wait a moment for stage to settle
                    Thread.sleep(500);

                    // Show refinement dialog
                    Alert refinementDialog = new Alert(Alert.AlertType.CONFIRMATION);
                    refinementDialog.setTitle("Refine Alignment");
                    refinementDialog.setHeaderText("Alignment Refinement");
                    refinementDialog.setContentText(
                            "The microscope has moved to the estimated position for the selected tile.\n\n" +
                                    "Please use the microscope controls to adjust the stage position so that\n" +
                                    "the live view matches the selected tile in QuPath.\n\n" +
                                    "When the alignment is correct, click 'Save Refined Position'."
                    );

                    ButtonType saveButton = new ButtonType("Save Refined Position");
                    ButtonType skipButton = new ButtonType("Skip Refinement");
                    ButtonType newAlignmentButton = new ButtonType("Create New Alignment");

                    refinementDialog.getButtonTypes().setAll(saveButton, skipButton, newAlignmentButton);

                    Optional<ButtonType> result = refinementDialog.showAndWait();

                    if (result.isPresent()) {
                        if (result.get() == saveButton) {
                            // Get the refined stage position
                            double[] refinedStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                            logger.info("Refined stage position: ({}, {})",
                                    refinedStageCoords[0], refinedStageCoords[1]);

                            // Calculate the refined transform
                            AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                                    initialTransform, tileCoords, refinedStageCoords
                            );

                            logger.info("Calculated refined transform based on position adjustment");

                            // Save the slide-specific alignment
                            Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
                            AffineTransformManager.saveSlideAlignment(
                                    project,
                                    alignContext.context.sample.sampleName(),
                                    alignContext.context.sample.modality(),
                                    refinedTransform
                            );

                            logger.info("Saved refined slide-specific transform");

                            // Notify user of success
                            Dialogs.showInfoNotification(
                                    "Alignment Refined",
                                    "The alignment has been refined and saved for this slide."
                            );

                            future.complete(refinedTransform);

                        } else if (result.get() == skipButton) {
                            // Use initial transform without refinement
                            logger.info("User skipped refinement - using initial transform");

                            // Still save the initial transform as slide-specific
                            Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
                            AffineTransformManager.saveSlideAlignment(
                                    project,
                                    alignContext.context.sample.sampleName(),
                                    alignContext.context.sample.modality(),
                                    initialTransform
                            );

                            future.complete(initialTransform);

                        } else if (result.get() == newAlignmentButton) {
                            // User wants to create a new alignment from scratch
                            logger.info("User requested new alignment - switching to manual alignment workflow");

                            // Complete with null to signal that manual alignment should be started
                            future.complete(null);
                        }
                    } else {
                        // Dialog was closed - treat as skip
                        logger.info("Refinement dialog closed - using initial transform");
                        future.complete(initialTransform);
                    }

                } catch (Exception e) {
                    logger.error("Error during refinement", e);
                    UIFunctions.notifyUserOfError(
                            "Error during refinement: " + e.getMessage() + "\n\n" +
                                    "The initial alignment will be used.",
                            "Refinement Error"
                    );
                    future.complete(initialTransform);
                }
            });
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

                        // Show overall progress dialog
                        Platform.runLater(() -> {
                            Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
                            progressAlert.setTitle("Acquisition Progress");
                            progressAlert.setHeaderText("Starting acquisition workflow");
                            progressAlert.setContentText(String.format(
                                    "Processing %d annotations with %d rotation angles each.\n" +
                                            "This may take several minutes per annotation.",
                                    currentAnnotations.size(),
                                    angleExposures != null ? angleExposures.size() : 1
                            ));
                            progressAlert.show();

                            // Auto-close after 3 seconds
                            PauseTransition pause = new PauseTransition(Duration.seconds(3));
                            pause.setOnFinished(e -> progressAlert.close());
                            pause.play();
                        });

                        // Process annotations
                        return processAnnotationsForAcquisition(
                                gui, sample, projectInfo, currentAnnotations,
                                macroPixelSize, transform, angleExposures, rotationManager
                        );
                    });
        });
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

        // Delete ALL existing tiles ONCE in a batch operation
        // This is the operation that was taking 30 seconds
        long startTime = System.currentTimeMillis();
        deleteAllTiles(gui, sample.modality());
        long deleteTime = System.currentTimeMillis() - startTime;

        if (deleteTime > 1000) {
            logger.warn("Tile deletion took {} ms - consider using batch operations", deleteTime);
        }

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
     *   <li>Launch acquisition via socket</li>
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
        CompletableFuture<Boolean> acquisitionChain = CompletableFuture.completedFuture(true);

        for (int i = 0; i < currentAnnotations.size(); i++) {
            final PathObject annotation = currentAnnotations.get(i);
            final int annotationIndex = i + 1;
            final int totalAnnotations = currentAnnotations.size();

            acquisitionChain = acquisitionChain.thenCompose(previousResult -> {
                // Check if previous acquisition was cancelled or failed
                if (!previousResult) {
                    logger.info("Stopping workflow due to cancelled or failed acquisition");
                    return CompletableFuture.completedFuture(false);
                }

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
                        return CompletableFuture.completedFuture(acquisitionResult);
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
                    return CompletableFuture.completedFuture(acquisitionResult);
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
        return acquisitionChain.thenCompose(finalResult -> {
            // Check if acquisitions were successful
            if (finalResult == null || !finalResult) {
                logger.info("Acquisitions were cancelled or failed, skipping final steps");
                return CompletableFuture.completedFuture(null);
            }

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
     * Performs acquisition for a single annotation using socket-based communication with the microscope server.
     *
     * <p>This method executes asynchronously and manages the complete acquisition lifecycle including:
     * <ul>
     *   <li>Building and sending the acquisition command to the microscope server</li>
     *   <li>Calculating expected file counts based on tiles and rotation angles</li>
     *   <li>Displaying a progress bar with cancellation support</li>
     *   <li>Monitoring acquisition state until completion or failure</li>
     *   <li>Handling errors and user notifications</li>
     * </ul>
     *
     * <p>The acquisition is performed as a blocking operation on the server side, meaning the server
     * will control the microscope hardware sequentially to capture all requested tiles and angles.
     * This method monitors the server's progress and updates the UI accordingly.
     *
     * <p><b>Progress Monitoring:</b> The expected number of files is calculated as
     * {@code tilesPerAnnotation × numberOfAngles}. The progress bar updates as the server
     * reports completion of each tile acquisition.
     *
     * <p><b>Cancellation:</b> Users can cancel the acquisition via the progress bar's cancel button.
     * This sends a cancellation request to the server, which will stop the acquisition gracefully
     * after completing the current tile.
     *
     * @param annotation The PathObject representing the tissue region to acquire. Must have a valid
     *                   name and ROI. The annotation's name is used as the region identifier for
     *                   the microscope server and file organization.
     * @param sample The sample setup information containing the sample name and output folder paths.
     *               This determines where acquired tiles will be saved.
     * @param projectsFolder The root directory path where all project data is stored. Tiles will be
     *                       saved in subdirectories under this path.
     * @param modeWithIndex The imaging modality with index suffix (e.g., "BF_10x_1", "PPM_10x_2").
     *                      This determines the acquisition parameters and output directory structure.
     * @param configFile The full path to the YAML configuration file containing microscope hardware
     *                   settings, stage limits, and imaging parameters.
     * @param tickExposures List of rotation angles (in ticks) paired with exposure times (in ms).
     *                      For brightfield, typically contains one entry (90.0 degrees).
     *                      For PPM, may contain multiple angles (e.g., -5, 0, 5, 90 degrees).
     *                      Can be null or empty for non-rotating acquisitions.
     *
     * @return A CompletableFuture that completes with:
     *         <ul>
     *           <li>{@code true} if the acquisition completed successfully</li>
     *           <li>{@code false} if the acquisition was cancelled by the user or ended in an
     *               unexpected state</li>
     *           <li>Exceptionally with a RuntimeException if the acquisition failed on the server</li>
     *         </ul>
     *
     * @throws RuntimeException wrapped in the CompletableFuture if:
     *         <ul>
     *           <li>Socket communication fails</li>
     *           <li>The server reports acquisition failure</li>
     *           <li>Timeout occurs (5 minutes per annotation)</li>
     *         </ul>
     *
     * @see MicroscopeSocketClient#monitorAcquisition
     * @see AcquisitionCommandBuilder
     * @see UIFunctions.ProgressHandle
     * @since 2.0
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
                logger.info("Starting socket-based acquisition for annotation: {}", annotation.getName());

                // Build acquisition command with all necessary parameters
                AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                        .yamlPath(configFile)
                        .projectsFolder(projectsFolder)
                        .sampleLabel(sample.sampleName())
                        .scanType(modeWithIndex)
                        .regionName(annotation.getName())
                        .angleExposures(tickExposures);

                logger.info("Starting acquisition with parameters:");
                logger.info("  Config: {}", configFile);
                logger.info("  Projects folder: {}", projectsFolder);
                logger.info("  Sample: {}", sample.sampleName());
                logger.info("  Mode: {}", modeWithIndex);
                logger.info("  Annotation: {}", annotation.getName());
                logger.info("  Angle-Exposure pairs: {}", tickExposures);

                // Start acquisition via socket
                MicroscopeController.getInstance().startAcquisition(acquisitionBuilder);

                logger.info("Socket acquisition command sent successfully");

                // Get the socket client for monitoring
                MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

                // Calculate expected files for this annotation
                // FIXED: Use the actual output directory, not temp/tiles
                String tileDirPath = Paths.get(projectsFolder, sample.sampleName(), modeWithIndex, annotation.getName()).toString();
                int tilesPerAngle = MinorFunctions.countTifEntriesInTileConfig(List.of(tileDirPath));
                int expectedFiles = tilesPerAngle;
                if (tickExposures != null && !tickExposures.isEmpty()) {
                    expectedFiles *= tickExposures.size();
                }

                logger.info("Expected files: {} ({}x{} angles)", expectedFiles, tilesPerAngle,
                        tickExposures != null ? tickExposures.size() : 1);

                // Create progress counter
                AtomicInteger progressCounter = new AtomicInteger(0);

                // Show progress bar with cancel button
                UIFunctions.ProgressHandle progressHandle = null;
                if (expectedFiles > 0) {
                    progressHandle = UIFunctions.showProgressBarAsync(
                            progressCounter,
                            expectedFiles,
                            300000, // 5 minute timeout per annotation
                            true    // Show cancel button
                    );

                    // Set up cancel callback
                    final UIFunctions.ProgressHandle finalHandle = progressHandle;
                    progressHandle.setCancelCallback(v -> {
                        logger.info("User requested acquisition cancellation");
                        try {
                            if (socketClient.cancelAcquisition()) {
                                logger.info("Cancellation request sent to server");
                            }
                        } catch (IOException e) {
                            logger.error("Failed to send cancel command", e);
                        }
                    });
                }


                // Monitor acquisition with progress updates
                MicroscopeSocketClient.AcquisitionState finalState =
                        socketClient.monitorAcquisition(
                                progress -> {
                                    logger.debug("Acquisition progress for {}: {}", annotation.getName(), progress);
                                    progressCounter.set(progress.current);
                                },
                                500,    // Poll every 500ms
                                300000  // 5 minute timeout
                        );

                // Close progress handle
                if (progressHandle != null) {
                    progressHandle.close();
                }

                // Check final state
                if (finalState == MicroscopeSocketClient.AcquisitionState.COMPLETED) {
                    logger.info("Socket acquisition completed successfully for {}", annotation.getName());
                    return true;
                } else if (finalState == MicroscopeSocketClient.AcquisitionState.CANCELLED) {
                    logger.warn("Socket acquisition was cancelled for {}", annotation.getName());
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError(
                                    "Acquisition was cancelled by user request",
                                    "Acquisition Cancelled"
                            )
                    );
                    return false;
                } else if (finalState == MicroscopeSocketClient.AcquisitionState.FAILED) {
                    throw new RuntimeException("Socket acquisition failed on server for " + annotation.getName());
                } else {
                    logger.warn("Acquisition ended in unexpected state: {} for {}", finalState, annotation.getName());
                    return false;
                }

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
     * Orchestrates the stitching process for a single annotation across all rotation angles.
     *
     * <p>This method manages the sequential stitching of tile sets acquired at different rotation
     * angles. For multi-angle acquisitions (e.g., PPM), each angle's tiles are stitched into a
     * separate OME-TIFF file. The stitching operations run sequentially to avoid memory issues.
     *
     * <p><b>Execution Model:</b> All stitching operations run on the single-threaded
     * {@code STITCH_EXECUTOR} to ensure only one stitching process runs at a time. This prevents
     * memory exhaustion when processing large tile sets.
     *
     * <p><b>Output Naming Convention:</b>
     * <ul>
     *   <li>Single angle: {@code sampleName_modality_annotationName.ome.tif}</li>
     *   <li>Multiple angles: {@code sampleName_modality_suffix_annotationName.ome.tif}
     *       where suffix indicates the angle (e.g., "_m5" for -5°, "_p5" for +5°)</li>
     * </ul>
     *
     * @param annotation The PathObject that was acquired. Its name is used in the output filename
     *                   and to locate the tile directory.
     * @param sample Sample information including the sample name used in output filenames.
     * @param projectsFolder Root directory containing all project data and where stitched images
     *                       will be saved in the "SlideImages" subdirectory.
     * @param modeWithIndex The base imaging modality folder (e.g., "PPM_10x_1") where tiles are
     *                      stored. This is NOT modified with angle suffixes.
     * @param rotationAngles List of rotation angles in degrees. If null or empty, performs a single
     *                       stitch. Otherwise, stitches each angle sequentially.
     * @param rotationManager Manager that provides angle-specific naming suffixes (e.g., "_m5", "_p5").
     *                        Required when rotationAngles is provided.
     * @param pixelSize Physical pixel size in micrometers, used for OME-TIFF metadata.
     * @param gui QuPath GUI instance for updating the project with stitched images.
     * @param project The QuPath project where stitched images will be imported.
     *
     * @return A CompletableFuture that completes when all stitching operations finish.
     *         The future completes normally even if individual stitching operations fail
     *         (errors are logged and shown to the user via UI notifications).
     *
     * @see #stitchAnnotation
     * @see UtilityFunctions#stitchImagesAndUpdateProject
     * @since 2.0
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
            // Stitch each angle SEQUENTIALLY
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
     * Performs the actual stitching operation for a single annotation and rotation angle combination.
     *
     * <p>This method handles the core stitching logic, including:
     * <ul>
     *   <li>Determining the correct input directory structure based on angle</li>
     *   <li>Building the appropriate output filename with modality and angle suffixes</li>
     *   <li>Invoking the BasicStitching plugin to create pyramidal OME-TIFF files</li>
     *   <li>Importing the stitched result into the QuPath project</li>
     *   <li>Error handling and user notification</li>
     * </ul>
     *
     * <p><b>Directory Structure:</b> For angle-based acquisitions, tiles are organized as:
     * {@code projectsFolder/sampleName/modeWithIndex/annotationName/angle/}
     * where angle is a string representation of the rotation angle (e.g., "-5.0", "0.0", "90.0").
     *
     * <p><b>Stitching Strategy:</b> Uses the TileConfiguration.txt file in each directory to
     * determine tile positions. The BasicStitching plugin handles the actual image processing,
     * pyramid generation, and OME-TIFF writing.
     *
     * @param annotation The annotation being stitched. Provides the name for directory lookup
     *                   and output file naming.
     * @param angle The rotation angle in degrees for this stitch operation. If null, indicates
     *              a single-angle acquisition. Used to locate the correct subdirectory and
     *              generate angle-specific output filenames.
     * @param sample Sample information for output file naming and directory paths.
     * @param projectsFolder Root directory for all project data.
     * @param modeWithIndex Base modality folder name (e.g., "PPM_10x_1"). This is the actual
     *                      directory where tiles are stored, without angle suffixes.
     * @param rotationManager Provides angle-to-suffix mapping for output filenames. Can be null
     *                        for single-angle acquisitions.
     * @param pixelSize Physical pixel size in micrometers for OME-TIFF metadata.
     * @param gui QuPath GUI for project updates and image importing.
     * @param project Target project for importing stitched images.
     *
     * @return A CompletableFuture that completes when stitching finishes. Completes normally
     *         even if stitching fails (errors are logged and displayed to user).
     *
     * @throws IOException wrapped in the CompletableFuture if:
     *         <ul>
     *           <li>Tile directory doesn't exist</li>
     *           <li>No tiles found matching the criteria</li>
     *           <li>Stitching plugin fails</li>
     *           <li>Output file cannot be written</li>
     *         </ul>
     *
     * @see UtilityFunctions#stitchImagesAndUpdateProject
     * @see RotationManager#getAngleSuffix
     * @since 2.0
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

                // The matching string is the raw angle value (e.g., "0.0", "-5.0", "90.0")
                // This is what the directories are actually named
                String matchingString;
                if (angle != null) {
                    matchingString = String.valueOf(angle);
                } else {
                    matchingString = annotationName;
                }

                logger.info("Stitching {} for modality {} (angle: {})",
                        annotationName, modeWithIndex, angle);

                // Call stitchImagesAndUpdateProject with the ORIGINAL parameters
                // Tiles are in: projectsFolder/sampleName/modeWithIndex/annotationName/angle/
                String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                        projectsFolder,
                        sample.sampleName(),
                        modeWithIndex,         // Original modality (e.g., "PPM_10x_1")
                        annotationName,        // The annotation folder name
                        matchingString,        // The angle value (e.g., "0.0", "-5.0")
                        gui,
                        project,
                        String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                        pixelSize,
                        1  // downsample
                );

                logger.info("Initial stitching output: {}", outPath);

                // Now manually rename the output file to include annotation name and angle suffix
                if (outPath != null) {
                    File stitchedFile = new File(outPath);
                    String desiredName;

                    if (angle != null && rotationManager != null) {
                        // For multi-angle: include annotation name and angle suffix
                        String angleSuffix = rotationManager.getAngleSuffix(sample.modality(), angle);
                        desiredName = sample.sampleName() + "_" + modeWithIndex + "_" +
                                annotationName + angleSuffix + ".ome.tif";
                    } else {
                        // For single angle: just include annotation name
                        desiredName = sample.sampleName() + "_" + modeWithIndex + "_" +
                                annotationName + ".ome.tif";
                    }

                    File renamedFile = new File(stitchedFile.getParent(), desiredName);

                    // Only rename if the file doesn't already have the correct name
                    if (!stitchedFile.getName().equals(desiredName)) {
                        if (stitchedFile.renameTo(renamedFile)) {
                            logger.info("Renamed stitched file from {} to {}",
                                    stitchedFile.getName(), desiredName);
                            outPath = renamedFile.getAbsolutePath();
                        } else {
                            logger.error("Failed to rename {} to {}",
                                    stitchedFile.getName(), desiredName);
                        }
                    }
                }

                logger.info("Final stitching output: {}", outPath);

            } catch (Exception e) {
                logger.error("Stitching failed for {} (angle: {})", annotation.getName(), angle, e);
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                String.format("Stitching failed for %s: %s",
                                        annotation.getName(), e.getMessage()),
                                "Stitching Error"
                        )
                );
            }
        }, STITCH_EXECUTOR);
    }
    /**
     * Convenience wrapper method for stitching a single annotation at a specific rotation angle.
     *
     * <p>This method simply delegates to {@link #stitchAnnotation} with all the same parameters.
     * It exists to provide a clearer method name when called from angle-iteration loops.
     *
     * @param annotation The annotation to stitch
     * @param angle The specific rotation angle for this stitch operation
     * @param sample Sample information
     * @param projectsFolder Project root directory
     * @param modeWithIndex Base modality folder
     * @param rotationManager Rotation angle suffix provider
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project Target QuPath project
     *
     * @return CompletableFuture that completes when stitching finishes
     *
     * @see #stitchAnnotation
     * @since 2.0
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
    /**
     * Internal context container for passing workflow state between dialog stages.
     *
     * <p>This class encapsulates the results from the sample setup and alignment selection
     * dialogs, providing a convenient way to pass this information through the asynchronous
     * workflow pipeline without excessive parameter lists.
     *
     * @since 2.0
     */
    private static class WorkflowContext {
        final SampleSetupController.SampleSetupResult sample;
        final AlignmentSelectionController.AlignmentChoice alignment;

        WorkflowContext(SampleSetupController.SampleSetupResult sample,
                        AlignmentSelectionController.AlignmentChoice alignment) {
            this.sample = sample;
            this.alignment = alignment;
        }
    }

    /**
     * Extended context for workflows using existing alignment with green box detection.
     *
     * <p>This class contains all information needed to process an existing alignment workflow,
     * including the detected green box location, macro image dimensions, and cropping offsets.
     * The green box provides the registration between macro and full-resolution images.
     *
     * @since 2.0
     */
    private static class AlignmentContext {
        /** Base workflow context with sample and alignment choice */
        final WorkflowContext context;

        /** Macro image pixel size in micrometers */
        final double pixelSize;

        /** Green box detection results including ROI and confidence */
        final GreenBoxDetector.DetectionResult greenBoxResult;

        /** Width of the cropped macro image in pixels */
        final int macroWidth;

        /** Height of the cropped macro image in pixels */
        final int macroHeight;

        /** X offset applied during macro image cropping */
        final int cropOffsetX;

        /** Y offset applied during macro image cropping */
        final int cropOffsetY;

        /**
         * Creates a new alignment context with green box detection results.
         *
         * @param context Base workflow context
         * @param pixelSize Macro image pixel size in micrometers
         * @param greenBoxResult Detection results containing the green box ROI
         * @param macroWidth Width of cropped macro image
         * @param macroHeight Height of cropped macro image
         * @param cropOffsetX Horizontal offset from original to cropped macro
         * @param cropOffsetY Vertical offset from original to cropped macro
         */
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


    /**
     * Context for manual alignment workflows where the user creates the transform interactively.
     *
     * <p>This simplified context is used when no existing alignment is available and the user
     * must manually establish correspondence between QuPath tiles and microscope stage positions.
     *
     * @since 2.0
     */
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
    /**
     * Wrapper for project setup information returned by {@link QPProjectFunctions}.
     *
     * <p>This class provides type-safe access to the project setup details that are
     * originally returned as a generic Map. Common keys include:
     * <ul>
     *   <li>"currentQuPathProject" - The Project&lt;BufferedImage&gt; instance</li>
     *   <li>"imagingModeWithIndex" - The modality folder name (e.g., "BF_10x_1")</li>
     *   <li>"tempTileDirectory" - Path for temporary tile storage</li>
     *   <li>"matchingImage" - The ProjectImageEntry if an image was imported</li>
     * </ul>
     *
     * @since 2.0
     */
    private static class ProjectInfo {
        final Map<String, Object> details;

        ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }
    }
}