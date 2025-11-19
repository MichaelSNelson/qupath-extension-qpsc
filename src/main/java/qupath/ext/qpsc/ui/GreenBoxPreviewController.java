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
 * Controller for interactive green box detection and parameter adjustment.
 *
 * <p>The green box is a visual marker added by slide scanners to indicate the
 * digitized region of a slide. This controller provides a user interface for:
 * <ul>
 *   <li>Previewing green box detection on macro images</li>
 *   <li>Adjusting detection parameters in real-time</li>
 *   <li>Confirming successful detection for use in alignment workflows</li>
 * </ul>
 *
 * <p>The detection process uses color thresholding to identify green rectangular
 * regions in the macro image. Parameters can be adjusted to handle variations
 * in scanner output and image quality.
 *
 * <h3>Typical Workflow:</h3>
 * <ol>
 *   <li>Dialog opens showing the original macro image</li>
 *   <li>User adjusts parameters if needed (edge thickness, green threshold)</li>
 *   <li>User clicks "Detect Green Box" to preview detection</li>
 *   <li>If successful, an overlay shows the detected box with confidence score</li>
 *   <li>User can reset and try again or confirm the detection</li>
 * </ol>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
 * GreenBoxDetector.DetectionParams savedParams = transformPreset.getGreenBoxParams();
 *
 * GreenBoxPreviewController.showPreviewDialog(macroImage, savedParams)
 *     .thenAccept(result -> {
 *         if (result != null) {
 *             ROI greenBox = result.getDetectedBox();
 *             // Use green box for alignment...
 *         }
 *     });
 * }</pre>
 *
 * @since 0.3.1
 * @author Mike Nelson
 */
public class GreenBoxPreviewController {
    private static final Logger logger = LoggerFactory.getLogger(GreenBoxPreviewController.class);

