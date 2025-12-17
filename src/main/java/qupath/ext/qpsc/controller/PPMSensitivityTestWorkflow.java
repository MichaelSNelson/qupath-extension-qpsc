package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PPMSensitivityTestWorkflow - Systematic testing of PPM rotation sensitivity
 *
 * <p>This workflow provides comprehensive testing of PPM (Polarized light Microscopy) rotation
 * stage sensitivity to analyze the impact of angular deviations on image quality and birefringence
 * calculations.
 *
 * <p>Test types:
 * <ul>
 *   <li><strong>Comprehensive</strong> - Runs all test types in sequence for complete analysis</li>
 *   <li><strong>Standard</strong> - Tests at all standard PPM angles (0, 7, 14, ..., 91 degrees)</li>
 *   <li><strong>Deviation</strong> - Fine angular deviations around a base angle (0.05 to 1.0 deg steps)</li>
 *   <li><strong>Repeatability</strong> - Measures mechanical repeatability by returning to same angle N times</li>
 *   <li><strong>Calibration</strong> - Compares current angles vs optimal from polarizer calibration</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Uses fixed exposures from background calibration for fair comparison</li>
 *   <li>Generates comprehensive analysis reports with statistics</li>
 *   <li>Optional image retention for manual inspection</li>
 *   <li>Configurable angle overrides for custom testing</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class PPMSensitivityTestWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMSensitivityTestWorkflow.class);

    /**
     * Main entry point for PPM sensitivity test workflow.
     * Shows UI for parameter input, then executes test.
     */
    public static void run() {
        logger.info("Starting PPM sensitivity test workflow");

        Platform.runLater(() -> {
            try {
                // Show test parameter dialog
                showTestDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("PPM sensitivity test parameters received");

                            // Execute test
                            CompletableFuture.runAsync(() -> {
                                executeTest(params);
                            }).exceptionally(ex -> {
                                logger.error("PPM sensitivity test failed", ex);
                                Platform.runLater(() -> {
                                    Dialogs.showErrorMessage("PPM Sensitivity Test Error",
                                            "Failed to execute test: " + ex.getMessage());
                                });
                                return null;
                            });
                        } else {
                            logger.info("PPM sensitivity test cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in PPM sensitivity test dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Test Error",
                                "Failed to show test dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start PPM sensitivity test workflow", e);
                Dialogs.showErrorMessage("Test Error",
                        "Failed to start PPM sensitivity test: " + e.getMessage());
            }
        });
    }

    /**
     * Shows dialog to collect test parameters.
     *
     * @return CompletableFuture with test parameters or null if cancelled
     */
    private static CompletableFuture<TestParams> showTestDialog() {
        CompletableFuture<TestParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<TestParams> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Rotation Sensitivity Test");

            // Header with instructions
            VBox headerBox = new VBox(10);
            headerBox.setPadding(new Insets(10));
            Label headerLabel = new Label("Test PPM Rotation Stage Sensitivity");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "Systematically test PPM rotation sensitivity by acquiring images at precise angles.\n" +
                "This test analyzes the impact of angular deviations on image quality and birefringence.\n\n" +
                "IMPORTANT: Position microscope at a representative tissue area before starting.\n\n" +
                "Test types:\n" +
                "  • Comprehensive - Runs all tests (recommended for thorough analysis)\n" +
                "  • Standard - Tests all standard angles (0, 7, 14, ..., 91 deg)\n" +
                "  • Deviation - Fine angular deviations around base angle\n" +
                "  • Repeatability - Mechanical repeatability test (N repetitions)\n" +
                "  • Calibration - Compare current vs optimal calibration angles"
            );
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-font-size: 11px;");

            headerBox.getChildren().addAll(headerLabel, new Separator(), instructionLabel);
            dialog.getDialogPane().setHeader(headerBox);

            // Buttons
            ButtonType okType = new ButtonType("Start Test", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // Fields
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));

            // Output folder
            TextField outputField = new TextField();
            outputField.setPrefColumnCount(30);

            // Get default output from config or user home
            String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFile);
            String defaultOutput = configManager.getBackgroundCorrectionFolder("ppm");

            if (defaultOutput == null || defaultOutput.isEmpty()) {
                defaultOutput = System.getProperty("user.home");
            }
            outputField.setText(defaultOutput);

            Button browseBtn = new Button("Browse...");
            browseBtn.setOnAction(e -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Output Folder for Test Results");
                File current = new File(outputField.getText());
                if (current.exists() && current.isDirectory()) {
                    chooser.setInitialDirectory(current);
                }
                File chosen = chooser.showDialog(dialog.getOwner());
                if (chosen != null) {
                    outputField.setText(chosen.getAbsolutePath());
                }
            });

            // Test type
            ComboBox<String> testTypeCombo = new ComboBox<>();
            testTypeCombo.getItems().addAll(
                "comprehensive",
                "standard",
                "deviation",
                "repeatability",
                "calibration"
            );
            testTypeCombo.setValue("comprehensive");
            testTypeCombo.setPrefWidth(200);

            // Test type description label
            Label testDescLabel = new Label();
            testDescLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            testDescLabel.setWrapText(true);
            testDescLabel.setPrefWidth(400);

            // Base angle (for deviation/repeatability tests)
            Spinner<Double> baseAngleSpinner = new Spinner<>(-90.0, 90.0, 7.0, 0.1);
            baseAngleSpinner.setEditable(true);
            baseAngleSpinner.setPrefWidth(100);

            // Number of repeats (for repeatability test)
            Spinner<Integer> repeatsSpinner = new Spinner<>(1, 50, 10, 1);
            repeatsSpinner.setEditable(true);
            repeatsSpinner.setPrefWidth(100);

            // Update description and field enablement when test type changes
            testTypeCombo.valueProperty().addListener((obs, old, newValue) -> {
                // Update description
                switch (newValue) {
                    case "comprehensive" ->
                        testDescLabel.setText("Runs all test types in sequence for complete analysis (20-30 min)");
                    case "standard" ->
                        testDescLabel.setText("Tests at all standard PPM angles: 0, 7, 14, ..., 91 degrees (5-8 min)");
                    case "deviation" ->
                        testDescLabel.setText("Fine angular deviations (0.05-1.0 deg) around base angle (3-5 min)");
                    case "repeatability" ->
                        testDescLabel.setText("Returns to same angle N times to measure mechanical precision (2-4 min)");
                    case "calibration" ->
                        testDescLabel.setText("Compares current angles vs optimal from polarizer calibration (3-5 min)");
                }

                // Enable/disable fields based on test type
                boolean needsBaseAngle = "deviation".equals(newValue) || "repeatability".equals(newValue);
                boolean needsRepeats = "repeatability".equals(newValue);

                baseAngleSpinner.setDisable(!needsBaseAngle);
                repeatsSpinner.setDisable(!needsRepeats);
            });
            testDescLabel.setText("Runs all test types in sequence for complete analysis (20-30 min)");

            // Initial state: disable base angle and repeats for comprehensive test
            baseAngleSpinner.setDisable(true);
            repeatsSpinner.setDisable(true);

            // Keep images checkbox
            CheckBox keepImagesCheckbox = new CheckBox("Retain acquired images after analysis");
            keepImagesCheckbox.setSelected(true);
            keepImagesCheckbox.setTooltip(new Tooltip(
                "If checked, all acquired .tif images are kept for manual inspection.\n" +
                "If unchecked, images are deleted after analysis to conserve disk space."
            ));

            // Info label for duration estimate
            Label durationLabel = new Label();
            durationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            // Update duration estimate when test type changes
            Runnable updateDuration = () -> {
                String testType = testTypeCombo.getValue();
                int estimatedMinutes = switch (testType) {
                    case "comprehensive" -> 25;
                    case "standard" -> 6;
                    case "deviation" -> 4;
                    case "repeatability" -> {
                        int repeats = repeatsSpinner.getValue();
                        yield (repeats * 15) / 60; // ~15 sec per repeat
                    }
                    case "calibration" -> 4;
                    default -> 5;
                };
                durationLabel.setText(String.format("Estimated duration: ~%d minutes", estimatedMinutes));
            };

            testTypeCombo.valueProperty().addListener((obs, old, val) -> updateDuration.run());
            repeatsSpinner.valueProperty().addListener((obs, old, val) -> updateDuration.run());

            // Initial duration estimate
            updateDuration.run();

            // Layout
            int row = 0;
            grid.add(new Label("Output Folder:"), 0, row);
            grid.add(outputField, 1, row, 2, 1);
            grid.add(browseBtn, 3, row);
            row++;

            grid.add(new Separator(), 0, row, 4, 1);
            row++;

            Label configLabel = new Label("Test Configuration:");
            configLabel.setStyle("-fx-font-weight: bold;");
            grid.add(configLabel, 0, row, 4, 1);
            row++;

            grid.add(new Label("Test Type:"), 0, row);
            grid.add(testTypeCombo, 1, row);
            grid.add(testDescLabel, 2, row, 2, 1);
            row++;

            grid.add(new Label("Base Angle (deg):"), 0, row);
            grid.add(baseAngleSpinner, 1, row);
            Label baseAngleNote = new Label("For deviation/repeatability tests");
            baseAngleNote.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            grid.add(baseAngleNote, 2, row, 2, 1);
            row++;

            grid.add(new Label("Repeats:"), 0, row);
            grid.add(repeatsSpinner, 1, row);
            Label repeatsNote = new Label("For repeatability test only");
            repeatsNote.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            grid.add(repeatsNote, 2, row, 2, 1);
            row++;

            grid.add(new Separator(), 0, row, 4, 1);
            row++;

            grid.add(keepImagesCheckbox, 0, row, 4, 1);
            row++;

            grid.add(new Separator(), 0, row, 4, 1);
            row++;

            // Help/Information section
            Label infoHeaderLabel = new Label("About This Test:");
            infoHeaderLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            grid.add(infoHeaderLabel, 0, row, 4, 1);
            row++;

            TextArea infoArea = new TextArea();
            infoArea.setEditable(false);
            infoArea.setWrapText(true);
            infoArea.setPrefRowCount(5);
            infoArea.setStyle("-fx-font-size: 10px;");
            infoArea.setText(
                "What this test measures:\n" +
                "The PPM sensitivity test systematically evaluates how rotation stage angular precision affects " +
                "image quality and birefringence calculations. This helps determine optimal angular tolerances " +
                "and validate rotation stage performance.\n\n" +
                "When to use each test type:\n" +
                "  - Comprehensive: First-time setup or complete system validation (runs all tests)\n" +
                "  - Standard: Verify angles used in normal PPM acquisitions\n" +
                "  - Deviation: Analyze impact of small angular errors around critical angles\n" +
                "  - Repeatability: Measure mechanical precision of rotation stage\n" +
                "  - Calibration: Compare current system to optimal polarizer calibration\n\n" +
                "Output files generated:\n" +
                "  - Test summary report (JSON format with statistics)\n" +
                "  - Analysis plots showing angular sensitivity curves\n" +
                "  - Acquired test images (if retention enabled)\n" +
                "  - Detailed execution logs for debugging"
            );
            grid.add(infoArea, 0, row, 4, 1);
            row++;

            grid.add(new Separator(), 0, row, 4, 1);
            row++;

            grid.add(durationLabel, 0, row, 4, 1);
            row++;

            // Validation feedback indicator
            Label validationLabel = new Label();
            validationLabel.setStyle("-fx-font-size: 11px;");
            grid.add(validationLabel, 0, row, 4, 1);

            // Validation update function
            Runnable updateValidation = () -> {
                String output = outputField.getText().trim();
                File outputDir = new File(output);

                if (output.isEmpty() || !outputDir.exists() || !outputDir.isDirectory()) {
                    validationLabel.setText("Warning: Please select a valid output folder");
                    validationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #D97706;"); // Orange warning
                } else {
                    validationLabel.setText("Ready to start");
                    validationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #059669;"); // Green ready
                }
            };

            // Update validation when output folder changes
            outputField.textProperty().addListener((obs, old, newValue) -> updateValidation.run());

            // Initial validation
            updateValidation.run();

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(700);

            // Validation
            Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String output = outputField.getText().trim();
                File outputDir = new File(output);

                if (output.isEmpty() || !outputDir.exists() || !outputDir.isDirectory()) {
                    Dialogs.showErrorMessage("Invalid Output Folder",
                            "Please select a valid output folder for the test results.");
                    event.consume();
                    return;
                }
            });

            // Result converter
            dialog.setResultConverter(button -> {
                if (button == okType) {
                    return new TestParams(
                            outputField.getText().trim(),
                            testTypeCombo.getValue(),
                            baseAngleSpinner.getValue(),
                            repeatsSpinner.getValue(),
                            keepImagesCheckbox.isSelected()
                    );
                }
                return null;
            });

            Optional<TestParams> result = dialog.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Executes the PPM sensitivity test via socket communication.
     *
     * @param params Test parameters from dialog
     */
    private static void executeTest(TestParams params) {
        logger.info("Executing PPM sensitivity test: type={}, base_angle={}, repeats={}",
                params.testType(), params.baseAngle(), params.nRepeats());

        // Create progress dialog
        Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
        progressDialog.setTitle("Test In Progress");
        progressDialog.setHeaderText("PPM Rotation Sensitivity Test Running");
        progressDialog.setContentText(
                String.format("Test type: %s\n", params.testType()) +
                "This may take several minutes (typically 5-30 minutes depending on test type).\n\n" +
                "IMPORTANT: Check the Python server logs for detailed progress information.\n" +
                "The logs will show:\n" +
                "  - Angle acquisition progress\n" +
                "  - Image capture status\n" +
                "  - Analysis results\n\n" +
                "Please wait for the test to complete.\n" +
                "This dialog will close automatically when finished."
        );
        progressDialog.getDialogPane().setMinWidth(500);
        progressDialog.getButtonTypes().clear(); // Remove buttons - dialog stays open until test completes

        // Show progress dialog on JavaFX thread
        Platform.runLater(() -> progressDialog.show());

        try {
            // Get socket client
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            // Ensure connection
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for sensitivity test");
                MicroscopeController.getInstance().connect();
            }

            // Get config file path
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();

            logger.info("Starting PPM sensitivity test with parameters:");
            logger.info("  Config: {}", configFileLocation);
            logger.info("  Output: {}", params.outputFolder());
            logger.info("  Test type: {}", params.testType());
            logger.info("  Base angle: {} deg", params.baseAngle());
            logger.info("  Repeats: {}", params.nRepeats());
            logger.info("  Keep images: {}", params.keepImages());

            // Create params object for socket client
            MicroscopeSocketClient.PPMSensitivityParams socketParams =
                new MicroscopeSocketClient.PPMSensitivityParams(
                    configFileLocation,
                    params.outputFolder(),
                    params.testType(),
                    params.baseAngle(),
                    params.nRepeats()
                );

            // Call the socket client method
            String resultDir = socketClient.runPPMSensitivityTest(socketParams);

            logger.info("PPM sensitivity test completed successfully");
            logger.info("Results saved to: {}", resultDir);

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show success and offer to open results
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Test Complete");
                alert.setHeaderText("PPM sensitivity test completed successfully!");
                alert.setContentText(
                        "Test results saved to:\n" + resultDir + "\n\n" +
                        "The output directory contains:\n" +
                        "  • Test summary report (JSON format)\n" +
                        "  • Analysis plots and statistics\n" +
                        "  • Acquired images (if retention enabled)\n" +
                        "  • Detailed logs\n\n" +
                        "Would you like to open the results folder?"
                );

                // Make dialog resizable and larger
                alert.setResizable(true);
                alert.getDialogPane().setPrefWidth(650);
                alert.getDialogPane().setPrefHeight(300);

                ButtonType openFolderBtn = new ButtonType("Open Folder");
                ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(openFolderBtn, closeBtn);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == openFolderBtn) {
                    try {
                        Path resultPath = Paths.get(resultDir);
                        if (Files.exists(resultPath)) {
                            java.awt.Desktop.getDesktop().open(resultPath.toFile());
                        }
                    } catch (IOException e) {
                        logger.error("Failed to open results folder", e);
                        Dialogs.showErrorMessage("Error",
                                "Could not open folder: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            logger.error("PPM sensitivity test failed", e);

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show error
            Platform.runLater(() -> {
                Dialogs.showErrorMessage("Test Failed",
                        "Failed to complete PPM sensitivity test:\n" + e.getMessage());
            });
        }
    }

    /**
     * Record for test parameters.
     */
    private record TestParams(
            String outputFolder,
            String testType,
            double baseAngle,
            int nRepeats,
            boolean keepImages
    ) {}
}
