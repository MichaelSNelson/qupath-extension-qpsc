package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for PPM angle selection dialog.
 * Allows users to select which polarization angles to acquire.
 */
public class PPMAngleSelectionController {

    /**
     * Shows a dialog for selecting PPM acquisition angles.
     * Allows users to choose which polarization angles to acquire from the configured range.
     *
     * @param plusAngle The positive rotation angle from config (in degrees)
     * @param minusAngle The negative rotation angle from config (in degrees)
     * @return CompletableFuture with list of selected angles in degrees, or cancelled if user cancels
     */
    public static CompletableFuture<List<Double>> showDialog(double plusAngle, double minusAngle) {
        CompletableFuture<List<Double>> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<List<Double>> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles for acquisition:");

            // Create checkboxes for each angle option
            CheckBox minusCheck = new CheckBox(String.format("%.1f degrees", minusAngle));
            CheckBox zeroCheck = new CheckBox("0 degrees");
            CheckBox plusCheck = new CheckBox(String.format("%.1f degrees", plusAngle));

            // Default selection - all angles
            minusCheck.setSelected(true);
            zeroCheck.setSelected(true);
            plusCheck.setSelected(true);

            VBox content = new VBox(10);
            content.setPadding(new Insets(20));
            content.getChildren().addAll(
                    new Label("Each selected angle will be acquired as a separate image:"),
                    minusCheck,
                    zeroCheck,
                    plusCheck
            );

            dialog.getDialogPane().setContent(content);

            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

            // Disable OK if no angles selected
            dialog.getDialogPane().lookupButton(okButton).disableProperty().bind(
                    minusCheck.selectedProperty()
                            .or(zeroCheck.selectedProperty())
                            .or(plusCheck.selectedProperty())
                            .not()
            );

            dialog.setResultConverter(button -> {
                if (button == okButton) {
                    List<Double> angles = new ArrayList<>();
                    if (minusCheck.isSelected()) angles.add(minusAngle);
                    if (zeroCheck.isSelected()) angles.add(0.0);
                    if (plusCheck.isSelected()) angles.add(plusAngle);
                    return angles;
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(
                    angles -> future.complete(angles),
                    () -> future.cancel(true)
            );
        });

        return future;
    }
}