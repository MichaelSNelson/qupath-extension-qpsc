package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
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

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow
 *
 * Orchestrates the workflow for targeted microscope acquisition using an existing (low-res) image in QuPath.
 *
 * <p>Steps:
 * <ol>
 *   <li>User provides sample and imaging parameters.</li>
 *   <li>Regions of interest (annotations) are detected and tiled based on camera and sample pixel size.</li>
 *   <li>User aligns the hardware stage to image space using interactive affine transformation tools.</li>
 *   <li>Acquisition coordinates are mapped and sent to the microscope (via Python/CLI).</li>
 *   <li>Tiles are stitched after each acquisition region (in background threads).</li>
 *   <li>Workflow supports acquiring/stitching multiple regions independently and in parallel.</li>
 * </ol>
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

        // 1. Collect sample setup and existing image info from the user via dialogs
        SampleSetupController.showDialog()
                .thenCompose(sample ->
                        ExistingImageController.showDialog()
                                .thenApply(existingImage -> Map.entry(sample, existingImage))
                )
                .thenAccept(pair -> Platform.runLater(() ->
                        executeWorkflow(pair.getKey(), pair.getValue(), qupathGUI)
                ))
                .exceptionally(ex -> {
                    UIFunctions.notifyUserOfError(ex.getMessage(), "Workflow Initialization Error");
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
        try {
            // --- 2. Retrieve preferences and initialize QuPath project ---
            String projectsFolder = sample.projectsFolder().getAbsolutePath();
            String modality = sample.modality();
            String sampleName = sample.sampleName();
            boolean invertX = QPPreferenceDialog.getInvertedXProperty();
            boolean invertY = QPPreferenceDialog.getInvertedYProperty();

            // Create/open project and get temporary tile directory, imaging mode, etc.
            Map<String, Object> projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                    qupathGUI, projectsFolder, sampleName, modality, invertX, invertY);

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            // --- 3. Camera and pixel size configuration ---
            double pixelSizeSource = existingImage.macroPixelSize();
            double pixelSizeFirstMode = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getDouble("imagingMode", modality, "pixelSize_um");

            int cameraWidth = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", modality, "detector", "width_px");
            int cameraHeight = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", modality, "detector", "height_px");

            // The field of view of the camera in units of QuPath (macro image) pixels
            double frameWidthQPpixels = cameraWidth * (pixelSizeFirstMode / pixelSizeSource);
            double frameHeightQPpixels = cameraHeight * (pixelSizeFirstMode / pixelSizeSource);

            // --- 4. Find annotations (ROIs) to target for acquisition ---
            List<PathObject> annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(o -> "Tissue".equals(o.getPathClass().getName()))
                    .collect(Collectors.toList());

            if (annotations.isEmpty()) {
                // No suitable regions, abort workflow
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No valid 'Tissue' annotations found.", "Region Selection Error"));
                return;
            }

            // --- 5. Generate tiling for all target annotations/regions ---
            UtilityFunctions.performTilingAndSaveConfiguration(
                    tempTileDirectory,
                    modeWithIndex,
                    frameWidthQPpixels,
                    frameHeightQPpixels,
                    QPPreferenceDialog.getTileOverlapPercentProperty(),
                    null, // No bounding box, use annotations directly
                    true, // Use annotations as regions to tile
                    annotations,
                    invertY,
                    invertX
            );

            // --- 6. Interactive affine transformation: user aligns hardware and software views ---
            CompletableFuture<AffineTransform> transformFuture =
                    AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            pixelSizeSource, invertY, invertX);

            if (transformFuture == null) {
                logger.info("Affine transform setup cancelled by user.");
                return;
            }

            // 7. Launch acquisition and stitching asynchronously for each annotation/region
            CompletableFuture.runAsync(() -> {
                annotations.forEach(annotation -> {
                    try {
                        // --- 7a. Launch tile acquisition via CLI ---
                        CliExecutor.ExecResult result = CliExecutor.execComplexCommand(
                                300, "tiles done", "acquireTiles", tempTileDirectory);

                        if (result.exitCode() != 0 || result.timedOut()) {
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Acquisition error: " + result.stderr(), "Acquisition Error"));
                            return;
                        }

                        // --- 7b. Stitch and update project in background ---
                        String stitchedImagePath = UtilityFunctions.stitchImagesAndUpdateProject(
                                projectsFolder,
                                sampleName,
                                modeWithIndex,
                                annotation.getName(),
                                qupathGUI,
                                project,
                                String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                pixelSizeFirstMode,
                                1 // downsample
                        );

                        // --- 7c. Post-processing: record tile boundaries for future alignment or QC ---
                        Path tileConfigPath = Path.of(projectsFolder, sampleName, modeWithIndex,
                                annotation.getName(), "TileConfiguration.txt");

                        List<List<Double>> extremes = TransformationFunctions.findImageBoundaries(tileConfigPath.toFile());
                        MinorFunctions.writeTileExtremesToFile(stitchedImagePath, extremes);

                    } catch (Exception e) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Error"));
                    }
                });

                // 8. Handle cleanup after all acquisitions/stitching
                handleTilesCleanup(tempTileDirectory);
            });

        } catch (IOException e) {
            UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Initialization Error");
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