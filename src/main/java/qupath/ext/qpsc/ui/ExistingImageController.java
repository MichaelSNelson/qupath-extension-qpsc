package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;

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
            String sampleName,
            double macroPixelSize,
            String groovyScriptPath,
            boolean nonIsotropicPixels,
            boolean useAutoRegistration
    ) {}

    /**
     * Shows the dialog to collect details needed for the existing image workflow.
     *
     * @param currentImageName The name of the currently open image (used to default sample name)
     * @return a CompletableFuture with user input results or cancellation
     */
    public static CompletableFuture<UserInput> showDialog(String currentImageName) {
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

            // Sample name field - default to image name without extension
            // Use QuPath's GeneralTools which handles multi-part extensions (.ome.tif, .ome.zarr, etc.)
            TextField sampleNameField = new TextField();
            String baseName = GeneralTools.stripExtension(currentImageName != null ? currentImageName : "");
            sampleNameField.setText(baseName);
            sampleNameField.setPromptText("Enter sample name...");

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

            int row = 0;
            grid.add(new Label("Sample name:"), 0, row);
            grid.add(sampleNameField, 1, row);
            row++;

            grid.add(new Label(""), 0, row);  // Empty for alignment
            grid.add(sampleNameErrorLabel, 1, row);
            row++;

            grid.add(new Label(res.getString("existingImage.label.pixelSize")), 0, row);
            grid.add(pixelSizeField, 1, row);
            row++;

            grid.add(nonIsotropicCheckBox, 1, row);
            row++;

            grid.add(autoRegistrationCheckBox, 1, row);

            dlg.getDialogPane().setContent(grid);

            dlg.setResultConverter(btn -> {
                if (btn == okType) {
                    try {
                        String sampleName = sampleNameField.getText().trim();

                        // Validate sample name
                        String sampleError = SampleNameValidator.getValidationError(sampleName);
                        if (sampleError != null) {
                            Dialogs.showErrorNotification("Invalid Sample Name", sampleError);
                            return null;
                        }

                        double pixelSize = Double.parseDouble(pixelSizeField.getText().trim());
                        boolean pixelsNonIsotropic = nonIsotropicCheckBox.isSelected();
                        boolean useAutoRegistration = autoRegistrationCheckBox.isSelected();

                        PersistentPreferences.setMacroImagePixelSizeInMicrons(String.valueOf(pixelSize));
                        String scriptPath = PersistentPreferences.getAnalysisScriptForAutomation();

                        // Return UserInput with sample name
                        return new UserInput(sampleName, pixelSize, scriptPath, pixelsNonIsotropic, useAutoRegistration);
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
