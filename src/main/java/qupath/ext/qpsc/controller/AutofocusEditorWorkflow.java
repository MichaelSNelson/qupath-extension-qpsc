package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AutofocusEditorWorkflow - Configuration editor for per-objective autofocus settings
 *
 * <p>This workflow provides a GUI for editing autofocus parameters stored in autofocus_{microscope}.yml.
 * The autofocus configuration is separate from the main microscope config and contains three parameters
 * per objective:
 * <ul>
 *   <li>n_steps: Number of Z positions to sample during autofocus</li>
 *   <li>search_range_um: Total Z range to search in micrometers</li>
 *   <li>n_tiles: Spatial frequency - autofocus runs every N tiles during acquisition</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Reads objectives from main microscope config (respects preference setting)</li>
 *   <li>Loads existing autofocus settings if autofocus_{microscope}.yml exists</li>
 *   <li>"Write to file" button saves immediately but keeps dialog open</li>
 *   <li>"OK" button saves (if changed) and closes dialog</li>
 *   <li>"Cancel" button closes without saving unsaved changes</li>
 *   <li>Parameter validation with warnings for extreme values</li>
 * </ul>
 *
 * @author Mike Nelson
 */
public class AutofocusEditorWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusEditorWorkflow.class);

    /**
     * Autofocus settings for a single objective
     */
    private static class AutofocusSettings {
        String objective;
        int nSteps;
        double searchRangeUm;
        int nTiles;
        int interpStrength;
        String interpKind;
        String scoreMetric;
        double textureThreshold;
        double tissueAreaThreshold;

        AutofocusSettings(String objective, int nSteps, double searchRangeUm, int nTiles,
                         int interpStrength, String interpKind, String scoreMetric,
                         double textureThreshold, double tissueAreaThreshold) {
            this.objective = objective;
            this.nSteps = nSteps;
            this.searchRangeUm = searchRangeUm;
            this.nTiles = nTiles;
            this.interpStrength = interpStrength;
            this.interpKind = interpKind;
            this.scoreMetric = scoreMetric;
            this.textureThreshold = textureThreshold;
            this.tissueAreaThreshold = tissueAreaThreshold;
        }

        // Validation with detailed feedback
        List<String> validate() {
            List<String> warnings = new ArrayList<>();

            if (nSteps <= 0) {
                warnings.add("n_steps must be positive");
            } else if (nSteps > 50) {
                warnings.add("n_steps > 50 may be unnecessarily slow (typical range: 5-20)");
            }

            if (searchRangeUm <= 0) {
                warnings.add("search_range_um must be positive");
            } else if (searchRangeUm > 1000) {
                warnings.add("search_range_um > 1000 um is very large (typical range: 10-50 um)");
            }

            if (nTiles <= 0) {
                warnings.add("n_tiles must be positive");
            } else if (nTiles > 20) {
                warnings.add("n_tiles > 20 may cause infrequent autofocus (typical range: 3-10)");
            }

            if (interpStrength <= 0) {
                warnings.add("interp_strength must be positive");
            } else if (interpStrength > 1000) {
                warnings.add("interp_strength > 1000 may be unnecessarily high (typical: 50-200)");
            }

            if (interpKind == null || interpKind.isEmpty()) {
                warnings.add("interp_kind must be specified");
            }

            if (scoreMetric == null || scoreMetric.isEmpty()) {
                warnings.add("score_metric must be specified");
            }

            if (textureThreshold <= 0) {
                warnings.add("texture_threshold must be positive");
            } else if (textureThreshold > 0.1) {
                warnings.add("texture_threshold > 0.1 is very high (typical range: 0.005-0.030)");
            }

            if (tissueAreaThreshold <= 0) {
                warnings.add("tissue_area_threshold must be positive");
            } else if (tissueAreaThreshold > 0.5) {
                warnings.add("tissue_area_threshold > 0.5 is very high (typical range: 0.05-0.30)");
            }

            return warnings;
        }
    }

    /**
     * Main entry point for the autofocus editor workflow
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                showAutofocusEditorDialog();
            } catch (Exception e) {
                logger.error("Error in autofocus editor workflow", e);
                Dialogs.showErrorMessage("Autofocus Editor Error",
                    "Failed to open autofocus editor: " + e.getMessage());
            }
        });
    }

    /**
     * Show the autofocus editor dialog
     */
    private static void showAutofocusEditorDialog() throws IOException {
        // Get microscope config path from preferences
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error",
                "No microscope configuration file set in preferences.");
            return;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            Dialogs.showErrorMessage("Configuration Error",
                "Microscope configuration file not found: " + configPath);
            return;
        }

        // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
        String configFilename = configFile.getName();
        String microscopeName = extractMicroscopeName(configFilename);

        // Construct autofocus config path
        File configDir = configFile.getParentFile();
        File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");

        logger.info("Autofocus editor using config: {}", autofocusFile.getAbsolutePath());

        // Load objectives from main config
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        List<String> objectives = loadObjectivesFromConfig(configManager);

        if (objectives.isEmpty()) {
            Dialogs.showErrorMessage("Configuration Error",
                "No objectives found in microscope configuration.");
            return;
        }

        // Load existing autofocus settings (if file exists)
        Map<String, AutofocusSettings> existingSettings = loadAutofocusSettings(autofocusFile);

        // Create working copy with defaults for all objectives
        Map<String, AutofocusSettings> workingSettings = new LinkedHashMap<>();
        logger.info("Creating working settings from {} objectives and {} existing settings",
            objectives.size(), existingSettings.size());
        logger.info("Objectives list: {}", objectives);
        logger.info("Existing settings keys: {}", existingSettings.keySet());

        for (String obj : objectives) {
            logger.info("Processing objective: '{}'", obj);
            if (existingSettings.containsKey(obj)) {
                AutofocusSettings existing = existingSettings.get(obj);
                logger.info("  FOUND in existingSettings: n_steps={}", existing.nSteps);
                workingSettings.put(obj, new AutofocusSettings(obj, existing.nSteps, existing.searchRangeUm,
                    existing.nTiles, existing.interpStrength, existing.interpKind, existing.scoreMetric,
                    existing.textureThreshold, existing.tissueAreaThreshold));
            } else {
                logger.info("  NOT FOUND in existingSettings - using defaults");
                // Use defaults: n_steps=9, search_range=15um, n_tiles=5, interp_strength=100,
                // interp_kind=quadratic, score_metric=laplacian_variance,
                // texture_threshold=0.005, tissue_area_threshold=0.2
                workingSettings.put(obj, new AutofocusSettings(obj, 9, 15.0, 5, 100, "quadratic",
                    "laplacian_variance", 0.005, 0.2));
            }
        }

        // Create dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Autofocus Configuration Editor");
        dialog.setHeaderText("Edit autofocus parameters for " + microscopeName + " microscope\n" +
                "Settings will be saved to: " + autofocusFile.getName());

        // Create UI
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));

        // Objective selection
        Label objectiveLabel = new Label("Select Objective:");
        ComboBox<String> objectiveCombo = new ComboBox<>();
        objectiveCombo.getItems().addAll(objectives);
        objectiveCombo.setValue(objectives.get(0));

        // Parameter inputs
        GridPane paramGrid = new GridPane();
        paramGrid.setHgap(10);
        paramGrid.setVgap(10);
        paramGrid.setPadding(new Insets(10));

        Label nStepsLabel = new Label("n_steps:");
        Spinner<Integer> nStepsSpinner = new Spinner<>(1, 100, 9, 1);
        nStepsSpinner.setEditable(true);
        nStepsSpinner.setPrefWidth(100);
        nStepsSpinner.setTooltip(new Tooltip(
            "Number of Z positions sampled during autofocus.\n\n" +
            "Higher values (15-30):\n" +
            "  + More accurate focus finding\n" +
            "  + Better for thick samples\n" +
            "  - Slower autofocus (~2-3x time)\n\n" +
            "Lower values (5-11):\n" +
            "  + Faster autofocus\n" +
            "  + Adequate for thin, flat samples\n" +
            "  - May miss optimal focus on thick samples\n\n" +
            "Typical: 9-15 steps"
        ));
        Label nStepsDesc = new Label("(Number of Z positions to sample)");
        nStepsDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label searchRangeLabel = new Label("search_range_um:");
        TextField searchRangeField = new TextField("15.0");
        searchRangeField.setPrefWidth(100);
        searchRangeField.setTooltip(new Tooltip(
            "Total Z range to search, centered on current position.\n\n" +
            "Larger range (30-50um):\n" +
            "  + Finds focus even when stage is far off\n" +
            "  + Better for initial acquisition setup\n" +
            "  - Slower if many steps used\n\n" +
            "Smaller range (10-20um):\n" +
            "  + Faster autofocus\n" +
            "  + Works well when stage is pre-leveled\n" +
            "  - May fail if sample is very tilted\n\n" +
            "Typical: 15-25um for most samples"
        ));
        Label searchRangeDesc = new Label("(Total Z range in micrometers)");
        searchRangeDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label nTilesLabel = new Label("n_tiles:");
        Spinner<Integer> nTilesSpinner = new Spinner<>(1, 50, 5, 1);
        nTilesSpinner.setEditable(true);
        nTilesSpinner.setPrefWidth(100);
        nTilesSpinner.setTooltip(new Tooltip(
            "Spatial frequency: Autofocus runs every N tiles.\n\n" +
            "Lower values (1-3):\n" +
            "  + More frequent autofocus\n" +
            "  + Better tracking of uneven samples\n" +
            "  - Significantly slower acquisition\n" +
            "  - More wear on Z motor\n\n" +
            "Higher values (5-10):\n" +
            "  + Faster acquisition\n" +
            "  + Less mechanical wear\n" +
            "  - May lose focus on tilted samples\n\n" +
            "Typical: 5 tiles (good balance)\n" +
            "Use 1-3 for tilted or curved samples"
        ));
        Label nTilesDesc = new Label("(Autofocus every N tiles)");
        nTilesDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpStrengthLabel = new Label("interp_strength:");
        Spinner<Integer> interpStrengthSpinner = new Spinner<>(10, 1000, 100, 10);
        interpStrengthSpinner.setEditable(true);
        interpStrengthSpinner.setPrefWidth(100);
        interpStrengthSpinner.setTooltip(new Tooltip(
            "Density of interpolated points in focus curve.\n\n" +
            "Higher values (150-200):\n" +
            "  + Smoother focus curve fitting\n" +
            "  + More precise peak finding\n" +
            "  - Minimal speed impact\n\n" +
            "Lower values (50-100):\n" +
            "  + Slightly faster computation\n" +
            "  + Usually sufficient for most samples\n\n" +
            "Typical: 100 (good default)\n" +
            "Increase to 150-200 if autofocus is inconsistent"
        ));
        Label interpStrengthDesc = new Label("(Interpolation density factor)");
        interpStrengthDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label interpKindLabel = new Label("interp_kind:");
        ComboBox<String> interpKindCombo = new ComboBox<>();
        interpKindCombo.getItems().addAll("linear", "quadratic", "cubic");
        interpKindCombo.setValue("quadratic");
        interpKindCombo.setPrefWidth(150);
        interpKindCombo.setTooltip(new Tooltip(
            "Interpolation method for focus curve fitting.\n\n" +
            "Linear:\n" +
            "  + Simple and fast\n" +
            "  - Less accurate peak detection\n\n" +
            "Quadratic (recommended):\n" +
            "  + Good balance of speed and accuracy\n" +
            "  + Smooth parabolic curve fitting\n" +
            "  + Works well for most samples\n\n" +
            "Cubic:\n" +
            "  + Most accurate curve fitting\n" +
            "  - Can be sensitive to noise\n" +
            "  - May overfit sparse data\n\n" +
            "Typical: quadratic for most applications"
        ));
        Label interpKindDesc = new Label("(Interpolation method)");
        interpKindDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label scoreMetricLabel = new Label("score_metric:");
        ComboBox<String> scoreMetricCombo = new ComboBox<>();
        scoreMetricCombo.getItems().addAll(
            "laplacian_variance",
            "sobel",
            "brenner_gradient",
            "robust_sharpness",
            "hybrid_sharpness"
        );
        scoreMetricCombo.setValue("laplacian_variance");
        scoreMetricCombo.setPrefWidth(200);
        scoreMetricCombo.setTooltip(new Tooltip(
            "Algorithm for measuring image sharpness.\n\n" +
            "laplacian_variance (~5ms, recommended):\n" +
            "  + Fast and reliable for most samples\n" +
            "  + Good for uniform tissue\n\n" +
            "sobel (~5ms):\n" +
            "  + Edge-sensitive metric\n" +
            "  + Good for high-contrast features\n\n" +
            "brenner_gradient (~3ms):\n" +
            "  + Fastest option\n" +
            "  - Less robust on noisy images\n\n" +
            "robust_sharpness (~20ms):\n" +
            "  + Resistant to debris/particles\n" +
            "  + Best for contaminated samples\n" +
            "  - Slower autofocus\n\n" +
            "hybrid_sharpness (~8ms):\n" +
            "  + Balanced speed and robustness\n" +
            "  + Good compromise for varied samples"
        ));
        Label scoreMetricDesc = new Label("(Focus sharpness metric)");
        scoreMetricDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label textureThresholdLabel = new Label("texture_threshold:");
        TextField textureThresholdField = new TextField("0.005");
        textureThresholdField.setPrefWidth(100);
        textureThresholdField.setTooltip(new Tooltip(
            "Minimum texture variance required for tissue detection.\n" +
            "Controls whether autofocus runs at a position.\n\n" +
            "Lower values (0.005-0.010):\n" +
            "  + More sensitive - detects smooth tissue\n" +
            "  + Accepts homogeneous samples\n" +
            "  + Better for uniform staining\n" +
            "  - May accept out-of-focus areas\n" +
            "  - Risk of autofocus on background\n\n" +
            "Higher values (0.015-0.030):\n" +
            "  + More selective - requires textured tissue\n" +
            "  + Rejects blurry or empty areas\n" +
            "  - May skip smooth but valid tissue\n" +
            "  - Can cause missed autofocus points\n\n" +
            "Typical: 0.005 for smooth tissue, 0.010-0.015 for textured\n" +
            "Tune if seeing 'Insufficient tissue' warnings"
        ));
        Label textureThresholdDesc = new Label("(Min texture for tissue detection, typical: 0.005-0.030)");
        textureThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label tissueAreaThresholdLabel = new Label("tissue_area_threshold:");
        TextField tissueAreaThresholdField = new TextField("0.2");
        tissueAreaThresholdField.setPrefWidth(100);
        tissueAreaThresholdField.setTooltip(new Tooltip(
            "Minimum fraction of image that must contain tissue.\n" +
            "Determines if enough tissue is present for autofocus.\n\n" +
            "Lower values (0.05-0.15):\n" +
            "  + Accepts sparse tissue coverage\n" +
            "  + Runs autofocus at edges of sample\n" +
            "  + Better for small or fragmented samples\n" +
            "  - May autofocus on debris or artifacts\n" +
            "  - Less reliable on mostly-empty tiles\n\n" +
            "Higher values (0.20-0.30):\n" +
            "  + Requires substantial tissue presence\n" +
            "  + More reliable autofocus targets\n" +
            "  + Rejects edge tiles with partial coverage\n" +
            "  - May skip valid tissue at sample edges\n" +
            "  - Can defer too many autofocus points\n\n" +
            "Typical: 0.2 (20% coverage)\n" +
            "Lower to 0.1-0.15 for sparse or small samples\n" +
            "Raise to 0.25-0.3 if autofocus on background/debris"
        ));
        Label tissueAreaThresholdDesc = new Label("(Min tissue coverage fraction, typical: 0.05-0.30)");
        tissueAreaThresholdDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        paramGrid.add(nStepsLabel, 0, 0);
        paramGrid.add(nStepsSpinner, 1, 0);
        paramGrid.add(nStepsDesc, 2, 0);

        paramGrid.add(searchRangeLabel, 0, 1);
        paramGrid.add(searchRangeField, 1, 1);
        paramGrid.add(searchRangeDesc, 2, 1);

        paramGrid.add(nTilesLabel, 0, 2);
        paramGrid.add(nTilesSpinner, 1, 2);
        paramGrid.add(nTilesDesc, 2, 2);

        paramGrid.add(interpStrengthLabel, 0, 3);
        paramGrid.add(interpStrengthSpinner, 1, 3);
        paramGrid.add(interpStrengthDesc, 2, 3);

        paramGrid.add(interpKindLabel, 0, 4);
        paramGrid.add(interpKindCombo, 1, 4);
        paramGrid.add(interpKindDesc, 2, 4);

        paramGrid.add(scoreMetricLabel, 0, 5);
        paramGrid.add(scoreMetricCombo, 1, 5);
        paramGrid.add(scoreMetricDesc, 2, 5);

        paramGrid.add(textureThresholdLabel, 0, 6);
        paramGrid.add(textureThresholdField, 1, 6);
        paramGrid.add(textureThresholdDesc, 2, 6);

        paramGrid.add(tissueAreaThresholdLabel, 0, 7);
        paramGrid.add(tissueAreaThresholdField, 1, 7);
        paramGrid.add(tissueAreaThresholdDesc, 2, 7);

        // Status label for validation feedback
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(500);

        // Track current objective for saving changes before switching
        // Initialize to null to skip save on first load (prevents overwriting loaded settings with UI defaults)
        final String[] currentObjective = {null};

        // Save current UI values to working settings
        Runnable saveCurrentSettings = () -> {
            try {
                int nSteps = nStepsSpinner.getValue();
                double searchRange = Double.parseDouble(searchRangeField.getText());
                int nTiles = nTilesSpinner.getValue();
                int interpStrength = interpStrengthSpinner.getValue();
                String interpKind = interpKindCombo.getValue();
                String scoreMetric = scoreMetricCombo.getValue();
                double textureThreshold = Double.parseDouble(textureThresholdField.getText());
                double tissueAreaThreshold = Double.parseDouble(tissueAreaThresholdField.getText());

                workingSettings.put(currentObjective[0],
                    new AutofocusSettings(currentObjective[0], nSteps, searchRange, nTiles,
                        interpStrength, interpKind, scoreMetric, textureThreshold, tissueAreaThreshold));
            } catch (NumberFormatException ex) {
                logger.warn("Invalid numeric input when saving settings");
            }
        };

        // Load settings from working copy for selected objective
        Runnable loadSettingsForObjective = () -> {
            // First save current UI state
            if (currentObjective[0] != null) {
                saveCurrentSettings.run();
            }

            // Update current objective
            String selectedObjective = objectiveCombo.getValue();
            currentObjective[0] = selectedObjective;

            logger.info("Loading settings for objective: {}", selectedObjective);
            logger.info("Working settings contains {} objectives: {}", workingSettings.size(), workingSettings.keySet());
            logger.info("Existing settings contains {} objectives: {}", existingSettings.size(), existingSettings.keySet());

            // Load from working settings
            AutofocusSettings settings = workingSettings.get(selectedObjective);

            if (settings != null) {
                logger.info("Found settings for {}: n_steps={}, search_range={}, texture_threshold={}, tissue_area_threshold={}",
                    selectedObjective, settings.nSteps, settings.searchRangeUm, settings.textureThreshold, settings.tissueAreaThreshold);

                nStepsSpinner.getValueFactory().setValue(settings.nSteps);
                searchRangeField.setText(String.valueOf(settings.searchRangeUm));
                nTilesSpinner.getValueFactory().setValue(settings.nTiles);
                interpStrengthSpinner.getValueFactory().setValue(settings.interpStrength);
                interpKindCombo.setValue(settings.interpKind);
                scoreMetricCombo.setValue(settings.scoreMetric);
                textureThresholdField.setText(String.valueOf(settings.textureThreshold));
                tissueAreaThresholdField.setText(String.valueOf(settings.tissueAreaThreshold));

                if (existingSettings.containsKey(selectedObjective)) {
                    statusLabel.setText("Loaded existing settings for " + selectedObjective);
                    logger.info("UI populated with existing settings for {}", selectedObjective);
                } else {
                    statusLabel.setText("Using default values for " + selectedObjective);
                    logger.info("UI populated with default values for {}", selectedObjective);
                }
            } else {
                logger.warn("No settings found in workingSettings for objective: {}", selectedObjective);
            }
        };

        objectiveCombo.setOnAction(e -> loadSettingsForObjective.run());
        loadSettingsForObjective.run(); // Load initial settings

        // "Write to file" button
        Button writeButton = new Button("Write to File");
        writeButton.setOnAction(e -> {
            try {
                // Save current UI state to working settings
                saveCurrentSettings.run();

                // Validate all settings
                boolean hasErrors = false;
                for (AutofocusSettings settings : workingSettings.values()) {
                    List<String> warnings = settings.validate();
                    if (!warnings.isEmpty()) {
                        boolean proceed = Dialogs.showConfirmDialog(
                            "Validation Warnings for " + settings.objective,
                            String.join("\n", warnings) + "\n\nContinue saving?"
                        );
                        if (!proceed) {
                            hasErrors = true;
                            break;
                        }
                    }
                }

                if (hasErrors) {
                    return;
                }

                // Save to file
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved successfully to " + autofocusFile.getName());
                statusLabel.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved to: {}", autofocusFile.getAbsolutePath());

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings", ex);
                Dialogs.showErrorMessage("Save Error", "Failed to save settings: " + ex.getMessage());
            }
        });

        // "Test Standard Autofocus" button
        Button testStandardButton = new Button("Test Standard Autofocus");
        testStandardButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification("Validation Warnings",
                            "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved - running standard autofocus test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before standard test");

                // Determine output path for test results (same directory as config file)
                // Note: configDir is already defined earlier in this method (line 173)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the STANDARD test workflow
                // Note: TestAutofocusWorkflow will run async and show its own dialogs
                TestAutofocusWorkflow.runStandard(testOutputPath);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Standard autofocus test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error",
                    "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start standard autofocus test", ex);
                Dialogs.showErrorMessage("Test Error",
                    "Failed to start standard autofocus test: " + ex.getMessage());
            }
        });

        // "Test Adaptive Autofocus" button
        Button testAdaptiveButton = new Button("Test Adaptive Autofocus");
        testAdaptiveButton.setOnAction(e -> {
            try {
                // First, save current UI state to working settings
                saveCurrentSettings.run();

                // Validate current settings
                String currentObj = objectiveCombo.getValue();
                AutofocusSettings currentSettings = workingSettings.get(currentObj);

                if (currentSettings != null) {
                    List<String> warnings = currentSettings.validate();
                    if (!warnings.isEmpty()) {
                        Dialogs.showWarningNotification("Validation Warnings",
                            "Current settings have warnings:\n" + String.join("\n", warnings));
                    }
                }

                // Save to file first so test uses current settings
                saveAutofocusSettings(autofocusFile, workingSettings);
                statusLabel.setText("Settings saved - running adaptive autofocus test...");
                statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                logger.info("Autofocus settings saved before adaptive test");

                // Determine output path for test results (same directory as config file)
                String testOutputPath = new File(configDir, "autofocus_tests").getAbsolutePath();
                logger.info("Using autofocus test output path: {}", testOutputPath);

                // Run the ADAPTIVE test workflow
                // Note: TestAutofocusWorkflow will run async and show its own dialogs
                TestAutofocusWorkflow.runAdaptive(testOutputPath);

                // Update status after launching test
                Platform.runLater(() -> {
                    statusLabel.setText("Adaptive autofocus test launched - check for results dialog");
                    statusLabel.setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                });

            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Input Error", "Please enter valid numeric values before testing.");
            } catch (IOException ex) {
                logger.error("Failed to save autofocus settings before test", ex);
                Dialogs.showErrorMessage("Save Error",
                    "Failed to save settings before test: " + ex.getMessage());
            } catch (Exception ex) {
                logger.error("Failed to start adaptive autofocus test", ex);
                Dialogs.showErrorMessage("Test Error",
                    "Failed to start adaptive autofocus test: " + ex.getMessage());
            }
        });

        // Button row with write and both test buttons
        HBox buttonRow = new HBox(10, writeButton, testStandardButton, testAdaptiveButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        // Layout
        HBox objectiveRow = new HBox(10, objectiveLabel, objectiveCombo);
        objectiveRow.setAlignment(Pos.CENTER_LEFT);

        mainLayout.getChildren().addAll(
            objectiveRow,
            new Separator(),
            paramGrid,
            statusLabel,
            new Separator(),
            buttonRow
        );

        dialog.getDialogPane().setContent(mainLayout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // OK button behavior - save if changed
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setOnAction(e -> {
            writeButton.fire(); // Trigger save
        });

        dialog.showAndWait();
    }

    /**
     * Extract microscope name from config filename
     * E.g., "config_PPM.yml" -> "PPM"
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present
        if (nameWithoutExt.startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
    }

    /**
     * Load list of objectives from main microscope config
     */
    private static List<String> loadObjectivesFromConfig(MicroscopeConfigManager configManager) {
        List<String> objectives = new ArrayList<>();

        try {
            Map<String, Object> config = configManager.getAllConfig();
            Map<String, Object> acqProfiles = (Map<String, Object>) config.get("acq_profiles_new");

            if (acqProfiles != null) {
                List<Map<String, Object>> defaults = (List<Map<String, Object>>) acqProfiles.get("defaults");

                if (defaults != null) {
                    for (Map<String, Object> entry : defaults) {
                        String objective = (String) entry.get("objective");
                        if (objective != null && !objectives.contains(objective)) {
                            objectives.add(objective);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error loading objectives from config", e);
        }

        return objectives;
    }

    /**
     * Load autofocus settings from YAML file
     */
    private static Map<String, AutofocusSettings> loadAutofocusSettings(File autofocusFile) {
        Map<String, AutofocusSettings> settings = new LinkedHashMap<>();

        if (!autofocusFile.exists()) {
            logger.info("Autofocus config file does not exist yet: {}", autofocusFile.getAbsolutePath());
            return settings;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));

            if (data != null) {
                List<Map<String, Object>> afSettings = (List<Map<String, Object>>) data.get("autofocus_settings");

                if (afSettings != null) {
                    for (Map<String, Object> entry : afSettings) {
                        String objective = (String) entry.get("objective");
                        int nSteps = ((Number) entry.get("n_steps")).intValue();
                        double searchRange = ((Number) entry.get("search_range_um")).doubleValue();
                        int nTiles = ((Number) entry.get("n_tiles")).intValue();

                        // New parameters with defaults for backward compatibility
                        int interpStrength = entry.containsKey("interp_strength") ?
                            ((Number) entry.get("interp_strength")).intValue() : 100;
                        String interpKind = entry.containsKey("interp_kind") ?
                            (String) entry.get("interp_kind") : "quadratic";
                        String scoreMetric = entry.containsKey("score_metric") ?
                            (String) entry.get("score_metric") : "laplacian_variance";
                        double textureThreshold = entry.containsKey("texture_threshold") ?
                            ((Number) entry.get("texture_threshold")).doubleValue() : 0.005;
                        double tissueAreaThreshold = entry.containsKey("tissue_area_threshold") ?
                            ((Number) entry.get("tissue_area_threshold")).doubleValue() : 0.2;

                        logger.info("Loaded from YAML - objective='{}', n_steps={}, search_range={}, texture_threshold={}, tissue_area_threshold={}",
                            objective, nSteps, searchRange, textureThreshold, tissueAreaThreshold);

                        settings.put(objective, new AutofocusSettings(objective, nSteps, searchRange, nTiles,
                            interpStrength, interpKind, scoreMetric, textureThreshold, tissueAreaThreshold));
                    }
                }
            }

            logger.info("Loaded autofocus settings for {} objectives", settings.size());
        } catch (Exception e) {
            logger.error("Error loading autofocus settings from file", e);
        }

        return settings;
    }

    /**
     * Save autofocus settings to YAML file
     */
    private static void saveAutofocusSettings(File autofocusFile, Map<String, AutofocusSettings> settings) throws IOException {
        // Build YAML structure
        List<Map<String, Object>> afSettingsList = new ArrayList<>();

        for (AutofocusSettings setting : settings.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("objective", setting.objective);
            entry.put("n_steps", setting.nSteps);
            entry.put("search_range_um", setting.searchRangeUm);
            entry.put("n_tiles", setting.nTiles);
            entry.put("interp_strength", setting.interpStrength);
            entry.put("interp_kind", setting.interpKind);
            entry.put("score_metric", setting.scoreMetric);
            entry.put("texture_threshold", setting.textureThreshold);
            entry.put("tissue_area_threshold", setting.tissueAreaThreshold);
            afSettingsList.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("autofocus_settings", afSettingsList);

        // Configure YAML dumper for clean output
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        // Write with header comment
        try (FileWriter writer = new FileWriter(autofocusFile)) {
            writer.write("# ========== AUTOFOCUS CONFIGURATION ==========\n");
            writer.write("# Autofocus parameters per objective\n");
            writer.write("# These settings control the autofocus behavior during image acquisition\n");
            writer.write("#\n");
            writer.write("# Parameters:\n");
            writer.write("#   n_steps: Number of Z positions to sample during autofocus (higher = more accurate but slower)\n");
            writer.write("#   search_range_um: Total Z range to search in micrometers (centered on current position)\n");
            writer.write("#   n_tiles: Spatial frequency - autofocus runs every N tiles during large acquisitions (lower = more frequent but slower)\n");
            writer.write("#   interp_strength: Interpolation density factor for focus curve fitting (typical: 50-200)\n");
            writer.write("#   interp_kind: Interpolation method - 'linear', 'quadratic', or 'cubic'\n");
            writer.write("#   score_metric: Focus sharpness metric - 'laplacian_variance' (fast ~5ms), 'sobel' (fast ~5ms),\n");
            writer.write("#                 'brenner_gradient' (fastest ~3ms), 'robust_sharpness' (particle-resistant ~20ms),\n");
            writer.write("#                 or 'hybrid_sharpness' (balanced ~8ms)\n");
            writer.write("#   texture_threshold: Minimum texture variance for tissue detection (typical: 0.005-0.030)\n");
            writer.write("#                      Lower values accept smoother tissue, higher values require more texture\n");
            writer.write("#   tissue_area_threshold: Minimum fraction of image that must contain tissue (typical: 0.05-0.30)\n");
            writer.write("#                          Lower values accept sparse tissue, higher values require fuller coverage\n\n");

            yaml.dump(root, writer);
        }

        logger.info("Saved autofocus settings for {} objectives to: {}",
            settings.size(), autofocusFile.getAbsolutePath());
    }
}
