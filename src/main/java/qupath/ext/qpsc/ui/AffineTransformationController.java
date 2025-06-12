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

                // 1. Create initial scaling transform (only scale and flip, no translation yet)
                AffineTransform scalingTransform =
                        TransformationFunctions.setupAffineTransformation(macroPixelSizeMicrons, invertedX, invertedY);
                logger.info("Initial scaling transform: {}", scalingTransform);

                // 2. Prompt user to select a reference tile for initial alignment
                PathObject refTile = UIFunctions.promptTileSelectionDialog(
                        "Select a REFERENCE tile (usually a tile on the boundary of your region),\n" +
                                "then press OK. This will be used for initial stage/image alignment."
                );
                if (refTile == null) {
                    logger.info("User cancelled reference tile selection.");
                    future.complete(null);
                    return;
                }
                double[] qpRefCoords = {refTile.getROI().getCentroidX(), refTile.getROI().getCentroidY()};
                logger.info("User selected tile '{}' at QuPath coords: {}", refTile.getName(), Arrays.toString(qpRefCoords));

                // 3. Move stage to the *predicted* position using the current (scaling) transform
                double[] stageGuess = TransformationFunctions.qpToMicroscopeCoordinates(qpRefCoords, scalingTransform);
                MicroscopeController.getInstance().moveStageXY(stageGuess[0], stageGuess[1]);
                logger.info("Moved stage to initial guess: {}", Arrays.toString(stageGuess));

                // 4. Prompt user to confirm or adjust the stage, then get the actual stage coordinates
                boolean positionOk = UIFunctions.stageToQuPathAlignmentGUI2(); // "Is this position accurate?"
                if (!positionOk) {
                    logger.info("User cancelled at initial stage alignment.");
                    future.complete(null);
                    return;
                }
                // Hardware call: get current measured stage coordinates
                double[] measuredStageCoords = MicroscopeController.getInstance().getStagePositionXY();
                logger.info("Measured stage coordinates: {}", Arrays.toString(measuredStageCoords));

                // 5. Compute offset (e.g., to account for difference between image ROI and FOV center)
                double[] offset = addTranslationToScaledAffine(); // Implement as needed
                logger.info("Using offset: {}", Arrays.toString(offset));

                // 6. Refine transform: align image tile and measured stage coordinates
                AffineTransform transform = addTranslationToScaledAffine(
                        scalingTransform, qpRefCoords, measuredStageCoords, offset
                );
                logger.info("Affine transform after initial alignment: {}", transform);

                // 7. Offer to refine alignment using additional tile(s)
                //    For secondary refinement, repeat with "top-center" or "left-center" tile, if user desires
                while (UIFunctions.promptYesNoDialog("Refine Alignment?",
                        "Would you like to use another tile for further alignment?")) {

                    // Suggest a good refinement tile (e.g., top center, left center)
                    PathObject nextTile = TransformationFunctions.getTopCenterTile(QP.getDetectionObjects()); // implement or select manually
                    if (nextTile == null) {
                        nextTile = UIFunctions.promptTileSelectionDialog(
                                "Select an additional tile for refinement, then press OK."
                        );
                        if (nextTile == null) {
                            logger.info("User cancelled additional refinement tile selection.");
                            break;
                        }
                    }
                    double[] qpCoords2 = {nextTile.getROI().getCentroidX(), nextTile.getROI().getCentroidY()};
                    MicroscopeController.getInstance().moveStageXY(
                            TransformationFunctions.qpToMicroscopeCoordinates(qpCoords2, transform)
                    );
                    boolean refineOk = UIFunctions.stageToQuPathAlignmentGUI2();
                    if (!refineOk) {
                        logger.info("User cancelled during secondary alignment.");
                        break;
                    }
                    double[] measuredCoords2 = MicroscopeController.getInstance().getStagePositionXY();
                    transform = addTranslationToScaledAffine(
                            transform, qpCoords2, measuredCoords2, offset
                    );
                    logger.info("Affine transform refined after tile '{}': {}", nextTile.getName(), transform);
                }

                // 8. Done: return the transform
                future.complete(transform);

            } catch (Exception e) {
                logger.error("Affine transformation setup failed", e);
                Dialogs.showErrorNotification("Affine Transform Error", e.getMessage());
                future.complete(null);
            }
        });


    /**
     * Compute the XY offset between QuPath ROI center and stage FOV, if needed.
     * This may depend on frame/camera parameters.
     * @return [offsetX, offsetY]
     */
    private static double[] computeStageOffset() {
        // TODO: implement based on your frame width, pixel size, camera setup, etc.
        // Example placeholder: return new double[]{-0.5 * frameWidth * pixelSize, 0.5 * frameHeight * pixelSize};
        return new double[]{0, 0};
    }
}
