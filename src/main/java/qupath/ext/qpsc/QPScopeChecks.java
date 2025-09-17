package qupath.ext.qpsc;

import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;


/**
 * QPScopeChecks contains helper functions to verify that the QuPath environment is ready
 * for launching the QP Scope extension. It performs a series of checks:
 *
 * 1. Checks whether a project is open in QuPath.
 * 2. Checks that the expected hardware (as defined by current preferences) is accessible.
 * 3. Checks that QuPath is in the correct state.
 * 4. Validates the microscope configuration file structure and content.
 *
 * For now, the hardware and state checks are dummy checks (always returning true), but they are
 * structured so that you can later add real logic. If any check fails, a warning dialog is shown.
 */
public class QPScopeChecks {

    private static final Logger logger = LoggerFactory.getLogger(QPScopeChecks.class);

    /**
     * Checks all the necessary conditions for the extension to start.
     *
     * @return true if all conditions are met; otherwise false.
     */
    public static boolean checkEnvironment() {
        // 1. Check if a project is open in QuPath.
        if (QuPathGUI.getInstance() == null || QuPathGUI.getInstance().getProject() == null) {
            Dialogs.showWarningNotification("No Project Open",
                    "You must open a project in QuPath before launching the extension.");
            return false;
        }

        // 2. Check if the expected hardware is accessible (dummy check for now).
        if (!checkHardwareAccessible()) {
            Dialogs.showWarningNotification("Hardware Not Accessible",
                    "The hardware expected by the current preferences is not accessible.\n"
                            + "Please verify your hardware connection.");
            return false;
        }

        // 3. Check if QuPath is in the correct state (dummy check for now).
        if (!checkQuPathState()) {
            Dialogs.showWarningNotification("Incorrect QuPath State",
                    "QuPath is not in the expected state.\n"
                            + "Please ensure QuPath is properly configured before launching the extension.");
            return false;
        }

        // 4. Validate microscope configuration
        if (!validateMicroscopeConfig()) {
            return false; // Error dialogs are shown inside validateMicroscopeConfig
        }
        
        // 5. Validate stage limits configuration
        if (!validateStageLimitsConfig()) {
            return false; // Error dialogs are shown inside validateStageLimitsConfig
        }

        // All checks passed.
        return true;
    }

    /**
     * Validate the YAML config and return true if all required keys are present.
     */
    public static boolean validateMicroscopeConfig() {
        // Define the nested keys that are required for basic operation
        Set<String[]> required = Set.of(
                // Basic microscope info
                new String[]{"microscope", "name"},
                new String[]{"microscope", "type"},

                // Stage configuration - used for bounds checking
                new String[]{"stage", "limits", "x_um", "low"},
                new String[]{"stage", "limits", "x_um", "high"},
                new String[]{"stage", "limits", "y_um", "low"},
                new String[]{"stage", "limits", "y_um", "high"},
                new String[]{"stage", "limits", "z_um", "low"},
                new String[]{"stage", "limits", "z_um", "high"},

                // Core sections
                new String[]{"modalities"},
                new String[]{"acq_profiles_new"},
                new String[]{"slide_size_um"}
        );

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var mgr = MicroscopeConfigManager.getInstance(configPath);

        // First check the basic required keys
        var missing = mgr.validateRequiredKeys(required);
        if (!missing.isEmpty()) {
            logger.error("Missing required configuration keys: {}",
                    missing.stream().map(arr -> String.join(".", arr)).toList());
            Dialogs.showWarningNotification("Configuration Error",
                    "Missing required configuration sections:\n" +
                            missing.stream().map(arr -> String.join(".", arr)).toList());
            return false;
        }

        // Check modalities section
        Set<String> availableModalities = mgr.getAvailableModalities();
        if (availableModalities.isEmpty()) {
            logger.error("No modalities defined in configuration");
            Dialogs.showWarningNotification("Configuration Error",
                    "No modalities defined in configuration");
            return false;
        }

        // Check acquisition profiles
        List<Object> profiles = mgr.getList("acq_profiles_new", "profiles");
        if (profiles == null || profiles.isEmpty()) {
            logger.error("No acquisition profiles defined");
            Dialogs.showWarningNotification("Configuration Error",
                    "No acquisition profiles defined in configuration");
            return false;
        }

        // Validate that we have at least one complete profile
        boolean hasValidProfile = false;
        List<String> profileErrors = new ArrayList<>();

        // Track which objectives are used in profiles
        Set<String> usedObjectives = new HashSet<>();

        for (Object profileObj : profiles) {
            if (!(profileObj instanceof Map)) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) profileObj;

            String modality = (String) profile.get("modality");
            String objective = (String) profile.get("objective");
            String detector = (String) profile.get("detector");

            if (modality == null || objective == null || detector == null) {
                profileErrors.add(String.format("Profile missing required fields: modality=%s, objective=%s, detector=%s",
                        modality, objective, detector));
                continue;
            }

            usedObjectives.add(objective);

            // Check if modality exists
            if (!availableModalities.contains(modality)) {
                profileErrors.add(String.format("Profile references unknown modality: %s", modality));
                continue;
            }

            // Check if detector exists in resources
            Map<String, Object> detectorSection = mgr.getResourceSection("id_detector");
            if (detectorSection == null || !detectorSection.containsKey(detector)) {
                profileErrors.add(String.format("Profile references unknown detector: %s", detector));
                continue;
            }

            // Validate detector dimensions
            @SuppressWarnings("unchecked")
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detector);
            Integer width = detectorData != null && detectorData.get("width_px") instanceof Number ?
                    ((Number) detectorData.get("width_px")).intValue() : null;
            Integer height = detectorData != null && detectorData.get("height_px") instanceof Number ?
                    ((Number) detectorData.get("height_px")).intValue() : null;

