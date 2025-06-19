package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
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
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static qupath.ext.qpsc.utilities.MinorFunctions.firstLines;

/**
 * Runs the full  "bounding box " acquisition workflow:
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
     * Daemon threads so they won’t block JVM shutdown.
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stitching-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the  "boundingBox " menu command.
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
                    /**
                     * Modified section of BoundingBoxWorkflow.run() to handle rotation
                     * This replaces the section from "// 7) Prepare CLI arguments" onwards
                     */

                    // 7) Get rotation angles based on modality
                    logger.info("Checking rotation requirements for modality: {}", sample.modality());
                    RotationManager rotationManager = new RotationManager(sample.modality());

                    rotationManager.getRotationAngles(sample.modality())
                            .thenAccept(rotationAngles -> {
                                logger.info("Rotation angles for {}: {}", sample.modality(), rotationAngles);

                                // Process acquisition for each rotation angle (or once if no rotation)
                                List<Double> anglesToProcess = (rotationAngles != null && !rotationAngles.isEmpty())
                                        ? rotationAngles : List.of(Double.NaN);  // NaN indicates no rotation

                                List<CompletableFuture<Void>> allAcquisitions = new ArrayList<>();

                                for (Double angle : anglesToProcess) {
                                    // Create final copies for use in lambdas
                                    final Double finalAngle = angle;
                                    final String finalAngleSuffix = !Double.isNaN(angle)
                                            ? rotationManager.getAngleSuffix(sample.modality(), angle)
                                            : "";

                                    CompletableFuture<Void> angleAcquisition = CompletableFuture.runAsync(() -> {
                                        // Handle rotation if needed
                                        if (!Double.isNaN(finalAngle)) {
                                            try {
                                                logger.info("Setting rotation stage to {} degrees", finalAngle);
                                                MicroscopeController.getInstance().moveStageR(finalAngle);
                                                Thread.sleep(1000); // Wait for stage to settle
                                                logger.info("Rotation stage movement completed");
                                            } catch (Exception e) {
                                                logger.error("Failed to set rotation angle to {} degrees", finalAngle, e);
                                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                        "Failed to set rotation: " + e.getMessage(),
                                                        "Rotation Error"
                                                ));
                                                return; // Skip this angle
                                            }
                                        }

                                        // Prepare acquisition arguments
                                        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                        String boundsMode = "bounds";
                                        String finalModeWithIndex = modeWithIndex + finalAngleSuffix;

                                        List<String> cliArgs = List.of(
                                                res.getString("command.acquisitionWorkflow"),
                                                configFileLocation,
                                                projectsFolder,
                                                sample.sampleName(),
                                                finalModeWithIndex,
                                                boundsMode
                                        );

                                        logger.info("Starting acquisition with args: {}", cliArgs);

                                        // Run acquisition
                                        try {
                                            CliExecutor.ExecResult scanRes = CliExecutor.execComplexCommand(
                                                    60, // timeout
                                                    res.getString("acquisition.cli.progressRegex"),
                                                    cliArgs.toArray(new String[0])
                                            );

                                            if (scanRes.exitCode() != 0) {
                                                String stderr = scanRes.stderr().toString();
                                                // Check for MicroManager-specific errors
                                                if (stderr.contains("mmcorej") || stderr.contains("MicroManager")) {
                                                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                            "Failed to connect to MicroManager. Please verify that:\n" +
                                                                    "- All hardware is connected\n" +
                                                                    "- MicroManager can connect and run\n" +
                                                                    "- MicroManager is NOT currently in 'Live' mode\n" +
                                                                    "- Devices are configured correctly in MicroManager\n\n" +
                                                                    "Details:\n" + firstLines(stderr, 10),
                                                            "MicroManager Connection"
                                                    ));
                                                } else {
                                                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                            "Acquisition failed with exit code " + scanRes.exitCode() +
                                                                    ".\n\nError output:\n" + firstLines(stderr, 10),
                                                            "Acquisition"
                                                    ));
                                                }
                                                logger.error("Acquisition failed, stderr:\n{}", stderr);
                                                return;
                                            }

                                            // Success - schedule stitching
                                            CompletableFuture.runAsync(() -> {
                                                try {
                                                    Platform.runLater(() ->
                                                            qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                                    "Stitching",
                                                                    "Stitching " + sample.sampleName() +
                                                                            (finalAngleSuffix.isEmpty() ? "" : " at angle " + finalAngle) + "…"));

                                                    String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                                            projectsFolder,
                                                            sample.sampleName(),
                                                            finalModeWithIndex,
                                                            "bounds",
                                                            qupathGUI,
                                                            project,
                                                            String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                                            pixelSize,
                                                            1  // downsample
                                                    );

                                                    Platform.runLater(() ->
                                                            qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                                    "Stitching complete",
                                                                    "Output: " + outPath));

                                                } catch (Exception e) {
                                                    Platform.runLater(() ->
                                                            UIFunctions.notifyUserOfError(
                                                                    "Stitching failed:\n" + e.getMessage(),
                                                                    "Stitching Error"));
                                                }
                                            }, STITCH_EXECUTOR);

                                        } catch (Exception e) {
                                            logger.error("Acquisition failed", e);
                                            Platform.runLater(() ->
                                                    UIFunctions.notifyUserOfError(
                                                            "Failed to launch acquisition:\n" + e.getMessage(),
                                                            res.getString("acquisition.error.title")));
                                        }
                                    });

                                    allAcquisitions.add(angleAcquisition);
                                }

                                // After all angles are processed, handle cleanup
                                CompletableFuture.allOf(allAcquisitions.toArray(new CompletableFuture[0]))
                                        .thenRun(() -> {
                                            String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                                            if ("Delete".equals(handling)) {
                                                UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                                            } else if ("Zip".equals(handling)) {
                                                UtilityFunctions.zipTilesAndMove(tempTileDir);
                                                UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                                            }
                                        });

                            })
                            .exceptionally(ex -> {
                                logger.error("Rotation workflow failed", ex);
                                return null;
                            });
                });
    }
}
