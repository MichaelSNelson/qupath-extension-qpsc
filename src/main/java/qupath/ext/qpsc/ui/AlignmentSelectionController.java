package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * Shows the alignment selection dialog.
     *
     * @param gui The QuPath GUI instance
     * @param modality The current imaging modality (e.g., "BF_10x")
     * @return CompletableFuture with the user's choice, or null if cancelled
     */
    public static CompletableFuture<AlignmentChoice> showDialog(QuPathGUI gui, String modality) {
        CompletableFuture<AlignmentChoice> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Initialize transform manager
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                AffineTransformManager transformManager = new AffineTransformManager(
                        new File(configPath).getParent());

                // Get current microscope name from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                String microscopeName = mgr.getString("microscope", "name");

                logger.info("Loading transforms for microscope: '{}' from directory: '{}'",
                        microscopeName, new File(configPath).getParent());

                // Create dialog
                Dialog<AlignmentChoice> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Alignment Selection");
                dialog.setHeaderText("Choose alignment method for " + modality);
                dialog.setResizable(true);

                // Create content
                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(500);

                // Radio buttons for choice
                ToggleGroup toggleGroup = new ToggleGroup();

                RadioButton useExistingRadio = new RadioButton("Use existing alignment");
                useExistingRadio.setToggleGroup(toggleGroup);

                RadioButton createNewRadio = new RadioButton("Create new alignment");
                createNewRadio.setToggleGroup(toggleGroup);
                createNewRadio.setSelected(true);

                // Transform selection area
                VBox transformSelectionBox = new VBox(10);
                transformSelectionBox.setPadding(new Insets(0, 0, 0, 30));

                ComboBox<AffineTransformManager.TransformPreset> transformCombo = new ComboBox<>();
                transformCombo.setPrefWidth(400);
                transformCombo.setDisable(true);

                // Load transforms for the current MICROSCOPE (not modality)
                List<AffineTransformManager.TransformPreset> availableTransforms =
                        transformManager.getTransformsForMicroscope(microscopeName);

                logger.info("Found {} transforms for microscope '{}' in file: {}",
                        availableTransforms.size(), microscopeName,
                        new File(configPath).getParent() + "/saved_transforms.json");

                // Debug: log all available transforms
                if (logger.isDebugEnabled()) {
                    transformManager.getAllTransforms().forEach(t ->
                            logger.debug("Available transform: '{}' for microscope: '{}'",
                                    t.getName(), t.getMicroscope()));
                }

                transformCombo.getItems().addAll(availableTransforms);

                // Custom cell factory to show transform details
                transformCombo.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(item.getName() + " - " + item.getMountingMethod());
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
                            setText(item.getName() + " - " + item.getMountingMethod());
                        }
                    }
                });

                if (!availableTransforms.isEmpty()) {
                    transformCombo.getSelectionModel().selectFirst();
                }

                // Transform details
                TextArea detailsArea = new TextArea();
                detailsArea.setEditable(false);
                detailsArea.setPrefRowCount(4);
                detailsArea.setWrapText(true);
                detailsArea.setDisable(true);

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

                // Refinement checkbox
                CheckBox refineCheckBox = new CheckBox("Refine alignment with single tile");
                refineCheckBox.setDisable(true);
                refineCheckBox.setTooltip(new Tooltip(
                        "After using the saved alignment, verify position with a single tile"
                ));

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
                createNewDescription.setStyle("-fx-font-weight: bold;");

                Label step1 = new Label("• Tissue detection or manual annotation creation");
                Label step2 = new Label("• Tile generation for the regions of interest");
                Label step3 = new Label("• Manual alignment of microscope stage to tiles");
                Label step4 = new Label("• Optional multi-tile refinement for accuracy");

                // Set explicit text color for visibility
                step1.setStyle("-fx-text-fill: #333333;");
                step2.setStyle("-fx-text-fill: #333333;");
                step3.setStyle("-fx-text-fill: #333333;");
                step4.setStyle("-fx-text-fill: #333333;");

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
                            return new AlignmentChoice(
                                    true,
                                    transformCombo.getValue(),
                                    refineCheckBox.isSelected()
                            );
                        } else {
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