package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.StitchingBlockingDialog;
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
                    modalityHandler.getRotationAngles(sample.modality())
                            .thenApply(angleExposures -> {
                                if (bb.angleOverrides() != null && !bb.angleOverrides().isEmpty()) {
                                    return modalityHandler.applyAngleOverrides(angleExposures, bb.angleOverrides());
                                }
                                return angleExposures;
                            })
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

                                        // Use explicit hardware selections from sample setup
                                        String objective = sample.objective();
                                        String detector = sample.detector();
                                        double WSI_pixelSize_um_forAcq = finalWSI_pixelSize_um; // Already calculated above

                                        // Get the modality base (without index)
                                        String baseModality = sample.modality();

                                        // Get background correction settings
                                        boolean bgEnabled = configManager.getBoolean("modalities", baseModality, "background_correction", "enabled");
                                        String bgMethod = configManager.getString("modalities", baseModality, "background_correction", "method");
                                        String bgBaseFolder = configManager.getString("modalities", baseModality, "background_correction", "base_folder");
                                        
                                        // Construct detector-specific background folder path
                                        String bgFolder = null;
                                        if (bgEnabled && bgBaseFolder != null) {
                                            // Extract magnification from objective (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x")
                                            String magnification = extractMagnificationFromObjective(objective);
                                            // Build path: base_folder/detector/modality/magnification
                                            bgFolder = Paths.get(bgBaseFolder, detector, baseModality, magnification).toString();
                                            logger.info("Constructed background folder path: {}", bgFolder);
                                        }

                                        // Get autofocus parameters
                                        Map<String, Object> afParams = configManager.getAutofocusParams(objective);
                                        int afTiles = 5;    // defaults
                                        int afSteps = 11;
                                        double afRange = 50.0;

                                        if (afParams != null) {
                                            if (afParams.get("n_tiles") instanceof Number) {
                                                afTiles = ((Number) afParams.get("n_tiles")).intValue();
                                            }
                                            if (afParams.get("n_steps") instanceof Number) {
                                                afSteps = ((Number) afParams.get("n_steps")).intValue();
                                            }
                                            if (afParams.get("search_range_um") instanceof Number) {
                                                afRange = ((Number) afParams.get("search_range_um")).doubleValue();
                                            }
                                        }

                                        // Determine processing pipeline based on detector properties
                                        List<String> processingSteps = new ArrayList<>();
                                        if (configManager.detectorRequiresDebayering(detector)) {
                                            processingSteps.add("debayer");
                                        }
                                        if (bgEnabled && bgFolder != null) {
                                            processingSteps.add("background_correction");
                                        }
                                        // Get white balance setting from config for this profile
                                        boolean whiteBalanceEnabled = configManager.isWhiteBalanceEnabled(baseModality, objective, detector);
                                        logger.debug("White balance enabled for {}/{}/{}: {}",
                                                baseModality, objective, detector, whiteBalanceEnabled);

                                        // Build enhanced acquisition command
                                        AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                                                .yamlPath(configFileLocation)
                                                .projectsFolder(projectsFolder)
                                                .sampleLabel(sample.sampleName())
                                                .scanType(modeWithIndex)
                                                .regionName(boundsMode)
                                                .angleExposures(angleExposures)
                                                .hardware(objective, detector, WSI_pixelSize_um_forAcq)
                                                .autofocus(afTiles, afSteps, afRange)
                                                .processingPipeline(processingSteps)
                                                .whiteBalance(whiteBalanceEnabled);

                                        // Only add background correction if enabled and configured
                                        if (bgEnabled && bgMethod != null && bgFolder != null) {
                                            acquisitionBuilder.backgroundCorrection(true, bgMethod, bgFolder);
                                        }

                                        logger.info("Starting acquisition with parameters:");
                                        logger.info("  Config: {}", configFileLocation);
                                        logger.info("  Projects folder: {}", projectsFolder);
                                        logger.info("  Sample: {}", sample.sampleName());
                                        logger.info("  Mode: {}", modeWithIndex);
                                        logger.info("  Region: {}", boundsMode);
                                        logger.info("  Hardware: obj={}, det={}, px={}µm", objective, detector, WSI_pixelSize_um_forAcq);
                                        logger.info("  Autofocus: {} tiles, {} steps, {}µm range", afTiles, afSteps, afRange);
                                        logger.info("  Processing: {}", processingSteps);
                                        logger.info("  Angle-Exposure pairs: {}", angleExposures);
                                        String commandString = acquisitionBuilder.buildSocketMessage();
                                        MinorFunctions.saveAcquisitionCommand(
                                                commandString,
                                                projectsFolder,
                                                sample.sampleName(),
                                                modeWithIndex,
                                                boundsMode
                                        );
                                        // Start acquisition via socket
                                        MicroscopeController.getInstance().startAcquisition(acquisitionBuilder);

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
                                        // Show blocking dialog before starting stitching
                                        StitchingBlockingDialog blockingDialog = null;
                                        try {
                                            // Must create blocking dialog on JavaFX thread
                                            final StitchingBlockingDialog[] dialogRef = {null};
                                            Platform.runLater(() -> {
                                                dialogRef[0] = StitchingBlockingDialog.show(sample.sampleName());
                                            });
                                            // Wait briefly for dialog creation
                                            Thread.sleep(100);
                                            blockingDialog = dialogRef[0];
                                        } catch (Exception e) {
                                            logger.warn("Failed to create stitching blocking dialog", e);
                                        }
                                        
                                        final StitchingBlockingDialog finalDialog = blockingDialog;
                                        
                                        CompletableFuture<Void> stitchFuture = CompletableFuture.runAsync(() -> {
                                            try {
                                                if (finalDialog != null) {
                                                    finalDialog.updateStatus("Initializing stitching operation...");
                                                }

                                                String matchingPattern;
                                                if (angleExposures.size() > 1) {
                                                    // Multi-angle: use "." to match all angle directories
                                                    matchingPattern = ".";
                                                } else {
                                                    // Single-angle: use the specific angle directory name
                                                    double angle = angleExposures.get(0).ticks();
                                                    matchingPattern = String.valueOf(angle);
                                                    logger.info("Single-angle acquisition: using matching pattern '{}' for angle {}", matchingPattern, angle);
                                                }

                                                if (finalDialog != null) {
                                                    finalDialog.updateStatus(angleExposures.size() > 1 
                                                            ? "Stitching " + sample.sampleName() + " for all angles..."
                                                            : "Stitching " + sample.sampleName() + "...");
                                                }

                                                String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                                                        projectsFolder,
                                                        sample.sampleName(),
                                                        modeWithIndex,
                                                        boundsMode,
                                                        matchingPattern,
                                                        qupathGUI,
                                                        project,
                                                        String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                                        finalWSI_pixelSize_um,
                                                        1,
                                                        modalityHandler,
                                                        null
                                                );

                                                logger.info("Stitching completed. Last output path: {}", outPath);

                                                // Close blocking dialog and show completion notification
                                                if (finalDialog != null) {
                                                    finalDialog.close();
                                                }
                                                
                                                Platform.runLater(() ->
                                                        qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                                "Stitching complete",
                                                                angleExposures.size() > 1
                                                                        ? "All angles stitched successfully"
                                                                        : "Stitching complete"));

                                            } catch (Exception e) {
                                                logger.error("Stitching failed", e);
                                                
                                                // Close blocking dialog with error
                                                if (finalDialog != null) {
                                                    finalDialog.closeWithError(e.getMessage());
                                                } else {
                                                    Platform.runLater(() ->
                                                            UIFunctions.notifyUserOfError(
                                                                    "Stitching failed:\n" + e.getMessage(),
                                                                    "Stitching Error"));
                                                }
                                            }
                                        }, STITCH_EXECUTOR);

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

    /**
     * Extract magnification from objective identifier.
     * Examples:
     * - "LOCI_OBJECTIVE_OLYMPUS_10X_001" -> "10x"
     * - "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x"
     * - "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001" -> "40x"
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective == null) return "unknown";
        
        // Look for pattern like "10X", "20X", "40X"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)X", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(objective);
        
        if (matcher.find()) {
            return matcher.group(1).toLowerCase() + "x";  // "20X" -> "20x"
        }
        
        // Fallback: return "unknown" if pattern not found
        return "unknown";
    }
}