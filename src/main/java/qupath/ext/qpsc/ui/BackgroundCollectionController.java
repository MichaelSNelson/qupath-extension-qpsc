package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.BackgroundCollectionWorkflow.BackgroundCollectionResult;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * BackgroundCollectionController - UI for background image acquisition
 * 
 * <p>Provides a dialog for:
 * <ul>
 *   <li>Selecting modality</li>
 *   <li>Adjusting exposure times for each angle</li>
 *   <li>Choosing output folder</li>
 *   <li>Persisting exposure changes back to YAML config</li>
 * </ul>
 */
public class BackgroundCollectionController {
    
    private static final Logger logger = LoggerFactory.getLogger(BackgroundCollectionController.class);
    
    private ComboBox<String> modalityComboBox;
    private ComboBox<String> objectiveComboBox;
    private TextField outputPathField;
    private VBox exposureControlsPane;
    private List<AngleExposure> currentAngleExposures = new ArrayList<>();
    private List<TextField> exposureFields = new ArrayList<>();
    private BackgroundSettingsReader.BackgroundSettings existingBackgroundSettings;
    private Label backgroundValidationLabel;
    
    /**
     * Shows the background collection dialog and returns the result.
     * 
     * @return CompletableFuture with BackgroundCollectionResult, or null if cancelled
     */
    public static CompletableFuture<BackgroundCollectionResult> showDialog() {
        var controller = new BackgroundCollectionController();
        return controller.showDialogInternal();
    }
    
    private CompletableFuture<BackgroundCollectionResult> showDialogInternal() {
        CompletableFuture<BackgroundCollectionResult> future = new CompletableFuture<>();
        
        Platform.runLater(() -> {
            try {
                // Create dialog
                Dialog<BackgroundCollectionResult> dialog = new Dialog<>();
                dialog.setTitle("Background Collection");
                dialog.setHeaderText("Configure background image acquisition for flat field correction");
                
                // Create UI
                VBox content = createDialogContent();
                dialog.getDialogPane().setContent(content);
                dialog.getDialogPane().setPrefWidth(600);
                dialog.getDialogPane().setPrefHeight(500);
                dialog.setResizable(true);
                
                // Add buttons
                ButtonType okButtonType = new ButtonType("Acquire Backgrounds", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);
                
                // Disable OK button initially
                Button okButton = (Button) dialog.getDialogPane().lookupButton(okButtonType);
                okButton.setDisable(true);
                
                // Enable OK when valid modality and objective are selected
                modalityComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = newVal != null && objectiveComboBox.getValue() != null && !outputPathField.getText().trim().isEmpty();
                    okButton.setDisable(!isValid);
                    if (newVal != null) {
                        updateObjectiveSelection(newVal);
                        // Only update exposure controls if both modality and objective are selected
                        if (objectiveComboBox.getValue() != null) {
                            updateExposureControlsWithBackground(newVal, objectiveComboBox.getValue());
                        }
                    } else {
                        // Clear objectives when modality is cleared
                        objectiveComboBox.getItems().clear();
                        objectiveComboBox.setDisable(true);
                        clearExposureControls();
                    }
                });
                
                // Add objective change listener
                objectiveComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = modalityComboBox.getValue() != null && newVal != null && !outputPathField.getText().trim().isEmpty();
                    okButton.setDisable(!isValid);
                    // Update exposure controls when objective changes (if modality is also selected)
                    if (newVal != null && modalityComboBox.getValue() != null) {
                        updateExposureControlsWithBackground(modalityComboBox.getValue(), newVal);
                    } else {
                        clearExposureControls();
                    }
                });
                
                outputPathField.textProperty().addListener((obs, oldVal, newVal) -> {
                    boolean isValid = modalityComboBox.getValue() != null && objectiveComboBox.getValue() != null && !newVal.trim().isEmpty();
                    okButton.setDisable(!isValid);
                });
                
                // Set result converter
                dialog.setResultConverter(dialogButton -> {
                    if (dialogButton == okButtonType) {
                        return createResult();
                    }
                    return null;
                });
                
