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

import java.io.File;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SampleSetupController {
    /** Holds the user’s last entries from the "sample setup " dialog. */
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
     * @return a CompletableFuture that completes with the user’s entries,
     *         or is cancelled if the user hits "Cancel. "
     */
    public static CompletableFuture<SampleSetupResult> showDialog() {
        CompletableFuture<SampleSetupResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<SampleSetupResult> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("sampleSetup.title"));
            dlg.setHeaderText(res.getString("sampleSetup.header"));

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
                chooser.setInitialDirectory(new File(folderField.getText()));
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
            modalityBox.setValue(modalities.iterator().next());

            // --- Layout ---
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));
            grid.add(new Label(res.getString("sampleSetup.label.name")),     0, 0);
            grid.add(sampleNameField,                                      1, 0);
            grid.add(new Label(res.getString("sampleSetup.label.projectsFolder")),   0, 1);
            grid.add(folderBox,                                            1, 1);
            grid.add(new Label(res.getString("sampleSetup.label.modality")), 0, 2);
            grid.add(modalityBox,                                          1, 2);

            dlg.getDialogPane().setContent(grid);

            dlg.setResultConverter(button -> {
                if (button == okType) {
                    String name   = sampleNameField.getText().trim();
                    File   folder = new File(folderField.getText().trim());
                    String mod    = modalityBox.getValue();
                    if (name.isEmpty() || !folder.isDirectory() || mod == null) {
                        new Alert(Alert.AlertType.ERROR,
                                res.getString("sampleSetup.error.invalidInput"))
                                .showAndWait();
                        return null; // keep it open
                    }
                    return new SampleSetupResult(name, folder, mod);
                }
                return null;
            });

            Optional<SampleSetupResult> resOpt = dlg.showAndWait();
            if (resOpt.isPresent()) {
                lastSampleSetup = resOpt.get();       // <— store it for later
                future.complete(lastSampleSetup);
            } else {
                future.cancel(true);
            }
        });

        return future;
    }

    /** Holds the user’s choices from the "sample setup " dialog. */
    public record SampleSetupResult(String sampleName, File projectsFolder, String modality) { }
}

