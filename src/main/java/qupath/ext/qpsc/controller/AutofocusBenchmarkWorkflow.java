package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;
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

                            // Calculate accurate trial count and time estimate
                            int numDistances = params.testDistances().size();
                            int directions = 2; // above and below focus
                            int standardTrials, adaptiveTrials;

                            if (params.quickMode()) {
                                standardTrials = 2 * 2 * 1 * 2 * numDistances * directions;
                                adaptiveTrials = 1 * 1 * 2 * numDistances * directions;
                            } else {
                                standardTrials = 5 * 4 * 3 * 3 * numDistances * directions;
                                adaptiveTrials = 3 * 2 * 3 * numDistances * directions;
                            }
                            int totalTrials = standardTrials + adaptiveTrials;

                            double avgSeconds = params.quickMode() ? 30.0 : 70.0;
                            double totalMinutes = (totalTrials * avgSeconds) / 60.0;
                            double totalHours = totalMinutes / 60.0;

                            String estimatedTime;
                            if (totalHours >= 1.0) {
                                estimatedTime = String.format("%.1f hours (%,d trials)", totalHours, totalTrials);
                            } else {
                                estimatedTime = String.format("%.0f minutes (%,d trials)", totalMinutes, totalTrials);
                            }

                            String warningText = "";
                            if (totalHours >= 1.0) {
                                warningText = "\n\nWARNING: This is a very long benchmark! " +
                                        "Consider using Quick Mode or reducing test distances.";
                            }

                            confirm.setContentText(
                                    String.format(
                                            "Reference Z: %.2f um\n" +
                                            "Test distances: %s\n" +
                                            "Mode: %s\n" +
                                            "Estimated time: %s\n\n" +
                                            "The benchmark will systematically test autofocus parameters. " +
                                            "Do not disturb the microscope during this time.%s\n\n" +
                                            "Continue?",
                                            params.referenceZ(),
                                            params.testDistances(),
                                            params.quickMode() ? "Quick" : "Full",
                                            estimatedTime,
                                            warningText
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

        // Cancellation flag shared between UI thread and background thread
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // Show progress dialog with real-time updates
        Platform.runLater(() -> {
            // Create progress window
            Stage progressStage = new Stage();
            progressStage.initModality(Modality.NONE);
            progressStage.setTitle("Autofocus Parameter Benchmark");
            progressStage.setAlwaysOnTop(true);
            progressStage.setResizable(false);

            // Progress bar for determinate progress once trials start
            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(0);
            progressBar.setPrefWidth(280);

            // Also keep spinner for initial phase
            ProgressIndicator progressIndicator = new ProgressIndicator();
            progressIndicator.setProgress(-1); // Indeterminate
            progressIndicator.setPrefSize(40, 40);

            Label titleLabel = new Label("Running Benchmark...");
            titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label progressLabel = new Label("Trial 0/0");
            progressLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

            Label statusLabel = new Label("Initializing...");
            statusLabel.setStyle("-fx-font-size: 11px;");
            statusLabel.setWrapText(true);
            statusLabel.setMaxWidth(280);

            Label timeLabel = new Label("Progress updates received after each trial");
            timeLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

            // Cancel button
            Button cancelButton = new Button("Cancel Benchmark");
            cancelButton.setStyle("-fx-background-color: #cc6600; -fx-text-fill: white;");
            cancelButton.setOnAction(e -> {
                // Confirm cancellation
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Cancel Benchmark");
                confirm.setHeaderText("Cancel the running benchmark?");
                confirm.setContentText(
                        "The benchmark will be stopped. Partial results may have been saved " +
                        "to the output directory.\n\nCancel the benchmark?");

                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.OK) {
                        logger.info("User requested benchmark cancellation");
                        cancelled.set(true);
                        cancelButton.setDisable(true);
                        cancelButton.setText("Cancelling...");
                        statusLabel.setText("Cancellation requested - closing connection...");

                        // Close the client connection to abort the benchmark
                        // This will cause an IOException in the background thread
                        try {
                            client.disconnect();
                        } catch (Exception ex) {
                            logger.debug("Error during cancellation disconnect: {}", ex.getMessage());
                        }
                    }
                });
            });

            HBox buttonBox = new HBox(cancelButton);
            buttonBox.setAlignment(Pos.CENTER);
            buttonBox.setPadding(new Insets(10, 0, 0, 0));

            VBox layout = new VBox(12);
            layout.setAlignment(Pos.CENTER);
            layout.setPadding(new Insets(20));
            layout.getChildren().addAll(titleLabel, progressIndicator, progressBar,
                    progressLabel, statusLabel, timeLabel, buttonBox);

            Scene scene = new Scene(layout, 340, 280);
            progressStage.setScene(scene);
            progressStage.show();

            // Run benchmark in background thread
            Thread benchmarkThread = new Thread(() -> {
                try {
                    logger.info("Sending benchmark command to server");

                    // Update status
                    Platform.runLater(() -> statusLabel.setText("Sending command to server..."));

                    // Create progress listener that updates the UI
                    MicroscopeSocketClient.BenchmarkProgressListener progressListener =
                            (current, total, statusMsg) -> {
                                Platform.runLater(() -> {
                                    // Update progress bar
                                    double progress = (double) current / total;
                                    progressBar.setProgress(progress);

                                    // Update progress label
                                    progressLabel.setText(String.format("Trial %d/%d (%.1f%%)",
                                            current, total, progress * 100));

                                    // Update status with latest trial result
                                    statusLabel.setText(statusMsg);
                                });
                            };

                    // Execute benchmark with progress listener
                    Map<String, Object> results = client.runAutofocusBenchmark(
                            params.referenceZ(),
                            params.outputPath(),
                            params.testDistances(),
                            params.quickMode(),
                            params.objective(),
                            progressListener
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

                        // Check if this was a user cancellation
                        if (cancelled.get()) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("Benchmark Cancelled");
                            alert.setHeaderText("Benchmark was cancelled by user");
                            alert.setContentText(
                                    "The benchmark has been stopped.\n\n" +
                                    "Partial results may have been saved to:\n" +
                                    params.outputPath());
                            alert.showAndWait();
                            return;
                        }

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
    @SuppressWarnings("unchecked")
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
        Object successfulTrials = results.get("successful_trials");
        Object failedTrials = results.get("failed_trials");
        Object successRate = results.get("success_rate");

        if (totalTrials != null) {
            resultsText.append(String.format("  Total trials: %s\n", totalTrials));
        }
        if (successfulTrials != null && failedTrials != null) {
            resultsText.append(String.format("  Successful: %s, Failed: %s\n", successfulTrials, failedTrials));
        }
        if (successRate != null) {
            double rate = (successRate instanceof Number) ? ((Number) successRate).doubleValue() : 0.0;
            resultsText.append(String.format("  Success rate: %.1f%%\n", rate * 100));
        }

        resultsText.append("\nPerformance:\n");

        // Extract timing_stats (nested structure from Python)
        Object timingStats = results.get("timing_stats");
        if (timingStats instanceof Map) {
            Map<String, Object> timing = (Map<String, Object>) timingStats;
            Object meanDuration = timing.get("mean_duration_ms");
            Object minDuration = timing.get("min_duration_ms");
            Object maxDuration = timing.get("max_duration_ms");

            if (meanDuration != null) {
                resultsText.append(String.format("  Mean time-to-focus: %.0f ms\n",
                        ((Number) meanDuration).doubleValue()));
            }
            if (minDuration != null && maxDuration != null) {
                resultsText.append(String.format("  Range: %.0f - %.0f ms\n",
                        ((Number) minDuration).doubleValue(),
                        ((Number) maxDuration).doubleValue()));
            }
        }

        // Extract accuracy_stats (nested structure from Python)
        Object accuracyStats = results.get("accuracy_stats");
        if (accuracyStats instanceof Map) {
            Map<String, Object> accuracy = (Map<String, Object>) accuracyStats;
            Object meanError = accuracy.get("mean_z_error_um");
            Object minError = accuracy.get("min_z_error_um");
            Object maxError = accuracy.get("max_z_error_um");

            if (meanError != null) {
                resultsText.append(String.format("  Mean focus error: %.2f um\n",
                        ((Number) meanError).doubleValue()));
            }
            if (minError != null && maxError != null) {
                resultsText.append(String.format("  Error range: %.2f - %.2f um\n",
                        ((Number) minError).doubleValue(),
                        ((Number) maxError).doubleValue()));
            }
        }

        // Show fastest configurations if available
        Object fastestStandard = results.get("fastest_standard");
        if (fastestStandard instanceof Map) {
            Map<String, Object> fs = (Map<String, Object>) fastestStandard;
            resultsText.append("\nFastest Standard Config:\n");
            resultsText.append(String.format("  n_steps=%s, range=%s um, metric=%s\n",
                    fs.get("n_steps"), fs.get("search_range_um"), fs.get("score_metric")));
            Object fsDuration = fs.get("duration_ms");
            Object fsError = fs.get("z_error_um");
            if (fsDuration != null && fsError != null) {
                resultsText.append(String.format("  Duration: %.0f ms, Error: %.2f um\n",
                        ((Number) fsDuration).doubleValue(),
                        ((Number) fsError).doubleValue()));
            }
        }

        Object fastestAdaptive = results.get("fastest_adaptive");
        if (fastestAdaptive instanceof Map) {
            Map<String, Object> fa = (Map<String, Object>) fastestAdaptive;
            resultsText.append("\nFastest Adaptive Config:\n");
            resultsText.append(String.format("  initial_step=%s um, metric=%s\n",
                    fa.get("initial_step_um"), fa.get("score_metric")));
            Object faDuration = fa.get("duration_ms");
            Object faError = fa.get("z_error_um");
            if (faDuration != null && faError != null) {
                resultsText.append(String.format("  Duration: %.0f ms, Error: %.2f um\n",
                        ((Number) faDuration).doubleValue(),
                        ((Number) faError).doubleValue()));
            }
        }

        resultsText.append("\nDetailed results saved to:\n");
        resultsText.append(String.format("%s/autofocus_benchmark_*/\n", params.outputPath()));

        resultsText.append("\nReview benchmark_results.csv for all trial data.");

        // Display in text area for better readability
        TextArea textArea = new TextArea(resultsText.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(25);
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
