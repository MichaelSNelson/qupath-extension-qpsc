// New class: qupath.ext.qpsc.ui.GreenBoxPreviewController
package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.ext.qpsc.utilities.GreenBoxDetector;
import qupath.ext.qpsc.utilities.AffineTransformManager;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for previewing and confirming green box detection results
 * before proceeding with acquisition.
 */
public class GreenBoxPreviewController {

    /**
     * Shows a preview of green box detection and allows user to confirm or adjust.
     *
     * @param macroImage The macro image
     * @param savedParams Previously saved detection parameters (can be null)
     * @return CompletableFuture with confirmed detection result or null if cancelled
     */
    public static CompletableFuture<GreenBoxDetector.DetectionResult> showPreviewDialog(
            BufferedImage macroImage,
            GreenBoxDetector.DetectionParams savedParams) {

        CompletableFuture<GreenBoxDetector.DetectionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<GreenBoxDetector.DetectionResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Confirm Green Box Detection");
            dialog.setHeaderText("Please verify the detected scan area (green box)");
            dialog.setResizable(true);

            // Use saved params or defaults
            GreenBoxDetector.DetectionParams params = savedParams != null ?
                    savedParams : new GreenBoxDetector.DetectionParams();

            // Image view for preview
            ImageView previewView = new ImageView();
            previewView.setPreserveRatio(true);
            previewView.setFitWidth(600);

            // Parameter spinners
            Spinner<Double> greenThreshold = new Spinner<>(0.0, 1.0, params.greenThreshold, 0.05);
            Spinner<Double> saturationMin = new Spinner<>(0.0, 1.0, params.saturationMin, 0.05);
            Spinner<Integer> edgeThickness = new Spinner<>(1, 50, params.edgeThickness, 1);

            Label statusLabel = new Label();

            // Update preview function
            Runnable updatePreview = () -> {
                params.greenThreshold = greenThreshold.getValue();
                params.saturationMin = saturationMin.getValue();
                params.edgeThickness = edgeThickness.getValue();

                var result = GreenBoxDetector.detectGreenBox(macroImage, params);
                if (result != null) {
                    previewView.setImage(SwingFXUtils.toFXImage(result.getDebugImage(), null));
                    statusLabel.setText(String.format("Confidence: %.2f", result.getConfidence()));
                    statusLabel.setStyle("-fx-text-fill: " +
                            (result.getConfidence() > 0.7 ? "green" : "orange"));
                } else {
                    statusLabel.setText("No green box detected");
                    statusLabel.setStyle("-fx-text-fill: red");
                }
            };

            // Add listeners
            greenThreshold.valueProperty().addListener((obs, old, val) -> updatePreview.run());
            saturationMin.valueProperty().addListener((obs, old, val) -> updatePreview.run());
            edgeThickness.valueProperty().addListener((obs, old, val) -> updatePreview.run());

            // Initial preview
            updatePreview.run();

            // Layout
            VBox content = new VBox(10);
            content.getChildren().addAll(
                    previewView,
                    statusLabel,
                    new Separator(),
                    new Label("Adjust parameters if needed:"),
                    new HBox(10, new Label("Green threshold:"), greenThreshold),
                    new HBox(10, new Label("Min saturation:"), saturationMin),
                    new HBox(10, new Label("Edge thickness:"), edgeThickness)
            );

            dialog.getDialogPane().setContent(content);

            ButtonType confirmType = new ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(confirmType, cancelType);

            dialog.setResultConverter(button -> {
                if (button == confirmType) {
                    return GreenBoxDetector.detectGreenBox(macroImage, params);
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(
                    result -> future.complete(result),
                    () -> future.complete(null)
            );
        });

        return future;
    }
}