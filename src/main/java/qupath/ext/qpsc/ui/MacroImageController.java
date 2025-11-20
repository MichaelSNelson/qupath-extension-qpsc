package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.embed.swing.SwingFXUtils;
import javafx.util.StringConverter;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MacroImageAnalyzer;
import qupath.ext.qpsc.utilities.GreenBoxDetector;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;

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
            dialog.getDialogPane().setPrefSize(950, 850);
            dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            dialog.getDialogPane().setMinWidth(900);
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

            // Create instructions using a Label with proper sizing
            Label instructions = new Label(
                    "This workflow creates an alignment transform between the macro image and main image.\n\n" +
                            "1. Choose to create new or refine existing transform\n" +
                            "2. Configure green box detection to find the scanned area (REQUIRED: Visit this tab)\n" +
                            "3. Configure tissue detection for annotation placement (OR visit this tab)\n\n" +
                            "You must visit at least one detection tab (2 or 3) before proceeding.\n" +
                            "The created transform will be saved and available in the Existing Image workflow."
            );
            instructions.setWrapText(true);
            instructions.setMaxWidth(750);
            instructions.setPrefWidth(750);
            instructions.setMinHeight(Region.USE_COMPUTED_SIZE);
            instructions.setPrefHeight(Region.USE_COMPUTED_SIZE);  // Allow label to compute its own height
            instructions.setStyle(
                    "-fx-background-color: -fx-control-inner-background; " +
                            "-fx-padding: 15; " +
                            "-fx-border-color: -fx-box-border; " +
                            "-fx-border-width: 1; " +
                            "-fx-border-radius: 5; " +
                            "-fx-background-radius: 5; " +
                            "-fx-font-weight: normal; " +
                            "-fx-text-fill: -fx-text-base-color;"
            );

            // Wrap the instructions in a VBox to ensure proper sizing
            VBox instructionsContainer = new VBox(instructions);
            instructionsContainer.setPrefWidth(750);
            instructionsContainer.setMinHeight(Region.USE_PREF_SIZE);

            content.getChildren().addAll(instructionsContainer, tabs);
            dialog.getDialogPane().setPrefSize(950, 900);  // Increased height to accommodate content
            dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            dialog.getDialogPane().setMinWidth(900);
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
     * Creates the green box detection tab with persistent preferences support.
     */
    private static Tab createGreenBoxTab(QuPathGUI gui) {
        Tab tab = new Tab();

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Enable/disable green box detection
        CheckBox enableGreenBox = new CheckBox("Use green box detection for initial positioning");
        enableGreenBox.setSelected(true);
        // Get flip settings for display
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        // Detection parameters - CHANGED TO MULTI-COLUMN LAYOUT
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(20);  // Increased horizontal gap between columns
        paramsGrid.setVgap(8);   // Slightly increased vertical gap
        paramsGrid.setDisable(false);

        // Create spinners with saved values
        Label greenThresholdLabel = new Label("Green Dominance:");
        Spinner<Double> greenThresholdSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenThreshold(), 0.05);
        greenThresholdSpinner.setEditable(true);
        greenThresholdSpinner.setPrefWidth(100);

        Label saturationLabel = new Label("Min Saturation:");
        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenSaturationMin(), 0.05);
        saturationSpinner.setEditable(true);
        saturationSpinner.setPrefWidth(100);

        Label brightnessMinLabel = new Label("Min Brightness:");
        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenBrightnessMin(), 0.05);
        brightnessMinSpinner.setEditable(true);
        brightnessMinSpinner.setPrefWidth(100);

        Label brightnessMaxLabel = new Label("Max Brightness:");
        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenBrightnessMax(), 0.05);
        brightnessMaxSpinner.setEditable(true);
        brightnessMaxSpinner.setPrefWidth(100);

        Label edgeThicknessLabel = new Label("Edge Thickness:");
        Spinner<Integer> edgeThicknessSpinner = new Spinner<>(1, 50,
                PersistentPreferences.getGreenEdgeThickness(), 1);
        edgeThicknessSpinner.setEditable(true);
        edgeThicknessSpinner.setPrefWidth(100);

        Label minWidthLabel = new Label("Min Box Width:");
        Spinner<Integer> minWidthSpinner = new Spinner<>(10, 5000,
                PersistentPreferences.getGreenMinBoxWidth(), 10);
        minWidthSpinner.setEditable(true);
        minWidthSpinner.setPrefWidth(100);

        Label minHeightLabel = new Label("Min Box Height:");
        Spinner<Integer> minHeightSpinner = new Spinner<>(10, 5000,
                PersistentPreferences.getGreenMinBoxHeight(), 10);
        minHeightSpinner.setEditable(true);
        minHeightSpinner.setPrefWidth(100);

        Label hueMinLabel = new Label("Hue Min:");
        Spinner<Double> hueMinSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenHueMin(), 0.01);
        hueMinSpinner.setEditable(true);
        hueMinSpinner.setPrefWidth(100);

        Label hueMaxLabel = new Label("Hue Max:");
        Spinner<Double> hueMaxSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getGreenHueMax(), 0.01);
        hueMaxSpinner.setEditable(true);
        hueMaxSpinner.setPrefWidth(100);

        // CHANGED: Add spinners to grid in 3 columns instead of 2
        int row = 0;

        // Row 0: Green threshold and Edge thickness
        paramsGrid.add(greenThresholdLabel, 0, row);
        paramsGrid.add(greenThresholdSpinner, 1, row);
        paramsGrid.add(edgeThicknessLabel, 2, row);
        paramsGrid.add(edgeThicknessSpinner, 3, row++);

        // Row 1: Saturation and Min Width
        paramsGrid.add(saturationLabel, 0, row);
        paramsGrid.add(saturationSpinner, 1, row);
        paramsGrid.add(minWidthLabel, 2, row);
        paramsGrid.add(minWidthSpinner, 3, row++);

        // Row 2: Brightness Min and Min Height
        paramsGrid.add(brightnessMinLabel, 0, row);
        paramsGrid.add(brightnessMinSpinner, 1, row);
        paramsGrid.add(minHeightLabel, 2, row);
        paramsGrid.add(minHeightSpinner, 3, row++);

        // Row 3: Brightness Max and Hue Min
        paramsGrid.add(brightnessMaxLabel, 0, row);
        paramsGrid.add(brightnessMaxSpinner, 1, row);
        paramsGrid.add(hueMinLabel, 2, row);
        paramsGrid.add(hueMinSpinner, 3, row++);

        // Row 4: Hue Max (left column only)
        paramsGrid.add(hueMaxLabel, 0, row);
        paramsGrid.add(hueMaxSpinner, 1, row++);

        // Add value change listeners to save preferences
        greenThresholdSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenThreshold(val);
            logger.debug("Green threshold updated to: {}", val);
        });

        saturationSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenSaturationMin(val);
            logger.debug("Green saturation min updated to: {}", val);
        });

        brightnessMinSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenBrightnessMin(val);
            logger.debug("Green brightness min updated to: {}", val);
        });

        brightnessMaxSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenBrightnessMax(val);
            logger.debug("Green brightness max updated to: {}", val);
        });

        edgeThicknessSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenEdgeThickness(val);
            logger.debug("Green edge thickness updated to: {}", val);
        });

        minWidthSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenMinBoxWidth(val);
            logger.debug("Green min box width updated to: {}", val);
        });

        minHeightSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenMinBoxHeight(val);
            logger.debug("Green min box height updated to: {}", val);
        });

        hueMinSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenHueMin(val);
            logger.debug("Green hue min updated to: {}", val);
        });

        hueMaxSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setGreenHueMax(val);
            logger.debug("Green hue max updated to: {}", val);
        });

        // Bind enable state
        enableGreenBox.selectedProperty().addListener((obs, old, selected) -> {
            paramsGrid.setDisable(!selected);
        });

        // Preview button and image
        Button previewButton = new Button("Preview Green Box Detection");
        ImageView previewImage = new ImageView();
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);

        // CHANGED: Make ScrollPane much larger for better image visibility
        ScrollPane imageScroll = new ScrollPane(previewImage);
        imageScroll.setPrefViewportHeight(450);  // Increased from 300
        imageScroll.setPrefViewportWidth(700);   // Set preferred width too
        imageScroll.setMinHeight(350);           // Set minimum height
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);
        imageScroll.setPannable(true);

        previewImage.fitWidthProperty().bind(imageScroll.widthProperty().subtract(20));
        previewImage.fitHeightProperty().bind(imageScroll.heightProperty().subtract(20));

        // Enable text wrapping for long file paths
        Label resultLabel = new Label();
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(Double.MAX_VALUE);  // Allow full width
        resultLabel.setPrefHeight(Region.USE_COMPUTED_SIZE);  // Allow height to adjust
        resultLabel.setMinHeight(Region.USE_PREF_SIZE);

        // Add info about flips being applied
        Label flipInfoLabel = new Label();
        if (flipX || flipY) {
            String flipText = "Image display: ";
            if (flipX && flipY) flipText += "Flipped X and Y";
            else if (flipX) flipText += "Flipped X";
            else flipText += "Flipped Y";
            flipInfoLabel.setText(flipText);
            flipInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #2c3e50;");
        }

        // Try to load and display the macro image immediately when tab is created
        Platform.runLater(() -> {
            try {
                ImageData imageData = gui.getImageData();
                if (imageData != null) {
                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                    if (macroImage != null) {
                        // Crop the macro image
                        MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(macroImage);
                        BufferedImage processedImage = croppedResult.getCroppedImage();

                        // Apply flips if needed
                        if (flipX || flipY) {
                            processedImage = MacroImageUtility.flipMacroImage(processedImage, flipX, flipY);
                        }

                        Image fxImage = SwingFXUtils.toFXImage(processedImage, null);
                        previewImage.setImage(fxImage);
                        resultLabel.setText("Macro image loaded (cropped and flipped). Click 'Preview Green Box Detection' to detect the scanned area.");
                        resultLabel.setStyle("-fx-text-fill: #059669;");
                    }
                }
            } catch (Exception ex) {
                logger.debug("Could not load macro image on tab creation", ex);
            }
        });

        // Reset to defaults button
        Button resetButton = new Button("Reset to Defaults");
        resetButton.setOnAction(e -> {
            logger.info("Resetting green box parameters to defaults");
            greenThresholdSpinner.getValueFactory().setValue(0.4);
            saturationSpinner.getValueFactory().setValue(0.3);
            brightnessMinSpinner.getValueFactory().setValue(0.3);
            brightnessMaxSpinner.getValueFactory().setValue(0.9);
            edgeThicknessSpinner.getValueFactory().setValue(3);
            minWidthSpinner.getValueFactory().setValue(100);
            minHeightSpinner.getValueFactory().setValue(100);
        });

        previewButton.setOnAction(e -> {
            ImageData imageData = gui.getImageData();
            if (imageData == null) {
                resultLabel.setText("No image loaded");
                resultLabel.setStyle("-fx-text-fill: red;");
                return;
            }

            try {
                // Get macro image using utility
                BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);

                if (macroImage != null) {
                    logger.info("Successfully retrieved macro image: {}x{}",
                            macroImage.getWidth(), macroImage.getHeight());

                    // Crop the macro image to just the slide area
                    MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(macroImage);
                    BufferedImage processedImage = croppedResult.getCroppedImage();

                    logger.info("Cropped to slide area: {}x{} (offset: {}, {})",
                            processedImage.getWidth(), processedImage.getHeight(),
                            croppedResult.getCropOffsetX(), croppedResult.getCropOffsetY());

                    // Apply flips if needed
                    if (flipX || flipY) {
                        processedImage = MacroImageUtility.flipMacroImage(processedImage, flipX, flipY);
                        logger.info("Applied flips: X={}, Y={}", flipX, flipY);
                    }

                    // First, always show the processed (cropped and flipped) macro image
                    Image originalImage = SwingFXUtils.toFXImage(processedImage, null);
                    previewImage.setImage(originalImage);

                    // Create detection parameters from current spinner values
                    GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();
                    params.greenThreshold = greenThresholdSpinner.getValue();
                    params.saturationMin = saturationSpinner.getValue();
                    params.brightnessMin = brightnessMinSpinner.getValue();
                    params.brightnessMax = brightnessMaxSpinner.getValue();
                    params.edgeThickness = edgeThicknessSpinner.getValue();
                    params.minBoxWidth = minWidthSpinner.getValue();
                    params.minBoxHeight = minHeightSpinner.getValue();

                    // Run detection on the flipped, cropped image
                    var result = GreenBoxDetector.detectGreenBox(processedImage, params);

                    if (result != null) {
                        // Now update with the debug image showing the detection
                        Image debugImage = SwingFXUtils.toFXImage(result.getDebugImage(), null);
                        previewImage.setImage(debugImage);

                        resultLabel.setText(String.format(
                                "Green box detected at (%.0f, %.0f) size %.0fx%.0f with confidence %.2f\n" +
                                        "Note: Coordinates are in the flipped, cropped macro image",
                                result.getDetectedBox().getBoundsX(),
                                result.getDetectedBox().getBoundsY(),
                                result.getDetectedBox().getBoundsWidth(),
                                result.getDetectedBox().getBoundsHeight(),
                                result.getConfidence()
                        ));
                        resultLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

                        // Save current params since detection was successful
                        params.saveToPreferences();
                        logger.info("Saved successful detection parameters to preferences");
                    } else {
                        // No detection - keep showing the processed macro image
                        resultLabel.setText("No green box detected with current parameters");
                        resultLabel.setStyle("-fx-text-fill: orange;");
                    }
                } else {
                    resultLabel.setText("Could not retrieve macro image from current image");
                    resultLabel.setStyle("-fx-text-fill: red;");
                    logger.error("Failed to retrieve macro image");
                }
            } catch (Exception ex) {
                String errorMsg = "Error during green box detection: " + ex.getMessage();
                resultLabel.setText(errorMsg);
                resultLabel.setStyle("-fx-text-fill: red;");
                logger.error("Error in green box preview", ex);
            }
        });

        // Layout
        HBox buttonBox = new HBox(10, previewButton, resetButton);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(
                enableGreenBox,
                flipInfoLabel,  // Add flip info
                new Separator(),
                paramsGrid,
                buttonBox,
                resultLabel,
                imageScroll
        );

        VBox.setVgrow(imageScroll, Priority.ALWAYS);

        // Store components for retrieval
        Map<String, Object> greenBoxData = new HashMap<>();
        greenBoxData.put("enable", enableGreenBox);
        greenBoxData.put("greenThreshold", greenThresholdSpinner);
        greenBoxData.put("saturation", saturationSpinner);
        greenBoxData.put("brightnessMin", brightnessMinSpinner);
        greenBoxData.put("brightnessMax", brightnessMaxSpinner);
        greenBoxData.put("edgeThickness", edgeThicknessSpinner);
        greenBoxData.put("minWidth", minWidthSpinner);
        greenBoxData.put("minHeight", minHeightSpinner);
        greenBoxData.put("hueMin", hueMinSpinner);
        greenBoxData.put("hueMax", hueMaxSpinner);
        content.setUserData(greenBoxData);

        tab.setContent(content);
        return tab;
    }