            if (width == null || height == null || width <= 0 || height <= 0) {
                profileErrors.add(String.format("Detector %s has invalid dimensions: width=%s, height=%s",
                        detector, width, height));
                continue;
            }

            // Check if settings exist
            if (!profile.containsKey("settings")) {
                profileErrors.add(String.format("Profile %s/%s/%s missing settings",
                        modality, objective, detector));
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) profile.get("settings");

            // Validate exposure settings
            if (!settings.containsKey("exposures_ms")) {
                profileErrors.add(String.format("Profile %s/%s/%s missing exposures_ms",
                        modality, objective, detector));
                continue;
            }

            // Check pixel size (should come from defaults or profile)
            double pixelSize = mgr.getModalityPixelSize(modality, objective, detector);
            if (pixelSize <= 0) {
                profileErrors.add(String.format("Invalid pixel size for %s/%s/%s: %f",
                        modality, objective, detector, pixelSize));
                continue;
            }

            logger.debug("Valid profile found: {}/{}/{} with pixel size {} µm",
                    modality, objective, detector, pixelSize);
            hasValidProfile = true;
        }

        // Check defaults for all used objectives
        List<Object> defaults = mgr.getList("acq_profiles_new", "defaults");
        if (defaults != null) {
            for (String objective : usedObjectives) {
                boolean hasDefaults = false;
                for (Object defaultObj : defaults) {
                    if (defaultObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> def = (Map<String, Object>) defaultObj;
                        if (objective.equals(def.get("objective"))) {
                            hasDefaults = true;

                            // Validate autofocus parameters
                            Map<String, Object> autofocus = mgr.getAutofocusParams(objective);
                            if (autofocus != null) {
                                Integer nSteps = mgr.getAutofocusIntParam(objective, "n_steps");
                                Integer searchRange = mgr.getAutofocusIntParam(objective, "search_range_um");

                                if (nSteps == null || nSteps <= 0 || searchRange == null || searchRange <= 0) {
                                    logger.warn("Incomplete autofocus configuration for objective {}: n_steps={}, search_range_um={}",
                                            objective, nSteps, searchRange);
                                    profileErrors.add(String.format("Incomplete autofocus for objective %s", objective));
                                }
                            }
                            break;
                        }
                    }
                }

                if (!hasDefaults) {
                    logger.warn("No defaults found for objective: {}", objective);
                    profileErrors.add(String.format("No defaults defined for objective: %s", objective));
                }
            }
        }

        // Check for PPM-specific requirements if PPM modality is present
        if (availableModalities.contains("ppm")) {
            // Check rotation stage
            String rotationDevice = mgr.getString("modalities", "ppm", "rotation_stage", "device");
            if (rotationDevice == null) {
                profileErrors.add("PPM modality missing rotation stage configuration");
            }

            // Check rotation angles
            List<Object> rotationAngles = mgr.getList("modalities", "ppm", "rotation_angles");
            if (rotationAngles == null || rotationAngles.isEmpty()) {
                profileErrors.add("PPM modality missing rotation angles");
            }
        }

        // Report all errors if any were found
        if (!profileErrors.isEmpty()) {
            logger.error("Configuration validation errors: {}", profileErrors);
            String errorMessage = "Configuration validation errors:\n\n" +
                    String.join("\n", profileErrors);
            Dialogs.showWarningNotification("Configuration Errors", errorMessage);
            return false;
        }

        if (!hasValidProfile) {
            logger.error("No valid acquisition profiles found");
            Dialogs.showWarningNotification("Configuration Error",
                    "No valid acquisition profiles found in configuration");
            return false;
        }

        // Additional validation using ConfigManager's built-in validation
        List<String> configErrors = mgr.validateConfiguration();
        if (!configErrors.isEmpty()) {
            logger.error("Configuration validation failed: {}", configErrors);
            Dialogs.showWarningNotification("Configuration Error",
                    "Configuration validation failed:\n" + String.join("\n", configErrors));
            return false;
        }

        logger.info("Microscope configuration validation passed");
        return true;
    }

    /**
     * Dummy check for hardware accessibility.
     * Replace this with real logic to check that the hardware is connected
     * or available as expected by your preferences.
     *
     * @return true if hardware is accessible
     */
    private static boolean checkHardwareAccessible() {
        // TODO: Implement real hardware accessibility check.
        // For example:
        // - Check if PycroManager is running
        // - Check if MicroManager core is accessible
        // - Verify stage controller is responding
        // - Verify camera/detector is connected
        return true;
    }

    /**
     * Dummy check to ensure QuPath is in the correct state.
     * Replace with actual state validations if needed.
     *
     * @return true if QuPath is in the correct state
     */
    private static boolean checkQuPathState() {
        // TODO: Implement real state checking logic.
        // For example:
        // - Check if an image is currently open
        // - Check if annotations exist
        // - Verify project directory structure
        // - Check available memory
        return true;
    }
    
    /**
     * Validates stage limits configuration.
     * Stage limits are critical for preventing hardware damage from out-of-bounds movements.
     *
     * @return true if stage limits configuration is adequate
     */
    public static boolean validateStageLimitsConfig() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            
            List<String> missingConfigs = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            // Check stage limits configuration
            Map<String, Double> xyLimits = mgr.getStageXYLimits();
            Map<String, Double> zLimits = mgr.getStageZLimits();
            
            if (xyLimits == null || xyLimits.isEmpty()) {
                missingConfigs.add("Stage XY limits (x_low, x_high, y_low, y_high)");
                warnings.add("• Stage XY movements will not be bounds-checked");
                warnings.add("• Risk of hardware damage from out-of-bounds movements");
            } else {
                // Validate XY limits are complete
                String[] requiredXYKeys = {"x_low", "x_high", "y_low", "y_high"};
                for (String key : requiredXYKeys) {
                    if (!xyLimits.containsKey(key) || xyLimits.get(key) == null) {
                        missingConfigs.add("Stage XY limit: " + key);
                    }
                }
            }
            
            if (zLimits == null || zLimits.isEmpty()) {
                missingConfigs.add("Stage Z limits (z_low, z_high)");
                warnings.add("• Stage Z movements will not be bounds-checked");
                warnings.add("• Risk of hardware damage from out-of-bounds movements");
            } else {
                // Validate Z limits are complete
                String[] requiredZKeys = {"z_low", "z_high"};
                for (String key : requiredZKeys) {
                    if (!zLimits.containsKey(key) || zLimits.get(key) == null) {
                        missingConfigs.add("Stage Z limit: " + key);
                    }
                }
            }
            
            
            // If we have missing critical configurations, block progress
            if (!missingConfigs.isEmpty()) {
                StringBuilder errorMessage = new StringBuilder();
                errorMessage.append("Critical stage limits configuration is missing:\n\n");
                
                errorMessage.append("Missing configurations:\n");
                for (String missing : missingConfigs) {
                    errorMessage.append("• ").append(missing).append("\n");
                }
                
                if (!warnings.isEmpty()) {
                    errorMessage.append("\nPotential issues:\n");
                    for (String warning : warnings) {
                        errorMessage.append(warning).append("\n");
                    }
                }
                
                errorMessage.append("\nThe extension is disabled until stage limits are properly configured.");
                errorMessage.append("\nPlease add the missing configuration to your microscope YAML file.");
                
                logger.error("Stage limits validation failed: {}", missingConfigs);
                Dialogs.showErrorNotification("Stage Limits Configuration Required", errorMessage.toString());
                return false;
            }
            
            logger.info("Stage limits configuration validated successfully");
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating stage limits configuration", e);
            Dialogs.showErrorNotification("Configuration Validation Error", 
                    "Failed to validate stage limits configuration: " + e.getMessage());
            return false;
        }
    }

}