package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.lib.gui.QuPathGUI;

import java.util.concurrent.CompletableFuture;

/**
 * Controller for selecting alignment refinement options.
 *
 * <p>This dialog consolidates all refinement-related choices into a single
 * unified interface with confidence-based recommendations. It replaces:
 * <ul>
 *   <li>Refinement checkbox in AlignmentSelectionController</li>
 *   <li>3-button dialog in AlignmentHelper.checkForSlideAlignment()</li>
 *   <li>Scattered refinement options in SingleTileRefinement</li>
 * </ul>
 *
 * <p>The dialog shows alignment confidence and provides intelligent
 * recommendations based on the confidence level:
 * <ul>
 *   <li>High confidence ({@literal >}0.8): Recommend proceeding without refinement</li>
 *   <li>Medium confidence (0.5-0.8): Recommend single-tile refinement</li>
 *   <li>Low confidence ({@literal <}0.5): Recommend full manual alignment</li>
 * </ul>
 */
public class RefinementSelectionController {
    private static final Logger logger = LoggerFactory.getLogger(RefinementSelectionController.class);

    // Confidence thresholds for recommendations
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.8;
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.5;

    /**
     * Enumeration of refinement choices available to the user.
     */
    public enum RefinementChoice {
        /** Proceed directly without any refinement */
        NONE("Proceed without refinement"),
        /** Verify position with a single tile acquisition */
        SINGLE_TILE("Single-tile refinement"),
        /** Start completely fresh with full manual alignment */
        FULL_MANUAL("Full manual alignment");

        private final String displayName;

        RefinementChoice(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Result from the refinement selection dialog.
     *
     * @param choice The user's selected refinement option
     * @param wasAutoSelected Whether the choice was auto-selected based on confidence
     */
    public record RefinementResult(
            RefinementChoice choice,
            boolean wasAutoSelected
    ) {}

    /**
     * Information about the current alignment state.
     *
     * @param confidence Confidence score (0.0-1.0) of the alignment
     * @param source Description of alignment source (e.g., "Slide-specific", "General")
     * @param transformName Name of the transform being used
     */
    public record AlignmentInfo(
            double confidence,
            String source,
            String transformName
    ) {
        /**
         * Creates alignment info with default medium confidence.
         */
        public static AlignmentInfo withDefaults(String source, String transformName) {
            return new AlignmentInfo(0.7, source, transformName);
        }

        /**
         * Returns the confidence level category.
         */
        public String getConfidenceLevel() {
            if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
                return "HIGH";
            } else if (confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
                return "MEDIUM";
            } else {
                return "LOW";
            }
        }
    }

