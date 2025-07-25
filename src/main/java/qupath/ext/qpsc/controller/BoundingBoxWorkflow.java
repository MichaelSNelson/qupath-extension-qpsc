package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static qupath.ext.qpsc.utilities.MinorFunctions.firstLines;

/**
 * Runs the full "bounding box" acquisition workflow:
 * <ol>
 *   <li>Ask user for sample details (name, folder, modality)</li>
 *   <li>Ask user for bounding-box coordinates and focus flag</li>
 *   <li>Create/open a QuPath project and import any open image</li>
 *   <li>Compute tiling grid and write TileConfiguration.txt</li>
 *   <li>Launch microscope CLI to acquire tiles (async)</li>
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

                    //TODO FIX THIS?? not sure if these are the right properties to use here.
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

                    // 5) Compute frame size in microns from YAML
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                    double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
                    //String detectorID  = mgr.getString("imagingMode", sample.modality(), "detector");

                    // --- Get detector properties (width/height) from resources_LOCI ---
                    int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
                    int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

                    double frameWidth = pixelSize * cameraWidth;
                    double frameHeight = pixelSize * cameraHeight;

                    // 6) Create tile configuration using new API
                    TilingRequest request = new TilingRequest.Builder()
                            .outputFolder(tempTileDir)
                            .modalityName(modeWithIndex)
                            .frameSize(frameWidth, frameHeight)
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

                                    // TODO: Handle angle overrides with exposure times
                                    // For now, log a warning
                                    logger.warn("Angle overrides not fully implemented with exposure times");
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

                                List<String> cliArgs = new ArrayList<>(List.of(
                                        res.getString("command.acquisitionWorkflow"),
                                        configFileLocation,
                                        projectsFolder,
                                        sample.sampleName(),
                                        modeWithIndex,
                                        boundsMode
                                ));

                                // Add angles parameter if rotation is needed
                                if (angleExposures != null && !angleExposures.isEmpty()) {
                                    // Format as parenthesized string with angle,exposure pairs
                                    String anglesStr = angleExposures.stream()
                                            .map(ae -> ae.ticks + "," + ae.exposureMs)
                                            .collect(Collectors.joining(" ", "(", ")"));
                                    cliArgs.add(anglesStr);
                                }

                                logger.info("Starting acquisition with args: {}", cliArgs);

                                // Run acquisition ONCE with all angles
                                CompletableFuture<CliExecutor.ExecResult> acquisitionFuture = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        // Calculate proper timeout - 60 seconds base + 60 per angle
                                        int timeoutSeconds = 60;
                                        if (rotationAngles != null && !rotationAngles.isEmpty()) {
                                            timeoutSeconds = 60 * rotationAngles.size();
                                        }

                                        CliExecutor.ExecResult scanRes = CliExecutor.execComplexCommand(
                                                timeoutSeconds,
                                                res.getString("acquisition.cli.progressRegex"),
                                                cliArgs.toArray(new String[0])
                                        );

                                        return scanRes;
                                    } catch (Exception e) {
                                        logger.error("Acquisition failed", e);
                                        throw new RuntimeException("Failed to launch acquisition", e);
                                    }
                                });

                                // Wait for acquisition to complete (monitoring TIF files)
                                acquisitionFuture.thenCompose(scanRes -> {
                                    if (scanRes.exitCode() != 0) {
                                        String stderr = scanRes.stderr().toString();
                                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                "Acquisition failed with exit code " + scanRes.exitCode() +
                                                        ".\n\nError output:\n" + firstLines(stderr, 10),
                                                "Acquisition"
                                        ));
                                        logger.error("Acquisition failed, stderr:\n{}", stderr);
                                        return CompletableFuture.completedFuture(null);
                                    }

                                    // Check if all files were found
                                    if (!scanRes.allFilesFound()) {
                                        logger.warn("Acquisition process completed but not all expected files were found");
                                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                "Acquisition may have failed - not all expected tile files were created.\n" +
                                                        "Check the acquisition log for details.",
                                                "Incomplete Acquisition"
                                        ));
                                        // Continue anyway - partial stitching might be useful
                                    } else {
                                        logger.info("Acquisition completed successfully - all expected files found");
                                    }

                                    // Now start stitching
                                    CompletableFuture<Void> stitchFuture = CompletableFuture.runAsync(() -> {
                                        try {
                                            Platform.runLater(() ->
                                                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                            "Stitching",
                                                            "Stitching " + sample.sampleName() + " for all angles…"));

                                            // For rotation workflows, pass "." to match all angle subfolders
                                            String matchingPattern = (rotationAngles != null && !rotationAngles.isEmpty()) ? "." : boundsMode;

                                            String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                                    projectsFolder,
                                                    sample.sampleName(),
                                                    modeWithIndex,
                                                    boundsMode,
                                                    matchingPattern,  // "." will match all angle subfolders
                                                    qupathGUI,
                                                    project,
                                                    String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                                    pixelSize,
                                                    1
                                            );

                                            // Note: outPath will be the last stitched file path when multiple angles are processed
                                            // The stitchImagesAndUpdateProject method handles importing all generated files to the project
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

                                }).exceptionally(ex -> {
                                    logger.error("Workflow failed", ex);
                                    Platform.runLater(() ->
                                            UIFunctions.notifyUserOfError(
                                                    "Workflow error: " + ex.getMessage(),
                                                    res.getString("acquisition.error.title")));
                                    return null;
                                });

                            }) // This closes the thenAccept from rotationAngles
                            .exceptionally(ex -> {
                                logger.error("Rotation workflow failed", ex);
                                return null;
                            });

                }); // This closes another thenAccept
    } // This closes the thenAccept from the dialog chain
}