//    /**
//     * Creates the green box detection tab with persistent preferences support.
//     */
//    private static Tab createGreenBoxTab(QuPathGUI gui) {
//        Tab tab = new Tab();
//
//        VBox content = new VBox(10);
//        content.setPadding(new Insets(10));
//
//        // Enable/disable green box detection
//        CheckBox enableGreenBox = new CheckBox("Use green box detection for initial positioning");
//        enableGreenBox.setSelected(true);
//        // Get flip settings for display
//        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
//        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
//        // Detection parameters
//        GridPane paramsGrid = new GridPane();
//        paramsGrid.setHgap(10);
//        paramsGrid.setVgap(5);
//        paramsGrid.setDisable(false);
//
//        // Create spinners with saved values
//        Label greenThresholdLabel = new Label("Green Dominance:");
//        Spinner<Double> greenThresholdSpinner = new Spinner<>(0.0, 1.0,
//                PersistentPreferences.getGreenThreshold(), 0.05);
//        greenThresholdSpinner.setEditable(true);
//        greenThresholdSpinner.setPrefWidth(100);
//
//        Label saturationLabel = new Label("Min Saturation:");
//        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0,
//                PersistentPreferences.getGreenSaturationMin(), 0.05);
//        saturationSpinner.setEditable(true);
//        saturationSpinner.setPrefWidth(100);
//
//        Label brightnessMinLabel = new Label("Min Brightness:");
//        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0,
//                PersistentPreferences.getGreenBrightnessMin(), 0.05);
//        brightnessMinSpinner.setEditable(true);
//        brightnessMinSpinner.setPrefWidth(100);
//
//        Label brightnessMaxLabel = new Label("Max Brightness:");
//        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0,
//                PersistentPreferences.getGreenBrightnessMax(), 0.05);
//        brightnessMaxSpinner.setEditable(true);
//        brightnessMaxSpinner.setPrefWidth(100);
//
//        Label edgeThicknessLabel = new Label("Edge Thickness:");
//        Spinner<Integer> edgeThicknessSpinner = new Spinner<>(1, 50,
//                PersistentPreferences.getGreenEdgeThickness(), 1);
//        edgeThicknessSpinner.setEditable(true);
//        edgeThicknessSpinner.setPrefWidth(100);
//
//        Label minWidthLabel = new Label("Min Box Width:");
//        Spinner<Integer> minWidthSpinner = new Spinner<>(10, 5000,
//                PersistentPreferences.getGreenMinBoxWidth(), 10);
//        minWidthSpinner.setEditable(true);
//        minWidthSpinner.setPrefWidth(100);
//
//        Label minHeightLabel = new Label("Min Box Height:");
//        Spinner<Integer> minHeightSpinner = new Spinner<>(10, 5000,
//                PersistentPreferences.getGreenMinBoxHeight(), 10);
//        minHeightSpinner.setEditable(true);
//        minHeightSpinner.setPrefWidth(100);
//
//        // Add spinners to grid
//        int row = 0;
//        paramsGrid.add(greenThresholdLabel, 0, row);
//        paramsGrid.add(greenThresholdSpinner, 1, row++);
//        paramsGrid.add(saturationLabel, 0, row);
//        paramsGrid.add(saturationSpinner, 1, row++);
//        paramsGrid.add(brightnessMinLabel, 0, row);
//        paramsGrid.add(brightnessMinSpinner, 1, row++);
//        paramsGrid.add(brightnessMaxLabel, 0, row);
//        paramsGrid.add(brightnessMaxSpinner, 1, row++);
//        paramsGrid.add(edgeThicknessLabel, 0, row);
//        paramsGrid.add(edgeThicknessSpinner, 1, row++);
//        paramsGrid.add(minWidthLabel, 0, row);
//        paramsGrid.add(minWidthSpinner, 1, row++);
//        paramsGrid.add(minHeightLabel, 0, row);
//        paramsGrid.add(minHeightSpinner, 1, row++);
//
//        // Add value change listeners to save preferences
//        greenThresholdSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenThreshold(val);
//            logger.debug("Green threshold updated to: {}", val);
//        });
//
//        saturationSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenSaturationMin(val);
//            logger.debug("Green saturation min updated to: {}", val);
//        });
//
//        brightnessMinSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenBrightnessMin(val);
//            logger.debug("Green brightness min updated to: {}", val);
//        });
//
//        brightnessMaxSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenBrightnessMax(val);
//            logger.debug("Green brightness max updated to: {}", val);
//        });
//
//        edgeThicknessSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenEdgeThickness(val);
//            logger.debug("Green edge thickness updated to: {}", val);
//        });
//
//        minWidthSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenMinBoxWidth(val);
//            logger.debug("Green min box width updated to: {}", val);
//        });
//
//        minHeightSpinner.valueProperty().addListener((obs, old, val) -> {
//            PersistentPreferences.setGreenMinBoxHeight(val);
//            logger.debug("Green min box height updated to: {}", val);
//        });
//
//        // Bind enable state
//        enableGreenBox.selectedProperty().addListener((obs, old, selected) -> {
//            paramsGrid.setDisable(!selected);
//        });
//
//        // Preview button and image
//        Button previewButton = new Button("Preview Green Box Detection");
//        ImageView previewImage = new ImageView();
//        previewImage.setPreserveRatio(true);
//        previewImage.setSmooth(true);
//
//        ScrollPane imageScroll = new ScrollPane(previewImage);
//        imageScroll.setPrefViewportHeight(300);
//        imageScroll.setFitToWidth(true);
//        imageScroll.setFitToHeight(true);
//        imageScroll.setPannable(true);
//
//        previewImage.fitWidthProperty().bind(imageScroll.widthProperty().subtract(20));
//        previewImage.fitHeightProperty().bind(imageScroll.heightProperty().subtract(20));
//
//        Label resultLabel = new Label();
//        resultLabel.setWrapText(true);
//        // Add info about flips being applied
//        Label flipInfoLabel = new Label();
//        if (flipX || flipY) {
//            String flipText = "Image display: ";
//            if (flipX && flipY) flipText += "Flipped X and Y";
//            else if (flipX) flipText += "Flipped X";
//            else flipText += "Flipped Y";
//            flipInfoLabel.setText(flipText);
//            flipInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #2c3e50;");
//        }
//
//        // Try to load and display the macro image immediately when tab is created
//        Platform.runLater(() -> {
//            try {
//                ImageData imageData = gui.getImageData();
//                if (imageData != null) {
//                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
//                    if (macroImage != null) {
//                        // Crop the macro image
//                        MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(macroImage);
//                        BufferedImage processedImage = croppedResult.getCroppedImage();
//
//                        // Apply flips if needed
//                        if (flipX || flipY) {
//                            processedImage = MacroImageUtility.flipMacroImage(processedImage, flipX, flipY);
//                        }
//
//                        Image fxImage = SwingFXUtils.toFXImage(processedImage, null);
//                        previewImage.setImage(fxImage);
//                        resultLabel.setText("Macro image loaded (cropped and flipped). Click 'Preview Green Box Detection' to detect the scanned area.");
//                        resultLabel.setStyle("-fx-text-fill: #059669;");
//                    }
//                }
//            } catch (Exception ex) {
//                logger.debug("Could not load macro image on tab creation", ex);
//            }
//        });
//        // Reset to defaults button
//        Button resetButton = new Button("Reset to Defaults");
//        resetButton.setOnAction(e -> {
//            logger.info("Resetting green box parameters to defaults");
//            greenThresholdSpinner.getValueFactory().setValue(0.4);
//            saturationSpinner.getValueFactory().setValue(0.3);
//            brightnessMinSpinner.getValueFactory().setValue(0.3);
//            brightnessMaxSpinner.getValueFactory().setValue(0.9);
//            edgeThicknessSpinner.getValueFactory().setValue(3);
//            minWidthSpinner.getValueFactory().setValue(100);
//            minHeightSpinner.getValueFactory().setValue(100);
//        });
//
//        previewButton.setOnAction(e -> {
//            ImageData imageData = gui.getImageData();
//            if (imageData == null) {
//                resultLabel.setText("No image loaded");
//                resultLabel.setStyle("-fx-text-fill: red;");
//                return;
//            }
//
//            try {
//                // Get macro image using utility
//                BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
//
//                if (macroImage != null) {
//                    logger.info("Successfully retrieved macro image: {}x{}",
//                            macroImage.getWidth(), macroImage.getHeight());
//
//                    // Crop the macro image to just the slide area
//                    MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(macroImage);
//                    BufferedImage processedImage = croppedResult.getCroppedImage();
//
//                    logger.info("Cropped to slide area: {}x{} (offset: {}, {})",
//                            processedImage.getWidth(), processedImage.getHeight(),
//                            croppedResult.getCropOffsetX(), croppedResult.getCropOffsetY());
//
//                    // Apply flips if needed
//                    if (flipX || flipY) {
//                        processedImage = MacroImageUtility.flipMacroImage(processedImage, flipX, flipY);
//                        logger.info("Applied flips: X={}, Y={}", flipX, flipY);
//                    }
//
//                    // First, always show the processed (cropped and flipped) macro image
//                    Image originalImage = SwingFXUtils.toFXImage(processedImage, null);
//                    previewImage.setImage(originalImage);
//
//                    // Create detection parameters from current spinner values
//                    GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();
//                    params.greenThreshold = greenThresholdSpinner.getValue();
//                    params.saturationMin = saturationSpinner.getValue();
//                    params.brightnessMin = brightnessMinSpinner.getValue();
//                    params.brightnessMax = brightnessMaxSpinner.getValue();
//                    params.edgeThickness = edgeThicknessSpinner.getValue();
//                    params.minBoxWidth = minWidthSpinner.getValue();
//                    params.minBoxHeight = minHeightSpinner.getValue();
//
//                    // Run detection on the flipped, cropped image
//                    var result = GreenBoxDetector.detectGreenBox(processedImage, params);
//
//                    if (result != null) {
//                        // Now update with the debug image showing the detection
//                        Image debugImage = SwingFXUtils.toFXImage(result.getDebugImage(), null);
//                        previewImage.setImage(debugImage);
//
//                        resultLabel.setText(String.format(
//                                "Green box detected at (%.0f, %.0f) size %.0fx%.0f with confidence %.2f\n" +
//                                        "Note: Coordinates are in the flipped, cropped macro image",
//                                result.getDetectedBox().getBoundsX(),
//                                result.getDetectedBox().getBoundsY(),
//                                result.getDetectedBox().getBoundsWidth(),
//                                result.getDetectedBox().getBoundsHeight(),
//                                result.getConfidence()
//                        ));
//                        resultLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
//
//                        // Save current params since detection was successful
//                        params.saveToPreferences();
//                        logger.info("Saved successful detection parameters to preferences");
//                    } else {
//                        // No detection - keep showing the processed macro image
//                        resultLabel.setText("No green box detected with current parameters");
//                        resultLabel.setStyle("-fx-text-fill: orange;");
//                    }
//                } else {
//                    resultLabel.setText("Could not retrieve macro image from current image");
//                    resultLabel.setStyle("-fx-text-fill: red;");
//                    logger.error("Failed to retrieve macro image");
//                }
//            } catch (Exception ex) {
//                String errorMsg = "Error during green box detection: " + ex.getMessage();
//                resultLabel.setText(errorMsg);
//                resultLabel.setStyle("-fx-text-fill: red;");
//                logger.error("Error in green box preview", ex);
//            }
//        });
//
//        // Layout
//        HBox buttonBox = new HBox(10, previewButton, resetButton);
//        buttonBox.setAlignment(Pos.CENTER_LEFT);
//
//        content.getChildren().addAll(
//                enableGreenBox,
//                flipInfoLabel,  // Add flip info
//                new Separator(),
//                paramsGrid,
//                buttonBox,
//                resultLabel,
//                imageScroll
//        );
//
//        VBox.setVgrow(imageScroll, Priority.ALWAYS);
//
//        // Store components for retrieval
//        content.setUserData(Map.of(
//                "enable", enableGreenBox,
//                "greenThreshold", greenThresholdSpinner,
//                "saturation", saturationSpinner,
//                "brightnessMin", brightnessMinSpinner,
//                "brightnessMax", brightnessMaxSpinner,
//                "edgeThickness", edgeThicknessSpinner,
//                "minWidth", minWidthSpinner,
//                "minHeight", minHeightSpinner
//        ));
//
//        tab.setContent(content);
//        return tab;
//    }

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

        // Save new transform option - initialize from preferences
        CheckBox saveNewTransform = new CheckBox("Save transform when complete");
        saveNewTransform.setSelected(PersistentPreferences.getSaveTransformDefault());

        // Save preference when changed
        saveNewTransform.selectedProperty().addListener((obs, old, selected) -> {
            PersistentPreferences.setSaveTransformDefault(selected);
            logger.debug("Save transform default updated to: {}", selected);
        });

        // Transform name field
        TextField transformName = new TextField();
        transformName.setPromptText("Transform name (e.g., 'Slide_Mount_v1')");

        // Generate suggested name based on date and modality
        String suggestedName = generateTransformName(currentMicroscope);

        // Use last transform name if available, otherwise use suggestion
        String lastUsedName = PersistentPreferences.getLastTransformName();
        if (!lastUsedName.isEmpty()) {
            transformName.setText(lastUsedName);
        } else {
            transformName.setText(suggestedName);
        }

        // Enable/disable name field based on save checkbox
        transformName.setDisable(!saveNewTransform.isSelected());
        saveNewTransform.selectedProperty().addListener((obs, old, selected) -> {
            transformName.setDisable(!selected);
            if (selected && transformName.getText().isEmpty()) {
                transformName.setText(generateTransformName(currentMicroscope));
            }
        });

        // Save name when changed
        transformName.textProperty().addListener((obs, old, newName) -> {
            if (!newName.isEmpty()) {
                PersistentPreferences.setLastTransformName(newName);
            }
        });

        // Info label
        Label infoLabel = new Label();
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic;");

        // Update info based on selection
        transformGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == createNew) {
                infoLabel.setText("A new transform will be created from scratch using manual alignment.");
            } else {
                infoLabel.setText("The existing transform will be used as a starting point and refined.");
            }
        });

        // Set initial info
        infoLabel.setText("A new transform will be created from scratch using manual alignment.");

        // Layout
        content.getChildren().addAll(
                new Label("Transform Mode:"),
                createNew,
                useExisting,
                transformCombo,
                detailsArea,
                new Separator(),
                saveNewTransform,
                transformName,
                infoLabel
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
     * Generates a suggested transform name based on current date and microscope.
     */
    private static String generateTransformName(String microscope) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String date = sdf.format(new Date());
        return String.format("%s_Transform_%s", microscope, date);
    }

    /**
     * Creates the threshold configuration tab with proper min region size handling.
     */
    private static Tab createThresholdTab(QuPathGUI gui) {
        Tab tab = new Tab();

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Threshold method selection - initialize from saved preference
        ComboBox<MacroImageAnalyzer.ThresholdMethod> methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll(MacroImageAnalyzer.ThresholdMethod.values());

        // Load saved method
        try {
            String savedMethod = PersistentPreferences.getTissueDetectionMethod();
            MacroImageAnalyzer.ThresholdMethod method =
                    MacroImageAnalyzer.ThresholdMethod.valueOf(savedMethod);
            methodCombo.getSelectionModel().select(method);
            logger.debug("Loaded saved tissue detection method: {}", method);
        } catch (Exception e) {
            // Default to COLOR_DECONVOLUTION if saved value is invalid
            methodCombo.getSelectionModel().select(MacroImageAnalyzer.ThresholdMethod.COLOR_DECONVOLUTION);
            logger.debug("Using default tissue detection method");
        }

        // Save method when changed
        methodCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, method) -> {
            if (method != null) {
                PersistentPreferences.setTissueDetectionMethod(method.name());
                logger.debug("Saved tissue detection method: {}", method);
            }
        });

        // Method-specific parameters
        GridPane paramsGrid = new GridPane();
        paramsGrid.setHgap(10);
        paramsGrid.setVgap(5);

        // Create all parameter controls with saved values
        Label percentileLabel = new Label("Percentile:");
        Spinner<Double> percentileSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getTissuePercentile(), 0.05);
        percentileSpinner.setEditable(true);
        percentileSpinner.setPrefWidth(120);

        Label fixedLabel = new Label("Threshold:");
        Spinner<Integer> fixedSpinner = new Spinner<>(0, 255,
                PersistentPreferences.getTissueFixedThreshold());
        fixedSpinner.setEditable(true);
        fixedSpinner.setPrefWidth(120);

        Label eosinLabel = new Label("Eosin Sensitivity:");
        Spinner<Double> eosinSpinner = new Spinner<>(0.0, 2.0,
                PersistentPreferences.getTissueEosinThreshold(), 0.05);
        eosinSpinner.setEditable(true);
        eosinSpinner.setPrefWidth(120);

        Label hematoxylinLabel = new Label("Hematoxylin Sensitivity:");
        Spinner<Double> hematoxylinSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getTissueHematoxylinThreshold(), 0.05);
        hematoxylinSpinner.setEditable(true);
        hematoxylinSpinner.setPrefWidth(120);

        Label saturationLabel = new Label("Min Saturation:");
        Spinner<Double> saturationSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getTissueSaturationThreshold(), 0.05);
        saturationSpinner.setEditable(true);
        saturationSpinner.setPrefWidth(120);

        Label brightnessMinLabel = new Label("Min Brightness:");
        Spinner<Double> brightnessMinSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getTissueBrightnessMin(), 0.05);
        brightnessMinSpinner.setEditable(true);
        brightnessMinSpinner.setPrefWidth(120);

        Label brightnessMaxLabel = new Label("Max Brightness:");
        Spinner<Double> brightnessMaxSpinner = new Spinner<>(0.0, 1.0,
                PersistentPreferences.getTissueBrightnessMax(), 0.05);
        brightnessMaxSpinner.setEditable(true);
        brightnessMaxSpinner.setPrefWidth(120);

        // Min region size - always visible
        Label minSizeLabel = new Label("Min Region Size (pixels):");
        Spinner<Integer> minSizeSpinner = new Spinner<>(100, 150000,
                PersistentPreferences.getTissueMinRegionSize(), 100);
        minSizeSpinner.setEditable(true);
        minSizeSpinner.setPrefWidth(120);

        // Add value change listeners to save preferences
        percentileSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissuePercentile(val);
            logger.debug("Tissue percentile updated to: {}", val);
        });

        fixedSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueFixedThreshold(val);
            logger.debug("Tissue fixed threshold updated to: {}", val);
        });

        eosinSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueEosinThreshold(val);
            logger.debug("Tissue eosin threshold updated to: {}", val);
        });

        hematoxylinSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueHematoxylinThreshold(val);
            logger.debug("Tissue hematoxylin threshold updated to: {}", val);
        });

        saturationSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueSaturationThreshold(val);
            logger.debug("Tissue saturation threshold updated to: {}", val);
        });

        brightnessMinSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueBrightnessMin(val);
            logger.debug("Tissue brightness min updated to: {}", val);
        });

        brightnessMaxSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueBrightnessMax(val);
            logger.debug("Tissue brightness max updated to: {}", val);
        });

        minSizeSpinner.valueProperty().addListener((obs, old, val) -> {
            PersistentPreferences.setTissueMinRegionSize(val);
            logger.debug("Tissue min region size updated to: {}", val);
        });

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
        previewButton.setPrefWidth(180);

        // Reset to defaults button
        Button resetButton = new Button("Reset to Defaults");
        resetButton.setPrefWidth(120);
        resetButton.setOnAction(e -> {
            logger.info("Resetting tissue detection parameters to defaults");
            // Reset based on current method
            MacroImageAnalyzer.ThresholdMethod method = methodCombo.getValue();
            if (method != null) {
                switch (method) {
                    case PERCENTILE -> percentileSpinner.getValueFactory().setValue(0.5);
                    case FIXED -> fixedSpinner.getValueFactory().setValue(128);
                    case HE_EOSIN -> {
                        eosinSpinner.getValueFactory().setValue(0.15);
                        saturationSpinner.getValueFactory().setValue(0.1);
                        brightnessMinSpinner.getValueFactory().setValue(0.2);
                        brightnessMaxSpinner.getValueFactory().setValue(0.95);
                    }
                    case HE_DUAL -> {
                        eosinSpinner.getValueFactory().setValue(0.15);
                        hematoxylinSpinner.getValueFactory().setValue(0.15);
                        saturationSpinner.getValueFactory().setValue(0.1);
                        brightnessMinSpinner.getValueFactory().setValue(0.2);
                        brightnessMaxSpinner.getValueFactory().setValue(0.95);
                    }
                    case COLOR_DECONVOLUTION -> {
                        brightnessMinSpinner.getValueFactory().setValue(0.2);
                        brightnessMaxSpinner.getValueFactory().setValue(0.95);
                    }
                }
            }
            minSizeSpinner.getValueFactory().setValue(1000);
        });

        // Buttons layout
        HBox buttonBox = new HBox(10, previewButton, resetButton);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        // Preview image and results
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
        statsLabel.setWrapText(true);
        statsLabel.setStyle("-fx-font-weight: bold;");

        previewButton.setOnAction(e -> {
            try {
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

                logger.info("Running tissue detection preview with method: {} and params: {}",
                        methodCombo.getValue(), params);

                var result = MacroImageAnalyzer.analyzeMacroImage(
                        gui.getImageData(),
                        methodCombo.getValue(),
                        params
                );

                if (result != null) {
                    Image fxImage = SwingFXUtils.toFXImage(result.getThresholdedImage(), null);
                    previewImage.setImage(fxImage);

                    // Update stats with color coding
                    int regionCount = result.getTissueRegions().size();
                    String regionStyle = regionCount > 0 ? "-fx-text-fill: green;" : "-fx-text-fill: orange;";

                    statsLabel.setText(String.format(
                            "Detected %d tissue regions (after %d pixel minimum filter)\n" +
                                    "Total tissue bounds: %.0fx%.0f pixels (%.1fx%.1f mm)\n" +
                                    "Threshold value used: %d",
                            regionCount,
                            minSizeSpinner.getValue(),
                            result.getTissueBounds().getBoundsWidth(),
                            result.getTissueBounds().getBoundsHeight(),
                            result.getTissueBounds().getBoundsWidth() * 0.08,  // Assuming 80µm macro pixels
                            result.getTissueBounds().getBoundsHeight() * 0.08,
                            result.getThreshold()
                    ));
                    statsLabel.setStyle(regionStyle + " -fx-font-weight: bold;");

                    logger.info("Tissue detection successful: {} regions found", regionCount);
                } else {
                    statsLabel.setText("Failed to analyze macro image - check if macro image is available");
                    statsLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    logger.warn("Tissue detection returned null result");
                }
            } catch (Exception ex) {
                statsLabel.setText("Error during tissue detection: " + ex.getMessage());
                statsLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                logger.error("Error in tissue detection preview", ex);
            }
        });

        // Info label
        Label infoLabel = new Label("Parameters are automatically saved as you adjust them.");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic;");

        // Layout
        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(
                new Label("Tissue Detection Method:"),
                methodCombo,
                new Separator(),
                paramsGrid,
                infoLabel,
                new Separator(),
                buttonBox,
                statsLabel,
                imageScroll
        );

        VBox.setVgrow(imageScroll, Priority.ALWAYS);
        contentBox.setFillWidth(true);

        // Store data for retrieval
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
        Spinner<Integer> minWidthSpinner = (Spinner<Integer>) greenBoxData.get("minWidth");
        Spinner<Integer> minHeightSpinner = (Spinner<Integer>) greenBoxData.get("minHeight");
        Spinner<Double> hueMinSpinner = (Spinner<Double>) greenBoxData.get("hueMin");
        Spinner<Double> hueMaxSpinner = (Spinner<Double>) greenBoxData.get("hueMax");

        GreenBoxDetector.DetectionParams greenBoxParams = new GreenBoxDetector.DetectionParams();
        greenBoxParams.greenThreshold = greenThresholdSpinner.getValue();
        greenBoxParams.saturationMin = saturationSpinner.getValue();
        greenBoxParams.brightnessMin = brightnessMinSpinner.getValue();
        greenBoxParams.brightnessMax = brightnessMaxSpinner.getValue();
        greenBoxParams.edgeThickness = edgeThicknessSpinner.getValue();
        greenBoxParams.minBoxWidth = minWidthSpinner.getValue();
        greenBoxParams.minBoxHeight = minHeightSpinner.getValue();
        greenBoxParams.hueMin = hueMinSpinner.getValue();
        greenBoxParams.hueMax = hueMaxSpinner.getValue();

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