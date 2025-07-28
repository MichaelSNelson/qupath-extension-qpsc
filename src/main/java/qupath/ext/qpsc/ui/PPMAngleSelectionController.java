package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.converter.IntegerStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/**
 * Controller for PPM tick (2 * angle) selection dialog.
 * Allows users to select which polarization angles to acquire and set exposure times.
 */
public class PPMAngleSelectionController {

    private static final Logger logger = LoggerFactory.getLogger(PPMAngleSelectionController.class);

    /**
     * Result containing selected angles with their exposure times
     */
    public static class AngleExposureResult {
        public final List<AngleExposure> angleExposures;

        public AngleExposureResult(List<AngleExposure> angleExposures) {
            this.angleExposures = angleExposures;
        }

        /**
         * Get list of angles only (for backward compatibility)
         */
        public List<Double> getAngles() {
            return angleExposures.stream()
                    .map(ae -> ae.angle)
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * Represents an angle with its exposure time
     */
    public static class AngleExposure {
        public final double angle;
        public final int exposureMs;

        public AngleExposure(double angle, int exposureMs) {
            this.angle = angle;
            this.exposureMs = exposureMs;
        }

        @Override
        public String toString() {
            return String.format("%.1f° @ %dms", angle, exposureMs);
        }
    }

    /**
     * Shows a dialog for selecting PPM acquisition angles in ticks with exposure times.
     * Allows users to choose which polarization angles to acquire from the configured range.
     *
     * @param plusAngle The positive rotation angle from config (in ticks)
     * @param minusAngle The negative rotation angle from config (in ticks)
     * @return CompletableFuture with AngleExposureResult, or cancelled if user cancels
     */
    public static CompletableFuture<AngleExposureResult> showDialog(double plusAngle, double minusAngle) {
        CompletableFuture<AngleExposureResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<AngleExposureResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles (in tick marks) and exposure times for acquisition:");

            // Create grid for angle selections and exposure fields
            GridPane angleGrid = new GridPane();
            angleGrid.setHgap(10);
            angleGrid.setVgap(10);
            angleGrid.setPadding(new Insets(10));

            // Create integer text formatter for exposure fields
            UnaryOperator<TextFormatter.Change> integerFilter = change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty()) {
                    return change;
                }
                try {
                    int value = Integer.parseInt(newText);
                    if (value > 0) {
                        return change;
                    }
                } catch (NumberFormatException e) {
                    // Not a valid integer
                }
                return null; // Reject the change
            };

            // Row 0: Headers
            angleGrid.add(new Label("Angle"), 0, 0);
            angleGrid.add(new Label("Exposure (ms)"), 2, 0);

            // Row 1: Minus angle
            CheckBox minusCheck = new CheckBox(String.format("%.1f 'degrees'", minusAngle));
            minusCheck.setSelected(PersistentPreferences.getPPMMinusSelected());

            TextField minusExposureField = new TextField(String.valueOf(PersistentPreferences.getPPMMinusExposureMs()));
            minusExposureField.setTextFormatter(new TextFormatter<>(integerFilter));
            minusExposureField.setPrefWidth(80);
            minusExposureField.setDisable(!minusCheck.isSelected());

            angleGrid.add(minusCheck, 0, 1);
            angleGrid.add(minusExposureField, 2, 1);

            // Row 2: Zero angle
            CheckBox zeroCheck = new CheckBox("0 'degrees'");
            zeroCheck.setSelected(PersistentPreferences.getPPMZeroSelected());

            TextField zeroExposureField = new TextField(String.valueOf(PersistentPreferences.getPPMZeroExposureMs()));
            zeroExposureField.setTextFormatter(new TextFormatter<>(integerFilter));
            zeroExposureField.setPrefWidth(80);
            zeroExposureField.setDisable(!zeroCheck.isSelected());

            angleGrid.add(zeroCheck, 0, 2);
            angleGrid.add(zeroExposureField, 2, 2);

            // Row 3: Plus angle
            CheckBox plusCheck = new CheckBox(String.format("%.1f 'degrees'", plusAngle));
            plusCheck.setSelected(PersistentPreferences.getPPMPlusSelected());

