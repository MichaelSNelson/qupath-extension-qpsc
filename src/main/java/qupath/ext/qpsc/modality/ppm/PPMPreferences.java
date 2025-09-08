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
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());
            List<?> angles = mgr.getList("exposures", "ppm_angles");
            if (angles != null) {
                for (Object obj : angles) {
                    if (obj instanceof Map<?, ?> angle) {
                        Object name = angle.get("name");
                        Object exposure = angle.get("exposure_ms");
                        if (name != null && exposure instanceof Number) {
                            double ms = ((Number) exposure).doubleValue();
                            switch (name.toString()) {
                                case "positive" -> plusExposure.set(String.valueOf(ms));
                                case "negative" -> minusExposure.set(String.valueOf(ms));
                                case "crossed" -> zeroExposure.set(String.valueOf(ms));
                                case "uncrossed" -> uncrossedExposure.set(String.valueOf(ms));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load default PPM exposures from configuration", e);
        }
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
}

