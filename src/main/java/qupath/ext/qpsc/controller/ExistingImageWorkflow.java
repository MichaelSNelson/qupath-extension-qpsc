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
 * Handles the workflow for processing an existing macro image in QuPath,
 * including annotation validation, affine transformation setup, microscope acquisition,
 * and image stitching.
 */
public class ExistingImageWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);

    public static void run() {
        var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
        QuPathGUI qupathGUI = QuPathGUI.getInstance();

        SampleSetupController.showDialog()
                .thenCompose(sample ->
                        ExistingImageController.showDialog()
                                .thenApply(existingImage -> Map.entry(sample, existingImage))
                )
                .thenAccept(pair -> {
                    var sample = pair.getKey();
                    var existingImage = pair.getValue();

                    Platform.runLater(() -> executeWorkflow(sample, existingImage, qupathGUI));
                })
                .exceptionally(ex -> {
                    UIFunctions.notifyUserOfError(ex.getMessage(), "Workflow Initialization Error");
                    return null;
                });
    }


    private static void executeWorkflow(SampleSetupController.SampleSetupResult sample, ExistingImageController.UserInput existingImage, QuPathGUI qupathGUI) {
        try {

            // Retrieve necessary preferences
            String projectsFolder = sample.projectsFolder().getAbsolutePath();
            String modality = sample.modality();
            String sampleName = sample.sampleName();
            boolean invertX = QPPreferenceDialog.getInvertedXProperty();
            boolean invertY = QPPreferenceDialog.getInvertedYProperty();
            // Project initialization or loading
            Map<String, Object> projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                    qupathGUI, projectsFolder, sampleName, modality, invertX, invertY);

            Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            double pixelSizeSource = existingImage.macroPixelSize();
            double pixelSizeFirstMode = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getDouble("imagingMode", modality, "pixelSize_um");

            int cameraWidth = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", modality, "detector", "width_px");
            int cameraHeight = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getInteger("imagingMode", modality, "detector", "height_px");

            double frameWidthQPpixels = cameraWidth * (pixelSizeFirstMode / pixelSizeSource);
            double frameHeightQPpixels = cameraHeight * (pixelSizeFirstMode / pixelSizeSource);

            List<PathObject> annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(o -> "Tissue".equals(o.getPathClass().getName()))
                    .collect(Collectors.toList());

            if (annotations.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "No valid 'Tissue' annotations found.");
                alert.showAndWait();
                return;
            }

            UtilityFunctions.performTilingAndSaveConfiguration(tempTileDirectory, modeWithIndex,
                    frameWidthQPpixels, frameHeightQPpixels,
                    QPPreferenceDialog.getTileOverlapPercentProperty(),
                    null, true, annotations, invertY, invertX);

            CompletableFuture<AffineTransform> transform = AffineTransformationController.setupAffineTransformationAndValidationGUI(
                    pixelSizeSource, invertY, invertX);

            if (transform == null) {
                logger.info("Affine transform setup cancelled.");
                return;
            }

            CompletableFuture.runAsync(() -> {
                annotations.forEach(annotation -> {
                    try {
                        CliExecutor.ExecResult result = CliExecutor.execComplexCommand(
                                300, "tiles done", "acquireTiles", tempTileDirectory);

                        if (result.exitCode() != 0 || result.timedOut()) {
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Acquisition error: " + result.stderr(), "Acquisition Error"));
                            return;
                        }

                        String stitchedImagePath = UtilityFunctions.stitchImagesAndUpdateProject(
                                projectsFolder, sampleName, modeWithIndex, annotation.getName(),
                                qupathGUI, project,
                                String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()), pixelSizeFirstMode, 1);

                        Path tileConfigPath = Path.of(projectsFolder, sampleName, modeWithIndex,
                                annotation.getName(), "TileConfiguration.txt");

                        List<List<Double>> extremes = TransformationFunctions.findImageBoundaries(tileConfigPath.toFile());
                        MinorFunctions.writeTileExtremesToFile(stitchedImagePath, extremes);

                    } catch (Exception e) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Error"));
                    }
                });

                handleTilesCleanup(tempTileDirectory);
            });

        } catch (IOException e) {
            UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Initialization Error");
        }
    }

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