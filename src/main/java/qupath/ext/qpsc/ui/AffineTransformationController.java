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
                PathObject refTile = UIFunctions.promptTileSelectionDialog(
                        "Select a REFERENCE tile (preferably a tile on the boundary of your region),\n" +
                                "then press OK. This will be used for initial stage/image alignment."
                );
                if (refTile == null) {
                    logger.info("User cancelled reference tile selection.");
                    future.complete(null);
                    return;
                }
                double[] qpRefCoords = {refTile.getROI().getCentroidX(), refTile.getROI().getCentroidY()};
                logger.info("User selected tile '{}' at QuPath coords: {}", refTile.getName(), Arrays.toString(qpRefCoords));

                // 3. Move stage to the predicted position using the scaling transform (NO offset)
                double[] stageGuess = TransformationFunctions.qpToMicroscopeCoordinates(qpRefCoords, scalingTransform);
                MicroscopeController.getInstance().moveStageXY(stageGuess[0], stageGuess[1]);
                logger.info("Moved stage to initial guess: {}", Arrays.toString(stageGuess));

                // 4. Prompt user to confirm stage position, then fetch the actual measured stage position
                boolean positionOk = UIFunctions.stageToQuPathAlignmentGUI2();
                if (!positionOk) {
                    logger.info("User cancelled at initial stage alignment.");
                    future.complete(null);
                    return;
                }
                double[] measuredStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                logger.info("Measured stage coordinates: {}", Arrays.toString(measuredStageCoords));

                // 5. Refine transform: align image and measured stage coordinates (NO offset for now)
                AffineTransform transform = TransformationFunctions.addTranslationToScaledAffine(
                        scalingTransform, qpRefCoords, measuredStageCoords, null
                );
                logger.info("Affine transform after initial alignment: {}", transform);

                // 6. Secondary refinement: use two geometric extremes for more robust alignment
                //    (top center and left center tiles, automatically determined)
                Collection<PathObject> allTiles = gui.getViewer().getHierarchy().getDetectionObjects();
                PathObject topCenterTile = TransformationFunctions.getTopCenterTile(allTiles);
                PathObject leftCenterTile = TransformationFunctions.getLeftCenterTile(allTiles);

                for (PathObject tile : Arrays.asList(topCenterTile, leftCenterTile)) {
                    if (tile == null) continue;
                    logger.info("Secondary alignment: moving to refinement tile '{}'", tile.getName());

                    double[] tileCoords = {tile.getROI().getCentroidX(), tile.getROI().getCentroidY()};
                    double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(tileCoords, transform);

                    MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);
                    boolean ok = UIFunctions.stageToQuPathAlignmentGUI2();
                    if (!ok) {
                        logger.info("User cancelled during secondary alignment at tile '{}'.", tile.getName());
                        future.complete(null);
                        return;
                    }
                    double[] measuredCoords = MicroscopeController.getInstance().getStagePositionXY();
                    transform = TransformationFunctions.addTranslationToScaledAffine(
                            transform, tileCoords, measuredCoords, null
                    );
                    logger.info("Refined transform after tile '{}': {}", tile.getName(), transform);
                }

                // 7. Done: return the final affine transform
                future.complete(transform);

            } catch (Exception e) {
                logger.error("Affine transformation setup failed", e);
                Dialogs.showErrorNotification("Affine Transform Error", e.getMessage());
                future.complete(null);
            }
        });

        return future;
    }

}
