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

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static qupath.ext.qpsc.utilities.ImageProcessing.detectOcus40DataBounds;

/**
 * ExistingImageWorkflow - Handles acquisition workflows for images already open in QuPath.
 *
 * <p>This workflow enables users to acquire high-resolution microscopy images based on
 * annotations in an existing QuPath image. It supports two primary alignment paths:</p>
 *
 * <ul>
 *   <li><b>Path A - Existing Alignment:</b> Uses a previously saved transform between
 *       macro images and stage coordinates. Requires the image to have an associated
 *       macro image with a detectable green registration box.</li>
 *   <li><b>Path B - Manual Alignment:</b> Allows users to create a new alignment by
 *       manually correlating QuPath tile positions with microscope stage coordinates.</li>
 * </ul>
 *
 * <h3>Workflow Steps:</h3>
 * <ol>
 *   <li>Verify an image is open in QuPath</li>
 *   <li>Connect to microscope server</li>
 *   <li>Sample setup dialog (name, folder, modality)</li>
 *   <li>Check for existing slide-specific alignment</li>
 *   <li>Alignment selection (use existing or create new)</li>
 *   <li>Path-specific processing (green box detection or manual setup)</li>
 *   <li>Project creation/setup</li>
 *   <li>Tile generation and coordinate transformation</li>
 *   <li>Sequential acquisition of each annotation</li>
 *   <li>Parallel stitching of acquired tiles</li>
 *   <li>Import stitched images back to project</li>
 * </ol>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Supports multi-angle acquisition (PPM, brightfield)</li>
 *   <li>Automatic tissue detection via configurable scripts</li>
 *   <li>Progress monitoring with cancellation support</li>
 *   <li>Slide-specific alignment saving and reuse</li>
 *   <li>Robust error handling and user notifications</li>
 * </ul>
 *
 * @author Mike Nelson
 * @version 3.0
 * @since 2.0
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    /**
     * Valid annotation class names that can be used for acquisition.
     * Annotations must have one of these PathClass names to be processed.
     */
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Single-threaded executor for stitching operations.
     * Ensures only one stitching process runs at a time to prevent memory exhaustion.
     * Uses daemon threads so they won't block JVM shutdown.
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the existing image workflow.
     *
     * <p>This method orchestrates the entire workflow from start to finish.
     * It begins by validating prerequisites (open image, server connection)
     * then guides the user through a series of dialogs to configure the acquisition.</p>
     *
     * <p>The workflow is asynchronous and non-blocking, using CompletableFutures
     * to chain operations while keeping the UI responsive.</p>
     *
     * <h3>Workflow Decision Tree:</h3>
     * <pre>
     * Start
     *   ├─ Check image open? → No → Show error
     *   ├─ Connect to server? → Failed → Show error
     *   ├─ Sample setup dialog → Cancelled → Exit
     *   ├─ Check slide alignment exists?
     *   │    ├─ Yes → Use existing?
     *   │    │         ├─ Yes → Fast path (skip to acquisition)
     *   │    │         └─ No → Continue to alignment selection
     *   │    └─ No → Continue to alignment selection
     *   └─ Alignment selection
     *        ├─ Use existing transform → Path A (green box)
     *        └─ Create new → Path B (manual)
     * </pre>
     */
    public static void run() {
        logger.info("************************************");
        logger.info("Starting Existing Image Workflow...");
        logger.info("************************************");

        QuPathGUI gui = QuPathGUI.getInstance();

        // Step 1: Verify we have an open image
        if (gui.getImageData() == null) {
            logger.warn("No image is currently open in QuPath");
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No image is currently open. Please open an image first.",
                            "No Image")
            );
            return;
        }

        logger.info("Current image: {}", gui.getImageData().getServer().getPath());

        // Step 2: Ensure connection to microscope server
        try {
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for stage control");
                MicroscopeController.getInstance().connect();
                logger.info("Successfully connected to microscope server");
            }
        } catch (IOException e) {
            logger.error("Failed to connect to microscope server: {}", e.getMessage(), e);
            UIFunctions.notifyUserOfError(
                    "Cannot connect to microscope server.\nPlease check server is running and try again.",
                    "Connection Error"
            );
            return;
        }

        // Step 3: Sample Setup Dialog
        logger.info("Showing sample setup dialog");
        SampleSetupController.showDialog()
                .thenCompose(sample -> {
                    if (sample == null) {
                        logger.info("Sample setup cancelled by user");
                        return CompletableFuture.completedFuture(null);
                    }

                    logger.info("Sample setup complete - Name: {}, Modality: {}, Folder: {}",
                            sample.sampleName(), sample.modality(), sample.projectsFolder());

                    // Step 4: Check for existing slide-specific alignment
                    return checkForSlideSpecificAlignment(gui, sample)
                            .thenCompose(slideAlignment -> {
                                if (slideAlignment != null) {
                                    logger.info("Using existing slide alignment - fast path");
                                    // Fast path: Use existing slide alignment
                                    return handleExistingSlideAlignment(gui, sample, slideAlignment);
                                } else {
                                    logger.info("No existing slide alignment - continuing with alignment selection");
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
     * Checks if there's an existing slide-specific alignment and asks user if they want to use it.
     *
     * <p>This method looks for a previously saved alignment for the current slide/sample.
     * If found, it presents a dialog asking the user whether to use it or create a new one.</p>
     *
     * @param gui The QuPath GUI instance
     * @param sample Sample setup information including name and folders
     * @return CompletableFuture with the slide transform if user chooses to use it, null otherwise
     */
    private static CompletableFuture<AffineTransform> checkForSlideSpecificAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        logger.info("Checking for slide-specific alignment for sample: {}", sample.sampleName());

        // Try to load slide-specific alignment
        AffineTransform slideTransform = null;

        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            // Project is already open
            logger.debug("Project already open, loading alignment from project");
            slideTransform = AffineTransformManager.loadSlideAlignment(project, sample.sampleName());
        } else {
            // No project yet, check if one would exist
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            logger.debug("No open project, checking directory: {}", projectDir);
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(projectDir, sample.sampleName());
            }
        }

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment, prompting user");
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
     * Fast path handler when using existing slide alignment.
     *
     * <p>This method bypasses the alignment selection dialog and proceeds directly
     * to acquisition setup when a valid slide-specific alignment already exists.</p>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @param slideTransform The existing slide-specific transform
     * @return CompletableFuture that completes when workflow finishes
     */
    private static CompletableFuture<Void> handleExistingSlideAlignment(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            AffineTransform slideTransform) {

        logger.info("Using existing slide-specific alignment - fast path");
        logger.debug("Transform: {}", slideTransform);

        // Set the transform
        MicroscopeController.getInstance().setCurrentTransform(slideTransform);
        logger.info("Set current transform in microscope controller");

        // Setup or get project
        return setupProject(gui, sample)
                .thenCompose(projectInfo -> {
                    if (projectInfo == null) {
                        logger.warn("Project setup failed or was cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Get macro pixel size from preferences
                    String macroPixelSizeStr = PersistentPreferences.getMacroImagePixelSizeInMicrons();
                    double macroPixelSize = 80.0; // default
                    try {
                        macroPixelSize = Double.parseDouble(macroPixelSizeStr);
                        logger.debug("Using macro pixel size from preferences: {} µm", macroPixelSize);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid macro pixel size in preferences '{}', using default: {} µm",
                                macroPixelSizeStr, macroPixelSize);
                    }

                    // Create or validate annotations
                    List<PathObject> annotations = ensureAnnotationsExist(gui, macroPixelSize);
                    if (annotations.isEmpty()) {
                        logger.warn("No valid annotations found, cannot proceed");
                        return CompletableFuture.completedFuture(null);
                    }

                    logger.info("Found {} valid annotations, proceeding to acquisition", annotations.size());

                    // Proceed directly to acquisition
                    return continueToAcquisition(gui, sample, projectInfo,
                            annotations, macroPixelSize, slideTransform);
                });
    }

    /**
     * Normal workflow path with alignment selection dialog.
     *
     * <p>This method shows the alignment selection dialog where users can choose
     * between using an existing general transform or creating a new manual alignment.</p>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @return CompletableFuture that completes when workflow finishes
     */
    private static CompletableFuture<Void> continueWithAlignmentSelection(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        logger.info("Showing alignment selection dialog for modality: {}", sample.modality());

        // Continue with normal alignment selection dialog
        return AlignmentSelectionController.showDialog(gui, sample.modality())
                .thenApply(alignment -> {
                    if (alignment == null) {
                        logger.info("Alignment selection cancelled");
                        return null;
                    }
                    logger.info("Alignment selected - Use existing: {}, Refinement: {}",
                            alignment.useExistingAlignment(), alignment.refinementRequested());
                    return new WorkflowContext(sample, alignment);
                })
                .thenCompose(context -> {
                    if (context == null) {
                        logger.info("Workflow cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Path-specific handling
                    if (context.alignment.useExistingAlignment()) {
                        logger.info("Path A: Using existing alignment");
                        return handleExistingAlignmentPath(gui, context);
                    } else {
                        logger.info("Path B: Manual alignment");
                        return handleManualAlignmentPath(gui, context);
                    }
                });
    }

    /**
     * Handles Path A: Using existing general alignment with green box detection.
     *
     * <p>This path requires:</p>
     * <ul>
     *   <li>A macro image associated with the current image</li>
     *   <li>A detectable green registration box in the macro image</li>
     *   <li>A previously saved general transform for the scanner</li>
     * </ul>
     *
     * <p>The workflow combines the detected green box position with the general
     * macro-to-stage transform to create a full-resolution-to-stage transform.</p>
     *
     * @param gui QuPath GUI instance
     * @param context Workflow context with sample and alignment information
     * @return CompletableFuture that completes when workflow finishes
     */
    private static CompletableFuture<Void> handleExistingAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path A: Using existing alignment with green box detection");

        // Check macro image availability
        if (!MacroImageUtility.isMacroImageAvailable(gui)) {
            logger.error("No macro image available for green box detection");
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
            logger.debug("Looking for scanner config: {}", scannerConfigFile);

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
            logger.error("Configuration error: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    e.getMessage() + "\n\nThe workflow cannot continue without the macro image pixel size.",
                    "Configuration Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        // Get the original macro image
        BufferedImage originalMacroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (originalMacroImage == null) {
            logger.error("Cannot retrieve macro image from current image");
            UIFunctions.notifyUserOfError(
                    "Cannot retrieve macro image",
                    "Macro Image Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        logger.debug("Retrieved macro image: {}x{} pixels",
                originalMacroImage.getWidth(), originalMacroImage.getHeight());

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
        logger.debug("Cropped macro image: {}x{} pixels, offset: ({}, {})",
                croppedMacroImage.getWidth(), croppedMacroImage.getHeight(),
                croppedResult.getCropOffsetX(), croppedResult.getCropOffsetY());

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
        logger.info("Showing green box preview dialog");
        return GreenBoxPreviewController.showPreviewDialog(displayMacroImage, params)
                .thenApply(greenBoxResult -> {
                    if (greenBoxResult == null) {
                        logger.info("Green box detection cancelled");
                        return null;
                    }
                    logger.info("Green box detected at ({}, {}, {}, {}) with confidence {}",
                            greenBoxResult.getDetectedBox().getBoundsX(),
                            greenBoxResult.getDetectedBox().getBoundsY(),
                            greenBoxResult.getDetectedBox().getBoundsWidth(),
                            greenBoxResult.getDetectedBox().getBoundsHeight(),
                            greenBoxResult.getConfidence());

                    // Note: greenBoxResult contains coordinates in the displayed (cropped+flipped) image space
                    return new AlignmentContext(context, pixelSize, greenBoxResult,
                            macroWidth, macroHeight,
                            croppedResult.getCropOffsetX(),
                            croppedResult.getCropOffsetY());
                })
                .thenCompose(alignContext -> {
                    if (alignContext == null) {
                        logger.info("Alignment context null, workflow cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Now create/setup project
                    logger.info("Setting up project");
                    return setupProject(gui, alignContext.context.sample)
                            .thenCompose(projectInfo -> {
                                if (projectInfo == null) {
                                    logger.warn("Project setup failed");
                                    return CompletableFuture.completedFuture(null);
                                }

                                // Continue with existing alignment workflow
                                return processExistingAlignment(gui, alignContext, projectInfo);
                            });
                });
    }

    /**
     * Handles Path B: Manual alignment creation.
     *
     * <p>This path is used when no existing alignment is available or when the user
     * chooses to create a new one. It guides the user through manually establishing
     * correspondence between QuPath tiles and microscope stage positions.</p>
     *
     * @param gui QuPath GUI instance
     * @param context Workflow context with sample information
     * @return CompletableFuture that completes when workflow finishes
     */
    private static CompletableFuture<Void> handleManualAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path B: Manual alignment creation");

        // Get macro pixel size
        double pixelSize;
        try {
            // For manual alignment, try to get from preferences first
            String savedScanner = PersistentPreferences.getSelectedScanner();
            logger.debug("Saved scanner from preferences: {}", savedScanner);

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
                        logger.debug("No macro pixel size in scanner config, using default: {} µm", pixelSize);
                    }
                } else {
                    pixelSize = MacroImageUtility.getMacroPixelSize();
                    logger.debug("Scanner config not found, using default pixel size: {} µm", pixelSize);
                }
            } else {
                pixelSize = MacroImageUtility.getMacroPixelSize();
                logger.debug("No saved scanner, using default pixel size: {} µm", pixelSize);
            }
        } catch (IllegalStateException e) {
            logger.error("Failed to get macro pixel size: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    e.getMessage() + "\n\nThe workflow cannot continue without the macro image pixel size.",
                    "Configuration Error"
            );
            return CompletableFuture.completedFuture(null);
        }

        // Create/setup project
        logger.info("Setting up project for manual alignment");
        return setupProject(gui, context.sample)
                .thenApply(projectInfo -> {
                    if (projectInfo == null) {
                        logger.warn("Project setup failed");
                        return null;
                    }
                    return new ManualAlignmentContext(context, pixelSize, projectInfo);
                })
                .thenCompose(manualContext -> {
                    if (manualContext == null) {
                        logger.info("Manual context null, workflow cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Continue with manual alignment workflow
                    return processManualAlignment(gui, manualContext);
                });
    }

    /**
     * Sets up the QuPath project - either creates new or uses existing.
     *
     * <p>This method handles project creation and configuration, including:</p>
     * <ul>
     *   <li>Creating a new project if none exists</li>
     *   <li>Setting up the directory structure</li>
     *   <li>Importing the current image with appropriate flips</li>
     *   <li>Saving macro image dimensions for later reference</li>
     * </ul>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @return CompletableFuture with ProjectInfo containing project details
     */
    private static CompletableFuture<ProjectInfo> setupProject(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<ProjectInfo> future = new CompletableFuture<>();

        logger.info("Setting up project for sample: {}", sample.sampleName());

        // Run on JavaFX thread since QPProjectFunctions manipulates GUI
        Platform.runLater(() -> {
            try {
                Map<String, Object> projectDetails;
                boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();

                logger.debug("Flip settings - X: {}, Y: {}", flippedX, flippedY);

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

                    // Save macro image dimensions if available
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
                                    logger.debug("Reopening image after adding to project");
                                    gui.openImageEntry(newEntry);
                                }
                            }
                        } else {
                            logger.info("Current image already exists in project, no need to re-add");
                        }
                    }
                }

                logger.info("Project setup complete - Mode: {}, Tile dir: {}",
                        projectDetails.get("imagingModeWithIndex"),
                        projectDetails.get("tempTileDirectory"));

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
     *
     * <p>This method creates a full-resolution to stage transform by combining:</p>
     * <ol>
     *   <li>A pixel-size-based transform from full-resolution to macro coordinates</li>
     *   <li>The detected green box position for translation offset</li>
     *   <li>The saved general transform for macro to stage mapping</li>
     * </ol>
     *
     * <p><b>Transform Construction:</b></p>
     * <p>The full-resolution to stage transform is built in two steps:</p>
     * <ul>
     *   <li><b>Full-res → Macro:</b> Uses the physical pixel size ratio between the full-resolution
     *       image and macro image to scale coordinates. The green box location provides only the
     *       translation offset, never affecting the scale. Scale = fullResPixelSize / macroPixelSize</li>
     *   <li><b>Macro → Stage:</b> Uses the previously saved general transform that maps macro
     *       image coordinates to physical stage positions in micrometers</li>
     * </ul>
     *
     * <p><b>Critical Design Decision:</b></p>
     * <p>The green box dimensions are NEVER used for scaling. The scale between full-resolution
     * and macro images is determined solely by their respective pixel sizes. This ensures that
     * tile spacing in acquisition remains accurate and is based on the precise pixel dimensions
     * of the full-resolution image, not the imprecise green box detection in the low-resolution
     * macro image.</p>
     *
     * <p>The method also handles optional single-tile refinement where the user can
     * fine-tune the alignment by manually adjusting one tile position.</p>
     *
     * @param gui QuPath GUI instance providing access to the current image data
     * @param alignContext Context containing green box detection results, pixel sizes, and workflow configuration
     * @param projectInfo Project setup information including tile directories and modality settings
     * @return CompletableFuture that completes when all processing finishes, including any
     *         refinement steps and acquisition of all annotations
     */
    private static CompletableFuture<Void> processExistingAlignment(
            QuPathGUI gui, AlignmentContext alignContext, ProjectInfo projectInfo) {

        logger.info("Processing existing alignment with green box detection");

        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");

        // Get the base transform (macro→stage)
        AffineTransform macroToStageTransform = alignContext.context.alignment.selectedTransform().getTransform();
        logger.info("Loaded macro→stage transform: {}", macroToStageTransform);

        // Get or create annotations first
        List<PathObject> annotations = ensureAnnotationsExist(gui, alignContext.pixelSize);
        if (annotations.isEmpty()) {
            logger.warn("No annotations available for existing alignment processing");
            return CompletableFuture.completedFuture(null);
        }

        // Create full-res→stage transform using current green box
        ROI greenBox = alignContext.greenBoxResult.getDetectedBox();

        // Get the reported image dimensions (includes padding)
        int reportedWidth = gui.getImageData().getServer().getWidth();
        int reportedHeight = gui.getImageData().getServer().getHeight();

        logger.info("Green box detected at ({}, {}, {}, {}) in displayed (cropped+flipped) macro",
                greenBox.getBoundsX(), greenBox.getBoundsY(),
                greenBox.getBoundsWidth(), greenBox.getBoundsHeight());
        logger.info("Reported image dimensions (with padding): {} x {}", reportedWidth, reportedHeight);
        logger.info("Note: Image has already been flipped during import - all coordinates are in flipped space");

        // Get the actual pixel sizes - these are the ground truth for scaling
        double macroPixelSize = alignContext.pixelSize; // e.g., 80 µm/pixel
        double fullResPixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons(); // e.g., 0.5 µm/pixel

        logger.info("Pixel sizes - Macro: {} µm/pixel, Full-res: {} µm/pixel",
                macroPixelSize, fullResPixelSize);

        // Detect actual data bounds by finding non-padding areas
        // This handles pyramidal images that add white padding around the actual acquired area
        int dataMinX = 0;
        int dataMinY = 0;
        int dataMaxX = reportedWidth;
        int dataMaxY = reportedHeight;

        try {
            logger.info("Detecting actual data bounds (excluding padding)...");

            // Check if this is an Ocus40 image by looking at the scanner name
            String scannerName = alignContext.context.alignment.selectedTransform().getMountingMethod();

            if ("Ocus40".equalsIgnoreCase(scannerName)) {
                logger.info("Detected Ocus40 scanner - using white background detection");

                // Get the directory containing the tissue detection script
                String tissueScriptPath = QPPreferenceDialog.getTissueDetectionScriptProperty();
                String scriptDirectory = null;
                if (tissueScriptPath != null && !tissueScriptPath.isBlank()) {
                    File scriptFile = new File(tissueScriptPath);
                    scriptDirectory = scriptFile.getParent();
                }

                if (scriptDirectory != null) {
                    Rectangle bounds = detectOcus40DataBounds(gui, scriptDirectory);
                    if (bounds != null) {
                        dataMinX = bounds.x;
                        dataMinY = bounds.y;
                        dataMaxX = bounds.x + bounds.width;
                        dataMaxY = bounds.y + bounds.height;

                        logger.info("Successfully detected Ocus40 data bounds");
                    } else {
                        logger.warn("Ocus40 white background detection failed, falling back to calculation");
                    }
                } else {
                    logger.warn("Cannot find script directory for WhiteBackground.json classifier");
                }
            }

            // If not Ocus40 or detection failed, fall back to calculation
            if (dataMaxX == reportedWidth && dataMaxY == reportedHeight) {
                logger.info("Using green box-based calculation for data bounds");

                double pixelSizeRatio = macroPixelSize / fullResPixelSize;
                int calculatedWidth = (int) Math.round(greenBox.getBoundsWidth() * pixelSizeRatio);
                int calculatedHeight = (int) Math.round(greenBox.getBoundsHeight() * pixelSizeRatio);

                // For non-Ocus40 scanners, assume symmetric padding or no padding
                int widthDiff = reportedWidth - calculatedWidth;
                int heightDiff = reportedHeight - calculatedHeight;

                // Distribute padding evenly if no specific scanner info
                dataMinX = widthDiff / 2;
                dataMinY = heightDiff / 2;
                dataMaxX = dataMinX + calculatedWidth;
                dataMaxY = dataMinY + calculatedHeight;

                logger.info("Calculated bounds: data starts at ({}, {}) with size {} x {}",
                        dataMinX, dataMinY, calculatedWidth, calculatedHeight);
            }

        } catch (Exception e) {
            logger.error("Error detecting data bounds: {}", e.getMessage());
            // Fall back to reported dimensions
            dataMinX = 0;
            dataMinY = 0;
            dataMaxX = reportedWidth;
            dataMaxY = reportedHeight;
        }

        // Calculate actual dimensions and offset
        int actualWidth = dataMaxX - dataMinX;
        int actualHeight = dataMaxY - dataMinY;

        logger.info("Actual data dimensions: {} x {} at offset ({}, {})",
                actualWidth, actualHeight, dataMinX, dataMinY);

        // Use actual dimensions for transform calculations
        int fullResWidth = actualWidth;
        int fullResHeight = actualHeight;

        // IMPORTANT: We also need to account for the data offset when creating the transform
        int dataOffsetX = dataMinX;
        int dataOffsetY = dataMinY;



        logger.info("Pixel sizes - Macro: {} µm/pixel, Full-res: {} µm/pixel",
                macroPixelSize, fullResPixelSize);

        // Calculate the scale based ONLY on pixel sizes (NOT green box dimensions!)
        // This is the critical fix - the green box size is imprecise due to low resolution
        double pixelSizeRatio = fullResPixelSize / macroPixelSize; // e.g., 0.5/80 = 0.00625

        // Create full-res to macro transform
        AffineTransform fullResToMacro = new AffineTransform();

        // Scale from full-res pixels to macro pixels based on pixel size ratio
        fullResToMacro.scale(pixelSizeRatio, pixelSizeRatio);

        // Then translate to position the origin at the green box location
        // IMPORTANT: We need to account for the data offset within the padded image
        // The green box represents where the actual data (not padding) is in the macro
        fullResToMacro.translate(
                (greenBox.getBoundsX() - dataOffsetX * pixelSizeRatio) / pixelSizeRatio,
                (greenBox.getBoundsY() - dataOffsetY * pixelSizeRatio) / pixelSizeRatio
        );

        logger.info("FullRes→Macro transform: scale({}) based on pixel size ratio, translate accounting for data offset",
                pixelSizeRatio);

        // Verify the transform by testing corner points
        // Test with actual data bounds, not full image bounds
        double[] topLeft = {dataOffsetX, dataOffsetY};
        double[] bottomRight = {dataOffsetX + fullResWidth, dataOffsetY + fullResHeight};
        double[] topLeftInMacro = new double[2];
        double[] bottomRightInMacro = new double[2];
        fullResToMacro.transform(topLeft, 0, topLeftInMacro, 0, 1);
        fullResToMacro.transform(bottomRight, 0, bottomRightInMacro, 0, 1);

        logger.info("Transform verification:");
        logger.info("  Full-res data start ({},{}) → macro ({}, {})",
                dataOffsetX, dataOffsetY, topLeftInMacro[0], topLeftInMacro[1]);
        logger.info("  Full-res data end ({},{}) → macro ({}, {})",
                dataOffsetX + fullResWidth, dataOffsetY + fullResHeight,
                bottomRightInMacro[0], bottomRightInMacro[1]);

        // Verify the mapped size matches expected pixel-based calculations
        double expectedWidthInMacro = fullResWidth * pixelSizeRatio;
        double expectedHeightInMacro = fullResHeight * pixelSizeRatio;
        double actualWidthInMacro = bottomRightInMacro[0] - topLeftInMacro[0];
        double actualHeightInMacro = bottomRightInMacro[1] - topLeftInMacro[1];

        logger.info("  Expected size in macro (based on pixel ratio): {} x {} pixels",
                expectedWidthInMacro, expectedHeightInMacro);
        logger.info("  Actual mapped size: {} x {} pixels",
                actualWidthInMacro, actualHeightInMacro);

        // Log warning if there's a significant discrepancy (should not happen with correct implementation)
        if (Math.abs(actualWidthInMacro - expectedWidthInMacro) > 1.0 ||
                Math.abs(actualHeightInMacro - expectedHeightInMacro) > 1.0) {
            logger.warn("Transform verification shows size discrepancy! This may indicate an error.");
        }

        // Now combine: full-res → macro → stage
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
        logger.info("Set current transform in microscope controller");

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
                    logger.info("User chose to create new alignment");
                    return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                            annotations, fullResToStage);
                }
            });
        } else {
            // No existing slide alignment, proceed with normal flow
            logger.info("No existing slide alignment found, proceeding with refinement check");
            return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                    annotations, fullResToStage);
        }
    }

    /**
     * Handles the alignment refinement process.
     *
     * <p>This method checks if the user requested refinement during alignment selection.
     * If so, it guides them through single-tile refinement to improve alignment accuracy.</p>
     *
     * @param gui QuPath GUI instance
     * @param alignContext Alignment context with green box results
     * @param projectInfo Project information
     * @param annotations List of valid annotations
     * @param fullResToStageTransform Initial transform to refine
     * @return CompletableFuture that completes when refinement finishes
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

        logger.info("Creating tiles for {} annotations", annotations.size());
        createTilesForAnnotations(annotations, alignContext.context.sample, tempTileDir,
                modeWithIndex, alignContext.pixelSize, invertedX, invertedY);

        if (alignContext.context.alignment.refinementRequested()) {
            logger.info("User requested alignment refinement");
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
                            logger.info("Refinement complete, continuing with acquisition");
                            // Continue with the refined (or initial) transform
                            return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                                    annotations, alignContext.pixelSize, refinedTransform);
                        }
                    });
        } else {
            logger.info("No refinement requested, saving and continuing");
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
     * Processes manual alignment workflow.
     *
     * <p>This method guides the user through creating a new alignment from scratch
     * by manually correlating QuPath tile positions with microscope stage positions.</p>
     *
     * @param gui QuPath GUI instance
     * @param context Manual alignment context
     * @return CompletableFuture that completes when alignment is created
     */
    private static CompletableFuture<Void> processManualAlignment(
            QuPathGUI gui, ManualAlignmentContext context) {

        logger.info("Starting manual alignment process");

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

        logger.info("Creating tiles for manual alignment");
        createTilesForAnnotations(annotations, context.context.sample, tempTileDir,
                modeWithIndex, context.pixelSize, invertedX, invertedY);

        // Setup manual transform
        logger.info("Starting manual transform setup GUI");
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        context.pixelSize, invertedX, invertedY)
                .thenCompose(transform -> {
                    if (transform == null) {
                        logger.info("Transform setup cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    logger.info("Manual transform created: {}", transform);

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
                    logger.info("Saved slide-specific transform");

                    return continueToAcquisition(gui, context.context.sample,
                            context.projectInfo, annotations, context.pixelSize, transform);
                });
    }

    /**
     * Ensures annotations exist - either collects existing ones or creates new via tissue detection.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Checks for existing valid annotations</li>
     *   <li>If none found, optionally runs tissue detection</li>
     *   <li>Ensures all annotations have names</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param macroPixelSize Pixel size for tissue detection script
     * @return List of valid annotations (may be empty)
     */
    private static List<PathObject> ensureAnnotationsExist(
            QuPathGUI gui, double macroPixelSize) {

        logger.info("Ensuring annotations exist for acquisition");

        // Get existing annotations
        List<PathObject> annotations = getCurrentValidAnnotations();

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing valid annotations", annotations.size());
            ensureAnnotationNames(annotations);
            return annotations;
        }

        logger.info("No existing annotations found, checking tissue detection script");

        // Run tissue detection if configured
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();

        // If no script is configured, prompt user to select one
        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured, prompting user");
            tissueScript = promptForTissueDetectionScript();
        }

        if (tissueScript != null && !tissueScript.isBlank()) {
            try {
                logger.info("Running tissue detection script: {}", tissueScript);

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
                logger.info("Found {} annotations after tissue detection", annotations.size());

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
            }
        }

        if (annotations.isEmpty()) {
            logger.warn("Still no valid annotations after tissue detection");
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
     * Prompts user to select a tissue detection script.
     *
     * @return Path to selected script or null if cancelled
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
                logger.info("User declined tissue detection");
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
                logger.info("User selected tissue detection script: {}", selectedFile);
                future.complete(selectedFile.getAbsolutePath());
            } else {
                logger.info("User cancelled tissue detection script selection");
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
     * Gets current valid annotations from the image hierarchy.
     *
     * <p>Annotations must have:</p>
     * <ul>
     *   <li>A non-empty ROI</li>
     *   <li>A PathClass matching one of VALID_ANNOTATION_CLASSES</li>
     * </ul>
     *
     * @return List of valid annotations
     */
    private static List<PathObject> getCurrentValidAnnotations() {
        var annotations = QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        logger.debug("Found {} valid annotations from {} total",
                annotations.size(), QP.getAnnotationObjects().size());

        return annotations;
    }

    /**
     * Ensures all annotations have names (auto-generates if needed).
     *
     * <p>Names are generated based on the annotation class and centroid position
     * to ensure uniqueness.</p>
     *
     * @param annotations List of annotations to process
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
     * Creates tiles for the given annotations.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Removes existing tiles for the modality</li>
     *   <li>Validates tile counts to prevent excessive tiling</li>
     *   <li>Creates new tiles using TilingUtilities</li>
     * </ol>
     *
     * @param annotations List of annotations to tile
     * @param sample Sample information
     * @param tempTileDirectory Output directory for tile configurations
     * @param modeWithIndex Modality with index (e.g., "PPM_10x_1")
     * @param macroPixelSize Pixel size in micrometers
     * @param invertedX Whether X axis is inverted
     * @param invertedY Whether Y axis is inverted
     * @throws RuntimeException if tiling would create too many tiles (>10000)
     */
    private static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        logger.info("Creating tiles for {} annotations in modality {}",
                annotations.size(), modeWithIndex);

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

        // Calculate frame dimensions in microns
        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to image pixels (THIS IS THE FIX!)
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        logger.info("Camera frame size: {} x {} µm", frameWidthMicrons, frameHeightMicrons);
        logger.info("Frame size in QuPath pixels: {} x {} pixels", frameWidthPixels, frameHeightPixels);

        try {
            // Remove existing tiles
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            logger.debug("Removing existing tiles for modality base: {}", modalityBase);

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

                    logger.debug("Annotation '{}' will create approximately {} tiles",
                            ann.getName(), totalTiles);
                }
            }

            // Create new tiles with PIXEL dimensions
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthPixels, frameHeightPixels)  // PIXELS, not microns!
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
     * Deletes all detection tiles for a given modality.
     *
     * <p>This method efficiently removes tiles by:</p>
     * <ul>
     *   <li>Batch removal if removing >80% of detections</li>
     *   <li>Direct removal for smaller percentages</li>
     * </ul>
     *
     * @param gui QuPath GUI instance
     * @param modality Modality name (base name without index)
     */
    private static void deleteAllTiles(QuPathGUI gui, String modality) {
        var hierarchy = gui.getViewer().getHierarchy();
        int totalDetections = hierarchy.getDetectionObjects().size();

        String modalityBase = modality.replaceAll("(_\\d+)$", "");
        logger.debug("Deleting tiles for modality base: {}", modalityBase);

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
     * <p>This method allows the user to fine-tune the alignment by:</p>
     * <ol>
     *   <li>Selecting a tile in QuPath</li>
     *   <li>Moving the microscope to the estimated position</li>
     *   <li>Manually adjusting the stage position to match</li>
     *   <li>Calculating a refined transform based on the adjustment</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param alignContext Alignment context with green box results
     * @param projectInfo Project information
     * @param annotations List of annotations
     * @param initialTransform Initial transform to refine
     * @return CompletableFuture with refined transform, or null if user wants new alignment
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
                logger.info("User cancelled tile selection");
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

                        logger.error("Estimated position outside stage bounds");
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
                        logger.debug("Centered viewer on tile and selected it");
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
     * Offers to save a transform for future use.
     *
     * <p>This method is typically called after manual alignment creation to allow
     * the user to save the transform as a general preset.</p>
     *
     * @param transform The transform to save
     * @param modality The modality name
     * @return CompletableFuture with true if saved, false otherwise
     */
    private static CompletableFuture<Boolean> offerToSaveTransform(
            AffineTransform transform, String modality) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        logger.info("Offering to save transform for future use");

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
                    logger.info("User cancelled transform save");
                    future.complete(false);
                }
            } else {
                logger.info("User declined to save transform");
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Continues to the acquisition phase after alignment is established.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Validates annotations</li>
     *   <li>Gets rotation angles for the modality</li>
     *   <li>Regenerates tiles and transforms coordinates</li>
     *   <li>Processes each annotation for acquisition</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample information
     * @param projectInfo Project details
     * @param annotations List of annotations to acquire
     * @param macroPixelSize Pixel size in micrometers
     * @param transform Full-res to stage transform
     * @return CompletableFuture that completes when acquisition finishes
     */
    private static CompletableFuture<Void> continueToAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            double macroPixelSize,
            AffineTransform transform) {

        logger.info("Continuing to acquisition phase with {} annotations", annotations.size());

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
            logger.info("Getting rotation angles for modality: {}", sample.modality());

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
     * Ensures annotations are ready for acquisition.
     *
     * <p>This critical method:</p>
     * <ol>
     *   <li>Gets current valid annotations</li>
     *   <li>Deletes all existing tiles</li>
     *   <li>Cleans up stale folders from previous runs</li>
     *   <li>Creates fresh tiles for all annotations</li>
     *   <li>Transforms tile coordinates to stage coordinates</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample information
     * @param tempTileDirectory Directory for tile configurations
     * @param modeWithIndex Modality with index
     * @param macroPixelSize Pixel size in micrometers
     * @param fullResToStage Transform from full-res to stage coordinates
     * @return List of annotations ready for acquisition
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
     * Cleans up folders from previous tile creation that no longer have corresponding annotations.
     *
     * <p>This prevents stale tile configurations from interfering with acquisition.</p>
     *
     * @param tempTileDirectory Base directory for tile configurations
     * @param currentAnnotations Current list of valid annotations
     */
    private static void cleanupStaleFolders(String tempTileDirectory, List<PathObject> currentAnnotations) {
        try {
            File tempDir = new File(tempTileDirectory);
            if (!tempDir.exists() || !tempDir.isDirectory()) {
                logger.debug("Temp directory doesn't exist, nothing to clean up");
                return;
            }

            // Get current annotation names
            Set<String> currentAnnotationNames = currentAnnotations.stream()
                    .map(PathObject::getName)
                    .filter(name -> name != null && !name.trim().isEmpty())
                    .collect(Collectors.toSet());

            logger.debug("Current annotation names: {}", currentAnnotationNames);

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
     * Recursively deletes a directory and all its contents.
     *
     * @param dir Directory to delete
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
     *
     * <p>This is the core acquisition orchestration method that:</p>
     * <ol>
     *   <li>Processes each annotation sequentially to prevent hardware conflicts</li>
     *   <li>Monitors acquisition progress with cancellation support</li>
     *   <li>Launches stitching operations asynchronously after each acquisition</li>
     *   <li>Waits for all operations to complete before cleanup</li>
     * </ol>
     *
     * <p>While acquisitions are sequential, stitching operations can run in parallel
     * using the dedicated STITCH_EXECUTOR to maximize throughput.</p>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample information
     * @param projectInfo Project details
     * @param annotations List of annotations to acquire
     * @param macroPixelSize Pixel size in micrometers
     * @param transform Full-res to stage transform
     * @param angleExposures List of rotation angles with exposure times
     * @param rotationManager Manager for rotation-specific settings
     * @return CompletableFuture that completes when all operations finish
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

        logger.info("Starting acquisition for {} annotations with {} angles each",
                annotations.size(), angleExposures != null ? angleExposures.size() : 1);

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
                        logger.error("Acquisition failed or was cancelled for annotation: {}", annotation.getName());
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

                    logger.info("Launched stitching for annotation: {}", annotation.getName());

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
     * Performs acquisition for a single annotation using socket-based communication.
     *
     * <p>This method manages the complete acquisition lifecycle including:</p>
     * <ul>
     *   <li>Building and sending the acquisition command</li>
     *   <li>Calculating expected file counts</li>
     *   <li>Displaying progress with cancellation support</li>
     *   <li>Monitoring until completion or failure</li>
     * </ul>
     *
     * @param annotation The annotation to acquire
     * @param sample Sample information
     * @param projectsFolder Project root directory
     * @param modeWithIndex Modality with index
     * @param configFile Path to microscope configuration
     * @param tickExposures List of angles with exposure times
     * @return CompletableFuture with true if successful, false if cancelled
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
                            300000, // 5 minute timeout per annotation, should be reset by MicroscopeSocketClient.monitorAcquisition()
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
     * <p>This method manages sequential stitching of tile sets acquired at different
     * rotation angles. Each angle's tiles are stitched into a separate OME-TIFF file.</p>
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample information
     * @param projectsFolder Root project directory
     * @param modeWithIndex Base modality folder
     * @param rotationAngles List of rotation angles
     * @param rotationManager Manager for angle suffixes
     * @param pixelSize Physical pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project Target project for imports
     * @return CompletableFuture that completes when all stitching finishes
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
            logger.info("Stitching {} angles for annotation: {}",
                    rotationAngles.size(), annotation.getName());

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
            logger.info("Single stitching for annotation: {}", annotation.getName());
            // Single stitch
            return stitchAnnotation(
                    annotation, null, sample, projectsFolder,
                    modeWithIndex, null, pixelSize, gui, project
            );
        }
    }

    /**
     * Performs the actual stitching operation for a single annotation and rotation angle.
     *
     * <p>This method handles the core stitching logic, including directory structure
     * navigation, output file naming, and error handling.</p>
     *
     * @param annotation The annotation being stitched
     * @param angle The rotation angle (null for single-angle)
     * @param sample Sample information
     * @param projectsFolder Project root directory
     * @param modeWithIndex Base modality folder
     * @param rotationManager Manager for angle suffixes
     * @param pixelSize Physical pixel size
     * @param gui QuPath GUI instance
     * @param project Target project
     * @return CompletableFuture that completes when stitching finishes
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

                // Call stitchImagesAndUpdateProject with the correct parameters
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

                logger.info("Stitching completed for {} (angle: {}), output: {}",
                        annotationName, angle, outPath);

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
     * Convenience wrapper for stitching a single annotation at a specific angle.
     *
     * @param annotation The annotation to stitch
     * @param angle The rotation angle
     * @param sample Sample information
     * @param projectsFolder Project root directory
     * @param modeWithIndex Base modality folder
     * @param rotationManager Manager for angle suffixes
     * @param pixelSize Physical pixel size
     * @param gui QuPath GUI instance
     * @param project Target project
     * @return CompletableFuture that completes when stitching finishes
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

    // ===== Helper Classes =====

    /**
     * Internal context container for passing workflow state between dialog stages.
     *
     * <p>This class encapsulates the results from the sample setup and alignment
     * selection dialogs.</p>
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
     * <p>Contains all information needed to process an existing alignment workflow,
     * including the detected green box location and macro image dimensions.</p>
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
     * Context for manual alignment workflows.
     *
     * <p>Used when no existing alignment is available and the user must
     * manually establish correspondence.</p>
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
     * Wrapper for project setup information.
     *
     * <p>Provides type-safe access to project setup details.</p>
     */
    private static class ProjectInfo {
        final Map<String, Object> details;

        ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }
    }
}