    /**
     * Shows the refinement selection dialog.
     *
     * @param gui The QuPath GUI instance
     * @param alignmentInfo Information about the current alignment
     * @return CompletableFuture with the user's choice, or null if cancelled
     */
    public static CompletableFuture<RefinementResult> showDialog(
            QuPathGUI gui,
            AlignmentInfo alignmentInfo) {

        logger.info("Showing refinement selection dialog - confidence: {}, source: {}",
                String.format("%.2f", alignmentInfo.confidence()), alignmentInfo.source());

        CompletableFuture<RefinementResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Dialog<RefinementResult> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setTitle("Alignment Refinement");
                dialog.setHeaderText("Choose refinement level for alignment");
                dialog.setResizable(true);

                VBox content = new VBox(15);
                content.setPadding(new Insets(20));
                content.setPrefWidth(500);

                // Alignment info section
                VBox infoSection = createAlignmentInfoSection(alignmentInfo);

                // Question header
                Label questionLabel = new Label("How would you like to proceed?");
                questionLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

                // Radio button options
                ToggleGroup toggleGroup = new ToggleGroup();

                VBox optionsBox = new VBox(12);
                optionsBox.setPadding(new Insets(0, 0, 0, 10));

                // Option 1: No refinement
                RadioButton noRefineRadio = new RadioButton();
                VBox noRefineOption = createOptionCard(
                        noRefineRadio,
                        "Proceed without refinement",
                        "Fastest option - use existing alignment as-is",
                        "Accuracy: +/- 20 um | Time: No additional time",
                        toggleGroup
                );

                // Option 2: Single-tile refinement
                RadioButton singleTileRadio = new RadioButton();
                VBox singleTileOption = createOptionCard(
                        singleTileRadio,
                        "Single-tile refinement",
                        "Acquire one tile to verify and adjust position",
                        "Accuracy: +/- 5 um | Time: +2-3 minutes",
                        toggleGroup
                );

                // Option 3: Full manual alignment
                RadioButton fullManualRadio = new RadioButton();
                VBox fullManualOption = createOptionCard(
                        fullManualRadio,
                        "Start manual alignment over",
                        "Complete re-alignment with multiple points",
                        "Accuracy: +/- 2 um | Time: +10-15 minutes",
                        toggleGroup
                );

                optionsBox.getChildren().addAll(noRefineOption, singleTileOption, fullManualOption);

                // Auto-select based on confidence
                RefinementChoice recommendedChoice = getRecommendedChoice(alignmentInfo.confidence());
                boolean autoSelected = true;

                switch (recommendedChoice) {
                    case NONE -> noRefineRadio.setSelected(true);
                    case SINGLE_TILE -> singleTileRadio.setSelected(true);
                    case FULL_MANUAL -> fullManualRadio.setSelected(true);
                }

                // Try to restore last user preference (overrides auto-selection)
                String lastChoice = PersistentPreferences.getLastRefinementChoice();
                if (!lastChoice.isEmpty()) {
                    try {
                        RefinementChoice savedChoice = RefinementChoice.valueOf(lastChoice);
                        switch (savedChoice) {
                            case NONE -> noRefineRadio.setSelected(true);
                            case SINGLE_TILE -> singleTileRadio.setSelected(true);
                            case FULL_MANUAL -> fullManualRadio.setSelected(true);
                        }
                        autoSelected = false;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Invalid saved refinement choice: {}", lastChoice);
                    }
                }

                // Recommendation label
                Label recommendationLabel = createRecommendationLabel(alignmentInfo.confidence(), recommendedChoice);

                content.getChildren().addAll(
                        infoSection,
                        new Separator(),
                        questionLabel,
                        optionsBox,
                        new Separator(),
                        recommendationLabel
                );

                // Dialog buttons
                ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
                ButtonType backButton = new ButtonType("Back", ButtonBar.ButtonData.BACK_PREVIOUS);
                dialog.getDialogPane().getButtonTypes().addAll(continueButton, backButton);

                dialog.getDialogPane().setContent(content);

                // Track if user changes selection (no longer auto-selected)
                final boolean[] wasAutoSelected = {autoSelected};
                toggleGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
                    wasAutoSelected[0] = false;
                });

