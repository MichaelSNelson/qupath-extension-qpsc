package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

public class BoundingBoxController {

    /** Holds the bounding-box + focus flag. */
    public record BoundingBoxResult(double x1, double y1,
                                    double x2, double y2,
                                    boolean inFocus) { }

    /**
     * Show a dialog asking the user to define a rectangular bounding box
     * (either by entering four comma-separated values or via four separate fields),
     * plus an "In focus? " checkbox. Returns a CompletableFuture that completes
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
            dlg.setHeaderText(res.getString("boundingBox.header"));

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

            // 3a) CSV entry tab
            TextField csvField = new TextField();
            csvField.setPromptText(res.getString("boundingBox.prompt.csv"));  // e.g. "X1, Y1, X2, Y2"
            Tab csvTab = new Tab(res.getString("boundingBox.tab.csv"), new VBox(5, csvField));

            // 3b) Separate-fields tab
            TextField x1Field = new TextField();
            TextField y1Field = new TextField();
            TextField x2Field = new TextField();
            TextField y2Field = new TextField();

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

            // 4) In-focus checkbox
            CheckBox inFocusCheckbox = new CheckBox(res.getString("boundingBox.label.inFocus"));

            // 5) Assemble content
            VBox content = new VBox(10, tabs, inFocusCheckbox);
            content.setPadding(new Insets(20));
            dlg.getDialogPane().setContent(content);

            // 6) Convert the user’s button choice into a BoundingBoxResult
            dlg.setResultConverter(button -> {
                if (button != okType) {
                    // User cancelled or closed the dialog
                    return null;
                }
                double x1, y1, x2, y2;
                try {
                    if (tabs.getSelectionModel().getSelectedItem() == csvTab) {
                        // Parse CSV entry
                        String[] parts = csvField.getText().split(",");
                        if (parts.length != 4) {
                            throw new IllegalArgumentException(res.getString("boundingBox.error.invalidInput"));
                        }
                        x1 = Double.parseDouble(parts[0].trim());
                        y1 = Double.parseDouble(parts[1].trim());
                        x2 = Double.parseDouble(parts[2].trim());
                        y2 = Double.parseDouble(parts[3].trim());
                    } else {
                        // Parse the four separate fields
                        x1 = Double.parseDouble(x1Field.getText());
                        y1 = Double.parseDouble(y1Field.getText());
                        x2 = Double.parseDouble(x2Field.getText());
                        y2 = Double.parseDouble(y2Field.getText());
                    }
                } catch (Exception e) {
                    // Show an error and keep the dialog open
                    new Alert(Alert.AlertType.ERROR,
                            res.getString("boundingBox.error.invalidInput") + "\n" + e.getMessage()
                    ).showAndWait();
                    return null;
                }
                return new BoundingBoxResult(x1, y1, x2, y2, inFocusCheckbox.isSelected());
            });

            // 7) Show and handle result
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

