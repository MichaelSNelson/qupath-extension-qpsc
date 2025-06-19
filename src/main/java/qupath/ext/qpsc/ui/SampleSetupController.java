package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SampleSetupController {
    /** Holds the user's last entries from the "sample setup" dialog. */
    private static SampleSetupResult lastSampleSetup;

    /** Expose the most recently completed SampleSetupResult, or null if none yet. */
    public static SampleSetupResult getLastSampleSetup() {
        return lastSampleSetup;
    }

    /**
     * Show a dialog to collect:
     *  - Sample name (text)
     *  - Projects folder (directory chooser, default from prefs)
     *  - Modality (combo box, keys from microscope YAML imagingMode section)
     *
     * @return a CompletableFuture that completes with the user's entries,
     *         or is cancelled if the user hits "Cancel."
     */
    /**
     * Show a dialog to collect sample/project information.
     * If a project is already open, adapts to only ask for modality.
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

            ButtonType okType     = new ButtonType(res.getString("sampleSetup.button.ok"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(res.getString("sampleSetup.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // --- Fields ---
            TextField sampleNameField = new TextField();
            sampleNameField.setPromptText(res.getString("sampleSetup.prompt.sampleName"));

            TextField folderField = new TextField();
            folderField.setPrefColumnCount(30);
            folderField.setText(QPPreferenceDialog.getProjectsFolderProperty());

            Button browseBtn = new Button(res.getString("sampleSetup.button.browse"));
            browseBtn.setOnAction(e -> {
                Window win = dlg.getDialogPane().getScene().getWindow();
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(res.getString("sampleSetup.title.directorychooser"));
                File currentFolder = new File(folderField.getText());
                if (currentFolder.exists() && currentFolder.isDirectory()) {
                    chooser.setInitialDirectory(currentFolder);
                }
                File chosen = chooser.showDialog(win);
                if (chosen != null) folderField.setText(chosen.getAbsolutePath());
            });
            HBox folderBox = new HBox(5, folderField, browseBtn);

            Set<String> modalities = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getSection("imagingMode")
                    .keySet();
            ComboBox<String> modalityBox = new ComboBox<>(
                    FXCollections.observableArrayList(modalities)
            );
            if (!modalities.isEmpty()) {
                modalityBox.setValue(modalities.iterator().next());
            }

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

            int row = 0;

            // Only show name/folder fields if no project is open
            if (!hasOpenProject) {
                grid.add(new Label(res.getString("sampleSetup.label.name")), 0, row);
                grid.add(sampleNameField, 1, row);
                row++;

                grid.add(new Label(res.getString("sampleSetup.label.projectsFolder")), 0, row);
                grid.add(folderBox, 1, row);
                row++;
            } else {
                // Show existing project info as non-editable
                grid.add(new Label("Project:"), 0, row);
                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(projectLabel, 1, row);
                row++;

                // Pre-fill hidden fields with existing values
                sampleNameField.setText(existingProjectName);
                folderField.setText(existingProjectFolder.getParent());
            }

            grid.add(new Label(res.getString("sampleSetup.label.modality")), 0, row);
            grid.add(modalityBox, 1, row);
            row++;

            grid.add(errorLabel, 0, row, 2, 1);
            dlg.getDialogPane().setContent(grid);

            // Prevent dialog from closing on OK if validation fails
            Button okButton = (Button) dlg.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                // Validate inputs
                String name   = sampleNameField.getText().trim();
                File   folder = new File(folderField.getText().trim());
                String mod    = modalityBox.getValue();

                // Build validation error message
                StringBuilder errors = new StringBuilder();

                if (!hasOpenProject && name.isEmpty()) {
                    errors.append("• Sample name cannot be empty\n");
                }
                if (!hasOpenProject && (!folder.exists() || !folder.isDirectory())) {

                    errors.append("• Projects folder must be a valid directory\n");
                }
                if (mod == null || mod.isEmpty()) {
                    errors.append("• Please select a modality\n");
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
                    } else {
                        modalityBox.requestFocus();
                    }
                } else {
                    // Valid input - hide error label
                    errorLabel.setVisible(false);
                }
            });

            dlg.setResultConverter(button -> {
                if (button == okType) {
                    String name   = sampleNameField.getText().trim();
                    File   folder = new File(folderField.getText().trim());
                    String mod    = modalityBox.getValue();

                    return new SampleSetupResult(name, folder, mod);
                }
                return null;
            });

            // Set initial focus
            Platform.runLater(() -> {
                if (hasOpenProject) {
                    modalityBox.requestFocus();
                } else {
                    sampleNameField.requestFocus();
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

    /** Holds the user's choices from the "sample setup" dialog. */
    public record SampleSetupResult(String sampleName, File projectsFolder, String modality) { }
}