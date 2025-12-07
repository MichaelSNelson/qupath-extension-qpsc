package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for sample setup dialog that collects project information.
 * Supports both new project creation and adding to existing projects.
 */
public class SampleSetupController {
    private static final Logger logger = LoggerFactory.getLogger(SampleSetupController.class);

    /** Holds the user's last entries from the "sample setup" dialog. */
    private static SampleSetupResult lastSampleSetup;

    /** Expose the most recently completed SampleSetupResult, or null if none yet. */
    public static SampleSetupResult getLastSampleSetup() {
        return lastSampleSetup;
    }

    /**
     * Show a dialog to collect sample/project information.
     * If a project is already open, adapts to only ask for modality.
     * All fields are populated with last used values from persistent preferences.
     *
     * @return a CompletableFuture that completes with the user's entries,
     *         or is cancelled if the user hits "Cancel."
     */
    public static CompletableFuture<SampleSetupResult> showDialog() {
        return showDialog(null);  // No default sample name
    }

    /**
     * Show a dialog to collect sample/project information with an optional default sample name.
     * If a project is already open, adapts to only ask for modality.
     *
     * @param defaultSampleName Optional default value for sample name field (e.g., current image name without extension)
     * @return a CompletableFuture that completes with the user's entries,
     *         or is cancelled if the user hits "Cancel."
     */
    public static CompletableFuture<SampleSetupResult> showDialog(String defaultSampleName) {
        CompletableFuture<SampleSetupResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            // Check if a project is already open
            QuPathGUI gui = QuPathGUI.getInstance();
            boolean hasOpenProject = (gui != null && gui.getProject() != null);
            String existingProjectName = null;
            File existingProjectFolder = null;

            if (hasOpenProject) {
                // Extract project name and folder from open project
                File projectFile = gui.getProject().getPath().toFile();
                existingProjectFolder = projectFile.getParentFile();
                existingProjectName = existingProjectFolder.getName();
                logger.info("Found open project: {} in {}", existingProjectName,
                        existingProjectFolder.getParent());
            }

            Dialog<SampleSetupResult> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("sampleSetup.title"));

            // Adapt header based on whether project exists
            if (hasOpenProject) {
                dlg.setHeaderText("Adding to existing project: " + existingProjectName +
                        "\nPlease select the imaging modality:");
            } else {
                dlg.setHeaderText(res.getString("sampleSetup.header"));
            }