            TextField plusExposureField = new TextField(String.valueOf(PersistentPreferences.getPPMPlusExposureMs()));
            plusExposureField.setTextFormatter(new TextFormatter<>(integerFilter));
            plusExposureField.setPrefWidth(80);
            plusExposureField.setDisable(!plusCheck.isSelected());

            angleGrid.add(plusCheck, 0, 3);
            angleGrid.add(plusExposureField, 2, 3);

            // Row 4: Brightfield (90 degrees)
            CheckBox brightfieldCheck = new CheckBox("90 'degrees' (Brightfield)");
            brightfieldCheck.setSelected(PersistentPreferences.getPPMBrightfieldSelected());

            TextField brightfieldExposureField = new TextField(String.valueOf(PersistentPreferences.getPPMBrightfieldExposureMs()));
            brightfieldExposureField.setTextFormatter(new TextFormatter<>(integerFilter));
            brightfieldExposureField.setPrefWidth(80);
            brightfieldExposureField.setDisable(!brightfieldCheck.isSelected());

            angleGrid.add(brightfieldCheck, 0, 4);
            angleGrid.add(brightfieldExposureField, 2, 4);

            // Enable/disable exposure fields based on checkbox states
            minusCheck.selectedProperty().addListener((obs, old, selected) -> {
                minusExposureField.setDisable(!selected);
                if (!selected) minusExposureField.clear();
                PersistentPreferences.setPPMMinusSelected(selected);
                logger.debug("PPM minus tick selection updated to: {}", selected);
            });

            zeroCheck.selectedProperty().addListener((obs, old, selected) -> {
                zeroExposureField.setDisable(!selected);
                if (!selected) zeroExposureField.clear();
                PersistentPreferences.setPPMZeroSelected(selected);
                logger.debug("PPM zero tick selection updated to: {}", selected);
            });

            plusCheck.selectedProperty().addListener((obs, old, selected) -> {
                plusExposureField.setDisable(!selected);
                if (!selected) plusExposureField.clear();
                PersistentPreferences.setPPMPlusSelected(selected);
                logger.debug("PPM plus tick selection updated to: {}", selected);
            });

            brightfieldCheck.selectedProperty().addListener((obs, old, selected) -> {
                brightfieldExposureField.setDisable(!selected);
                if (!selected) brightfieldExposureField.clear();
                PersistentPreferences.setPPMBrightfieldSelected(selected);
                logger.debug("PPM brightfield selection updated to: {}", selected);
            });

            // Save exposure preferences when changed
            minusExposureField.textProperty().addListener((obs, old, newVal) -> {
                if (!newVal.isEmpty()) {
                    try {
                        int value = Integer.parseInt(newVal);
                        PersistentPreferences.setPPMMinusExposureMs(value);
                    } catch (NumberFormatException e) {
                        // Ignore - formatter should prevent this
                    }
                }
            });

