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
import qupath.ext.qpsc.utilities.GreenBoxDetector;
import qupath.lib.gui.QuPathGUI;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI controller for microscope alignment workflow.
 * This workflow creates/refines affine transforms between macro and main images.
 *
 * @since 0.3.0
 */
public class MacroImageController {
    private static final Logger logger = LoggerFactory.getLogger(MacroImageController.class);

    /**
     * Configuration for the alignment workflow.
     */
    public record AlignmentConfig(
            boolean useExistingTransform,
            AffineTransformManager.TransformPreset selectedTransform,
            boolean saveTransform,
            String transformName,
            MacroImageAnalyzer.ThresholdMethod thresholdMethod,
            Map<String, Object> thresholdParams,
            GreenBoxDetector.DetectionParams greenBoxParams,  // Already exists
            boolean useGreenBoxDetection
    ) {}

    /**
     * Shows the main alignment workflow dialog.
     * This is focused on creating/refining transforms, NOT acquisition.
     */
    public static CompletableFuture<AlignmentConfig> showAlignmentDialog(
            QuPathGUI gui,
            AffineTransformManager transformManager,
            String currentMicroscope) {

        CompletableFuture<AlignmentConfig> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            Dialog<AlignmentConfig> dialog = new Dialog<>();
            dialog.initModality(Modality.NONE);
            dialog.setTitle("Microscope Alignment Setup");
            dialog.setHeaderText("Create or verify microscope alignment between macro and main images");
            dialog.setResizable(true);

            // Set dialog size
            dialog.getDialogPane().setPrefSize(800, 700);
            dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);

            // Create tabbed interface with color coding
            TabPane tabs = new TabPane();
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // Create styled tabs with colors to make them more noticeable
            // Tab 1: Transform Selection
            Tab transformTab = createTransformTab(transformManager, currentMicroscope);
            Label transformLabel = new Label("1. Transform");
            transformLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-color: #3498db; -fx-padding: 5;");
            transformTab.setGraphic(transformLabel);

            // Tab 2: Green Box Detection (with asterisk to show it needs visiting)
            Tab greenBoxTab = createGreenBoxTab(gui);
            Label greenBoxLabel = new Label("2. Green Box Detection *");
            greenBoxLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-color: #27ae60; -fx-padding: 5;");
            greenBoxTab.setGraphic(greenBoxLabel);

            // Tab 3: Tissue Threshold Settings (with asterisk to show it needs visiting)
            Tab thresholdTab = createThresholdTab(gui);
            Label thresholdLabel = new Label("3. Tissue Detection *");
            thresholdLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-color: #e67e22; -fx-padding: 5;");
            thresholdTab.setGraphic(thresholdLabel);

            tabs.getTabs().addAll(transformTab, greenBoxTab, thresholdTab);

            // Track which tabs have been visited
            Set<Tab> visitedTabs = new HashSet<>();
            visitedTabs.add(transformTab); // First tab is always visited

            tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null) {
                    visitedTabs.add(newTab);
                }
            });

            // Add instructions
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            Label instructions = new Label(
                    "This workflow creates an alignment transform between the macro image and main image.\n" +
                            "1. Choose to create new or refine existing transform\n" +
                            "2. Configure green box detection to find the scanned area (REQUIRED: Visit this tab)\n" +
                            "3. Configure tissue detection for annotation placement (OR visit this tab)\n\n" +
                            "You must visit at least one detection tab (2 or 3) before proceeding.\n" +
                            "The created transform will be saved and available in the Existing Image workflow."
            );
            instructions.setWrapText(true);
            instructions.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 10; -fx-border-color: #bdc3c7; -fx-font-weight: bold;");

            content.getChildren().addAll(instructions, tabs);

            dialog.getDialogPane().setContent(content);

            // Buttons
            ButtonType createType = new ButtonType("Create/Update Alignment", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(createType, cancelType);

            // Disable OK button until detection tabs are visited
            Button okButton = (Button) dialog.getDialogPane().lookupButton(createType);
            okButton.setDisable(true);

            // Update button state when tabs are visited
            tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null) {
                    visitedTabs.add(newTab);

                    // Remove asterisk from visited tab
                    if (newTab == greenBoxTab && greenBoxLabel.getText().endsWith(" *")) {
                        greenBoxLabel.setText("2. Green Box Detection");
                    } else if (newTab == thresholdTab && thresholdLabel.getText().endsWith(" *")) {
                        thresholdLabel.setText("3. Tissue Detection");
                    }

                    // Enable OK button only if at least one detection tab has been visited
                    boolean hasVisitedDetection = visitedTabs.contains(greenBoxTab) ||
                            visitedTabs.contains(thresholdTab);
                    okButton.setDisable(!hasVisitedDetection);

                    // Update button tooltip
                    if (!hasVisitedDetection) {
                        okButton.setTooltip(new Tooltip("Please visit at least one detection tab (Green Box or Tissue Detection)"));
                    } else {
                        okButton.setTooltip(null);
                    }
                }
            });

            // Result converter
            dialog.setResultConverter(button -> {
                if (button == createType) {
                    return gatherAlignmentConfig(transformTab, greenBoxTab, thresholdTab);
                }
                return null;
            });

            dialog.showAndWait().ifPresentOrElse(
                    config -> {
                        if (config != null) {
                            future.complete(config);
                        } else {
                            future.complete(null);
                        }
                    },
                    () -> future.complete(null)
            );
        });

        return future;
    }

    /**
     * Creates the green box detection tab.
     */
    private static Tab createGreenBoxTab(QuPathGUI gui) {
        Tab tab = new Tab();

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Enable/disable green box detection
        CheckBox enableGreenBox = new CheckBox("Use green box detection for initial positioning");
        enableGreenBox.setSelected(true);

        // Detection parameters
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(10);
        paramsGrid.setVgap(5);
        paramsGrid.setDisable(false);

        Label greenThresholdLabel = new Label("Green Dominance:");
        Spinner<Double> greenThresholdSpinner = new Spinner<>(0.0, 1.0, 0.4, 0.05);
        greenThresholdSpinner.setEditable(true);
        greenThresholdSpinner.setPrefWidth(100);

        Label saturationLabel = new Label("Min Saturation:");
        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0, 0.3, 0.05);
        saturationSpinner.setEditable(true);
        saturationSpinner.setPrefWidth(100);

        Label brightnessMinLabel = new Label("Min Brightness:");
        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0, 0.3, 0.05);
        brightnessMinSpinner.setEditable(true);
        brightnessMinSpinner.setPrefWidth(100);

        Label brightnessMaxLabel = new Label("Max Brightness:");
        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0, 0.9, 0.05);
        brightnessMaxSpinner.setEditable(true);
        brightnessMaxSpinner.setPrefWidth(100);

        Label edgeThicknessLabel = new Label("Edge Thickness:");
        Spinner<Integer> edgeThicknessSpinner = new Spinner<>(1, 50, 3, 1);
        edgeThicknessSpinner.setEditable(true);
        edgeThicknessSpinner.setPrefWidth(100);

        // Add to grid
        int row = 0;
        paramsGrid.add(greenThresholdLabel, 0, row);
        paramsGrid.add(greenThresholdSpinner, 1, row++);
        paramsGrid.add(saturationLabel, 0, row);
        paramsGrid.add(saturationSpinner, 1, row++);
        paramsGrid.add(brightnessMinLabel, 0, row);
        paramsGrid.add(brightnessMinSpinner, 1, row++);
        paramsGrid.add(brightnessMaxLabel, 0, row);
        paramsGrid.add(brightnessMaxSpinner, 1, row++);
        paramsGrid.add(edgeThicknessLabel, 0, row);
        paramsGrid.add(edgeThicknessSpinner, 1, row++);

        // Bind enable state
        enableGreenBox.selectedProperty().addListener((obs, old, selected) -> {
            paramsGrid.setDisable(!selected);
        });

        // Preview button and image
        Button previewButton = new Button("Preview Green Box Detection");
        ImageView previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);

        ScrollPane imageScroll = new ScrollPane(previewImage);
        imageScroll.setPrefViewportHeight(300);
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);
        imageScroll.setPannable(true);

        previewImage.fitWidthProperty().bind(imageScroll.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageScroll.heightProperty().subtract(20));

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);

        previewButton.setOnAction(e -> {
            // Get macro image
            var imageData = gui.getImageData();
            if (imageData == null) {
                resultLabel.setText("No image loaded");
                resultLabel.setStyle("-fx-text-fill: red;");
                return;
            }

            try {
                // First, get the list of associated images
                var associatedList = imageData.getServer().getAssociatedImageList();
                if (associatedList == null || associatedList.isEmpty()) {
                    resultLabel.setText("No associated images found");
                    resultLabel.setStyle("-fx-text-fill: red;");
                    logger.warn("No associated images in the image server");
                    return;
                }

                logger.info("Available associated images: {}", associatedList);

                // Find the macro image - try different approaches
                java.awt.image.BufferedImage macroImage = null;
                String macroKey = null;

                // Try to find which entry contains "macro"
                for (String name : associatedList) {
                    if (name.toLowerCase().contains("macro")) {
                        macroKey = name;
                        break;
                    }
                }

                if (macroKey != null) {
                    logger.info("Attempting to retrieve macro image with key: '{}'", macroKey);

                    // Try different ways to get the image
                    try {
                        // Try exact name first
                        macroImage = (java.awt.image.BufferedImage) imageData.getServer().getAssociatedImage(macroKey);
                    } catch (Exception ex) {
                        logger.debug("Failed with exact key '{}': {}", macroKey, ex.getMessage());
                    }

                    // If that didn't work and it's a series format, try just the series part
                    if (macroImage == null && macroKey.startsWith("Series ")) {
                        String seriesOnly = macroKey.split("\\s*\\(")[0].trim();
                        logger.info("Trying series-only key: '{}'", seriesOnly);
                        try {
                            macroImage = (java.awt.image.BufferedImage) imageData.getServer().getAssociatedImage(seriesOnly);
                        } catch (Exception ex) {
                            logger.debug("Failed with series key '{}': {}", seriesOnly, ex.getMessage());
                        }
                    }

                    // Last resort - try just "macro"
                    if (macroImage == null) {
                        logger.info("Trying simple 'macro' key");
                        try {
                            macroImage = (java.awt.image.BufferedImage) imageData.getServer().getAssociatedImage("macro");
                        } catch (Exception ex) {
                            logger.debug("Failed with 'macro' key: {}", ex.getMessage());
                        }
                    }
                }

                if (macroImage != null) {
                    logger.info("Successfully retrieved macro image: {}x{}",
                            macroImage.getWidth(), macroImage.getHeight());

                    // Create detection parameters
                    GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();
                    params.greenThreshold = greenThresholdSpinner.getValue();
                    params.saturationMin = saturationSpinner.getValue();
                    params.brightnessMin = brightnessMinSpinner.getValue();
                    params.brightnessMax = brightnessMaxSpinner.getValue();
                    params.edgeThickness = edgeThicknessSpinner.getValue();

                    // Run detection
                    var result = GreenBoxDetector.detectGreenBox(macroImage, params);

                    if (result != null) {
                        Image fxImage = SwingFXUtils.toFXImage(result.getDebugImage(), null);
                        previewImage.setImage(fxImage);
                        resultLabel.setText(String.format(
                                "Green box detected at (%.0f, %.0f) size %.0fx%.0f with confidence %.2f",
                                result.getDetectedBox().getBoundsX(),
                                result.getDetectedBox().getBoundsY(),
                                result.getDetectedBox().getBoundsWidth(),
                                result.getDetectedBox().getBoundsHeight(),
                                result.getConfidence()
                        ));
                        resultLabel.setStyle("-fx-text-fill: green;");
                    } else {
                        resultLabel.setText("No green box detected with current parameters");
                        resultLabel.setStyle("-fx-text-fill: orange;");
                        // Still show the macro image
                        Image fxImage = SwingFXUtils.toFXImage(macroImage, null);
                        previewImage.setImage(fxImage);
                    }
                } else {
                    resultLabel.setText("Could not retrieve macro image. Associated images: " + associatedList);
                    resultLabel.setStyle("-fx-text-fill: red;");
                    logger.error("Failed to retrieve macro image from any attempted key");
                }
            } catch (Exception ex) {
                String errorMsg = "Error during green box detection: " + ex.getMessage();
                resultLabel.setText(errorMsg);
                resultLabel.setStyle("-fx-text-fill: red;");
                logger.error("Error in green box preview", ex);
            }
        });

        // Layout
        content.getChildren().addAll(
                enableGreenBox,
                new Separator(),
                paramsGrid,
                previewButton,
                resultLabel,
                imageScroll
        );

        VBox.setVgrow(imageScroll, Priority.ALWAYS);

        // Store components for retrieval
        content.setUserData(Map.of(
                "enable", enableGreenBox,
                "greenThreshold", greenThresholdSpinner,
                "saturation", saturationSpinner,
                "brightnessMin", brightnessMinSpinner,
                "brightnessMax", brightnessMaxSpinner,
                "edgeThickness", edgeThicknessSpinner
        ));

        tab.setContent(content);
        return tab;
    }

    /**
     * Creates the transform selection tab.
     */
    private static Tab createTransformTab(AffineTransformManager manager,
                                          String currentMicroscope) {
        Tab tab = new Tab();

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Radio buttons for transform choice
        ToggleGroup transformGroup = new ToggleGroup();
        RadioButton useExisting = new RadioButton("Refine existing transform");
        RadioButton createNew = new RadioButton("Create new transform");
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
        CheckBox saveNewTransform = new CheckBox("Save transform when complete");
        saveNewTransform.setSelected(true);
        TextField transformName = new TextField();
        transformName.setPromptText("Transform name (e.g., 'Slide_Mount_v1')");

        // Layout
        content.getChildren().addAll(
                new Label("Transform Mode:"),
                createNew,
                useExisting,
                transformCombo,
                detailsArea,
                new Separator(),
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
     * Creates the threshold configuration tab with proper min region size handling.
     */
    private static Tab createThresholdTab(QuPathGUI gui) {
        Tab tab = new Tab();

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Threshold method selection
        ComboBox<MacroImageAnalyzer.ThresholdMethod> methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll(MacroImageAnalyzer.ThresholdMethod.values());
        methodCombo.getSelectionModel().select(MacroImageAnalyzer.ThresholdMethod.COLOR_DECONVOLUTION);

        // Method-specific parameters
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(10);
        paramsGrid.setVgap(5);

        // Create all parameter controls
        Label percentileLabel = new Label("Percentile:");
        Spinner<Double> percentileSpinner = new Spinner<>(0.0, 1.0, 0.5, 0.05);
        percentileSpinner.setEditable(true);

        Label fixedLabel = new Label("Threshold:");
        Spinner<Integer> fixedSpinner = new Spinner<>(0, 255, 128);
        fixedSpinner.setEditable(true);

        Label eosinLabel = new Label("Eosin Sensitivity:");
        Spinner<Double> eosinSpinner = new Spinner<>(0.0, 2.0, 0.3, 0.1);
        eosinSpinner.setEditable(true);

        Label hematoxylinLabel = new Label("Hematoxylin Sensitivity:");
        Spinner<Double> hematoxylinSpinner = new Spinner<>(0.0, 1.0, 0.15, 0.05);
        hematoxylinSpinner.setEditable(true);

        Label saturationLabel = new Label("Min Saturation:");
        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0, 0.1, 0.05);
        saturationSpinner.setEditable(true);

        Label brightnessMinLabel = new Label("Min Brightness:");
        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0, 0.2, 0.05);
        brightnessMinSpinner.setEditable(true);

        Label brightnessMaxLabel = new Label("Max Brightness:");
        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0, 0.95, 0.05);
        brightnessMaxSpinner.setEditable(true);

        // Min region size - always visible
        Label minSizeLabel = new Label("Min Region Size (pixels):");
        Spinner<Integer> minSizeSpinner = new Spinner<>(100, 50000, 1000, 100);
        minSizeSpinner.setEditable(true);

        // Add all to grid
        int row = 0;
        paramsGrid.add(percentileLabel, 0, row);
        paramsGrid.add(percentileSpinner, 1, row++);
        paramsGrid.add(fixedLabel, 0, row);
        paramsGrid.add(fixedSpinner, 1, row++);
        paramsGrid.add(eosinLabel, 0, row);
        paramsGrid.add(eosinSpinner, 1, row++);
        paramsGrid.add(hematoxylinLabel, 0, row);
        paramsGrid.add(hematoxylinSpinner, 1, row++);
        paramsGrid.add(saturationLabel, 0, row);
        paramsGrid.add(saturationSpinner, 1, row++);
        paramsGrid.add(brightnessMinLabel, 0, row);
        paramsGrid.add(brightnessMinSpinner, 1, row++);
        paramsGrid.add(brightnessMaxLabel, 0, row);
        paramsGrid.add(brightnessMaxSpinner, 1, row++);
        paramsGrid.add(new Separator(), 0, row++, 2, 1);
        paramsGrid.add(minSizeLabel, 0, row);
        paramsGrid.add(minSizeSpinner, 1, row++);

        // Update parameter visibility based on method
        methodCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, method) -> {
            updateThresholdParameterVisibility(method,
                    percentileLabel, percentileSpinner,
                    fixedLabel, fixedSpinner,
                    eosinLabel, eosinSpinner,
                    hematoxylinLabel, hematoxylinSpinner,
                    saturationLabel, saturationSpinner,
                    brightnessMinLabel, brightnessMinSpinner,
                    brightnessMaxLabel, brightnessMaxSpinner);
        });

        // Trigger initial visibility update
        updateThresholdParameterVisibility(methodCombo.getValue(),
                percentileLabel, percentileSpinner,
                fixedLabel, fixedSpinner,
                eosinLabel, eosinSpinner,
                hematoxylinLabel, hematoxylinSpinner,
                saturationLabel, saturationSpinner,
                brightnessMinLabel, brightnessMinSpinner,
                brightnessMaxLabel, brightnessMaxSpinner);

        // Preview button
        Button previewButton = new Button("Preview Tissue Detection");
        ImageView previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);

        ScrollPane imageScroll = new ScrollPane(previewImage);
        imageScroll.setPrefViewportHeight(300);
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);
        imageScroll.setPannable(true);

        previewImage.fitWidthProperty().bind(imageScroll.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageScroll.heightProperty().subtract(20));

        Label statsLabel = new Label();

        previewButton.setOnAction(e -> {
            // Gather current parameters including min size
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

                // Update stats
                statsLabel.setText(String.format(
                        "Detected %d tissue regions (after %d pixel minimum filter)\nTotal tissue bounds: %.0fx%.0f pixels",
                        result.getTissueRegions().size(),
                        minSizeSpinner.getValue(),
                        result.getTissueBounds().getBoundsWidth(),
                        result.getTissueBounds().getBoundsHeight()
                ));
            }
        });

        // Layout
        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(
                new Label("Tissue Detection Method:"),
                methodCombo,
                paramsGrid,
                new Separator(),
                previewButton,
                statsLabel,
                imageScroll
        );

        VBox.setVgrow(imageScroll, Priority.ALWAYS);
        contentBox.setFillWidth(true);

        // Store data
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
     * Updates visibility of threshold parameters based on selected method.
     * Note: Min region size is always visible.
     */
    private static void updateThresholdParameterVisibility(
            MacroImageAnalyzer.ThresholdMethod method,
            Label percentileLabel, Spinner<Double> percentileSpinner,
            Label fixedLabel, Spinner<Integer> fixedSpinner,
            Label eosinLabel, Spinner<Double> eosinSpinner,
            Label hematoxylinLabel, Spinner<Double> hematoxylinSpinner,
            Label saturationLabel, Spinner<Double> saturationSpinner,
            Label brightnessMinLabel, Spinner<Double> brightnessMinSpinner,
            Label brightnessMaxLabel, Spinner<Double> brightnessMaxSpinner) {

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
        if (method != null) {
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
        }
    }

    /**
     * Gathers all configuration from the dialog tabs.
     */
    @SuppressWarnings("unchecked")
    private static AlignmentConfig gatherAlignmentConfig(
            Tab transformTab, Tab greenBoxTab, Tab thresholdTab) {

        // Get transform settings
        var transformData = (Map<String, Object>) transformTab.getContent().getUserData();
        RadioButton useExisting = (RadioButton) transformData.get("useExisting");
        ComboBox<AffineTransformManager.TransformPreset> combo =
                (ComboBox<AffineTransformManager.TransformPreset>) transformData.get("transformCombo");
        CheckBox saveNew = (CheckBox) transformData.get("saveNew");
        TextField nameField = (TextField) transformData.get("transformName");

        // Get green box settings
        var greenBoxData = (Map<String, Object>) greenBoxTab.getContent().getUserData();
        CheckBox enableGreenBox = (CheckBox) greenBoxData.get("enable");
        Spinner<Double> greenThresholdSpinner = (Spinner<Double>) greenBoxData.get("greenThreshold");
        Spinner<Double> saturationSpinner = (Spinner<Double>) greenBoxData.get("saturation");
        Spinner<Double> brightnessMinSpinner = (Spinner<Double>) greenBoxData.get("brightnessMin");
        Spinner<Double> brightnessMaxSpinner = (Spinner<Double>) greenBoxData.get("brightnessMax");
        Spinner<Integer> edgeThicknessSpinner = (Spinner<Integer>) greenBoxData.get("edgeThickness");

        GreenBoxDetector.DetectionParams greenBoxParams = new GreenBoxDetector.DetectionParams();
        greenBoxParams.greenThreshold = greenThresholdSpinner.getValue();
        greenBoxParams.saturationMin = saturationSpinner.getValue();
        greenBoxParams.brightnessMin = brightnessMinSpinner.getValue();
        greenBoxParams.brightnessMax = brightnessMaxSpinner.getValue();
        greenBoxParams.edgeThickness = edgeThicknessSpinner.getValue();

        // Get threshold settings
        var thresholdData = (Map<String, Object>) thresholdTab.getContent().getUserData();
        ComboBox<MacroImageAnalyzer.ThresholdMethod> methodCombo =
                (ComboBox<MacroImageAnalyzer.ThresholdMethod>) thresholdData.get("method");
        Spinner<Double> percentileSpinner = (Spinner<Double>) thresholdData.get("percentile");
        Spinner<Integer> fixedSpinner = (Spinner<Integer>) thresholdData.get("fixed");
        Spinner<Double> eosinSpinner = (Spinner<Double>) thresholdData.get("eosinThreshold");
        Spinner<Double> hematoxylinSpinner = (Spinner<Double>) thresholdData.get("hematoxylinThreshold");
        Spinner<Double> saturationThresholdSpinner = (Spinner<Double>) thresholdData.get("saturationThreshold");
        Spinner<Double> brightnessMinThresholdSpinner = (Spinner<Double>) thresholdData.get("brightnessMin");
        Spinner<Double> brightnessMaxThresholdSpinner = (Spinner<Double>) thresholdData.get("brightnessMax");
        Spinner<Integer> minSizeSpinner = (Spinner<Integer>) thresholdData.get("minSize");

        Map<String, Object> thresholdParams = new HashMap<>();
        thresholdParams.put("percentile", percentileSpinner.getValue());
        thresholdParams.put("threshold", fixedSpinner.getValue());
        thresholdParams.put("eosinThreshold", eosinSpinner.getValue());
        thresholdParams.put("hematoxylinThreshold", hematoxylinSpinner.getValue());
        thresholdParams.put("saturationThreshold", saturationThresholdSpinner.getValue());
        thresholdParams.put("brightnessMin", brightnessMinThresholdSpinner.getValue());
        thresholdParams.put("brightnessMax", brightnessMaxThresholdSpinner.getValue());
        thresholdParams.put("minRegionSize", minSizeSpinner.getValue());

        return new AlignmentConfig(
                useExisting.isSelected(),
                useExisting.isSelected() ? combo.getValue() : null,
                saveNew.isSelected(),
                nameField.getText(),
                methodCombo.getValue(),
                thresholdParams,
                greenBoxParams,
                enableGreenBox.isSelected()
        );
    }
}