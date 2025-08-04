
// File: qupath/ext/qpsc/controller/workflow/SingleTileRefinement.java
package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Helper for single-tile alignment refinement.
 */
public class SingleTileRefinement {
    private static final Logger logger = LoggerFactory.getLogger(SingleTileRefinement.class);

    /**
     * Performs single-tile refinement of alignment.
     */
    public static CompletableFuture<AffineTransform> performRefinement(
            QuPathGUI gui,
            List<PathObject> annotations,
            AffineTransform initialTransform) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        logger.info("Starting single-tile refinement");

        // Select tile for refinement
        UIFunctions.promptTileSelectionDialogAsync(
                "Select a tile for alignment refinement.\n" +
                        "The microscope will move to the estimated position for this tile.\n" +
                        "You will then manually adjust the stage position to match."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                logger.info("User cancelled tile selection");
                future.complete(initialTransform);
                return;
            }

            Platform.runLater(() -> {
                try {
                    performTileRefinement(gui, selectedTile, initialTransform, future);
                } catch (Exception e) {
                    logger.error("Error during refinement", e);
                    UIFunctions.notifyUserOfError(
                            "Error during refinement: " + e.getMessage(),
                            "Refinement Error"
                    );
                    future.complete(initialTransform);
                }
            });
        });

        return future;
    }

    private static void performTileRefinement(
            QuPathGUI gui,
            PathObject selectedTile,
            AffineTransform initialTransform,
            CompletableFuture<AffineTransform> future) throws Exception {

        // Get tile coordinates
        double[] tileCoords = {
                selectedTile.getROI().getCentroidX(),
                selectedTile.getROI().getCentroidY()
        };

        logger.info("Selected tile '{}' at coordinates: ({}, {})",
                selectedTile.getName(), tileCoords[0], tileCoords[1]);

        // Transform to stage position
        double[] estimatedStageCoords = TransformationFunctions.transformQuPathFullResToStage(
                tileCoords, initialTransform);

        // Validate stage bounds
        if (!validateStageBounds(estimatedStageCoords)) {
            UIFunctions.notifyUserOfError(
                    "Selected tile is outside stage bounds!\n" +
                            "Please select a different tile or create a new alignment.",
                    "Stage Bounds Error"
            );
            future.complete(initialTransform);
            return;
        }

        // Center viewer on tile
        centerViewerOnTile(gui, selectedTile);

        // Move to estimated position
        logger.info("Moving to estimated position");
        MicroscopeController.getInstance().moveStageXY(
                estimatedStageCoords[0], estimatedStageCoords[1]);

        // Wait for stage to settle
        Thread.sleep(500);

        // Show refinement dialog
        showRefinementDialog(tileCoords, initialTransform, future);
    }

    private static boolean validateStageBounds(double[] stageCoords) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double stageXMin = mgr.getDouble("stage", "xlimit", "low");
        double stageXMax = mgr.getDouble("stage", "xlimit", "high");
        double stageYMin = mgr.getDouble("stage", "ylimit", "low");
        double stageYMax = mgr.getDouble("stage", "ylimit", "high");

        return stageCoords[0] >= stageXMin && stageCoords[0] <= stageXMax &&
                stageCoords[1] >= stageYMin && stageCoords[1] <= stageYMax;
    }

    private static void centerViewerOnTile(QuPathGUI gui, PathObject tile) {
        var viewer = gui.getViewer();
        if (viewer != null && tile.getROI() != null) {
            double cx = tile.getROI().getCentroidX();
            double cy = tile.getROI().getCentroidY();
            viewer.setCenterPixelLocation(cx, cy);
            viewer.getHierarchy().getSelectionModel().setSelectedObject(tile);
        }
    }

    private static void showRefinementDialog(
            double[] tileCoords,
            AffineTransform initialTransform,
            CompletableFuture<AffineTransform> future) throws IOException {

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Refine Alignment");
        dialog.setHeaderText("Alignment Refinement");
        dialog.setContentText(
                "The microscope has moved to the estimated position for the selected tile.\n\n" +
                        "Please use the microscope controls to adjust the stage position so that\n" +
                        "the live view matches the selected tile in QuPath.\n\n" +
                        "When the alignment is correct, click 'Save Refined Position'."
        );

        ButtonType saveButton = new ButtonType("Save Refined Position");
        ButtonType skipButton = new ButtonType("Skip Refinement");
        ButtonType newAlignmentButton = new ButtonType("Create New Alignment");

        dialog.getButtonTypes().setAll(saveButton, skipButton, newAlignmentButton);

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent()) {
            if (result.get() == saveButton) {
                // Get refined position
                double[] refinedStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                logger.info("Refined stage position: ({}, {})",
                        refinedStageCoords[0], refinedStageCoords[1]);

                // Calculate refined transform
                AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                        initialTransform, tileCoords, refinedStageCoords);

                logger.info("Calculated refined transform");

                Dialogs.showInfoNotification(
                        "Alignment Refined",
                        "The alignment has been refined and saved for this slide."
                );

                future.complete(refinedTransform);

            } else if (result.get() == skipButton) {
                logger.info("User skipped refinement");
                future.complete(initialTransform);

            } else if (result.get() == newAlignmentButton) {
                logger.info("User requested new alignment");
                future.complete(null); // Signal to switch to manual alignment
            }
        } else {
            future.complete(initialTransform);
        }
    }
}