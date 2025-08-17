package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import qupath.ext.qpsc.model.RotationManager;

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
                        pd = QPProjectFunctions.createAndOpenQuPathProject(
                                qupathGUI,
                                projectsFolder,
                                sample.sampleName(),
                                sample.modality(),
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

                    // 5) Get camera FOV from server
                    logger.info("Getting camera FOV for modality: {}", sample.modality());

                    double frameWidthMicrons, frameHeightMicrons;
                    try {
                        double[] fov = MicroscopeController.getInstance().getCameraFOVFromConfig(sample.modality());
                        frameWidthMicrons = fov[0];
                        frameHeightMicrons = fov[1];
                        logger.info("Camera FOV for {}: {} x {} microns", sample.modality(), frameWidthMicrons, frameHeightMicrons);
                    } catch (IOException e) {
                        UIFunctions.notifyUserOfError(
                                "Failed to get camera FOV for modality " + sample.modality() + ": " + e.getMessage() +
                                        "\n\nPlease check configuration file.",
                                "FOV Error"
                        );
                        return; // Exit the workflow
                    }

// Get pixel size for stitching metadata (still needed)
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                            QPPreferenceDialog.getMicroscopeConfigFileProperty());
                    double pixelSize = mgr.getDouble("imaging_mode", sample.modality(), "pixel_size_um");

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
                    RotationManager rotationManager = new RotationManager(sample.modality());

                    rotationManager.getRotationTicksWithExposure(sample.modality())
                            .thenApply(angleExposures -> {
                                // Check if we have angle overrides from the dialog
                                if (bb.angleOverrides() != null && !bb.angleOverrides().isEmpty()) {
                                    logger.info("Using user-specified PPM angles: plus={}, minus={}",
                                            bb.angleOverrides().get("plus"),
                                            bb.angleOverrides().get("minus"));

                                    // Create new angle exposures with user-specified angles
                                    List<RotationManager.TickExposure> overriddenExposures = new ArrayList<>();

                                    // Find and replace the angles while keeping exposures
                                    for (RotationManager.TickExposure ae : angleExposures) {
                                        if (ae.ticks < 0) {
                                            // Replace minus angle
                                            overriddenExposures.add(new RotationManager.TickExposure(
                                                    bb.angleOverrides().get("minus"), ae.exposureMs));
                                        } else if (ae.ticks > 0 && ae.ticks < 90) {
                                            // Replace plus angle
                                            overriddenExposures.add(new RotationManager.TickExposure(
                                                    bb.angleOverrides().get("plus"), ae.exposureMs));
                                        } else {
                                            // Keep 0 and 90 degrees unchanged
                                            overriddenExposures.add(ae);
                                        }
                                    }

                                    logger.info("Overridden angle exposures: {}", overriddenExposures);
                                    return overriddenExposures;
                                }
                                return angleExposures;
                            })
                            .thenAccept(angleExposures -> {
                                // Extract just the angles for logging and file counting
                                List<Double> rotationAngles = angleExposures.stream()
                                        .map(ae -> ae.ticks)
                                        .collect(Collectors.toList());
                                logger.info("Rotation angles for {}: {}", sample.modality(), rotationAngles);

                                // Prepare acquisition arguments
                                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                String boundsMode = "bounds";

                                // ========== SOCKET-BASED ACQUISITION ==========
                                logger.info("Starting socket-based acquisition");

                                CompletableFuture<Void> acquisitionFuture = CompletableFuture.runAsync(() -> {
                                    try {
                                        logger.info("Building acquisition command for socket communication");

                                        // Build acquisition command with all necessary parameters
                                        AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                                                .yamlPath(configFileLocation)
                                                .projectsFolder(projectsFolder)
                                                .sampleLabel(sample.sampleName())
                                                .scanType(modeWithIndex)
                                                .regionName(boundsMode)
                                                .angleExposures(angleExposures);

                                        // Add any additional modality-specific parameters here in the future
                                        // For example:
                                        // if (sample.modality().startsWith("LaserScan")) {
                                        //     acquisitionBuilder.laserPower(25.0).laserWavelength(488);
                                        // }

                                        logger.info("Starting acquisition with parameters:");
                                        logger.info("  Config: {}", configFileLocation);
                                        logger.info("  Projects folder: {}", projectsFolder);
                                        logger.info("  Sample: {}", sample.sampleName());
                                        logger.info("  Mode: {}", modeWithIndex);
                                        logger.info("  Region: {}", boundsMode);
                                        logger.info("  Angle-Exposure pairs: {}", angleExposures);

                                        // Start acquisition via socket
                                        MicroscopeController.getInstance().startAcquisition(acquisitionBuilder);

                                        logger.info("Socket acquisition command sent successfully");

                                        // Get the socket client for monitoring
                                        MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

                                        // Calculate expected files for progress bar
                                        int expectedFiles = MinorFunctions.countTifEntriesInTileConfig(
                                                List.of(Paths.get(tempTileDir, boundsMode).toString())
                                        ) * angleExposures.size();

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
                                            throw new RuntimeException("Socket acquisition failed on server");
                                        } else {
                                            logger.warn("Acquisition ended in unexpected state: {}", finalState);
                                        }

                                    } catch (Exception e) {
                                        logger.error("Socket acquisition failed", e);
                                        Platform.runLater(() ->
                                                UIFunctions.notifyUserOfError(
                                                        "Socket acquisition failed: " + e.getMessage(),
                                                        "Acquisition Error"
                                                )
                                        );
                                        throw new RuntimeException("Socket acquisition failed", e);
                                    }
                                });

                                // Continue with stitching after acquisition completes
                                acquisitionFuture.thenCompose(ignored -> {
                                    // Check if acquisition was cancelled
                                    if (ignored == null) {
                                        // Normal completion - proceed with stitching
                                        CompletableFuture<Void> stitchFuture = CompletableFuture.runAsync(() -> {
                                            try {
                                                Platform.runLater(() ->
                                                        qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                                "Stitching",
                                                                "Stitching " + sample.sampleName() + " for all angles…"));

                                                String matchingPattern = (rotationAngles != null && !rotationAngles.isEmpty()) ? "." : boundsMode;

                                                String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                                        projectsFolder,
                                                        sample.sampleName(),
                                                        modeWithIndex,
                                                        boundsMode,
                                                        matchingPattern,
                                                        qupathGUI,
                                                        project,
                                                        String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                                        pixelSize,
                                                        1
                                                );

                                                logger.info("Stitching completed. Last output path: {}", outPath);

                                                Platform.runLater(() ->
                                                        qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                                "Stitching complete",
                                                                "All angles stitched successfully"));

                                            } catch (Exception e) {
                                                logger.error("Stitching failed", e);
                                                Platform.runLater(() ->
                                                        UIFunctions.notifyUserOfError(
                                                                "Stitching failed:\n" + e.getMessage(),
                                                                "Stitching Error"));
                                            }
                                        }, STITCH_EXECUTOR);

                                        // After stitching completes, handle cleanup
                                        stitchFuture.thenRun(() -> {
                                            String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                                            if ("Delete".equals(handling)) {
                                                UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                                            } else if ("Zip".equals(handling)) {
                                                UtilityFunctions.zipTilesAndMove(tempTileDir);
                                                UtilityFunctions.deleteTilesAndFolder(tempTileDir);
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
                                    Platform.runLater(() ->
                                            UIFunctions.notifyUserOfError(
                                                    "Workflow error: " + ex.getMessage(),
                                                    res.getString("acquisition.error.title")));
                                    return null;
                                });
                            }) // This closes the thenAccept from angleExposures
                            .exceptionally(ex -> {
                                logger.error("Rotation workflow failed", ex);
                                return null;
                            });

                }); // This closes the thenAccept from the dialog chain
    }
}