package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.AnnotationAcquisitionDialog;
import qupath.ext.qpsc.ui.DualProgressDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages the acquisition phase of the microscope workflow.
 *
 * <p>This class orchestrates the complete acquisition process including:
 * <ul>
 *   <li>Annotation validation with user confirmation</li>
 *   <li>Rotation angle configuration for multi-modal imaging</li>
 *   <li>Tile generation and transformation to stage coordinates</li>
 *   <li>Acquisition monitoring with progress tracking</li>
 *   <li>Automatic stitching queue management</li>
 *   <li>Error handling and user cancellation support</li>
 * </ul>
 *
 * <p>The acquisition process is performed sequentially for each annotation to ensure
 * proper resource management and allow for user intervention if needed.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AcquisitionManager {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionManager.class);


    /** Maximum time to wait for acquisition completion (5 minutes) */
    private static final int ACQUISITION_TIMEOUT_MS = 300000;

    /** Single-threaded executor for stitching operations to prevent overwhelming system resources */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    private final QuPathGUI gui;
    private final WorkflowState state;
    private DualProgressDialog dualProgressDialog;

    /**
     * Creates a new acquisition manager.
     *
     * @param gui The QuPath GUI instance
     * @param state The current workflow state containing all necessary information
     */
    public AcquisitionManager(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the complete acquisition phase.
     *
     * <p>This method orchestrates the entire acquisition workflow:
     * <ol>
     *   <li>Validates annotations with user confirmation</li>
     *   <li>Retrieves rotation angles for the imaging modality</li>
     *   <li>Prepares tiles for acquisition</li>
     *   <li>Processes each annotation sequentially</li>
     * </ol>
     *
     * @return CompletableFuture containing the updated workflow state, or null if cancelled/failed
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Starting acquisition phase");

        return validateAnnotations()
                .thenCompose(valid -> {
                    if (!valid) {
                        logger.info("Annotation validation failed or cancelled");
                        state.cancelled = true; // Mark workflow as cancelled
                        return CompletableFuture.completedFuture(null);
                    }
                    return getRotationAngles();
                })
                .thenCompose(this::prepareForAcquisition)
                .thenCompose(this::processAnnotations)
                .thenApply(success -> {
                    if (success) {
                        logger.info("Acquisition phase completed successfully");
                        return state;
                    }
                    throw new RuntimeException("Acquisition failed or was cancelled");
                });
    }

    /**
     * Validates annotations with user confirmation dialog.
     *
     * <p>Shows a dialog listing all valid annotations and allows the user to confirm
     * before proceeding with acquisition.
     *
     * @return CompletableFuture with true if user confirms, false if cancelled
     */
    private CompletableFuture<Boolean> validateAnnotations() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Get all unique annotation classes in current image
        Set<PathClass> existingClasses = MinorFunctions.getExistingClassifications(QP.getCurrentImageData());
        Set<String> existingClassNames = existingClasses.stream()
                .map(PathClass::toString)
                .collect(Collectors.toSet());

        logger.info("Found {} unique annotation classes: {}",
                existingClassNames.size(), existingClassNames);

        // Get preferences
        List<String> preselected = PersistentPreferences.getSelectedAnnotationClasses();

        // Show selection dialog with modality for modality-specific UI
        return AnnotationAcquisitionDialog.showDialog(existingClassNames, preselected, state.sample.modality())
                .thenApply(result -> {
                    if (!result.proceed || result.selectedClasses.isEmpty()) {
                        logger.info("Acquisition cancelled or no classes selected");
                        return false;
                    }

                    // Store selected classes and angle overrides in state
                    state.selectedAnnotationClasses = result.selectedClasses;
                    logger.info("User selected {} classes for acquisition: {}",
                            result.selectedClasses.size(), result.selectedClasses);

                    // Store angle overrides if provided
                    if (result.angleOverrides != null && !result.angleOverrides.isEmpty()) {
                        state.angleOverrides = result.angleOverrides;
                        logger.info("User provided angle overrides: {}", result.angleOverrides);
                    }

                    return true;
                });
    }
    /**
     * Retrieves rotation angles configured for the imaging modality.
     *
     * <p>For polarized light imaging or other multi-angle acquisitions, this method
     * retrieves the configured rotation angles and decimal exposure times.
     * If the user provided angle overrides in the annotation dialog, those will be
     * applied following the BoundingBoxWorkflow pattern.
     *
     * @return CompletableFuture containing list of rotation angles with exposure settings
     */
    private CompletableFuture<List<AngleExposure>> getRotationAngles() {
        ModalityHandler handler = ModalityRegistry.getHandler(state.sample.modality());
        logger.info("Getting rotation angles for modality: {} with hardware: obj={}, det={}",
                state.sample.modality(), state.sample.objective(), state.sample.detector());

        // Load profile-specific exposure defaults for PPM modality
        if ("ppm".equals(state.sample.modality())) {
            try {
                qupath.ext.qpsc.modality.ppm.PPMPreferences.loadExposuresForProfile(
                        state.sample.objective(), state.sample.detector());
            } catch (Exception e) {
                logger.warn("Failed to load PPM exposure defaults for {}/{}: {}",
                        state.sample.objective(), state.sample.detector(), e.getMessage());
            }
        }

        // Handle angle overrides if provided (following BoundingBoxWorkflow pattern)
        if (state.angleOverrides != null && !state.angleOverrides.isEmpty() && "ppm".equals(state.sample.modality())) {
            logger.info("Applying angle overrides from user dialog: {}", state.angleOverrides);

            // For PPM with overrides, get the default angles first, apply overrides, then show dialog with corrected angles
            qupath.ext.qpsc.modality.ppm.RotationManager rotationManager =
                new qupath.ext.qpsc.modality.ppm.RotationManager(state.sample.modality(), state.sample.objective(), state.sample.detector());

            // Get the default angles (this won't show any dialog since we're using the new method)
            return rotationManager.getDefaultAnglesWithExposure(state.sample.modality())
                .thenCompose(defaultAngles -> {
                    // Apply overrides to the default angles
                    List<AngleExposure> overriddenAngles =
                        handler.applyAngleOverrides(defaultAngles, state.angleOverrides);

                    // Extract the overridden plus/minus angles for the dialog
                    double plusAngle = 7.0;  // fallback
                    double minusAngle = -7.0; // fallback
                    double uncrossedAngle = 90.0; // fallback

                    for (AngleExposure ae : overriddenAngles) {
                        if (ae.ticks() > 0 && ae.ticks() < 45) plusAngle = ae.ticks();
                        else if (ae.ticks() < 0) minusAngle = ae.ticks();
                        else if (ae.ticks() >= 45) uncrossedAngle = ae.ticks();
                    }

                    // Now show the dialog with the correct angles
                    return qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController.showDialog(
                        plusAngle, minusAngle, uncrossedAngle,
                        state.sample.modality(), state.sample.objective(), state.sample.detector())
                        .thenApply(result -> {
                            if (result == null) {
                                logger.info("User cancelled angle selection dialog - acquisition will be cancelled");
                                throw new RuntimeException("ANGLE_SELECTION_CANCELLED");
                            }
                            List<AngleExposure> finalAngles = new ArrayList<>();
                            for (qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController.AngleExposure ae : result.angleExposures) {
                                finalAngles.add(new AngleExposure(ae.angle, ae.exposureMs));
                            }
                            return finalAngles;
                        });
                });
        } else {
            // Normal flow - no overrides or not PPM
            return handler.getRotationAngles(state.sample.modality(), state.sample.objective(), state.sample.detector());
        }
    }

    /**
     * Prepares annotations for acquisition by regenerating tiles.
     *
     * <p>This method:
     * <ul>
     *   <li>Retrieves current valid annotations</li>
     *   <li>Cleans up old tiles and directories</li>
     *   <li>Creates fresh tiles based on camera FOV</li>
     *   <li>Transforms tile coordinates to stage space</li>
     * </ul>
     *
     * @param angleExposures List of rotation angles, or null for single acquisition
     * @return CompletableFuture with angle exposures for next phase
     */
    private CompletableFuture<List<AngleExposure>> prepareForAcquisition(
            List<AngleExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            // Check for cancellation
            if (state.cancelled) {
                logger.info("Skipping acquisition preparation - workflow was cancelled");
                return null;
            }

            logger.info("Preparing for acquisition with {} angles",
                    angleExposures != null ? angleExposures.size() : 1);

            // Save project entry state before acquisition starts
            try {
                var imageData = QP.getCurrentImageData();
                var entry = QP.getProjectEntry();
                if (imageData != null && entry != null) {
                    entry.saveImageData(imageData);
                    logger.info("Saved project entry state before acquisition");
                } else {
                    logger.warn("Could not save project entry state - imageData or entry is null");
                }
            } catch (Exception e) {
                logger.error("Failed to save project entry state before acquisition", e);
            }

            // Get current annotations
            List<PathObject> currentAnnotations = AnnotationHelper.getCurrentValidAnnotations(
                    state.selectedAnnotationClasses
            );
            if (currentAnnotations.isEmpty()) {
                throw new RuntimeException("No valid annotations for acquisition");
            }

            logger.info("Found {} annotations to acquire", currentAnnotations.size());

            // Clean up old tiles
            TileHelper.deleteAllTiles(gui, state.sample.modality());
            TileHelper.cleanupStaleFolders(
                    state.projectInfo.getTempTileDirectory(),
                    currentAnnotations
            );

            // Create fresh tiles
            TileHelper.createTilesForAnnotations(
                    currentAnnotations,
                    state.sample,
                    state.projectInfo.getTempTileDirectory(),
                    state.projectInfo.getImagingModeWithIndex(),
                    state.pixelSize
            );

            // Transform tile configurations to stage coordinates
            try {
                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                        state.projectInfo.getTempTileDirectory(),
                        state.transform
                );
                logger.info("Transformed tile configurations for: {}", modifiedDirs);
            } catch (IOException e) {
                logger.error("Failed to transform tile configurations", e);
                throw new RuntimeException("Failed to transform tile configurations", e);
            }

            state.annotations = currentAnnotations;
            return angleExposures;
        });
    }

    /**
     * Processes all annotations for acquisition.
     *
     * <p>Annotations are processed sequentially to:
     * <ul>
     *   <li>Provide clear progress feedback</li>
     *   <li>Allow cancellation between annotations</li>
     *   <li>Prevent resource exhaustion</li>
     *   <li>Enable immediate stitching after each acquisition</li>
     * </ul>
     *
     * @param angleExposures Rotation angles for multi-modal acquisition
     * @return CompletableFuture with true if all successful, false if any failed/cancelled
     */
    private CompletableFuture<Boolean> processAnnotations(
            List<AngleExposure> angleExposures) {

        // Check for cancellation
        if (state.cancelled) {
            logger.info("Skipping annotation processing - workflow was cancelled");
            return CompletableFuture.completedFuture(false);
        }

        if (state.annotations.isEmpty()) {
            logger.warn("No annotations to process");
            return CompletableFuture.completedFuture(false);
        }

        // Show initial progress notification
        showAcquisitionStartNotification(angleExposures);
        
        // Create and show dual progress dialog on JavaFX Application Thread
        CompletableFuture<DualProgressDialog> dialogSetup = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DualProgressDialog dialog = new DualProgressDialog(state.annotations.size(), true);
                dialog.setCancelCallback(v -> {
                    logger.info("User requested workflow cancellation via dual progress dialog");
                    try {
                        MicroscopeController.getInstance().getSocketClient().cancelAcquisition();
                    } catch (IOException e) {
                        logger.error("Failed to send cancel command", e);
                    }
                });
                dialog.show();
                dualProgressDialog = dialog; // Set field for other method access
                dialogSetup.complete(dialog);
            } catch (Exception e) {
                dialogSetup.completeExceptionally(e);
            }
        });
        
        // Wait for dialog setup to complete and get final reference
        final DualProgressDialog progressDialog = getDialogSafely(dialogSetup);
        
        // If dialog creation failed, return early
        if (progressDialog == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Process each annotation sequentially
        CompletableFuture<Boolean> acquisitionChain = CompletableFuture.completedFuture(true);

        for (int i = 0; i < state.annotations.size(); i++) {
            final PathObject annotation = state.annotations.get(i);
            final int index = i + 1;
            final int total = state.annotations.size();

            acquisitionChain = acquisitionChain.thenCompose(previousSuccess -> {
                if (!previousSuccess) {
                    logger.info("Stopping acquisition due to previous failure");
                    return CompletableFuture.completedFuture(false);
                }

                logger.info("Processing annotation {} of {}: {}", index, total, annotation.getName());

                showProgressNotification(index, total, annotation.getName());

                return performSingleAnnotationAcquisition(annotation, angleExposures, progressDialog)
                        .thenApply(success -> {
                            if (success) {
                                // Mark annotation complete in dual progress dialog
                                if (progressDialog != null) {
                                    Platform.runLater(() -> progressDialog.completeCurrentAnnotation());
                                }
                                // Launch stitching asynchronously after successful acquisition
                                launchStitching(annotation, angleExposures);
                            } else {
                                // Show error in dual progress dialog
                                if (progressDialog != null) {
                                    Platform.runLater(() -> progressDialog.showError("Failed to acquire " + annotation.getName()));
                                }
                            }
                            return success;
                        });
            });
        }

        return acquisitionChain.whenComplete((result, error) -> {
            // Close dual progress dialog when workflow completes or fails
            if (progressDialog != null) {
                if (error != null) {
                    Platform.runLater(() -> progressDialog.showError("Workflow failed: " + error.getMessage()));
                } else if (!result) {
                    Platform.runLater(() -> progressDialog.showError("Workflow was cancelled"));
                }
                // Dialog will auto-close after completion or error display
            }
        });
    }

    /**
     * Performs acquisition for a single annotation.
     *
     * <p>This method:
     * <ul>
     *   <li>Builds the acquisition command with all parameters</li>
     *   <li>Starts the acquisition on the microscope</li>
     *   <li>Monitors progress with cancellation support</li>
     * </ul>
     *
     * @param annotation The annotation to acquire
     * @param angleExposures Rotation angles for this acquisition
     * @return CompletableFuture with true if successful, false if failed/cancelled
     */
    private CompletableFuture<Boolean> performSingleAnnotationAcquisition(
            PathObject annotation,
            List<AngleExposure> angleExposures,
            DualProgressDialog progressDialog) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting acquisition for annotation: {}", annotation.getName());

                // Get configuration file path
                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

                // Extract modality base name
                String modalityWithIndex = state.projectInfo.getImagingModeWithIndex();
                String baseModality = state.sample.modality();

                // Get WSI pixel size using explicit hardware configuration
                double WSI_pixelSize_um;
                try {
                    WSI_pixelSize_um = configManager.getModalityPixelSize(baseModality, state.sample.objective(), state.sample.detector());
                    logger.debug("Using explicit hardware config: obj={}, det={}, px={}",
                            state.sample.objective(), state.sample.detector(), WSI_pixelSize_um);
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Failed to get pixel size for selected hardware configuration: " + 
                            baseModality + "/" + state.sample.objective() + "/" + state.sample.detector() + " - " + e.getMessage());
                }

                // Build acquisition configuration using shared builder
                AcquisitionConfigurationBuilder.AcquisitionConfiguration config = 
                    AcquisitionConfigurationBuilder.buildConfiguration(
                        state.sample, 
                        configFileLocation, 
                        modalityWithIndex, 
                        annotation.getName(), 
                        angleExposures, 
                        state.sample.projectsFolder().getAbsolutePath(), 
                        WSI_pixelSize_um
                    );

                logger.info("Acquisition parameters for {}:", annotation.getName());
                logger.info("  Config: {}", configFileLocation);
                logger.info("  Sample: {}", state.sample.sampleName());
                logger.info("  Hardware: {} / {} @ {} µm/px", config.objective(), config.detector(), config.WSI_pixelSize_um());
                logger.info("  Autofocus: {} tiles, {} steps, {} µm range", config.afTiles(), config.afSteps(), config.afRange());
                logger.info("  Processing: {}", config.processingSteps());
                if (config.bgEnabled()) {
                    logger.info("  Background correction: {} method from {}", config.bgMethod(), config.bgFolder());
                }
                if (angleExposures != null && !angleExposures.isEmpty()) {
                    logger.info("  Angles: {}", angleExposures);
                }
                String commandString = config.commandBuilder().buildSocketMessage();
                MinorFunctions.saveAcquisitionCommand(
                        commandString,
                        state.sample.projectsFolder().getAbsolutePath(),
                        state.sample.sampleName(),
                        modalityWithIndex,
                        annotation.getName()
                );
                // Start acquisition
                MicroscopeController.getInstance().startAcquisition(config.commandBuilder());

                // Monitor progress
                return monitorAcquisition(annotation, angleExposures, progressDialog);

            } catch (Exception e) {
                logger.error("Acquisition failed for {}", annotation.getName(), e);
                showAcquisitionError(annotation.getName(), e.getMessage());
                return false;
            }
        });
    }
    /**
     * Monitors acquisition progress with cancellation support.
     *
     * <p>This method:
     * <ul>
     *   <li>Calculates expected file count based on tiles and angles</li>
     *   <li>Shows a progress bar with cancel button</li>
     *   <li>Polls the microscope server for status updates</li>
     *   <li>Handles user cancellation requests</li>
     * </ul>
     *
     * @param annotation The annotation being acquired
     * @param angleExposures Rotation angles for calculating expected files
     * @return true if completed successfully, false if cancelled/failed
     * @throws IOException if communication with microscope fails
     */
    private boolean monitorAcquisition(PathObject annotation,
                                       List<AngleExposure> angleExposures,
                                       DualProgressDialog progressDialog) throws IOException {

        MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

        // Calculate expected files with retry logic to handle timing issues
        String tileDirPath = Paths.get(
                state.sample.projectsFolder().getAbsolutePath(),
                state.sample.sampleName(),
                state.projectInfo.getImagingModeWithIndex(),
                annotation.getName()
        ).toString();

        // Try to count tiles with retry logic (3 attempts, 200ms delay)
        int tilesPerAngle = MinorFunctions.countExpectedTilesWithRetry(List.of(tileDirPath), 3, 200);

        // If count is still 0 after retries, estimate from annotation bounds
        if (tilesPerAngle == 0) {
            logger.warn("Could not count tiles from TileConfiguration file, estimating from annotation bounds");
            tilesPerAngle = estimateTileCount(annotation);
            logger.info("Estimated {} tiles for annotation {}", tilesPerAngle, annotation.getName());
        }

        final int expectedFiles = angleExposures != null && !angleExposures.isEmpty()
            ? tilesPerAngle * angleExposures.size()
            : tilesPerAngle;

        logger.info("Expected files: {} ({}x{} angles)", expectedFiles, tilesPerAngle,
                angleExposures != null ? angleExposures.size() : 1);

        // Create progress counter
        AtomicInteger progressCounter = new AtomicInteger(0);

        // Start tracking this annotation in the dual progress dialog
        if (progressDialog != null && !progressDialog.isCancelled()) {
            Platform.runLater(() -> progressDialog.startAnnotation(annotation.getName(), expectedFiles));
        }

        // Flag to track if we've read the acquisition metadata file
        AtomicBoolean metadataRead = new AtomicBoolean(false);
        // Flag to track if we're currently handling a manual focus request (to avoid showing multiple dialogs)
        AtomicBoolean handlingManualFocus = new AtomicBoolean(false);

        try {
            // Monitor acquisition with regular status updates
            MicroscopeSocketClient.AcquisitionState finalState =
                    socketClient.monitorAcquisition(
                            progress -> {
                                progressCounter.set(progress.current);
                                // Update dual progress dialog
                                if (progressDialog != null && !progressDialog.isCancelled()) {
                                    Platform.runLater(() -> progressDialog.updateCurrentAnnotationProgress(progress.current));
                                }

                                // Check for manual focus request (only once per request)
                                try {
                                    if (socketClient.isManualFocusRequested() && !handlingManualFocus.get()) {
                                        handlingManualFocus.set(true);
                                        logger.info("Manual focus requested by server - showing dialog");

                                        // Use CountDownLatch to block until dialog is closed and acknowledged
                                        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                                        // Show dialog on JavaFX thread
                                        Platform.runLater(() -> {
                                            try {
                                                UIFunctions.ManualFocusResult result = UIFunctions.showManualFocusDialog();

                                                // Handle user's choice
                                                try {
                                                    switch (result) {
                                                        case RETRY_AUTOFOCUS:
                                                            socketClient.acknowledgeManualFocus();
                                                            logger.info("User chose to retry autofocus");
                                                            break;
                                                        case USE_CURRENT_FOCUS:
                                                            socketClient.skipAutofocusRetry();
                                                            logger.info("User chose to use current focus");
                                                            break;
                                                        case CANCEL_ACQUISITION:
                                                            socketClient.cancelAcquisition();
                                                            logger.info("User chose to cancel acquisition");
                                                            break;
                                                    }
                                                } catch (IOException e) {
                                                    logger.error("Failed to send manual focus response", e);
                                                }
                                            } finally {
                                                handlingManualFocus.set(false);
                                                latch.countDown();
                                            }
                                        });

                                        // Block until dialog is closed and acknowledged
                                        // Periodically check progress to prevent timeout during manual focus
                                        try {
                                            while (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                                                // Ping server every 30 seconds to prevent timeout
                                                try {
                                                    socketClient.getAcquisitionProgress();
                                                    logger.debug("Keepalive ping during manual focus");
                                                } catch (IOException e) {
                                                    logger.warn("Failed to ping server during manual focus", e);
                                                }
                                            }
                                        } catch (InterruptedException e) {
                                            logger.error("Interrupted while waiting for manual focus", e);
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                } catch (IOException e) {
                                    logger.warn("Failed to check manual focus status: {}", e.getMessage());
                                }

                                // Check for acquisition metadata file (only once)
                                if (!metadataRead.get() && progressDialog != null) {
                                    java.nio.file.Path metadataPath = java.nio.file.Paths.get(tileDirPath, "acquisition_metadata.txt");
                                    if (java.nio.file.Files.exists(metadataPath)) {
                                        metadataRead.set(true);
                                        try {
                                            java.util.List<String> lines = java.nio.file.Files.readAllLines(metadataPath);
                                            for (String line : lines) {
                                                if (line.startsWith("timing_window_size=")) {
                                                    int timingWindowSize = Integer.parseInt(line.substring("timing_window_size=".length()));
                                                    logger.info("Read timing window size from acquisition metadata: {} tiles", timingWindowSize);
                                                    Platform.runLater(() -> progressDialog.setTimingWindowSize(timingWindowSize));
                                                    break;
                                                }
                                            }
                                        } catch (Exception e) {
                                            logger.warn("Failed to read acquisition metadata: {}", e.getMessage());
                                        }
                                    }
                                }
                            },
                            500,    // Poll every 500ms for responsive UI
                            ACQUISITION_TIMEOUT_MS
                    );

            // Check final state
            switch (finalState) {
                case COMPLETED:
                    logger.info("Acquisition completed successfully for {}", annotation.getName());
                    return true;

                case CANCELLED:
                    logger.warn("Acquisition was cancelled for {}", annotation.getName());
                    showCancellationNotification();
                    // Check if cancellation came from dual progress dialog
                    if (progressDialog != null && progressDialog.isCancelled()) {
                        logger.info("Cancellation was initiated via dual progress dialog");
                    }
                    return false;

                case FAILED:
                    // Get detailed failure message from server
                    String failureMessage = socketClient.getLastFailureMessage();
                    String errorDetails = failureMessage != null ? failureMessage : "Unknown server error";
                    logger.error("Server acquisition failed: {}", errorDetails);
                    throw new RuntimeException("Acquisition failed on server: " + errorDetails);

                default:
                    logger.warn("Unexpected acquisition state: {}", finalState);
                    return false;
            }

        } catch (InterruptedException e) {
            logger.error("Acquisition monitoring interrupted", e);
            throw new RuntimeException(e);
        } finally {
            // No individual progress handle to close - dual dialog manages its own lifecycle
        }
    }

    /**
     * Launches stitching for a completed acquisition.
     *
     * <p>Stitching is performed asynchronously on a dedicated thread to:
     * <ul>
     *   <li>Allow the next acquisition to start immediately</li>
     *   <li>Prevent UI blocking during intensive stitching operations</li>
     *   <li>Process stitching jobs sequentially to manage resources</li>
     * </ul>
     *
     * @param annotation The annotation that was acquired
     * @param angleExposures Rotation angles used in acquisition
     */
    private void launchStitching(PathObject annotation,
                                 List<AngleExposure> angleExposures) {

        // Get required parameters for stitching
        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

        // Use the same hardware-specific pixel size calculation as was used for acquisition
        String baseModality = state.sample.modality().replaceAll("(_\\d+)$", "");
        String objective = state.sample.objective();
        String detector = state.sample.detector();
        
        double WSI_pixelSize_um;
        try {
            WSI_pixelSize_um = configManager.getModalityPixelSize(baseModality, objective, detector);
            logger.info("Using stitching WSI pixel size for {}/{}/{}: {} µm", 
                    baseModality, objective, detector, WSI_pixelSize_um);
        } catch (IllegalArgumentException e) {
            logger.error("Failed to determine pixel size for stitching: {}", e.getMessage());
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "Cannot determine pixel size for hardware: " + baseModality + "/" + objective + "/" + detector +
                                    "\n\nPlease check configuration. This should match the acquisition hardware settings.",
                            "Configuration Error"
                    )
            );
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        ModalityHandler handler = ModalityRegistry.getHandler(state.sample.modality());

        // Calculate offset for this annotation (for metadata)
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(
                annotation, state.transform);
        logger.info("Annotation {} offset from slide corner: ({}, {}) µm",
                annotation.getName(), offset[0], offset[1]);

        // Create stitching future
        CompletableFuture<Void> stitchFuture = StitchingHelper.performAnnotationStitching(
                annotation,
                state.sample,
                state.projectInfo.getImagingModeWithIndex(),
                angleExposures,
                WSI_pixelSize_um,
                gui,
                project,
                STITCH_EXECUTOR,
                handler
        );

        state.stitchingFutures.add(stitchFuture);
        logger.info("Launched stitching for annotation: {}", annotation.getName());
    }

    /**
     * Estimates tile count based on annotation bounds and camera FOV.
     * This is used as a fallback when TileConfiguration files are not available.
     *
     * @param annotation The annotation to estimate tiles for
     * @return Estimated number of tiles (minimum 1)
     */
    private int estimateTileCount(PathObject annotation) {
        try {
            // Get annotation bounds in image pixels
            double annWidth = annotation.getROI().getBoundsWidth();
            double annHeight = annotation.getROI().getBoundsHeight();

            // Get image pixel size
            double imagePixelSize = QuPathGUI.getInstance().getImageData()
                    .getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

            // Convert to microns
            double annWidthMicrons = annWidth * imagePixelSize;
            double annHeightMicrons = annHeight * imagePixelSize;

            // Get camera FOV from configuration
            double[] fovMicrons = MicroscopeController.getInstance().getCameraFOVFromConfig(
                    state.sample.modality(),
                    state.sample.objective(),
                    state.sample.detector());

            double fovWidthMicrons = fovMicrons[0];
            double fovHeightMicrons = fovMicrons[1];

            // Get overlap percentage
            double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
            double effectiveWidth = fovWidthMicrons * (1 - overlapPercent / 100.0);
            double effectiveHeight = fovHeightMicrons * (1 - overlapPercent / 100.0);

            // Calculate tile grid
            int tilesX = (int) Math.ceil(annWidthMicrons / effectiveWidth);
            int tilesY = (int) Math.ceil(annHeightMicrons / effectiveHeight);
            int totalTiles = tilesX * tilesY;

            logger.info("Tile estimate: annotation {}x{} um, FOV {}x{} um, overlap {}%, grid {}x{} = {} tiles",
                    Math.round(annWidthMicrons), Math.round(annHeightMicrons),
                    Math.round(fovWidthMicrons), Math.round(fovHeightMicrons),
                    Math.round(overlapPercent), tilesX, tilesY, totalTiles);

            // Return at least 1 tile to avoid division by zero
            return Math.max(1, totalTiles);

        } catch (Exception e) {
            logger.error("Failed to estimate tile count, defaulting to 1", e);
            return 1; // Safe default to prevent division by zero
        }
    }

    // UI notification methods

    /**
     * Shows initial notification about acquisition start.
     */
    private void showAcquisitionStartNotification(List<AngleExposure> angleExposures) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Acquisition Progress");
            alert.setHeaderText("Starting acquisition workflow");
            alert.setContentText(String.format(
                    "Processing %d annotations with %d rotation angles each.\n" +
                            "This may take several minutes per annotation.",
                    state.annotations.size(),
                    angleExposures == null || angleExposures.isEmpty() ? 1 : angleExposures.size()
            ));
            alert.show();

            // Auto-close after 3 seconds
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pause.setOnFinished(e -> alert.close());
            pause.play();
        });
    }

    /**
     * Shows progress notification for current annotation.
     */
    private void showProgressNotification(int current, int total, String annotationName) {
        Platform.runLater(() -> {
            String message = String.format("Acquiring annotation %d of %d: %s",
                    current, total, annotationName);
            Dialogs.showInfoNotification("Acquisition Progress", message);
        });
    }

    /**
     * Safely retrieves the dialog from the CompletableFuture with timeout handling.
     */
    private DualProgressDialog getDialogSafely(CompletableFuture<DualProgressDialog> dialogSetup) {
        try {
            return dialogSetup.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Failed to setup dual progress dialog", e);
            return null;
        }
    }

    /**
     * Shows error notification for failed acquisition.
     */
    private void showAcquisitionError(String annotationName, String errorMessage) {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition failed for " + annotationName + ":\n\n" + errorMessage,
                        "Acquisition Error"
                )
        );
    }

    /**
     * Shows notification that acquisition was cancelled.
     */
    private void showCancellationNotification() {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition was cancelled by user request",
                        "Acquisition Cancelled"
                )
        );
    }

}
