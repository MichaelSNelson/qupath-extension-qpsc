package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.fx.dialogs.Dialogs;
import javafx.geometry.Insets;
import java.io.IOException;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

public class InterfaceController extends VBox {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    @FXML
    private TextField sampleNameField;

    @FXML
    private Spinner<Double> pixelSizeSpinner;

    @FXML
    private Label warningLabel;

    @FXML
    private Button okButton;

    @FXML
    private Button cancelButton;

    // Future to capture the result when the dialog is closed.
    private CompletableFuture<UserInputResult> resultFuture;

    /**
     * Loads the FXML, creates and shows the dialog, and returns a CompletableFuture
     * that completes when the user makes a choice.
     */
    public static CompletableFuture<UserInputResult> createAndShow() throws IOException {
        FXMLLoader loader = new FXMLLoader(InterfaceController.class.getResource("interface.fxml"), resources);
        // Load the FXML's root node (a VBox)
        VBox root = loader.load();
        // Retrieve the controller instance from the loader
        InterfaceController controller = loader.getController();

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(new Scene(root));
        stage.setTitle(resources.getString("stage.title"));

        controller.resultFuture = new CompletableFuture<>();
        stage.show();

        return controller.resultFuture;
    }


    @FXML
    private void initialize() {
        // If the Spinner's value factory hasn't been set in FXML, create one explicitly.
        if (pixelSizeSpinner.getValueFactory() == null) {
            SpinnerValueFactory.DoubleSpinnerValueFactory valueFactory =
                    new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1000.0, 1.0, 0.1);
            pixelSizeSpinner.setEditable(true);
            pixelSizeSpinner.setValueFactory(valueFactory);
        }
        // Optionally set prompt text using resource keys.
        // (Uncomment these lines if you want to use the properties from strings.properties.)
        // sampleNameField.setPromptText(resources.getString("sampleName.prompt"));
        // pixelSizeSpinner.getEditor().setPromptText(resources.getString("pixelSize.prompt"));

