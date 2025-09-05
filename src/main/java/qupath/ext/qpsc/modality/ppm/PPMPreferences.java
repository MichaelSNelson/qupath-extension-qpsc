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
 * Stores user preferences for PPM modality such as selected angles and exposure times.
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

    // Exposure times in milliseconds for each angle
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
                            int ms = ((Number) exposure).intValue();
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

    public static int getMinusExposureMs() {
        return Integer.parseInt(minusExposure.get());
    }

    public static void setMinusExposureMs(int ms) {
        minusExposure.set(String.valueOf(ms));
    }

    public static int getZeroExposureMs() {
        return Integer.parseInt(zeroExposure.get());
    }

    public static void setZeroExposureMs(int ms) {
        zeroExposure.set(String.valueOf(ms));
    }

    public static int getPlusExposureMs() {
        return Integer.parseInt(plusExposure.get());
    }

    public static void setPlusExposureMs(int ms) {
        plusExposure.set(String.valueOf(ms));
    }

    public static boolean getUncrossedSelected() {
        return Boolean.parseBoolean(uncrossedSelected.get());
    }

    public static void setUncrossedSelected(boolean selected) {
        uncrossedSelected.set(String.valueOf(selected));
    }

    public static int getUncrossedExposureMs() {
        return Integer.parseInt(uncrossedExposure.get());
    }

    public static void setUncrossedExposureMs(int ms) {
        uncrossedExposure.set(String.valueOf(ms));
    }
}

