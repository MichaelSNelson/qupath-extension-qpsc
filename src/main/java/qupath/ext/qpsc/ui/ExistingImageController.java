package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.fx.dialogs.Dialogs;

import java.io.File;
import java.nio.file.Paths;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

import static qupath.ext.qpsc.preferences.PersistentPreferences.getAnalysisScriptForAutomation;

/**
 * ExistingImageController
 *
 * <p>Handles the GUI for existing image workflows, collecting necessary parameters
 * such as sample name, pixel size, and isotropy, and integrates with ExistingImageWorkflow.
 */
public class ExistingImageController {

    /**
     * Data structure to hold user inputs from the existing image dialog.
     */

    public record UserInput(
            double macroPixelSize,
            String groovyScriptPath,
            boolean nonIsotropicPixels
    ) {}

    /**
     * Shows the dialog to collect details needed for the existing image workflow.
     *
     * @return a CompletableFuture with user input results or cancellation
     */
    public static CompletableFuture<UserInput> showDialog() {
        CompletableFuture<UserInput> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<UserInput> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("existingImage.title"));
            dlg.setHeaderText(res.getString("existingImage.header"));

            ButtonType okType = new ButtonType(res.getString("existingImage.button.ok"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(res.getString("existingImage.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            TextField pixelSizeField = new TextField();
            pixelSizeField.setText(PersistentPreferences.getMacroImagePixelSizeInMicrons());

            CheckBox nonIsotropicCheckBox = new CheckBox(res.getString("existingImage.label.nonIsotropicPixels"));

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));

            grid.add(new Label(res.getString("existingImage.label.pixelSize")), 0, 0);
            grid.add(pixelSizeField, 1, 0);
            grid.add(nonIsotropicCheckBox, 1, 1);

            dlg.getDialogPane().setContent(grid);

            dlg.setResultConverter(btn -> {
                if (btn == okType) {
                    try {
                        double pixelSize = Double.parseDouble(pixelSizeField.getText().trim());
                        boolean pixelsNonIsotropic = nonIsotropicCheckBox.isSelected();
                        PersistentPreferences.setMacroImagePixelSizeInMicrons(String.valueOf(pixelSize));

                        return new UserInput(pixelSize, PersistentPreferences.getAnalysisScriptForAutomation(), pixelsNonIsotropic);
                    } catch (Exception e) {
                        Dialogs.showErrorNotification(res.getString("existingImage.error.title"), e.getMessage());
                        return null;
                    }
                }
                return null;
            });

            dlg.showAndWait().ifPresentOrElse(future::complete, () -> future.cancel(true));
        });

        return future;
    }
}
