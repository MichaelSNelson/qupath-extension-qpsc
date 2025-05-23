package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;

import java.awt.geom.AffineTransform;
import java.util.concurrent.CompletableFuture;

/**
 * Handles user interactions for affine transformation validation and setup.
 */
public class AffineTransformationController {


    /**
     * Prompts the user to validate an affine transformation setup.
     * Returns a CompletableFuture with the resulting transform or null if cancelled.
     *
     * @param macroPixelSizeMicrons Pixel size of macro image in microns
     * @param invertedX Whether to invert the X-axis
     * @param invertedY Whether to invert the Y-axis
     * @return CompletableFuture with the user's validated affine transform or null.
     */
    public static CompletableFuture<AffineTransform> setupAffineTransformationAndValidationGUI(
            double macroPixelSizeMicrons,
            boolean invertedX,
            boolean invertedY
    ) {
        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.CONFIRMATION);
            alert.setTitle("Affine Transformation Setup");
            alert.setHeaderText("Affine Transformation Validation");
            alert.setContentText("Adjust and validate the affine transformation.\n" +
                    "Current macro pixel size: " + macroPixelSizeMicrons + " µm");

            alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            alert.showAndWait().ifPresentOrElse(button -> {
                if (button == javafx.scene.control.ButtonType.OK) {
                    // Perform initial scaling transform using existing method
                    AffineTransform transform = TransformationFunctions.setupAffineTransformation(
                            macroPixelSizeMicrons, invertedX, invertedY
                    );
                    future.complete(transform);
                } else {
                    future.complete(null);
                }
            }, () -> future.complete(null));
        });

        return future;
    }
}
