package qupath.ext.qpsc.modality.ppm;

import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Stores user preferences for PPM modality such as selected angles and exposure times.
 */
public class PPMPreferences {

    // Angle selection flags
    private static final StringProperty minusSelected =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");
    private static final StringProperty zeroSelected =
            PathPrefs.createPersistentPreference("PPMZeroSelected", "true");
    private static final StringProperty plusSelected =
            PathPrefs.createPersistentPreference("PPMPlusSelected", "true");

    // Exposure times in milliseconds for each angle
    private static final StringProperty minusExposure =
            PathPrefs.createPersistentPreference("PPMMinusExposureMs", "500");
    private static final StringProperty zeroExposure =
            PathPrefs.createPersistentPreference("PPMZeroExposureMs", "800");
    private static final StringProperty plusExposure =
            PathPrefs.createPersistentPreference("PPMPlusExposureMs", "500");

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
}

