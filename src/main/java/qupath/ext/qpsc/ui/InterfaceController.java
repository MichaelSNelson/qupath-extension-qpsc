package qupath.ext.qpsc.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.fx.dialogs.Dialogs;

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
}
