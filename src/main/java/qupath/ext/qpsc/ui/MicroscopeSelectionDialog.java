package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for selecting the source microscope/scanner for alignment.
 * Ensures only microscopes with macro image support are selectable.
 */
public class MicroscopeSelectionDialog {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeSelectionDialog.class);

    /**
     * Result of microscope selection
     */
    public record MicroscopeSelection(
            String microscopeName,
            boolean hasMacroSupport,
            String configPath
    ) {}

    /**
     * Shows the microscope selection dialog.
     *
     * @return CompletableFuture with the selected microscope, or null if cancelled
     */
    public static CompletableFuture<MicroscopeSelection> showDialog() {
        CompletableFuture<MicroscopeSelection> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Get current microscope config
                String currentConfigPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager currentMgr = MicroscopeConfigManager.getInstance(currentConfigPath);
                String currentMicroscope = currentMgr.getString("microscope", "name");

                if (currentMicroscope == null) {
                    logger.error("Current microscope has no name in config");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Configuration Error");
                    alert.setHeaderText("Current microscope configuration is invalid");
                    alert.setContentText("The current microscope configuration file does not have a 'microscope: name:' entry.");
                    alert.showAndWait();
                    future.complete(null);
                    return;
                }

                logger.info("Current microscope: {}", currentMicroscope);

                // Get base directory for configs
                File configDir = new File(currentConfigPath).getParentFile();
                logger.info("Looking for microscope configs in: {}", configDir);

                // Find all config files
                Map<String, File> microscopeConfigs = new HashMap<>();
                File[] yamlFiles = configDir.listFiles((dir, name) ->
                        name.startsWith("config_") && name.endsWith(".yml"));

                if (yamlFiles != null) {
                    logger.info("Found {} YAML files", yamlFiles.length);
                    for (File file : yamlFiles) {
                        try {
                            logger.debug("Checking file: {}", file.getName());

                            // Read the YAML file directly to avoid singleton caching issues
                            Map<String, Object> configData = MinorFunctions.loadYamlFile(file.getAbsolutePath());
                            String name = (String) MinorFunctions.getYamlValue(configData, "microscope", "name");

                            if (name == null) {
                                logger.warn("No microscope name found in {}", file.getName());
                                continue;
                            }

                            logger.info("Found microscope '{}' in {}", name, file.getName());

                            // Skip current microscope
                            if (!name.equals(currentMicroscope)) {
                                microscopeConfigs.put(name, file);
                                logger.info("Added microscope '{}' to selection list", name);
                            } else {
                                logger.info("Skipping current microscope '{}'", name);
                            }
                        } catch (Exception e) {
                            logger.warn("Could not read config file: {}", file.getName(), e);
                        }
                    }
                } else {
                    logger.error("No YAML files found in directory");
                }

                logger.info("Total selectable microscopes found: {}", microscopeConfigs.size());

                if (microscopeConfigs.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("No Microscopes Found");
                    alert.setHeaderText("No other microscope configurations found");
                    alert.setContentText(
                            "No microscope configuration files were found in:\n" + configDir + "\n\n" +
                                    "To create alignments, you need configuration files for the source microscopes.\n" +
                                    "Create a config_[MicroscopeName].yml file for each microscope you want to align from."
                    );
                    alert.showAndWait();
                    future.complete(null);
                    return;
                }

                // Create dialog
                Dialog<MicroscopeSelection> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Select Source Microscope");
                dialog.setHeaderText("Select the microscope/scanner that created this image:");
                dialog.setResizable(true);

                // Create content
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(500);

                // Microscope selection
                ComboBox<String> microscopeCombo = new ComboBox<>();
                microscopeCombo.getItems().addAll(microscopeConfigs.keySet());
                microscopeCombo.setPrefWidth(400);

                // Try to restore last selection
                String lastSelected = PersistentPreferences.getSelectedScanner();
                if (microscopeConfigs.containsKey(lastSelected)) {
                    microscopeCombo.setValue(lastSelected);
                } else if (!microscopeConfigs.isEmpty()) {
                    microscopeCombo.getSelectionModel().selectFirst();
                }

                // Info area
                TextArea infoArea = new TextArea();
                infoArea.setPrefRowCount(4);
                infoArea.setEditable(false);
                infoArea.setWrapText(true);

                // Warning label
                Label warningLabel = new Label();
                warningLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                warningLabel.setWrapText(true);
                warningLabel.setVisible(false);

                // Update info when selection changes
                microscopeCombo.valueProperty().addListener((obs, old, selected) -> {
                    if (selected != null) {
                        File configFile = microscopeConfigs.get(selected);
                        try {
                            // Load the config data for this specific file
                            Map<String, Object> configData = MinorFunctions.loadYamlFile(configFile.getAbsolutePath());

                            // Check for macro support at root level
                            boolean hasMacro = configData.containsKey("macro");

                            StringBuilder info = new StringBuilder();
                            info.append("Config file: ").append(configFile.getName()).append("\n");

                            // Get microscope type
                            String type = (String) MinorFunctions.getYamlValue(configData, "microscope", "type");
                            if (type != null) {
                                info.append("Type: ").append(type).append("\n");
                            }

                            if (hasMacro) {
                                Double pixelSize = MinorFunctions.getYamlDouble(configData, "macro", "pixelSize_um");

                                info.append("Macro image: SUPPORTED");
                                if (pixelSize != null) {
                                    info.append(" (").append(pixelSize).append(" µm/pixel)");
                                }
                                info.append("\n");

                                // Check if cropping is required
                                Boolean cropRequired = MinorFunctions.getYamlBoolean(configData, "macro", "requiresCropping");
                                if (cropRequired != null && cropRequired) {
                                    info.append("Requires slide area cropping\n");
                                }

                                warningLabel.setVisible(false);
                            } else {
                                info.append("Macro image: NOT SUPPORTED\n");
                                warningLabel.setText("⚠ This microscope does not have macro image support. " +
                                        "Manual alignment is the only option for non-macro image scanners.");
                                warningLabel.setVisible(true);
                            }

                            infoArea.setText(info.toString());

                        } catch (Exception e) {
                            infoArea.setText("Error reading config: " + e.getMessage());
                            logger.error("Error reading microscope config", e);
                        }
                    }
                });

                // Trigger initial update
                if (microscopeCombo.getValue() != null) {
                    String initialValue = microscopeCombo.getValue();
                    microscopeCombo.setValue(null);
                    microscopeCombo.setValue(initialValue);
                }

                // Info about current microscope
                Label currentInfo = new Label("Current microscope: " + currentMicroscope +
                        " (alignments cannot be created to the same microscope)");
                currentInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

                // Assemble content
                content.getChildren().addAll(
                        new Label("Source microscope/scanner:"),
                        microscopeCombo,
                        new Label("Configuration details:"),
                        infoArea,
                        warningLabel,
                        new Separator(),
                        currentInfo
                );

                // Set up dialog buttons
                ButtonType okButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
                dialog.getDialogPane().setContent(content);

                // Disable OK button if no macro support
                Button okBtn = (Button) dialog.getDialogPane().lookupButton(okButton);
                okBtn.disableProperty().bind(warningLabel.visibleProperty());

                // Convert result
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == okButton && microscopeCombo.getValue() != null) {
                        String selected = microscopeCombo.getValue();
                        File configFile = microscopeConfigs.get(selected);

                        // Save selection
                        PersistentPreferences.setSelectedScanner(selected);

                        // Check macro support one more time
                        try {
                            Map<String, Object> configData = MinorFunctions.loadYamlFile(configFile.getAbsolutePath());
                            boolean hasMacro = configData.containsKey("macro");

                            logger.info("User selected microscope '{}' with macro support: {}", selected, hasMacro);
                            return new MicroscopeSelection(selected, hasMacro, configFile.getAbsolutePath());
                        } catch (Exception e) {
                            logger.error("Error checking macro support", e);
                            return null;
                        }
                    }
                    logger.info("Dialog cancelled or no selection");
                    return null;
                });

                // Show dialog
                dialog.showAndWait().ifPresent(future::complete);
                if (!future.isDone()) {
                    logger.info("Dialog closed without selection");
                    future.complete(null);
                }

            } catch (Exception e) {
                logger.error("Error showing microscope selection dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}