package qupath.ext.qpsc.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Unified acquisition controller that consolidates sample setup, hardware selection,
 * and bounding box configuration into a single dialog with collapsible sections.
 * <p>
 * This replaces the multi-dialog workflow with a single-screen experience where users
 * can see all configuration at once while still having the option to collapse sections
 * they don't need to modify.
 * <p>
 * Key features:
 * <ul>
 *   <li>TitledPane sections for Project, Hardware, Region, and Advanced options</li>
 *   <li>Real-time acquisition preview showing tile count, time estimate, storage estimate</li>
 *   <li>Debounced preview updates (300ms delay) for responsive feedback</li>
 *   <li>Persistent preferences for all fields and section expansion states</li>
 *   <li>Validation with clickable error summary</li>
 * </ul>
 */
public class UnifiedAcquisitionController {
    private static final Logger logger = LoggerFactory.getLogger(UnifiedAcquisitionController.class);

    /** Debounce delay for preview updates in milliseconds */
    private static final long PREVIEW_DEBOUNCE_MS = 300;

    /**
     * Result record containing all user selections from the unified dialog.
     */
    public record UnifiedAcquisitionResult(
            String sampleName,
            File projectsFolder,
            String modality,
            String objective,
            String detector,
            double x1, double y1,
            double x2, double y2,
            Map<String, Double> angleOverrides
    ) {}

    /**
     * Shows the unified acquisition dialog.
     *
     * @return CompletableFuture containing the result, or cancelled if user cancels
     */
    public static CompletableFuture<UnifiedAcquisitionResult> showDialog() {
        CompletableFuture<UnifiedAcquisitionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                UnifiedDialogBuilder builder = new UnifiedDialogBuilder();
                Optional<UnifiedAcquisitionResult> result = builder.buildAndShow();

                if (result.isPresent()) {
                    future.complete(result.get());
                } else {
                    future.cancel(true);
                }
            } catch (Exception e) {
                logger.error("Error showing unified acquisition dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Internal builder class that constructs and manages the unified dialog.
     */
    private static class UnifiedDialogBuilder {
        private final ResourceBundle res;
        private final MicroscopeConfigManager configManager;
        private final boolean hasOpenProject;
        private final String existingProjectName;
        private final File existingProjectFolder;

        // UI Components - Project Section
        private TextField sampleNameField;
        private Label sampleNameErrorLabel;
        private TextField folderField;

        // UI Components - Hardware Section
        private ComboBox<String> modalityBox;
        private ComboBox<String> objectiveBox;
        private ComboBox<String> detectorBox;

        // UI Components - Region Section
        private TextField startXField;
        private TextField startYField;
        private TextField widthField;
        private TextField heightField;
        private Label calculatedBoundsLabel;

        // UI Components - Preview Section
        private Label previewRegionLabel;
        private Label previewFOVLabel;
        private Label previewTileGridLabel;
        private Label previewAnglesLabel;
        private Label previewTotalImagesLabel;
        private Label previewTimeLabel;
        private Label previewStorageLabel;
        private Label previewErrorLabel;

        // UI Components - Modality Section
        private ModalityHandler.BoundingBoxUI modalityUI;

        // UI Components - Validation
        private VBox errorSummaryPanel;
        private VBox errorListBox;
        private final Map<String, String> validationErrors = new LinkedHashMap<>();

        // TitledPanes for section management
        private TitledPane projectPane;
        private TitledPane hardwarePane;
        private TitledPane regionPane;
        private TitledPane advancedPane;

        // Debounce timer for preview updates
        private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));

        // Dialog and OK button references
        private Dialog<UnifiedAcquisitionResult> dialog;
        private Button okButton;

        UnifiedDialogBuilder() {
            this.res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            this.configManager = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

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
        }

        Optional<UnifiedAcquisitionResult> buildAndShow() {
            dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Bounded Acquisition");
            dialog.setHeaderText("Configure and start a new bounded acquisition.\n" +
                    "All settings are visible below - expand sections as needed.");
            dialog.setResizable(true);

            // Add buttons
            ButtonType okType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.setDisable(true); // Start disabled until validation passes

            // Build all sections
            createProjectSection();
            createHardwareSection();
            createRegionSection();
            createPreviewPanel();
            createAdvancedSection();
            createErrorSummaryPanel();

            // Setup debounced preview updates
            setupPreviewUpdateListeners();

            // Assemble the main content
            VBox mainContent = new VBox(10,
                    errorSummaryPanel,
                    projectPane,
                    hardwarePane,
                    regionPane,
                    advancedPane,
                    createPreviewSection()
            );
            mainContent.setPadding(new Insets(15));

            // Wrap in scroll pane for smaller screens
            ScrollPane scrollPane = new ScrollPane(mainContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(600);
            scrollPane.setPrefViewportWidth(700);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(750);
            dialog.getDialogPane().setPrefHeight(700);

            // Initialize hardware dropdowns
            initializeHardwareSelections();

            // Initial validation
            Platform.runLater(this::validateAll);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button != okType) {
                    return null;
                }
                return createResult();
            });

