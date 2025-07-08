package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.fx.dialogs.Dialogs;

import java.util.ResourceBundle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * ExistingImageController
 *
 * <p>Handles the GUI for existing image workflows, collecting necessary parameters
 * such as pixel size and isotropy, and facilitates dialogs for annotation confirmation.
 */
public class ExistingImageController {

    /**
     * Data structure to hold user inputs from the existing image dialog.
     */
    public record UserInput(
            double macroPixelSize,
            String groovyScriptPath,
            boolean nonIsotropicPixels,
            boolean useAutoRegistration
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
            dlg.initModality(Modality.NONE);
            dlg.setTitle(res.getString("existingImage.title"));
            dlg.setHeaderText(res.getString("existingImage.header"));

            ButtonType okType = new ButtonType(res.getString("existingImage.button.ok"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(res.getString("existingImage.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            TextField pixelSizeField = new TextField();
            pixelSizeField.setText(PersistentPreferences.getMacroImagePixelSizeInMicrons());

            CheckBox nonIsotropicCheckBox = new CheckBox(res.getString("existingImage.label.nonIsotropicPixels"));

            // Add auto-registration checkbox
            CheckBox autoRegistrationCheckBox = new CheckBox("Use automatic tissue detection (if macro image available)");
            autoRegistrationCheckBox.setSelected(true); // Default to enabled
            autoRegistrationCheckBox.setTooltip(new Tooltip(
                    "When enabled, the system will attempt to automatically detect tissue regions\n" +
                            "using the macro image and saved microscope alignment transforms."
            ));

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));
            grid.add(new Label(res.getString("existingImage.label.pixelSize")), 0, 0);
            grid.add(pixelSizeField, 1, 0);
            grid.add(nonIsotropicCheckBox, 1, 1);
            grid.add(autoRegistrationCheckBox, 1, 2);

            dlg.getDialogPane().setContent(grid);

            dlg.setResultConverter(btn -> {
                if (btn == okType) {
                    try {
                        double pixelSize = Double.parseDouble(pixelSizeField.getText().trim());
                        boolean pixelsNonIsotropic = nonIsotropicCheckBox.isSelected();
                        boolean useAutoRegistration = autoRegistrationCheckBox.isSelected();

                        PersistentPreferences.setMacroImagePixelSizeInMicrons(String.valueOf(pixelSize));
                        String scriptPath = PersistentPreferences.getAnalysisScriptForAutomation();

                        // Modified UserInput to include auto-registration flag
                        return new UserInput(pixelSize, scriptPath, pixelsNonIsotropic, useAutoRegistration);
                    } catch (Exception e) {
                        Dialogs.showErrorNotification(res.getString("existingImage.error.title"), e.getMessage());
                        return null;
                    }
                }
                return null;
            });

            dlg.showAndWait().ifPresentOrElse(
                    result -> future.complete(result),
                    () -> future.completeExceptionally(new CancellationException("User closed dialog"))
            );
        });

        return future;
    }

    /**
     * Prompt the user for macro image pixel size if not available from metadata.
     */
    public static CompletableFuture<Double> requestPixelSizeDialog(double defaultValue) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            TextInputDialog dlg = new TextInputDialog(defaultValue > 0 ? String.valueOf(defaultValue) : "");
            dlg.setTitle("Enter Macro Image Pixel Size");
            dlg.setHeaderText("Image metadata did not provide a valid pixel size (µm).");
            dlg.setContentText("Pixel size (µm):");

            dlg.showAndWait().ifPresentOrElse(
                    value -> {
                        try {
                            double px = Double.parseDouble(value);
                            future.complete(px);
                        } catch (Exception e) {
                            future.completeExceptionally(new IllegalArgumentException("Invalid pixel size: " + value));
                        }
                    },
                    () -> future.completeExceptionally(new CancellationException("User cancelled pixel size entry"))
            );
        });
        return future;
    }


    /**
     * Prompts the user to verify or create annotations if tissue detection script is missing or fails.
     */
    public static void promptForAnnotations() {
        Platform.runLater(() -> {
            qupath.fx.dialogs.Dialogs.showInfoNotification(
                    "Annotations Required",
                    "No tissue detection script provided or failed.\n" +
                            "Please create or verify annotations for regions you wish to acquire."
            );
        });
    }
}
