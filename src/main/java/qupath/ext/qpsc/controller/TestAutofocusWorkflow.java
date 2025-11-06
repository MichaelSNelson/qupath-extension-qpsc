package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * TestAutofocusWorkflow - Runs autofocus test at current microscope position
 *
 * <p>This workflow provides interactive autofocus testing with diagnostic output:
 * <ol>
 *   <li>Reads current objective from config</li>
 *   <li>Uses settings from autofocus_{microscope}.yml</li>
 *   <li>Performs autofocus scan at current XY position</li>
 *   <li>Generates diagnostic plot showing focus curve</li>
 *   <li>Displays results and opens plot for review</li>
 * </ol>
 *
 * <p>Key features:
 * <ul>
 *   <li>Uses saved autofocus settings from config file</li>
 *   <li>Comprehensive diagnostic plotting</li>
 *   <li>Shows raw vs interpolated focus peaks</li>
 *   <li>Reports Z shift from starting position</li>
 *   <li>Helps troubleshoot autofocus issues</li>
 * </ul>
 *
 * <p>This workflow is designed to be called from:
 * <ul>
 *   <li>Autofocus Editor dialog (test button)</li>
 *   <li>Standalone menu item for quick testing</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class TestAutofocusWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(TestAutofocusWorkflow.class);

    /**
     * Main entry point for standard autofocus test workflow.
     * Uses default output path in same directory as configuration file.
     */
    public static void runStandard() {
        // Determine output path from config file location
        String defaultOutputPath = getDefaultOutputPath();
        runStandard(defaultOutputPath);
    }

    /**
     * Main entry point for adaptive autofocus test workflow.
     * Uses default output path in same directory as configuration file.
     */
    public static void runAdaptive() {
        // Determine output path from config file location
        String defaultOutputPath = getDefaultOutputPath();
        runAdaptive(defaultOutputPath);
    }

    /**
     * Get default output path for autofocus tests based on config file location.
     * Creates autofocus_tests folder at same level as config file.
     *
     * @return Path to autofocus_tests directory, or user.home fallback if config not found
     */
    private static String getDefaultOutputPath() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                File configFile = new File(configPath);
                if (configFile.exists()) {
                    // Get parent directory of config file
                    File configDir = configFile.getParentFile();

                    // Create autofocus_tests subdirectory
                    File outputDir = new File(configDir, "autofocus_tests");

                    logger.info("Using autofocus test output path: {}", outputDir.getAbsolutePath());
                    return outputDir.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to determine config-based output path, using fallback", e);
        }

        // Fallback to user home directory
        String fallbackPath = System.getProperty("user.home") + File.separator + "autofocus_tests";
        logger.info("Using fallback autofocus test output path: {}", fallbackPath);
        return fallbackPath;
    }

    /**
     * Run STANDARD autofocus test with specified output path.
     * Tests the symmetric-sweep autofocus algorithm.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     */
    public static void runStandard(String outputPath) {
        logger.info("Starting STANDARD autofocus test workflow");
        runTest(outputPath, false); // false = standard
    }

    /**
     * Run ADAPTIVE autofocus test with specified output path.
     * Tests the intelligent bidirectional search algorithm used during acquisitions.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     */
    public static void runAdaptive(String outputPath) {
        logger.info("Starting ADAPTIVE autofocus test workflow");
        runTest(outputPath, true); // true = adaptive
    }

    /**
     * Internal method to run autofocus test.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     * @param isAdaptive True for adaptive autofocus, false for standard
     */
    private static void runTest(String outputPath, boolean isAdaptive) {
        String testType = isAdaptive ? "adaptive" : "standard";
        logger.info("Starting {} autofocus test workflow", testType);

        Platform.runLater(() -> {
            try {
                // Validate microscope connection
                if (!MicroscopeController.getInstance().isConnected()) {
                    boolean connect = Dialogs.showConfirmDialog("Connection Required",
                            "Microscope server is not connected. Connect now?");

                    if (connect) {
                        try {
                            MicroscopeController.getInstance().connect();
                        } catch (IOException e) {
                            logger.error("Failed to connect to microscope server", e);
                            Dialogs.showErrorMessage("Connection Failed",
                                    "Could not connect to microscope server: " + e.getMessage());
                            return;
                        }
                    } else {
                        logger.info("Autofocus test cancelled - no connection");
                        return;
                    }
                }

                // Get configuration
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

                // Load config to get current objective
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                String objective = getCurrentObjective(configManager);

                if (objective == null) {
                    Dialogs.showErrorMessage("Configuration Error",
                            "Could not determine current objective from configuration.\n" +
                            "Please check microscope/objective_in_use setting.");
                    return;
                }

                logger.info("Testing {} autofocus for objective: {}", testType, objective);

                // Show confirmation dialog
                String dialogTitle = isAdaptive ? "Test Adaptive Autofocus" : "Test Standard Autofocus";
                String algorithmDesc = isAdaptive
                    ? "The microscope will perform an intelligent bidirectional search (same algorithm used during acquisitions)."
                    : "The microscope will perform a symmetric Z-sweep centered on current position.";

                boolean proceed = Dialogs.showConfirmDialog(dialogTitle,
                        String.format("Test %s autofocus at current position?\n\n" +
                                "Objective: %s\n" +
                                "Settings: autofocus_%s.yml\n" +
                                "Output: %s\n\n" +
                                "%s",
                                testType,
                                objective,
                                extractMicroscopeName(configFile.getName()),
                                outputPath,
                                algorithmDesc));

                if (!proceed) {
                    logger.info("{} autofocus test cancelled by user", testType);
                    return;
                }

                // Execute test in background
                CompletableFuture.runAsync(() -> {
                    executeAutofocusTest(configPath, outputPath, objective, isAdaptive);
                }).exceptionally(ex -> {
                    logger.error("Autofocus test failed", ex);
                    Platform.runLater(() -> {
                        Dialogs.showErrorMessage("Autofocus Test Error",
                                "Failed to execute autofocus test: " + ex.getMessage());
                    });
                    return null;
                });

            } catch (Exception e) {
                logger.error("Failed to start autofocus test workflow", e);
                Dialogs.showErrorMessage("Autofocus Test Error",
                        "Failed to start autofocus test: " + e.getMessage());
            }
        });
    }

    /**
     * Execute autofocus test via socket communication.
     *
     * @param isAdaptive True for adaptive autofocus, false for standard
     */
    private static void executeAutofocusTest(String configPath, String outputPath, String objective, boolean isAdaptive) {
        String testType = isAdaptive ? "adaptive" : "standard";
        logger.info("Executing {} autofocus test for objective: {}", testType, objective);

        try {
            // Get socket client from MicroscopeController
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            // Ensure connection
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Connecting to microscope server for autofocus test");
                MicroscopeController.getInstance().connect();
            }

            // Create output directory
            File outputDir = new File(outputPath);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IOException("Failed to create output directory: " + outputPath);
            }

            logger.info("Starting {} autofocus test", testType);

            // Call the appropriate test method
            Map<String, String> result;
            if (isAdaptive) {
                result = socketClient.testAdaptiveAutofocus(configPath, outputPath, objective);
            } else {
                result = socketClient.testAutofocus(configPath, outputPath, objective);
            }

            logger.info("{} autofocus test completed successfully", testType);

            // Extract results
            String plotPath = result.get("plot_path");
            String message_text = result.get("message");
            String initialZ = result.get("initial_z");
            String finalZ = result.get("final_z");
            String zShift = result.get("z_shift");

            // Show success notification on UI thread
            Platform.runLater(() -> {
                StringBuilder message = new StringBuilder(testType.substring(0, 1).toUpperCase() + testType.substring(1)
                    + " autofocus test completed successfully!\n\n");

                if (initialZ != null && finalZ != null && zShift != null) {
                    message.append(String.format("Initial Z: %s um\n", initialZ));
                    message.append(String.format("Final Z: %s um\n", finalZ));
                    message.append(String.format("Z Shift: %s um\n\n", zShift));

                    // Warn if large shift
                    try {
                        double shift = Double.parseDouble(zShift);
                        if (Math.abs(shift) > 5.0) {
                            message.append("WARNING: Large Z shift detected!\n");
                            message.append("Starting position may have been out of focus.\n\n");
                        }
                    } catch (NumberFormatException e) {
                        // Ignore parsing error
                    }
                }

                if (plotPath != null) {
                    message.append("Diagnostic plot: ").append(plotPath);
                }

                // Show dialog with option to open plot
                boolean openPlot = Dialogs.showConfirmDialog("Autofocus Test Complete",
                        message.toString() + "\n\nOpen diagnostic plot?");

                if (openPlot && plotPath != null) {
                    try {
                        File plotFile = new File(plotPath);
                        if (plotFile.exists()) {
                            Desktop.getDesktop().open(plotFile);
                            logger.info("Opened diagnostic plot: {}", plotPath);
                        } else {
                            Dialogs.showErrorMessage("File Not Found",
                                    "Diagnostic plot file not found: " + plotPath);
                        }
                    } catch (IOException e) {
                        logger.error("Failed to open diagnostic plot", e);
                        Dialogs.showErrorMessage("Error Opening Plot",
                                "Could not open diagnostic plot: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Autofocus test failed", e);
            Platform.runLater(() -> {
                Dialogs.showErrorMessage("Autofocus Test Failed",
                        "Failed to execute autofocus test: " + e.getMessage());
            });
        }
    }

    /**
     * Get current objective from microscope configuration.
     *
     * @param configManager Configuration manager instance
     * @return Objective identifier or null if not found
     */
    private static String getCurrentObjective(MicroscopeConfigManager configManager) {
        try {
            Map<String, Object> config = configManager.getAllConfig();
            Map<String, Object> microscope = (Map<String, Object>) config.get("microscope");

            if (microscope != null) {
                Object objectiveInUse = microscope.get("objective_in_use");
                if (objectiveInUse != null) {
                    return objectiveInUse.toString();
                }
            }

            logger.warn("objective_in_use not found in config, checking acq_profiles_new");

            // Fallback: try to get first objective from acq_profiles_new
            Map<String, Object> acqProfiles = (Map<String, Object>) config.get("acq_profiles_new");
            if (acqProfiles != null) {
                java.util.List<Map<String, Object>> defaults =
                        (java.util.List<Map<String, Object>>) acqProfiles.get("defaults");

                if (defaults != null && !defaults.isEmpty()) {
                    Object objective = defaults.get(0).get("objective");
                    if (objective != null) {
                        logger.info("Using first objective from acq_profiles_new: {}", objective);
                        return objective.toString();
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error reading current objective from config", e);
        }

        return null;
    }

    /**
     * Extract microscope name from config filename.
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
}