                // Show dialog
                dialog.showAndWait().ifPresentOrElse(
                    result -> {
                        if (result != null) {
                            // Save exposure changes to YAML config
                            saveExposureChangesToConfig(result.modality(), result.angleExposures());
                        }
                        future.complete(result);
                    },
                    () -> future.complete(null)
                );
                
            } catch (Exception e) {
                logger.error("Error creating background collection dialog", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    private VBox createDialogContent() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        
        // Instructions
        Label instructionLabel = new Label(
                "Position the microscope at a clean, blank area before starting acquisition.\n" +
                "Background images will be saved with no processing applied."
        );
        instructionLabel.setWrapText(true);
        instructionLabel.setStyle("-fx-font-style: italic;");
        
        // Modality selection
        GridPane modalityPane = new GridPane();
        modalityPane.setHgap(10);
        modalityPane.setVgap(10);
        
        Label modalityLabel = new Label("Modality:");
        modalityComboBox = new ComboBox<>();
        // Get available modalities from configuration
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableModalities = configManager.getAvailableModalities();
            modalityComboBox.getItems().addAll(availableModalities);
        } catch (Exception e) {
            logger.error("Failed to load available modalities", e);
            // Fallback to common modality names
            modalityComboBox.getItems().addAll("ppm", "brightfield", "fluorescence");
        }
        modalityComboBox.setPromptText("Select modality...");
        
        modalityPane.add(modalityLabel, 0, 0);
        modalityPane.add(modalityComboBox, 1, 0);
        
        // Objective selection
        Label objectiveLabel = new Label("Objective:");
        objectiveComboBox = new ComboBox<>();
        objectiveComboBox.setPromptText("Select objective...");
        objectiveComboBox.setDisable(true); // Disabled until modality is selected
        
        modalityPane.add(objectiveLabel, 0, 1);
        modalityPane.add(objectiveComboBox, 1, 1);
        
        // Output path selection
        Label outputLabel = new Label("Output folder:");
        outputPathField = new TextField();
        outputPathField.setPromptText("Select folder for background images...");
        outputPathField.setPrefWidth(300);
        
        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseForOutputFolder());
        
        HBox outputPane = new HBox(10, outputPathField, browseButton);
        outputPane.setAlignment(Pos.CENTER_LEFT);
        
        modalityPane.add(outputLabel, 0, 2);
        modalityPane.add(outputPane, 1, 2);
        
        // Set default output path
        setDefaultOutputPath();
        
        // Exposure controls (will be populated when modality AND objective are selected)
        Label exposureLabel = new Label("Exposure Times (ms):");
        exposureControlsPane = new VBox(10);
        
        // Background validation label
        backgroundValidationLabel = new Label();
        backgroundValidationLabel.setWrapText(true);
        backgroundValidationLabel.setVisible(false); // Hidden until needed
        
        content.getChildren().addAll(
                instructionLabel,
                new Separator(),
                modalityPane,
                new Separator(),
                exposureLabel,
                backgroundValidationLabel,
                exposureControlsPane
        );
        
        return content;
    }
    
    /**
     * Clear exposure controls when no valid modality/objective combination is selected
     */
    private void clearExposureControls() {
        logger.debug("Clearing exposure controls");
        exposureControlsPane.getChildren().clear();
        exposureFields.clear();
        currentAngleExposures.clear();
        existingBackgroundSettings = null;
        backgroundValidationLabel.setVisible(false);
    }
    
    /**
     * Update exposure controls with background validation for the given modality and objective
     */
    private void updateExposureControlsWithBackground(String modality, String objective) {
        logger.info("Updating exposure controls with background validation for modality: {}, objective: {}", modality, objective);
        
        clearExposureControls();
        
        // Get modality handler
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        if (handler == null) {
            logger.warn("No handler found for modality: {}", modality);
            return;
        }
        
        // Try to find existing background settings
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            String baseBackgroundFolder = configManager.getBackgroundCorrectionFolder(modality);
            
            if (baseBackgroundFolder != null) {
                // Get detector for this modality/objective combination
                Set<String> detectors = configManager.getAvailableDetectorsForModalityObjective(modality, objective);
                if (!detectors.isEmpty()) {
                    String detector = detectors.iterator().next();
                    existingBackgroundSettings = BackgroundSettingsReader.findBackgroundSettings(
                            baseBackgroundFolder, modality, objective, detector);
                    
                    if (existingBackgroundSettings != null) {
                        logger.info("Found existing background settings: {}", existingBackgroundSettings);
                    } else {
                        logger.debug("No existing background settings found for {}/{}/{}", modality, objective, detector);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error searching for background settings", e);
        }
        
        // Get default angles and exposures
        logger.debug("Requesting rotation angles for modality: {}", modality);
        handler.getRotationAngles(modality).thenAccept(defaultExposures -> {
            Platform.runLater(() -> {
                logger.debug("Creating exposure controls for {} angles", defaultExposures.size());
                
                // Use background settings if available, otherwise use defaults
                List<AngleExposure> exposuresToUse = defaultExposures;
                if (existingBackgroundSettings != null) {
                    exposuresToUse = existingBackgroundSettings.angleExposures;
                    showBackgroundValidationMessage("Found existing background settings. Values have been auto-filled.", 
                            "-fx-text-fill: green; -fx-font-weight: bold;");
                }
                
                // Clear and update current values
                currentAngleExposures.clear();
                currentAngleExposures.addAll(exposuresToUse);
                
                // Create exposure controls
                GridPane exposureGrid = new GridPane();
                exposureGrid.setHgap(10);
                exposureGrid.setVgap(5);
                
                for (int i = 0; i < exposuresToUse.size(); i++) {
                    AngleExposure ae = exposuresToUse.get(i);
                    
                    Label angleLabel = new Label(String.format("%.1f°:", ae.ticks()));
                    TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
                    exposureField.setPrefWidth(100);
                    
                    // Update current values when user changes exposure
                    final int index = i;
                    exposureField.textProperty().addListener((obs, oldVal, newVal) -> {
                        try {
                            double newExposure = Double.parseDouble(newVal);
                            if (index < currentAngleExposures.size()) {
                                AngleExposure oldAe = currentAngleExposures.get(index);
                                currentAngleExposures.set(index, new AngleExposure(oldAe.ticks(), newExposure));
                                
                                // Validate against background settings if they exist
                                validateCurrentSettings();
                            }
                        } catch (NumberFormatException e) {
                            // Invalid input, ignore
                        }
                    });
                    
                    exposureGrid.add(angleLabel, 0, i);
                    exposureGrid.add(exposureField, 1, i);
                    exposureGrid.add(new Label("ms"), 2, i);
                    
                    exposureFields.add(exposureField);
                }
                
                // Add the grid to the exposure controls pane
                exposureControlsPane.getChildren().add(exposureGrid);
                logger.debug("Exposure controls added to dialog");
            });
        }).exceptionally(ex -> {
            logger.error("Failed to get rotation angles for modality: {}", modality, ex);
            Platform.runLater(() -> {
                Label errorLabel = new Label("Failed to load exposure settings for " + modality);
                errorLabel.setStyle("-fx-text-fill: red;");
                exposureControlsPane.getChildren().add(errorLabel);
            });
            return null;
        });
    }
    
    /**
     * Validate current settings against existing background settings
     */
    private void validateCurrentSettings() {
        if (existingBackgroundSettings == null) {
            return; // No background settings to validate against
        }
        
        boolean isValid = BackgroundSettingsReader.validateAngleExposures(
                existingBackgroundSettings, currentAngleExposures, 0.1); // 0.1ms tolerance
        
        if (!isValid) {
            showBackgroundValidationMessage(
                    "⚠️ WARNING: Settings differ from existing background images. " +
                    "Background correction will be disabled unless you acquire new background images with these settings.",
                    "-fx-text-fill: orange; -fx-font-weight: bold;");
        } else {
            showBackgroundValidationMessage(
                    "✓ Settings match existing background images. Background correction will be enabled.",
                    "-fx-text-fill: green; -fx-font-weight: bold;");
        }
    }
    
    /**
     * Show or hide background validation message
     */
    private void showBackgroundValidationMessage(String message, String style) {
        backgroundValidationLabel.setText(message);
        backgroundValidationLabel.setStyle(style);
        backgroundValidationLabel.setVisible(true);
    }
    
    private void updateObjectiveSelection(String modality) {
        logger.info("Updating objective selection for modality: {}", modality);
        
        objectiveComboBox.getItems().clear();
        
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Set<String> availableObjectives = configManager.getAvailableObjectivesForModality(modality);
            
            if (!availableObjectives.isEmpty()) {
                objectiveComboBox.getItems().addAll(availableObjectives);
                objectiveComboBox.setDisable(false);
                logger.info("Loaded {} objectives for modality {}", availableObjectives.size(), modality);
            } else {
                logger.warn("No objectives found for modality: {}", modality);
                objectiveComboBox.setDisable(true);
            }
        } catch (Exception e) {
            logger.error("Failed to load objectives for modality: {}", modality, e);
            objectiveComboBox.setDisable(true);
        }
    }
    
    private void browseForOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Background Images Output Folder");
        
        // Set initial directory to current value if valid
        String currentPath = outputPathField.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentDir = new File(currentPath);
            if (currentDir.exists()) {
                chooser.setInitialDirectory(currentDir);
            }
        }
        
        Stage stage = (Stage) outputPathField.getScene().getWindow();
        File selectedDir = chooser.showDialog(stage);
        
        if (selectedDir != null) {
            outputPathField.setText(selectedDir.getAbsolutePath());
        }
    }
    
    private void setDefaultOutputPath() {
        // Try to get default background folder from config or preferences
        try {
            String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);
            
            // Look for default background folder in modality configuration
            // Use the first available modality to get a default base folder
            Set<String> modalities = configManager.getAvailableModalities();
            String defaultPath = "C:/qpsc_data/background_tiles"; // fallback
            
            if (!modalities.isEmpty()) {
                String firstModality = modalities.iterator().next();
                String bgFolder = configManager.getBackgroundCorrectionFolder(firstModality);
                if (bgFolder != null) {
                    defaultPath = bgFolder;
                }
            }
            
            outputPathField.setText(defaultPath);
            
        } catch (Exception e) {
            logger.warn("Could not determine default background path", e);
            outputPathField.setText("C:/qpsc_data/background_tiles");
        }
    }
    
    private BackgroundCollectionResult createResult() {
        try {
            String modality = modalityComboBox.getValue();
            String objective = objectiveComboBox.getValue();
            String outputPath = outputPathField.getText().trim();
            
            if (modality == null || objective == null || outputPath.isEmpty()) {
                return null;
            }
            
            // Validate exposure values
            List<AngleExposure> finalExposures = new ArrayList<>();
            for (int i = 0; i < exposureFields.size(); i++) {
                try {
                    double exposure = Double.parseDouble(exposureFields.get(i).getText());
                    double angle = currentAngleExposures.get(i).ticks();
                    finalExposures.add(new AngleExposure(angle, exposure));
                } catch (NumberFormatException e) {
                    Dialogs.showErrorMessage("Invalid Exposure", 
                            "Please enter valid numeric values for all exposure times.");
                    return null;
                }
            }
            
            // Check if settings match existing background
            boolean settingsMatchExisting = existingBackgroundSettings != null && 
                    BackgroundSettingsReader.validateAngleExposures(existingBackgroundSettings, finalExposures, 0.1);
            
            return new BackgroundCollectionResult(modality, objective, finalExposures, outputPath, settingsMatchExisting);
            
        } catch (Exception e) {
            logger.error("Error creating result", e);
            Dialogs.showErrorMessage("Error", "Failed to create background collection parameters: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Saves the modified exposure times back to the YAML configuration file.
     */
    private void saveExposureChangesToConfig(String modality, List<AngleExposure> angleExposures) {
        try {
            logger.info("Saving exposure changes to config for modality: {}", modality);
            
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            var configManager = MicroscopeConfigManager.getInstance(configPath);
            
            // TODO: Implement exposure time persistence to YAML config
            // This will require updating the MicroscopeConfigManager to support writing values back
            logger.warn("Exposure time persistence not yet implemented - changes will be lost on restart");
            
        } catch (Exception e) {
            logger.error("Failed to save exposure changes to config", e);
            Platform.runLater(() -> Dialogs.showWarningNotification("Configuration Save Failed", 
                    "Exposure time changes could not be saved to configuration file. " +
                    "Changes will be lost when QuPath restarts."));
        }
    }
}