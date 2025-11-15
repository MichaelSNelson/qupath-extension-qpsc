package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.ui.BoundingBoxController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.projects.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Runs the full "bounding box" acquisition workflow using socket communication only:
 * <ol>
 *   <li>Ask user for sample details (name, folder, modality)</li>
 *   <li>Ask user for bounding-box coordinates and focus flag</li>
 *   <li>Create/open a QuPath project and import any open image</li>
 *   <li>Compute tiling grid and write TileConfiguration.txt</li>
 *   <li>Launch microscope acquisition via socket (async)</li>
 *   <li>When acquisition finishes, schedule stitching (async)</li>
 *   <li>When stitching finishes, notify user</li>
 *   <li>Zip or delete tiles per preference</li>
 * </ol>
 */
public class BoundingBoxWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BoundingBoxWorkflow.class);

    /**
     * Executor for running potentially long-running stitching jobs.
     * Daemon threads so they won't block JVM shutdown.
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stitching-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the "boundingBox" menu command.
     * Kicks off dialogs, then acquisition, then stitching, all without blocking the UI.
     */
    public static void run() {
        var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

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

        // 1) Sample setup → 2) Bounding box dialogs
        SampleSetupController.showDialog()
                .thenCompose(sample -> BoundingBoxController.showDialog()
                        .thenApply(bb -> Map.entry(sample, bb))
                )
                .thenAccept(pair -> {
                    var sample = pair.getKey();
                    var bb = pair.getValue();

                    // 3) Read persistent prefs
                    String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
                    double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                    boolean invertX = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertY = QPPreferenceDialog.getInvertedYProperty();

                    // 4) Create/open the QuPath project
                    QuPathGUI qupathGUI = QPEx.getQuPath();
                    Map<String, Object> pd;
                    try {
                        // Use enhanced scan type for consistent folder structure
                        String enhancedModality = ObjectiveUtils.createEnhancedFolderName(
                                sample.modality(), sample.objective());
                        logger.info("Using enhanced modality for project: {} -> {}", 
                                sample.modality(), enhancedModality);
                        
                        pd = QPProjectFunctions.createAndOpenQuPathProject(
                                qupathGUI,
                                projectsFolder,
                                sample.sampleName(),
                                enhancedModality,
                                invertX,
                                invertY
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String tempTileDir = (String) pd.get("tempTileDirectory");
                    String modeWithIndex = (String) pd.get("imagingModeWithIndex");
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) pd.get("currentQuPathProject");

// 5) Get camera FOV using explicit hardware selections
                    logger.info("Getting camera FOV for modality: {}, objective: {}, detector: {}", 
                            sample.modality(), sample.objective(), sample.detector());

                    // Get configuration manager instance
                    String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

                    double frameWidthMicrons, frameHeightMicrons;
                    try {
                        double[] fov = configManager.getModalityFOV(sample.modality(), sample.objective(), sample.detector());
                        if (fov == null) {
                            throw new IOException("Could not calculate FOV for the selected hardware configuration");
                        }
                        frameWidthMicrons = fov[0];
                        frameHeightMicrons = fov[1];
                        logger.info("Camera FOV for {}/{}/{}: {} x {} microns", 
                                sample.modality(), sample.objective(), sample.detector(), frameWidthMicrons, frameHeightMicrons);
                    } catch (Exception e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to get camera FOV for modality " + sample.modality() + 
                                ", objective " + sample.objective() + 
                                ", detector " + sample.detector() + ": " + e.getMessage() +
                                        "\n\nPlease check configuration file.",
                                "FOV Error"
                        );
                        return; // Exit the workflow
                    }

                    // Get WSI pixel size for stitching metadata using explicit selections
                    double WSI_pixelSize_um;
                    try {
                        WSI_pixelSize_um = configManager.getModalityPixelSize(sample.modality(), sample.objective(), sample.detector());
                        logger.info("WSI pixel size for {}/{}/{}: {} µm", 
                                sample.modality(), sample.objective(), sample.detector(), WSI_pixelSize_um);
                    } catch (IllegalArgumentException e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to get pixel size for the selected hardware configuration: " + e.getMessage(),
                                "Configuration Error"
                        );
                        return; // Exit the workflow
                    }

                    // 6) Create tile configuration using new API
                    TilingRequest request = new TilingRequest.Builder()
                            .outputFolder(tempTileDir)
                            .modalityName(modeWithIndex)
                            .frameSize(frameWidthMicrons, frameHeightMicrons)  // Use microns directly
                            .overlapPercent(overlapPercent)
                            .boundingBox(bb.x1(), bb.y1(), bb.x2(), bb.y2())
                            .invertAxes(invertX, invertY)
                            .createDetections(false)  // No QuPath objects needed for bounding box
                            .build();

                    try {
                        TilingUtilities.createTiles(request);
                    } catch (IOException e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to create tile configuration: " + e.getMessage(),
                                "Tiling Error"
                        );
                        return;  // Exit the workflow
                    }

                    // 7) Get rotation angles based on modality
                    logger.info("Checking rotation requirements for modality: {}", sample.modality());
                    ModalityHandler modalityHandler = ModalityRegistry.getHandler(sample.modality());
                    
                    // Load profile-specific exposure defaults for PPM modality
                    if ("ppm".equals(sample.modality())) {
                        try {
                            qupath.ext.qpsc.modality.ppm.PPMPreferences.loadExposuresForProfile(
                                    sample.objective(), sample.detector());
                        } catch (Exception e) {
                            logger.warn("Failed to load PPM exposure defaults for {}/{}: {}", 
                                       sample.objective(), sample.detector(), e.getMessage());
                        }
                    }

                    double finalWSI_pixelSize_um = WSI_pixelSize_um;

                    // Handle angle overrides by pre-applying them and calling the dialog directly
                    CompletableFuture<List<qupath.ext.qpsc.modality.AngleExposure>> anglesFuture;
                    if (bb.angleOverrides() != null && !bb.angleOverrides().isEmpty() && "ppm".equals(sample.modality())) {
                        // For PPM with overrides, get the default angles first, apply overrides, then show dialog with corrected angles
                        qupath.ext.qpsc.modality.ppm.RotationManager rotationManager =
                            new qupath.ext.qpsc.modality.ppm.RotationManager(sample.modality(), sample.objective(), sample.detector());

                        // Get the default angles (this won't show any dialog since we're using the new method)
                        anglesFuture = rotationManager.getDefaultAnglesWithExposure(sample.modality())
                            .thenCompose(defaultAngles -> {
                                // Apply overrides to the default angles
                                List<qupath.ext.qpsc.modality.AngleExposure> overriddenAngles =
                                    modalityHandler.applyAngleOverrides(defaultAngles, bb.angleOverrides());

                                // Extract the overridden plus/minus angles for the dialog
                                double plusAngle = 7.0;  // fallback
                                double minusAngle = -7.0; // fallback
                                double uncrossedAngle = 90.0; // fallback

                                for (qupath.ext.qpsc.modality.AngleExposure ae : overriddenAngles) {
                                    if (ae.ticks() > 0 && ae.ticks() < 45) plusAngle = ae.ticks();
                                    else if (ae.ticks() < 0) minusAngle = ae.ticks();
                                    else if (ae.ticks() >= 45) uncrossedAngle = ae.ticks();
                                }

                                // Now show the dialog with the correct angles
                                return qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController.showDialog(
                                    plusAngle, minusAngle, uncrossedAngle,
                                    sample.modality(), sample.objective(), sample.detector())
                                    .thenApply(result -> {
                                        if (result == null) {
                                            logger.info("User cancelled angle selection dialog - acquisition will be cancelled");
                                            throw new RuntimeException("ANGLE_SELECTION_CANCELLED");
                                        }
                                        List<qupath.ext.qpsc.modality.AngleExposure> finalAngles = new ArrayList<>();
                                        for (qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController.AngleExposure ae : result.angleExposures) {
                                            finalAngles.add(new qupath.ext.qpsc.modality.AngleExposure(ae.angle, ae.exposureMs));
                                        }
                                        return finalAngles;
                                    });
                            });
                    } else {
                        // Normal flow - no overrides or not PPM
                        anglesFuture = modalityHandler.getRotationAngles(sample.modality(), sample.objective(), sample.detector())
                            .thenApply(angleExposures -> {
                                if (bb.angleOverrides() != null && !bb.angleOverrides().isEmpty()) {
                                    return modalityHandler.applyAngleOverrides(angleExposures, bb.angleOverrides());
                                }
                                return angleExposures;
                            });
                    }

                    anglesFuture
                            .thenAccept(angleExposures -> {
                                // Extract just the angles for logging and file counting
                                List<Double> rotationAngles = angleExposures.stream()
                                        .map(ae -> ae.ticks())
                                        .collect(Collectors.toList());
                                logger.info("Rotation angles for {}: {}", sample.modality(), rotationAngles);

                                // Prepare acquisition arguments (using configFileLocation from above)
                                String boundsMode = "bounds";

                                // ========== SOCKET-BASED ACQUISITION ==========
                                logger.info("Starting socket-based acquisition");


                                CompletableFuture<Void> acquisitionFuture = CompletableFuture.runAsync(() -> {
                                    try {
                                        logger.info("Building acquisition command for socket communication");

                                        // Use the existing configManager and configFileLocation from above

                                        // Build acquisition configuration using shared builder
                                        AcquisitionConfigurationBuilder.AcquisitionConfiguration config = 
                                            AcquisitionConfigurationBuilder.buildConfiguration(
                                                sample, 
                                                configFileLocation, 
                                                modeWithIndex, 
                                                boundsMode, 
                                                angleExposures, 
                                                projectsFolder, 
                                                finalWSI_pixelSize_um
                                            );

                                        logger.info("Starting acquisition with parameters:");
                                        logger.info("  Config: {}", configFileLocation);
                                        logger.info("  Projects folder: {}", projectsFolder);
                                        logger.info("  Sample: {}", sample.sampleName());
                                        logger.info("  Mode: {}", modeWithIndex);
                                        logger.info("  Region: {}", boundsMode);
                                        logger.info("  Hardware: obj={}, det={}, px={}µm", config.objective(), config.detector(), config.WSI_pixelSize_um());
                                        logger.info("  Autofocus: {} tiles, {} steps, {}µm range", config.afTiles(), config.afSteps(), config.afRange());
                                        logger.info("  Processing: {}", config.processingSteps());
                                        logger.info("  Angle-Exposure pairs: {}", angleExposures);
                                        String commandString = config.commandBuilder().buildSocketMessage();
                                        MinorFunctions.saveAcquisitionCommand(
                                                commandString,
                                                projectsFolder,
                                                sample.sampleName(),
                                                modeWithIndex,
                                                boundsMode
                                        );
                                        // Start acquisition via socket
                                        MicroscopeController.getInstance().startAcquisition(config.commandBuilder());

                                        logger.info("Socket acquisition command sent successfully");

                                        // Get the socket client for monitoring
                                        MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

                                        // Calculate expected files for progress bar
                                        int angleCount = Math.max(1, angleExposures.size());
                                        int expectedFiles = MinorFunctions.countTifEntriesInTileConfig(
                                                List.of(Paths.get(tempTileDir, boundsMode).toString())
                                        ) * angleCount;

                                        // Create progress counter that will be updated by the monitoring callback
                                        AtomicInteger progressCounter = new AtomicInteger(0);

                                        // Show progress bar with cancel button
                                        UIFunctions.ProgressHandle progressHandle = null;
                                        if (expectedFiles > 0) {
                                            progressHandle = UIFunctions.showProgressBarAsync(
                                                    progressCounter,
                                                    expectedFiles,
                                                    300000, // 5 minute timeout
                                                    true    // Show cancel button
                                            );

                                            // Set up cancel callback
                                            final UIFunctions.ProgressHandle finalHandle = progressHandle;
                                            progressHandle.setCancelCallback(v -> {
                                                logger.info("User requested acquisition cancellation");
                                                try {
                                                    // Send cancel command to server
                                                    if (socketClient.cancelAcquisition()) {
                                                        logger.info("Cancellation request sent to server");
                                                    }
                                                } catch (IOException e) {
                                                    logger.error("Failed to send cancel command", e);
                                                }
                                            });
                                        }

                                        // Wait a moment for the server to process the acquisition command
                                        Thread.sleep(1000);

                                        // Monitor acquisition with progress updates
                                        MicroscopeSocketClient.AcquisitionState finalState =
                                                socketClient.monitorAcquisition(
                                                        // Progress callback - update the progress counter
                                                        progress -> {
                                                            logger.debug("Acquisition progress: {}", progress);
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
                                            logger.info("Socket acquisition completed successfully");
                                        } else if (finalState == MicroscopeSocketClient.AcquisitionState.CANCELLED) {
                                            logger.warn("Socket acquisition was cancelled");
                                            Platform.runLater(() ->
                                                    UIFunctions.notifyUserOfError(
                                                            "Acquisition was cancelled by user request",
                                                            "Acquisition Cancelled"
                                                    )
                                            );
                                            // Don't throw exception for user cancellation
                                            return;
                                        } else if (finalState == MicroscopeSocketClient.AcquisitionState.FAILED) {
                                            // Get detailed failure message from server
                                            String failureMessage = socketClient.getLastFailureMessage();
                                            String errorDetails = failureMessage != null ? failureMessage : "Unknown server error";
                                            logger.error("Server acquisition failed: {}", errorDetails);
                                            throw new RuntimeException("Acquisition failed on server: " + errorDetails);
                                        } else {
                                            logger.warn("Acquisition ended in unexpected state: {}", finalState);
                                        }

                                    } catch (Exception e) {
                                        // Log the error and re-throw - outer exception handler will show dialog
                                        logger.error("Socket acquisition failed", e);
                                        // Re-throw original exception to preserve detailed error message
                                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e.getMessage(), e);
                                    }
                                });

                                // Continue with stitching after acquisition completes
                                acquisitionFuture.thenCompose(ignored -> {
                                    // Check if acquisition was cancelled
                                    if (ignored == null) {
                                        // Normal completion - proceed with stitching
                                        // Use StitchingHelper for unified stitching approach
                                        // StitchingHelper manages its own dialog, so no need to create one manually
                                        
                                        CompletableFuture<Void> stitchFuture = StitchingHelper.performRegionStitching(
                                                boundsMode,
                                                sample,
                                                modeWithIndex,
                                                angleExposures,
                                                finalWSI_pixelSize_um,
                                                qupathGUI,
                                                project,
                                                STITCH_EXECUTOR,
                                                modalityHandler
                                        ).thenRun(() -> {
                                            // Show completion notification when stitching is done
                                            Platform.runLater(() ->
                                                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                            "Stitching complete",
                                                            angleExposures.size() > 1
                                                                    ? "All angles stitched successfully"
                                                                    : "Stitching complete"));
                                        }).exceptionally(ex -> {
                                            // Handle stitching errors
                                            logger.error("Stitching failed", ex);
                                            Platform.runLater(() ->
                                                    UIFunctions.notifyUserOfError(
                                                            "Stitching failed:\n" + ex.getMessage(),
                                                            "Stitching Error"));
                                            return null;
                                        });

                                        // After stitching completes, handle cleanup
                                        stitchFuture.thenRun(() -> {
                                            String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                                            if ("Delete".equals(handling)) {
                                                TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                                            } else if ("Zip".equals(handling)) {
                                                TileProcessingUtilities.zipTilesAndMove(tempTileDir);
                                                TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                                            }
                                        });

                                        return stitchFuture;
                                    } else {
                                        // Acquisition was cancelled - skip stitching
                                        logger.info("Skipping stitching due to cancelled acquisition");
                                        return CompletableFuture.completedFuture(null);
                                    }
                                }).exceptionally(ex -> {
                                    logger.error("Socket workflow failed", ex);
                                    // Unwrap CompletionException to get actual error message
                                    Throwable cause = ex.getCause();
                                    String errorMessage = cause != null ? cause.getMessage() : ex.getMessage();
                                    Platform.runLater(() ->
                                            UIFunctions.notifyUserOfError(
                                                    errorMessage,
                                                    res.getString("acquisition.error.title")));
                                    return null;
                                });
                            }) // This closes the thenAccept from angleExposures
                            .exceptionally(ex -> {
                                // Check if cancellation was due to user choice (background mismatch or angle selection)
                                Throwable cause = ex.getCause();
                                String message = cause != null ? cause.getMessage() : ex.getMessage();

                                if (message != null && (message.contains("BACKGROUND_MISMATCH_CANCELLED") ||
                                                       message.contains("ANGLE_SELECTION_CANCELLED"))) {
                                    logger.info("Acquisition cancelled by user due to: {}", message);
                                    Platform.runLater(() ->
                                            qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                    "Acquisition Cancelled",
                                                    "Acquisition was cancelled by user request"));
                                } else {
                                    logger.error("Rotation workflow failed", ex);
                                    Platform.runLater(() ->
                                            UIFunctions.notifyUserOfError(
                                                    "Workflow error: " + ex.getMessage(),
                                                    res.getString("acquisition.error.title")));
                                }
                                return null;
                            });

                }); // This closes the thenAccept from the dialog chain
    }

}