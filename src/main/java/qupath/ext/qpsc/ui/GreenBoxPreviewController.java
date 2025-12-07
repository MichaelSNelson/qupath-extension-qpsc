package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
                dialog.setHeaderText("Detecting slide boundary for alignment");
                dialog.setResizable(true);

                // Use saved params or defaults
                GreenBoxDetector.DetectionParams params = savedParams != null ?
                        savedParams : new GreenBoxDetector.DetectionParams();

                logger.debug("Initial parameters - Edge thickness: {}, Green threshold: {}",
                        params.edgeThickness, params.greenThreshold);

                // Keep track of current result and detection state
                final GreenBoxDetector.DetectionResult[] currentResult = new GreenBoxDetector.DetectionResult[1];
                final boolean[] autoDetectionSkipped = {false};
                final Task<GreenBoxDetector.DetectionResult>[] currentTask = new Task[1];

                // DECLARE confirmButton as final array to allow access in lambda
                final Button[] confirmButtonHolder = new Button[1];

                // Image view for preview
                ImageView previewView = new ImageView();
                previewView.setPreserveRatio(true);
                previewView.setFitWidth(600);
                previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));

                // Status label with confidence display
                Label statusLabel = new Label("Auto-detecting green box...");
                statusLabel.setStyle("-fx-text-fill: #1976D2; -fx-font-size: 12px;");

                // Progress indicator for auto-detection
                ProgressIndicator progressIndicator = new ProgressIndicator();
                progressIndicator.setPrefSize(24, 24);

                // Status row with progress indicator
                HBox statusRow = new HBox(10);
                statusRow.setAlignment(Pos.CENTER_LEFT);
                statusRow.getChildren().addAll(progressIndicator, statusLabel);

                // Skip auto-detection button
                Button skipButton = new Button("Skip Auto-Detection");
                skipButton.setStyle("-fx-font-size: 11px;");

                // === BASIC PARAMETERS (always visible) ===
                Spinner<Double> greenThreshold = new Spinner<>(0.0, 1.0, params.greenThreshold, 0.05);
                greenThreshold.setEditable(true);
                greenThreshold.setPrefWidth(80);

                Spinner<Double> saturationMin = new Spinner<>(0.0, 1.0, params.saturationMin, 0.05);
                saturationMin.setEditable(true);
                saturationMin.setPrefWidth(80);

                VBox basicParamsBox = new VBox(5);
                basicParamsBox.getChildren().addAll(
                        new HBox(10, new Label("Green threshold:"), greenThreshold),
                        new HBox(10, new Label("Min saturation:"), saturationMin)
                );

                // === ADVANCED PARAMETERS (in collapsible TitledPane) ===
                Spinner<Integer> edgeThickness = new Spinner<>(1, 20, params.edgeThickness, 1);
                edgeThickness.setEditable(true);
                edgeThickness.setPrefWidth(80);

                Spinner<Double> hueMin = new Spinner<>(0.0, 1.0, params.hueMin, 0.01);
                hueMin.setEditable(true);
                hueMin.setPrefWidth(80);

                Spinner<Double> hueMax = new Spinner<>(0.0, 1.0, params.hueMax, 0.01);
                hueMax.setEditable(true);
                hueMax.setPrefWidth(80);

                Spinner<Integer> minBoxWidth = new Spinner<>(10, 500, params.minBoxWidth, 10);
                minBoxWidth.setEditable(true);
                minBoxWidth.setPrefWidth(80);

                Spinner<Integer> minBoxHeight = new Spinner<>(10, 500, params.minBoxHeight, 10);
                minBoxHeight.setEditable(true);
                minBoxHeight.setPrefWidth(80);

                Spinner<Double> brightnessMin = new Spinner<>(0.0, 1.0, params.brightnessMin, 0.05);
                brightnessMin.setEditable(true);
                brightnessMin.setPrefWidth(80);

                Spinner<Double> brightnessMax = new Spinner<>(0.0, 1.0, params.brightnessMax, 0.05);
                brightnessMax.setEditable(true);
                brightnessMax.setPrefWidth(80);

                VBox advancedParamsContent = new VBox(5);
                advancedParamsContent.setPadding(new Insets(10));
                advancedParamsContent.getChildren().addAll(
                        new HBox(10, new Label("Edge thickness:"), edgeThickness, new Label("pixels")),
                        new HBox(10, new Label("Hue min:"), hueMin),
                        new HBox(10, new Label("Hue max:"), hueMax),
                        new HBox(10, new Label("Min box width:"), minBoxWidth, new Label("pixels")),
                        new HBox(10, new Label("Min box height:"), minBoxHeight, new Label("pixels")),
                        new HBox(10, new Label("Brightness min:"), brightnessMin),
                        new HBox(10, new Label("Brightness max:"), brightnessMax)
                );

                TitledPane advancedPane = new TitledPane("Advanced Parameters", advancedParamsContent);
                advancedPane.setExpanded(false);

                // === PRESET BUTTONS ===
                Button presetHighSensitivity = new Button("High Sensitivity");
                presetHighSensitivity.setStyle("-fx-font-size: 10px;");
                presetHighSensitivity.setOnAction(e -> {
                    greenThreshold.getValueFactory().setValue(0.15);
                    saturationMin.getValueFactory().setValue(0.2);
                    brightnessMin.getValueFactory().setValue(0.2);
                    brightnessMax.getValueFactory().setValue(0.95);
                    logger.debug("Applied High Sensitivity preset");
                });

                Button presetBalanced = new Button("Balanced");
                presetBalanced.setStyle("-fx-font-size: 10px;");
                presetBalanced.setOnAction(e -> {
                    greenThreshold.getValueFactory().setValue(0.25);
                    saturationMin.getValueFactory().setValue(0.3);
                    brightnessMin.getValueFactory().setValue(0.3);
                    brightnessMax.getValueFactory().setValue(0.9);
                    logger.debug("Applied Balanced preset");
                });

                Button presetHighPrecision = new Button("High Precision");
                presetHighPrecision.setStyle("-fx-font-size: 10px;");
                presetHighPrecision.setOnAction(e -> {
                    greenThreshold.getValueFactory().setValue(0.35);
                    saturationMin.getValueFactory().setValue(0.4);
                    brightnessMin.getValueFactory().setValue(0.4);
                    brightnessMax.getValueFactory().setValue(0.85);
                    logger.debug("Applied High Precision preset");
                });

                HBox presetBox = new HBox(10);
                presetBox.setAlignment(Pos.CENTER_LEFT);
                presetBox.getChildren().addAll(new Label("Presets:"), presetHighSensitivity, presetBalanced, presetHighPrecision);

                // === PARAMETERS CONTAINER (collapsible based on confidence) ===
                TitledPane paramsPane = new TitledPane();
                paramsPane.setText("Detection Parameters");

                VBox allParamsBox = new VBox(10);
                allParamsBox.setPadding(new Insets(10));
                allParamsBox.getChildren().addAll(basicParamsBox, advancedPane, presetBox);
                paramsPane.setContent(allParamsBox);
                paramsPane.setExpanded(false);  // Collapsed by default

                // === ACTION BUTTONS ===
                Button detectButton = new Button("Detect Again");
                detectButton.setOnAction(e -> runDetection(macroImage, params, greenThreshold, saturationMin,
                        edgeThickness, hueMin, hueMax, minBoxWidth, minBoxHeight, brightnessMin, brightnessMax,
                        previewView, statusLabel, statusRow, progressIndicator, paramsPane, confirmButtonHolder, currentResult));

                Button resetButton = new Button("Reset View");
                resetButton.setOnAction(e -> {
                    logger.debug("Resetting view to original image");
                    previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                    statusLabel.setText("Click 'Detect Again' to run detection");
                    statusLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 12px;");
                    currentResult[0] = null;
                    if (confirmButtonHolder[0] != null) {
                        confirmButtonHolder[0].setDisable(true);
                    }
                });

                HBox actionButtonBox = new HBox(10);
                actionButtonBox.setAlignment(Pos.CENTER_LEFT);
                actionButtonBox.getChildren().addAll(detectButton, resetButton);

                // === MAIN LAYOUT ===
                VBox content = new VBox(10);
                content.setPadding(new Insets(10));
                content.getChildren().addAll(
                        previewView,
                        statusRow,
                        skipButton,
                        new Separator(),
                        paramsPane,
                        actionButtonBox
                );

                dialog.getDialogPane().setContent(content);
                dialog.getDialogPane().setPrefWidth(700);

                ButtonType confirmType = new ButtonType("Use This Detection", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(confirmType, cancelType);

                Button confirmButton = (Button) dialog.getDialogPane().lookupButton(confirmType);
                confirmButtonHolder[0] = confirmButton;
                confirmButton.setDisable(true);

                // === SKIP AUTO-DETECTION HANDLER ===
                skipButton.setOnAction(e -> {
                    autoDetectionSkipped[0] = true;
                    if (currentTask[0] != null) {
                        currentTask[0].cancel();
                    }
                    progressIndicator.setVisible(false);
                    statusRow.getChildren().remove(progressIndicator);
                    statusLabel.setText("Auto-detection skipped. Adjust parameters and click 'Detect Again'");
                    statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
                    skipButton.setVisible(false);
                    paramsPane.setExpanded(true);
                    logger.info("User skipped auto-detection");
                });

                // === AUTO-DETECTION ON DIALOG OPEN ===
                Task<GreenBoxDetector.DetectionResult> autoDetectTask = new Task<>() {
                    @Override
                    protected GreenBoxDetector.DetectionResult call() {
                        logger.info("Starting auto-detection of green box");
                        return GreenBoxDetector.detectGreenBox(macroImage, params);
                    }
                };
                currentTask[0] = autoDetectTask;

                autoDetectTask.setOnSucceeded(e -> {
                    if (autoDetectionSkipped[0]) return;

                    GreenBoxDetector.DetectionResult result = autoDetectTask.getValue();
                    currentResult[0] = result;
                    progressIndicator.setVisible(false);
                    statusRow.getChildren().remove(progressIndicator);
                    skipButton.setVisible(false);

                    if (result != null) {
                        previewView.setImage(SwingFXUtils.toFXImage(result.getDebugImage(), null));

                        if (result.getConfidence() > 0.7) {
                            // HIGH CONFIDENCE - collapse parameters, show success
                            statusLabel.setText(String.format("[OK] Green box detected! Confidence: %.0f%% - Location: (%.0f, %.0f)",
                                    result.getConfidence() * 100,
                                    result.getDetectedBox().getBoundsX(),
                                    result.getDetectedBox().getBoundsY()));
                            statusLabel.setStyle("-fx-text-fill: #2E7D32; -fx-font-weight: bold; -fx-font-size: 12px;");
                            paramsPane.setExpanded(false);
                            paramsPane.setText("Detection Parameters (adjust if needed)");
                            confirmButton.setDisable(false);
                            logger.info("Auto-detection succeeded with high confidence: {}", result.getConfidence());
                        } else {
                            // LOW CONFIDENCE - expand parameters, show warning
                            statusLabel.setText(String.format("[!] Detection uncertain (%.0f%%) - Consider adjusting parameters",
                                    result.getConfidence() * 100));
                            statusLabel.setStyle("-fx-text-fill: #E65100; -fx-font-weight: bold; -fx-font-size: 12px;");
                            paramsPane.setExpanded(true);
                            paramsPane.setText("Detection Parameters (adjustment recommended)");
                            confirmButton.setDisable(false);
                            logger.warn("Auto-detection completed with low confidence: {}", result.getConfidence());
                        }
                    } else {
                        // DETECTION FAILED - expand parameters
                        statusLabel.setText("[X] No green box detected - Please adjust parameters and try again");
                        statusLabel.setStyle("-fx-text-fill: #C62828; -fx-font-weight: bold; -fx-font-size: 12px;");
                        paramsPane.setExpanded(true);
                        advancedPane.setExpanded(true);
                        confirmButton.setDisable(true);
                        logger.warn("Auto-detection failed to find green box");
                    }
                });

                autoDetectTask.setOnFailed(e -> {
                    if (autoDetectionSkipped[0]) return;
                    progressIndicator.setVisible(false);
                    statusRow.getChildren().remove(progressIndicator);
                    skipButton.setVisible(false);
                    statusLabel.setText("[X] Detection error - Please adjust parameters and try again");
                    statusLabel.setStyle("-fx-text-fill: #C62828; -fx-font-size: 12px;");
                    paramsPane.setExpanded(true);
                    logger.error("Auto-detection task failed", autoDetectTask.getException());
                });

                // Start auto-detection in background thread
                Thread autoDetectThread = new Thread(autoDetectTask);
                autoDetectThread.setDaemon(true);
                autoDetectThread.start();

                dialog.setResultConverter(button -> {
                    if (button == confirmType && currentResult[0] != null) {
                        logger.info("User confirmed green box detection - Final parameters: threshold={}, edge={}",
                                params.greenThreshold, params.edgeThickness);
                        params.saveToPreferences();
                        return currentResult[0];
                    } else if (button == cancelType) {
                        logger.info("User cancelled green box detection dialog");
                        if (currentTask[0] != null) {
                            currentTask[0].cancel();
                        }
                    }
                    return null;
                });

                logger.debug("Showing green box detection dialog with auto-detection");
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

    /**
     * Runs detection with current parameter values and updates UI accordingly.
     */
    private static void runDetection(BufferedImage macroImage, GreenBoxDetector.DetectionParams params,
                                     Spinner<Double> greenThreshold, Spinner<Double> saturationMin,
                                     Spinner<Integer> edgeThickness, Spinner<Double> hueMin, Spinner<Double> hueMax,
                                     Spinner<Integer> minBoxWidth, Spinner<Integer> minBoxHeight,
                                     Spinner<Double> brightnessMin, Spinner<Double> brightnessMax,
                                     ImageView previewView, Label statusLabel, HBox statusRow,
                                     ProgressIndicator progressIndicator, TitledPane paramsPane,
                                     Button[] confirmButtonHolder, GreenBoxDetector.DetectionResult[] currentResult) {

        // Update parameters from spinners
        params.greenThreshold = greenThreshold.getValue();
        params.saturationMin = saturationMin.getValue();
        params.edgeThickness = edgeThickness.getValue();
        params.hueMin = hueMin.getValue();
        params.hueMax = hueMax.getValue();
        params.minBoxWidth = minBoxWidth.getValue();
        params.minBoxHeight = minBoxHeight.getValue();
        params.brightnessMin = brightnessMin.getValue();
        params.brightnessMax = brightnessMax.getValue();

        logger.info("Running manual detection with params - threshold: {}, saturation: {}, edge: {}",
                params.greenThreshold, params.saturationMin, params.edgeThickness);

        // Show progress
        if (!statusRow.getChildren().contains(progressIndicator)) {
            statusRow.getChildren().add(0, progressIndicator);
        }
        progressIndicator.setVisible(true);
        statusLabel.setText("Detecting...");
        statusLabel.setStyle("-fx-text-fill: #1976D2; -fx-font-size: 12px;");

        // Run detection in background
        Task<GreenBoxDetector.DetectionResult> detectTask = new Task<>() {
            @Override
            protected GreenBoxDetector.DetectionResult call() {
                return GreenBoxDetector.detectGreenBox(macroImage, params);
            }
        };

        detectTask.setOnSucceeded(e -> {
            GreenBoxDetector.DetectionResult result = detectTask.getValue();
            currentResult[0] = result;
            progressIndicator.setVisible(false);
            statusRow.getChildren().remove(progressIndicator);

            if (result != null) {
                previewView.setImage(SwingFXUtils.toFXImage(result.getDebugImage(), null));

                statusLabel.setText(String.format("Detected at (%.0f, %.0f) - Size: %.0fx%.0f - Confidence: %.0f%%",
                        result.getDetectedBox().getBoundsX(),
                        result.getDetectedBox().getBoundsY(),
                        result.getDetectedBox().getBoundsWidth(),
                        result.getDetectedBox().getBoundsHeight(),
                        result.getConfidence() * 100));

                if (result.getConfidence() > 0.7) {
                    statusLabel.setStyle("-fx-text-fill: #2E7D32; -fx-font-weight: bold; -fx-font-size: 12px;");
                } else {
                    statusLabel.setStyle("-fx-text-fill: #E65100; -fx-font-weight: bold; -fx-font-size: 12px;");
                }

                if (confirmButtonHolder[0] != null) {
                    confirmButtonHolder[0].setDisable(false);
                }
            } else {
                statusLabel.setText("[X] No green box detected - Try adjusting parameters");
                statusLabel.setStyle("-fx-text-fill: #C62828; -fx-font-weight: bold; -fx-font-size: 12px;");
                previewView.setImage(SwingFXUtils.toFXImage(macroImage, null));
                if (confirmButtonHolder[0] != null) {
                    confirmButtonHolder[0].setDisable(true);
                }
            }
        });

        detectTask.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            statusRow.getChildren().remove(progressIndicator);
            statusLabel.setText("[X] Detection error");
            statusLabel.setStyle("-fx-text-fill: #C62828; -fx-font-size: 12px;");
            logger.error("Detection task failed", detectTask.getException());
        });

        Thread detectThread = new Thread(detectTask);
        detectThread.setDaemon(true);
        detectThread.start();
    }
}