package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.dialogs.Dialogs;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for PPM Birefringence Optimization Test parameters.
 *
 * This dialog configures the birefringence maximization test which finds the optimal
 * polarizer angle for maximum birefringence signal contrast.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class BirefringenceOptimizationDialog {

    private static final Logger logger = LoggerFactory.getLogger(BirefringenceOptimizationDialog.class);

    /**
     * Parameters for birefringence optimization test
     */
    public record BirefringenceParams(
            String outputFolder,
            double minAngle,
            double maxAngle,
            double angleStep,
            String exposureMode,
            Double fixedExposureMs,
            int targetIntensity
    ) {}

    /**
     * Shows the birefringence optimization dialog.
     *
     * @return CompletableFuture with test parameters or null if cancelled
     */
    public static CompletableFuture<BirefringenceParams> showDialog() {
        CompletableFuture<BirefringenceParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<BirefringenceParams> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Birefringence Optimization");

            // Header with instructions
            VBox headerBox = new VBox(10);
            headerBox.setPadding(new Insets(10));
            Label headerLabel = new Label("Find Optimal Polarizer Angle for Maximum Birefringence");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label instructionLabel = new Label(
                "Position microscope on a tissue sample with visible birefringence.\n\n" +
                "This test systematically scans polarizer angles to find the optimal angle\n" +
                "that maximizes birefringence signal contrast (difference between +theta and -theta).\n\n" +
                "IMPORTANT: Review exposure mode options before starting.\n" +
                "Default 'interpolate' mode is fastest, using pre-calibrated exposures."
            );
            instructionLabel.setWrapText(true);
            instructionLabel.setStyle("-fx-font-size: 11px;");

            headerBox.getChildren().addAll(headerLabel, new Separator(), instructionLabel);
            dialog.getDialogPane().setHeader(headerBox);

            // Buttons
            ButtonType okType = new ButtonType("Start Test", ButtonBar.ButtonData.OK_DONE);
            ButtonType restoreDefaultsType = new ButtonType("Restore Defaults", ButtonBar.ButtonData.LEFT);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, restoreDefaultsType, cancelType);

            // Main content layout
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));

            int row = 0;

            // Output folder
            Label outputLabel = new Label("Output Folder:");
            outputLabel.setStyle("-fx-font-weight: bold;");
            TextField outputField = new TextField();
            outputField.setPrefColumnCount(30);

            // Get default output from config
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

            grid.add(outputLabel, 0, row);
            grid.add(outputField, 1, row);
            grid.add(browseBtn, 2, row);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Angle Range Section
            Label angleLabel = new Label("Angle Range:");
            angleLabel.setStyle("-fx-font-weight: bold;");
            grid.add(angleLabel, 0, row, 3, 1);
            row++;

            Spinner<Double> minAngleSpinner = new Spinner<>(-90.0, 0.0, -10.0, 1.0);
            minAngleSpinner.setEditable(true);
            minAngleSpinner.setPrefWidth(100);

            Spinner<Double> maxAngleSpinner = new Spinner<>(0.0, 90.0, 10.0, 1.0);
            maxAngleSpinner.setEditable(true);
            maxAngleSpinner.setPrefWidth(100);

            Spinner<Double> stepSpinner = new Spinner<>(0.01, 1.0, 0.1, 0.01);
            stepSpinner.setEditable(true);
            stepSpinner.setPrefWidth(100);

            grid.add(new Label("Min Angle (deg):"), 0, row);
            grid.add(minAngleSpinner, 1, row);
            grid.add(new Label("Typical: -10"), 2, row);
            row++;

            grid.add(new Label("Max Angle (deg):"), 0, row);
            grid.add(maxAngleSpinner, 1, row);
            grid.add(new Label("Typical: +10"), 2, row);
            row++;

            grid.add(new Label("Step Size (deg):"), 0, row);
            grid.add(stepSpinner, 1, row);
            grid.add(new Label("0.1 recommended"), 2, row);
            row++;

            // Angle count estimate
            Label angleCountLabel = new Label();
            angleCountLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            // High acquisition count warning
            Label highCountWarningLabel = new Label();
            highCountWarningLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
            highCountWarningLabel.setVisible(false);

            Runnable updateAngleCount = () -> {
                double min = minAngleSpinner.getValue();
                double max = maxAngleSpinner.getValue();
                double step = stepSpinner.getValue();
                if (max > min && step > 0) {
                    int numAngles = (int) Math.ceil((max - min) / step) + 1;
                    int totalImages = numAngles * 2;  // +/- pair for each angle
                    angleCountLabel.setText(String.format("%d angles tested (%d images total)",
                            numAngles, totalImages));

                    // Show warning if total images exceeds 400
                    if (totalImages > 400) {
                        int estimatedMinutes = (totalImages * 2) / 60;  // ~2 sec per image
                        highCountWarningLabel.setText(String.format(
                            "WARNING: This will acquire %d images and may take %d+ minutes. Consider increasing step size.",
                            totalImages, estimatedMinutes));
                        highCountWarningLabel.setVisible(true);
                    } else {
                        highCountWarningLabel.setVisible(false);
                    }
                } else {
                    angleCountLabel.setText("Invalid angle range");
                    highCountWarningLabel.setVisible(false);
                }
            };
            minAngleSpinner.valueProperty().addListener((obs, old, val) -> updateAngleCount.run());
            maxAngleSpinner.valueProperty().addListener((obs, old, val) -> updateAngleCount.run());
            stepSpinner.valueProperty().addListener((obs, old, val) -> updateAngleCount.run());
            updateAngleCount.run();

            grid.add(angleCountLabel, 0, row, 3, 1);
            row++;

            grid.add(highCountWarningLabel, 0, row, 3, 1);
            row++;

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Exposure Mode Section
            Label exposureLabel = new Label("Exposure Settings:");
            exposureLabel.setStyle("-fx-font-weight: bold;");
            grid.add(exposureLabel, 0, row, 3, 1);
            row++;

            ToggleGroup exposureModeGroup = new ToggleGroup();

            RadioButton interpolateMode = new RadioButton("Interpolate (default)");
            interpolateMode.setToggleGroup(exposureModeGroup);
            interpolateMode.setSelected(true);

            RadioButton calibrateMode = new RadioButton("Calibrate");
            calibrateMode.setToggleGroup(exposureModeGroup);

            RadioButton fixedMode = new RadioButton("Fixed");
            fixedMode.setToggleGroup(exposureModeGroup);

            Label interpolateDesc = new Label("Use calibration points from sensitivity test and interpolate between them (fastest)");
            interpolateDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            interpolateDesc.setWrapText(true);

            Label calibrateDesc = new Label("Measure optimal exposures on background first, then acquire (most accurate)");
            calibrateDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            calibrateDesc.setWrapText(true);

            Label fixedDesc = new Label("Use same exposure for all angles (fastest, may saturate at some angles)");
            fixedDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
            fixedDesc.setWrapText(true);

            grid.add(interpolateMode, 0, row, 3, 1);
            row++;
            grid.add(interpolateDesc, 0, row, 3, 1);
            row++;

            grid.add(calibrateMode, 0, row, 3, 1);
            row++;
            grid.add(calibrateDesc, 0, row, 3, 1);
            row++;

            grid.add(fixedMode, 0, row, 3, 1);
            row++;
            grid.add(fixedDesc, 0, row, 3, 1);
            row++;

            // Fixed exposure field (only enabled when fixed mode selected)
            Spinner<Double> fixedExposureSpinner = new Spinner<>(0.1, 1000.0, 25.0, 0.1);
            fixedExposureSpinner.setEditable(true);
            fixedExposureSpinner.setPrefWidth(100);
            fixedExposureSpinner.setDisable(true);

            grid.add(new Label("Fixed Exposure (ms):"), 0, row);
            grid.add(fixedExposureSpinner, 1, row);
            row++;

            // Target intensity (only for calibrate mode)
            Slider targetIntensitySlider = new Slider(0, 255, 128);
            targetIntensitySlider.setShowTickLabels(true);
            targetIntensitySlider.setShowTickMarks(true);
            targetIntensitySlider.setMajorTickUnit(64);
            targetIntensitySlider.setMinorTickCount(3);
            targetIntensitySlider.setPrefWidth(200);
            targetIntensitySlider.setDisable(true);

            Label targetIntensityValue = new Label("128");
            targetIntensitySlider.valueProperty().addListener((obs, old, val) -> {
                targetIntensityValue.setText(String.valueOf(val.intValue()));
            });

            Label targetIntensityLabel = new Label("Target Intensity (100-150 recommended for PPM):");
            targetIntensityLabel.setStyle("-fx-font-size: 10px;");

            Label targetIntensityHelper = new Label("Values 100-150 optimal. Above 200 risks saturation.");
            targetIntensityHelper.setStyle("-fx-font-size: 9px; -fx-text-fill: gray;");

            grid.add(targetIntensityLabel, 0, row);
            grid.add(targetIntensitySlider, 1, row);
            grid.add(targetIntensityValue, 2, row);
            row++;

            grid.add(targetIntensityHelper, 0, row, 3, 1);
            row++;

            // Enable/disable fields based on exposure mode
            exposureModeGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
                if (selected == fixedMode) {
                    fixedExposureSpinner.setDisable(false);
                    targetIntensitySlider.setDisable(true);
                } else if (selected == calibrateMode) {
                    fixedExposureSpinner.setDisable(true);
                    targetIntensitySlider.setDisable(false);
                } else {
                    fixedExposureSpinner.setDisable(true);
                    targetIntensitySlider.setDisable(true);
                }
            });

            grid.add(new Separator(), 0, row, 3, 1);
            row++;

            // Duration estimate
            Label durationLabel = new Label();
            durationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray; -fx-font-weight: bold;");
            Runnable updateDuration = () -> {
                double min = minAngleSpinner.getValue();
                double max = maxAngleSpinner.getValue();
                double step = stepSpinner.getValue();
                if (max > min && step > 0) {
                    int numAngles = (int) Math.ceil((max - min) / step) + 1;
                    int totalImages = numAngles * 2;
                    int estimatedSeconds = totalImages * 2;  // ~2 sec per image
                    int minutes = estimatedSeconds / 60;

                    // Mode-specific duration estimates
                    String modeDescription;
                    if (exposureModeGroup.getSelectedToggle() == interpolateMode) {
                        modeDescription = String.format("~%d minutes (%d images, pre-calibrated exposures)",
                                minutes, totalImages);
                    } else if (exposureModeGroup.getSelectedToggle() == calibrateMode) {
                        modeDescription = String.format("~%d minutes (background calibration + %d images)",
                                minutes * 2, totalImages);
                    } else {  // fixed mode
                        modeDescription = String.format("~%d minutes (%d images, fixed exposure)",
                                minutes, totalImages);
                    }
                    durationLabel.setText("Estimated duration: " + modeDescription);
                }
            };
            updateDuration.run();
            minAngleSpinner.valueProperty().addListener((obs, old, val) -> updateDuration.run());
            maxAngleSpinner.valueProperty().addListener((obs, old, val) -> updateDuration.run());
            stepSpinner.valueProperty().addListener((obs, old, val) -> updateDuration.run());
            exposureModeGroup.selectedToggleProperty().addListener((obs, old, val) -> updateDuration.run());

            grid.add(durationLabel, 0, row, 3, 1);
            row++;

            // Warning about small steps
            Label warningLabel = new Label();
            warningLabel.setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
            warningLabel.setVisible(false);
            stepSpinner.valueProperty().addListener((obs, old, val) -> {
                if (val < 0.05) {
                    warningLabel.setText("WARNING: Very small step size will result in many acquisitions!");
                    warningLabel.setVisible(true);
                } else {
                    warningLabel.setVisible(false);
                }
            });
            grid.add(warningLabel, 0, row, 3, 1);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().setPrefWidth(700);

            // Handle restore defaults button
            Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restoreDefaultsType);
            restoreButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                minAngleSpinner.getValueFactory().setValue(-10.0);
                maxAngleSpinner.getValueFactory().setValue(10.0);
                stepSpinner.getValueFactory().setValue(0.1);
                interpolateMode.setSelected(true);
                fixedExposureSpinner.getValueFactory().setValue(25.0);
                targetIntensitySlider.setValue(128);
                event.consume();  // Prevent dialog from closing
            });

            // Validation
            Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String output = outputField.getText().trim();
                File outputDir = new File(output);

                if (output.isEmpty() || !outputDir.exists() || !outputDir.isDirectory()) {
                    Dialogs.showErrorMessage("Invalid Output Folder",
                            "Please select a valid output folder for test results.");
                    event.consume();
                    return;
                }

                double min = minAngleSpinner.getValue();
                double max = maxAngleSpinner.getValue();
                double step = stepSpinner.getValue();

                if (min >= max) {
                    Dialogs.showErrorMessage("Invalid Angle Range",
                            "Maximum angle must be greater than minimum angle.");
                    event.consume();
                    return;
                }

                if (step <= 0 || step > 1.0) {
                    Dialogs.showErrorMessage("Invalid Step Size",
                            "Step size must be between 0.01 and 1.0 degrees.");
                    event.consume();
                    return;
                }

                if (exposureModeGroup.getSelectedToggle() == fixedMode) {
                    if (fixedExposureSpinner.getValue() <= 0) {
                        Dialogs.showErrorMessage("Invalid Exposure",
                                "Fixed exposure must be greater than 0 ms.");
                        event.consume();
                        return;
                    }
                }
            });

            // Result converter
            dialog.setResultConverter(button -> {
                if (button == okType) {
                    String exposureMode;
                    Double fixedExposure = null;

                    if (exposureModeGroup.getSelectedToggle() == interpolateMode) {
                        exposureMode = "interpolate";
                    } else if (exposureModeGroup.getSelectedToggle() == calibrateMode) {
                        exposureMode = "calibrate";
                    } else {
                        exposureMode = "fixed";
                        fixedExposure = fixedExposureSpinner.getValue();
                    }

                    return new BirefringenceParams(
                            outputField.getText().trim(),
                            minAngleSpinner.getValue(),
                            maxAngleSpinner.getValue(),
                            stepSpinner.getValue(),
                            exposureMode,
                            fixedExposure,
                            (int) targetIntensitySlider.getValue()
                    );
                }
                return null;
            });

            Optional<BirefringenceParams> result = dialog.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.complete(null);
            }
        });

        return future;
    }
}
