package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.embed.swing.SwingFXUtils;
import javafx.util.StringConverter;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MacroImageAnalyzer;
import qupath.lib.gui.QuPathGUI;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * UI controller for macro image-based acquisition workflows.
 * Handles dialogs for transform selection, threshold configuration,
 * and workflow options.
 *
 * @since 0.3.0
 */
public class MacroImageController {

    /**
     * Configuration for the macro workflow.
     */
    public record WorkflowConfig(
            SampleSetupController.SampleSetupResult sampleSetup,
            boolean useExistingTransform,
            AffineTransformManager.TransformPreset selectedTransform,
            boolean saveTransform,
            String transformName,
            MacroImageAnalyzer.ThresholdMethod thresholdMethod,
            Map<String, Object> thresholdParams,
            boolean createSingleBounds
    ) {}

    /**
     * Result from save transform dialog.
     */
    public record SaveTransformResult(
            String name,
            String microscope,
            String mountingMethod,
            String notes
    ) {}

    /**
     * Shows the main macro workflow configuration dialog.
     */
    public static CompletableFuture<WorkflowConfig> showWorkflowDialog(
            QuPathGUI gui,
            AffineTransformManager transformManager,
            String currentMicroscope) {

        CompletableFuture<WorkflowConfig> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<WorkflowConfig> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Macro Image Acquisition Setup");
            dialog.setHeaderText("Configure acquisition using macro image analysis");
            dialog.setResizable(true);

            // Set dialog size
            dialog.getDialogPane().setPrefSize(700, 600);
            dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);

            // Create tabbed interface
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // Tab 1: Transform Selection
            Tab transformTab = createTransformTab(transformManager, currentMicroscope);

            // Tab 2: Threshold Settings
            Tab thresholdTab = createThresholdTab(gui);

            // Tab 3: Acquisition Options
            Tab optionsTab = createOptionsTab();

            tabs.getTabs().addAll(transformTab, thresholdTab, optionsTab);

