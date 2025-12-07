package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
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
 *
 * <p><b>Note:</b> Refinement options are now handled by {@link RefinementSelectionController}
 * which provides a unified interface with confidence-based recommendations. This dialog
 * focuses solely on choosing the alignment method (existing vs manual).
 *
 * <p>Smart defaults are applied based on:
 * <ul>
 *   <li>Availability of saved transforms</li>
 *   <li>Confidence score of the best available transform</li>
 *   <li>User's previous preferences</li>
 * </ul>
 */
public class AlignmentSelectionController {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentSelectionController.class);

    /**
     * Result of the alignment selection dialog.
     *
     * @param useExistingAlignment Whether user selected to use existing alignment
     * @param selectedTransform The transform preset selected (null if manual)
     * @param confidence Confidence score of the selected alignment (0.0-1.0)
     * @param wasAutoSelected Whether the choice was auto-selected based on smart defaults
     */
    public record AlignmentChoice(
            boolean useExistingAlignment,
            AffineTransformManager.TransformPreset selectedTransform,
            double confidence,
            boolean wasAutoSelected
    ) {
        /**
         * Legacy constructor for backward compatibility.
         * @deprecated Use the full constructor with confidence and wasAutoSelected
         */
        @Deprecated
        public AlignmentChoice(boolean useExistingAlignment,
                               AffineTransformManager.TransformPreset selectedTransform,
                               boolean refinementRequested) {
            this(useExistingAlignment, selectedTransform, 0.7, false);
        }
    }
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
                content.setPrefWidth(600);

                // Header
                Label headerLabel = new Label("Choose Alignment Method");
                headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

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


                // Confidence label - shows calculated confidence for selected transform
                Label confidenceLabel = new Label();
                confidenceLabel.setStyle("-fx-font-size: 11px;");
                confidenceLabel.setDisable(!useExistingRadio.isSelected());

                // Update confidence when transform changes
                transformCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, preset) -> {
                    if (preset != null) {
                        double conf = AlignmentHelper.calculateConfidence(preset);
                        String level = conf >= 0.8 ? "HIGH" : (conf >= 0.5 ? "MEDIUM" : "LOW");
                        String color = conf >= 0.8 ? "#2E7D32" : (conf >= 0.5 ? "#F57F17" : "#C62828");
                        confidenceLabel.setText(String.format("Confidence: %s (%.0f%%)", level, conf * 100));
                        confidenceLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
                    } else {
                        confidenceLabel.setText("");
                    }
                });

                // Trigger initial confidence update
                if (transformCombo.getValue() != null) {
                    double conf = AlignmentHelper.calculateConfidence(transformCombo.getValue());
                    String level = conf >= 0.8 ? "HIGH" : (conf >= 0.5 ? "MEDIUM" : "LOW");
                    String color = conf >= 0.8 ? "#2E7D32" : (conf >= 0.5 ? "#F57F17" : "#C62828");
                    confidenceLabel.setText(String.format("Confidence: %s (%.0f%%)", level, conf * 100));
                    confidenceLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
                }

                // Note: Refinement options moved to RefinementSelectionController
                Label refinementNote = new Label("[i] Refinement options available in next step");
                refinementNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #666; -fx-font-style: italic;");
                refinementNote.setDisable(!useExistingRadio.isSelected());

                transformSelectionBox.getChildren().addAll(
                        new Label("Select saved transform:"),
                        transformCombo,
                        detailsArea,
                        confidenceLabel,
                        refinementNote
                );

                // Enable/disable based on radio selection
                useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                    transformCombo.setDisable(!selected);
                    detailsArea.setDisable(!selected);
                    confidenceLabel.setDisable(!selected);
                    refinementNote.setDisable(!selected);
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

                // Determine availability for requirements
                boolean hasTransforms = !availableTransforms.isEmpty();

                // Disable existing alignment option if no transforms
                if (!hasTransforms) {
                    useExistingRadio.setDisable(true);
                }

                // Create comparison cards
                java.util.List<RequirementItem> existingReqs = java.util.List.of(
                        new RequirementItem("Saved transform available", hasTransforms),
                        new RequirementItem("Macro image with green box", true)  // Assumed available if in this workflow
                );

                VBox existingCard = createPathComparisonCard(
                        "Use Existing Alignment",
                        "Quick Setup (2-5 min)",
                        "Good Accuracy (+/- 20 um)",
                        existingReqs,
                        "Repeated acquisitions, same scanner setup",
                        hasTransforms
                );

                java.util.List<RequirementItem> manualReqs = java.util.List.of(
                        new RequirementItem("Manual microscope control", true),
                        new RequirementItem("Multiple tile selections", true)
                );

                VBox manualCard = createPathComparisonCard(
                        "Perform Manual Alignment",
                        "Detailed Setup (10-20 min)",
                        "Excellent Accuracy (+/- 5 um)",
                        manualReqs,
                        "First-time setup, new slides, maximum precision",
                        true  // Always available
                );

                // Make cards clickable to select radio buttons
                existingCard.setOnMouseClicked(e -> {
                    if (!useExistingRadio.isDisabled()) {
                        useExistingRadio.setSelected(true);
                    }
                });
                existingCard.setCursor(hasTransforms ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);

                manualCard.setOnMouseClicked(e -> createNewRadio.setSelected(true));
                manualCard.setCursor(javafx.scene.Cursor.HAND);

                // Visual feedback for selected card
                useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                    if (selected) {
                        existingCard.setStyle("-fx-border-color: #1976D2; -fx-border-width: 2; -fx-border-radius: 4; -fx-background-color: #E3F2FD; -fx-background-radius: 4;");
                        manualCard.setStyle("-fx-border-color: #CCCCCC; -fx-border-radius: 4; -fx-background-color: #FAFAFA; -fx-background-radius: 4;");
                    }
                });

                createNewRadio.selectedProperty().addListener((obs, old, selected) -> {
                    if (selected) {
                        manualCard.setStyle("-fx-border-color: #1976D2; -fx-border-width: 2; -fx-border-radius: 4; -fx-background-color: #E3F2FD; -fx-background-radius: 4;");
                        if (hasTransforms) {
                            existingCard.setStyle("-fx-border-color: #CCCCCC; -fx-border-radius: 4; -fx-background-color: #FAFAFA; -fx-background-radius: 4;");
                        }
                    }
                });

                // Create recommendation label
                Label recommendationLabel = createRecommendationLabel(hasTransforms, availableTransforms.size());

                // Cards container with radio buttons beside them
                HBox existingRow = new HBox(10);
                existingRow.setAlignment(Pos.TOP_LEFT);
                existingRow.getChildren().addAll(useExistingRadio, existingCard);
                HBox.setHgrow(existingCard, Priority.ALWAYS);

                HBox manualRow = new HBox(10);
                manualRow.setAlignment(Pos.TOP_LEFT);
                manualRow.getChildren().addAll(createNewRadio, manualCard);
                HBox.setHgrow(manualCard, Priority.ALWAYS);

                // Assemble content with comparison cards
                content.getChildren().addAll(
                        headerLabel,
                        new Separator(),
                        existingRow,
                        transformSelectionBox,
                        new Separator(),
                        manualRow,
                        new Separator(),
                        recommendationLabel
                );

                // Apply initial card highlighting based on selection
                Platform.runLater(() -> {
                    if (useExistingRadio.isSelected()) {
                        existingCard.setStyle("-fx-border-color: #1976D2; -fx-border-width: 2; -fx-border-radius: 4; -fx-background-color: #E3F2FD; -fx-background-radius: 4;");
                    } else if (createNewRadio.isSelected()) {
                        manualCard.setStyle("-fx-border-color: #1976D2; -fx-border-width: 2; -fx-border-radius: 4; -fx-background-color: #E3F2FD; -fx-background-radius: 4;");
                    }
                });

                // Set up dialog buttons
                ButtonType okButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);

                dialog.getDialogPane().setContent(content);

                // Track if user changed from auto-selected default
                final boolean[] wasAutoSelected = {true};
                toggleGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
                    wasAutoSelected[0] = false;
                });
                transformCombo.valueProperty().addListener((obs, old, newVal) -> {
                    wasAutoSelected[0] = false;
                });

                // Convert result
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == okButton) {
                        if (useExistingRadio.isSelected() && transformCombo.getValue() != null) {
                            AffineTransformManager.TransformPreset selected = transformCombo.getValue();
                            double confidence = AlignmentHelper.calculateConfidence(selected);
                            logger.info("Dialog result: Use existing alignment - transform: '{}', confidence: {:.2f}, auto-selected: {}",
                                    selected.getName(), confidence, wasAutoSelected[0]);
                            return new AlignmentChoice(
                                    true,
                                    selected,
                                    confidence,
                                    wasAutoSelected[0]
                            );
                        } else {
                            logger.info("Dialog result: Manual alignment selected");
                            return new AlignmentChoice(false, null, 0.0, wasAutoSelected[0]);
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

    // ==================== Path Comparison Card Helpers ====================

    /**
     * Creates a styled comparison card for an alignment path option.
     *
     * @param title The card title (e.g., "Use Existing Alignment")
     * @param timeInfo Time estimate (e.g., "Quick Setup (2-5 minutes)")
     * @param accuracyInfo Accuracy estimate (e.g., "Good Accuracy (+/- 20 um)")
     * @param requirements List of requirements with availability status
     * @param bestFor Description of ideal use case
     * @param isAvailable Whether this option is currently available
     * @return VBox containing the styled card
     */
    private static VBox createPathComparisonCard(String title, String timeInfo, String accuracyInfo,
                                                  java.util.List<RequirementItem> requirements,
                                                  String bestFor, boolean isAvailable) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-border-color: #CCCCCC; -fx-border-radius: 4; -fx-background-color: #FAFAFA; -fx-background-radius: 4;");

        if (!isAvailable) {
            card.setStyle(card.getStyle() + " -fx-opacity: 0.6;");
        }

        // Title
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // Time and accuracy row with icons
        HBox metricsRow = new HBox(20);
        metricsRow.setAlignment(Pos.CENTER_LEFT);

        // Time info with clock icon
        Label timeLabel = new Label("[T] " + timeInfo);
        timeLabel.setStyle("-fx-font-size: 11px;");

        // Accuracy info with target icon
        Label accuracyLabel = new Label("[A] " + accuracyInfo);
        accuracyLabel.setStyle("-fx-font-size: 11px;");

        metricsRow.getChildren().addAll(timeLabel, accuracyLabel);

        // Requirements list
        VBox requirementsBox = new VBox(3);
        requirementsBox.setPadding(new Insets(5, 0, 5, 0));

        for (RequirementItem req : requirements) {
            Label reqLabel = new Label();
            if (req.met()) {
                reqLabel.setText("[OK] " + req.description());
                reqLabel.setStyle("-fx-text-fill: #2E7D32; -fx-font-size: 11px;");
            } else {
                reqLabel.setText("[ - ] " + req.description());
                reqLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
            }
            requirementsBox.getChildren().add(reqLabel);
        }

        // Best for description
        Label bestForLabel = new Label("Best for: " + bestFor);
        bestForLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic; -fx-text-fill: #555;");

        card.getChildren().addAll(titleLabel, metricsRow, requirementsBox, bestForLabel);

        return card;
    }

    /**
     * Creates a recommendation label based on available alignments.
     *
     * @param hasTransforms Whether saved transforms are available
     * @param transformCount Number of available transforms
     * @return Label with styled recommendation text
     */
    private static Label createRecommendationLabel(boolean hasTransforms, int transformCount) {
        Label label = new Label();
        label.setWrapText(true);
        label.setPadding(new Insets(8, 10, 8, 10));
        label.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4; -fx-font-size: 11px;");

        if (hasTransforms) {
            label.setText("[i] Recommendation: Use Existing Alignment (found " + transformCount +
                    " saved transform" + (transformCount > 1 ? "s" : "") + " for this microscope)");
            label.setStyle(label.getStyle() + " -fx-text-fill: #F57F17;");
        } else {
            label.setText("[i] Recommendation: Perform Manual Alignment (no saved transforms found)");
            label.setStyle(label.getStyle() + " -fx-text-fill: #E65100;");
        }

        return label;
    }

    /**
     * Record for requirement items in the comparison cards.
     */
    private record RequirementItem(String description, boolean met) {}
}