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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.fx.dialogs.Dialogs;
import javafx.geometry.Insets;
import java.io.IOException;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * InterfaceController
 *
 * <p>JavaFX controller for your custom FXML dialog(s):
 *   - Loads `interface.fxml`, binds UI fields (TextField, Spinner, Buttons).
 *   - Collects sample name, pixel size, etc., then completes a CompletableFuture.
 */

public class InterfaceController extends VBox {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private static final Logger logger =
            LoggerFactory.getLogger(InterfaceController.class);
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
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle(res.getString("testDialog.title"));
            dlg.setHeaderText(res.getString("testDialog.header"));

            // --- Fields and status labels ---
            TextField xField = new TextField();
            TextField yField = new TextField();
            Label xyStatus = new Label();

            TextField zField = new TextField();
            Label zStatus = new Label();

            TextField rField = new TextField();
            Label rStatus = new Label();

            // --- Initialize fields from hardware positions ---
            try {
                double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                xField.setText(String.format("%.2f", xy[0]));
                yField.setText(String.format("%.2f", xy[1]));
            } catch (Exception ignored) {}

            try {
                double z = MicroscopeController.getInstance().getStagePositionZ();
                zField.setText(String.format("%.2f", z));
            } catch (Exception ignored) {}

            try {
                double r = MicroscopeController.getInstance().getStagePositionR();
                rField.setText(String.format("%.2f", r));
            } catch (Exception ignored) {}

            // --- Buttons ---
            Button moveXYBtn = new Button(res.getString("testDialog.button.moveXY"));
            Button moveZBtn = new Button(res.getString("testDialog.button.moveZ"));
            Button moveRBtn = new Button(res.getString("testDialog.button.moveR"));

            // --- Actions (dialog remains open) ---
            moveXYBtn.setOnAction(e -> {
                try {
                    double x = Double.parseDouble(xField.getText());
                    double y = Double.parseDouble(yField.getText());
                    MicroscopeController.getInstance().moveStageXY(x, y);
                    xyStatus.setText(String.format(res.getString("testDialog.status.xyMoved"), x, y));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("testDialog.title"));
                }
            });

            moveZBtn.setOnAction(e -> {
                try {
                    double z = Double.parseDouble(zField.getText());
                    MicroscopeController.getInstance().moveStageZ(z);
                    zStatus.setText(String.format(res.getString("testDialog.status.zMoved"), z));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("testDialog.title"));
                }
            });

            moveRBtn.setOnAction(e -> {
                try {
                    double r = Double.parseDouble(rField.getText());
                    MicroscopeController.getInstance().moveStageR(r);
                    rStatus.setText(String.format(res.getString("testDialog.status.rMoved"), r));
                } catch (Exception ex) {
                    UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("testDialog.title"));
                }
            });

            // --- Layout ---
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));

            // XY controls
            grid.add(new Label(res.getString("testDialog.label.x")), 0, 0);
            grid.add(xField, 1, 0);
            grid.add(new Label(res.getString("testDialog.label.y")), 0, 1);
            grid.add(yField, 1, 1);
            grid.add(moveXYBtn, 2, 0, 1, 2);
            grid.add(xyStatus, 1, 2, 2, 1);

            // Z controls
            grid.add(new Label(res.getString("testDialog.label.z")), 0, 3);
            grid.add(zField, 1, 3);
            grid.add(moveZBtn, 2, 3);
            grid.add(zStatus, 1, 4, 2, 1);

            // R (Polarizer) controls
            grid.add(new Label(res.getString("testDialog.label.r")), 0, 5);
            grid.add(rField, 1, 5);
            grid.add(moveRBtn, 2, 5);
            grid.add(rStatus, 1, 6, 2, 1);

            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); // Close button only closes dialog
            dlg.initModality(Modality.NONE);
            dlg.show();
        });
    }

}
