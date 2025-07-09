package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;

import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

public class BoundingBoxController {
    private static final Logger logger = LoggerFactory.getLogger(BoundingBoxController.class);

    /**
     * Holds the bounding-box + focus flag.
     */
    public record BoundingBoxResult(
            double x1, double y1,
            double x2, double y2,
            boolean inFocus,
            Map<String, Double> angleOverrides
    ) {
        // Convenience constructor without angle overrides
        public BoundingBoxResult(double x1, double y1, double x2, double y2, boolean inFocus) {
            this(x1, y1, x2, y2, inFocus, null);
        }
    }

    /**
     * Show a dialog asking the user to define a rectangular bounding box
     * (either by entering four comma-separated values or via four separate fields),
     * plus an "In focus?" checkbox. Returns a CompletableFuture that completes
     * with the BoundingBoxResult when the user clicks OK, or is cancelled if
     * the user hits Cancel or closes the dialog.
     *
     * @return a CompletableFuture which yields the bounding box coordinates and focus flag
     */
    public static CompletableFuture<BoundingBoxResult> showDialog() {
        CompletableFuture<BoundingBoxResult> future = new CompletableFuture<>();
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

        Platform.runLater(() -> {
            // 1) Create and configure the dialog window
            Dialog<BoundingBoxResult> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("boundingBox.title"));
            dlg.setHeaderText(res.getString("boundingBox.header") +
                    "\n\nNote: Enter coordinates in microscope stage units (microns).");

            // 2) Add OK and Cancel buttons, with localized labels
            ButtonType okType = new ButtonType(
                    res.getString("boundingBox.button.ok"),
                    ButtonBar.ButtonData.OK_DONE
            );
            ButtonType cancelType = new ButtonType(
                    res.getString("boundingBox.button.cancel"),
                    ButtonBar.ButtonData.CANCEL_CLOSE
            );
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // 3) Prepare two input modes via tabs: CSV entry or separate fields
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // 3a) CSV entry tab - initialized with saved value
            TextField csvField = new TextField(PersistentPreferences.getBoundingBoxString());
            csvField.setPromptText("e.g., 9000, 500, 10000, 1500");
            Tab csvTab = new Tab(res.getString("boundingBox.tab.csv"), new VBox(5, csvField));

            // 3b) Separate-fields tab - parse saved CSV to initialize fields
            TextField x1Field = new TextField();
            TextField y1Field = new TextField();
            TextField x2Field = new TextField();
            TextField y2Field = new TextField();

            // Initialize separate fields from saved CSV if valid
            try {
                String[] savedParts = PersistentPreferences.getBoundingBoxString().split(",");
                if (savedParts.length == 4) {
                    x1Field.setText(savedParts[0].trim());
                    y1Field.setText(savedParts[1].trim());
                    x2Field.setText(savedParts[2].trim());
                    y2Field.setText(savedParts[3].trim());
                }
            } catch (Exception e) {
                logger.debug("Could not parse saved bounding box for field initialization: {}", e.getMessage());
            }

            GridPane fieldsGrid = new GridPane();
            fieldsGrid.setHgap(10);
            fieldsGrid.setVgap(10);
            fieldsGrid.addRow(0,
                    new Label(res.getString("boundingBox.label.x1")), x1Field,
                    new Label(res.getString("boundingBox.label.y1")), y1Field
            );
            fieldsGrid.addRow(1,
                    new Label(res.getString("boundingBox.label.x2")), x2Field,
                    new Label(res.getString("boundingBox.label.y2")), y2Field
            );
            Tab fieldsTab = new Tab(res.getString("boundingBox.tab.fields"), fieldsGrid);

            tabs.getTabs().addAll(csvTab, fieldsTab);

            // 4) In-focus checkbox - initialized with saved value
            CheckBox inFocusCheckbox = new CheckBox(res.getString("boundingBox.label.inFocus"));
            inFocusCheckbox.setSelected(PersistentPreferences.getBoundingBoxInFocus());

            // 5) Error label for validation
            Label errorLabel = new Label();
            errorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 12px;");
            errorLabel.setWrapText(true);
            errorLabel.setVisible(false);

            // 6) Sync fields when switching tabs or typing
            csvField.textProperty().addListener((obs, old, newVal) -> {
                try {
                    String[] parts = newVal.split(",");
                    if (parts.length == 4) {
                        x1Field.setText(parts[0].trim());
                        y1Field.setText(parts[1].trim());
                        x2Field.setText(parts[2].trim());
                        y2Field.setText(parts[3].trim());
                    }
                } catch (Exception ignored) {
                }
            });

            // Update CSV when individual fields change
            Runnable updateCsvFromFields = () -> {
                if (!x1Field.getText().isEmpty() && !y1Field.getText().isEmpty() &&
                        !x2Field.getText().isEmpty() && !y2Field.getText().isEmpty()) {
                    csvField.setText(String.format("%s, %s, %s, %s",
                            x1Field.getText(), y1Field.getText(),
                            x2Field.getText(), y2Field.getText()));
                }
            };

            x1Field.textProperty().addListener((obs, old, newVal) -> updateCsvFromFields.run());
            y1Field.textProperty().addListener((obs, old, newVal) -> updateCsvFromFields.run());
            x2Field.textProperty().addListener((obs, old, newVal) -> updateCsvFromFields.run());
            y2Field.textProperty().addListener((obs, old, newVal) -> updateCsvFromFields.run());

            // 7) Assemble content
            VBox content = new VBox(10, tabs, inFocusCheckbox, errorLabel);
            content.setPadding(new Insets(20));
            dlg.getDialogPane().setContent(content);

            // 8) Prevent dialog from closing on OK if validation fails
            Button okButton = (Button) dlg.getDialogPane().lookupButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                double x1, y1, x2, y2;
                try {
                    if (tabs.getSelectionModel().getSelectedItem() == csvTab) {
                        // Parse CSV entry
                        String[] parts = csvField.getText().split(",");
                        if (parts.length != 4) {
                            throw new IllegalArgumentException("Please enter exactly 4 comma-separated values");
                        }
                        x1 = Double.parseDouble(parts[0].trim());
                        y1 = Double.parseDouble(parts[1].trim());
                        x2 = Double.parseDouble(parts[2].trim());
                        y2 = Double.parseDouble(parts[3].trim());
                    } else {
                        // Parse the four separate fields
                        if (x1Field.getText().trim().isEmpty() ||
                                y1Field.getText().trim().isEmpty() ||
                                x2Field.getText().trim().isEmpty() ||
                                y2Field.getText().trim().isEmpty()) {
                            throw new IllegalArgumentException("All coordinate fields must be filled");
                        }
                        x1 = Double.parseDouble(x1Field.getText());
                        y1 = Double.parseDouble(y1Field.getText());
                        x2 = Double.parseDouble(x2Field.getText());
                        y2 = Double.parseDouble(y2Field.getText());
                    }

                    // Validate that we have a proper bounding box
                    if (x1 == x2 || y1 == y2) {
                        throw new IllegalArgumentException("Bounding box must have non-zero width and height");
                    }

                    // Validate reasonable bounds (optional - adjust as needed)
                    double width = Math.abs(x2 - x1);
                    double height = Math.abs(y2 - y1);
                    if (width < 100 || height < 100) {
                        throw new IllegalArgumentException("Bounding box seems too small (< 100 µm). Please verify coordinates.");
                    }
                    if (width > 100000 || height > 100000) {
                        throw new IllegalArgumentException("Bounding box seems too large (> 100mm). Please verify coordinates.");
                    }

                    // Valid - hide error
                    errorLabel.setVisible(false);

                } catch (NumberFormatException e) {
                    errorLabel.setText("Invalid number format. Please enter valid numeric coordinates.");
                    errorLabel.setVisible(true);
                    event.consume();
                } catch (IllegalArgumentException e) {
                    errorLabel.setText(e.getMessage());
                    errorLabel.setVisible(true);
                    event.consume();
                }
            });

