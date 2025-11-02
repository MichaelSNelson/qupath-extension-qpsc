package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
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

            // Initialize with last used sample name if no project is open
            if (!hasOpenProject) {
                String lastSampleName = PersistentPreferences.getLastSampleName();
                if (!lastSampleName.isEmpty()) {
                    sampleNameField.setText(lastSampleName);
                    logger.debug("Loaded last sample name: {}", lastSampleName);
                }
            }

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

            // --- Info label for existing project ---
            Label infoLabel = new Label();
            infoLabel.setStyle("-fx-text-fill: -fx-accent; -fx-font-size: 11px; -fx-font-style: italic;");
            infoLabel.setWrapText(true);
            infoLabel.setVisible(false);

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

            // Only show name/folder fields if no project is open
            if (!hasOpenProject) {
                grid.add(new Label(res.getString("sampleSetup.label.name")), 0, row);
                grid.add(sampleNameField, 1, row);
                row++;

                grid.add(new Label(res.getString("sampleSetup.label.projectsFolder")), 0, row);
                grid.add(folderBox, 1, row);
                row++;

                // Add info about what will happen
                infoLabel.setText("A new project will be created in: " +
                        folderField.getText() + File.separator + "[Sample Name]");
                infoLabel.setVisible(true);
                grid.add(infoLabel, 0, row, 2, 1);
                row++;
            } else {
                // Show existing project info as non-editable
                grid.add(new Label("Project:"), 0, row);
                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(projectLabel, 1, row);
                row++;

                grid.add(new Label("Location:"), 0, row);
                Label locationLabel = new Label(existingProjectFolder.getParent());
                locationLabel.setStyle("-fx-font-size: 11px;");
                grid.add(locationLabel, 1, row);
                row++;

                // Pre-fill hidden fields with existing values
                sampleNameField.setText(existingProjectName);
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

            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().setPrefWidth(600);

            // Update info label when sample name changes
            if (!hasOpenProject) {
                sampleNameField.textProperty().addListener((obs, old, newVal) -> {
                    if (!newVal.trim().isEmpty()) {
                        infoLabel.setText("A new project will be created in: " +
                                folderField.getText() + File.separator + newVal.trim());
                    }
                });

                folderField.textProperty().addListener((obs, old, newVal) -> {
                    if (!sampleNameField.getText().trim().isEmpty()) {
                        infoLabel.setText("A new project will be created in: " +
                                newVal + File.separator + sampleNameField.getText().trim());
                    }
                });
            }

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

                if (!hasOpenProject && name.isEmpty()) {
                    errors.append("• Sample name cannot be empty\n");
                }

                if (!hasOpenProject && !name.isEmpty() && !name.matches("[a-zA-Z0-9_\\-]+")) {
                    errors.append("• Sample name should only contain letters, numbers, _ and -\n");
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
                    if (!hasOpenProject && name.isEmpty()) {
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

                    // Save to persistent preferences for next time
                    if (!hasOpenProject) {
                        PersistentPreferences.setLastSampleName(name);

                        //TODO maybe set QPPPreferenceDialog to new folder if that changed?
                    }
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

                    // Save to persistent preferences for next time
                    if (!hasOpenProject) {
                        PersistentPreferences.setLastSampleName(name);
                        // DO NOT save projects folder - it comes from QPPreferenceDialog
                    }
                    PersistentPreferences.setLastModality(mod);
                    PersistentPreferences.setLastObjective(obj);
                    PersistentPreferences.setLastDetector(det);

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
}