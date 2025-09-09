package qupath.ext.qpsc.modality.ppm;

import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

import java.util.List;
import java.util.Map;

/**
 * Manages user preferences for PPM (Polarized light Microscopy) modality configuration.
 * 
 * <p>This utility class stores and retrieves user preferences for PPM angle selection
 * and decimal exposure times. Preferences are persisted using QuPath's preference system
 * and automatically loaded from microscope configuration files when available.</p>
 * 
 * <p><strong>Supported Preferences:</strong></p>
 * <ul>
 *   <li><strong>Angle Selection:</strong> Boolean flags for each of the four PPM angles</li>
 *   <li><strong>Exposure Times:</strong> Decimal exposure values in milliseconds for precise timing</li>
 *   <li><strong>Configuration Loading:</strong> Automatic loading of default exposures from YAML config</li>
 * </ul>
 * 
 * <p>All exposure time values support decimal precision (e.g., 1.2ms, 500.0ms, 0.8ms)
 * for fine-grained exposure control across different illumination conditions.</p>
 * 
 * @author Mike Nelson
 * @since 1.0
 * @see qupath.lib.gui.prefs.PathPrefs
 * @see qupath.ext.qpsc.utilities.MicroscopeConfigManager
 */
public class PPMPreferences {

    private static final Logger logger = LoggerFactory.getLogger(PPMPreferences.class);

    // Angle selection flags
    private static final StringProperty minusSelected =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");
    private static final StringProperty zeroSelected =
            PathPrefs.createPersistentPreference("PPMZeroSelected", "true");
    private static final StringProperty plusSelected =
            PathPrefs.createPersistentPreference("PPMPlusSelected", "true");
    private static final StringProperty uncrossedSelected =
            PathPrefs.createPersistentPreference("PPMUncrossedSelected", "false");

    // Decimal exposure times in milliseconds for each angle (supports sub-millisecond precision)
    private static final StringProperty minusExposure =
            PathPrefs.createPersistentPreference("PPMMinusExposureMs", "500");
    private static final StringProperty zeroExposure =
            PathPrefs.createPersistentPreference("PPMZeroExposureMs", "800");
    private static final StringProperty plusExposure =
            PathPrefs.createPersistentPreference("PPMPlusExposureMs", "500");
    private static final StringProperty uncrossedExposure =
            PathPrefs.createPersistentPreference("PPMUncrossedExposureMs", "10");

    static {
        // PPM exposure defaults are initialized with fallback values.
        // Use loadExposuresForProfile() to load profile-specific defaults.
        logger.debug("PPMPreferences initialized with fallback exposure values");
    }

    private PPMPreferences() {}

    public static boolean getMinusSelected() {
        return Boolean.parseBoolean(minusSelected.get());
    }

    public static void setMinusSelected(boolean selected) {
        minusSelected.set(String.valueOf(selected));
    }

    public static boolean getZeroSelected() {
        return Boolean.parseBoolean(zeroSelected.get());
    }

    public static void setZeroSelected(boolean selected) {
        zeroSelected.set(String.valueOf(selected));
    }

    public static boolean getPlusSelected() {
        return Boolean.parseBoolean(plusSelected.get());
    }

    public static void setPlusSelected(boolean selected) {
        plusSelected.set(String.valueOf(selected));
    }

    /**
     * Gets the decimal exposure time for the negative (minus) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 1.2ms)
     */
    public static double getMinusExposureMs() {
        return Double.parseDouble(minusExposure.get());
    }

    /**
     * Sets the decimal exposure time for the negative (minus) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setMinusExposureMs(double ms) {
        minusExposure.set(String.valueOf(ms));
    }

    /**
     * Gets the decimal exposure time for the zero-degree (crossed polarizers) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 800.5ms)
     */
    public static double getZeroExposureMs() {
        return Double.parseDouble(zeroExposure.get());
    }

    /**
     * Sets the decimal exposure time for the zero-degree (crossed polarizers) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setZeroExposureMs(double ms) {
        zeroExposure.set(String.valueOf(ms));
    }

    /**
     * Gets the decimal exposure time for the positive (plus) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 1.8ms)
     */
    public static double getPlusExposureMs() {
        return Double.parseDouble(plusExposure.get());
    }