            ButtonType okType = new ButtonType(res.getString("sampleSetup.button.ok"),
                    ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(res.getString("sampleSetup.button.cancel"),
                    ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // --- Fields ---
            TextField sampleNameField = new TextField();
            sampleNameField.setPromptText(res.getString("sampleSetup.prompt.sampleName"));

            // Initialize sample name: use default if provided, otherwise use last used
            if (defaultSampleName != null && !defaultSampleName.trim().isEmpty()) {
                sampleNameField.setText(defaultSampleName.trim());
                logger.debug("Using provided default sample name: {}", defaultSampleName);
            } else {
                String lastSampleName = PersistentPreferences.getLastSampleName();
                if (!lastSampleName.isEmpty()) {
                    sampleNameField.setText(lastSampleName);
                    logger.debug("Loaded last sample name: {}", lastSampleName);
                }
            }

            // Add real-time validation feedback for sample name
            Label sampleNameErrorLabel = new Label();
            sampleNameErrorLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 10px;");
            sampleNameErrorLabel.setVisible(false);
            sampleNameField.textProperty().addListener((obs, oldVal, newVal) -> {
                String error = SampleNameValidator.getValidationError(newVal);
                if (error != null) {
                    sampleNameErrorLabel.setText(error);
                    sampleNameErrorLabel.setVisible(true);
                } else {
                    sampleNameErrorLabel.setVisible(false);
                }
            });

            TextField folderField = new TextField();
            folderField.setPrefColumnCount(20);

            String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
            folderField.setText(projectsFolder);
            logger.debug("Loaded projects folder from QPPreferenceDialog: {}", projectsFolder);


            Button browseBtn = new Button(res.getString("sampleSetup.button.browse"));
            browseBtn.setOnAction(e -> {
                Window win = dlg.getDialogPane().getScene().getWindow();
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(res.getString("sampleSetup.title.directorychooser"));

                File currentFolder = new File(folderField.getText());
                if (currentFolder.exists() && currentFolder.isDirectory()) {
                    chooser.setInitialDirectory(currentFolder);
                } else {
                    // Try parent directory
                    File parent = currentFolder.getParentFile();
                    if (parent != null && parent.exists() && parent.isDirectory()) {
                        chooser.setInitialDirectory(parent);
                    }
                }

                File chosen = chooser.showDialog(win);
                if (chosen != null) {
                    folderField.setText(chosen.getAbsolutePath());
                    logger.debug("User selected projects folder: {}", chosen.getAbsolutePath());
                }
            });

            HBox folderBox = new HBox(5, folderField, browseBtn);
            HBox.setHgrow(folderField, Priority.ALWAYS);

            // Get config manager instance
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            // Load modalities from config
            Set<String> modalities = configManager.getSection("modalities").keySet();

            ComboBox<String> modalityBox = new ComboBox<>(
                    FXCollections.observableArrayList(modalities)
            );

            // Create objective and detector dropdowns (initially empty)
            ComboBox<String> objectiveBox = new ComboBox<>();
            ComboBox<String> detectorBox = new ComboBox<>();

            // Set last used modality if available
            String lastModality = PersistentPreferences.getLastModality();
            if (!lastModality.isEmpty() && modalities.contains(lastModality)) {
                modalityBox.setValue(lastModality);
                logger.debug("Set modality to last used: {}", lastModality);
            } else if (!modalities.isEmpty()) {
                // Default to first if no saved preference or saved one not in list
                modalityBox.setValue(modalities.iterator().next());
            }

            // Update objectives when modality changes
            modalityBox.valueProperty().addListener((obs, oldModality, newModality) -> {
                if (newModality != null) {
                    logger.debug("Modality changed to: {}", newModality);

                    // Get available objectives for this modality
                    Set<String> objectiveIds = configManager.getAvailableObjectivesForModality(newModality);
                    Map<String, String> objectiveNames = configManager.getObjectiveFriendlyNames(objectiveIds);

                    // Create display strings that combine friendly name with ID for clarity
                    List<String> objectiveDisplayItems = objectiveIds.stream()
                            .map(id -> {
                                String name = objectiveNames.get(id);
                                return name + " (" + id + ")";
                            })
                            .sorted()
                            .collect(Collectors.toList());

                    objectiveBox.getItems().clear();
                    objectiveBox.getItems().addAll(objectiveDisplayItems);

                    // Select first objective if available
                    // This will trigger the objective listener which populates detectors
                    if (!objectiveDisplayItems.isEmpty()) {
                        objectiveBox.setValue(objectiveDisplayItems.get(0));
                    }
                    // Note: No need to clear detectors here - the objective listener handles detector population
                }
            });

            // Update detectors when objective changes
            objectiveBox.valueProperty().addListener((obs, oldObjective, newObjective) -> {
                if (newObjective != null && modalityBox.getValue() != null) {
                    // Extract objective ID from display string
                    String objectiveId = extractIdFromDisplayString(newObjective);
                    logger.debug("Objective changed to: {} ({})", newObjective, objectiveId);

                    // Get available detectors for this modality+objective combo
                    Set<String> detectorIds = configManager.getAvailableDetectorsForModalityObjective(
                            modalityBox.getValue(), objectiveId);
                    Map<String, String> detectorNames = configManager.getDetectorFriendlyNames(detectorIds);

                    // Create display strings
                    List<String> detectorDisplayItems = detectorIds.stream()
                            .map(id -> {
                                String name = detectorNames.get(id);
                                return name + " (" + id + ")";
                            })
                            .sorted()
                            .collect(Collectors.toList());

                    detectorBox.getItems().clear();
                    detectorBox.getItems().addAll(detectorDisplayItems);

                    // Try to restore last used detector
                    String lastDetector = PersistentPreferences.getLastDetector();
                    boolean detectorRestored = false;
                    if (!lastDetector.isEmpty()) {
                        // Try to find matching detector by ID
                        for (String displayItem : detectorDisplayItems) {
                            String id = extractIdFromDisplayString(displayItem);
                            if (id.equals(lastDetector)) {
                                detectorBox.setValue(displayItem);
                                detectorRestored = true;
                                logger.debug("Restored last detector: {}", lastDetector);
                                break;
                            }
                        }
                    }

                    // Select first detector if no saved preference or saved one not found
                    if (!detectorRestored && !detectorDisplayItems.isEmpty()) {
                        detectorBox.setValue(detectorDisplayItems.get(0));
                    }
                }
            });

            // Trigger initial population of objectives
            Platform.runLater(() -> {
                if (modalityBox.getValue() != null) {
                    // Manually trigger the change listener
                    String initialModality = modalityBox.getValue();
                    logger.debug("Triggering initial population for modality: {}", initialModality);

                    // Get available objectives for this modality
                    Set<String> objectiveIds = configManager.getAvailableObjectivesForModality(initialModality);
                    Map<String, String> objectiveNames = configManager.getObjectiveFriendlyNames(objectiveIds);

                    logger.debug("Initial objectives found: {}", objectiveIds);

                    // Create display strings that combine friendly name with ID for clarity
                    List<String> objectiveDisplayItems = objectiveIds.stream()
                            .map(id -> {
                                String name = objectiveNames.get(id);
                                return name + " (" + id + ")";
                            })
                            .sorted()
                            .collect(Collectors.toList());

                    objectiveBox.getItems().clear();
                    objectiveBox.getItems().addAll(objectiveDisplayItems);

                    // Try to restore last used objective
                    String lastObjective = PersistentPreferences.getLastObjective();
                    boolean objectiveRestored = false;
                    if (!lastObjective.isEmpty()) {
                        // Try to find matching objective by ID
                        for (String displayItem : objectiveDisplayItems) {
                            String id = extractIdFromDisplayString(displayItem);
                            if (id.equals(lastObjective)) {
                                objectiveBox.setValue(displayItem);
                                objectiveRestored = true;
                                logger.debug("Restored last objective: {}", lastObjective);
                                break;
                            }
                        }
                    }

                    // Select first objective if no saved preference or saved one not found
                    if (!objectiveRestored && !objectiveDisplayItems.isEmpty()) {
                        objectiveBox.setValue(objectiveDisplayItems.get(0));
                    }
                }
            });

            // --- Error label for validation messages ---
            Label errorLabel = new Label();
            errorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 12px;");
            errorLabel.setWrapText(true);
            errorLabel.setVisible(false);


            // --- Layout ---
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));
            ColumnConstraints col0 = new ColumnConstraints();
            col0.setMinWidth(120); // Ensure labels have minimum width
            ColumnConstraints col1 = new ColumnConstraints();
            col1.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(col0, col1);
            int row = 0;

