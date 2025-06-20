package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.scripting.QP;

import static qupath.ext.qpsc.utilities.TransformationFunctions.addTranslationToScaledAffine;

/**
 * Handles user interactions for affine transformation validation and interactive stage alignment.
 * Guides the user through aligning selected tiles to the live stage view,
 * refines the affine transform, and returns the final transform via CompletableFuture.
 */
public class AffineTransformationController {

    private static final Logger logger = LoggerFactory.getLogger(AffineTransformationController.class);

    /**
     * Launches the full affine alignment GUI flow:
     *  - Computes an initial scaling transform based on pixel size and flip
     *  - Guides the user to align the microscope stage to selected tiles
     *  - Refines the affine transform using measured stage coordinates
     *  - Returns the final AffineTransform (or null if cancelled)
     *
     * @param macroPixelSizeMicrons Pixel size of macro image in microns
     * @param invertedX             Whether to invert the X-axis
     * @param invertedY             Whether to invert the Y-axis
     * @return CompletableFuture with the user's validated affine transform, or null if cancelled.
     */
    public static CompletableFuture<AffineTransform> setupAffineTransformationAndValidationGUI(
            double macroPixelSizeMicrons,
            boolean invertedX,
            boolean invertedY
    ) {
        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                logger.info("Starting affine transformation setup (pixelSize: {}, flipX: {}, flipY: {})",
                        macroPixelSizeMicrons, invertedX, invertedY);

                QuPathGUI gui = QuPathGUI.getInstance();

                // 1. Create initial scaling transform
                AffineTransform scalingTransform =
                        TransformationFunctions.setupAffineTransformation(macroPixelSizeMicrons, invertedX, invertedY);
                logger.info("Initial scaling transform: {}", scalingTransform);

                // 2. Prompt user to select a reference tile for initial alignment
                UIFunctions.promptTileSelectionDialogAsync(
                        "Select a REFERENCE tile (preferably near the center or edge of your region).\n" +
                                "After selection:\n" +
                                "1. Manually move the microscope stage to center this tile in the live view\n" +
                                "2. Click 'Confirm Selection' when the stage is properly positioned"
                ).thenAccept(refTile -> {
                    if (refTile == null) {
                        logger.info("User cancelled reference tile selection.");
                        future.complete(null);
                        return;
                    }

                    try {
                        double[] qpRefCoords = {refTile.getROI().getCentroidX(), refTile.getROI().getCentroidY()};
                        logger.info("User selected tile '{}' at QuPath coords: {}", refTile.getName(), Arrays.toString(qpRefCoords));

                        // 3. Ask user to confirm they've manually aligned the stage
                        String message = "Please ensure the microscope is centered on the selected tile.\n" +
                                "The live camera view should show the same region as the selected tile in QuPath.\n\n" +
                                "Is the stage properly aligned?";

                        boolean aligned = UIFunctions.promptYesNoDialog("Confirm Stage Alignment", message);
                        if (!aligned) {
                            logger.info("User cancelled at manual alignment step.");
                            future.complete(null);
                            return;
                        }

                        // 4. NOW get the current stage position after user has manually aligned
                        double[] measuredStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                        logger.info("Current stage coordinates after manual alignment: {}", Arrays.toString(measuredStageCoords));

                        // 5. Calculate transform based on the manually aligned position
                        AffineTransform transform = TransformationFunctions.addTranslationToScaledAffine(
                                scalingTransform, qpRefCoords, measuredStageCoords, null
                        );
                        logger.info("Calculated affine transform from manual alignment: {}", transform);


                        // 6. Secondary refinement: use two geometric extremes for more robust alignment
                        //    (top center and left center tiles, automatically determined)
                        Collection<PathObject> allTiles = gui.getViewer().getHierarchy().getDetectionObjects();
                        PathObject topCenterTile = TransformationFunctions.getTopCenterTile(allTiles);
                        PathObject leftCenterTile = TransformationFunctions.getLeftCenterTile(allTiles);

                        // Process refinement tiles sequentially
                        CompletableFuture<AffineTransform> refinementFuture = CompletableFuture.completedFuture(transform);

                        for (PathObject tile : Arrays.asList(topCenterTile, leftCenterTile)) {
                            if (tile == null) continue;

                            refinementFuture = refinementFuture.thenCompose(currentTransform -> {
                                CompletableFuture<AffineTransform> tileFuture = new CompletableFuture<>();

                                Platform.runLater(() -> {
                                    try {
                                        // Select the tile BEFORE showing the dialog
                                        QP.resetSelection();
                                        QP.selectObjects(List.of(tile));  // Wrap in a List

                                        // Also center the viewer on this tile
                                        gui.getViewer().setCenterPixelLocation(
                                                tile.getROI().getCentroidX(),
                                                tile.getROI().getCentroidY()
                                        );

                                        logger.info("Secondary alignment: moving to refinement tile '{}'", tile.getName());

                                        double[] tileCoords = {tile.getROI().getCentroidX(), tile.getROI().getCentroidY()};
                                        double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(tileCoords, currentTransform);

                                        MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                                        boolean ok = UIFunctions.stageToQuPathAlignmentGUI2();
                                        if (!ok) {
                                            logger.info("User cancelled during secondary alignment at tile '{}'.", tile.getName());
                                            tileFuture.complete(null);
                                            return;
                                        }

                                        double[] measuredCoords = MicroscopeController.getInstance().getStagePositionXY();
                                        AffineTransform newTransform = TransformationFunctions.addTranslationToScaledAffine(
                                                currentTransform, tileCoords, measuredCoords, null
                                        );
                                        logger.info("Refined transform after tile '{}': {}", tile.getName(), newTransform);
                                        tileFuture.complete(newTransform);

                                    } catch (Exception e) {
                                        logger.error("Error during tile refinement", e);
                                        tileFuture.completeExceptionally(e);
                                    }
                                });

                                return tileFuture;
                            });
                        }
                        refinementFuture.thenAccept(finalTransform -> {
                            if (finalTransform != null) {
                                future.complete(finalTransform);
                            } else {
                                future.complete(null);
                            }
                        }).exceptionally(ex -> {
                            logger.error("Refinement failed", ex);
                            future.completeExceptionally(ex);
                            return null;
                        });

                    } catch (Exception e) {
                        logger.error("Affine transformation setup failed", e);
                        Dialogs.showErrorNotification("Affine Transform Error", e.getMessage());
                        future.complete(null);
                    }
                }).exceptionally(ex -> {
                    logger.error("Tile selection failed", ex);
                    future.complete(null);
                    return null;
                });

            } catch (Exception e) {
                logger.error("Affine transformation setup failed", e);
                Dialogs.showErrorNotification("Affine Transform Error", e.getMessage());
                future.complete(null);
            }
        });

        return future;
    }

}
