package qupath.ext.qpsc.modality.ppm.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.scripting.QP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controller for selecting PPM (Polarized Light Microscopy) angles and decimal exposure times.
 * 
 * <p>This controller provides a JavaFX dialog interface for users to select which polarization 
 * angles to acquire and specify precise exposure times for each angle. The controller supports 
 * decimal exposure values for fine-grained exposure control (e.g., 1.2ms, 0.5ms).</p>
 * 
 * <p>The dialog presents checkboxes for four standard PPM angles:</p>
 * <ul>
 *   <li><strong>Minus angle:</strong> Negative polarizer rotation (e.g., -7.0 degrees)</li>
 *   <li><strong>Zero angle:</strong> Crossed polarizers at 0.0 degrees</li>
 *   <li><strong>Plus angle:</strong> Positive polarizer rotation (e.g., +7.0 degrees)</li>
 *   <li><strong>Uncrossed angle:</strong> Parallel polarizers (e.g., 45.0 degrees)</li>
 * </ul>
 * 
 * <p>Each angle has an associated exposure time input field that accepts decimal values.
 * The exposure values are validated to ensure they are positive numbers and are stored 
 * in user preferences via {@link PPMPreferences}.</p>
 * 
 * @author Mike Nelson
 * @see PPMPreferences
 * @since 1.0
 */
public class PPMAngleSelectionController {

    private static final Logger logger = LoggerFactory.getLogger(PPMAngleSelectionController.class);

    /**
     * Immutable result container holding the user's angle and exposure time selections.
     * 
     * <p>This class encapsulates the output from the angle selection dialog, containing
     * the list of selected {@link AngleExposure} pairs that the user chose for acquisition.
     * Only angles that were checked in the dialog are included in the result.</p>
     * 
     * @since 1.0
     */
    public static class AngleExposureResult {
        /** The list of selected angle-exposure pairs for acquisition */
        public final List<AngleExposure> angleExposures;
        
        /**
         * Creates a new result with the specified angle-exposure pairs.
         * @param angleExposures the selected angle-exposure pairs, must not be null
         */
        public AngleExposureResult(List<AngleExposure> angleExposures) {
            this.angleExposures = angleExposures;
        }
        
        /**
         * Extracts just the angle values from the angle-exposure pairs.
         * @return a list containing the angle values in degrees
         */
        public List<Double> getAngles() {
            return angleExposures.stream().map(ae -> ae.angle).collect(Collectors.toList());
        }
    }

    /**
     * Simple data container pairing a rotation angle with its exposure time.
     * 
     * <p>This is a UI-specific version of the main {@link qupath.ext.qpsc.modality.AngleExposure} 
     * class, used within the angle selection dialog. It represents the user's selections
     * before they are converted to the modality system's format.</p>
     * 
     * @since 1.0
     */
    public static class AngleExposure {
        /** The rotation angle in degrees */
        public final double angle;
        /** The exposure time in milliseconds (supports decimal values) */
        public final double exposureMs;
        
        /**
         * Creates a new angle-exposure pair.
         * @param angle the rotation angle in degrees
         * @param exposureMs the exposure time in milliseconds (decimal values supported)
         */
        public AngleExposure(double angle, double exposureMs) {
            this.angle = angle;
            this.exposureMs = exposureMs;
        }
        
        /**
         * Returns a formatted string showing both angle and exposure time.
         * @return formatted string in the format "angle° @ exposureMs" (e.g., "7.0° @ 1.2ms")
         */
        @Override
        public String toString() {
            return String.format("%.1f° @ %.1fms", angle, exposureMs);
        }
    }

