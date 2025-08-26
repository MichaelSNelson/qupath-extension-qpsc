package qupath.ext.qpsc;

import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Set;


/**
 * QPScopeChecks contains helper functions to verify that the QuPath environment is ready
 * for launching the QP Scope extension. It performs a series of checks:
 *
 * 1. Checks whether a project is open in QuPath.
 * 2. Checks that the expected hardware (as defined by current preferences) is accessible.
 * 3. Checks that QuPath is in the correct state.
 *
 * For now, the hardware and state checks are dummy checks (always returning true), but they are
 * structured so that you can later add real logic. If any check fails, a warning dialog is shown.
 */
public class QPScopeChecks {

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
        // All checks passed.
        return true;
    }

    /**
     * Validate the YAML config and return true if all required keys are present.
     */
    public static boolean validateMicroscopeConfig() {
        Logger logger = LoggerFactory.getLogger(QPScopeChecks.class);

        // Define the nested keys that are actually used in the workflows
        Set<String[]> required = Set.of(
                // Basic microscope info
                new String[]{"microscope", "name"},
                new String[]{"microscope", "type"},

                // Stage configuration - used for bounds checking
                new String[]{"stage", "limits", "x", "low"},
                new String[]{"stage", "limits", "x", "high"},
                new String[]{"stage", "limits", "y", "low"},
                new String[]{"stage", "limits", "y", "high"},
                new String[]{"stage", "limits", "z", "low"},
                new String[]{"stage", "limits", "z", "high"},

                // At least one modality should be present
                new String[]{"modalities"}
        );

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var mgr = MicroscopeConfigManager.getInstance(configPath);

        // First check the basic required keys
        var missing = mgr.validateRequiredKeys(required);
        if (!missing.isEmpty()) {
            logger.error("Missing required configuration keys: {}",
                    missing.stream().map(arr -> String.join(".", arr)).toList());
            return false;
        }

        // Additional validation: Check that at least one modality has required fields
        Map<String, Object> imagingModes = mgr.getSection("modalities");
        if (imagingModes == null || imagingModes.isEmpty()) {
            logger.error("No modalities defined in configuration");
            Dialogs.showWarningNotification("Configuration Error",
                    "No modalities defined in configuration");
            return false;
        }

        // Check that each imaging mode has the required fields
        boolean hasValidMode = false;
        String camera = mgr.getString("microscope", "default_camera");
        for (Map.Entry<String, Object> entry : imagingModes.entrySet()) {
            String modeName = entry.getKey();
            if (entry.getValue() instanceof Map) {
                // Each modality needs a pixel size for the camera and an objective id
                Double pixelSize = mgr.getDouble("modalities", modeName, "cameras", camera);
                String objective = mgr.getString("modalities", modeName, "objective", "id");

                logger.debug("Checking modality '{}': pixelSize={}, objective={}",
                        modeName, pixelSize, objective);

                if (pixelSize != null && pixelSize > 0 && objective != null) {
                    hasValidMode = true;

                    if (camera != null) {
                        logger.debug("Checking camera '{}' for modality '{}'", camera, modeName);

                        // Get the detector section from resources directly using the existing method
                        Map<String, Object> detectorSection = mgr.getResourceSection("id_detector");
                        if (detectorSection != null && detectorSection.containsKey(camera)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> cameraData = (Map<String, Object>) detectorSection.get(camera);
                            Integer width = cameraData != null && cameraData.get("width_px") instanceof Number ?
                                    ((Number) cameraData.get("width_px")).intValue() : null;
                            Integer height = cameraData != null && cameraData.get("height_px") instanceof Number ?
                                    ((Number) cameraData.get("height_px")).intValue() : null;

                            if (width == null || height == null || width <= 0 || height <= 0) {
                                logger.error("Camera {} dimensions invalid or not found (width={}, height={})",
                                        camera, width, height);
                                Dialogs.showWarningNotification("Configuration Error",
                                        String.format("Camera %s referenced but has invalid dimensions in resources", camera));
                                return false;
                            } else {
                                logger.debug("Camera {} dimensions: {}x{}", camera, width, height);
                            }
                        } else {
                            logger.error("Camera {} not found in id_detector section of resources", camera);
                            Dialogs.showWarningNotification("Configuration Error",
                                    String.format("Camera %s not found in resources file", camera));
                            return false;
                        }
                    }

                    // Validate autofocus settings if provided for this modality
                    Map<String, Object> af = mgr.getSection("modalities", modeName, "objective", "autofocus");
                    if (af != null) {
                        Integer nSteps = mgr.getInteger("modalities", modeName, "objective", "autofocus", "n_steps");
                        Double searchRange = mgr.getDouble("modalities", modeName, "objective", "autofocus", "search_range");
                        if (nSteps == null || nSteps <= 0 || searchRange == null || searchRange <= 0) {
                            logger.warn("Autofocus configuration incomplete for modality {}: n_steps={}, search_range={}",
                                    modeName, nSteps, searchRange);
                            Dialogs.showWarningNotification("Configuration Warning",
                                    "Autofocus configuration incomplete for modality " + modeName +
                                            ". Default values will be used.");
                        }
                    }
                } else {
                    logger.warn("Modality '{}' is invalid: pixelSize={}, objective={}",
                            modeName, pixelSize, objective);
                }
            }
        }

        if (!hasValidMode) {
            logger.error("No valid modalities found in configuration");
            Dialogs.showWarningNotification("Configuration Error",
                    "No valid modalities found. Each modality requires a pixel size and objective id");
            return false;
        }

        logger.info("Microscope configuration validation passed");
        return true;
    }
    /**
     * Dummy check for hardware accessibility.
     * Replace this with real logic to check that the hardware is connected
     * or available as expected by your preferences.
     */
    private static boolean checkHardwareAccessible() {
        // TODO: Implement real hardware accessibility check.
        return true;
    }

    /**
     * Dummy check to ensure QuPath is in the correct state.
     * Replace with actual state validations if needed.
     */
    private static boolean checkQuPathState() {
        // TODO: Implement real state checking logic.
        return true;
    }

}