            zeroExposureField.textProperty().addListener((obs, old, newVal) -> {
                if (!newVal.isEmpty()) {
                    try {
                        int value = Integer.parseInt(newVal);
                        PersistentPreferences.setPPMZeroExposureMs(value);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            });

            plusExposureField.textProperty().addListener((obs, old, newVal) -> {
                if (!newVal.isEmpty()) {
                    try {
                        int value = Integer.parseInt(newVal);
                        PersistentPreferences.setPPMPlusExposureMs(value);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            });

            brightfieldExposureField.textProperty().addListener((obs, old, newVal) -> {
                if (!newVal.isEmpty()) {
                    try {
                        int value = Integer.parseInt(newVal);
                        PersistentPreferences.setPPMBrightfieldExposureMs(value);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            });

            logger.info("PPM tick dialog initialized with saved preferences: minus={}, zero={}, plus={}, brightfield={}",
                    minusCheck.isSelected(), zeroCheck.isSelected(), plusCheck.isSelected(), brightfieldCheck.isSelected());

            // Info label
            Label infoLabel = new Label("Each selected angle will be acquired with the specified exposure time.");

            // Quick select buttons
            Button selectAllBtn = new Button("Select All");
            Button selectNoneBtn = new Button("Select None");

            selectAllBtn.setOnAction(e -> {
                minusCheck.setSelected(true);
                zeroCheck.setSelected(true);
                plusCheck.setSelected(true);
                brightfieldCheck.setSelected(true);
            });

            selectNoneBtn.setOnAction(e -> {
                minusCheck.setSelected(false);
                zeroCheck.setSelected(false);
                plusCheck.setSelected(false);
                brightfieldCheck.setSelected(false);
            });

            HBox quickButtons = new HBox(10, selectAllBtn, selectNoneBtn);
            quickButtons.setAlignment(Pos.CENTER);

            VBox content = new VBox(10);
            content.setPadding(new Insets(20));
            content.getChildren().addAll(
                    infoLabel,
                    angleGrid,
                    new Separator(),
                    quickButtons
            );

            dialog.getDialogPane().setContent(content);

            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

            // Create validation binding for OK button
            Node okNode = dialog.getDialogPane().lookupButton(okButton);

            // Validation: at least one angle selected AND all selected angles have valid exposure times
            BooleanBinding validationBinding = Bindings.createBooleanBinding(() -> {
                        boolean anySelected = minusCheck.isSelected() || zeroCheck.isSelected() ||
                                plusCheck.isSelected() || brightfieldCheck.isSelected();

                        if (!anySelected) return false;

                        // Check each selected angle has a valid exposure
                        if (minusCheck.isSelected() &&
                                (minusExposureField.getText().isEmpty() || !isValidPositiveInteger(minusExposureField.getText()))) {
                            return false;
                        }
                        if (zeroCheck.isSelected() &&
                                (zeroExposureField.getText().isEmpty() || !isValidPositiveInteger(zeroExposureField.getText()))) {
                            return false;
                        }
                        if (plusCheck.isSelected() &&
                                (plusExposureField.getText().isEmpty() || !isValidPositiveInteger(plusExposureField.getText()))) {
                            return false;
                        }
                        if (brightfieldCheck.isSelected() &&
                                (brightfieldExposureField.getText().isEmpty() || !isValidPositiveInteger(brightfieldExposureField.getText()))) {
                            return false;
                        }

                        return true;
                    },
                    minusCheck.selectedProperty(), zeroCheck.selectedProperty(),
                    plusCheck.selectedProperty(), brightfieldCheck.selectedProperty(),
                    minusExposureField.textProperty(), zeroExposureField.textProperty(),
                    plusExposureField.textProperty(), brightfieldExposureField.textProperty());

            okNode.disableProperty().bind(validationBinding.not());

            dialog.setResultConverter(button -> {
                if (button == okButton) {
                    List<AngleExposure> angleExposures = new ArrayList<>();

                    if (minusCheck.isSelected()) {
                        int exposure = Integer.parseInt(minusExposureField.getText());
                        angleExposures.add(new AngleExposure(minusAngle, exposure));
                    }
                    if (zeroCheck.isSelected()) {
                        int exposure = Integer.parseInt(zeroExposureField.getText());
                        angleExposures.add(new AngleExposure(0.0, exposure));
                    }
                    if (plusCheck.isSelected()) {
                        int exposure = Integer.parseInt(plusExposureField.getText());
                        angleExposures.add(new AngleExposure(plusAngle, exposure));
                    }
                    if (brightfieldCheck.isSelected()) {
                        int exposure = Integer.parseInt(brightfieldExposureField.getText());
                        angleExposures.add(new AngleExposure(90.0, exposure));
                    }

                    logger.info("PPM angles and exposures selected: {}", angleExposures);
                    return new AngleExposureResult(angleExposures);
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(
                    result -> future.complete(result),
                    () -> future.cancel(true)
            );
        });

        return future;
    }

    /**
     * Helper method to validate positive integer strings
     */
    private static boolean isValidPositiveInteger(String text) {
        try {
            int value = Integer.parseInt(text);
            return value > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Backward compatibility method - returns just the angles as a list of doubles
     */
    public static CompletableFuture<List<Double>> showDialogLegacy(double plusAngle, double minusAngle) {
        return showDialog(plusAngle, minusAngle)
                .thenApply(result -> result != null ? result.getAngles() : null);
    }
}