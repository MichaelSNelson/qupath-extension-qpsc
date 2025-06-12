package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.ui.ExistingImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import javax.script.ScriptException;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow
 *
 * <p>Orchestrates the workflow for microscope acquisition using an existing (low-res) image in QuPath.
 * Guides the user through project setup, pixel size checks, annotation detection, tiling,
 * affine alignment, and asynchronous acquisition/stitching of regions.
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);

    /**
     * Main entry point for the workflow. Handles all user interaction and launches
     * the acquisition process.
     */
    public static void run() {
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
        QuPathGUI qupathGUI = QuPathGUI.getInstance();

        logger.info("Starting Existing Image Workflow...");

        // 1. Collect sample setup and existing image info from the user via dialogs
        SampleSetupController.showDialog()
                .thenCompose(sample -> {
                    logger.info("Sample info received: {}", sample);
                    return ExistingImageController.showDialog()
                            .thenApply(existingImage -> Map.entry(sample, existingImage));
                })
                .thenAccept(pair -> Platform.runLater(() ->
                        executeWorkflow(pair.getKey(), pair.getValue(), qupathGUI)
                ))
                .exceptionally(ex -> {
                    UIFunctions.notifyUserOfError(ex.getMessage(), "Workflow Initialization Error");
                    logger.error("Workflow initialization error", ex);
                    return null;
                });
    }

    /**
     * Executes the full acquisition and stitching workflow for a given sample and existing image.
     *
     * @param sample        User-provided sample/project parameters
     * @param existingImage User input regarding macro image properties
     * @param qupathGUI     QuPath main GUI instance
     */
    private static void executeWorkflow(
            SampleSetupController.SampleSetupResult sample,
            ExistingImageController.UserInput existingImage,
            QuPathGUI qupathGUI
    ) {
        logger.info("Executing workflow for sample: {} | Modality: {}", sample.sampleName(), sample.modality());

        try {
            // --- 1. Prepare project and import the image, handling X/Y flipping ---
            boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
            logger.info("Project setup: Flip X={}, Flip Y={}", flipX, flipY);

            Map<String, Object> projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                    qupathGUI,
                    sample.projectsFolder().getAbsolutePath(),
                    sample.sampleName(),
                    sample.modality(),
                    flipX,
                    flipY
            );

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            // --- 2. Retrieve pixel size from the currently open image, only prompt if missing ---
            double macroPixelSize = Double.NaN;
            var imageData = qupathGUI.getImageData();
            if (imageData != null && imageData.getServer() != null) {
                try {
                    macroPixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                    logger.info("Found macro pixel size in metadata: {} µm", macroPixelSize);
                } catch (Exception e) {
                    logger.warn("Could not retrieve pixel size from image metadata: {}", e.getMessage());
                    macroPixelSize = Double.NaN;
                }
            }
            if (Double.isNaN(macroPixelSize) || macroPixelSize <= 0 || macroPixelSize > 10) {
                macroPixelSize = ExistingImageController.requestPixelSizeDialog();
                logger.info("User-provided macro pixel size: {} µm", macroPixelSize);
            }


            // --- 3. Run tissue detection script or prompt for annotation creation ---
            String tissueDetectScript = existingImage.groovyScriptPath();
            boolean annotationsOk = false;

            if (tissueDetectScript != null && !tissueDetectScript.isBlank()) {
                // Optionally modify the script in place for pixel size, if needed
                try {
                    UtilityFunctions.modifyTissueDetectScript(
                            tissueDetectScript,
                            String.valueOf(macroPixelSize),
                            "<YOUR_JSON_FILE_HERE>"
                    );
                    logger.info("Running tissue detection script: {}", tissueDetectScript);
                    qupathGUI.runScript(null, tissueDetectScript);
                    annotationsOk = true;
                } catch (IOException e) {
                    logger.warn("Could not run tissue detection script: {}", e.getMessage());
                    annotationsOk = false;
                } catch (ScriptException e) {
                    throw new RuntimeException(e);
                }
            }

            if (!annotationsOk) {
                // Prompt user to confirm or edit annotations
                ExistingImageController.promptForAnnotations();
            }

            // --- 4. Find annotations (ROIs) to target for acquisition ---
            List<PathObject> annotations = new ArrayList<>(qupathGUI.getViewer().getHierarchy().getAnnotationObjects());

            if (annotations.isEmpty()) {
                // No suitable regions, abort workflow
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No annotations found. Please create/select regions to acquire.", "Region Selection Error"));
                logger.warn("No annotations found after tissue detection or manual input.");
                return;
            }

            // --- 5. Perform tiling on all annotation objects ---
            double pixelSizeFirstMode = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getDouble("imagingMode", sample.modality(), "pixelSize_um");
            int cameraWidth = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", sample.modality(), "detector", "width_px");
            int cameraHeight = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", sample.modality(), "detector", "height_px");

            double frameWidthQPpixels = cameraWidth * (pixelSizeFirstMode / macroPixelSize);
            double frameHeightQPpixels = cameraHeight * (pixelSizeFirstMode / macroPixelSize);

            logger.info("Tiling parameters: Frame size in QP px: {} x {}", frameWidthQPpixels, frameHeightQPpixels);

            UtilityFunctions.performTilingAndSaveConfiguration(
                    tempTileDirectory,
                    modeWithIndex,
                    frameWidthQPpixels,
                    frameHeightQPpixels,
                    QPPreferenceDialog.getTileOverlapPercentProperty(),
                    null, // Use annotations, not a bounding box
                    true,
                    annotations,
                    QPPreferenceDialog.getInvertedYProperty(),
                    QPPreferenceDialog.getInvertedXProperty()
            );

            // --- 6. User aligns microscope to image (stage alignment) ---
            CompletableFuture<AffineTransform> transformFuture =
                    AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            macroPixelSize, QPPreferenceDialog.getInvertedYProperty(), QPPreferenceDialog.getInvertedXProperty());

            transformFuture.thenAccept(transform -> {
                if (transform == null) {
                    logger.info("Affine transform setup cancelled by user.");
                    return;
                }

                // --- 7. Loop: acquire and stitch tiles for each annotation ---
                CompletableFuture.runAsync(() -> {
                    for (PathObject annotation : annotations) {
                        try {
                            logger.info("Acquiring tiles for annotation: {}", annotation.getName());

                            CliExecutor.ExecResult result = CliExecutor.execComplexCommand(
                                    300, "tiles done", "acquireTiles", tempTileDirectory);

                            if (result.exitCode() != 0 || result.timedOut()) {
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        "Acquisition error: " + result.stderr(), "Acquisition Error"));
                                logger.error("Acquisition failed for annotation: {}", annotation.getName());
                                continue;
                            }

                            String stitchedImagePath = UtilityFunctions.stitchImagesAndUpdateProject(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName(),
                                    modeWithIndex,
                                    annotation.getName(),
                                    qupathGUI,
                                    project,
                                    String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                    pixelSizeFirstMode,
                                    1
                            );

                            // --- 8. Save tile boundaries for future alignment ---
                            Path tileConfigPath = Path.of(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName(),
                                    modeWithIndex,
                                    annotation.getName(),
                                    "TileConfiguration.txt"
                            );

                            List<List<Double>> extremes = TransformationFunctions.findImageBoundaries(tileConfigPath.toFile());
                            MinorFunctions.writeTileExtremesToFile(stitchedImagePath, extremes);

                        } catch (Exception e) {
                            logger.error("Exception in tile acquisition/stitching: {}", e.getMessage());
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    e.getMessage(), "Workflow Error"));
                        }
                    }
                    handleTilesCleanup(tempTileDirectory);
                });
            });

        } catch (IOException e) {
            UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Initialization Error");
            logger.error("IOException during workflow initialization: {}", e.getMessage());
        }
    }

    /**
     * Handles post-acquisition tile cleanup based on user preferences.
     *
     * @param tileDirectory Directory to clean up (delete or zip, etc.)
     */
    private static void handleTilesCleanup(String tileDirectory) {
        String tileHandling = QPPreferenceDialog.getTileHandlingMethodProperty();
        if ("Delete".equals(tileHandling)) {
            UtilityFunctions.deleteTilesAndFolder(tileDirectory);
        } else if ("Zip".equals(tileHandling)) {
            UtilityFunctions.zipTilesAndMove(tileDirectory);
            UtilityFunctions.deleteTilesAndFolder(tileDirectory);
        }
    }
}
