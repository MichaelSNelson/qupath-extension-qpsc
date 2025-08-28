package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for selecting between existing alignment transforms or creating new ones.
 * This dialog appears in the Existing Image workflow after sample setup.
 */
public class AlignmentSelectionController {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentSelectionController.class);

    /**
     * Result of the alignment selection dialog.
     */
    public record AlignmentChoice(
            boolean useExistingAlignment,
            AffineTransformManager.TransformPreset selectedTransform,
            boolean refinementRequested
    ) {}
    /**
     * Updates the transform information display based on the selected transform.
     * Extracted to a separate method to avoid code duplication between initial display
     * and selection change handling.
     *
     * @param transformInfo The TextArea to update
     * @param selectedTransform The selected transform preset, or null if none selected
     */
    private static void updateTransformInfoDisplay(TextArea transformInfo,
                                                   AffineTransformManager.TransformPreset selectedTransform) {
        if (selectedTransform != null) {
            StringBuilder info = new StringBuilder();
            info.append("Transform: ").append(selectedTransform.getName()).append("\n");
            info.append("Created: ").append(selectedTransform.getCreatedDate()).append("\n");

            if (selectedTransform.getNotes() != null && !selectedTransform.getNotes().isEmpty()) {
                info.append("Notes: ").append(selectedTransform.getNotes()).append("\n");
            }

            // Add transform matrix details
            var transform = selectedTransform.getTransform();
            info.append("\nTransform matrix:\n");
            double[] matrix = new double[6];
            transform.getMatrix(matrix);
            info.append(String.format("  [%.4f, %.4f, %.4f]\n", matrix[0], matrix[2], matrix[4]));
            info.append(String.format("  [%.4f, %.4f, %.4f]\n", matrix[1], matrix[3], matrix[5]));

            // Add scale information
            info.append(String.format("\nScale: X=%.4f, Y=%.4f µm/pixel\n",
                    transform.getScaleX(), transform.getScaleY()));

            // Add green box parameters if available
            if (selectedTransform.getGreenBoxParams() != null) {
                var params = selectedTransform.getGreenBoxParams();
                info.append("\nGreen Box Parameters:\n");
                info.append(String.format("  Green threshold: %.2f\n", params.greenThreshold));
                info.append(String.format("  Min saturation: %.2f\n", params.saturationMin));
                info.append(String.format("  Brightness: %.2f - %.2f\n",
                        params.brightnessMin, params.brightnessMax));
            }

            transformInfo.setText(info.toString());
        } else {
            transformInfo.setText("No transform selected");
        }
    }
    /**
     * Shows the alignment selection dialog.
     *
     * @param gui The QuPath GUI instance
     * @param modality The current imaging modality (e.g., "BF_10x")
     * @return CompletableFuture with the user's choice, or null if cancelled
     */
    public static CompletableFuture<AlignmentChoice> showDialog(QuPathGUI gui, String modality) {
        logger.info("Starting alignment selection dialog for modality: {}", modality);
        CompletableFuture<AlignmentChoice> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Initialize transform manager
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                logger.debug("Retrieved config path: {}", configPath);
                AffineTransformManager transformManager = new AffineTransformManager(
                        new File(configPath).getParent());

                // Get current microscope name from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                String microscopeName = mgr.getMicroscopeName();

                logger.info("Loading transforms for microscope: '{}' from directory: '{}'",
                        microscopeName, new File(configPath).getParent());

                // Create dialog
                Dialog<AlignmentChoice> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Alignment Selection");
                dialog.setHeaderText("Choose alignment method for " + modality);
                dialog.setResizable(true);
                logger.debug("Created dialog with title: {}", dialog.getTitle());

                // Create content
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(500);

// Radio buttons for choice
                ToggleGroup toggleGroup = new ToggleGroup();

                RadioButton useExistingRadio = new RadioButton("Use existing alignment");
                useExistingRadio.setToggleGroup(toggleGroup);

                RadioButton createNewRadio = new RadioButton("Perform manual sample alignment");
                createNewRadio.setToggleGroup(toggleGroup);

                // Transform selection area
                VBox transformSelectionBox = new VBox(10);
                transformSelectionBox.setPadding(new Insets(0, 0, 0, 30));

                ComboBox<AffineTransformManager.TransformPreset> transformCombo = new ComboBox<>();
                transformCombo.setPrefWidth(400);

                // Load transforms for the current MICROSCOPE (not modality)
                List<AffineTransformManager.TransformPreset> availableTransforms =
                        transformManager.getTransformsForMicroscope(microscopeName);

                logger.info("Found {} transforms for microscope '{}' in file: {}",
                        availableTransforms.size(), microscopeName,
                        new File(configPath).getParent() + "/saved_transforms.json");

                transformCombo.getItems().addAll(availableTransforms);

                // NOW we can check saved preference for alignment choice
                boolean useExisting = PersistentPreferences.getUseExistingAlignment();
                if (useExisting && !availableTransforms.isEmpty()) {
                    useExistingRadio.setSelected(true);
                } else {
                    createNewRadio.setSelected(true);
                }

                // Disable combo initially based on radio selection
                transformCombo.setDisable(!useExistingRadio.isSelected());

                // Try to restore last selected transform
                String lastSelectedName = PersistentPreferences.getLastSelectedTransform();
                if (!lastSelectedName.isEmpty()) {
                    availableTransforms.stream()
                            .filter(t -> t.getName().equals(lastSelectedName))
                            .findFirst()
                            .ifPresent(transformCombo::setValue);
                } else if (!availableTransforms.isEmpty()) {
                    transformCombo.getSelectionModel().selectFirst();
                }

                // Save selection when changed
                transformCombo.valueProperty().addListener((obs, old, newVal) -> {
                    if (newVal != null) {
                        PersistentPreferences.setLastSelectedTransform(newVal.getName());
                        logger.info("User selected transform: '{}' (mounting method: {})",
                                newVal.getName(), newVal.getMountingMethod());
                    }
                });

                // Custom cell factory remains the same...
                transformCombo.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(item.getName() + " (" + item.getMountingMethod() + ")");
                        }
                    }
                });

                transformCombo.setButtonCell(new ListCell<>() {
                    @Override
                    protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(item.getName() + " (" + item.getMountingMethod() + ")");
                        }
                    }
                });

                // Transform details area
                TextArea detailsArea = new TextArea();
                detailsArea.setPrefRowCount(3);
                detailsArea.setEditable(false);
                detailsArea.setWrapText(true);
                detailsArea.setDisable(!useExistingRadio.isSelected());

                // Update details when selection changes
                transformCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, preset) -> {
                    if (preset != null) {
                        detailsArea.setText(String.format(
                                "Microscope: %s\nMounting: %s\nCreated: %s\nNotes: %s",
                                preset.getMicroscope(),
                                preset.getMountingMethod(),
                                preset.getCreatedDate(),
                                preset.getNotes()
                        ));
                    } else {
                        detailsArea.clear();
                    }
                });