            // Dialog content
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));
            content.getChildren().add(tabs);

            dialog.getDialogPane().setContent(content);

            // Buttons
            ButtonType runType = new ButtonType("Run Workflow", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(runType, cancelType);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button == runType) {
                    // Gather settings immediately while dialog is still open
                    WorkflowConfig config = gatherWorkflowConfig(
                            null,  // We'll get sample setup separately
                            transformTab, thresholdTab, optionsTab);
                    return config;
                }
                return null;
            });

            var result = dialog.showAndWait();
            if (result.isPresent() && result.get() != null) {
                // Now show sample setup dialog
                SampleSetupController.showDialog()
                        .thenAccept(sample -> {
                            if (sample != null) {
                                // Create complete config with sample info
                                WorkflowConfig completeConfig = new WorkflowConfig(
                                        sample,
                                        result.get().useExistingTransform(),
                                        result.get().selectedTransform(),
                                        result.get().saveTransform(),
                                        result.get().transformName(),
                                        result.get().thresholdMethod(),
                                        result.get().thresholdParams(),
                                        result.get().createSingleBounds()
                                );
                                future.complete(completeConfig);
                            } else {
                                future.complete(null);
                            }
                        })
                        .exceptionally(ex -> {
                            future.completeExceptionally(ex);
                            return null;
                        });
            } else {
                future.complete(null);
            }

            if (!future.isDone()) {
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Creates the transform selection tab.
     */
    private static Tab createTransformTab(AffineTransformManager manager,
                                          String currentMicroscope) {
        Tab tab = new Tab("Transform");

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Radio buttons for transform choice
        ToggleGroup transformGroup = new ToggleGroup();
        RadioButton useExisting = new RadioButton("Use saved transform");
        RadioButton createNew = new RadioButton("Create new transform (manual alignment)");
        useExisting.setToggleGroup(transformGroup);
        createNew.setToggleGroup(transformGroup);
        createNew.setSelected(true);

        // Transform selection
        ComboBox<AffineTransformManager.TransformPreset> transformCombo = new ComboBox<>();
        transformCombo.setDisable(true);
        transformCombo.setPrefWidth(300);

        // Load transforms for current microscope
        var transforms = manager.getTransformsForMicroscope(currentMicroscope);
        transformCombo.getItems().addAll(transforms);
        if (!transforms.isEmpty()) {
            transformCombo.getSelectionModel().selectFirst();
        }

        // Transform details
        TextArea detailsArea = new TextArea();
        detailsArea.setEditable(false);
        detailsArea.setPrefRowCount(4);
        detailsArea.setWrapText(true);

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

        // Enable/disable based on radio selection
        useExisting.selectedProperty().addListener((obs, old, selected) -> {
            transformCombo.setDisable(!selected);
            detailsArea.setDisable(!selected);
        });

        // Save new transform option
        CheckBox saveNewTransform = new CheckBox("Save new transform for future use");
        TextField transformName = new TextField();
        transformName.setPromptText("Transform preset name");
        transformName.setDisable(true);

        saveNewTransform.selectedProperty().addListener((obs, old, selected) -> {
            transformName.setDisable(!selected || useExisting.isSelected());
        });

        createNew.selectedProperty().addListener((obs, old, selected) -> {
            saveNewTransform.setDisable(!selected);
            transformName.setDisable(!selected || !saveNewTransform.isSelected());
        });

        // Layout
        content.getChildren().addAll(
                new Label("Transform Selection:"),
                useExisting,
                transformCombo,
                detailsArea,
                new Separator(),
                createNew,
                saveNewTransform,
                transformName
        );

        // Store UI components for later retrieval
        content.setUserData(Map.of(
                "useExisting", useExisting,
                "transformCombo", transformCombo,
                "saveNew", saveNewTransform,
                "transformName", transformName
        ));

        tab.setContent(content);
        return tab;
    }

    /**
     * Creates the threshold configuration tab.
     */
    private static Tab createThresholdTab(QuPathGUI gui) {
        Tab tab = new Tab("Threshold");

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Threshold method selection
        ComboBox<MacroImageAnalyzer.ThresholdMethod> methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll(MacroImageAnalyzer.ThresholdMethod.values());
        methodCombo.getSelectionModel().select(MacroImageAnalyzer.ThresholdMethod.OTSU);

        // Method-specific parameters
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(10);
        paramsGrid.setVgap(5);

        // Percentile parameter
        Label percentileLabel = new Label("Percentile:");
        Spinner<Double> percentileSpinner = new Spinner<>(0.0, 1.0, 0.5, 0.05);
        percentileSpinner.setEditable(true);

        // Fixed threshold parameter
        Label fixedLabel = new Label("Threshold:");
        Spinner<Integer> fixedSpinner = new Spinner<>(0, 255, 128);
        fixedSpinner.setEditable(true);

        // H&E Eosin parameters
        Label eosinLabel = new Label("Eosin Sensitivity:");
        Spinner<Double> eosinSpinner = new Spinner<>(0.0, 2.0, 0.3, 0.1);
        eosinSpinner.setEditable(true);
        eosinSpinner.getValueFactory().setConverter(new StringConverter<Double>() {
            private final DecimalFormat df = new DecimalFormat("#.####");
            @Override
            public String toString(Double value) {
                return df.format(value);
            }
            @Override
            public Double fromString(String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException e) {
                    return eosinSpinner.getValue();
                }
            }
        });

        // H&E Hematoxylin parameters
        Label hematoxylinLabel = new Label("Hematoxylin Sensitivity:");
        Spinner<Double> hematoxylinSpinner = new Spinner<>(0.0, 1.0, 0.15, 0.05);
        hematoxylinSpinner.setEditable(true);

        // Saturation threshold
        Label saturationLabel = new Label("Min Saturation:");
        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0, 0.1, 0.05);
        saturationSpinner.setEditable(true);

        // Brightness range
        Label brightnessMinLabel = new Label("Min Brightness:");
        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0, 0.6, 0.05);
        brightnessMinSpinner.setEditable(true);

        Label brightnessMaxLabel = new Label("Max Brightness:");
        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0, 0.95, 0.05);
        brightnessMaxSpinner.setEditable(true);

        Label minSizeLabel = new Label("Min Region Size (pixels):");
        Spinner<Integer> minSizeSpinner = new Spinner<>(100, 10000, 1000, 100);
        minSizeSpinner.setEditable(true);