                // Result converter
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == continueButton) {
                        RefinementChoice choice;
                        if (noRefineRadio.isSelected()) {
                            choice = RefinementChoice.NONE;
                        } else if (singleTileRadio.isSelected()) {
                            choice = RefinementChoice.SINGLE_TILE;
                        } else {
                            choice = RefinementChoice.FULL_MANUAL;
                        }

                        // Save preference
                        PersistentPreferences.setLastRefinementChoice(choice.name());
                        logger.info("User selected refinement: {} (auto-selected: {})",
                                choice, wasAutoSelected[0]);

                        return new RefinementResult(choice, wasAutoSelected[0]);
                    } else if (buttonType == backButton) {
                        logger.info("User pressed Back - returning to previous step");
                        return null; // Signals to go back
                    }
                    return null;
                });

                dialog.showAndWait().ifPresent(future::complete);
                if (!future.isDone()) {
                    future.complete(null);
                }

            } catch (Exception e) {
                logger.error("Error showing refinement selection dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Creates the alignment info section showing confidence and source.
     */
    private static VBox createAlignmentInfoSection(AlignmentInfo info) {
        VBox section = new VBox(8);
        section.setPadding(new Insets(12));
        section.setStyle("-fx-background-color: #F5F5F5; -fx-background-radius: 4;");

        // Confidence display with color coding
        HBox confidenceRow = new HBox(10);
        confidenceRow.setAlignment(Pos.CENTER_LEFT);

        Label confidenceLabel = new Label("Alignment Confidence:");
        confidenceLabel.setStyle("-fx-font-weight: bold;");

        String confidenceLevel = info.getConfidenceLevel();
        Label confidenceValue = new Label(
                String.format("%s (%.0f%%)", confidenceLevel, info.confidence() * 100));

        // Color code based on confidence
        String confidenceColor;
        if (info.confidence() >= HIGH_CONFIDENCE_THRESHOLD) {
            confidenceColor = "#2E7D32"; // Green
        } else if (info.confidence() >= MEDIUM_CONFIDENCE_THRESHOLD) {
            confidenceColor = "#F57F17"; // Amber
        } else {
            confidenceColor = "#C62828"; // Red
        }
        confidenceValue.setStyle("-fx-font-weight: bold; -fx-text-fill: " + confidenceColor + ";");

        confidenceRow.getChildren().addAll(confidenceLabel, confidenceValue);

        // Source info
        Label sourceLabel = new Label("Source: " + info.source());
        sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        // Transform name if available
        Label transformLabel = new Label("Transform: " + info.transformName());
        transformLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        section.getChildren().addAll(confidenceRow, sourceLabel, transformLabel);

        return section;
    }

    /**
     * Creates an option card with radio button, title, description, and metrics.
     */
    private static VBox createOptionCard(RadioButton radio, String title, String description,
                                          String metrics, ToggleGroup group) {
        radio.setToggleGroup(group);

        VBox card = new VBox(4);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color: #FAFAFA; -fx-background-radius: 4; " +
                "-fx-border-color: #E0E0E0; -fx-border-radius: 4;");

        // Title row with radio button
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

        titleRow.getChildren().addAll(radio, titleLabel);

        // Description
        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        descLabel.setPadding(new Insets(0, 0, 0, 24)); // Indent under radio

        // Metrics
        Label metricsLabel = new Label(metrics);
        metricsLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        metricsLabel.setPadding(new Insets(0, 0, 0, 24)); // Indent under radio

        card.getChildren().addAll(titleRow, descLabel, metricsLabel);

        // Make entire card clickable
        card.setOnMouseClicked(e -> radio.setSelected(true));
        card.setCursor(javafx.scene.Cursor.HAND);

        // Visual feedback for selection
        radio.selectedProperty().addListener((obs, old, selected) -> {
            if (selected) {
                card.setStyle("-fx-background-color: #E3F2FD; -fx-background-radius: 4; " +
                        "-fx-border-color: #1976D2; -fx-border-width: 2; -fx-border-radius: 4;");
            } else {
                card.setStyle("-fx-background-color: #FAFAFA; -fx-background-radius: 4; " +
                        "-fx-border-color: #E0E0E0; -fx-border-radius: 4;");
            }
        });

        return card;
    }

    /**
     * Gets the recommended refinement choice based on confidence.
     */
    private static RefinementChoice getRecommendedChoice(double confidence) {
        if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            return RefinementChoice.NONE;
        } else if (confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            return RefinementChoice.SINGLE_TILE;
        } else {
            return RefinementChoice.FULL_MANUAL;
        }
    }

    /**
     * Creates a recommendation label with explanation.
     */
    private static Label createRecommendationLabel(double confidence, RefinementChoice recommended) {
        Label label = new Label();
        label.setWrapText(true);
        label.setPadding(new Insets(10));
        label.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4; -fx-font-size: 11px;");

        String reason;
        if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            reason = "High confidence alignments rarely need refinement";
            label.setStyle(label.getStyle() + " -fx-text-fill: #2E7D32;");
        } else if (confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            reason = "Medium confidence - single-tile verification recommended";
            label.setStyle(label.getStyle() + " -fx-text-fill: #F57F17;");
        } else {
            reason = "Low confidence - full re-alignment recommended for accuracy";
            label.setStyle(label.getStyle() + " -fx-text-fill: #C62828;");
        }

        label.setText("[i] Recommendation: " + recommended.getDisplayName() + "\n    (" + reason + ")");

        return label;
    }
}
