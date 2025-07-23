package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
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

            // CSV Tab with colored label
            Tab csvTab = new Tab();
            Label csvTabLabel = new Label("CSV Entry");
            csvTabLabel.setStyle("-fx-text-fill: #4a90e2;"); // Pale blue
            csvTab.setGraphic(csvTabLabel);
            try {
                csvTabLabel.setText(res.getString("boundingBox.tab.csv"));
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

            // Add two position buttons for CSV tab
            Button csvGetFirstPosButton = new Button("Get First Stage Position");
            csvGetFirstPosButton.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        // Get current CSV values
                        String currentCsv = csvField.getText().trim();
                        String[] parts = currentCsv.split(",");

                        if (parts.length == 0 || currentCsv.isEmpty()) {
                            // Empty field, just set first position
                            csvField.setText(String.format("%.2f,%.2f", coords[0], coords[1]));
                        } else if (parts.length == 2) {
                            // Already have 2 values, keep them and add placeholder for second position
                            csvField.setText(String.format("%.2f,%.2f", coords[0], coords[1]));
                        } else if (parts.length == 4) {
                            // Update only x1,y1 keeping x2,y2
                            csvField.setText(String.format("%.2f,%.2f,%s,%s",
                                    coords[0], coords[1], parts[2].trim(), parts[3].trim()));
                        } else {
                            // 1 or 3 values - replace with just first position
                            csvField.setText(String.format("%.2f,%.2f", coords[0], coords[1]));
                        }
                        logger.info("Updated first position from stage: X={}, Y={}", coords[0], coords[1]);
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            Button csvGetSecondPosButton = new Button("Get Second Stage Position");
            csvGetSecondPosButton.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        String currentCsv = csvField.getText().trim();
                        String[] parts = currentCsv.split(",");

                        if (parts.length == 2) {
                            // Have x1,y1 - add x2,y2
                            csvField.setText(String.format("%s,%s,%.2f,%.2f",
                                    parts[0].trim(), parts[1].trim(), coords[0], coords[1]));
                            logger.info("Added second position from stage: X={}, Y={}", coords[0], coords[1]);
                        } else if (parts.length == 4) {
                            // Have all four values - replace x2,y2
                            csvField.setText(String.format("%s,%s,%.2f,%.2f",
                                    parts[0].trim(), parts[1].trim(), coords[0], coords[1]));
                            logger.info("Updated second position from stage: X={}, Y={}", coords[0], coords[1]);
                        } else if (parts.length == 3) {
                            // Three values - show warning
                            UIFunctions.showAlertDialog("Invalid coordinate format: found 3 values, expected 2 or 4");
                        } else {
                            // 0 or 1 values - need first position first
                            UIFunctions.showAlertDialog("Please get the first stage position before setting the second position");
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            // Arrange buttons horizontally
            HBox csvButtonBox = new HBox(10, csvGetFirstPosButton, csvGetSecondPosButton);

            VBox csvContent = new VBox(5, csvField, csvInstructions, csvButtonBox);
            csvContent.setPadding(new Insets(10));
            csvTab.setContent(csvContent);

            // Center + Size Tab (replacing the Individual Fields tab) with colored label
            Tab centerSizeTab = new Tab();
            Label centerSizeTabLabel = new Label("Center + Size");
            centerSizeTabLabel.setStyle("-fx-text-fill: #f5a623;"); // Pale orange
            centerSizeTab.setGraphic(centerSizeTabLabel);
            GridPane centerGrid = new GridPane();
            centerGrid.setHgap(10);
            centerGrid.setVgap(10);
            centerGrid.setPadding(new Insets(10));

            // Center point fields
            TextField centerXField = new TextField();
            TextField centerYField = new TextField();
            centerXField.setPromptText("Center X");
            centerYField.setPromptText("Center Y");
            centerXField.setPrefWidth(150);
            centerYField.setPrefWidth(150);

            // Size fields
            TextField widthField = new TextField();
            TextField heightField = new TextField();
            widthField.setPromptText("Width");
            heightField.setPromptText("Height");
            widthField.setPrefWidth(150);
            heightField.setPrefWidth(150);

            // Layout the center + size controls
            centerGrid.add(new Label("Center Point:"), 0, 0);
            centerGrid.add(new Label("X:"), 1, 0);
            centerGrid.add(centerXField, 2, 0);
            centerGrid.add(new Label("Y:"), 3, 0);
            centerGrid.add(centerYField, 4, 0);

            centerGrid.add(new Label("Size:"), 0, 1);
            centerGrid.add(new Label("Width:"), 1, 1);
            centerGrid.add(widthField, 2, 1);
            centerGrid.add(new Label("Height:"), 3, 1);
            centerGrid.add(heightField, 4, 1);

            // Add "Get Current Position" button for center point
            Button centerGetPosButton = new Button("Get Current Position");
            centerGetPosButton.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        centerXField.setText(String.format("%.2f", coords[0]));
                        centerYField.setText(String.format("%.2f", coords[1]));
                        logger.info("Updated center position from stage: X={}, Y={}", coords[0], coords[1]);

                        // If width/height are empty, set defaults
                        if (widthField.getText().trim().isEmpty()) {
                            widthField.setText("2000");
                        }
                        if (heightField.getText().trim().isEmpty()) {
                            heightField.setText("2000");
                        }
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            // Add calculated bounds display
            Label calculatedBoundsLabel = new Label("Calculated bounds: ");
            calculatedBoundsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            centerGrid.add(centerGetPosButton, 0, 2, 2, 1);
            centerGrid.add(calculatedBoundsLabel, 0, 3, 5, 1);

            centerSizeTab.setContent(centerGrid);

            tabs.getTabs().addAll(csvTab, centerSizeTab);

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

            // 7) Field synchronization between tabs

            // Update listener for center+size to CSV conversion
            ChangeListener<String> centerSizeListener = (obs, old, newVal) -> {
                try {
                    String centerXStr = centerXField.getText().trim();
                    String centerYStr = centerYField.getText().trim();
                    String widthStr = widthField.getText().trim();
                    String heightStr = heightField.getText().trim();

                    if (!centerXStr.isEmpty() && !centerYStr.isEmpty() &&
                            !widthStr.isEmpty() && !heightStr.isEmpty()) {

                        double centerX = Double.parseDouble(centerXStr);
                        double centerY = Double.parseDouble(centerYStr);
                        double width = Double.parseDouble(widthStr);
                        double height = Double.parseDouble(heightStr);

                        // Calculate corners from center and size
                        // Assuming positive direction means x2 > x1 and y2 > y1
                        double x1 = centerX - width / 2;
                        double y1 = centerY - height / 2;
                        double x2 = centerX + width / 2;
                        double y2 = centerY + height / 2;

                        // Update CSV field
                        csvField.setText(String.format("%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2));

                        // Update calculated bounds display
                        calculatedBoundsLabel.setText(String.format(
                                "Calculated bounds: (%.2f, %.2f) to (%.2f, %.2f)",
                                x1, y1, x2, y2));
                    }
                } catch (NumberFormatException e) {
                    // Invalid input, ignore
                }
            };

            centerXField.textProperty().addListener(centerSizeListener);
            centerYField.textProperty().addListener(centerSizeListener);
            widthField.textProperty().addListener(centerSizeListener);
            heightField.textProperty().addListener(centerSizeListener);

            // Update center+size fields when CSV changes
            csvField.textProperty().addListener((obs, old, newVal) -> {
                if (tabs.getSelectionModel().getSelectedItem() == csvTab &&
                        newVal != null && !newVal.trim().isEmpty()) {
                    String[] parts = newVal.split(",");
                    if (parts.length == 4) {
                        try {
                            double x1 = Double.parseDouble(parts[0].trim());
                            double y1 = Double.parseDouble(parts[1].trim());
                            double x2 = Double.parseDouble(parts[2].trim());
                            double y2 = Double.parseDouble(parts[3].trim());

                            // Calculate center and size
                            double centerX = (x1 + x2) / 2;
                            double centerY = (y1 + y2) / 2;
                            double width = Math.abs(x2 - x1);
                            double height = Math.abs(y2 - y1);

                            // Temporarily remove listeners to avoid feedback loop
                            centerXField.textProperty().removeListener(centerSizeListener);
                            centerYField.textProperty().removeListener(centerSizeListener);
                            widthField.textProperty().removeListener(centerSizeListener);
                            heightField.textProperty().removeListener(centerSizeListener);

                            centerXField.setText(String.format("%.2f", centerX));
                            centerYField.setText(String.format("%.2f", centerY));
                            widthField.setText(String.format("%.2f", width));
                            heightField.setText(String.format("%.2f", height));

                            // Re-add listeners
                            centerXField.textProperty().addListener(centerSizeListener);
                            centerYField.textProperty().addListener(centerSizeListener);
                            widthField.textProperty().addListener(centerSizeListener);
                            heightField.textProperty().addListener(centerSizeListener);

                            calculatedBoundsLabel.setText(String.format(
                                    "Calculated bounds: (%.2f, %.2f) to (%.2f, %.2f)",
                                    x1, y1, x2, y2));
                        } catch (Exception e) {
                            // Invalid format, ignore
                        }
                    }
                }
            });

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