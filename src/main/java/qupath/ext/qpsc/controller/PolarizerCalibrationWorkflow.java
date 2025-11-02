package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PolarizerCalibrationWorkflow - Calibrate PPM rotation stage to find crossed polarizer positions
 *
 * <p>This workflow provides a way to calibrate the polarized light microscopy (PPM) rotation stage:
 * <ol>
 *   <li>User positions microscope at uniform, bright background</li>
 *   <li>User sets calibration parameters (angle range, step size, exposure)</li>
 *   <li>System sweeps rotation angles and measures intensity</li>
 *   <li>Sine curve fitting identifies crossed polarizer positions</li>
 *   <li>Text report generated with config_PPM.yml update suggestions</li>
 * </ol>
 *
 * <p>Key features:
 * <ul>
 *   <li>Infrequent calibration - only needed when optics are repositioned</li>
 *   <li>Automatic sine fitting and minima detection</li>
 *   <li>Comprehensive report with all data and suggestions</li>
 *   <li>Report saved in background folder (camera/objective independent)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class PolarizerCalibrationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PolarizerCalibrationWorkflow.class);

    /**
     * Main entry point for polarizer calibration workflow.
     * Shows UI for parameter input, then executes calibration.
     */
    public static void run() {
        logger.info("Starting polarizer calibration workflow");

        Platform.runLater(() -> {
            try {
                // Show calibration parameter dialog
                showCalibrationDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Polarizer calibration parameters received");

                            // Execute calibration
                            CompletableFuture.runAsync(() -> {
                                executeCalibration(params);
                            }).exceptionally(ex -> {
                                logger.error("Polarizer calibration failed", ex);
                                Platform.runLater(() -> {
                                    Dialogs.showErrorMessage("Polarizer Calibration Error",
                                            "Failed to execute calibration: " + ex.getMessage());
                                });
                                return null;
                            });
                        } else {
                            logger.info("Polarizer calibration cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in polarizer calibration dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Calibration Error",
                                "Failed to show calibration dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start polarizer calibration workflow", e);
                Dialogs.showErrorMessage("Calibration Error",
                        "Failed to start polarizer calibration: " + e.getMessage());
            }
        });
    }

    /**
     * Shows dialog to collect calibration parameters.
     *
     * @return CompletableFuture with calibration parameters or null if cancelled
     */
    private static CompletableFuture<CalibrationParams> showCalibrationDialog() {
        CompletableFuture<CalibrationParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<CalibrationParams> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Polarizer Calibration (PPM)");

            // Header with instructions
            VBox headerBox = new VBox(10);
            headerBox.setPadding(new Insets(10));
            Label headerLabel = new Label("Calibrate PPM Rotation Stage");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "IMPORTANT: Position microscope at a uniform, bright background area.\n" +
                "This calibration finds crossed polarizer positions by sweeping rotation angles.\n\n" +
                "When to use:\n" +
                "  • After installing or repositioning polarization optics\n" +
                "  • After reseating or replacing rotation stage\n" +
                "  • To validate/update rotation_angles in config_PPM.yml"
            );
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-font-size: 11px;");

            headerBox.getChildren().addAll(headerLabel, new Separator(), instructionLabel);
            dialog.getDialogPane().setHeader(headerBox);

            // Buttons
            ButtonType okType = new ButtonType("Start Calibration", ButtonBar.ButtonData.OK_DONE);
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

            // Get background folder from config
            String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFile);
            String defaultOutput = configManager.getBackgroundCorrectionBaseFolder();

            if (defaultOutput == null || defaultOutput.isEmpty()) {
                defaultOutput = System.getProperty("user.home");
            }
            outputField.setText(defaultOutput);

            Button browseBtn = new Button("Browse...");
            browseBtn.setOnAction(e -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Output Folder for Calibration Report");
                File current = new File(outputField.getText());
                if (current.exists() && current.isDirectory()) {
                    chooser.setInitialDirectory(current);
                }
                File chosen = chooser.showDialog(dialog.getOwner());
                if (chosen != null) {
                    outputField.setText(chosen.getAbsolutePath());
                }
            });

            // Calibration parameters
            // Two-stage calibration: always uses full 360 deg range
            // Stage 1: Coarse sweep (user-defined step size)
            // Stage 2: Fine sweep (0.1 deg steps around each minimum)

            Spinner<Double> stepSizeSpinner = new Spinner<>(0.5, 10.0, 5.0, 0.5);
            stepSizeSpinner.setEditable(true);
            stepSizeSpinner.setPrefWidth(100);

            Spinner<Double> exposureSpinner = new Spinner<>(0.1, 1000.0, 10.0, 1.0);
            exposureSpinner.setEditable(true);
            exposureSpinner.setPrefWidth(100);

            // Info label for calibration description
            Label descriptionLabel = new Label(
                "Two-stage calibration:\n" +
                "1. Coarse sweep: 0-360 deg to locate minima\n" +
                "2. Fine sweep: 0.1 deg steps for exact positions"
            );
            descriptionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            descriptionLabel.setWrapText(true);

            // Info label for duration estimate
            Label durationLabel = new Label();
            durationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            // Update duration estimate when parameters change
            Runnable updateDuration = () -> {
                double step = stepSizeSpinner.getValue();
                // Coarse sweep: 360 deg / step size
                int coarseSteps = (int) Math.ceil(360.0 / step) + 1;
                // Fine sweep: Assume 2 minima, each with (step*2) deg range at 0.1 deg steps
                int fineStepsPerMinimum = (int) Math.ceil((step * 2) / 0.1);
                int totalFineSteps = fineStepsPerMinimum * 2;  // 2 minima expected
                int totalSteps = coarseSteps + totalFineSteps;
                int durationSec = (int) (totalSteps * 0.5);
                durationLabel.setText(String.format("Estimated: Coarse %d steps + Fine %d steps = ~%d seconds total",
                        coarseSteps, totalFineSteps, durationSec));
            };

            stepSizeSpinner.valueProperty().addListener((obs, old, val) -> updateDuration.run());

            // Initial duration estimate
            updateDuration.run();

            // Layout
            int row = 0;
            grid.add(new Label("Output Folder:"), 0, row);
            grid.add(outputField, 1, row);
            grid.add(browseBtn, 2, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            Label paramsLabel = new Label("Hardware Offset Calibration:");
            paramsLabel.setStyle("-fx-font-weight: bold;");
            grid.add(paramsLabel, 0, row, 3, 1);
            row++;

            grid.add(descriptionLabel, 0, row, 3, 1);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            grid.add(new Label("Coarse Step Size (deg):"), 0, row);
            grid.add(stepSizeSpinner, 1, row);
            grid.add(new Label("5 deg recommended"), 2, row);
            row++;

            grid.add(new Label("Exposure (ms):"), 0, row);
            grid.add(exposureSpinner, 1, row);
            grid.add(new Label("Keep short to avoid saturation"), 2, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            grid.add(durationLabel, 0, row, 3, 1);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(650);

            // Validation
            Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String output = outputField.getText().trim();
                File outputDir = new File(output);

                if (output.isEmpty() || !outputDir.exists() || !outputDir.isDirectory()) {
                    Dialogs.showErrorMessage("Invalid Output Folder",
                            "Please select a valid output folder for the calibration report.");
                    event.consume();
                    return;
                }

                double step = stepSizeSpinner.getValue();

                if (step <= 0 || step > 10.0) {
                    Dialogs.showErrorMessage("Invalid Step Size",
                            "Step size must be between 0.5 and 10.0 degrees.");
                    event.consume();
                    return;
                }
            });

            // Result converter
            // Note: Two-stage calibration always uses 0-360 deg range
            dialog.setResultConverter(button -> {
                if (button == okType) {
                    return new CalibrationParams(
                            outputField.getText().trim(),
                            0.0,  // start_angle (not used, always 0)
                            360.0,  // end_angle (not used, always 360)
                            stepSizeSpinner.getValue(),
                            exposureSpinner.getValue()
                    );
                }
                return null;
            });

            Optional<CalibrationParams> result = dialog.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Executes the polarizer calibration via socket communication.
     *
     * @param params Calibration parameters from dialog
     */
    private static void executeCalibration(CalibrationParams params) {
        logger.info("Executing polarizer calibration: {} to {} deg, step {}",
                params.startAngle(), params.endAngle(), params.stepSize());

        try {
            // Get socket client
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            // Ensure connection
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for calibration");
                MicroscopeController.getInstance().connect();
            }

            // Get config file path
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();

            logger.info("Starting calibration with parameters:");
            logger.info("  Config: {}", configFileLocation);
            logger.info("  Output: {}", params.outputFolder());
            logger.info("  Angles: {} to {} deg, step {}",
                    params.startAngle(), params.endAngle(), params.stepSize());
            logger.info("  Exposure: {} ms", params.exposure());

            // Call the socket client method
            String reportPath = socketClient.startPolarizerCalibration(
                    configFileLocation,
                    params.outputFolder(),
                    params.startAngle(),
                    params.endAngle(),
                    params.stepSize(),
                    params.exposure()
            );

            logger.info("Polarizer calibration completed successfully");
            logger.info("Report saved to: {}", reportPath);

            // Show success and offer to open report
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Calibration Complete");
                alert.setHeaderText("Polarizer calibration completed successfully!");
                alert.setContentText(
                        "Calibration report saved to:\n" + reportPath + "\n\n" +
                        "The report contains:\n" +
                        "  • Crossed polarizer positions (angles)\n" +
                        "  • Intensity statistics and sine fit parameters\n" +
                        "  • Suggestions for updating config_PPM.yml\n" +
                        "  • Raw data for all measurements\n\n" +
                        "Would you like to open the report folder?"
                );

                ButtonType openFolderBtn = new ButtonType("Open Folder");
                ButtonType closeBtn = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(openFolderBtn, closeBtn);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == openFolderBtn) {
                    try {
                        Path reportFilePath = Paths.get(reportPath);
                        Path parentDir = reportFilePath.getParent();
                        if (parentDir != null && Files.exists(parentDir)) {
                            java.awt.Desktop.getDesktop().open(parentDir.toFile());
                        }
                    } catch (IOException e) {
                        logger.error("Failed to open report folder", e);
                        Dialogs.showErrorMessage("Error",
                                "Could not open folder: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Polarizer calibration failed", e);
            Platform.runLater(() -> {
                Dialogs.showErrorMessage("Calibration Failed",
                        "Failed to complete polarizer calibration:\n" + e.getMessage());
            });
        }
    }

    /**
     * Record for calibration parameters.
     */
    private record CalibrationParams(
            String outputFolder,
            double startAngle,
            double endAngle,
            double stepSize,
            double exposure
    ) {}
}