            // ALWAYS show sample name field (editable)
            grid.add(new Label(res.getString("sampleSetup.label.name")), 0, row);
            grid.add(sampleNameField, 1, row);
            row++;

            // Add sample name validation error label
            grid.add(new Label(""), 0, row);  // Empty label for alignment
            grid.add(sampleNameErrorLabel, 1, row);
            row++;

            // Show different context based on project state
            // Note: Variant banner at top provides context (new vs existing project)
            if (!hasOpenProject) {
                grid.add(new Label(res.getString("sampleSetup.label.projectsFolder")), 0, row);
                grid.add(folderBox, 1, row);
                row++;
            } else {
                // Pre-fill hidden folder field with existing value for result
                folderField.setText(existingProjectFolder.getParent());
            }

            grid.add(new Label(res.getString("sampleSetup.label.modality")), 0, row);
            grid.add(modalityBox, 1, row);
            row++;

            grid.add(new Label("Objective:"), 0, row);
            grid.add(objectiveBox, 1, row);
            row++;

            grid.add(new Label("Detector:"), 0, row);
            grid.add(detectorBox, 1, row);
            row++;

            grid.add(errorLabel, 0, row, 2, 1);

            // Create variant detection banner
            HBox variantBanner = createVariantBanner(hasOpenProject, existingProjectName, folderField, sampleNameField);

            // Wrap banner and grid in a VBox
            VBox dialogContent = new VBox(0);
            dialogContent.getChildren().addAll(variantBanner, grid);

            dlg.getDialogPane().setContent(dialogContent);
            dlg.getDialogPane().setPrefWidth(600);

            // Note: Banner dynamically updates path display via listeners in createVariantBanner()

