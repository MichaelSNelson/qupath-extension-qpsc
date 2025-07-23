package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.ext.qpsc.utilities.GreenBoxDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for previewing and confirming green box detection results
 * before proceeding with acquisition.
 *
 * @since 0.3.1
 */
public class GreenBoxPreviewController {
    private static final Logger logger = LoggerFactory.getLogger(GreenBoxPreviewController.class);

    /**
     * Shows a preview dialog for green box detection allowing user to confirm or adjust parameters.
     * Initially shows the macro image, then allows detection preview with adjustable parameters.
     *
     * @param macroImage The macro image to analyze
     * @param savedParams Previously saved detection parameters, or null to use defaults
     * @return CompletableFuture with confirmed detection result containing the detected box,
     *         confidence score, and debug image, or null if cancelled
     */
    public static CompletableFuture<GreenBoxDetector.DetectionResult> showPreviewDialog(
            BufferedImage macroImage,
            GreenBoxDetector.DetectionParams savedParams) {

        CompletableFuture<GreenBoxDetector.DetectionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<GreenBoxDetector.DetectionResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Green Box Detection");
            dialog.setHeaderText("Adjust parameters and preview green box detection");
            dialog.setResizable(true);

            // Use saved params or defaults
            GreenBoxDetector.DetectionParams params = savedParams != null ?
                    savedParams : new GreenBoxDetector.DetectionParams();

            // Keep track of current result
            final GreenBoxDetector.DetectionResult[] currentResult = new GreenBoxDetector.DetectionResult[1];
            final boolean[] hasDetected = {false};

            // Image view for preview
            ImageView previewView = new ImageView();
            previewView.setPreserveRatio(true);
            previewView.setFitWidth(600);

            // Initially show the original macro image
            previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));

            // Parameter controls - only the essential ones
            Spinner<Integer> edgeThickness = new Spinner<>(1, 20, params.edgeThickness, 1);
            edgeThickness.setEditable(true);
            edgeThickness.setPrefWidth(80);

            Spinner<Double> greenThreshold = new Spinner<>(0.0, 1.0, params.greenThreshold, 0.05);
            greenThreshold.setEditable(true);
            greenThreshold.setPrefWidth(80);

            Label statusLabel = new Label("Click 'Detect Green Box' to preview detection");
            statusLabel.setStyle("-fx-text-fill: gray");

            // Preview button
            Button detectButton = new Button("Detect Green Box");
            detectButton.setOnAction(e -> {
                // Update parameters
                params.edgeThickness = edgeThickness.getValue();
                params.greenThreshold = greenThreshold.getValue();

                // Perform detection
                var result = GreenBoxDetector.detectGreenBox(macroImage, params);
                currentResult[0] = result;
                hasDetected[0] = true;

                if (result != null) {
                    // Show the debug image with green box overlay
                    previewView.setImage(SwingFXUtils.toFXImage(result.getDebugImage(), null));

                    statusLabel.setText(String.format("Green box detected at (%.0f, %.0f) - Size: %.0f x %.0f pixels - Confidence: %.2f",
                            result.getDetectedBox().getBoundsX(),
                            result.getDetectedBox().getBoundsY(),
                            result.getDetectedBox().getBoundsWidth(),
                            result.getDetectedBox().getBoundsHeight(),
                            result.getConfidence()));

                    if (result.getConfidence() > 0.7) {
                        statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold");
                    } else {
                        statusLabel.setStyle("-fx-text-fill: orange");
                    }
                } else {
                    statusLabel.setText("No green box detected - try adjusting parameters");
                    statusLabel.setStyle("-fx-text-fill: red");
                    // Keep showing the original image
                    previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                }
            });

            // Reset button to go back to original image
            Button resetButton = new Button("Reset View");
            resetButton.setOnAction(e -> {
                previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                statusLabel.setText("Click 'Detect Green Box' to preview detection");
                statusLabel.setStyle("-fx-text-fill: gray");
                hasDetected[0] = false;
                currentResult[0] = null;
            });

            // Layout
            VBox content = new VBox(10);

            // Image display
            content.getChildren().addAll(
                    previewView,
                    statusLabel,
                    new Separator()
            );

            // Parameter controls
            VBox paramBox = new VBox(5);
            paramBox.getChildren().addAll(
                    new Label("Detection Parameters:"),
                    new HBox(10, new Label("Edge thickness:"), edgeThickness, new Label("pixels")),
                    new HBox(10, new Label("Green threshold:"), greenThreshold)
            );

            // Buttons
            HBox buttonBox = new HBox(10);
            buttonBox.getChildren().addAll(detectButton, resetButton);

            content.getChildren().addAll(paramBox, buttonBox);

            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefWidth(700);

            ButtonType confirmType = new ButtonType("Use This Detection", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(confirmType, cancelType);

            // Only enable confirm button if detection has been performed and found a box
            Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirmType);
            confirmButton.setDisable(true);

            // Enable confirm button only when a successful detection exists
            detectButton.setOnAction(e -> {
                detectButton.getOnAction().handle(e); // Call the original handler
                confirmButton.setDisable(currentResult[0] == null);
            });

            dialog.setResultConverter(button -> {
                if (button == confirmType && currentResult[0] != null) {
                    logger.info("User confirmed green box detection with parameters: " +
                            "threshold={}, edge={}", params.greenThreshold, params.edgeThickness);
                    return currentResult[0];
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