package qupath.ext.qpsc.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Consolidated acquisition controller for the Existing Image workflow.
 *
 * <p>This dialog combines all configuration options into a single scrollable interface:
 * <ul>
 *   <li>Variant banner (new project vs existing)</li>
 *   <li>Project &amp; Sample configuration</li>
 *   <li>Hardware configuration (modality, objective, detector)</li>
 *   <li>Alignment configuration (path selection, transform)</li>
 *   <li>Refinement options (none, single-tile, full manual)</li>
 *   <li>Advanced options (green box params, modality-specific)</li>
 *   <li>Acquisition preview (tile count, time, storage estimates)</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>TitledPane sections for organized configuration</li>
 *   <li>Real-time preview updates with debouncing</li>
 *   <li>Confidence-based recommendations</li>
 *   <li>Dynamic visibility based on selections</li>
 *   <li>Persistent preferences for all fields</li>
 * </ul>
 */
public class ExistingImageAcquisitionController {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageAcquisitionController.class);

    /** Debounce delay for preview updates in milliseconds */
    private static final long PREVIEW_DEBOUNCE_MS = 300;

    /** Confidence thresholds */
    private static final double HIGH_CONFIDENCE = 0.8;
    private static final double MEDIUM_CONFIDENCE = 0.5;

    /**
     * Refinement choice options.
     */
    public enum RefinementChoice {
        NONE("Proceed without refinement"),
        SINGLE_TILE("Single-tile refinement"),
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
     * Result record containing all user selections from the consolidated dialog.
     */
    public record ExistingImageAcquisitionConfig(
            // Sample
            String sampleName,
            File projectsFolder,
            boolean isExistingProject,

            // Hardware
            String modality,
            String objective,
            String detector,

            // Alignment
            boolean useExistingAlignment,
            AffineTransformManager.TransformPreset selectedTransform,
            double alignmentConfidence,

            // Refinement
            RefinementChoice refinementChoice,

            // Advanced (green box params handled by ExistingAlignmentPath)
            Map<String, Double> angleOverrides
    ) {}

    /**
     * Shows the consolidated acquisition dialog.
     *
     * @param defaultSampleName Default sample name (typically from current image)
     * @param annotations List of annotations to acquire (used for tile calculation)
     * @return CompletableFuture containing the result, or cancelled if user cancels
     */
    public static CompletableFuture<ExistingImageAcquisitionConfig> showDialog(
            String defaultSampleName, List<PathObject> annotations) {

        CompletableFuture<ExistingImageAcquisitionConfig> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                ConsolidatedDialogBuilder builder = new ConsolidatedDialogBuilder(
                        defaultSampleName, annotations);
                Optional<ExistingImageAcquisitionConfig> result = builder.buildAndShow();

                if (result.isPresent()) {
                    future.complete(result.get());
                } else {
                    future.cancel(true);
                }
            } catch (Exception e) {
                logger.error("Error showing consolidated acquisition dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Internal builder class that constructs and manages the consolidated dialog.
     */
    private static class ConsolidatedDialogBuilder {
        private final MicroscopeConfigManager configManager;
        private final boolean hasOpenProject;
        private final String existingProjectName;
        private final File existingProjectFolder;
        private final String defaultSampleName;
        private final List<PathObject> annotations;
        private final int annotationCount;

        // Transform data
        private List<AffineTransformManager.TransformPreset> availableTransforms;
        private AffineTransformManager transformManager;

        // UI Components - Banner
        private HBox variantBanner;

        // UI Components - Project Section
        private TextField sampleNameField;
        private Label sampleNameErrorLabel;
        private TextField folderField;

        // UI Components - Hardware Section
        private ComboBox<String> modalityBox;
        private ComboBox<String> objectiveBox;
        private ComboBox<String> detectorBox;

        // UI Components - Alignment Section
        private RadioButton useExistingRadio;
        private RadioButton manualAlignRadio;
        private ComboBox<AffineTransformManager.TransformPreset> transformCombo;
        private Label confidenceLabel;
        private VBox transformSelectionBox;

        // UI Components - Refinement Section
        private ToggleGroup refinementGroup;
        private RadioButton noRefineRadio;
        private RadioButton singleTileRadio;
        private RadioButton fullManualRadio;
        private Label refinementRecommendationLabel;
        private VBox refinementBox;

        // UI Components - Advanced Section
        private ModalityHandler.BoundingBoxUI modalityUI;
        private TitledPane modalityPane;
        private VBox modalityContent;

        // UI Components - Preview Section
        private Label previewAnnotationsLabel;
        private Label previewTilesLabel;
        private Label previewTimeLabel;
        private Label previewStorageLabel;

        // UI Components - Validation
        private VBox errorSummaryPanel;
        private VBox errorListBox;
        private final Map<String, String> validationErrors = new LinkedHashMap<>();

        // TitledPanes
        private TitledPane projectPane;
        private TitledPane hardwarePane;
        private TitledPane alignmentPane;
        private TitledPane refinementPane;
        private TitledPane advancedPane;

        // Debounce timer
        private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));

        // Dialog and button references
        private Dialog<ExistingImageAcquisitionConfig> dialog;
        private Button startButton;

        ConsolidatedDialogBuilder(String defaultSampleName, List<PathObject> annotations) {
            this.defaultSampleName = defaultSampleName;
            this.annotations = annotations != null ? annotations : new ArrayList<>();
            this.annotationCount = this.annotations.size();
            this.configManager = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            // Initialize transform manager
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            this.transformManager = new AffineTransformManager(new File(configPath).getParent());

            // Check if a project is already open
            QuPathGUI gui = QuPathGUI.getInstance();
            this.hasOpenProject = (gui != null && gui.getProject() != null);

            if (hasOpenProject) {
                File projectFile = gui.getProject().getPath().toFile();
                this.existingProjectFolder = projectFile.getParentFile();
                this.existingProjectName = existingProjectFolder.getName();
                logger.info("Found open project: {} in {}", existingProjectName,
                        existingProjectFolder.getParent());
            } else {
                this.existingProjectFolder = null;
                this.existingProjectName = null;
            }

            // Load available transforms
            String microscopeName = configManager.getMicroscopeName();
            this.availableTransforms = transformManager.getTransformsForMicroscope(microscopeName);
            logger.info("Found {} transforms for microscope '{}'",
                    availableTransforms.size(), microscopeName);
        }

        Optional<ExistingImageAcquisitionConfig> buildAndShow() {
            dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Acquire from Existing Image");
            dialog.setHeaderText("Configure acquisition from the current image");
            dialog.setResizable(true);

            // Add buttons
            ButtonType startType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(startType, cancelType);

            startButton = (Button) dialog.getDialogPane().lookupButton(startType);
            startButton.setDisable(true);

            // Build all sections
            createVariantBanner();
            createProjectSection();
            createHardwareSection();
            createAlignmentSection();
            createRefinementSection();
            createAdvancedSection();
            createPreviewPanel();
            createErrorSummaryPanel();

            // Setup listeners
            setupPreviewUpdateListeners();

            // Assemble main content
            VBox mainContent = new VBox(10,
                    variantBanner,
                    errorSummaryPanel,
                    projectPane,
                    hardwarePane,
                    alignmentPane,
                    refinementPane,
                    advancedPane,
                    createPreviewSection()
            );
            mainContent.setPadding(new Insets(0));

            // Wrap in scroll pane
            ScrollPane scrollPane = new ScrollPane(mainContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(650);
            scrollPane.setPrefViewportWidth(650);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(700);
            dialog.getDialogPane().setPrefHeight(750);

            // Initialize hardware dropdowns
            initializeHardwareSelections();

            // Initial validation
            Platform.runLater(this::validateAll);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button != startType) {
                    return null;
                }
                return createResult();
            });

            return dialog.showAndWait();
        }

        private void createVariantBanner() {
            variantBanner = new HBox(10);
            variantBanner.setPadding(new Insets(12, 15, 12, 15));
            variantBanner.setAlignment(Pos.CENTER_LEFT);

            Label iconLabel = new Label();
            Label textLabel = new Label();
            textLabel.setWrapText(true);

            if (hasOpenProject) {
                variantBanner.setStyle("-fx-background-color: #E8F5E9; -fx-border-color: #A5D6A7; " +
                        "-fx-border-width: 0 0 1 0;");
                iconLabel.setText("[+]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2E7D32;");
                textLabel.setText("Adding to existing project: " + existingProjectName);
                textLabel.setStyle("-fx-text-fill: #2E7D32;");
            } else {
                variantBanner.setStyle("-fx-background-color: #E3F2FD; -fx-border-color: #90CAF9; " +
                        "-fx-border-width: 0 0 1 0;");
                iconLabel.setText("[*]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1565C0;");
                textLabel.setText("Creating new project");
                textLabel.setStyle("-fx-text-fill: #1565C0;");
            }

            variantBanner.getChildren().addAll(iconLabel, textLabel);
        }

        private void createProjectSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Sample name field
            sampleNameField = new TextField(defaultSampleName);
            sampleNameField.setPromptText("e.g., MySample01");
            sampleNameField.setPrefWidth(300);

            sampleNameErrorLabel = new Label();
            sampleNameErrorLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 10px;");
            sampleNameErrorLabel.setVisible(false);

            sampleNameField.textProperty().addListener((obs, oldVal, newVal) -> {
                String error = SampleNameValidator.getValidationError(newVal);
                if (error != null) {
                    sampleNameErrorLabel.setText(error);
                    sampleNameErrorLabel.setVisible(true);
                    validationErrors.put("sampleName", error);
                } else {
                    sampleNameErrorLabel.setVisible(false);
                    validationErrors.remove("sampleName");
                }
                updateErrorSummary();
                triggerPreviewUpdate();
            });

            int row = 0;
            grid.add(new Label("Sample Name:"), 0, row);
            grid.add(sampleNameField, 1, row);
            row++;
            grid.add(new Label(""), 0, row);
            grid.add(sampleNameErrorLabel, 1, row);
            row++;

            if (!hasOpenProject) {
                // Projects folder
                folderField = new TextField(QPPreferenceDialog.getProjectsFolderProperty());
                folderField.setPrefWidth(250);

                Button browseBtn = new Button("Browse...");
                browseBtn.setOnAction(e -> {
                    Window win = dialog.getDialogPane().getScene().getWindow();
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle("Select Projects Folder");
                    File currentFolder = new File(folderField.getText());
                    if (currentFolder.exists() && currentFolder.isDirectory()) {
                        chooser.setInitialDirectory(currentFolder);
                    }
                    File chosen = chooser.showDialog(win);
                    if (chosen != null) {
                        folderField.setText(chosen.getAbsolutePath());
                    }
                });

                HBox folderBox = new HBox(5, folderField, browseBtn);
                HBox.setHgrow(folderField, Priority.ALWAYS);

                grid.add(new Label("Projects Folder:"), 0, row);
                grid.add(folderBox, 1, row);
            } else {
                folderField = new TextField(existingProjectFolder.getParent());
                folderField.setVisible(false);

                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(new Label("Project:"), 0, row);
                grid.add(projectLabel, 1, row);
            }

            projectPane = new TitledPane("PROJECT & SAMPLE", grid);
            projectPane.setExpanded(true);
            projectPane.setStyle("-fx-font-weight: bold;");
        }

        private void createHardwareSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            Set<String> modalities = configManager.getSection("modalities").keySet();

            modalityBox = new ComboBox<>(FXCollections.observableArrayList(modalities));
            modalityBox.setPrefWidth(200);

            objectiveBox = new ComboBox<>();
            objectiveBox.setPrefWidth(300);

            detectorBox = new ComboBox<>();
            detectorBox.setPrefWidth(300);

            // Cascading selection
            modalityBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    updateObjectivesForModality(newVal);
                    updateModalityUI(newVal);
                }
            });

            objectiveBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && modalityBox.getValue() != null) {
                    updateDetectorsForObjective(modalityBox.getValue(), newVal);
                }
            });

            // Preview updates on hardware change
            modalityBox.valueProperty().addListener((obs, old, newVal) -> triggerPreviewUpdate());
            objectiveBox.valueProperty().addListener((obs, old, newVal) -> triggerPreviewUpdate());
            detectorBox.valueProperty().addListener((obs, old, newVal) -> triggerPreviewUpdate());

            int row = 0;
            grid.add(new Label("Modality:"), 0, row);
            grid.add(modalityBox, 1, row);
            row++;

            grid.add(new Label("Objective:"), 0, row);
            grid.add(objectiveBox, 1, row);
            row++;

            grid.add(new Label("Detector:"), 0, row);
            grid.add(detectorBox, 1, row);

            hardwarePane = new TitledPane("HARDWARE CONFIGURATION", grid);
            hardwarePane.setExpanded(true);
            hardwarePane.setStyle("-fx-font-weight: bold;");
        }

        private void createAlignmentSection() {
            VBox content = new VBox(12);
            content.setPadding(new Insets(10));

            // Alignment method toggle
            ToggleGroup alignmentGroup = new ToggleGroup();
            useExistingRadio = new RadioButton("Use existing alignment");
            manualAlignRadio = new RadioButton("Perform manual alignment");
            useExistingRadio.setToggleGroup(alignmentGroup);
            manualAlignRadio.setToggleGroup(alignmentGroup);

            boolean hasTransforms = !availableTransforms.isEmpty();
            if (hasTransforms && PersistentPreferences.getUseExistingAlignment()) {
                useExistingRadio.setSelected(true);
            } else {
                manualAlignRadio.setSelected(true);
            }

            if (!hasTransforms) {
                useExistingRadio.setDisable(true);
            }

            // Quick comparison
            HBox comparisonRow = new HBox(20);
            comparisonRow.setAlignment(Pos.CENTER_LEFT);

            Label existingInfo = new Label("[T] 2-5 min | [A] +/- 20 um");
            existingInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            Label manualInfo = new Label("[T] 10-20 min | [A] +/- 5 um");
            manualInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            VBox existingOption = new VBox(3, useExistingRadio, existingInfo);
            VBox manualOption = new VBox(3, manualAlignRadio, manualInfo);

            comparisonRow.getChildren().addAll(existingOption, manualOption);

            // Transform selection (for existing alignment)
            transformSelectionBox = new VBox(8);
            transformSelectionBox.setPadding(new Insets(0, 0, 0, 20));

            transformCombo = new ComboBox<>();
            transformCombo.setPrefWidth(350);
            transformCombo.getItems().addAll(availableTransforms);

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

            // Restore last selection
            String lastSelected = PersistentPreferences.getLastSelectedTransform();
            if (!lastSelected.isEmpty()) {
                availableTransforms.stream()
                        .filter(t -> t.getName().equals(lastSelected))
                        .findFirst()
                        .ifPresent(transformCombo::setValue);
            } else if (!availableTransforms.isEmpty()) {
                transformCombo.getSelectionModel().selectFirst();
            }

            // Confidence label
            confidenceLabel = new Label();
            confidenceLabel.setStyle("-fx-font-size: 11px;");
            updateConfidenceLabel();

            transformCombo.valueProperty().addListener((obs, old, preset) -> {
                updateConfidenceLabel();
                updateRefinementRecommendation();
                if (preset != null) {
                    PersistentPreferences.setLastSelectedTransform(preset.getName());
                }
            });

            Label transformLabel = new Label("Select saved transform:");
            transformSelectionBox.getChildren().addAll(transformLabel, transformCombo, confidenceLabel);
            transformSelectionBox.setVisible(useExistingRadio.isSelected());
            transformSelectionBox.setManaged(useExistingRadio.isSelected());

            // Toggle visibility
            useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                transformSelectionBox.setVisible(selected);
                transformSelectionBox.setManaged(selected);
                refinementBox.setVisible(selected);
                refinementBox.setManaged(selected);
                PersistentPreferences.setUseExistingAlignment(selected);
                triggerPreviewUpdate();
            });

            // Recommendation
            Label recommendationLabel = new Label();
            recommendationLabel.setWrapText(true);
            recommendationLabel.setPadding(new Insets(8));
            recommendationLabel.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4; -fx-font-size: 11px;");

            if (hasTransforms) {
                recommendationLabel.setText("[i] Recommendation: Use Existing Alignment (found " +
                        availableTransforms.size() + " saved transform" +
                        (availableTransforms.size() > 1 ? "s" : "") + ")");
            } else {
                recommendationLabel.setText("[i] Manual alignment required (no saved transforms found)");
            }

            content.getChildren().addAll(comparisonRow, transformSelectionBox, recommendationLabel);

            alignmentPane = new TitledPane("ALIGNMENT CONFIGURATION", content);
            alignmentPane.setExpanded(true);
            alignmentPane.setStyle("-fx-font-weight: bold;");
        }

        private void createRefinementSection() {
            refinementBox = new VBox(10);
            refinementBox.setPadding(new Insets(10));

            refinementGroup = new ToggleGroup();

            noRefineRadio = new RadioButton("Proceed without refinement");
            noRefineRadio.setToggleGroup(refinementGroup);

            singleTileRadio = new RadioButton("Single-tile refinement (+2-3 min)");
            singleTileRadio.setToggleGroup(refinementGroup);

            fullManualRadio = new RadioButton("Full manual alignment (+10-15 min)");
            fullManualRadio.setToggleGroup(refinementGroup);

            // Add descriptions
            Label noRefineDesc = new Label("    Fastest - use alignment as-is. Accuracy: +/- 20 um");
            noRefineDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            Label singleTileDesc = new Label("    Verify position with one tile. Accuracy: +/- 5 um");
            singleTileDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            Label fullManualDesc = new Label("    Complete re-alignment. Accuracy: +/- 2 um");
            fullManualDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            // Recommendation label
            refinementRecommendationLabel = new Label();
            refinementRecommendationLabel.setWrapText(true);
            refinementRecommendationLabel.setPadding(new Insets(8));
            refinementRecommendationLabel.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4; -fx-font-size: 11px;");

            // Set default based on confidence
            updateRefinementRecommendation();

            refinementBox.getChildren().addAll(
                    noRefineRadio, noRefineDesc,
                    singleTileRadio, singleTileDesc,
                    fullManualRadio, fullManualDesc,
                    refinementRecommendationLabel
            );

            refinementPane = new TitledPane("REFINEMENT OPTIONS", refinementBox);
            refinementPane.setExpanded(true);
            refinementPane.setStyle("-fx-font-weight: bold;");

            // Initially visible only if using existing alignment
            refinementBox.setVisible(useExistingRadio.isSelected());
            refinementBox.setManaged(useExistingRadio.isSelected());
        }

        private void createAdvancedSection() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            // Note: Green box detection parameters are handled by ExistingAlignmentPath
            // which shows its own dialog during the alignment process

            // Modality-specific options (will be populated by updateModalityUI)
            modalityContent = new VBox(5);
            Label modalityPlaceholder = new Label("Select a modality to see specific options.");
            modalityPlaceholder.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
            modalityContent.getChildren().add(modalityPlaceholder);

            modalityPane = new TitledPane("Modality Options", modalityContent);
            modalityPane.setExpanded(false);

            content.getChildren().add(modalityPane);

            advancedPane = new TitledPane("ADVANCED OPTIONS", content);
            advancedPane.setExpanded(false);
            advancedPane.setStyle("-fx-font-weight: bold;");
        }

        private void createPreviewPanel() {
            if (annotationCount > 0) {
                previewAnnotationsLabel = new Label("Annotations: " + annotationCount + " regions selected");
            } else {
                previewAnnotationsLabel = new Label("Annotations: None (will be created after alignment)");
            }
            previewTilesLabel = new Label("Tiles: --");
            previewTimeLabel = new Label("Estimated Time: --");
            previewStorageLabel = new Label("Estimated Storage: --");
        }

        private TitledPane createPreviewSection() {
            VBox previewContent = new VBox(5,
                    previewAnnotationsLabel,
                    previewTilesLabel,
                    previewTimeLabel,
                    previewStorageLabel
            );
            previewContent.setPadding(new Insets(10));
            previewContent.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-width: 1px;");

            TitledPane previewPane = new TitledPane("ACQUISITION PREVIEW", previewContent);
            previewPane.setExpanded(true);
            previewPane.setCollapsible(false);
            previewPane.setStyle("-fx-font-weight: bold;");

            return previewPane;
        }

        private void createErrorSummaryPanel() {
            errorSummaryPanel = new VBox(5);
            errorSummaryPanel.setStyle("-fx-background-color: #fff3cd; -fx-border-color: #ffc107; " +
                    "-fx-border-width: 1px; -fx-padding: 10px;");
            errorSummaryPanel.setVisible(false);
            errorSummaryPanel.setManaged(false);

            Label errorTitle = new Label("Please fix the following errors:");
            errorTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #856404;");

            errorListBox = new VBox(3);
            errorSummaryPanel.getChildren().addAll(errorTitle, errorListBox);
        }

        private void initializeHardwareSelections() {
            String lastModality = PersistentPreferences.getLastModality();
            Set<String> modalities = configManager.getSection("modalities").keySet();

            if (!lastModality.isEmpty() && modalities.contains(lastModality)) {
                modalityBox.setValue(lastModality);
            } else if (!modalities.isEmpty()) {
                modalityBox.setValue(modalities.iterator().next());
            }
        }

        private void updateObjectivesForModality(String modality) {
            Set<String> objectiveIds = configManager.getAvailableObjectivesForModality(modality);
            Map<String, String> objectiveNames = configManager.getObjectiveFriendlyNames(objectiveIds);

            List<String> objectiveDisplayItems = objectiveIds.stream()
                    .map(id -> objectiveNames.get(id) + " (" + id + ")")
                    .sorted()
                    .collect(Collectors.toList());

            objectiveBox.getItems().clear();
            objectiveBox.getItems().addAll(objectiveDisplayItems);

            String lastObjective = PersistentPreferences.getLastObjective();
            boolean restored = false;
            if (!lastObjective.isEmpty()) {
                for (String displayItem : objectiveDisplayItems) {
                    if (extractIdFromDisplayString(displayItem).equals(lastObjective)) {
                        objectiveBox.setValue(displayItem);
                        restored = true;
                        break;
                    }
                }
            }

            if (!restored && !objectiveDisplayItems.isEmpty()) {
                objectiveBox.setValue(objectiveDisplayItems.get(0));
            }
        }

        private void updateDetectorsForObjective(String modality, String objectiveDisplay) {
            String objectiveId = extractIdFromDisplayString(objectiveDisplay);
            Set<String> detectorIds = configManager.getAvailableDetectorsForModalityObjective(modality, objectiveId);
            Map<String, String> detectorNames = configManager.getDetectorFriendlyNames(detectorIds);

            List<String> detectorDisplayItems = detectorIds.stream()
                    .map(id -> detectorNames.get(id) + " (" + id + ")")
                    .sorted()
                    .collect(Collectors.toList());

            detectorBox.getItems().clear();
            detectorBox.getItems().addAll(detectorDisplayItems);

            String lastDetector = PersistentPreferences.getLastDetector();
            boolean restored = false;
            if (!lastDetector.isEmpty()) {
                for (String displayItem : detectorDisplayItems) {
                    if (extractIdFromDisplayString(displayItem).equals(lastDetector)) {
                        detectorBox.setValue(displayItem);
                        restored = true;
                        break;
                    }
                }
            }

            if (!restored && !detectorDisplayItems.isEmpty()) {
                detectorBox.setValue(detectorDisplayItems.get(0));
            }
        }

        private void updateModalityUI(String modality) {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            Optional<ModalityHandler.BoundingBoxUI> uiOpt = handler.createBoundingBoxUI();

            // Clear existing content
            modalityContent.getChildren().clear();

            if (uiOpt.isPresent()) {
                modalityUI = uiOpt.get();
                // Add the modality-specific UI to the content pane
                modalityContent.getChildren().add(modalityUI.getNode());
                modalityPane.setExpanded(true);  // Auto-expand when there's content
                logger.debug("Added modality UI for: {}", modality);
            } else {
                modalityUI = null;
                // Show placeholder when no modality-specific UI
                Label placeholder = new Label("No specific options for " + modality);
                placeholder.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                modalityContent.getChildren().add(placeholder);
                modalityPane.setExpanded(false);
            }
        }

        private void updateConfidenceLabel() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            if (preset != null) {
                double confidence = AlignmentHelper.calculateConfidence(preset);
                String level = confidence >= HIGH_CONFIDENCE ? "HIGH" :
                        (confidence >= MEDIUM_CONFIDENCE ? "MEDIUM" : "LOW");
                String color = confidence >= HIGH_CONFIDENCE ? "#2E7D32" :
                        (confidence >= MEDIUM_CONFIDENCE ? "#F57F17" : "#C62828");
                confidenceLabel.setText(String.format("Confidence: %s (%.0f%%)", level, confidence * 100));
                confidenceLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            } else {
                confidenceLabel.setText("");
            }
        }

        private void updateRefinementRecommendation() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            double confidence = preset != null ? AlignmentHelper.calculateConfidence(preset) : 0.5;

            String recommendation;
            if (confidence >= HIGH_CONFIDENCE) {
                recommendation = "[i] Recommendation: Proceed without refinement (high confidence)";
                noRefineRadio.setSelected(true);
            } else if (confidence >= MEDIUM_CONFIDENCE) {
                recommendation = "[i] Recommendation: Single-tile refinement (medium confidence)";
                singleTileRadio.setSelected(true);
            } else {
                recommendation = "[i] Recommendation: Full manual alignment (low confidence)";
                fullManualRadio.setSelected(true);
            }

            refinementRecommendationLabel.setText(recommendation);
        }

        private void setupPreviewUpdateListeners() {
            previewDebounce.setOnFinished(event -> updatePreviewPanel());
        }

        private void triggerPreviewUpdate() {
            previewDebounce.playFromStart();
        }

        private void updatePreviewPanel() {
            try {
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                if (modality == null || objective == null || detector == null) {
                    previewTilesLabel.setText("Tiles: --");
                    previewTimeLabel.setText("Estimated Time: --");
                    previewStorageLabel.setText("Estimated Storage: --");
                    return;
                }

                // Get FOV in microns
                double[] fov = configManager.getModalityFOV(modality, objective, detector);
                if (fov == null) {
                    previewTilesLabel.setText("Tiles: -- (FOV not available)");
                    return;
                }
                double fovWidthMicrons = fov[0];
                double fovHeightMicrons = fov[1];

                // Get image pixel size to convert annotation bounds from pixels to microns
                double imagePixelSize = 1.0; // Default if no image
                QuPathGUI gui = QuPathGUI.getInstance();
                if (gui != null && gui.getImageData() != null) {
                    imagePixelSize = gui.getImageData().getServer()
                            .getPixelCalibration().getAveragedPixelSizeMicrons();
                }

                // Calculate actual tile count from annotation bounds
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                int totalTiles = calculateTileCount(fovWidthMicrons, fovHeightMicrons,
                        overlapPercent, imagePixelSize);

                // Get angle count from modality handler
                int angleCount = getAngleCountForModality(modality);
                int totalImages = totalTiles * angleCount;

                // Update labels with actual counts
                if (angleCount > 1) {
                    previewTilesLabel.setText(String.format("Images: %,d tiles x %d angles = %,d images",
                            totalTiles, angleCount, totalImages));
                } else {
                    previewTilesLabel.setText(String.format("Tiles: %,d", totalTiles));
                }

                // Time estimate: ~2 seconds per image
                double estimatedSeconds = totalImages * 2.0;
                String timeEstimate = formatTime(estimatedSeconds);
                previewTimeLabel.setText("Estimated Time: " + timeEstimate);

                // Storage estimate: ~4 MB per image
                double estimatedMB = totalImages * 4.0;
                String storageEstimate = formatStorage(estimatedMB);
                previewStorageLabel.setText("Estimated Storage: " + storageEstimate);

            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
            }
        }

        /**
         * Calculates the total tile count for all annotations.
         *
         * @param fovWidthMicrons FOV width in microns
         * @param fovHeightMicrons FOV height in microns
         * @param overlapPercent Overlap percentage
         * @param imagePixelSize Image pixel size in microns
         * @return Total number of tiles
         */
        private int calculateTileCount(double fovWidthMicrons, double fovHeightMicrons,
                                        double overlapPercent, double imagePixelSize) {
            if (annotations.isEmpty()) {
                return 0;
            }

            // Effective FOV size considering overlap
            double effectiveFovWidth = fovWidthMicrons * (1 - overlapPercent / 100.0);
            double effectiveFovHeight = fovHeightMicrons * (1 - overlapPercent / 100.0);

            int totalTiles = 0;
            for (PathObject ann : annotations) {
                if (ann.getROI() != null) {
                    // Get annotation bounds in pixels, convert to microns
                    double annWidthMicrons = ann.getROI().getBoundsWidth() * imagePixelSize;
                    double annHeightMicrons = ann.getROI().getBoundsHeight() * imagePixelSize;

                    // Add buffer (half FOV on each side) like TilingUtilities does
                    annWidthMicrons += fovWidthMicrons;
                    annHeightMicrons += fovHeightMicrons;

                    // Calculate tiles for this annotation
                    int tilesX = (int) Math.ceil(annWidthMicrons / effectiveFovWidth);
                    int tilesY = (int) Math.ceil(annHeightMicrons / effectiveFovHeight);
                    totalTiles += tilesX * tilesY;
                }
            }

            return totalTiles;
        }

        /**
         * Gets the estimated number of angles for a modality (for preview purposes).
         *
         * <p>This method provides a synchronous estimate for the preview panel.
         * It uses angle overrides if available from the modality UI, otherwise
         * returns a reasonable default based on the modality type.
         *
         * @param modality The modality name
         * @return Estimated number of angles (minimum 1)
         */
        private int getAngleCountForModality(String modality) {
            if (modality == null) return 1;

            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            if (handler != null) {
                // Use angle overrides if available from modalityUI (most accurate)
                if (modalityUI != null) {
                    Map<String, Double> overrides = modalityUI.getAngleOverrides();
                    if (overrides != null && !overrides.isEmpty()) {
                        return overrides.size();
                    }
                }
                // For PPM modalities, use default of 4 angles (typical configuration)
                // This avoids blocking the UI thread with async calls for a preview estimate
                if (modality.toLowerCase().startsWith("ppm")) {
                    return 4; // Typical PPM: 0, +7, -7, 90 degrees
                }
            }
            return 1;
        }

        private String formatTime(double seconds) {
            if (seconds < 60) {
                return String.format("~%.0f seconds", seconds);
            } else if (seconds < 3600) {
                return String.format("~%.0f minutes", seconds / 60.0);
            } else {
                return String.format("~%.1f hours", seconds / 3600.0);
            }
        }

        private String formatStorage(double megabytes) {
            if (megabytes < 1024) {
                return String.format("~%.0f MB", megabytes);
            } else {
                return String.format("~%.1f GB", megabytes / 1024.0);
            }
        }

        private void validateAll() {
            String sampleName = sampleNameField.getText().trim();
            String sampleError = SampleNameValidator.getValidationError(sampleName);
            if (sampleError != null) {
                validationErrors.put("sampleName", sampleError);
            } else {
                validationErrors.remove("sampleName");
            }

            if (modalityBox.getValue() == null) {
                validationErrors.put("modality", "Please select a modality");
            } else {
                validationErrors.remove("modality");
            }

            if (objectiveBox.getValue() == null) {
                validationErrors.put("objective", "Please select an objective");
            } else {
                validationErrors.remove("objective");
            }

            if (detectorBox.getValue() == null) {
                validationErrors.put("detector", "Please select a detector");
            } else {
                validationErrors.remove("detector");
            }

            if (useExistingRadio.isSelected() && transformCombo.getValue() == null) {
                validationErrors.put("transform", "Please select a transform");
            } else {
                validationErrors.remove("transform");
            }

            updateErrorSummary();
        }

        private void updateErrorSummary() {
            if (validationErrors.isEmpty()) {
                errorSummaryPanel.setVisible(false);
                errorSummaryPanel.setManaged(false);
                startButton.setDisable(false);
            } else {
                errorListBox.getChildren().clear();
                validationErrors.forEach((fieldId, errorMsg) -> {
                    Label errorLabel = new Label("- " + errorMsg);
                    errorLabel.setStyle("-fx-text-fill: #856404;");
                    errorListBox.getChildren().add(errorLabel);
                });

                errorSummaryPanel.setVisible(true);
                errorSummaryPanel.setManaged(true);
                startButton.setDisable(true);
            }
        }

        private ExistingImageAcquisitionConfig createResult() {
            try {
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = new File(folderField.getText().trim());
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                boolean useExisting = useExistingRadio.isSelected();
                AffineTransformManager.TransformPreset transform = useExisting ? transformCombo.getValue() : null;
                double confidence = transform != null ? AlignmentHelper.calculateConfidence(transform) : 0.0;

                RefinementChoice refinement = RefinementChoice.NONE;
                if (singleTileRadio.isSelected()) {
                    refinement = RefinementChoice.SINGLE_TILE;
                } else if (fullManualRadio.isSelected()) {
                    refinement = RefinementChoice.FULL_MANUAL;
                }

                // Angle overrides from modality UI (green box params handled by ExistingAlignmentPath)
                Map<String, Double> angleOverrides = null;
                if (modalityUI != null) {
                    angleOverrides = modalityUI.getAngleOverrides();
                }

                // Save preferences
                PersistentPreferences.setLastSampleName(sampleName);
                PersistentPreferences.setLastModality(modality);
                PersistentPreferences.setLastObjective(objective);
                PersistentPreferences.setLastDetector(detector);

                logger.info("Created acquisition config: sample={}, modality={}, useExisting={}, refinement={}",
                        sampleName, modality, useExisting, refinement);

                return new ExistingImageAcquisitionConfig(
                        sampleName, projectsFolder, hasOpenProject,
                        modality, objective, detector,
                        useExisting, transform, confidence,
                        refinement,
                        angleOverrides
                );

            } catch (Exception e) {
                logger.error("Error creating result", e);
                return null;
            }
        }

        private static String extractIdFromDisplayString(String displayString) {
            if (displayString == null) return null;

            int openParen = displayString.lastIndexOf('(');
            int closeParen = displayString.lastIndexOf(')');

            if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
                return displayString.substring(openParen + 1, closeParen);
            }

            return displayString;
        }
    }
}
