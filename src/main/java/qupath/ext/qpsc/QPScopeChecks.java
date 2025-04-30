package qupath.ext.qpsc;

import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
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

    //TODO adjust after finalizing config and yml file structure
    /**
     * Validate the YAML config and return true if all required keys are present.
     */
    public static boolean validateMicroscopeConfig() {
        // Define the nested keys you absolutely need:
        Set<String[]> required = Set.of(
                new String[]{"microscope", "name"},
                new String[]{"fake", "key"},
                new String[]{"stage"},
                new String[]{"imagingMode"},
                new String[]{"slideSize"}
        );

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var mgr = MicroscopeConfigManager.getInstance(configPath);

        var missing = mgr.validateRequiredKeys(required);
        return missing.isEmpty();
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
