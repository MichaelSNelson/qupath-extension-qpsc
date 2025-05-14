package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.ext.qpsc.ui.BoundingBoxController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
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
                .thenCompose(sample ->
                        BoundingBoxController.showDialog()
                                .thenApply(bb -> Map.entry(sample, bb))
                )
                .thenAccept(pair -> {
                    var sample = pair.getKey();
                    var bb     = pair.getValue();

                    // 3) Read persistent prefs
                    String projectsFolder    = QPPreferenceDialog.getProjectsFolderProperty();
                    double overlapPercent    = QPPreferenceDialog.getTileOverlapPercentProperty();
                    boolean invertX          = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertY          = QPPreferenceDialog.getInvertedYProperty();

                    // 4) Create/open the QuPath project and import any open image
                    QuPathGUI qupathGUI = QPEx.getQuPath();
                    Map<String,Object> pd;
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
                    String tempTileDir        = (String) pd.get("tempTileDirectory");
                    String modeWithIndex      = (String) pd.get("imagingModeWithIndex");
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) pd.get("currentQuPathProject");

                    // 5) Compute frame size in microns from YAML
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                    System.out.println(mgr.getAllConfig());  // Check if YAML content is printed
                    System.out.println(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                    double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
                    System.out.println("Pixel size: " +pixelSize);

                    //String detectorID = mgr.getString("imagingMode", sample.modality(), "detector");

                    int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");

                    int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

                    double frameWidth  = pixelSize * cameraWidth;
                    double frameHeight = pixelSize * cameraHeight;

                    // 6) Write tile grid
                    UtilityFunctions.performTilingAndSaveConfiguration(
                            tempTileDir,
                            modeWithIndex,
                            frameWidth, frameHeight,
                            overlapPercent,
                            List.of(bb.x1(), bb.y1(), bb.x2(), bb.y2()),
                            /*createTiles=*/ true,
                            Collections.emptyList(),
                            invertY, invertX);

                    // 7) Launch acquisition CLI off the FX thread
                    CompletableFuture<CliExecutor.ExecResult> scanFuture = CompletableFuture.supplyAsync(() -> {
                        try {
                            return CliExecutor.execComplexCommand(
                                    /* timeoutSec= */ 300,
                                    /* progressRegex= */ "tiles done",
                                    /* args= */ "acquireTiles", tempTileDir);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    });

                    // 8) When scan completes, schedule stitching
                    scanFuture.thenAccept(scanRes -> {
                        if (scanRes.exitCode() == 0) {
                            // Kick off stitching on your stitching executor
                            CompletableFuture.runAsync(() -> {
                                try {
                                    // Show a quick "starting " notification
                                    Platform.runLater(() ->
                                            qupath.fx.dialogs.Dialogs.showInfoNotification(
                                                    "Stitching",
                                                    "Stitching " + sample.sampleName() + "…"));

                                    // **NEW**: match updated signature
                                    String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                            projectsFolder,                            // root projects folder
                                            sample.sampleName(),                       // the sample subfolder
                                            modeWithIndex,                      // e.g. "4x_bf_1"
                                            "bounds",                                  // annotationName
                                            qupathGUI,                                 // QuPathGUI instance
                                            project,                                   // your Project<BufferedImage>
                                            String.valueOf(QPPreferenceDialog                           // fetch compression choice
                                                    .getCompressionTypeProperty()),                               // e.g. "DEFAULT "
                                            pixelSize,                                 // µm per pixel
                                            /*downsample=*/ 1                          // your downsample factor
                                    );

                                    // Notify on success
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
                        }

                    }).exceptionally(ex -> {
                        Platform.runLater(() ->
                                UIFunctions.notifyUserOfError(
                                        "Failed to launch acquisition:\n" + ex.getCause().getMessage(),
                                        res.getString("acquisition.error.title")));
                        return null;
                    });

                    // 9) Immediately return to UI — user can start another workflow now!

                    // 10) Finally, schedule cleanup *after* scan & stitch (optional)
                    scanFuture.thenRun(() -> {
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
                    // user cancelled or dialog error
                    logger.info("Bounding-box workflow aborted: {}", ex.getMessage());
                    return null;
                });
    }
}