            // 9) Convert the user's button choice into a BoundingBoxResult
            dlg.setResultConverter(button -> {
                if (button != okType) {
                    return null;
                }
                double x1, y1, x2, y2;
                try {
                    if (tabs.getSelectionModel().getSelectedItem() == csvTab) {
                        String[] parts = csvField.getText().split(",");
                        x1 = Double.parseDouble(parts[0].trim());
                        y1 = Double.parseDouble(parts[1].trim());
                        x2 = Double.parseDouble(parts[2].trim());
                        y2 = Double.parseDouble(parts[3].trim());
                    } else {
                        x1 = Double.parseDouble(x1Field.getText());
                        y1 = Double.parseDouble(y1Field.getText());
                        x2 = Double.parseDouble(x2Field.getText());
                        y2 = Double.parseDouble(y2Field.getText());
                    }

                    // Save preferences before returning
                    PersistentPreferences.setBoundingBoxString(csvField.getText());
                    PersistentPreferences.setBoundingBoxInFocus(inFocusCheckbox.isSelected());
                    logger.info("Saved bounding box preferences: {} (in focus: {})",
                            csvField.getText(), inFocusCheckbox.isSelected());

                    return new BoundingBoxResult(x1, y1, x2, y2, inFocusCheckbox.isSelected());
                } catch (Exception e) {
                    logger.error("Error creating bounding box result", e);
                    return null;
                }
            });

            // 10) Show and handle result
            Optional<BoundingBoxResult> result = dlg.showAndWait();
            if (result.isPresent()) {
                future.complete(result.get());
            } else {
                future.cancel(true);
            }
        });

        return future;
    }
}