package qupath.ext.qpsc.modality.ppm.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Controller for selecting PPM (Polarized Light Microscopy) angles and decimal exposure times.
 * 
 * <p>This controller provides a JavaFX dialog interface for users to select which polarization 
 * angles to acquire and specify precise exposure times for each angle. The controller supports 
 * decimal exposure values for fine-grained exposure control (e.g., 1.2ms, 0.5ms).</p>
 * 
 * <p>The dialog presents checkboxes for four standard PPM angles:</p>
 * <ul>
 *   <li><strong>Minus angle:</strong> Negative polarizer rotation (e.g., -5.0 degrees)</li>
 *   <li><strong>Zero angle:</strong> Crossed polarizers at 0.0 degrees</li>
 *   <li><strong>Plus angle:</strong> Positive polarizer rotation (e.g., +5.0 degrees)</li>
 *   <li><strong>Uncrossed angle:</strong> Parallel polarizers (e.g., 45.0 degrees)</li>
 * </ul>
 * 
 * <p>Each angle has an associated exposure time input field that accepts decimal values.
 * The exposure values are validated to ensure they are positive numbers and are stored 
 * in user preferences via {@link PPMPreferences}.</p>
 * 
 * @author Mike Nelson
 * @see PPMPreferences
 * @since 1.0
 */
public class PPMAngleSelectionController {

    private static final Logger logger = LoggerFactory.getLogger(PPMAngleSelectionController.class);

    /**
     * Immutable result container holding the user's angle and exposure time selections.
     * 
     * <p>This class encapsulates the output from the angle selection dialog, containing
     * the list of selected {@link AngleExposure} pairs that the user chose for acquisition.
     * Only angles that were checked in the dialog are included in the result.</p>
     * 
     * @since 1.0
     */
    public static class AngleExposureResult {
        /** The list of selected angle-exposure pairs for acquisition */
        public final List<AngleExposure> angleExposures;
        
        /**
         * Creates a new result with the specified angle-exposure pairs.
         * @param angleExposures the selected angle-exposure pairs, must not be null
         */
        public AngleExposureResult(List<AngleExposure> angleExposures) {
            this.angleExposures = angleExposures;
        }
        
        /**
         * Extracts just the angle values from the angle-exposure pairs.
         * @return a list containing the angle values in degrees
         */
        public List<Double> getAngles() {
            return angleExposures.stream().map(ae -> ae.angle).collect(Collectors.toList());
        }
    }

    /**
     * Simple data container pairing a rotation angle with its exposure time.
     * 
     * <p>This is a UI-specific version of the main {@link qupath.ext.qpsc.modality.AngleExposure} 
     * class, used within the angle selection dialog. It represents the user's selections
     * before they are converted to the modality system's format.</p>
     * 
     * @since 1.0
     */
    public static class AngleExposure {
        /** The rotation angle in degrees */
        public final double angle;
        /** The exposure time in milliseconds (supports decimal values) */
        public final double exposureMs;
        
        /**
         * Creates a new angle-exposure pair.
         * @param angle the rotation angle in degrees
         * @param exposureMs the exposure time in milliseconds (decimal values supported)
         */
        public AngleExposure(double angle, double exposureMs) {
            this.angle = angle;
            this.exposureMs = exposureMs;
        }
        
        /**
         * Returns a formatted string showing both angle and exposure time.
         * @return formatted string in the format "angle° @ exposureMs" (e.g., "5.0° @ 1.2ms")
         */
        @Override
        public String toString() {
            return String.format("%.1f° @ %.1fms", angle, exposureMs);
        }
    }