            return dialog.showAndWait();
        }

        private void createProjectSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Sample name field
            sampleNameField = new TextField();
            sampleNameField.setPromptText("e.g., MySample01");
            sampleNameField.setPrefWidth(300);

            String lastSampleName = PersistentPreferences.getLastSampleName();
            if (!lastSampleName.isEmpty()) {
                sampleNameField.setText(lastSampleName);
            }

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
                folderField = new TextField();
                folderField.setPrefWidth(250);
                folderField.setText(QPPreferenceDialog.getProjectsFolderProperty());

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
                row++;

                Label infoLabel = new Label("A new project will be created for this sample.");
                infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px; -fx-text-fill: gray;");
                grid.add(infoLabel, 0, row, 2, 1);
            } else {
                folderField = new TextField(existingProjectFolder.getParent());
                folderField.setVisible(false);

                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(new Label("Project:"), 0, row);
                grid.add(projectLabel, 1, row);
                row++;

                Label locationLabel = new Label(existingProjectFolder.getParent());
                locationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
                grid.add(new Label("Location:"), 0, row);
                grid.add(locationLabel, 1, row);
                row++;

                Label infoLabel = new Label("Sample will be added to the existing project.");
                infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px;");
                grid.add(infoLabel, 0, row, 2, 1);
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

            // Get available modalities
            Set<String> modalities = configManager.getSection("modalities").keySet();

            modalityBox = new ComboBox<>(FXCollections.observableArrayList(modalities));
            modalityBox.setPrefWidth(200);

            objectiveBox = new ComboBox<>();
            objectiveBox.setPrefWidth(300);

            detectorBox = new ComboBox<>();
            detectorBox.setPrefWidth(300);

            // Set up cascading selection listeners
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

            // Trigger preview update on any hardware change
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

        private void createRegionSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Start point fields
            startXField = new TextField();
            startYField = new TextField();
            startXField.setPromptText("Start X");
            startYField.setPromptText("Start Y");
            startXField.setPrefWidth(120);
            startYField.setPrefWidth(120);

            // Size fields - load saved values
            widthField = new TextField(PersistentPreferences.getBoundingBoxWidth());
            heightField = new TextField(PersistentPreferences.getBoundingBoxHeight());
            widthField.setPromptText("Width");
            heightField.setPromptText("Height");
            widthField.setPrefWidth(120);
            heightField.setPrefWidth(120);

            // If we have a saved bounding box, parse and populate start fields
            String savedBounds = PersistentPreferences.getBoundingBoxString();
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                String[] parts = savedBounds.split(",");
                if (parts.length == 4) {
                    try {
                        startXField.setText(parts[0].trim());
                        startYField.setText(parts[1].trim());
                    } catch (Exception e) {
                        // Ignore parsing errors
                    }
                }
            }

            // Get Stage Position button
            Button getPositionBtn = new Button("Get Stage Position");
            getPositionBtn.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        startXField.setText(String.format("%.2f", coords[0]));
                        startYField.setText(String.format("%.2f", coords[1]));
                        logger.info("Updated start position from stage: X={}, Y={}", coords[0], coords[1]);

                        // Set defaults if empty
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

            calculatedBoundsLabel = new Label("Enter coordinates to see calculated bounds");
            calculatedBoundsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

            // Validation listeners
            startXField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
            });
            startYField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
            });
            widthField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxWidth(newVal);
                }
            });
            heightField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxHeight(newVal);
                }
            });

            int row = 0;

            Label instructionLabel = new Label("Enter a starting point and acquisition size in microns:");
            instructionLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px;");
            grid.add(instructionLabel, 0, row, 4, 1);
            row++;

            grid.add(new Label("Start X (um):"), 0, row);
            grid.add(startXField, 1, row);
            grid.add(new Label("Start Y (um):"), 2, row);
            grid.add(startYField, 3, row);
            row++;

            grid.add(new Label("Width (um):"), 0, row);
            grid.add(widthField, 1, row);
            grid.add(new Label("Height (um):"), 2, row);
            grid.add(heightField, 3, row);
            row++;

            HBox buttonBox = new HBox(10, getPositionBtn);
            buttonBox.setAlignment(Pos.CENTER_LEFT);
            grid.add(buttonBox, 0, row, 4, 1);
            row++;

            grid.add(calculatedBoundsLabel, 0, row, 4, 1);

            regionPane = new TitledPane("ACQUISITION REGION", grid);
            regionPane.setExpanded(true);
            regionPane.setStyle("-fx-font-weight: bold;");
        }

        private void createPreviewPanel() {
            previewRegionLabel = new Label("Region: --");
            previewFOVLabel = new Label("Field of View: --");
            previewTileGridLabel = new Label("Tile Grid: --");
            previewAnglesLabel = new Label("Angles: --");
            previewTotalImagesLabel = new Label("Total Images: --");
            previewTimeLabel = new Label("Est. Time: --");
            previewStorageLabel = new Label("Est. Storage: --");
            previewErrorLabel = new Label("Enter valid coordinates to see preview");
            previewErrorLabel.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
        }

        private TitledPane createPreviewSection() {
            VBox previewContent = new VBox(5,
                    previewRegionLabel,
                    previewFOVLabel,
                    previewTileGridLabel,
                    previewAnglesLabel,
                    previewTotalImagesLabel,
                    new Separator(),
                    previewTimeLabel,
                    previewStorageLabel,
                    previewErrorLabel
            );
            previewContent.setPadding(new Insets(10));
            previewContent.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-width: 1px;");

            TitledPane previewPane = new TitledPane("ACQUISITION PREVIEW", previewContent);
            previewPane.setExpanded(true);
            previewPane.setCollapsible(false);
            previewPane.setStyle("-fx-font-weight: bold;");

            return previewPane;
        }

        private void createAdvancedSection() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            Label placeholder = new Label("Modality-specific options will appear here when a modality is selected.");
            placeholder.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
            content.getChildren().add(placeholder);

            advancedPane = new TitledPane("ADVANCED OPTIONS", content);
            advancedPane.setExpanded(false); // Collapsed by default
            advancedPane.setStyle("-fx-font-weight: bold;");
        }

        private void createErrorSummaryPanel() {
            errorSummaryPanel = new VBox(5);
            errorSummaryPanel.setStyle(
                    "-fx-background-color: #fff3cd; " +
                    "-fx-border-color: #ffc107; " +
                    "-fx-border-width: 1px; " +
                    "-fx-padding: 10px;"
            );
            errorSummaryPanel.setVisible(false);
            errorSummaryPanel.setManaged(false);

            Label errorTitle = new Label("Please fix the following errors:");
            errorTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #856404;");

            errorListBox = new VBox(3);
            errorSummaryPanel.getChildren().addAll(errorTitle, errorListBox);
        }

        private void initializeHardwareSelections() {
            // Set last used modality
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

            // Try to restore last used objective
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

            // Try to restore last used detector
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

            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            if (uiOpt.isPresent()) {
                modalityUI = uiOpt.get();
                content.getChildren().add(modalityUI.getNode());
            } else {
                modalityUI = null;
                Label noOptions = new Label("No additional options for " + modality + " modality.");
                noOptions.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                content.getChildren().add(noOptions);
            }

            advancedPane.setContent(content);
        }

        private void setupPreviewUpdateListeners() {
            previewDebounce.setOnFinished(event -> updatePreviewPanel());
        }

        private void triggerPreviewUpdate() {
            previewDebounce.playFromStart();
        }

        private void updatePreviewPanel() {
            try {
                // Parse coordinates
                String startXStr = startXField.getText().trim();
                String startYStr = startYField.getText().trim();
                String widthStr = widthField.getText().trim();
                String heightStr = heightField.getText().trim();

                if (startXStr.isEmpty() || startYStr.isEmpty() ||
                    widthStr.isEmpty() || heightStr.isEmpty()) {
                    showPreviewPlaceholder("Enter all coordinates to see preview");
                    return;
                }

                double startX = Double.parseDouble(startXStr);
                double startY = Double.parseDouble(startYStr);
                double width = Double.parseDouble(widthStr);
                double height = Double.parseDouble(heightStr);

                if (width <= 0 || height <= 0) {
                    showPreviewPlaceholder("Width and height must be positive");
                    return;
                }

                // Get hardware selections
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                if (modality == null || objective == null || detector == null) {
                    showPreviewPlaceholder("Select hardware configuration to see preview");
                    return;
                }

                // Calculate FOV
                double[] fov = configManager.getModalityFOV(modality, objective, detector);
                if (fov == null) {
                    showPreviewPlaceholder("Could not calculate FOV for selected hardware");
                    return;
                }

                double frameWidth = fov[0];
                double frameHeight = fov[1];

                // Calculate tile grid
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                double stepX = frameWidth * (1.0 - overlapPercent / 100.0);
                double stepY = frameHeight * (1.0 - overlapPercent / 100.0);

                int tilesX = (int) Math.ceil(width / stepX);
                int tilesY = (int) Math.ceil(height / stepY);
                int totalTiles = tilesX * tilesY;

                // Get angle count from modality - use sensible defaults for preview
                // PPM typically has 4 angles, other modalities have 1
                int angleCount = 1;
                if ("ppm".equalsIgnoreCase(modality)) {
                    angleCount = 4;  // PPM default: minus, zero, plus, uncrossed
                }

                int totalImages = totalTiles * angleCount;

                // Estimate time (rough: 2 seconds per image including movement and exposure)
                double estimatedSeconds = totalImages * 2.0;
                String timeEstimate = formatTime(estimatedSeconds);

                // Estimate storage (rough: 4MB per image for 16-bit 2048x2048)
                double estimatedMB = totalImages * 4.0;
                String storageEstimate = formatStorage(estimatedMB);

                // Update calculated bounds
                double x1 = startX;
                double y1 = startY;
                double x2 = startX + width;
                double y2 = startY + height;
                calculatedBoundsLabel.setText(String.format(
                        "Calculated bounds: (%.1f, %.1f) to (%.1f, %.1f)", x1, y1, x2, y2));
                calculatedBoundsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #28a745;");

                // Update preview labels
                previewRegionLabel.setText(String.format("Region: %.2f x %.2f mm",
                        width / 1000.0, height / 1000.0));
                previewFOVLabel.setText(String.format("Field of View: %.0f x %.0f um (%s)",
                        frameWidth, frameHeight, objective));
                previewTileGridLabel.setText(String.format("Tile Grid: %d x %d = %d tiles (%.0f%% overlap)",
                        tilesX, tilesY, totalTiles, overlapPercent));
                previewAnglesLabel.setText(String.format("Angles: %d (%s modality)",
                        angleCount, modality));
                previewTotalImagesLabel.setText(String.format("Total Images: %,d", totalImages));
                previewTimeLabel.setText("Est. Time: " + timeEstimate);
                previewStorageLabel.setText("Est. Storage: " + storageEstimate);

                previewErrorLabel.setVisible(false);

            } catch (NumberFormatException e) {
                showPreviewPlaceholder("Invalid number format in coordinates");
            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
                showPreviewPlaceholder("Could not calculate preview");
            }
        }

        private void showPreviewPlaceholder(String message) {
            previewRegionLabel.setText("Region: --");
            previewFOVLabel.setText("Field of View: --");
            previewTileGridLabel.setText("Tile Grid: --");
            previewAnglesLabel.setText("Angles: --");
            previewTotalImagesLabel.setText("Total Images: --");
            previewTimeLabel.setText("Est. Time: --");
            previewStorageLabel.setText("Est. Storage: --");
            previewErrorLabel.setText(message);
            previewErrorLabel.setVisible(true);
        }

        private String formatTime(double seconds) {
            if (seconds < 60) {
                return String.format("%.0f seconds", seconds);
            } else if (seconds < 3600) {
                return String.format("%.1f minutes", seconds / 60.0);
            } else {
                return String.format("%.1f hours", seconds / 3600.0);
            }
        }

        private String formatStorage(double megabytes) {
            if (megabytes < 1024) {
                return String.format("%.0f MB", megabytes);
            } else {
                return String.format("%.1f GB", megabytes / 1024.0);
            }
        }

        private void validateRegion() {
            try {
                String startXStr = startXField.getText().trim();
                String startYStr = startYField.getText().trim();
                String widthStr = widthField.getText().trim();
                String heightStr = heightField.getText().trim();

                StringBuilder errors = new StringBuilder();

                if (startXStr.isEmpty()) errors.append("Start X is required. ");
                if (startYStr.isEmpty()) errors.append("Start Y is required. ");
                if (widthStr.isEmpty()) errors.append("Width is required. ");
                if (heightStr.isEmpty()) errors.append("Height is required. ");

                if (errors.length() == 0) {
                    double width = Double.parseDouble(widthStr);
                    double height = Double.parseDouble(heightStr);
                    if (width <= 0) errors.append("Width must be positive. ");
                    if (height <= 0) errors.append("Height must be positive. ");

                    // Also validate start coordinates are numbers
                    Double.parseDouble(startXStr);
                    Double.parseDouble(startYStr);
                }

                if (errors.length() > 0) {
                    validationErrors.put("region", errors.toString().trim());
                } else {
                    validationErrors.remove("region");
                }

            } catch (NumberFormatException e) {
                validationErrors.put("region", "Invalid number format in coordinates");
            }

            updateErrorSummary();
        }

        private void validateAll() {
            // Validate sample name
            String sampleName = sampleNameField.getText().trim();
            String sampleError = SampleNameValidator.getValidationError(sampleName);
            if (sampleError != null) {
                validationErrors.put("sampleName", sampleError);
            } else {
                validationErrors.remove("sampleName");
            }

            // Validate hardware
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

            // Validate region
            validateRegion();

            updateErrorSummary();
        }

        private void updateErrorSummary() {
            if (validationErrors.isEmpty()) {
                errorSummaryPanel.setVisible(false);
                errorSummaryPanel.setManaged(false);
                okButton.setDisable(false);
            } else {
                errorListBox.getChildren().clear();
                validationErrors.forEach((fieldId, errorMsg) -> {
                    Label errorLabel = new Label("- " + errorMsg);
                    errorLabel.setStyle("-fx-text-fill: #856404;");
                    errorListBox.getChildren().add(errorLabel);
                });

                errorSummaryPanel.setVisible(true);
                errorSummaryPanel.setManaged(true);
                okButton.setDisable(true);
            }
        }

        private UnifiedAcquisitionResult createResult() {
            try {
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = new File(folderField.getText().trim());
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                double startX = Double.parseDouble(startXField.getText().trim());
                double startY = Double.parseDouble(startYField.getText().trim());
                double width = Double.parseDouble(widthField.getText().trim());
                double height = Double.parseDouble(heightField.getText().trim());

                double x1 = startX;
                double y1 = startY;
                double x2 = startX + width;
                double y2 = startY + height;

                // Save preferences
                PersistentPreferences.setLastSampleName(sampleName);
                PersistentPreferences.setLastModality(modality);
                PersistentPreferences.setLastObjective(objective);
                PersistentPreferences.setLastDetector(detector);
                PersistentPreferences.setBoundingBoxString(
                        String.format("%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2));

                // Get angle overrides if available
                Map<String, Double> angleOverrides = null;
                if (modalityUI != null) {
                    angleOverrides = modalityUI.getAngleOverrides();
                    if (angleOverrides != null) {
                        logger.info("User specified angle overrides: {}", angleOverrides);
                    }
                }

                logger.info("Created unified acquisition result: sample={}, modality={}, " +
                           "objective={}, detector={}, bounds=({},{}) to ({},{})",
                        sampleName, modality, objective, detector, x1, y1, x2, y2);

                return new UnifiedAcquisitionResult(
                        sampleName, projectsFolder, modality, objective, detector,
                        x1, y1, x2, y2, angleOverrides
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