            // Prevent dialog from closing on OK if validation fails
            Button okButton = (Button) dlg.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                // Validate inputs
                String name = sampleNameField.getText().trim();
                File folder = new File(folderField.getText().trim());
                String mod = modalityBox.getValue();
                String obj = extractIdFromDisplayString(objectiveBox.getValue());
                String det = extractIdFromDisplayString(detectorBox.getValue());

                // Build validation error message
                StringBuilder errors = new StringBuilder();

                // ALWAYS validate sample name (required for all workflows)
                String sampleNameError = SampleNameValidator.getValidationError(name);
                if (sampleNameError != null) {
                    errors.append("• ").append(sampleNameError).append("\n");
                }

                if (!hasOpenProject && (!folder.exists() || !folder.isDirectory())) {
                    errors.append("• Projects folder must be a valid directory\n");
                }

                if (mod == null || mod.isEmpty()) {
                    errors.append("• Please select a modality\n");
                }

                if (obj == null || obj.isEmpty()) {
                    errors.append("• Please select an objective\n");
                }

                if (det == null || det.isEmpty()) {
                    errors.append("• Please select a detector\n");
                }

                if (errors.length() > 0) {
                    // Show error and consume event to prevent dialog closing
                    errorLabel.setText(errors.toString().trim());
                    errorLabel.setVisible(true);
                    event.consume();

                    // Focus the first problematic field
                    if (sampleNameError != null) {
                        sampleNameField.requestFocus();
                    } else if (!hasOpenProject && (!folder.exists() || !folder.isDirectory())) {
                        folderField.requestFocus();
                    } else if (mod == null || mod.isEmpty()) {
                        modalityBox.requestFocus();
                    } else if (obj == null || obj.isEmpty()) {
                        objectiveBox.requestFocus();
                    } else {
                        detectorBox.requestFocus();
                    }
                } else {
                    // Valid input - hide error label and save preferences
                    errorLabel.setVisible(false);

                    // ALWAYS save sample name preference (for all workflows)
                    PersistentPreferences.setLastSampleName(name);
                    PersistentPreferences.setLastModality(mod);
                    PersistentPreferences.setLastObjective(obj);
                    PersistentPreferences.setLastDetector(det);

                    logger.info("Saved sample setup preferences - name: {}, folder: {}, modality: {}, objective: {}, detector: {}",
                            name, folder.getAbsolutePath(), mod, obj, det);
                }
            });

            dlg.setResultConverter(button -> {
                if (button == okType) {
                    String name = sampleNameField.getText().trim();
                    File folder = new File(folderField.getText().trim());
                    String mod = modalityBox.getValue();
                    String obj = extractIdFromDisplayString(objectiveBox.getValue());
                    String det = extractIdFromDisplayString(detectorBox.getValue());

                    // ALWAYS save preferences (for all workflows)
                    PersistentPreferences.setLastSampleName(name);
                    PersistentPreferences.setLastModality(mod);
                    PersistentPreferences.setLastObjective(obj);
                    PersistentPreferences.setLastDetector(det);
                    // DO NOT save projects folder - it comes from QPPreferenceDialog

                    logger.info("Saved sample setup preferences - name: {}, modality: {}, objective: {}, detector: {}",
                            name, mod, obj, det);

                    return new SampleSetupResult(name, folder, mod, obj, det);
                }
                return null;
            });

            // Set initial focus
            Platform.runLater(() -> {
                if (hasOpenProject) {
                    modalityBox.requestFocus();
                } else {
                    if (sampleNameField.getText().isEmpty()) {
                        sampleNameField.requestFocus();
                    } else {
                        modalityBox.requestFocus();
                    }
                }
            });

            Optional<SampleSetupResult> resOpt = dlg.showAndWait();
            if (resOpt.isPresent()) {
                lastSampleSetup = resOpt.get();
                future.complete(lastSampleSetup);
            } else {
                future.cancel(true);
            }
        });

        return future;
    }

    /**
     * Helper method to extract the ID from display strings like "20x Olympus (LOCI_OBJECTIVE_OLYMPUS_20X_POL_001)"
     * Returns the ID part in parentheses, or the original string if no parentheses found.
     */
    private static String extractIdFromDisplayString(String displayString) {
        if (displayString == null) return null;
        
        int openParen = displayString.lastIndexOf('(');
        int closeParen = displayString.lastIndexOf(')');
        
        if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
            return displayString.substring(openParen + 1, closeParen);
        }
        
        return displayString; // fallback to original string
    }

    /** Holds the user's choices from the "sample setup" dialog. */
    public record SampleSetupResult(String sampleName, File projectsFolder, String modality, String objective, String detector) { }

    // ==================== Variant Detection Banner ====================

    /**
     * Banner background color for creating a new project (blue tint).
     */
    private static final String BANNER_COLOR_NEW_PROJECT = "-fx-background-color: #E3F2FD; -fx-border-color: #90CAF9; -fx-border-width: 0 0 1 0;";

    /**
     * Banner background color for adding to an existing project (green tint).
     */
    private static final String BANNER_COLOR_EXISTING_PROJECT = "-fx-background-color: #E8F5E9; -fx-border-color: #A5D6A7; -fx-border-width: 0 0 1 0;";

    /**
     * Creates a variant detection banner that shows context about whether
     * a new project is being created or images are being added to an existing project.
     *
     * @param hasOpenProject True if adding to existing project, false if creating new
     * @param existingProjectName Name of the existing project (if hasOpenProject is true)
     * @param projectsFolder Folder where new projects will be created (if hasOpenProject is false)
     * @param sampleNameField The sample name text field (for dynamic updates to new project path)
     * @return HBox containing the styled banner
     */
    private static HBox createVariantBanner(boolean hasOpenProject, String existingProjectName,
                                            TextField folderField, TextField sampleNameField) {
        HBox banner = new HBox(10);
        banner.setPadding(new Insets(12, 15, 12, 15));
        banner.setAlignment(Pos.CENTER_LEFT);

        // Icon label - use text symbols for cross-platform compatibility
        Label iconLabel = new Label();
        iconLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Primary text (bold)
        Label primaryText = new Label();
        primaryText.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // Secondary text (path/details)
        Label secondaryText = new Label();
        secondaryText.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        if (hasOpenProject) {
            // Adding to existing project - green banner
            banner.setStyle(BANNER_COLOR_EXISTING_PROJECT);
            iconLabel.setText("[+]");
            iconLabel.setStyle(iconLabel.getStyle() + " -fx-text-fill: #2E7D32;");
            primaryText.setText("Adding to existing project: " + existingProjectName);
            primaryText.setStyle(primaryText.getStyle() + " -fx-text-fill: #1B5E20;");
            secondaryText.setText("New sample will be added to this project");
            secondaryText.setStyle(secondaryText.getStyle() + " -fx-text-fill: #388E3C;");
        } else {
            // Creating new project - blue banner
            banner.setStyle(BANNER_COLOR_NEW_PROJECT);
            iconLabel.setText("[*]");
            iconLabel.setStyle(iconLabel.getStyle() + " -fx-text-fill: #1565C0;");
            primaryText.setText("Creating new project");
            primaryText.setStyle(primaryText.getStyle() + " -fx-text-fill: #0D47A1;");

            // Dynamic path display - will be updated as user types
            String initialPath = folderField.getText();
            String sampleName = sampleNameField.getText().trim();
            if (!sampleName.isEmpty()) {
                secondaryText.setText("Location: " + initialPath + File.separator + sampleName);
            } else {
                secondaryText.setText("Location: " + initialPath + File.separator + "[Sample Name]");
            }
            secondaryText.setStyle(secondaryText.getStyle() + " -fx-text-fill: #1976D2;");

            // Add listeners to update path dynamically
            sampleNameField.textProperty().addListener((obs, oldVal, newVal) -> {
                String path = folderField.getText();
                String name = newVal.trim();
                if (!name.isEmpty()) {
                    secondaryText.setText("Location: " + path + File.separator + name);
                } else {
                    secondaryText.setText("Location: " + path + File.separator + "[Sample Name]");
                }
            });

            folderField.textProperty().addListener((obs, oldVal, newVal) -> {
                String name = sampleNameField.getText().trim();
                if (!name.isEmpty()) {
                    secondaryText.setText("Location: " + newVal + File.separator + name);
                } else {
                    secondaryText.setText("Location: " + newVal + File.separator + "[Sample Name]");
                }
            });
        }

        // Layout: icon | primary text | secondary text (stacked vertically)
        VBox textBox = new VBox(2);
        textBox.getChildren().addAll(primaryText, secondaryText);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        banner.getChildren().addAll(iconLabel, textBox);

        return banner;
    }
}