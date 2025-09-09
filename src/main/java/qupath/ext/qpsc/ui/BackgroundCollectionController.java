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
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
    private TextField outputPathField;
    private VBox exposureControlsPane;
    private List<AngleExposure> currentAngleExposures = new ArrayList<>();
    private List<TextField> exposureFields = new ArrayList<>();
    
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
                dialog.getDialogPane().setPrefWidth(500);
                
                // Add buttons
                ButtonType okButtonType = new ButtonType("Acquire Backgrounds", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);
                
                // Disable OK button initially
                Button okButton = (Button) dialog.getDialogPane().lookupButton(okButtonType);
                okButton.setDisable(true);
                
                // Enable OK when valid modality is selected
                modalityComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                    okButton.setDisable(newVal == null || outputPathField.getText().trim().isEmpty());
                    if (newVal != null) {
                        updateExposureControls(newVal);
                    }
                });
                
                outputPathField.textProperty().addListener((obs, oldVal, newVal) -> {
                    okButton.setDisable(modalityComboBox.getValue() == null || newVal.trim().isEmpty());
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
        modalityComboBox.getItems().addAll(ModalityRegistry.getInstance().getRegisteredPrefixes());
        modalityComboBox.setPromptText("Select modality...");
        
        modalityPane.add(modalityLabel, 0, 0);
        modalityPane.add(modalityComboBox, 1, 0);
        
        // Output path selection
        Label outputLabel = new Label("Output folder:");
        outputPathField = new TextField();
        outputPathField.setPromptText("Select folder for background images...");
        outputPathField.setPrefWidth(300);
        
        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseForOutputFolder());
        
        HBox outputPane = new HBox(10, outputPathField, browseButton);
        outputPane.setAlignment(Pos.CENTER_LEFT);
        
        modalityPane.add(outputLabel, 0, 1);
        modalityPane.add(outputPane, 1, 1);
        
        // Set default output path
        setDefaultOutputPath();
        
        // Exposure controls (will be populated when modality is selected)
        Label exposureLabel = new Label("Exposure Times (ms):");
        exposureControlsPane = new VBox(10);
        
        content.getChildren().addAll(
                instructionLabel,
                new Separator(),
                modalityPane,
                new Separator(),
                exposureLabel,
                exposureControlsPane
        );
        
        return content;
    }
    
    private void updateExposureControls(String modality) {
        logger.info("Updating exposure controls for modality: {}", modality);
        
        exposureControlsPane.getChildren().clear();
        exposureFields.clear();
        currentAngleExposures.clear();
        
        // Get modality handler
        ModalityHandler handler = ModalityRegistry.getInstance().getHandler(modality);
        if (handler == null) {
            logger.warn("No handler found for modality: {}", modality);
            return;
        }
        
        // Get default angles and exposures
        List<AngleExposure> defaultExposures = handler.getAnglesAndExposures();
        currentAngleExposures.addAll(defaultExposures);
        
        // Create exposure controls
        GridPane exposureGrid = new GridPane();
        exposureGrid.setHgap(10);
        exposureGrid.setVgap(5);
        
        for (int i = 0; i < defaultExposures.size(); i++) {
            AngleExposure ae = defaultExposures.get(i);
            
            Label angleLabel = new Label(String.format("%.1f°:", ae.ticks()));
            TextField exposureField = new TextField(String.valueOf(ae.exposureMs()));
            exposureField.setPrefWidth(100);
            
            // Update current values when user changes exposure
            final int index = i;
            exposureField.textProperty().addListener((obs, oldVal, newVal) -> {
                try {
                    double newExposure = Double.parseDouble(newVal);
                    AngleExposure oldAe = currentAngleExposures.get(index);
                    currentAngleExposures.set(index, new AngleExposure(oldAe.ticks(), newExposure));
                } catch (NumberFormatException e) {
                    // Invalid input, ignore
                }
            });
            
            exposureGrid.add(angleLabel, 0, i);
            exposureGrid.add(exposureField, 1, i);
            exposureGrid.add(new Label("ms"), 2, i);
            
            exposureFields.add(exposureField);
        }
        
        exposureControlsPane.getChildren().add(exposureGrid);
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
            var configManager = MicroscopeConfigManager.getInstance();
            var config = configManager.getConfig();
            
            // Look for background_tiles folder path in config
            String defaultPath = configManager.lookupPath("background_correction.base_folder")
                    .map(Object::toString)
                    .orElse("C:/qpsc_data/background_tiles");
            
            outputPathField.setText(defaultPath);
            
        } catch (Exception e) {
            logger.warn("Could not determine default background path", e);
            outputPathField.setText("C:/qpsc_data/background_tiles");
        }
    }
    
    private BackgroundCollectionResult createResult() {
        try {
            String modality = modalityComboBox.getValue();
            String outputPath = outputPathField.getText().trim();
            
            if (modality == null || outputPath.isEmpty()) {
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
            
            return new BackgroundCollectionResult(modality, finalExposures, outputPath);
            
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
            
            var configManager = MicroscopeConfigManager.getInstance();
            
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