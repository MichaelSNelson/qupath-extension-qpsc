package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.BirefringenceOptimizationDialog;
import qupath.lib.gui.dialogs.Dialogs;

import java.awt.Desktop;
import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * BirefringenceOptimizationWorkflow - Find optimal polarizer angle for maximum birefringence signal
 *
 * This workflow provides a way to systematically test polarizer angles and identify
 * the angle that maximizes birefringence signal contrast:
 * <ol>
 *   <li>User positions microscope on tissue with visible birefringence</li>
 *   <li>Dialog collects test parameters (angle range, step size, exposure mode)</li>
 *   <li>Test acquires paired images (+theta, -theta) across angle range</li>
 *   <li>Analysis computes signal difference and finds optimal angle</li>
 *   <li>Results displayed with visualization plot</li>
 * </ol>
 *
 * The test supports three exposure modes:
 * <ul>
 *   <li><strong>Interpolate</strong> (default) - Use calibration exposures from sensitivity test</li>
 *   <li><strong>Calibrate</strong> - Measure exposures on background first, then acquire</li>
 *   <li><strong>Fixed</strong> - Use same exposure for all angles (fastest, may saturate)</li>
 * </ul>
 *
 * Results include:
 * <ul>
 *   <li>Optimal angle (raw difference method)</li>
 *   <li>Optimal angle (normalized difference method)</li>
 *   <li>Signal metrics at optimal angles</li>
 *   <li>Visualization plot showing signal vs angle</li>
 *   <li>Sanity check at 0 degrees (should be near zero)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class BirefringenceOptimizationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(BirefringenceOptimizationWorkflow.class);

    /**
     * Main entry point for birefringence optimization workflow.
     * Shows UI for parameter input, then executes test.
     */
    public static void run() {
        logger.info("Starting birefringence optimization workflow");

        Platform.runLater(() -> {
            try {
                // Show dialog and collect parameters
                BirefringenceOptimizationDialog.showDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Birefringence optimization parameters received");

                            // Execute test
                            CompletableFuture.runAsync(() -> {
                                executeOptimization(params);
                            }).exceptionally(ex -> {
                                logger.error("Birefringence optimization failed", ex);
                                Platform.runLater(() -> {
                                    Dialogs.showErrorMessage("Birefringence Optimization Error",
                                            "Failed to execute optimization: " + ex.getMessage());
                                });
                                return null;
                            });
                        } else {
                            logger.info("Birefringence optimization cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in birefringence optimization dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Optimization Error",
                                "Failed to show optimization dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start birefringence optimization workflow", e);
                Dialogs.showErrorMessage("Optimization Error",
                        "Failed to start birefringence optimization: " + e.getMessage());
            }
        });
    }

    /**
     * Executes the birefringence optimization via socket communication with progress updates.
     *
     * @param params Optimization parameters from dialog
     */
    private static void executeOptimization(BirefringenceOptimizationDialog.BirefringenceParams params) {
        logger.info("Executing birefringence optimization: {} to {} deg, step {}",
                params.minAngle(), params.maxAngle(), params.angleStep());

        // Create progress dialog with percentage display
        Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
        progressDialog.setTitle("Optimization In Progress");
        progressDialog.setHeaderText("PPM Birefringence Optimization Running");

        // Create a VBox with progress info that can be updated
        javafx.scene.control.Label progressLabel = new javafx.scene.control.Label("Starting optimization...");
        progressLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        javafx.scene.control.Label infoLabel = new javafx.scene.control.Label(
                "The test acquires paired images at symmetric angles (+theta, -theta)\n" +
                "to measure birefringence signal strength across the angle range.\n\n" +
                "The dialog will close automatically when the test completes."
        );

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.getChildren().addAll(progressLabel, infoLabel);
        progressDialog.getDialogPane().setContent(content);

        progressDialog.getButtonTypes().clear();
        progressDialog.getButtonTypes().add(ButtonType.CANCEL);

        // Show progress dialog
        Platform.runLater(() -> progressDialog.show());

        try {
            // Get microscope configuration
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            String serverHost = QPPreferenceDialog.getMicroscopeServerHost();
            int serverPort = QPPreferenceDialog.getMicroscopeServerPort();

            // Connect to microscope server
            logger.info("Connecting to microscope server at {}:{}", serverHost, serverPort);
            MicroscopeSocketClient socketClient = new MicroscopeSocketClient(serverHost, serverPort);
            socketClient.connect();

            logger.info("Sending birefringence optimization command:");
            logger.info("  Config: {}", configFileLocation);
            logger.info("  Output: {}", params.outputFolder());
            logger.info("  Angle range: {} to {} deg", params.minAngle(), params.maxAngle());
            logger.info("  Step size: {} deg", params.angleStep());
            logger.info("  Exposure mode: {}", params.exposureMode());
            if (params.fixedExposureMs() != null) {
                logger.info("  Fixed exposure: {} ms", params.fixedExposureMs());
            }
            logger.info("  Target intensity: {}", params.targetIntensity());

            // Create progress callback to update the dialog
            java.util.function.BiConsumer<Integer, Integer> progressCallback = (current, total) -> {
                int percent = (int) ((current * 100.0) / total);
                Platform.runLater(() -> {
                    progressLabel.setText(String.format("Progress: %d%% (%d/%d angle pairs)", percent, current, total));
                });
            };

            // Call the socket client method with progress callback
            String resultPath = socketClient.runBirefringenceOptimization(
                    configFileLocation,
                    params.outputFolder(),
                    params.minAngle(),
                    params.maxAngle(),
                    params.angleStep(),
                    params.exposureMode(),
                    params.fixedExposureMs(),
                    params.targetIntensity(),
                    progressCallback
            );

            logger.info("Birefringence optimization completed successfully");
            logger.info("Results saved to: {}", resultPath);

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show success dialog with results
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Optimization Complete");
                alert.setHeaderText("Birefringence optimization completed successfully!");
                alert.setContentText(
                        "Results saved to:\n" + resultPath + "\n\n" +
                        "The results directory contains:\n" +
                        "  - birefringence_results.json: Optimal angles and signal metrics\n" +
                        "  - birefringence_analysis.png: Visualization plot\n" +
                        "  - raw_difference_*.tif: Difference images (if keep_images=true)\n" +
                        "  - normalized_difference_*.tif: Normalized differences\n\n" +
                        "Review the JSON file and plot to identify the optimal angle.\n\n" +
                        "Would you like to open the results folder?"
                );

                ButtonType openFolder = new ButtonType("Open Folder");
                ButtonType close = new ButtonType("Close");
                alert.getButtonTypes().setAll(openFolder, close);

                alert.showAndWait().ifPresent(response -> {
                    if (response == openFolder) {
                        try {
                            File resultsDir = new File(resultPath);
                            if (resultsDir.exists()) {
                                Desktop.getDesktop().open(resultsDir);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to open results folder", e);
                            Dialogs.showErrorMessage("Error",
                                    "Failed to open results folder: " + e.getMessage());
                        }
                    }
                });
            });

        } catch (Exception e) {
            logger.error("Birefringence optimization failed", e);

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show error
            Platform.runLater(() -> {
                Dialogs.showErrorMessage("Optimization Failed",
                        "Failed to complete birefringence optimization:\n" + e.getMessage());
            });
        }
    }
}
