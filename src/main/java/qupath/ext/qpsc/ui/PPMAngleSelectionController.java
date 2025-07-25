package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for PPM tick (2 * angle) selection dialog.
 * Allows users to select which polarization angles to acquire.
 */
public class PPMAngleSelectionController {

    private static final Logger logger = LoggerFactory.getLogger(PPMAngleSelectionController.class);
    /**
     * Shows a dialog for selecting PPM acquisition angles in ticks.
     * Allows users to choose which polarization angles to acquire from the configured range.
     *
     * @param plusAngle The positive rotation angle from config (in ticks)
     * @param minusAngle The negative rotation angle from config (in ticks)
     * @return CompletableFuture with list of selected angles in ticks, or cancelled if user cancels
     */
    public static CompletableFuture<List<Double>> showDialog(double plusAngle, double minusAngle) {
        CompletableFuture<List<Double>> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<List<Double>> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles (in tick marks) for acquisition:");

            // Create checkboxes for each angle option with saved preferences
            CheckBox minusCheck = new CheckBox(String.format("%.1f 'degrees'", minusAngle));
            CheckBox zeroCheck = new CheckBox("0 'degrees'");
            CheckBox plusCheck = new CheckBox(String.format("%.1f 'degrees'", plusAngle));

            // Load saved selections
            minusCheck.setSelected(PersistentPreferences.getPPMMinusSelected());
            zeroCheck.setSelected(PersistentPreferences.getPPMZeroSelected());
            plusCheck.setSelected(PersistentPreferences.getPPMPlusSelected());

            logger.info("PPM tick dialog initialized with saved preferences: minus={}, zero={}, plus={}",
                    minusCheck.isSelected(), zeroCheck.isSelected(), plusCheck.isSelected());

            // Save preferences when changed
            minusCheck.selectedProperty().addListener((obs, old, selected) -> {
                PersistentPreferences.setPPMMinusSelected(selected);
                logger.debug("PPM minus tick selection updated to: {}", selected);
            });

            zeroCheck.selectedProperty().addListener((obs, old, selected) -> {
                PersistentPreferences.setPPMZeroSelected(selected);
                logger.debug("PPM zero tick selection updated to: {}", selected);
            });

            plusCheck.selectedProperty().addListener((obs, old, selected) -> {
                PersistentPreferences.setPPMPlusSelected(selected);
                logger.debug("PPM plus tick selection updated to: {}", selected);
            });

            // Info label
            Label infoLabel = new Label("Each selected angle will be acquired as a separate image:");

            // Quick select buttons
            Button selectAllBtn = new Button("Select All");
            Button selectNoneBtn = new Button("Select None");

            selectAllBtn.setOnAction(e -> {
                minusCheck.setSelected(true);
                zeroCheck.setSelected(true);
                plusCheck.setSelected(true);
            });

            selectNoneBtn.setOnAction(e -> {
                minusCheck.setSelected(false);
                zeroCheck.setSelected(false);
                plusCheck.setSelected(false);
            });

            HBox quickButtons = new HBox(10, selectAllBtn, selectNoneBtn);
            quickButtons.setAlignment(Pos.CENTER);

            VBox content = new VBox(10);
            content.setPadding(new Insets(20));
            content.getChildren().addAll(
                    infoLabel,
                    minusCheck,
                    zeroCheck,
                    plusCheck,
                    new Separator(),
                    quickButtons
            );

            dialog.getDialogPane().setContent(content);

            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

            // Disable OK if no angles selected
            Node okNode = dialog.getDialogPane().lookupButton(okButton);
            okNode.disableProperty().bind(
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

                    logger.info("PPM angles selected: {}", angles);
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
    }}