    /**
     * Sets the decimal exposure time for the positive (plus) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setPlusExposureMs(double ms) {
        plusExposure.set(String.valueOf(ms));
    }

    public static boolean getUncrossedSelected() {
        return Boolean.parseBoolean(uncrossedSelected.get());
    }

    public static void setUncrossedSelected(boolean selected) {
        uncrossedSelected.set(String.valueOf(selected));
    }

    /**
     * Gets the decimal exposure time for the uncrossed (parallel polarizers) PPM angle.
     * @return exposure time in milliseconds (supports decimal values like 0.5ms)
     */
    public static double getUncrossedExposureMs() {
        return Double.parseDouble(uncrossedExposure.get());
    }

    /**
     * Sets the decimal exposure time for the uncrossed (parallel polarizers) PPM angle.
     * @param ms exposure time in milliseconds (decimal values supported)
     */
    public static void setUncrossedExposureMs(double ms) {
        uncrossedExposure.set(String.valueOf(ms));
    }

    /**
     * Loads exposure defaults from the configuration for the specified objective/detector combination.
     * Updates the preference values with the profile-specific exposures if found.
     * 
     * @param objective the objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_10X_001")
     * @param detector the detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if exposures were loaded successfully, false otherwise
     */
    public static boolean loadExposuresForProfile(String objective, String detector) {
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());
            
            List<?> profiles = mgr.getList("acq_profiles_new", "profiles");
            if (profiles == null) {
                logger.warn("No acquisition profiles found in configuration");
                return false;
            }

            // Find matching profile for ppm modality with specified objective/detector
            for (Object profileObj : profiles) {
                if (profileObj instanceof Map<?, ?> profile) {
                    Object modalityObj = profile.get("modality");
                    Object objectiveObj = profile.get("objective");
                    Object detectorObj = profile.get("detector");
                    
                    if ("ppm".equals(modalityObj) && 
                        objective.equals(objectiveObj) && 
                        detector.equals(detectorObj)) {
                        
                        // Found matching profile, extract exposures
                        Object settingsObj = profile.get("settings");
                        if (settingsObj instanceof Map<?, ?> settings) {
                            Object exposuresObj = settings.get("exposures_ms");
                            if (exposuresObj instanceof Map<?, ?> exposures) {
                                
                                // Load exposures for each angle
                                loadExposureForAngle(exposures, "negative", "setMinusExposureMs");
                                loadExposureForAngle(exposures, "crossed", "setZeroExposureMs");
                                loadExposureForAngle(exposures, "positive", "setPlusExposureMs");
                                loadExposureForAngle(exposures, "uncrossed", "setUncrossedExposureMs");
                                
                                logger.info("Loaded PPM exposures for {}/{} from configuration", 
                                           objective, detector);
                                return true;
                            }
                        }
                    }
                }
            }
            
            logger.warn("No PPM profile found for objective {} and detector {}", objective, detector);
            return false;
            
        } catch (Exception e) {
            logger.warn("Failed to load PPM exposures for {}/{}", objective, detector, e);
            return false;
        }
    }
    
    /**
     * Helper method to extract exposure value for a specific angle and set the corresponding preference.
     */
    private static void loadExposureForAngle(Map<?, ?> exposures, String angleName, String setterMethod) {
        Object exposureObj = exposures.get(angleName);
        if (exposureObj instanceof Number) {
            // Simple numeric exposure (for TELEDYNE detector)
            double ms = ((Number) exposureObj).doubleValue();
            setExposureValue(setterMethod, ms);
        } else if (exposureObj instanceof Map<?, ?> exposureMap) {
            // Complex exposure with 'all' fallback (for JAI detector)
            Object allObj = exposureMap.get("all");
            if (allObj instanceof Number) {
                double ms = ((Number) allObj).doubleValue();
                setExposureValue(setterMethod, ms);
            }
        }
    }
    
    /**
     * Helper method to call the appropriate setter method by name.
     */
    private static void setExposureValue(String setterMethod, double ms) {
        switch (setterMethod) {
            case "setMinusExposureMs" -> setMinusExposureMs(ms);
            case "setZeroExposureMs" -> setZeroExposureMs(ms);
            case "setPlusExposureMs" -> setPlusExposureMs(ms);
            case "setUncrossedExposureMs" -> setUncrossedExposureMs(ms);
            default -> logger.warn("Unknown setter method: {}", setterMethod);
        }
    }
}

