package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.UnifiedAcquisitionController;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
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
 * Bounded acquisition workflow using the unified acquisition dialog.
 * <p>
 * This workflow provides a streamlined user experience with all configuration
 * visible in a single dialog, replacing the multi-dialog approach of the
 * original BoundingBoxWorkflow.
 * <p>
 * Workflow steps:
 * <ol>
 *   <li>Show unified acquisition dialog (sample, hardware, region, preview)</li>
 *   <li>Create/open a QuPath project</li>
 *   <li>Compute tiling grid and write TileConfiguration.txt</li>
 *   <li>Launch microscope acquisition via socket</li>
 *   <li>When acquisition finishes, perform stitching</li>
 *   <li>Handle tile cleanup per preference</li>
 * </ol>
 */
public class BoundedAcquisitionWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BoundedAcquisitionWorkflow.class);

    /**
     * Executor for running stitching jobs.
     * Daemon threads so they won't block JVM shutdown.
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bounded-stitching-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the "boundedAcquisition" menu command.
     * Shows the unified dialog, then handles acquisition and stitching.
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

        // Show the unified acquisition dialog
        UnifiedAcquisitionController.showDialog()
                .thenAccept(result -> {
                    if (result == null) {
                        logger.info("Unified acquisition dialog cancelled");
                        return;
                    }

                    logger.info("Starting bounded acquisition: sample={}, modality={}, " +
                               "bounds=({},{}) to ({},{})",
                            result.sampleName(), result.modality(),
                            result.x1(), result.y1(), result.x2(), result.y2());

                    // Read persistent prefs
                    String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
                    double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                    boolean invertX = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertY = QPPreferenceDialog.getInvertedYProperty();

                    // Create/open the QuPath project
                    QuPathGUI qupathGUI = QPEx.getQuPath();
                    Map<String, Object> pd;
                    try {
                        // Use enhanced scan type for consistent folder structure
                        String enhancedModality = ObjectiveUtils.createEnhancedFolderName(
                                result.modality(), result.objective());
                        logger.info("Using enhanced modality for project: {} -> {}",
                                result.modality(), enhancedModality);

                        // Check if a project is already open
                        Project<BufferedImage> existingProject = qupathGUI.getProject();
                        if (existingProject != null) {
                            logger.info("Using existing project: {}", existingProject.getPath());
                            pd = QPProjectFunctions.getCurrentProjectInformation(
                                    projectsFolder,
                                    result.sampleName(),
                                    enhancedModality
                            );
                        } else {
                            logger.info("Creating new project with sample name: {}", result.sampleName());
                            pd = QPProjectFunctions.createAndOpenQuPathProject(
                                    qupathGUI,
                                    projectsFolder,
                                    result.sampleName(),
                                    enhancedModality,
                                    invertX,
                                    invertY
                            );
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }

                    String tempTileDir = (String) pd.get("tempTileDirectory");
                    String modeWithIndex = (String) pd.get("imagingModeWithIndex");
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) pd.get("currentQuPathProject");

                    // Get camera FOV using explicit hardware selections
                    String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);

                    double frameWidthMicrons, frameHeightMicrons;
                    try {
                        double[] fov = configManager.getModalityFOV(
                                result.modality(), result.objective(), result.detector());
                        if (fov == null) {
                            throw new IOException("Could not calculate FOV for the selected hardware configuration");
                        }
                        frameWidthMicrons = fov[0];
                        frameHeightMicrons = fov[1];
                        logger.info("Camera FOV: {} x {} microns", frameWidthMicrons, frameHeightMicrons);
                    } catch (Exception e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to get camera FOV: " + e.getMessage(),
                                "FOV Error"
                        );
                        return;
                    }

                    // Get pixel size for stitching
                    double WSI_pixelSize_um;
                    try {
                        WSI_pixelSize_um = configManager.getModalityPixelSize(
                                result.modality(), result.objective(), result.detector());
                    } catch (IllegalArgumentException e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to get pixel size: " + e.getMessage(),
                                "Configuration Error"
                        );
                        return;
                    }

                    // Create tile configuration
                    TilingRequest request = new TilingRequest.Builder()
                            .outputFolder(tempTileDir)
                            .modalityName(modeWithIndex)
                            .frameSize(frameWidthMicrons, frameHeightMicrons)
                            .overlapPercent(overlapPercent)
                            .boundingBox(result.x1(), result.y1(), result.x2(), result.y2())
                            .invertAxes(invertX, invertY)
                            .createDetections(false)
                            .build();

                    try {
                        TilingUtilities.createTiles(request);
                    } catch (IOException e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to create tile configuration: " + e.getMessage(),
                                "Tiling Error"
                        );
                        return;
                    }

                    // Get rotation angles for the modality
                    ModalityHandler modalityHandler = ModalityRegistry.getHandler(result.modality());

                    // Load profile-specific exposure defaults for PPM
                    if ("ppm".equals(result.modality())) {
                        try {
                            qupath.ext.qpsc.modality.ppm.PPMPreferences.loadExposuresForProfile(
                                    result.objective(), result.detector());
                        } catch (Exception e) {
                            logger.warn("Failed to load PPM exposure defaults: {}", e.getMessage());
                        }
                    }

                    double finalWSI_pixelSize_um = WSI_pixelSize_um;

                    // Handle angle overrides
                    CompletableFuture<List<qupath.ext.qpsc.modality.AngleExposure>> anglesFuture;
                    if (result.angleOverrides() != null && !result.angleOverrides().isEmpty()
                            && "ppm".equals(result.modality())) {
                        // PPM with overrides
                        qupath.ext.qpsc.modality.ppm.RotationManager rotationManager =
                                new qupath.ext.qpsc.modality.ppm.RotationManager(
                                        result.modality(), result.objective(), result.detector());

                        anglesFuture = rotationManager.getDefaultAnglesWithExposure(result.modality())
                                .thenCompose(defaultAngles -> {
                                    List<qupath.ext.qpsc.modality.AngleExposure> overriddenAngles =
                                            modalityHandler.applyAngleOverrides(defaultAngles, result.angleOverrides());

                                    double plusAngle = 7.0, minusAngle = -7.0, uncrossedAngle = 90.0;
                                    for (qupath.ext.qpsc.modality.AngleExposure ae : overriddenAngles) {
                                        if (ae.ticks() > 0 && ae.ticks() < 45) plusAngle = ae.ticks();
                                        else if (ae.ticks() < 0) minusAngle = ae.ticks();
                                        else if (ae.ticks() >= 45) uncrossedAngle = ae.ticks();
                                    }

                                    return qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController.showDialog(
                                            plusAngle, minusAngle, uncrossedAngle,
                                            result.modality(), result.objective(), result.detector())
                                            .thenApply(dialogResult -> {
                                                if (dialogResult == null) {
                                                    throw new RuntimeException("ANGLE_SELECTION_CANCELLED");
                                                }
                                                List<qupath.ext.qpsc.modality.AngleExposure> finalAngles = new ArrayList<>();
                                                for (var ae : dialogResult.angleExposures) {
                                                    finalAngles.add(new qupath.ext.qpsc.modality.AngleExposure(
                                                            ae.angle, ae.exposureMs));
                                                }
                                                return finalAngles;
                                            });
                                });
                    } else {
                        // Normal flow
                        anglesFuture = modalityHandler.getRotationAngles(
                                result.modality(), result.objective(), result.detector())
                                .thenApply(angleExposures -> {
                                    if (result.angleOverrides() != null && !result.angleOverrides().isEmpty()) {
                                        return modalityHandler.applyAngleOverrides(
                                                angleExposures, result.angleOverrides());
                                    }
                                    return angleExposures;
                                });
                    }

                    anglesFuture.thenAccept(angleExposures -> {
                        List<Double> rotationAngles = angleExposures.stream()
                                .map(ae -> ae.ticks())
                                .collect(Collectors.toList());
                        logger.info("Rotation angles: {}", rotationAngles);

                        String boundsMode = "bounds";

                        // Start socket-based acquisition
                        CompletableFuture<Void> acquisitionFuture = CompletableFuture.runAsync(() -> {
                            try {
                                // Build acquisition configuration
                                // Create a SampleSetupResult-like object for the builder
                                var sampleResult = new qupath.ext.qpsc.ui.SampleSetupController.SampleSetupResult(
                                        result.sampleName(),
                                        result.projectsFolder(),
                                        result.modality(),
                                        result.objective(),
                                        result.detector()
                                );

                                AcquisitionConfigurationBuilder.AcquisitionConfiguration config =
                                        AcquisitionConfigurationBuilder.buildConfiguration(
                                                sampleResult,
                                                configFileLocation,
                                                modeWithIndex,
                                                boundsMode,
                                                angleExposures,
                                                projectsFolder,
                                                finalWSI_pixelSize_um
                                        );

                                logger.info("Starting acquisition - Sample: {}, Mode: {}, Angles: {}",
                                        result.sampleName(), modeWithIndex, angleExposures.size());

                                String commandString = config.commandBuilder().buildSocketMessage();
                                MinorFunctions.saveAcquisitionCommand(
                                        commandString,
                                        projectsFolder,
                                        result.sampleName(),
                                        modeWithIndex,
                                        boundsMode
                                );

                                MicroscopeController.getInstance().startAcquisition(config.commandBuilder());

                                // Monitor acquisition
                                MicroscopeSocketClient socketClient =
                                        MicroscopeController.getInstance().getSocketClient();

                                int angleCount = Math.max(1, angleExposures.size());
                                int expectedFiles = MinorFunctions.countTifEntriesInTileConfig(
                                        List.of(Paths.get(tempTileDir, boundsMode).toString())
                                ) * angleCount;

                                AtomicInteger progressCounter = new AtomicInteger(0);

                                UIFunctions.ProgressHandle progressHandle = null;
                                if (expectedFiles > 0) {
                                    progressHandle = UIFunctions.showProgressBarAsync(
                                            progressCounter, expectedFiles, 300000, true);

                                    final UIFunctions.ProgressHandle finalHandle = progressHandle;
                                    progressHandle.setCancelCallback(v -> {
                                        logger.info("User requested acquisition cancellation");
                                        try {
                                            socketClient.cancelAcquisition();
                                        } catch (IOException e) {
                                            logger.error("Failed to send cancel command", e);
                                        }
                                    });
                                }

                                Thread.sleep(1000);

                                MicroscopeSocketClient.AcquisitionState finalState =
                                        socketClient.monitorAcquisition(
                                                progress -> progressCounter.set(progress.current),
                                                500, 300000
                                        );

                                if (progressHandle != null) {
                                    progressHandle.close();
                                }

                                if (finalState == MicroscopeSocketClient.AcquisitionState.COMPLETED) {
                                    logger.info("Acquisition completed successfully");
                                } else if (finalState == MicroscopeSocketClient.AcquisitionState.CANCELLED) {
                                    logger.warn("Acquisition was cancelled");
                                    Platform.runLater(() ->
                                            UIFunctions.notifyUserOfError(
                                                    "Acquisition was cancelled",
                                                    "Acquisition Cancelled"
                                            )
                                    );
                                    return;
                                } else if (finalState == MicroscopeSocketClient.AcquisitionState.FAILED) {
                                    String failureMessage = socketClient.getLastFailureMessage();
                                    throw new RuntimeException("Acquisition failed: " +
                                            (failureMessage != null ? failureMessage : "Unknown error"));
                                }

                            } catch (Exception e) {
                                logger.error("Acquisition failed", e);
                                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                            }
                        });

                        // Handle stitching after acquisition
                        acquisitionFuture.thenCompose(ignored -> {
                            CompletableFuture<Void> stitchFuture = StitchingHelper.performRegionStitching(
                                    boundsMode,
                                    new qupath.ext.qpsc.ui.SampleSetupController.SampleSetupResult(
                                            result.sampleName(),
                                            result.projectsFolder(),
                                            result.modality(),
                                            result.objective(),
                                            result.detector()
                                    ),
                                    modeWithIndex,
                                    angleExposures,
                                    finalWSI_pixelSize_um,
                                    qupathGUI,
                                    project,
                                    STITCH_EXECUTOR,
                                    modalityHandler
                            ).thenRun(() -> {
                                Platform.runLater(() ->
                                        qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                "Stitching complete",
                                                angleExposures.size() > 1
                                                        ? "All angles stitched successfully"
                                                        : "Stitching complete"
                                        )
                                );
                            }).exceptionally(ex -> {
                                logger.error("Stitching failed", ex);
                                Platform.runLater(() ->
                                        UIFunctions.notifyUserOfError(
                                                "Stitching failed:\n" + ex.getMessage(),
                                                "Stitching Error"
                                        )
                                );
                                return null;
                            });

                            // Handle cleanup after stitching
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
                        }).exceptionally(ex -> {
                            logger.error("Workflow failed", ex);
                            Throwable cause = ex.getCause();
                            String errorMessage = cause != null ? cause.getMessage() : ex.getMessage();
                            Platform.runLater(() ->
                                    UIFunctions.notifyUserOfError(errorMessage, "Acquisition Error")
                            );
                            return null;
                        });

                    }).exceptionally(ex -> {
                        Throwable cause = ex.getCause();
                        String message = cause != null ? cause.getMessage() : ex.getMessage();

                        if (message != null && (message.contains("BACKGROUND_MISMATCH_CANCELLED") ||
                                               message.contains("ANGLE_SELECTION_CANCELLED"))) {
                            logger.info("Acquisition cancelled by user: {}", message);
                            Platform.runLater(() ->
                                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                                            "Acquisition Cancelled",
                                            "Acquisition was cancelled by user request"
                                    )
                            );
                        } else {
                            logger.error("Workflow failed", ex);
                            String msg = ex.getMessage();
                            if (msg == null || msg.isEmpty()) {
                                msg = cause != null ? cause.getMessage() : "Unknown error occurred";
                            }
                            final String finalErrorMsg = msg;
                            Platform.runLater(() ->
                                    UIFunctions.notifyUserOfError(finalErrorMsg, "Acquisition Error")
                            );
                        }
                        return null;
                    });

                });
    }
}
