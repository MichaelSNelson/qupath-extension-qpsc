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
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

            // Use the comprehensive validation method from MicroscopeConfigManager
            List<String> errors = mgr.validateConfiguration();

            if (!errors.isEmpty()) {
                logger.error("Configuration validation failed: {}", errors);
                String errorMessage = "Configuration validation errors:\n\n• " +
                        String.join("\n• ", errors);
                Dialogs.showWarningNotification("Configuration Errors", errorMessage);
                return false;
            }

            logger.info("Configuration validation passed");
            return true;
        } catch (Exception e) {
            logger.error("Error during configuration validation", e);
            Dialogs.showErrorNotification("Configuration Validation Error",
                    "Failed to validate configuration: " + e.getMessage());
            return false;
        }

    }

    /**
     * Validates stage limits configuration.
     * Since comprehensive validation is now handled by validateConfiguration(),
     * this method is redundant and simply delegates to validateMicroscopeConfig().
     *
     * @return true if stage limits configuration is adequate
     */
    public static boolean validateStageLimitsConfig() {
        // Delegate to comprehensive validation
        return validateMicroscopeConfig();
    }

    /**
     * Dummy check to ensure hardware is accessible.
     * Replace with actual hardware checks when needed.
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
        // TODO: Implement real QuPath state checks.
        // For example:
        // - Check if annotations exist
        // - Verify project directory structure
        // - Check available memory
        return true;
    }

}