    /**
     * Shows an interactive dialog for green box detection with parameter adjustment.
     *
     * <p>This method creates a modal dialog that allows users to:
     * <ul>
     *   <li>View the macro image</li>
     *   <li>Adjust detection parameters</li>
     *   <li>Preview detection results with visual overlay</li>
     *   <li>Confirm successful detection</li>
     * </ul>
     *
     * <p>The dialog starts by showing the original macro image. Users must click
     * "Detect Green Box" to run detection. The result is displayed as an overlay
     * on the image, showing the detected box in red with confidence information.
     *
     * @param macroImage The macro image to analyze. This should be the full macro
     *                   image from the slide scanner, potentially already cropped
     *                   and flipped according to preferences.
     * @param savedParams Previously saved detection parameters to use as defaults,
     *                    or null to use system defaults. If provided, these typically
     *                    come from a saved transform preset.
     * @return CompletableFuture that resolves to:
     *         <ul>
     *           <li>DetectionResult with the detected box, confidence score, and
     *               debug image if user confirms the detection</li>
     *           <li>null if user cancels or closes the dialog</li>
     *         </ul>
     * @throws IllegalArgumentException if macroImage is null
     */
    public static CompletableFuture<GreenBoxDetector.DetectionResult> showPreviewDialog(
            BufferedImage macroImage,
            GreenBoxDetector.DetectionParams savedParams) {

        if (macroImage == null) {
            throw new IllegalArgumentException("Macro image cannot be null");
        }

        logger.info("Opening green box preview dialog - Image size: {}x{}, Has saved params: {}",
                macroImage.getWidth(), macroImage.getHeight(), savedParams != null);

        CompletableFuture<GreenBoxDetector.DetectionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Dialog<GreenBoxDetector.DetectionResult> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Green Box Detection");
                dialog.setHeaderText("Adjust parameters and preview green box detection");
                dialog.setResizable(true);

                // Use saved params or defaults
                GreenBoxDetector.DetectionParams params = savedParams != null ?
                        savedParams : new GreenBoxDetector.DetectionParams();

                logger.debug("Initial parameters - Edge thickness: {}, Green threshold: {}",
                        params.edgeThickness, params.greenThreshold);

                // Keep track of current result
                final GreenBoxDetector.DetectionResult[] currentResult = new GreenBoxDetector.DetectionResult[1];
                final boolean[] hasDetected = {false};

                // DECLARE confirmButton as final array to allow access in lambda
                final Button[] confirmButtonHolder = new Button[1];

                // Image view for preview
                ImageView previewView = new ImageView();
                previewView.setPreserveRatio(true);
                previewView.setFitWidth(600);

                // Initially show the original macro image
                previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                logger.debug("Initialized preview with macro image");

                // Parameter controls - only the essential ones
                Spinner<Integer> edgeThickness = new Spinner<>(1, 20, params.edgeThickness, 1);
                edgeThickness.setEditable(true);
                edgeThickness.setPrefWidth(80);

                Spinner<Double> greenThreshold = new Spinner<>(0.0, 1.0, params.greenThreshold, 0.05);
                greenThreshold.setEditable(true);
                greenThreshold.setPrefWidth(80);

                Spinner<Double> hueMin = new Spinner<>(0.0, 1.0, params.hueMin, 0.01);
                hueMin.setEditable(true);
                hueMin.setPrefWidth(80);

                Spinner<Double> hueMax = new Spinner<>(0.0, 1.0, params.hueMax, 0.01);
                hueMax.setEditable(true);
                hueMax.setPrefWidth(80);

                Label statusLabel = new Label("Click 'Detect Green Box' to preview detection");
                statusLabel.setStyle("-fx-text-fill: gray");

                // Preview button
                Button detectButton = new Button("Detect Green Box");
                detectButton.setOnAction(e -> {
                    // Update parameters
                    params.edgeThickness = edgeThickness.getValue();
                    params.greenThreshold = greenThreshold.getValue();
                    params.hueMin = hueMin.getValue();
                    params.hueMax = hueMax.getValue();

                    logger.info("Running green box detection - Edge: {}, Threshold: {}, Hue: [{}, {}]",
                            params.edgeThickness, params.greenThreshold, params.hueMin, params.hueMax);

                    // Perform detection
                    var result = GreenBoxDetector.detectGreenBox(macroImage, params);
                    currentResult[0] = result;
                    hasDetected[0] = true;

                    if (result != null) {
                        logger.info("Green box detected successfully - Location: ({}, {}), Size: {}x{}, Confidence: {}",
                                result.getDetectedBox().getBoundsX(),
                                result.getDetectedBox().getBoundsY(),
                                result.getDetectedBox().getBoundsWidth(),
                                result.getDetectedBox().getBoundsHeight(),
                                result.getConfidence());

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
                            logger.debug("High confidence detection (>0.7)");
                        } else {
                            statusLabel.setStyle("-fx-text-fill: orange");
                            logger.warn("Low confidence detection ({}) - user may want to adjust parameters",
                                    result.getConfidence());
                        }

                        // Enable confirm button when detection succeeds
                        if (confirmButtonHolder[0] != null) {
                            confirmButtonHolder[0].setDisable(false);
                            logger.debug("Enabled confirm button");
                        }
                    } else {
                        logger.warn("No green box detected with current parameters");
                        statusLabel.setText("No green box detected - try adjusting parameters");
                        statusLabel.setStyle("-fx-text-fill: red");
                        // Keep showing the original image
                        previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));

                        // Disable confirm button when no detection
                        if (confirmButtonHolder[0] != null) {
                            confirmButtonHolder[0].setDisable(true);
                        }
                    }
                });

                // Reset button to go back to original image
                Button resetButton = new Button("Reset View");
                resetButton.setOnAction(e -> {
                    logger.debug("Resetting view to original image");
                    previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                    statusLabel.setText("Click 'Detect Green Box' to preview detection");
                    statusLabel.setStyle("-fx-text-fill: gray");
                    hasDetected[0] = false;
                    currentResult[0] = null;

                    // Disable confirm button on reset
                    if (confirmButtonHolder[0] != null) {
                        confirmButtonHolder[0].setDisable(true);
                    }
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
                        new HBox(10, new Label("Green threshold:"), greenThreshold),
                        new HBox(10, new Label("Hue min:"), hueMin, new Label("(0.0-1.0)")),
                        new HBox(10, new Label("Hue max:"), hueMax, new Label("(0.0-1.0)"))
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

                // NOW we can get the confirm button and store it in our holder
                Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirmType);
                confirmButtonHolder[0] = confirmButton;
                confirmButton.setDisable(true);
                logger.debug("Dialog initialized, confirm button disabled initially");

                dialog.setResultConverter(button -> {
                    if (button == confirmType && currentResult[0] != null) {
                        logger.info("User confirmed green box detection - Final parameters: " +
                                "threshold={}, edge={}", params.greenThreshold, params.edgeThickness);

                        // Save the successful parameters
                        params.saveToPreferences();
                        logger.debug("Saved detection parameters to preferences");

                        return currentResult[0];
                    } else if (button == cancelType) {
                        logger.info("User cancelled green box detection dialog");
                    }
                    return null;
                });

                logger.debug("Showing green box detection dialog");
                dialog.showAndWait().ifPresentOrElse(
                        result -> {
                            if (result != null) {
                                logger.info("Green box detection completed successfully");
                            } else {
                                logger.info("Green box detection completed without result");
                            }
                            future.complete(result);
                        },
                        () -> {
                            logger.info("Green box detection dialog closed without selection");
                            future.complete(null);
                        }
                );

            } catch (Exception e) {
                logger.error("Error in green box preview dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}