// Add to grid at line ~356:
        paramsGrid.add(minSizeLabel, 0, 7);
        paramsGrid.add(minSizeSpinner, 1, 7);

        // Initially hide all parameters
        percentileLabel.setVisible(false);
        percentileSpinner.setVisible(false);
        fixedLabel.setVisible(false);
        fixedSpinner.setVisible(false);
        eosinLabel.setVisible(false);
        eosinSpinner.setVisible(false);
        hematoxylinLabel.setVisible(false);
        hematoxylinSpinner.setVisible(false);
        saturationLabel.setVisible(false);
        saturationSpinner.setVisible(false);
        brightnessMinLabel.setVisible(false);
        brightnessMinSpinner.setVisible(false);
        brightnessMaxLabel.setVisible(false);
        brightnessMaxSpinner.setVisible(false);

        paramsGrid.add(percentileLabel, 0, 0);
        paramsGrid.add(percentileSpinner, 1, 0);
        paramsGrid.add(fixedLabel, 0, 1);
        paramsGrid.add(fixedSpinner, 1, 1);
        paramsGrid.add(eosinLabel, 0, 2);
        paramsGrid.add(eosinSpinner, 1, 2);
        paramsGrid.add(hematoxylinLabel, 0, 3);
        paramsGrid.add(hematoxylinSpinner, 1, 3);
        paramsGrid.add(saturationLabel, 0, 4);
        paramsGrid.add(saturationSpinner, 1, 4);
        paramsGrid.add(brightnessMinLabel, 0, 5);
        paramsGrid.add(brightnessMinSpinner, 1, 5);
        paramsGrid.add(brightnessMaxLabel, 0, 6);
        paramsGrid.add(brightnessMaxSpinner, 1, 6);

        // Update parameter visibility based on method
        methodCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, method) -> {
            // Hide all first
            percentileLabel.setVisible(false);
            percentileSpinner.setVisible(false);
            fixedLabel.setVisible(false);
            fixedSpinner.setVisible(false);
            eosinLabel.setVisible(false);
            eosinSpinner.setVisible(false);
            hematoxylinLabel.setVisible(false);
            hematoxylinSpinner.setVisible(false);
            saturationLabel.setVisible(false);
            saturationSpinner.setVisible(false);
            brightnessMinLabel.setVisible(false);
            brightnessMinSpinner.setVisible(false);
            brightnessMaxLabel.setVisible(false);
            brightnessMaxSpinner.setVisible(false);

            // Show relevant parameters
            switch (method) {
                case COLOR_DECONVOLUTION -> {
                    brightnessMinLabel.setVisible(true);
                    brightnessMinSpinner.setVisible(true);
                    brightnessMaxLabel.setVisible(true);
                    brightnessMaxSpinner.setVisible(true);
                }
                case PERCENTILE -> {
                    percentileLabel.setVisible(true);
                    percentileSpinner.setVisible(true);
                }
                case FIXED -> {
                    fixedLabel.setVisible(true);
                    fixedSpinner.setVisible(true);
                }
                case HE_EOSIN -> {
                    eosinLabel.setVisible(true);
                    eosinSpinner.setVisible(true);
                    saturationLabel.setVisible(true);
                    saturationSpinner.setVisible(true);
                    brightnessMinLabel.setVisible(true);
                    brightnessMinSpinner.setVisible(true);
                    brightnessMaxLabel.setVisible(true);
                    brightnessMaxSpinner.setVisible(true);
                    minSizeLabel.setVisible(true);
                    minSizeSpinner.setVisible(true);
                }
                case HE_DUAL -> {
                    eosinLabel.setVisible(true);
                    eosinSpinner.setVisible(true);
                    hematoxylinLabel.setVisible(true);
                    hematoxylinSpinner.setVisible(true);
                    saturationLabel.setVisible(true);
                    saturationSpinner.setVisible(true);
                    brightnessMinLabel.setVisible(true);
                    brightnessMinSpinner.setVisible(true);
                    brightnessMaxLabel.setVisible(true);
                    brightnessMaxSpinner.setVisible(true);
                }

            }
        });

        // Preview button
        Button previewButton = new Button("Preview Threshold");
        ImageView previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);

        // Create a scroll pane for the image
        ScrollPane imageScroll = new ScrollPane(previewImage);
        imageScroll.setPrefViewportHeight(400);
        imageScroll.setPrefViewportWidth(500);
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);
        imageScroll.setPannable(true);

        // Bind image size to scroll pane size
        previewImage.fitWidthProperty().bind(imageScroll.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageScroll.heightProperty().subtract(20));

        previewButton.setOnAction(e -> {
            // Run analysis with current settings
            Map<String, Object> params = new HashMap<>();
            params.put("percentile", percentileSpinner.getValue());
            params.put("threshold", fixedSpinner.getValue());
            params.put("eosinThreshold", eosinSpinner.getValue());
            params.put("hematoxylinThreshold", hematoxylinSpinner.getValue());
            params.put("saturationThreshold", saturationSpinner.getValue());
            params.put("brightnessMin", brightnessMinSpinner.getValue());
            params.put("brightnessMax", brightnessMaxSpinner.getValue());
            params.put("minRegionSize", minSizeSpinner.getValue());
            var result = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(),
                    methodCombo.getValue(),
                    params
            );

            if (result != null) {
                Image fxImage = SwingFXUtils.toFXImage(result.getThresholdedImage(), null);
                previewImage.setImage(fxImage);

                // Force layout update
                Platform.runLater(() -> {
                    imageScroll.layout();
                    previewImage.autosize();
                });
            }
        });

        // Layout
        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(
                new Label("Threshold Method:"),
                methodCombo,
                paramsGrid,
                new Separator(),
                previewButton,
                imageScroll
        );

        // Make the VBox grow to fill available space
        VBox.setVgrow(imageScroll, Priority.ALWAYS);
        contentBox.setFillWidth(true);

        contentBox.setUserData(Map.of(
        "method", methodCombo,
                "percentile", percentileSpinner,
                "fixed", fixedSpinner,
                "eosinThreshold", eosinSpinner,
                "hematoxylinThreshold", hematoxylinSpinner,
                "saturationThreshold", saturationSpinner,
                "brightnessMin", brightnessMinSpinner,
                "brightnessMax", brightnessMaxSpinner,
                "minSize", minSizeSpinner

        ));

        tab.setContent(contentBox);
        return tab;
    }

    /**
     * Creates the acquisition options tab.
     */
    private static Tab createOptionsTab() {
        Tab tab = new Tab("Options");

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Region creation options
        Label regionLabel = new Label("Region Creation:");
        RadioButton singleBounds = new RadioButton("Single bounding box around all tissue");
        RadioButton individualRegions = new RadioButton("Individual regions for each tissue area");

        ToggleGroup regionGroup = new ToggleGroup();
        singleBounds.setToggleGroup(regionGroup);
        individualRegions.setToggleGroup(regionGroup);
        singleBounds.setSelected(true);

        // Additional options
        CheckBox addBuffer = new CheckBox("Add buffer around detected regions");
        addBuffer.setSelected(true);

        CheckBox autoFocus = new CheckBox("Perform autofocus on first tile");
        autoFocus.setSelected(false);

        // Layout
        content.getChildren().addAll(
                regionLabel,
                singleBounds,
                individualRegions,
                new Separator(),
                addBuffer,
                autoFocus
        );

        // Store components
        content.setUserData(Map.of(
                "singleBounds", singleBounds,
                "addBuffer", addBuffer,
                "autoFocus", autoFocus
        ));

        tab.setContent(content);
        return tab;
    }

    /**
     * Gathers all configuration from the dialog tabs.
     */
    @SuppressWarnings("unchecked")
    private static WorkflowConfig gatherWorkflowConfig(
            SampleSetupController.SampleSetupResult sample,
            Tab transformTab, Tab thresholdTab, Tab optionsTab) {

        // Get transform settings
        var transformData = (Map<String, Object>) transformTab.getContent().getUserData();
        RadioButton useExisting = (RadioButton) transformData.get("useExisting");
        ComboBox<AffineTransformManager.TransformPreset> combo =
                (ComboBox<AffineTransformManager.TransformPreset>) transformData.get("transformCombo");
        CheckBox saveNew = (CheckBox) transformData.get("saveNew");
        TextField nameField = (TextField) transformData.get("transformName");

        // Get threshold settings
        var thresholdData = (Map<String, Object>) thresholdTab.getContent().getUserData();
        ComboBox<MacroImageAnalyzer.ThresholdMethod> methodCombo =
                (ComboBox<MacroImageAnalyzer.ThresholdMethod>) thresholdData.get("method");
        Spinner<Double> percentileSpinner = (Spinner<Double>) thresholdData.get("percentile");
        Spinner<Integer> fixedSpinner = (Spinner<Integer>) thresholdData.get("fixed");
        Spinner<Double> eosinSpinner = (Spinner<Double>) thresholdData.get("eosinThreshold");
        Spinner<Double> hematoxylinSpinner = (Spinner<Double>) thresholdData.get("hematoxylinThreshold");
        Spinner<Double> saturationSpinner = (Spinner<Double>) thresholdData.get("saturationThreshold");
        Spinner<Double> brightnessMinSpinner = (Spinner<Double>) thresholdData.get("brightnessMin");
        Spinner<Double> brightnessMaxSpinner = (Spinner<Double>) thresholdData.get("brightnessMax");
        Spinner<Integer> minSizeSpinner = (Spinner<Integer>) thresholdData.get("minSize");

        Map<String, Object> thresholdParams = new HashMap<>();
        thresholdParams.put("percentile", percentileSpinner.getValue());
        thresholdParams.put("threshold", fixedSpinner.getValue());
        thresholdParams.put("eosinThreshold", eosinSpinner.getValue());
        thresholdParams.put("hematoxylinThreshold", hematoxylinSpinner.getValue());
        thresholdParams.put("saturationThreshold", saturationSpinner.getValue());
        thresholdParams.put("brightnessMin", brightnessMinSpinner.getValue());
        thresholdParams.put("brightnessMax", brightnessMaxSpinner.getValue());
        thresholdParams.put("minRegionSize", minSizeSpinner.getValue());
        // Get options
        var optionsData = (Map<String, Object>) optionsTab.getContent().getUserData();
        RadioButton singleBounds = (RadioButton) optionsData.get("singleBounds");

        return new WorkflowConfig(
                sample,
                useExisting.isSelected(),
                useExisting.isSelected() ? combo.getValue() : null,
                saveNew.isSelected(),
                nameField.getText(),
                methodCombo.getValue(),
                thresholdParams,
                singleBounds.isSelected()
        );
    }

    /**
     * Shows dialog to save a new transform preset.
     */
    public static CompletableFuture<SaveTransformResult> showSaveTransformDialog(
            String defaultName) {

        CompletableFuture<SaveTransformResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<SaveTransformResult> dialog = new Dialog<>();
            dialog.setTitle("Save Transform Preset");
            dialog.setHeaderText("Save this transform for future use");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));

            TextField nameField = new TextField(defaultName);
            TextField microscopeField = new TextField();
            TextField mountingField = new TextField();
            TextArea notesArea = new TextArea();
            notesArea.setPrefRowCount(3);

            grid.add(new Label("Name:"), 0, 0);
            grid.add(nameField, 1, 0);
            grid.add(new Label("Microscope:"), 0, 1);
            grid.add(microscopeField, 1, 1);
            grid.add(new Label("Mounting Method:"), 0, 2);
            grid.add(mountingField, 1, 2);
            grid.add(new Label("Notes:"), 0, 3);
            grid.add(notesArea, 1, 3);

            dialog.getDialogPane().setContent(grid);

            ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

            // Disable save until required fields are filled
            Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
            saveButton.disableProperty().bind(
                    nameField.textProperty().isEmpty()
                            .or(microscopeField.textProperty().isEmpty())
                            .or(mountingField.textProperty().isEmpty())
            );

            dialog.setResultConverter(button -> {
                if (button == saveType) {
                    return new SaveTransformResult(
                            nameField.getText().trim(),
                            microscopeField.getText().trim(),
                            mountingField.getText().trim(),
                            notesArea.getText().trim()
                    );
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(
                    future::complete,
                    () -> future.complete(null)
            );
        });

        return future;
    }
}