    /**
     * Shows the PPM angle selection dialog and returns the user's selections asynchronously.
     * 
     * <p>This method creates and displays a modal dialog where users can select which PPM
     * angles to acquire and specify decimal exposure times for each angle. The dialog 
     * presents four angle options with checkboxes and exposure time input fields.</p>
     * 
     * <p>The exposure time input fields support decimal values (e.g., 1.2, 0.5, 15.8) and
     * are validated to ensure positive values. User selections are automatically saved to
     * preferences via {@link PPMPreferences}.</p>
     * 
     * <p>The dialog includes convenience buttons for \"Select All\" and \"Select None\" to
     * quickly configure common acquisition patterns.</p>
     * 
     * @param plusAngle the positive polarizer angle value in degrees (typically +5.0)
     * @param minusAngle the negative polarizer angle value in degrees (typically -5.0)
     * @param uncrossedAngle the uncrossed polarizer angle in degrees (typically 45.0)
     * @return a {@code CompletableFuture} that completes with the user's selections, or
     *         is cancelled if the user cancels the dialog
     * @since 1.0
     */
    public static CompletableFuture<AngleExposureResult> showDialog(double plusAngle, double minusAngle, double uncrossedAngle) {
        CompletableFuture<AngleExposureResult> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Dialog<AngleExposureResult> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("PPM Angle Selection");
            dialog.setHeaderText("Select polarization angles (in tick marks) and exposure times for acquisition:");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(10));