// RIGHT AFTER the listener above, add this line to populate the initial selection:
// Trigger initial update for the already selected item
                if (transformCombo.getValue() != null) {
                    detailsArea.setText(String.format(
                            "Microscope: %s\nMounting: %s\nCreated: %s\nNotes: %s",
                            transformCombo.getValue().getMicroscope(),
                            transformCombo.getValue().getMountingMethod(),
                            transformCombo.getValue().getCreatedDate(),
                            transformCombo.getValue().getNotes()
                    ));
                }


                // Refinement checkbox
                CheckBox refineCheckBox = new CheckBox("Refine alignment with single tile");
                refineCheckBox.setDisable(!useExistingRadio.isSelected());
                refineCheckBox.setTooltip(new Tooltip(
                        "After using the saved alignment, verify position with a single tile"
                ));

                // Load saved refinement preference
                refineCheckBox.setSelected(PersistentPreferences.getRefineAlignment());

                // Save when changed
                refineCheckBox.selectedProperty().addListener((obs, old, selected) -> {
                    PersistentPreferences.setRefineAlignment(selected);
                    logger.info("Refinement checkbox changed to: {}", selected);
                });

                transformSelectionBox.getChildren().addAll(
                        new Label("Select saved transform:"),
                        transformCombo,
                        detailsArea,
                        refineCheckBox
                );

                // Enable/disable based on radio selection
                useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                    transformCombo.setDisable(!selected);
                    detailsArea.setDisable(!selected);
                    refineCheckBox.setDisable(!selected);
                    // Save preference when changed
                    PersistentPreferences.setUseExistingAlignment(selected);

                });

                createNewRadio.selectedProperty().addListener((obs, old, selected) -> {
                    // Save preference when changed
                    if (selected) {
                        PersistentPreferences.setUseExistingAlignment(false);
                        logger.info("User selected: Create new alignment");
                    }
                });

                // Info label
                Label infoLabel = new Label();
                infoLabel.setWrapText(true);
                infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

                if (availableTransforms.isEmpty()) {
                    infoLabel.setText(
                            "No saved alignments found for microscope '" + microscopeName + "'.\n" +
                                    "You'll need to create a new alignment using the manual process."
                    );
                    useExistingRadio.setDisable(true);
                } else {
                    infoLabel.setText(
                            "Found " + availableTransforms.size() + " saved alignment(s) for this microscope.\n" +
                                    "Using an existing alignment will apply the saved transform and detect the green box location."
                    );
                }

                // Create new alignment description with better styling
                VBox createNewBox = new VBox(5);
                createNewBox.setPadding(new Insets(0, 0, 0, 30));

                Label createNewDescription = new Label("This will guide you through:");
                createNewDescription.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-text-base-color;");

                Label step1 = new Label("• Tissue detection or manual annotation creation");
                Label step2 = new Label("• Tile generation for the regions of interest");
                Label step3 = new Label("• Manual alignment of microscope stage to tiles");
                Label step4 = new Label("• Optional multi-tile refinement for accuracy");

                // Use theme-aware text color
                step1.setStyle("-fx-text-fill: -fx-text-base-color;");
                step2.setStyle("-fx-text-fill: -fx-text-base-color;");
                step3.setStyle("-fx-text-fill: -fx-text-base-color;");
                step4.setStyle("-fx-text-fill: -fx-text-base-color;");

                createNewBox.getChildren().addAll(createNewDescription, step1, step2, step3, step4);

                // Assemble content
                content.getChildren().addAll(
                        new Label("How would you like to align the microscope to the image?"),
                        new Separator(),
                        useExistingRadio,
                        transformSelectionBox,
                        new Separator(),
                        createNewRadio,
                        createNewBox,
                        new Separator(),
                        infoLabel
                );

                // Set up dialog buttons
                ButtonType okButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

                dialog.getDialogPane().setContent(content);

                // Convert result
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == okButton) {
                        if (useExistingRadio.isSelected() && transformCombo.getValue() != null) {
                            logger.info("Dialog result: Use existing alignment - transform: '{}', refinement: {}",
                                    transformCombo.getValue() != null ? transformCombo.getValue().getName() : "null",
                                    refineCheckBox.isSelected());
                            return new AlignmentChoice(
                                    true,
                                    transformCombo.getValue(),
                                    refineCheckBox.isSelected()
                            );
                        } else {
                            logger.info("Dialog closed without result");
                            return new AlignmentChoice(false, null, false);
                        }
                    }
                    return null;
                });

                // Show dialog
                dialog.showAndWait().ifPresent(future::complete);
                if (!future.isDone()) {
                    future.complete(null);
                }

            } catch (Exception e) {
                logger.error("Error showing alignment selection dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}