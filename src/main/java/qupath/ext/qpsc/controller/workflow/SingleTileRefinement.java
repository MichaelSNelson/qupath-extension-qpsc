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
 *
 * <p>This class provides functionality to refine an existing alignment by:
 * <ul>
 *   <li>Selecting a single tile from the annotations</li>
 *   <li>Moving the microscope to the estimated position</li>
 *   <li>Allowing manual adjustment of the stage position</li>
 *   <li>Calculating a refined transform based on the adjustment</li>
 * </ul>
 *
 * <p>Single-tile refinement improves alignment accuracy by correcting for
 * small errors in the initial transform.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class SingleTileRefinement {
    private static final Logger logger = LoggerFactory.getLogger(SingleTileRefinement.class);

    /**
     * Result of refinement containing both the transform and the selected tile.
     */
    public static class RefinementResult {
        public final AffineTransform transform;
        public final PathObject selectedTile;

        public RefinementResult(AffineTransform transform, PathObject selectedTile) {
            this.transform = transform;
            this.selectedTile = selectedTile;
        }
    }

    /**
     * Performs single-tile refinement of alignment.
     *
     * <p>This method:
     * <ol>
     *   <li>Prompts user to select a tile</li>
     *   <li>Moves microscope to estimated position</li>
     *   <li>Allows manual position adjustment</li>
     *   <li>Calculates refined transform</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param annotations List of annotations containing tiles
     * @param initialTransform Initial transform to refine
     * @return CompletableFuture with RefinementResult containing refined transform and selected tile
     */
    public static CompletableFuture<RefinementResult> performRefinement(
            QuPathGUI gui,
            List<PathObject> annotations,
            AffineTransform initialTransform) {

        CompletableFuture<RefinementResult> future = new CompletableFuture<>();

        logger.info("Starting single-tile refinement");

        // Select tile for refinement
        UIFunctions.promptTileSelectionDialogAsync(
                "Select a tile for alignment refinement.\n" +
                        "The microscope will move to the estimated position for this tile.\n" +
                        "You will then manually adjust the stage position to match."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                logger.info("User cancelled tile selection");
                future.complete(new RefinementResult(initialTransform, null));
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
                    future.complete(new RefinementResult(initialTransform, selectedTile));
                }
            });
        });

        return future;
    }

    /**
     * Performs the actual tile refinement process.
     *
     * <p>This method:
     * <ol>
     *   <li>Gets tile center coordinates</li>
     *   <li>Transforms to estimated stage position</li>
     *   <li>Validates stage boundaries</li>
     *   <li>Centers viewer on tile</li>
     *   <li>Moves microscope to position</li>
     *   <li>Shows refinement dialog</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param selectedTile The tile selected for refinement
     * @param initialTransform Initial transform
     * @param future Future to complete with RefinementResult
     * @throws Exception if refinement fails
     */
    private static void performTileRefinement(
            QuPathGUI gui,
            PathObject selectedTile,
            AffineTransform initialTransform,
            CompletableFuture<RefinementResult> future) throws Exception {

        // Get tile coordinates (centroid)
        double[] tileCoords = {
                selectedTile.getROI().getCentroidX(),
                selectedTile.getROI().getCentroidY()
        };

        // Get frame dimensions from tile ROI (in pixels)
        double frameWidth = selectedTile.getROI().getBoundsWidth();
        double frameHeight = selectedTile.getROI().getBoundsHeight();

        // Get flip status - when image is flipped, the estimation is systematically
        // off by 1 frame in the flip direction due to coordinate system differences
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        logger.info("Selected tile '{}' at coordinates: ({}, {}), frame size: {}x{}, flips: X={}, Y={}",
                selectedTile.getName(), tileCoords[0], tileCoords[1], frameWidth, frameHeight, flipX, flipY);

        // Apply flip-based correction to pixel coordinates before transform
        // In flipped image space, the prediction is 1 frame higher (Y) and 1 frame right (X)
        // To correct: shift coordinates in the opposite direction of the flip
        double[] correctedCoords = {tileCoords[0], tileCoords[1]};
        if (flipX) {
            // In flipped X: prediction is 1 frame to the right, so subtract frame width
            correctedCoords[0] -= frameWidth;
            logger.debug("Applied flipX correction: X {} -> {}", tileCoords[0], correctedCoords[0]);
        }
        if (flipY) {
            // In flipped Y: prediction is 1 frame higher (smaller Y), so add frame height
            correctedCoords[1] += frameHeight;
            logger.debug("Applied flipY correction: Y {} -> {}", tileCoords[1], correctedCoords[1]);
        }

        // Transform corrected coordinates to stage position
        double[] estimatedStageCoords = TransformationFunctions.transformQuPathFullResToStage(
                correctedCoords, initialTransform);

        if (flipX || flipY) {
            // Also log what the uncorrected position would have been for comparison
            double[] uncorrectedStageCoords = TransformationFunctions.transformQuPathFullResToStage(
                    tileCoords, initialTransform);
            logger.info("Stage position: corrected=({}, {}), uncorrected=({}, {})",
                    String.format("%.1f", estimatedStageCoords[0]),
                    String.format("%.1f", estimatedStageCoords[1]),
                    String.format("%.1f", uncorrectedStageCoords[0]),
                    String.format("%.1f", uncorrectedStageCoords[1]));
        }

        // Center viewer on tile
        centerViewerOnTile(gui, selectedTile);

        // Move to estimated position
        logger.info("Moving to estimated position: ({}, {})",
                estimatedStageCoords[0], estimatedStageCoords[1]);
        MicroscopeController.getInstance().moveStageXY(
                estimatedStageCoords[0], estimatedStageCoords[1]);

        // Wait for stage to settle
        Thread.sleep(500);

        // Show refinement dialog
        showRefinementDialog(tileCoords, initialTransform, selectedTile, future);
    }


    /**
     * Centers the QuPath viewer on the selected tile.
     *
     * @param gui QuPath GUI instance
     * @param tile Tile to center on
     */
    private static void centerViewerOnTile(QuPathGUI gui, PathObject tile) {
        var viewer = gui.getViewer();
        if (viewer != null && tile.getROI() != null) {
            double cx = tile.getROI().getCentroidX();
            double cy = tile.getROI().getCentroidY();
            viewer.setCenterPixelLocation(cx, cy);
            viewer.getHierarchy().getSelectionModel().setSelectedObject(tile);
            logger.debug("Centered viewer on tile at ({}, {})", cx, cy);
        }
    }

    /**
     * Shows the refinement dialog for user interaction.
     *
     * <p>Presents options to:
     * <ul>
     *   <li>Save the refined position after manual adjustment</li>
     *   <li>Skip refinement and use initial transform</li>
     *   <li>Create entirely new alignment</li>
     * </ul>
     *
     * @param tileCoords Original tile coordinates in QuPath
     * @param initialTransform Initial transform
     * @param selectedTile The tile that was selected for refinement
     * @param future Future to complete with result
     * @throws IOException if stage position cannot be read
     */
    private static void showRefinementDialog(
            double[] tileCoords,
            AffineTransform initialTransform,
            PathObject selectedTile,
            CompletableFuture<RefinementResult> future) throws IOException {

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

                future.complete(new RefinementResult(refinedTransform, selectedTile));

            } else if (result.get() == skipButton) {
                logger.info("User skipped refinement");
                future.complete(new RefinementResult(initialTransform, selectedTile));

            } else if (result.get() == newAlignmentButton) {
                logger.info("User requested new alignment");
                future.complete(new RefinementResult(null, selectedTile)); // Signal to switch to manual alignment
            }
        } else {
            // Dialog closed without selection
            future.complete(new RefinementResult(initialTransform, selectedTile));
        }
    }
}