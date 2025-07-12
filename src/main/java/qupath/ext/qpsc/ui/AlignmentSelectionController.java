package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for alignment selection dialog.
 * Allows users to choose between using an existing microscope alignment
 * or creating a new one through the full alignment workflow.
 */
public class AlignmentSelectionController {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentSelectionController.class);

    /**
     * Result from the alignment selection dialog.
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
     * @param modality The imaging modality being used
     * @return CompletableFuture with the user's alignment choice, or empty if cancelled
     */
    public static CompletableFuture<AlignmentChoice> showDialog(QuPathGUI gui, String modality) {
        CompletableFuture<AlignmentChoice> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<AlignmentChoice> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Select Alignment Method");
            dialog.setHeaderText("Choose how to align the microscope with your image");
            dialog.setResizable(true);

            // Create the UI components
            VBox content = new VBox(15);
            content.setPadding(new Insets(20));
            content.setPrefWidth(500);

            // Check if macro image is available
            boolean hasMacroImage = MacroImageUtility.isMacroImageAvailable(gui);

            // Option 1: Use existing alignment
            RadioButton useExistingRadio = new RadioButton("Use existing microscope alignment");
            useExistingRadio.setWrapText(true);

            // Load available transforms
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            AffineTransformManager transformManager = null;
            List<AffineTransformManager.TransformPreset> availableTransforms = null;

            try {
                if (configPath != null && !configPath.isEmpty()) {
                    transformManager = new AffineTransformManager(new File(configPath).getParent());
                    availableTransforms = (List<AffineTransformManager.TransformPreset>) transformManager.getAllTransforms();
                }
            } catch (Exception e) {
                logger.warn("Could not load saved transforms: {}", e.getMessage());
            }

            // Transform selection combo box
            ComboBox<AffineTransformManager.TransformPreset> transformCombo = new ComboBox<>();
            transformCombo.setMaxWidth(Double.MAX_VALUE);
            transformCombo.setDisable(true);

            if (availableTransforms != null && !availableTransforms.isEmpty()) {
                transformCombo.getItems().addAll(availableTransforms);

                // Custom cell factory to show transform details
                transformCombo.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                            setText(String.format("%s - %s (%s)",
                                    item.getName(),
                                    item.getMicroscope(),
                                    sdf.format(item.getCreatedDate())));
                        }
                    }
                });

                transformCombo.setButtonCell(transformCombo.getCellFactory().call(null));

                // Select the most recently used or first available
                String savedName = QPPreferenceDialog.getSavedTransformName();
                if (savedName != null && !savedName.isEmpty()) {
                    transformCombo.getItems().stream()
                            .filter(t -> t.getName().equals(savedName))
                            .findFirst()
                            .ifPresent(transformCombo::setValue);
                }
                if (transformCombo.getValue() == null) {
                    transformCombo.setValue(transformCombo.getItems().get(0));
                }
            } else {
                useExistingRadio.setDisable(true);
                transformCombo.setPromptText("No saved alignments available");
            }

            // Refinement checkbox
            CheckBox refineCheckBox = new CheckBox("Refine alignment with single tile");
            refineCheckBox.setDisable(true);
            refineCheckBox.setTooltip(new Tooltip(
                    "After applying the saved alignment, allow fine-tuning with a single tile adjustment"));

            // Layout for existing alignment option
            VBox existingBox = new VBox(5);
            existingBox.setPadding(new Insets(0, 0, 0, 20));
            existingBox.getChildren().addAll(transformCombo, refineCheckBox);

            // Option 2: Create new alignment
            RadioButton createNewRadio = new RadioButton("Create new alignment");
            createNewRadio.setWrapText(true);

            Label newAlignmentLabel = new Label(
                    "This will guide you through:\n" +
                            "• Tissue detection\n" +
                            "• Tile selection and initial alignment\n" +
                            "• Automatic edge tile refinement");
            newAlignmentLabel.setWrapText(true);
            newAlignmentLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            newAlignmentLabel.setPadding(new Insets(5, 0, 0, 20));

            // Toggle group
            ToggleGroup alignmentGroup = new ToggleGroup();
            useExistingRadio.setToggleGroup(alignmentGroup);
            createNewRadio.setToggleGroup(alignmentGroup);

            // Enable/disable controls based on selection
            useExistingRadio.selectedProperty().addListener((obs, oldVal, newVal) -> {
                transformCombo.setDisable(!newVal);
                refineCheckBox.setDisable(!newVal);
            });

            // Default selection
            if (availableTransforms != null && !availableTransforms.isEmpty() && hasMacroImage) {
                useExistingRadio.setSelected(true);
            } else {
                createNewRadio.setSelected(true);
            }

            // Macro image warning if needed
            if (!hasMacroImage && useExistingRadio.isSelected()) {
                Label warningLabel = new Label(
                        "⚠ No macro image found. Using existing alignment requires a macro image.");
                warningLabel.setStyle("-fx-text-fill: #ff6600; -fx-font-weight: bold;");
                warningLabel.setWrapText(true);
                content.getChildren().add(warningLabel);
                createNewRadio.setSelected(true);
                useExistingRadio.setDisable(true);
            }

            // Add all components
            content.getChildren().addAll(
                    useExistingRadio,
                    existingBox,
                    new Separator(),
                    createNewRadio,
                    newAlignmentLabel
            );

            dialog.getDialogPane().setContent(content);

            // Buttons
            ButtonType okType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // Disable OK button if no valid selection
            Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.disableProperty().bind(
                    useExistingRadio.selectedProperty()
                            .and(transformCombo.valueProperty().isNull())
            );

            // Result converter
            dialog.setResultConverter(buttonType -> {
                if (buttonType == okType) {
                    if (useExistingRadio.isSelected()) {
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
            Optional<AlignmentChoice> result = dialog.showAndWait();
            if (result.isPresent()) {
                logger.info("User selected: {} alignment",
                        result.get().useExistingAlignment() ? "existing" : "new");
                future.complete(result.get());
            } else {
                logger.info("Alignment selection cancelled");
                future.cancel(true);
            }
        });

        return future;
    }
}