    /**
     * Shows the PPM angle selection dialog and returns the user's selections asynchronously.
     * 
     * <p>This method creates and displays a modal dialog where users can select which PPM
     * angles to acquire and specify decimal exposure times for each angle. The dialog 
     * presents four angle options with checkboxes and exposure time input fields.</p>
     * 
     * <p>The exposure time input fields support decimal values (e.g., 1.2, 0.5, 15.8) and
     * are validated to ensure positive values. Default exposure times are determined using
     * priority order: background image settings → config file → persistent preferences.</p>
     * 
     * <p>The dialog includes convenience buttons for \"Select All\" and \"Select None\" to
     * quickly configure common acquisition patterns.</p>
     * 
     * @param plusAngle the positive polarizer angle value in degrees (typically +7.0)
     * @param minusAngle the negative polarizer angle value in degrees (typically -7.0)
     * @param uncrossedAngle the uncrossed polarizer angle in degrees (typically 45.0)
     * @param modality the modality name (e.g., "ppm") for config lookup
     * @param objective the objective ID for config lookup
     * @param detector the detector ID for config lookup
     * @return a {@code CompletableFuture} that completes with the user's selections, or
     *         is cancelled if the user cancels the dialog
     * @since 1.0
     */
    public static CompletableFuture<AngleExposureResult> showDialog(double plusAngle, double minusAngle, double uncrossedAngle,
                                                                   String modality, String objective, String detector) {
        CompletableFuture<AngleExposureResult> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Dialog<AngleExposureResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles (in tick marks) and exposure times for acquisition:");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(10));

            UnaryOperator<TextFormatter.Change> doubleFilter = change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty()) return change;
                try {
                    double value = Double.parseDouble(newText);
                    if (value > 0) return change;
                } catch (NumberFormatException ignored) {}
                return null;
            };

            grid.add(new Label("Angle"),0,0);
            grid.add(new Label("Exposure (ms)"),2,0);

            CheckBox minusCheck = new CheckBox(String.format("%.1f 'degrees'", minusAngle));
            minusCheck.setSelected(PPMPreferences.getMinusSelected());
            TextField minusExposure = new TextField(String.valueOf(getDefaultExposureTime(minusAngle, modality, objective, detector)));
            minusExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            minusExposure.setPrefWidth(80);
            minusExposure.setDisable(!minusCheck.isSelected());
            grid.add(minusCheck,0,1);
            grid.add(minusExposure,2,1);

            CheckBox zeroCheck = new CheckBox("0 'degrees'");
            zeroCheck.setSelected(PPMPreferences.getZeroSelected());
            TextField zeroExposure = new TextField(String.valueOf(getDefaultExposureTime(0.0, modality, objective, detector)));
            zeroExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            zeroExposure.setPrefWidth(80);
            zeroExposure.setDisable(!zeroCheck.isSelected());
            grid.add(zeroCheck,0,2);
            grid.add(zeroExposure,2,2);

            CheckBox plusCheck = new CheckBox(String.format("%.1f 'degrees'", plusAngle));
            plusCheck.setSelected(PPMPreferences.getPlusSelected());
            TextField plusExposure = new TextField(String.valueOf(getDefaultExposureTime(plusAngle, modality, objective, detector)));
            plusExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            plusExposure.setPrefWidth(80);
            plusExposure.setDisable(!plusCheck.isSelected());
            grid.add(plusCheck,0,3);
            grid.add(plusExposure,2,3);

            CheckBox uncrossedCheck = new CheckBox(String.format("%.1f 'degrees' (uncrossed)", uncrossedAngle));
            uncrossedCheck.setSelected(PPMPreferences.getUncrossedSelected());
            TextField uncrossedExposure = new TextField(String.valueOf(getDefaultExposureTime(uncrossedAngle, modality, objective, detector)));
            uncrossedExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            uncrossedExposure.setPrefWidth(80);
            uncrossedExposure.setDisable(!uncrossedCheck.isSelected());
            grid.add(uncrossedCheck,0,4);
            grid.add(uncrossedExposure,2,4);

            minusCheck.selectedProperty().addListener((obs,o,s)->{
                minusExposure.setDisable(!s);
                if(!s) minusExposure.clear();
                PPMPreferences.setMinusSelected(s);
                logger.debug("PPM minus tick selection updated to: {}", s);
            });
            zeroCheck.selectedProperty().addListener((obs,o,s)->{
                zeroExposure.setDisable(!s);
                if(!s) zeroExposure.clear();
                PPMPreferences.setZeroSelected(s);
                logger.debug("PPM zero tick selection updated to: {}", s);
            });
            plusCheck.selectedProperty().addListener((obs,o,s)->{
                plusExposure.setDisable(!s);
                if(!s) plusExposure.clear();
                PPMPreferences.setPlusSelected(s);
                logger.debug("PPM plus tick selection updated to: {}", s);
            });
            uncrossedCheck.selectedProperty().addListener((obs,o,s)->{
                uncrossedExposure.setDisable(!s);
                if(!s) uncrossedExposure.clear();
                PPMPreferences.setUncrossedSelected(s);
                logger.debug("PPM uncrossed tick selection updated to: {}", s);
            });

            minusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setMinusExposureMs(Double.parseDouble(n)); });
            zeroExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setZeroExposureMs(Double.parseDouble(n)); });
            plusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setPlusExposureMs(Double.parseDouble(n)); });
            uncrossedExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setUncrossedExposureMs(Double.parseDouble(n)); });

            Label info = new Label("Each selected angle will be acquired with the specified exposure time.");
            Button selectAll = new Button("Select All");
            Button selectNone = new Button("Select None");
            selectAll.setOnAction(e->{minusCheck.setSelected(true);zeroCheck.setSelected(true);plusCheck.setSelected(true);uncrossedCheck.setSelected(true);});
            selectNone.setOnAction(e->{minusCheck.setSelected(false);zeroCheck.setSelected(false);plusCheck.setSelected(false);uncrossedCheck.setSelected(false);});
            HBox quickButtons = new HBox(10, selectAll, selectNone);

            // Check for missing background images and add warning if needed
            Node backgroundWarning = createBackgroundWarningIfNeeded(modality, objective, detector);
            
            VBox content = new VBox(10);
            content.getChildren().add(info);
            if (backgroundWarning != null) {
                content.getChildren().add(backgroundWarning);
            }
            content.getChildren().addAll(grid, new Separator(), quickButtons);
            content.setPadding(new Insets(20));
            dialog.getDialogPane().setContent(content);

            ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);
            Node okNode = dialog.getDialogPane().lookupButton(okType);

            BooleanBinding valid = Bindings.createBooleanBinding(() -> {
                boolean any = minusCheck.isSelected() || zeroCheck.isSelected() || plusCheck.isSelected() || uncrossedCheck.isSelected();
                if(!any) return false;
                if(uncrossedCheck.isSelected() && !isValidPosDouble(uncrossedExposure.getText())) return false;
                if(plusCheck.isSelected() && !isValidPosDouble(plusExposure.getText())) return false;
                if(zeroCheck.isSelected() && !isValidPosDouble(zeroExposure.getText())) return false;
                if(minusCheck.isSelected() && !isValidPosDouble(minusExposure.getText())) return false;

                return true;
            }, minusCheck.selectedProperty(), zeroCheck.selectedProperty(), plusCheck.selectedProperty(), uncrossedCheck.selectedProperty(),
            minusExposure.textProperty(), zeroExposure.textProperty(), plusExposure.textProperty(), uncrossedExposure.textProperty());
            okNode.disableProperty().bind(valid.not());

            dialog.setResultConverter(button -> {
                if(button==okType){
                    List<AngleExposure> list = new ArrayList<>();
                    if(uncrossedCheck.isSelected()) list.add(new AngleExposure(uncrossedAngle,Double.parseDouble(uncrossedExposure.getText())));
                    if(plusCheck.isSelected()) list.add(new AngleExposure(plusAngle,Double.parseDouble(plusExposure.getText())));
                    if(zeroCheck.isSelected()) list.add(new AngleExposure(0.0,Double.parseDouble(zeroExposure.getText())));
                    if(minusCheck.isSelected()) list.add(new AngleExposure(minusAngle,Double.parseDouble(minusExposure.getText())));

                    logger.info("PPM angles and exposures selected: {}", list);
                    return new AngleExposureResult(list);
                }
                return null;
            });

            // Handle the dialog result and validation asynchronously
            dialog.showAndWait().ifPresentOrElse(result -> {
                // Perform background validation asynchronously after dialog closes
                performBackgroundValidationAsync(result, modality, objective, detector, future);
            }, () -> future.cancel(true));
        });
        return future;
    }

    /**
     * Performs background validation asynchronously after the main dialog closes.
     * This prevents deadlocks by not blocking the JavaFX thread during dialog result conversion.
     *
     * @param result the selected angle-exposure result
     * @param modality the modality name
     * @param objective the objective ID
     * @param detector the detector ID
     * @param future the future to complete with the final result
     */
    private static void performBackgroundValidationAsync(AngleExposureResult result, String modality,
                                                        String objective, String detector,
                                                        CompletableFuture<AngleExposureResult> future) {
        // Validate against background settings if available
        try {
            validateAgainstBackgroundSettingsAsync(result.angleExposures, modality, objective, detector, future, result);
        } catch (Exception e) {
            logger.warn("Background validation failed with error: {}", e.getMessage());
            // Complete with the result anyway for other errors
            future.complete(result);
        }
    }

    /**
     * Asynchronous version of background settings validation that doesn't block the JavaFX thread.
     *
     * @param selectedAngles the user's selected angle-exposure pairs
     * @param modality the modality name
     * @param objective the objective ID
     * @param detector the detector ID
     * @param future the future to complete based on validation result
     * @param result the result to complete with if validation passes
     */
    private static void validateAgainstBackgroundSettingsAsync(List<AngleExposure> selectedAngles,
                                                              String modality, String objective, String detector,
                                                              CompletableFuture<AngleExposureResult> future,
                                                              AngleExposureResult result) {
        // Skip validation if hardware parameters are missing
        if (modality == null || objective == null || detector == null) {
            logger.debug("Skipping background validation - missing hardware parameters");
            future.complete(result);
            return;
        }

        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            String backgroundFolder = configManager.getBackgroundCorrectionFolder(modality);

            if (backgroundFolder == null) {
                logger.debug("No background correction folder configured - skipping validation");
                future.complete(result);
                return;
            }

            BackgroundSettingsReader.BackgroundSettings backgroundSettings =
                    BackgroundSettingsReader.findBackgroundSettings(backgroundFolder, modality, objective, detector);

            if (backgroundSettings == null) {
                logger.debug("No background settings found for {}/{}/{} - skipping validation",
                        modality, objective, detector);
                future.complete(result);
                return;
            }

            // Convert UI angle-exposure pairs to the format expected by validator
            List<qupath.ext.qpsc.modality.AngleExposure> currentAngles = new ArrayList<>();
            for (AngleExposure ae : selectedAngles) {
                currentAngles.add(new qupath.ext.qpsc.modality.AngleExposure(ae.angle, ae.exposureMs));
            }

            // Validate with a tolerance of 0.1ms
            // Use subset validation to allow users to select fewer angles than background images exist for
            double tolerance = 0.1;
            boolean matches = BackgroundSettingsReader.validateAngleExposuresSubset(
                    backgroundSettings, currentAngles, tolerance);

            if (!matches) {
                logger.warn("Selected exposure settings do not match existing background settings");
                // Show warning dialog asynchronously
                showBackgroundMismatchWarningAsync(backgroundSettings, selectedAngles, future, result);
            } else {
                logger.info("Selected exposure settings match existing background settings");
                future.complete(result);
            }

        } catch (Exception e) {
            logger.error("Error validating against background settings", e);
            // Complete with result anyway for validation errors
            future.complete(result);
        }
    }

    /**
     * Shows background mismatch warning dialog asynchronously without blocking the JavaFX thread.
     *
     * @param backgroundSettings the existing background settings
     * @param selectedAngles the user's selected angle-exposure pairs
     * @param future the future to complete based on user choice
     * @param result the result to complete with if user chooses to proceed
     */
    private static void showBackgroundMismatchWarningAsync(BackgroundSettingsReader.BackgroundSettings backgroundSettings,
                                                          List<AngleExposure> selectedAngles,
                                                          CompletableFuture<AngleExposureResult> future,
                                                          AngleExposureResult result) {
        Platform.runLater(() -> {
            Alert warning = new Alert(Alert.AlertType.WARNING);
            warning.setTitle("Background Correction Issue");
            warning.setHeaderText("Selected angles missing background images or have exposure mismatches");

            StringBuilder message = new StringBuilder();
            message.append("Some of your selected angles have issues that may affect background correction:\n\n");
            message.append("• Missing background images: Cannot perform background correction\n");
            message.append("• Exposure mismatches: Background correction may be inaccurate\n\n");

            message.append("Background settings (from ").append(backgroundSettings.settingsFilePath).append("):\n");
            for (qupath.ext.qpsc.modality.AngleExposure bgAe : backgroundSettings.angleExposures) {
                message.append(String.format("  %.1f° = %.1f ms\n", bgAe.ticks(), bgAe.exposureMs()));
            }

            message.append("\nYour selected settings:\n");
            for (AngleExposure userAe : selectedAngles) {
                message.append(String.format("  %.1f° = %.1f ms\n", userAe.angle, userAe.exposureMs));
            }

            message.append("\nDetailed differences:\n");
            message.append(getDetailedMismatchInfo(backgroundSettings, selectedAngles));

            message.append("\nRecommendation: Use the background settings exposure times for optimal results,");
            message.append(" or collect new background images with your selected exposure times.");

            warning.setContentText(message.toString());
            warning.setResizable(true);

            // Make dialog larger to accommodate content
            warning.getDialogPane().setPrefWidth(600);
            warning.getDialogPane().setPrefHeight(400);

            // Make the dialog modal and always on top
            warning.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            warning.initOwner(javafx.stage.Stage.getWindows().stream()
                    .filter(javafx.stage.Window::isShowing)
                    .findFirst().orElse(null));

            // Set custom button types for clearer user choice
            warning.getButtonTypes().clear();
            ButtonType proceedButton = new ButtonType("Proceed Anyway", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel Acquisition", ButtonBar.ButtonData.CANCEL_CLOSE);
            warning.getButtonTypes().addAll(proceedButton, cancelButton);

            // Make Cancel the default button to encourage users to review settings
            Button cancelBtn = (Button) warning.getDialogPane().lookupButton(cancelButton);
            Button proceedBtn = (Button) warning.getDialogPane().lookupButton(proceedButton);

            cancelBtn.setDefaultButton(true);
            proceedBtn.setDefaultButton(false);

            // Style the proceed button to indicate it's not recommended
            proceedBtn.setStyle("-fx-base: #ffcc99;"); // Light orange to indicate caution

            // Handle the result asynchronously
            warning.showAndWait().ifPresentOrElse(
                buttonType -> {
                    if (buttonType == proceedButton) {
                        logger.info("User chose to proceed despite background settings mismatch");
                        future.complete(result);
                    } else {
                        logger.info("User chose to cancel acquisition due to background settings mismatch");
                        future.cancel(true);
                    }
                },
                () -> {
                    logger.info("Background mismatch dialog was closed - canceling acquisition");
                    future.cancel(true);
                }
            );
        });
    }

    /**
     * Gets default exposure time for a given angle following priority order:
     * 1. Background image exposure times per angle
     * 2. Config file for the current microscope
     * 3. Persistent preferences
     * 
     * @param angle the angle in degrees
     * @param modality the modality name (e.g., "ppm")
     * @param objective the objective ID
     * @param detector the detector ID
     * @return default exposure time in ms, or fallback value if not found
     */
    private static double getDefaultExposureTime(double angle, String modality, String objective, String detector) {
        logger.debug("Getting default exposure time for angle {} with modality={}, objective={}, detector={}", 
                angle, modality, objective, detector);
        
        // If any parameters are null, skip to persistent preferences
        if (modality == null || objective == null || detector == null) {
            logger.debug("Missing hardware parameters, skipping to persistent preferences");
            double preferencesValue = getPersistentPreferenceExposure(angle);
            logger.info("Using persistent preferences exposure time for angle {}: {}ms", angle, preferencesValue);
            return preferencesValue;
        }
        
        // Priority 1: Check background image exposure times per angle
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            String backgroundFolder = configManager.getBackgroundCorrectionFolder(modality);
            
            if (backgroundFolder != null) {
                BackgroundSettingsReader.BackgroundSettings backgroundSettings = 
                        BackgroundSettingsReader.findBackgroundSettings(backgroundFolder, modality, objective, detector);
                
                if (backgroundSettings != null && backgroundSettings.angleExposures != null) {
                    // Find matching angle in background settings
                    for (qupath.ext.qpsc.modality.AngleExposure ae : backgroundSettings.angleExposures) {
                        if (Math.abs(ae.ticks() - angle) < 0.001) { // Match with small tolerance
                            logger.info("Using background image exposure time for angle {}: {}ms", angle, ae.exposureMs());
                            return ae.exposureMs();
                        }
                    }
                    logger.debug("No matching angle found in background settings for angle {}", angle);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read background settings for exposure time", e);
        }
        
        // Priority 2: Check config file for current microscope
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            Map<String, Object> exposures = configManager.getModalityExposures(modality, objective, detector);
            
            if (exposures != null) {
                // Look for angle-specific exposure or general exposure
                String angleKey = String.valueOf(angle);
                if (exposures.containsKey(angleKey)) {
                    Object exposureValue = exposures.get(angleKey);
                    if (exposureValue instanceof Number) {
                        double configExposure = ((Number) exposureValue).doubleValue();
                        logger.info("Using config file exposure time for angle {}: {}ms", angle, configExposure);
                        return configExposure;
                    }
                }
                
                // Try common angle names
                String[] angleNames = getAngleNames(angle);
                for (String angleName : angleNames) {
                    if (exposures.containsKey(angleName)) {
                        Object exposureValue = exposures.get(angleName);
                        if (exposureValue instanceof Number) {
                            double configExposure = ((Number) exposureValue).doubleValue();
                            logger.info("Using config file exposure time for angle {} (key={}): {}ms", 
                                    angle, angleName, configExposure);
                            return configExposure;
                        }
                    }
                }
                logger.debug("No exposure setting found in config for angle {}", angle);
            }
        } catch (Exception e) {
            logger.debug("Failed to read config file exposure settings", e);
        }
        
        // Priority 3: Fallback to persistent preferences
        double preferencesValue = getPersistentPreferenceExposure(angle);
        logger.info("Using persistent preferences exposure time for angle {}: {}ms", angle, preferencesValue);
        return preferencesValue;
    }
    
    /**
     * Get common names for an angle that might be used in config files.
     */
    private static String[] getAngleNames(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return new String[]{"0", "zero", "0.0"};
        } else if (angle > 0 && angle < 20) {
            return new String[]{"plus", "positive", String.valueOf(angle)};
        } else if (angle < 0 && angle > -20) {
            return new String[]{"minus", "negative", String.valueOf(angle)};
        } else if (angle >= 40 && angle <= 50) {
            return new String[]{"uncrossed", "45", "45.0", String.valueOf(angle)};
        }
        return new String[]{String.valueOf(angle)};
    }
    
    /**
     * Get exposure time from persistent preferences for a given angle.
     */
    private static double getPersistentPreferenceExposure(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return PPMPreferences.getZeroExposureMs();
        } else if (angle > 0 && angle < 20) {
            return PPMPreferences.getPlusExposureMs();
        } else if (angle < 0 && angle > -20) {
            return PPMPreferences.getMinusExposureMs();
        } else if (angle >= 40 && angle <= 50) {
            return PPMPreferences.getUncrossedExposureMs();
        }
        
        // Default fallback
        return 1.0;
    }

    /**
     * Container for background validation results
     */
    public static class BackgroundValidationResult {
        public final Set<Double> anglesWithoutBackground;
        public final Set<Double> angleswithExposureMismatches;
        public final String userMessage;

        public BackgroundValidationResult(Set<Double> anglesWithoutBackground,
                                        Set<Double> anglesWithExposureMismatches,
                                        String userMessage) {
            this.anglesWithoutBackground = anglesWithoutBackground;
            this.angleswithExposureMismatches = anglesWithExposureMismatches;
            this.userMessage = userMessage;
        }

        public boolean hasIssues() {
            return !anglesWithoutBackground.isEmpty() || !angleswithExposureMismatches.isEmpty();
        }
    }

    /**
     * Validates background settings against user selections and returns structured results.
     *
     * @param backgroundSettings the existing background settings
     * @param selectedAngles the user's selected angle-exposure pairs
     * @return validation results with angles to disable background correction for
     */
    public static BackgroundValidationResult validateBackgroundSettings(BackgroundSettingsReader.BackgroundSettings backgroundSettings,
                                                                        List<AngleExposure> selectedAngles) {
        // Convert user angles to the format used by background settings
        Map<Double, Double> userAngleMap = new HashMap<>();
        for (AngleExposure userAe : selectedAngles) {
            userAngleMap.put(userAe.angle, userAe.exposureMs);
        }

        Map<Double, Double> bgAngleMap = new HashMap<>();
        for (qupath.ext.qpsc.modality.AngleExposure bgAe : backgroundSettings.angleExposures) {
            bgAngleMap.put(bgAe.ticks(), bgAe.exposureMs());
        }

        // Find selected angles that have NO background images
        Set<Double> anglesWithoutBackground = new HashSet<>();
        for (Double userAngle : userAngleMap.keySet()) {
            if (!bgAngleMap.containsKey(userAngle)) {
                anglesWithoutBackground.add(userAngle);
            }
        }

        // Find angles with exposure mismatches
        Set<Double> anglesWithExposureMismatches = new HashSet<>();
        double tolerance = 0.1;
        for (Double angle : userAngleMap.keySet()) {
            if (bgAngleMap.containsKey(angle)) {
                double userExposure = userAngleMap.get(angle);
                double bgExposure = bgAngleMap.get(angle);
                double diff = Math.abs(userExposure - bgExposure);

                if (diff > tolerance) {
                    anglesWithExposureMismatches.add(angle);
                }
            }
        }

        // Generate user message
        String userMessage = generateBackgroundValidationMessage(anglesWithoutBackground, anglesWithExposureMismatches,
                                                               userAngleMap, bgAngleMap, tolerance);

        return new BackgroundValidationResult(anglesWithoutBackground, anglesWithExposureMismatches, userMessage);
    }

    /**
     * Generates user message for background validation issues.
     */
    private static String generateBackgroundValidationMessage(Set<Double> anglesWithoutBackground,
                                                            Set<Double> anglesWithExposureMismatches,
                                                            Map<Double, Double> userAngleMap,
                                                            Map<Double, Double> bgAngleMap,
                                                            double tolerance) {
        StringBuilder info = new StringBuilder();

        if (!anglesWithoutBackground.isEmpty()) {
            info.append("  Selected angles without background images: ");
            anglesWithoutBackground.forEach(angle -> info.append(String.format("%.1f° ", angle)));
            info.append("\n  → Background correction will be DISABLED for these angles\n");
        }

        if (!anglesWithExposureMismatches.isEmpty()) {
            info.append("  Exposure time mismatches (background correction will be DISABLED):\n");
            for (Double angle : anglesWithExposureMismatches) {
                double userExposure = userAngleMap.get(angle);
                double bgExposure = bgAngleMap.get(angle);
                double diff = Math.abs(userExposure - bgExposure);
                info.append(String.format("    %.1f°: selected %.1f ms vs background %.1f ms (diff: %.1f ms)\n",
                        angle, userExposure, bgExposure, diff));
            }
        }

        // If no specific issues found, provide general explanation
        if (info.length() == 0) {
            info.append("  Background images exist for different angles than selected");
        }

        return info.toString();
    }

    /**
     * Gets detailed mismatch information between background settings and user selections.
     * Focuses on actual issues: missing background images and exposure mismatches.
     *
     * @param backgroundSettings the existing background settings
     * @param selectedAngles the user's selected angle-exposure pairs
     * @return formatted string describing the actual problems
     */
    private static String getDetailedMismatchInfo(BackgroundSettingsReader.BackgroundSettings backgroundSettings,
                                                 List<AngleExposure> selectedAngles) {
        BackgroundValidationResult result = validateBackgroundSettings(backgroundSettings, selectedAngles);
        return result.userMessage;
    }
    
    /**
     * Creates a prominent warning UI component if background images are missing.
     * 
     * @param modality the modality name
     * @param objective the objective ID  
     * @param detector the detector ID
     * @return warning Node or null if background images are available
     */
    private static Node createBackgroundWarningIfNeeded(String modality, String objective, String detector) {
        // Skip if hardware parameters are missing
        if (modality == null || objective == null || detector == null) {
            return null;
        }
        
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
            
            // Check if background correction is enabled
            boolean bgEnabled = configManager.isBackgroundCorrectionEnabled(modality);
            if (!bgEnabled) {
                // Background correction is disabled, no warning needed
                return null;
            }
            
            String backgroundFolder = configManager.getBackgroundCorrectionFolder(modality);
            if (backgroundFolder == null) {
                return createBackgroundWarning(
                    "⚠️ BACKGROUND CORRECTION ISSUE",
                    "Background correction is enabled but no background folder is configured.",
                    "Configure background folder or disable background correction.",
                    true
                );
            }
            
            // Check if background settings/images exist for this hardware combination
            BackgroundSettingsReader.BackgroundSettings backgroundSettings = 
                    BackgroundSettingsReader.findBackgroundSettings(backgroundFolder, modality, objective, detector);
            
            if (backgroundSettings == null) {
                return createBackgroundWarning(
                    "⚠️ MISSING BACKGROUND IMAGES",
                    String.format("No background images found for %s + %s + %s", 
                            modality, getObjectiveDisplayName(objective), getDetectorDisplayName(detector)),
                    "Collect background images before acquisition for optimal image quality.",
                    true
                );
            }
            
            // Background images found - no warning needed
            logger.debug("Background images found for {}/{}/{}", modality, objective, detector);
            return null;
            
        } catch (Exception e) {
            logger.error("Error checking background image availability", e);
            return createBackgroundWarning(
                "⚠️ BACKGROUND VALIDATION ERROR", 
                "Could not verify background image availability: " + e.getMessage(),
                "Check background correction configuration.",
                false
            );
        }
    }
    
    /**
     * Creates a visual warning component with consistent styling.
     */
    private static Node createBackgroundWarning(String title, String message, String recommendation, boolean isError) {
        VBox warningBox = new VBox(5);
        warningBox.setPadding(new Insets(10));
        warningBox.setStyle(
            "-fx-background-color: " + (isError ? "#ffebee" : "#fff3e0") + ";" +
            "-fx-border-color: " + (isError ? "#f44336" : "#ff9800") + ";" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 5;" +
            "-fx-background-radius: 5;"
        );
        
        // Title
        Label titleLabel = new Label(title);
        titleLabel.setStyle(
            "-fx-font-weight: bold;" +
            "-fx-font-size: 14px;" +
            "-fx-text-fill: " + (isError ? "#d32f2f" : "#e65100") + ";"
        );
        
        // Message
        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(500);
        messageLabel.setStyle("-fx-font-size: 12px;");
        
        // Recommendation
        Label recommendationLabel = new Label("→ " + recommendation);
        recommendationLabel.setWrapText(true);
        recommendationLabel.setMaxWidth(500);
        recommendationLabel.setStyle(
            "-fx-font-style: italic;" +
            "-fx-font-size: 11px;" +
            "-fx-text-fill: " + (isError ? "#d32f2f" : "#e65100") + ";"
        );
        
        warningBox.getChildren().addAll(titleLabel, messageLabel, recommendationLabel);
        return warningBox;
    }
    
    /**
     * Gets a user-friendly display name for an objective.
     */
    private static String getObjectiveDisplayName(String objective) {
        if (objective == null) return "Unknown Objective";
        
        // Extract magnification and meaningful parts
        if (objective.contains("OLYMPUS")) {
            if (objective.contains("10X")) return "10x Olympus";
            if (objective.contains("20X")) return "20x Olympus"; 
            if (objective.contains("40X")) return "40x Olympus";
        }
        
        // Fallback to extracting magnification
        if (objective.contains("10X") || objective.contains("10x")) return "10x";
        if (objective.contains("20X") || objective.contains("20x")) return "20x";
        if (objective.contains("40X") || objective.contains("40x")) return "40x";
        
        return objective; // Return full name if no pattern matches
    }
    
    /**
     * Gets a user-friendly display name for a detector.
     */
    private static String getDetectorDisplayName(String detector) {
        if (detector == null) return "Unknown Camera";
        
        if (detector.contains("JAI")) return "JAI Camera";
        if (detector.contains("FLIR")) return "FLIR Camera";
        if (detector.contains("BASLER")) return "Basler Camera";
        
        return detector; // Return full name if no pattern matches
    }

    /**
     * Validates that a text string represents a positive decimal number.
     * 
     * <p>This helper method is used to validate exposure time input fields to ensure
     * they contain valid positive decimal values before enabling the OK button.</p>
     * 
     * @param text the text string to validate
     * @return {@code true} if the text represents a positive number, {@code false} otherwise
     */
    private static boolean isValidPosDouble(String text){
        try{double v=Double.parseDouble(text);return v>0;}catch(NumberFormatException e){return false;}
    }
}
