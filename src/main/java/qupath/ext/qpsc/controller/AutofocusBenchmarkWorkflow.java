package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.AutofocusBenchmarkDialog;
import qupath.fx.dialogs.Dialogs;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Workflow for running autofocus parameter benchmarks.
 *
 * <p>This workflow guides users through systematic testing of autofocus parameters
 * to find optimal configurations for their microscope and samples. The workflow:
 * <ol>
 *   <li>Connects to microscope server and gets current Z position</li>
 *   <li>Shows configuration dialog for benchmark parameters</li>
 *   <li>Sends benchmark command to Python server</li>
 *   <li>Monitors long-running benchmark execution (10-60 minutes)</li>
 *   <li>Displays results summary with recommended settings</li>
 * </ol>
 *
 * <p>The benchmark tests various combinations of:
 * <ul>
 *   <li>n_steps (number of Z positions sampled)</li>
 *   <li>search_range (distance from current Z to search)</li>
 *   <li>interpolation methods (linear, quadratic, cubic)</li>
 *   <li>score metrics (laplacian_variance, sobel, brenner_gradient)</li>
 *   <li>standard vs adaptive autofocus algorithms</li>
 * </ul>
 *
 * <p>Results include:
 * <ul>
 *   <li>Success rate at different distances from focus</li>
 *   <li>Time-to-focus measurements for each configuration</li>
 *   <li>Focus accuracy (how close to true focus position)</li>
 *   <li>Recommended fastest and most accurate configurations</li>
 *   <li>Detailed CSV and JSON files for further analysis</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AutofocusBenchmarkWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(AutofocusBenchmarkWorkflow.class);

    /**
     * Runs the complete autofocus benchmark workflow.
     *
     * <p>This is the main entry point called from the QuPath menu.
     * The workflow is entirely asynchronous and will not block the UI thread.
     *
     * @throws IOException if initial server connection fails
     */
    public static void run() throws IOException {
        logger.info("Starting Autofocus Parameter Benchmark workflow");

        // Get server connection parameters
        String host = QPPreferenceDialog.getMicroscopeServerHost();
        int port = QPPreferenceDialog.getMicroscopeServerPort();

        logger.debug("Connecting to microscope server at {}:{}", host, port);

        // Create socket client
        MicroscopeSocketClient client = new MicroscopeSocketClient(host, port);

        try {
            // Connect to server
            client.connect();
            logger.info("Connected to microscope server");

            // Get current Z position
            double currentZ;
            try {
                currentZ = client.getStageZ();
                logger.info("Current stage Z position: {} um", currentZ);
            } catch (Exception e) {
                logger.warn("Could not get current Z position: {}", e.getMessage());
                currentZ = 0.0;
            }

            // Show configuration dialog
            final double zPos = currentZ;
            AutofocusBenchmarkDialog.showDialog(zPos, null)
                    .thenAccept(params -> {
                        if (params == null) {
                            logger.info("Benchmark cancelled by user");
                            closeClient(client);
                            return;
                        }

                        // Validate output directory exists
                        File outputDir = new File(params.outputPath());
                        if (!outputDir.exists()) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("Invalid Output Directory");
                                alert.setHeaderText("Output directory does not exist");
                                alert.setContentText("Please create the directory:\n" + params.outputPath());
                                alert.showAndWait();
                            });
                            closeClient(client);
                            return;
                        }

                        // Confirm with user before starting (benchmark takes a long time)
                        Platform.runLater(() -> {
                            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                            confirm.setTitle("Confirm Benchmark");
                            confirm.setHeaderText("Ready to start autofocus parameter benchmark");

                            String estimatedTime = params.quickMode() ? "10-15 minutes" : "30-60 minutes";

                            confirm.setContentText(
                                    String.format(
                                            "Reference Z: %.2f um\n" +
                                            "Test distances: %s\n" +
                                            "Mode: %s\n" +
                                            "Estimated time: %s\n\n" +
                                            "The benchmark will systematically test autofocus parameters. " +
                                            "Do not disturb the microscope during this time.\n\n" +
                                            "Continue?",
                                            params.referenceZ(),
                                            params.testDistances(),
                                            params.quickMode() ? "Quick" : "Full",
                                            estimatedTime
                                    )
                            );

                            confirm.showAndWait().ifPresent(response -> {
                                if (response == ButtonType.OK) {
                                    // Run benchmark
                                    runBenchmark(client, params);
                                } else {
                                    logger.info("Benchmark cancelled by user at confirmation");
                                    closeClient(client);
                                }
                            });
                        });
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in benchmark workflow", ex);
                        Platform.runLater(() -> {
                            Dialogs.showErrorMessage("Benchmark Error",
                                    "Error during benchmark: " + ex.getMessage());
                        });
                        closeClient(client);
                        return null;
                    });

        } catch (IOException e) {
            logger.error("Failed to connect to microscope server", e);
            closeClient(client);
            throw e;
        }
    }

    /**
     * Executes the benchmark on a background thread and shows progress dialog.
     *
     * @param client Connected microscope socket client
     * @param params User-configured benchmark parameters
     */
    private static void runBenchmark(MicroscopeSocketClient client, AutofocusBenchmarkDialog.BenchmarkParams params) {
        logger.info("Starting benchmark execution");

        // Show simple progress dialog (benchmark doesn't have granular progress updates)
        Platform.runLater(() -> {
            // Create simple progress window
            Stage progressStage = new Stage();
            progressStage.initModality(Modality.NONE);
            progressStage.setTitle("Autofocus Parameter Benchmark");
            progressStage.setAlwaysOnTop(true);
            progressStage.setResizable(false);

            ProgressIndicator progressIndicator = new ProgressIndicator();
            progressIndicator.setProgress(-1); // Indeterminate

            Label titleLabel = new Label("Running Benchmark...");
            titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label statusLabel = new Label("Testing autofocus parameters");
            statusLabel.setStyle("-fx-font-size: 12px;");

            Label timeLabel = new Label("This may take 10-60 minutes");
            timeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            VBox layout = new VBox(15);
            layout.setAlignment(Pos.CENTER);
            layout.setPadding(new Insets(20));
            layout.getChildren().addAll(titleLabel, progressIndicator, statusLabel, timeLabel);

            Scene scene = new Scene(layout, 300, 180);
            progressStage.setScene(scene);
            progressStage.show();

            // Run benchmark in background thread
            Thread benchmarkThread = new Thread(() -> {
                try {
                    logger.info("Sending benchmark command to server");

                    // Update status
                    Platform.runLater(() -> statusLabel.setText("Initializing benchmark..."));

                    // Execute benchmark
                    Map<String, Object> results = client.runAutofocusBenchmark(
                            params.referenceZ(),
                            params.outputPath(),
                            params.testDistances(),
                            params.quickMode(),
                            params.objective()
                    );

                    logger.info("Benchmark completed successfully");
                    logger.info("Results: {}", results);

                    // Close progress dialog
                    Platform.runLater(progressStage::close);

                    // Show results
                    Platform.runLater(() -> showResults(results, params));

                } catch (IOException e) {
                    logger.error("Benchmark execution failed", e);

                    Platform.runLater(() -> {
                        progressStage.close();

                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Benchmark Failed");
                        alert.setHeaderText("Autofocus benchmark encountered an error");

                        // Check for safety violation
                        String errorMsg = e.getMessage();
                        if (errorMsg != null && errorMsg.contains("SAFETY VIOLATION")) {
                            alert.setContentText(
                                    "SAFETY ERROR: The benchmark would exceed Z safety limits.\n\n" +
                                    errorMsg + "\n\n" +
                                    "Please reduce test distances or search range."
                            );
                        } else {
                            alert.setContentText(
                                    "Error during benchmark execution:\n\n" + errorMsg + "\n\n" +
                                    "Check server logs for details."
                            );
                        }

                        alert.showAndWait();
                    });

                } finally {
                    closeClient(client);
                }
            }, "BenchmarkExecutionThread");

            benchmarkThread.setDaemon(true);
            benchmarkThread.start();
        });
    }

    /**
     * Shows benchmark results in a user-friendly dialog.
     *
     * @param results Parsed benchmark results from server
     * @param params Original benchmark parameters
     */
    private static void showResults(Map<String, Object> results, AutofocusBenchmarkDialog.BenchmarkParams params) {
        Alert resultsDialog = new Alert(Alert.AlertType.INFORMATION);
        resultsDialog.setTitle("Benchmark Results");
        resultsDialog.setHeaderText("Autofocus Parameter Benchmark Complete");

        // Build results text
        StringBuilder resultsText = new StringBuilder();
        resultsText.append("Benchmark Configuration:\n");
        resultsText.append(String.format("  Reference Z: %.2f um\n", params.referenceZ()));
        resultsText.append(String.format("  Output: %s\n", params.outputPath()));
        resultsText.append(String.format("  Mode: %s\n\n", params.quickMode() ? "Quick" : "Full"));

        resultsText.append("Summary:\n");

        // Extract key statistics
        Object totalTrials = results.get("total_trials");
        Object successRate = results.get("success_rate");

        if (totalTrials != null) {
            resultsText.append(String.format("  Total trials: %s\n", totalTrials));
        }

        if (successRate != null) {
            double rate = (successRate instanceof Double) ? (Double) successRate : 0.0;
            resultsText.append(String.format("  Success rate: %.1f%%\n\n", rate * 100));
        }

        // Check for timing and accuracy stats
        resultsText.append("Performance:\n");
        Object meanDuration = results.get("mean_duration_ms");
        Object meanError = results.get("mean_z_error_um");

        if (meanDuration != null) {
            resultsText.append(String.format("  Mean time-to-focus: %.0f ms\n",
                    ((Number) meanDuration).doubleValue()));
        }

        if (meanError != null) {
            resultsText.append(String.format("  Mean focus error: %.2f um\n",
                    ((Number) meanError).doubleValue()));
        }

        resultsText.append("\nDetailed results saved to:\n");
        resultsText.append(String.format("%s/benchmark_results.csv\n", params.outputPath()));
        resultsText.append(String.format("%s/benchmark_summary.json\n", params.outputPath()));

        resultsText.append("\nReview the detailed CSV for recommended configurations.");

        // Display in text area for better readability
        TextArea textArea = new TextArea(resultsText.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(20);
        textArea.setPrefColumnCount(60);

        resultsDialog.getDialogPane().setContent(textArea);
        resultsDialog.setResizable(true);

        resultsDialog.showAndWait();

        logger.info("Results displayed to user");
    }

    /**
     * Safely closes the microscope client connection.
     *
     * @param client Socket client to close
     */
    private static void closeClient(MicroscopeSocketClient client) {
        if (client != null) {
            try {
                client.close();
                logger.debug("Microscope client closed");
            } catch (Exception e) {
                logger.warn("Error closing microscope client", e);
            }
        }
    }
}