        // Set the warning label text.
        warningLabel.setText(resources.getString("warning.check_preferences"));
    }

    @FXML
    private void handleOk() {
        String sampleName = sampleNameField.getText();
        Double pixelSize = pixelSizeSpinner.getValue();
        if (sampleName == null || sampleName.trim().isEmpty()) {
            Dialogs.showWarningNotification(resources.getString("error"), "Sample name cannot be empty.");
            return;
        }
        if (pixelSize == null) {
            Dialogs.showWarningNotification(resources.getString("error"), "Pixel size must be specified.");
            return;
        }
        if (resultFuture != null) {
            resultFuture.complete(new UserInputResult(sampleName, pixelSize));
        }
        Stage stage = (Stage) this.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void handleCancel() {
        if (resultFuture != null) {
            resultFuture.cancel(true);
        }
        Stage stage = (Stage) this.getScene().getWindow();
        stage.close();
    }

    /**
     * Data structure to hold the user's input.
     */
    public static class UserInputResult {
        private final String sampleName;
        private final Double pixelSize;

        public UserInputResult(String sampleName, Double pixelSize) {
            this.sampleName = sampleName;
            this.pixelSize = pixelSize;
        }

        public String getSampleName() {
            return sampleName;
        }

        public Double getPixelSize() {
            return pixelSize;
        }

        @Override
        public String toString() {
            return "UserInputResult{sampleName='" + sampleName + "', pixelSize=" + pixelSize + "}";
        }
    }

    public static void showTestStageMovementDialog() {
        Platform.runLater(() -> {
            var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle(res.getString("testDialog.title"));
            dialog.setHeaderText(res.getString("testDialog.header"));

            // --- X/Y fields + status ---
            TextField xField = new TextField();
            TextField yField = new TextField();
            Label statusLabel = new Label();

            // --- Spinner for polarizer angle 0–179, wraps ---
            Spinner<Integer> angleSpinner = new Spinner<>(0, 179, 0, 1);
            angleSpinner.setEditable(true);

            // Formatter so you can type into the spinner editor
            angleSpinner.getEditor().setTextFormatter(new TextFormatter<Integer>(
                    new StringConverter<Integer>() {
                        @Override public String toString(Integer i) {
                            return i == null ? "" : i.toString();
                        }
                        @Override public Integer fromString(String s) {
                            try { return Integer.parseInt(s); }
                            catch (NumberFormatException e) { return angleSpinner.getValue(); }
                        }
                    }
            ));

            Label currentAngleLabel = new Label();
            // Initialize spinner & label from hardware
            try {
                double p = MicroscopeController.getInstance().getStagePositionP();
                // spinner holds half the real angle
                angleSpinner.getValueFactory().setValue((int)Math.round(p / 2.0));
                currentAngleLabel.setText(
                        res.getString("testDialog.label.currentAngle") + ": " + String.format("%.1f°", p));
            } catch (Exception e) {
                currentAngleLabel.setText(res.getString("testDialog.label.currentAngle") + ": ?");
            }

            // When spinner changes, move the polarizer and refresh label
            angleSpinner.valueProperty().addListener((obs, oldV, newV) -> {
                int realAngle = (newV % 180 + 180) % 180 * 2;  // wrap & ×2
                try {
                    MicroscopeController.getInstance().moveStageP(realAngle);
                    double actual = MicroscopeController.getInstance().getStagePositionP();
                    currentAngleLabel.setText(
                            res.getString("testDialog.label.currentAngle") + ": " + String.format("%.1f°", actual));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(ex.getMessage(),
                            res.getString("testDialog.title"));
                }
            });

            // --- Buttons for X/Y move & get coords ---
            ButtonType moveXY = new ButtonType(
                    res.getString("testDialog.button.move"), ButtonBar.ButtonData.APPLY);
            ButtonType getXY  = new ButtonType(
                    res.getString("testDialog.button.getCoords"), ButtonBar.ButtonData.OTHER);
            dialog.getDialogPane().getButtonTypes().addAll(moveXY, getXY, ButtonType.CLOSE);

            Button moveBtn = (Button) dialog.getDialogPane().lookupButton(moveXY);
            Button getBtn  = (Button) dialog.getDialogPane().lookupButton(getXY);

            moveBtn.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                try {
                    double x = Double.parseDouble(xField.getText());
                    double y = Double.parseDouble(yField.getText());
                    if (MicroscopeController.getInstance().isWithinBoundsXY(x, y)) {
                        MicroscopeController.getInstance().moveStageXY(x, y);
                        statusLabel.setText(res.getString("testDialog.status.moveSuccess"));
                    } else {
                        UIFunctions.notifyUserOfError(
                                res.getString("testDialog.error.outOfBounds"),
                                res.getString("testDialog.title"));
                    }
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(ex.getMessage(),
                            res.getString("testDialog.title"));
                }
            });


            getBtn.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();   // ← prevent dialog from closing

                try {
                    double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                    xField.setText(String.format("%.2f", xy[0]));
                    yField.setText(String.format("%.2f", xy[1]));
                    statusLabel.setText(res.getString("testDialog.status.gotCoords"));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(),
                            res.getString("testDialog.title"));
                }
            });

            // --- Layout ---
            GridPane grid = new GridPane();
            grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20));

            grid.add(new Label(res.getString("testDialog.label.x")), 0, 0);
            grid.add(xField, 1, 0);
            grid.add(new Label(res.getString("testDialog.label.y")), 0, 1);
            grid.add(yField, 1, 1);
            grid.add(statusLabel, 0, 2, 2, 1);

            grid.add(new Label(res.getString("testDialog.label.angleSpinner")), 0, 3);
            grid.add(angleSpinner, 1, 3);
            grid.add(currentAngleLabel, 0, 4, 2, 1);

            dialog.getDialogPane().setContent(grid);
            dialog.initModality(Modality.NONE);
            dialog.show();
        });
    }
}