            UnaryOperator<TextFormatter.Change> doubleFilter = change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty()) return change;
                try {
                    double value = Double.parseDouble(newText);
                    if (value > 0) return change;
                } catch (NumberFormatException ignored) {}
                return null;
            };

            grid.add(new Label("Angle"),0,0);
            grid.add(new Label("Exposure (ms)"),2,0);

            CheckBox minusCheck = new CheckBox(String.format("%.1f 'degrees'", minusAngle));
            minusCheck.setSelected(PPMPreferences.getMinusSelected());
            TextField minusExposure = new TextField(String.valueOf(PPMPreferences.getMinusExposureMs()));
            minusExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            minusExposure.setPrefWidth(80);
            minusExposure.setDisable(!minusCheck.isSelected());
            grid.add(minusCheck,0,1);
            grid.add(minusExposure,2,1);

            CheckBox zeroCheck = new CheckBox("0 'degrees'");
            zeroCheck.setSelected(PPMPreferences.getZeroSelected());
            TextField zeroExposure = new TextField(String.valueOf(PPMPreferences.getZeroExposureMs()));
            zeroExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            zeroExposure.setPrefWidth(80);
            zeroExposure.setDisable(!zeroCheck.isSelected());
            grid.add(zeroCheck,0,2);
            grid.add(zeroExposure,2,2);

            CheckBox plusCheck = new CheckBox(String.format("%.1f 'degrees'", plusAngle));
            plusCheck.setSelected(PPMPreferences.getPlusSelected());
            TextField plusExposure = new TextField(String.valueOf(PPMPreferences.getPlusExposureMs()));
            plusExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            plusExposure.setPrefWidth(80);
            plusExposure.setDisable(!plusCheck.isSelected());
            grid.add(plusCheck,0,3);
            grid.add(plusExposure,2,3);

            CheckBox uncrossedCheck = new CheckBox(String.format("%.1f 'degrees' (uncrossed)", uncrossedAngle));
            uncrossedCheck.setSelected(PPMPreferences.getUncrossedSelected());
            TextField uncrossedExposure = new TextField(String.valueOf(PPMPreferences.getUncrossedExposureMs()));
            uncrossedExposure.setTextFormatter(new TextFormatter<>(doubleFilter));
            uncrossedExposure.setPrefWidth(80);
            uncrossedExposure.setDisable(!uncrossedCheck.isSelected());
            grid.add(uncrossedCheck,0,4);
            grid.add(uncrossedExposure,2,4);

            minusCheck.selectedProperty().addListener((obs,o,s)->{
                minusExposure.setDisable(!s);
                if(!s) minusExposure.clear();
                PPMPreferences.setMinusSelected(s);
                logger.debug("PPM minus tick selection updated to: {}", s);
            });
            zeroCheck.selectedProperty().addListener((obs,o,s)->{
                zeroExposure.setDisable(!s);
                if(!s) zeroExposure.clear();
                PPMPreferences.setZeroSelected(s);
                logger.debug("PPM zero tick selection updated to: {}", s);
            });
            plusCheck.selectedProperty().addListener((obs,o,s)->{
                plusExposure.setDisable(!s);
                if(!s) plusExposure.clear();
                PPMPreferences.setPlusSelected(s);
                logger.debug("PPM plus tick selection updated to: {}", s);
            });
            uncrossedCheck.selectedProperty().addListener((obs,o,s)->{
                uncrossedExposure.setDisable(!s);
                if(!s) uncrossedExposure.clear();
                PPMPreferences.setUncrossedSelected(s);
                logger.debug("PPM uncrossed tick selection updated to: {}", s);
            });

            minusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setMinusExposureMs(Double.parseDouble(n)); });
            zeroExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setZeroExposureMs(Double.parseDouble(n)); });
            plusExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setPlusExposureMs(Double.parseDouble(n)); });
            uncrossedExposure.textProperty().addListener((obs,o,n)->{ if(!n.isEmpty()) PPMPreferences.setUncrossedExposureMs(Double.parseDouble(n)); });

            Label info = new Label("Each selected angle will be acquired with the specified exposure time.");
            Button selectAll = new Button("Select All");
            Button selectNone = new Button("Select None");
            selectAll.setOnAction(e->{minusCheck.setSelected(true);zeroCheck.setSelected(true);plusCheck.setSelected(true);uncrossedCheck.setSelected(true);});
            selectNone.setOnAction(e->{minusCheck.setSelected(false);zeroCheck.setSelected(false);plusCheck.setSelected(false);uncrossedCheck.setSelected(false);});
            HBox quickButtons = new HBox(10, selectAll, selectNone);

            VBox content = new VBox(10, info, grid, new Separator(), quickButtons);
            content.setPadding(new Insets(20));
            dialog.getDialogPane().setContent(content);

            ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);
            Node okNode = dialog.getDialogPane().lookupButton(okType);

            BooleanBinding valid = Bindings.createBooleanBinding(() -> {
                boolean any = minusCheck.isSelected() || zeroCheck.isSelected() || plusCheck.isSelected() || uncrossedCheck.isSelected();
                if(!any) return false;
                if(minusCheck.isSelected() && !isValidPosDouble(minusExposure.getText())) return false;
                if(zeroCheck.isSelected() && !isValidPosDouble(zeroExposure.getText())) return false;
                if(plusCheck.isSelected() && !isValidPosDouble(plusExposure.getText())) return false;
                if(uncrossedCheck.isSelected() && !isValidPosDouble(uncrossedExposure.getText())) return false;
                return true;
            }, minusCheck.selectedProperty(), zeroCheck.selectedProperty(), plusCheck.selectedProperty(), uncrossedCheck.selectedProperty(),
            minusExposure.textProperty(), zeroExposure.textProperty(), plusExposure.textProperty(), uncrossedExposure.textProperty());
            okNode.disableProperty().bind(valid.not());

            dialog.setResultConverter(button -> {
                if(button==okType){
                    List<AngleExposure> list = new ArrayList<>();
                    if(minusCheck.isSelected()) list.add(new AngleExposure(minusAngle,Double.parseDouble(minusExposure.getText())));
                    if(zeroCheck.isSelected()) list.add(new AngleExposure(0.0,Double.parseDouble(zeroExposure.getText())));
                    if(plusCheck.isSelected()) list.add(new AngleExposure(plusAngle,Double.parseDouble(plusExposure.getText())));
                    if(uncrossedCheck.isSelected()) list.add(new AngleExposure(uncrossedAngle,Double.parseDouble(uncrossedExposure.getText())));
                    logger.info("PPM angles and exposures selected: {}", list);
                    return new AngleExposureResult(list);
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(future::complete, ()->future.cancel(true));
        });
        return future;
    }

    /**
     * Validates that a text string represents a positive decimal number.
     * 
     * <p>This helper method is used to validate exposure time input fields to ensure
     * they contain valid positive decimal values before enabling the OK button.</p>
     * 
     * @param text the text string to validate
     * @return {@code true} if the text represents a positive number, {@code false} otherwise
     */
    private static boolean isValidPosDouble(String text){
        try{double v=Double.parseDouble(text);return v>0;}catch(NumberFormatException e){return false;}
    }
}
