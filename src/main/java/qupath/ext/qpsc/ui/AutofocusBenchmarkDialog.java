package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for configuring autofocus parameter benchmark testing.
 *
 * <p>This dialog allows users to configure systematic testing of autofocus parameters
 * to find optimal settings for their microscope and samples. The benchmark tests
 * various combinations of parameters and measures both speed and accuracy.
 *
 * <p>Key features:
 * <ul>
 *   <li>Reference Z position selection (manual or current stage position)</li>
 *   <li>Output directory selection for results</li>
 *   <li>Configurable test distances from focus</li>
 *   <li>Standard autofocus parameter selection (n_steps, search_range, etc.)</li>
 *   <li>Score metric selection (laplacian, sobel, brenner)</li>
 *   <li>Quick mode for faster testing with reduced parameter space</li>
 *   <li>Persistent preferences for user convenience</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AutofocusBenchmarkDialog {
    private static final Logger logger = LoggerFactory.getLogger(AutofocusBenchmarkDialog.class);

    // Persistent preferences for benchmark settings
    private static final DoubleProperty referenceZProperty =
            PathPrefs.createPersistentPreference("afbench.referenceZ", 0.0);
    private static final StringProperty outputPathProperty =
            PathPrefs.createPersistentPreference("afbench.outputPath", "");
    private static final StringProperty testDistancesProperty =
            PathPrefs.createPersistentPreference("afbench.testDistances", "5,10,20,30,50");
    private static final BooleanProperty quickModeProperty =
            PathPrefs.createPersistentPreference("afbench.quickMode", false);
    private static final StringProperty objectiveProperty =
            PathPrefs.createPersistentPreference("afbench.objective", "");

    /**
     * Result record containing user-configured benchmark parameters.
     */
    public record BenchmarkParams(
            double referenceZ,
            String outputPath,
            List<Double> testDistances,
            boolean quickMode,
            String objective
    ) {}

    /**
     * Shows the autofocus benchmark configuration dialog.
     *
     * @param currentZ Current Z stage position, used as default for reference_z
     * @param defaultObjective Default objective identifier (e.g., "20X")
     * @return CompletableFuture with configured parameters, or null if cancelled
     */
    public static CompletableFuture<BenchmarkParams> showDialog(
            Double currentZ,
            String defaultObjective) {

        CompletableFuture<BenchmarkParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Create dialog
                Dialog<BenchmarkParams> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Autofocus Parameter Benchmark");
                dialog.setHeaderText("Configure autofocus parameter testing");
                dialog.setResizable(true);

                // Main content container
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(600);

                // ========== REQUIRED PARAMETERS SECTION ==========
                Label requiredLabel = new Label("Required Parameters");
                requiredLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

                // Reference Z position
                HBox referenceZBox = new HBox(10);
                referenceZBox.setAlignment(Pos.CENTER_LEFT);
                Label referenceZLabel = new Label("Reference Z (um):");
                referenceZLabel.setPrefWidth(150);

                TextField referenceZField = new TextField();
                referenceZField.setPrefWidth(150);
                referenceZField.setPromptText("Enter in-focus Z position");

                // Initialize with current Z or last used value
                if (currentZ != null && currentZ != 0.0) {
                    referenceZField.setText(String.format("%.2f", currentZ));
                } else if (referenceZProperty.get() != 0.0) {
                    referenceZField.setText(String.format("%.2f", referenceZProperty.get()));
                }

                Button useCurrentZButton = new Button("Use Current Z");
                useCurrentZButton.setOnAction(e -> {
                    if (currentZ != null) {
                        referenceZField.setText(String.format("%.2f", currentZ));
                    }
                });

                Label referenceZHelp = new Label("(Must be manually verified in focus before starting)");
                referenceZHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

                referenceZBox.getChildren().addAll(referenceZLabel, referenceZField, useCurrentZButton);

                // Output directory
                HBox outputBox = new HBox(10);
                outputBox.setAlignment(Pos.CENTER_LEFT);
                Label outputLabel = new Label("Output Directory:");
                outputLabel.setPrefWidth(150);

                TextField outputField = new TextField();
                outputField.setPrefWidth(350);
                outputField.setText(outputPathProperty.get());

                Button browseButton = new Button("Browse...");
                browseButton.setOnAction(e -> {
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle("Select Output Directory for Benchmark Results");

                    String currentPath = outputField.getText();
                    if (!currentPath.isEmpty()) {
                        File currentDir = new File(currentPath);
                        if (currentDir.exists()) {
                            chooser.setInitialDirectory(currentDir);
                        }
                    }

                    File selectedDir = chooser.showDialog(dialog.getOwner());
                    if (selectedDir != null) {
                        outputField.setText(selectedDir.getAbsolutePath());
                    }
                });

                outputBox.getChildren().addAll(outputLabel, outputField, browseButton);

                // ========== TEST CONFIGURATION SECTION ==========
                Label testConfigLabel = new Label("Test Configuration");
                testConfigLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

                // Test distances
                HBox distancesBox = new HBox(10);
                distancesBox.setAlignment(Pos.CENTER_LEFT);
                Label distancesLabel = new Label("Test Distances (um):");
                distancesLabel.setPrefWidth(150);

                TextField distancesField = new TextField();
                distancesField.setPrefWidth(350);
                distancesField.setText(testDistancesProperty.get());
                distancesField.setPromptText("Comma-separated values (e.g., 5,10,20,30,50)");

                Label distancesHelp = new Label("Distances from focus to test (above and below)");
                distancesHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

                distancesBox.getChildren().addAll(distancesLabel, distancesField);

                // Distance bounds validation label (declare before using in container)
                Label distanceWarningLabel = new Label();
                distanceWarningLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #cc6600; -fx-padding: 2 0 0 0;");
                distanceWarningLabel.setManaged(false); // Hide by default
                distanceWarningLabel.setVisible(false);

                // VBox to hold distances field and warning
                VBox distancesContainer = new VBox(2);
                distancesContainer.getChildren().addAll(distancesBox, distancesHelp, distanceWarningLabel);

                // Quick mode checkbox
                CheckBox quickModeCheck = new CheckBox("Quick Mode (reduced parameter space for faster testing)");
                quickModeCheck.setSelected(quickModeProperty.get());

                // Estimated test count and duration label
                Label estimateLabel = new Label();
                estimateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #0066cc; -fx-padding: 5 0 0 25;");

                // Objective identifier
                HBox objectiveBox = new HBox(10);
                objectiveBox.setAlignment(Pos.CENTER_LEFT);
                Label objectiveLabel = new Label("Objective:");
                objectiveLabel.setPrefWidth(150);

                ComboBox<String> objectiveComboBox = new ComboBox<>();
                objectiveComboBox.setPrefWidth(350);
                objectiveComboBox.setPromptText("Select objective...");

                // Load available objectives from microscope configuration
                try {
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                    List<Map<String, Object>> hardwareObjectives = configManager.getHardwareObjectives();

                    List<String> objectiveIds = new ArrayList<>();
                    for (Map<String, Object> objective : hardwareObjectives) {
                        String id = (String) objective.get("id");
                        if (id != null) {
                            objectiveIds.add(id);
                        }
                    }

                    if (!objectiveIds.isEmpty()) {
                        objectiveComboBox.getItems().addAll(objectiveIds);
                        logger.debug("Loaded {} objectives for benchmark dialog: {}", objectiveIds.size(), objectiveIds);

                        // Initialize with default or last used value
                        String lastUsed = objectiveProperty.get();
                        if (defaultObjective != null && !defaultObjective.isEmpty() &&
                                objectiveIds.contains(defaultObjective)) {
                            objectiveComboBox.setValue(defaultObjective);
                        } else if (lastUsed != null && !lastUsed.isEmpty() &&
                                objectiveIds.contains(lastUsed)) {
                            objectiveComboBox.setValue(lastUsed);
                        } else if (!objectiveIds.isEmpty()) {
                            // Select first objective by default
                            objectiveComboBox.setValue(objectiveIds.get(0));
                        }
                    } else {
                        logger.warn("No objectives found in microscope configuration");
                    }
                } catch (Exception e) {
                    logger.error("Failed to load objectives from configuration", e);
                }

                Label objectiveHelp = new Label("Used for Z safety limits during benchmark testing");
                objectiveHelp.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

                objectiveBox.getChildren().addAll(objectiveLabel, objectiveComboBox);

                // ========== INFORMATION SECTION ==========
                Label infoLabel = new Label("Benchmark Information");
                infoLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

                TextArea infoArea = new TextArea();
                infoArea.setEditable(false);
                infoArea.setWrapText(true);
                infoArea.setPrefRowCount(6);
                infoArea.setText(
                        "The autofocus parameter benchmark systematically tests different autofocus " +
                        "configurations to find optimal settings for your microscope and samples.\n\n" +

                        "What is tested:\n" +
                        "- Standard autofocus: n_steps (5 values), search_range (4 values), " +
                        "interpolation (3 types), score metrics (3 types)\n" +
                        "- Adaptive autofocus: initial_step (3 values), min_step (2 values)\n" +
                        "- Each distance tested both above and below focus\n\n" +

                        "TIMING:\n" +
                        "- Quick mode: ~100 trials, 5-15 minutes\n" +
                        "- Full mode with 5 distances: ~1,980 trials, 30+ HOURS\n" +
                        "- Reduce distances to 2-3 for faster results (e.g., \"10,30\")\n\n" +

                        "Results are saved as CSV and JSON files. Progress updates are shown " +
                        "during execution. You can cancel long-running benchmarks from the progress dialog."
                );

                // Validation feedback label (shown at bottom when Run button is disabled)
                Label validationLabel = new Label();
                validationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc0000; -fx-padding: 10 0 0 0;");
                validationLabel.setWrapText(true);

                // ========== ASSEMBLE CONTENT ==========
                content.getChildren().addAll(
                        requiredLabel,
                        referenceZBox,
                        referenceZHelp,
                        outputBox,
                        new Separator(),
                        testConfigLabel,
                        distancesContainer,
                        quickModeCheck,
                        estimateLabel,
                        objectiveBox,
                        objectiveHelp,
                        new Separator(),
                        infoLabel,
                        infoArea,
                        validationLabel
                );

                // ========== DIALOG BUTTONS ==========
                ButtonType runButton = new ButtonType("Run Benchmark", ButtonBar.ButtonData.OK_DONE);
                ButtonType restoreDefaultsButton = new ButtonType("Restore Defaults", ButtonBar.ButtonData.LEFT);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

                dialog.getDialogPane().getButtonTypes().addAll(runButton, restoreDefaultsButton, cancelButton);
                dialog.getDialogPane().setContent(content);

                // Handle restore defaults button
                Button restoreBtn = (Button) dialog.getDialogPane().lookupButton(restoreDefaultsButton);
                restoreBtn.setOnAction(e -> {
                    distancesField.setText("5,10,20,30,50");
                    quickModeCheck.setSelected(false);
                    e.consume(); // Prevent dialog from closing
                });

                // Validate inputs
                Button runBtn = (Button) dialog.getDialogPane().lookupButton(runButton);
                runBtn.setDisable(true);

                // Enable run button only when required fields are filled
                Runnable validateInputs = () -> {
                    boolean valid = true;
                    List<String> missingFields = new ArrayList<>();

                    // Check reference Z
                    boolean hasValidZ = false;
                    try {
                        double z = Double.parseDouble(referenceZField.getText().trim());
                        if (z != 0.0) {
                            hasValidZ = true;
                        } else {
                            missingFields.add("reference Z position (cannot be 0)");
                        }
                    } catch (NumberFormatException ex) {
                        missingFields.add("valid reference Z position");
                    }
                    if (!hasValidZ) valid = false;

                    // Check output path
                    if (outputField.getText().trim().isEmpty()) {
                        valid = false;
                        missingFields.add("output directory");
                    }

                    runBtn.setDisable(!valid);

                    // Update validation feedback label
                    if (!valid) {
                        validationLabel.setText("Missing required fields: " + String.join(", ", missingFields));
                        validationLabel.setManaged(true);
                        validationLabel.setVisible(true);
                    } else {
                        validationLabel.setText("");
                        validationLabel.setManaged(false);
                        validationLabel.setVisible(false);
                    }
                };

                // Update estimate label based on test distances and quick mode
                // Uses ACTUAL parameter grid from BenchmarkConfig defaults
                // Note: Skips impossible combinations where search_range < distance
                Runnable updateEstimate = () -> {
                    try {
                        // Parse test distances
                        List<Double> distances = new ArrayList<>();
                        String distancesText = distancesField.getText().trim();
                        if (!distancesText.isEmpty()) {
                            String[] parts = distancesText.split(",");
                            for (String part : parts) {
                                try {
                                    distances.add(Double.parseDouble(part.trim()));
                                } catch (NumberFormatException e) {
                                    // Ignore invalid values for estimate
                                }
                            }
                        }

                        // Default distances if none parsed
                        if (distances.isEmpty()) {
                            distances = List.of(5.0, 10.0, 20.0, 30.0, 50.0);
                        }

                        boolean quickMode = quickModeCheck.isSelected();
                        int directions = 2; // above and below focus

                        int standardTrials, adaptiveTrials;

                        if (quickMode) {
                            // Quick mode: reduced parameter space
                            // search_range values: [25.0, 50.0], n_steps: 2, interp: 1, metrics: 2
                            // adaptive: initial: 1, min: 1, metrics: 2
                            double[] quickRanges = {25.0, 50.0};
                            int nSteps = 2, nInterp = 1, nMetrics = 2;

                            standardTrials = 0;
                            for (double distance : distances) {
                                // Count valid ranges (range >= distance)
                                int validRanges = 0;
                                for (double range : quickRanges) {
                                    if (range >= distance) validRanges++;
                                }
                                standardTrials += validRanges * nSteps * nInterp * nMetrics * directions;
                            }

                            // Adaptive doesn't have range/distance constraint
                            adaptiveTrials = 1 * 1 * 2 * distances.size() * directions;
                        } else {
                            // Full mode: complete parameter grid (from BenchmarkConfig defaults)
                            // search_range values: [15.0, 25.0, 35.0, 50.0]
                            // n_steps: 5, interp: 3, metrics: 3
                            double[] fullRanges = {15.0, 25.0, 35.0, 50.0};
                            int nSteps = 5, nInterp = 3, nMetrics = 3;

                            standardTrials = 0;
                            for (double distance : distances) {
                                // Count valid ranges (range >= distance)
                                int validRanges = 0;
                                for (double range : fullRanges) {
                                    if (range >= distance) validRanges++;
                                }
                                standardTrials += validRanges * nSteps * nInterp * nMetrics * directions;
                            }

                            // Adaptive: initial(3) * min(2) * metrics(3) - no range/distance constraint
                            adaptiveTrials = 3 * 2 * 3 * distances.size() * directions;
                        }

                        int totalTrials = standardTrials + adaptiveTrials;

                        // Estimate duration: ~30-90 seconds per trial on average
                        // Quick trials are faster (~20-40s), full grid has mix of fast and slow
                        double avgSecondsPerTrial = quickMode ? 30.0 : 70.0;
                        double totalMinutes = (totalTrials * avgSecondsPerTrial) / 60.0;
                        double totalHours = totalMinutes / 60.0;

                        // Format the estimate string
                        String durationStr;
                        String warningStyle = "-fx-font-size: 11px; -fx-padding: 5 0 0 25; ";

                        if (totalHours >= 1.0) {
                            durationStr = String.format("%.1f hours", totalHours);
                            // Red warning for very long benchmarks
                            warningStyle += "-fx-text-fill: #cc0000; -fx-font-weight: bold;";
                            estimateLabel.setText(String.format(
                                    "WARNING: %,d trials estimated, ~%s! Consider using Quick Mode.",
                                    totalTrials, durationStr));
                        } else if (totalMinutes >= 30) {
                            durationStr = String.format("%.0f minutes", totalMinutes);
                            // Orange warning for long benchmarks
                            warningStyle += "-fx-text-fill: #cc6600;";
                            estimateLabel.setText(String.format(
                                    "Estimated: %,d trials, ~%s",
                                    totalTrials, durationStr));
                        } else {
                            durationStr = String.format("%.0f minutes", Math.max(5, totalMinutes));
                            // Blue info for reasonable benchmarks
                            warningStyle += "-fx-text-fill: #0066cc;";
                            estimateLabel.setText(String.format(
                                    "Estimated: %,d trials, ~%s",
                                    totalTrials, durationStr));
                        }

                        estimateLabel.setStyle(warningStyle);
                        estimateLabel.setManaged(true);
                        estimateLabel.setVisible(true);

                    } catch (Exception e) {
                        estimateLabel.setText("");
                        estimateLabel.setManaged(false);
                        estimateLabel.setVisible(false);
                    }
                };

                // Validate distance bounds (1-200 um recommended range)
                Runnable validateDistanceBounds = () -> {
                    try {
                        List<Double> outOfBounds = new ArrayList<>();
                        String distancesText = distancesField.getText().trim();
                        if (!distancesText.isEmpty()) {
                            String[] parts = distancesText.split(",");
                            for (String part : parts) {
                                try {
                                    double dist = Double.parseDouble(part.trim());
                                    if (dist < 1.0 || dist > 200.0) {
                                        outOfBounds.add(dist);
                                    }
                                } catch (NumberFormatException e) {
                                    // Ignore for bounds validation
                                }
                            }
                        }

                        if (!outOfBounds.isEmpty()) {
                            distanceWarningLabel.setText(String.format(
                                    "Warning: Some distances outside recommended range (1-200 um): %s",
                                    outOfBounds));
                            distanceWarningLabel.setManaged(true);
                            distanceWarningLabel.setVisible(true);
                        } else {
                            distanceWarningLabel.setText("");
                            distanceWarningLabel.setManaged(false);
                            distanceWarningLabel.setVisible(false);
                        }
                    } catch (Exception e) {
                        distanceWarningLabel.setText("");
                        distanceWarningLabel.setManaged(false);
                        distanceWarningLabel.setVisible(false);
                    }
                };

                // Combined update function
                Runnable updateAll = () -> {
                    validateInputs.run();
                    updateEstimate.run();
                    validateDistanceBounds.run();
                };

                referenceZField.textProperty().addListener((obs, old, newVal) -> updateAll.run());
                outputField.textProperty().addListener((obs, old, newVal) -> updateAll.run());
                distancesField.textProperty().addListener((obs, old, newVal) -> updateAll.run());
                quickModeCheck.selectedProperty().addListener((obs, old, newVal) -> updateAll.run());

                // Initial validation and updates
                updateAll.run();

                // Convert result
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == runButton) {
                        try {
                            // Parse reference Z
                            double refZ = Double.parseDouble(referenceZField.getText().trim());

                            // Get output path
                            String outPath = outputField.getText().trim();

                            // Parse test distances
                            List<Double> distances = new ArrayList<>();
                            String distancesText = distancesField.getText().trim();
                            if (!distancesText.isEmpty()) {
                                String[] parts = distancesText.split(",");
                                for (String part : parts) {
                                    try {
                                        distances.add(Double.parseDouble(part.trim()));
                                    } catch (NumberFormatException e) {
                                        logger.warn("Invalid distance value: {}", part);
                                    }
                                }
                            }

                            // If no valid distances parsed, use defaults
                            if (distances.isEmpty()) {
                                distances.add(5.0);
                                distances.add(10.0);
                                distances.add(20.0);
                                distances.add(30.0);
                                distances.add(50.0);
                            }

                            // Get quick mode
                            boolean quick = quickModeCheck.isSelected();

                            // Get objective
                            String obj = objectiveComboBox.getValue();
                            if (obj == null) {
                                obj = "";
                            }

                            // Save preferences for next time
                            referenceZProperty.set(refZ);
                            outputPathProperty.set(outPath);
                            testDistancesProperty.set(distancesField.getText().trim());
                            quickModeProperty.set(quick);
                            objectiveProperty.set(obj);

                            logger.info("User configured autofocus benchmark:");
                            logger.info("  Reference Z: {} um", refZ);
                            logger.info("  Output: {}", outPath);
                            logger.info("  Test distances: {}", distances);
                            logger.info("  Quick mode: {}", quick);
                            logger.info("  Objective: {}", obj.isEmpty() ? "(not specified)" : obj);

                            return new BenchmarkParams(refZ, outPath, distances, quick, obj);

                        } catch (Exception e) {
                            logger.error("Error parsing benchmark parameters", e);
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Invalid Parameters");
                            alert.setHeaderText("Could not parse benchmark parameters");
                            alert.setContentText("Please check your input values and try again.");
                            alert.showAndWait();
                            return null;
                        }
                    }
                    return null;
                });

                // Show dialog and complete future
                dialog.showAndWait().ifPresentOrElse(
                        future::complete,
                        () -> {
                            logger.info("Autofocus benchmark dialog cancelled");
                            future.complete(null);
                        }
                );

            } catch (Exception e) {
                logger.error("Error showing autofocus benchmark dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Shows a simplified version of the dialog with minimal options (quick mode only).
     *
     * @param currentZ Current Z stage position
     * @param defaultObjective Default objective identifier
     * @return CompletableFuture with configured parameters, or null if cancelled
     */
    public static CompletableFuture<BenchmarkParams> showQuickDialog(
            Double currentZ,
            String defaultObjective) {

        // Pre-configure for quick mode and use showDialog
        quickModeProperty.set(true);
        return showDialog(currentZ, defaultObjective);
    }
}
