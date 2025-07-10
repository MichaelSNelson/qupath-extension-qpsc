package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.Map;
import java.util.MissingResourceException;
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
    /**
     * Shows the bounding box configuration dialog.
     * @return CompletableFuture containing the BoundingBoxResult, or null if cancelled
     */
    /**
     * Shows the bounding box configuration dialog.
     * @return CompletableFuture containing the BoundingBoxResult, or null if cancelled
     */
    public static CompletableFuture<BoundingBoxResult> showDialog() {
        CompletableFuture<BoundingBoxResult> future = new CompletableFuture<>();
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

        Platform.runLater(() -> {
            // Get the selected modality from the last sample setup
            String lastModality = SampleSetupController.getLastSampleSetup() != null
                    ? SampleSetupController.getLastSampleSetup().modality()
                    : PersistentPreferences.getLastModality();

            boolean isPPMModality = lastModality != null && lastModality.startsWith("PPM_");

            // 1) Create and configure the dialog window
            Dialog<BoundingBoxResult> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("boundingBox.title"));

            String headerText = res.getString("boundingBox.header") +
                    "\n\nNote: Enter coordinates in microscope stage units (microns).";

            if (isPPMModality) {
                headerText += "\n\nPPM mode detected - you can adjust polarization angles below.";
            }

            dlg.setHeaderText(headerText);

            // 2) Add buttons
            ButtonType okType = new ButtonType(res.getString("sampleSetup.button.ok"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(res.getString("sampleSetup.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // Get the OK button for validation control
            Button okButton = (Button) dlg.getDialogPane().lookupButton(okType);

            // 3) Prepare tabs
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // CSV Tab
            Tab csvTab = new Tab("CSV Entry");
            try {
                csvTab.setText(res.getString("boundingBox.tab.csv"));
            } catch (MissingResourceException e) {
                // Use default if key not found
            }
            TextField csvField = new TextField();
            csvField.setPromptText("x1,y1,x2,y2");
            csvField.setPrefWidth(350);

            // Load saved preference
            String savedBounds = PersistentPreferences.getBoundingBoxString();
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                csvField.setText(savedBounds);
            }

            Label csvInstructions = new Label("Enter as: x1,y1,x2,y2 (e.g., -5000,-5000,5000,5000)");
            csvInstructions.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            VBox csvContent = new VBox(5, csvField, csvInstructions);
            csvContent.setPadding(new Insets(10));
            csvTab.setContent(csvContent);

            // Fields Tab
            Tab fieldsTab = new Tab("Individual Fields");
            try {
                fieldsTab.setText(res.getString("boundingBox.tab.fields"));
            } catch (MissingResourceException e) {
                // Use default if key not found
            }
            GridPane fieldGrid = new GridPane();
            fieldGrid.setHgap(10);
            fieldGrid.setVgap(10);
            fieldGrid.setPadding(new Insets(10));

            TextField x1Field = new TextField();
            TextField y1Field = new TextField();
            TextField x2Field = new TextField();
            TextField y2Field = new TextField();

            x1Field.setPromptText("X1");
            y1Field.setPromptText("Y1");
            x2Field.setPromptText("X2");
            y2Field.setPromptText("Y2");

            x1Field.setPrefWidth(150);
            y1Field.setPrefWidth(150);
            x2Field.setPrefWidth(150);
            y2Field.setPrefWidth(150);

            fieldGrid.add(new Label("Upper Left:"), 0, 0);
            fieldGrid.add(new Label("X1:"), 1, 0);
            fieldGrid.add(x1Field, 2, 0);
            fieldGrid.add(new Label("Y1:"), 3, 0);
            fieldGrid.add(y1Field, 4, 0);

            fieldGrid.add(new Label("Lower Right:"), 0, 1);
            fieldGrid.add(new Label("X2:"), 1, 1);
            fieldGrid.add(x2Field, 2, 1);
            fieldGrid.add(new Label("Y2:"), 3, 1);
            fieldGrid.add(y2Field, 4, 1);

            fieldsTab.setContent(fieldGrid);

            tabs.getTabs().addAll(csvTab, fieldsTab);

            // 4) In-focus checkbox - initialized with saved value
            String inFocusLabel = "Keep stage in focus while moving";
            try {
                inFocusLabel = res.getString("boundingBox.label.inFocus");
            } catch (MissingResourceException e) {
                // Use default if key not found
            }
            CheckBox inFocusCheckbox = new CheckBox(inFocusLabel);
            inFocusCheckbox.setSelected(PersistentPreferences.getBoundingBoxInFocus());

            // 5) PPM angle configuration (only shown for PPM modalities)
            VBox ppmConfig = new VBox(5);
            ppmConfig.setVisible(isPPMModality);
            ppmConfig.setManaged(isPPMModality);

            if (isPPMModality) {
                // Get default angles from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                        QPPreferenceDialog.getMicroscopeConfigFileProperty());
                Map<String, Object> ppmConfigMap = mgr.getPPMConfig();

                double defaultPlus = 5.0;
                double defaultMinus = -5.0;

                Map<String, Object> ppmPlus = (Map<String, Object>) ppmConfigMap.get("ppm_plus");
                Map<String, Object> ppmMinus = (Map<String, Object>) ppmConfigMap.get("ppm_minus");

                if (ppmPlus != null && ppmPlus.containsKey("degrees")) {
                    defaultPlus = ((Number) ppmPlus.get("degrees")).doubleValue();
                }
                if (ppmMinus != null && ppmMinus.containsKey("degrees")) {
                    defaultMinus = ((Number) ppmMinus.get("degrees")).doubleValue();
                }

                Label ppmLabel = new Label("PPM Polarization Angles:");
                ppmLabel.setStyle("-fx-font-weight: bold;");

                CheckBox overrideAngles = new CheckBox("Override default angles for this acquisition");

                GridPane angleGrid = new GridPane();
                angleGrid.setHgap(10);
                angleGrid.setVgap(5);
                angleGrid.setDisable(true);

                Label plusLabel = new Label("Plus angle (degrees):");
                Spinner<Double> plusSpinner = new Spinner<>(-180.0, 180.0, defaultPlus, 0.5);
                plusSpinner.setEditable(true);
                plusSpinner.setPrefWidth(100);

                Label minusLabel = new Label("Minus angle (degrees):");
                Spinner<Double> minusSpinner = new Spinner<>(-180.0, 180.0, defaultMinus, 0.5);
                minusSpinner.setEditable(true);
                minusSpinner.setPrefWidth(100);

                angleGrid.add(plusLabel, 0, 0);
                angleGrid.add(plusSpinner, 1, 0);
                angleGrid.add(minusLabel, 0, 1);
                angleGrid.add(minusSpinner, 1, 1);

                overrideAngles.selectedProperty().addListener((obs, old, selected) -> {
                    angleGrid.setDisable(!selected);
                });

                ppmConfig.getChildren().addAll(
                        new Separator(),
                        ppmLabel,
                        overrideAngles,
                        angleGrid
                );

                // Store angle values in node properties for retrieval
                ppmConfig.getProperties().put("overrideAngles", overrideAngles);
                ppmConfig.getProperties().put("plusSpinner", plusSpinner);
                ppmConfig.getProperties().put("minusSpinner", minusSpinner);
            }

            // 6) Error label for validation
            Label errorLabel = new Label();
            errorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 12px;");
            errorLabel.setWrapText(true);
            errorLabel.setVisible(false);

            // 7) Field synchronization
            // When CSV changes, update individual fields
            csvField.textProperty().addListener((obs, old, newVal) -> {
                if (newVal == null || newVal.trim().isEmpty()) {
                    x1Field.clear();
                    y1Field.clear();
                    x2Field.clear();
                    y2Field.clear();
                    return;
                }

                String[] parts = newVal.split(",");
                if (parts.length == 4) {
                    try {
                        x1Field.setText(parts[0].trim());
                        y1Field.setText(parts[1].trim());
                        x2Field.setText(parts[2].trim());
                        y2Field.setText(parts[3].trim());
                    } catch (Exception e) {
                        // Invalid format, clear fields
                        x1Field.clear();
                        y1Field.clear();
                        x2Field.clear();
                        y2Field.clear();
                    }
                }
            });

            // When individual fields change, update CSV
            ChangeListener<String> fieldListener = (obs, old, newVal) -> {
                String x1 = x1Field.getText().trim();
                String y1 = y1Field.getText().trim();
                String x2 = x2Field.getText().trim();
                String y2 = y2Field.getText().trim();

                if (!x1.isEmpty() && !y1.isEmpty() && !x2.isEmpty() && !y2.isEmpty()) {
                    csvField.setText(String.format("%s,%s,%s,%s", x1, y1, x2, y2));
                }
            };

            x1Field.textProperty().addListener(fieldListener);
            y1Field.textProperty().addListener(fieldListener);
            x2Field.textProperty().addListener(fieldListener);
            y2Field.textProperty().addListener(fieldListener);

            // Initialize fields from saved CSV if present
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                csvField.setText(savedBounds);
            }

            // 8) Validation
            Runnable validateFields = () -> {
                String csvText = csvField.getText().trim();
                boolean valid = false;
                String errorMsg = "";

                if (csvText.isEmpty()) {
                    errorMsg = "Please enter bounding box coordinates";
                } else {
                    String[] parts = csvText.split(",");
                    if (parts.length != 4) {
                        errorMsg = "Expected 4 values (x1,y1,x2,y2), found " + parts.length;
                    } else {
                        try {
                            double x1 = Double.parseDouble(parts[0].trim());
                            double y1 = Double.parseDouble(parts[1].trim());
                            double x2 = Double.parseDouble(parts[2].trim());
                            double y2 = Double.parseDouble(parts[3].trim());

                            // Just check that we have different corners (not the same point)
                            if (x1 == x2 && y1 == y2) {
                                errorMsg = "The two corners must be different points";
                            } else {
                                valid = true;
                                errorMsg = "";
                            }
                        } catch (NumberFormatException e) {
                            errorMsg = "Invalid number format";
                        }
                    }
                }

                errorLabel.setText(errorMsg);
                errorLabel.setVisible(!valid);
                okButton.setDisable(!valid);
            };

            // Add listeners for validation
            csvField.textProperty().addListener((obs, old, newVal) -> validateFields.run());

            // Initial validation
            validateFields.run();

            // 9) Assemble content
            VBox content = new VBox(10, tabs, inFocusCheckbox, ppmConfig, errorLabel);
            content.setPadding(new Insets(20));
            dlg.getDialogPane().setContent(content);

            // 10) Convert the user's button choice into a BoundingBoxResult
            dlg.setResultConverter(button -> {
                if (button != okType) {
                    return null;
                }
                double x1, y1, x2, y2;
                try {
                    String[] parts = csvField.getText().split(",");
                    x1 = Double.parseDouble(parts[0].trim());
                    y1 = Double.parseDouble(parts[1].trim());
                    x2 = Double.parseDouble(parts[2].trim());
                    y2 = Double.parseDouble(parts[3].trim());

                    // Save preferences before returning
                    PersistentPreferences.setBoundingBoxString(csvField.getText());
                    PersistentPreferences.setBoundingBoxInFocus(inFocusCheckbox.isSelected());

                    // Handle PPM angle override if present
                    if (isPPMModality && ppmConfig.getProperties().containsKey("overrideAngles")) {
                        CheckBox override = (CheckBox) ppmConfig.getProperties().get("overrideAngles");
                        if (override.isSelected()) {
                            Spinner<Double> plusSpin = (Spinner<Double>) ppmConfig.getProperties().get("plusSpinner");
                            Spinner<Double> minusSpin = (Spinner<Double>) ppmConfig.getProperties().get("minusSpinner");

                            // Store overridden angles in the result
                            Map<String, Double> angleOverrides = Map.of(
                                    "plus", plusSpin.getValue(),
                                    "minus", minusSpin.getValue()
                            );

                            logger.info("User overrode PPM angles: plus={}, minus={}",
                                    plusSpin.getValue(), minusSpin.getValue());

                            return new BoundingBoxResult(x1, y1, x2, y2, inFocusCheckbox.isSelected(), angleOverrides);
                        }
                    }

                    return new BoundingBoxResult(x1, y1, x2, y2, inFocusCheckbox.isSelected());
                } catch (Exception e) {
                    logger.error("Error creating bounding box result", e);
                    return null;
                }
            });

            // 11) Show dialog and handle result
            dlg.showAndWait().ifPresentOrElse(
                    result -> {
                        logger.info("Bounding box configured: ({}, {}) to ({}, {}), inFocus: {}",
                                result.x1(), result.y1(), result.x2(), result.y2(), result.inFocus());
                        future.complete(result);
                    },
                    () -> {
                        logger.info("Bounding box dialog cancelled");
                        future.complete(null);
                    }
            );
        });

        return future;
    }
}