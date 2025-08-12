package qupath.ext.qpsc.modalities.ppm;

import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * PPM-specific persistent preferences.
 * Extracted from PersistentPreferences to isolate PPM functionality.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMPreferences {

    // ================== PPM ANGLE SELECTION ==================
    private static final StringProperty ppmMinusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");

    private static final StringProperty ppmZeroSelectedSaved =
            PathPrefs.createPersistentPreference("PPMZeroSelected", "true");

    private static final StringProperty ppmPlusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMPlusSelected", "true");

    private static final StringProperty ppmBrightfieldSelectedSaved =
            PathPrefs.createPersistentPreference("PPMBrightfieldSelected", "false");

    // ================== PPM EXPOSURE TIMES ==================
    private static final StringProperty ppmMinusExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMMinusExposureMs", "500");

    private static final StringProperty ppmZeroExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMZeroExposureMs", "800");

    private static final StringProperty ppmPlusExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMPlusExposureMs", "500");

    private static final StringProperty ppmBrightfieldExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMBrightfieldExposureMs", "10");

    // ================== ANGLE SELECTION GETTERS/SETTERS ==================

    public static boolean getPPMMinusSelected() {
        return Boolean.parseBoolean(ppmMinusSelectedSaved.getValue());
    }

    public static void setPPMMinusSelected(final boolean selected) {
        ppmMinusSelectedSaved.setValue(String.valueOf(selected));
    }

    public static boolean getPPMZeroSelected() {
        return Boolean.parseBoolean(ppmZeroSelectedSaved.getValue());
    }

    public static void setPPMZeroSelected(final boolean selected) {
        ppmZeroSelectedSaved.setValue(String.valueOf(selected));
    }

    public static boolean getPPMPlusSelected() {
        return Boolean.parseBoolean(ppmPlusSelectedSaved.getValue());
    }

    public static void setPPMPlusSelected(final boolean selected) {
        ppmPlusSelectedSaved.setValue(String.valueOf(selected));
    }

    public static boolean getPPMBrightfieldSelected() {
        return Boolean.parseBoolean(ppmBrightfieldSelectedSaved.getValue());
    }

    public static void setPPMBrightfieldSelected(final boolean selected) {
        ppmBrightfieldSelectedSaved.setValue(String.valueOf(selected));
    }

    // ================== EXPOSURE TIME GETTERS/SETTERS ==================

    public static int getPPMMinusExposureMs() {
        return Integer.parseInt(ppmMinusExposureMsSaved.getValue());
    }

    public static void setPPMMinusExposureMs(final int exposureMs) {
        ppmMinusExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    public static int getPPMZeroExposureMs() {
        return Integer.parseInt(ppmZeroExposureMsSaved.getValue());
    }

    public static void setPPMZeroExposureMs(final int exposureMs) {
        ppmZeroExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    public static int getPPMPlusExposureMs() {
        return Integer.parseInt(ppmPlusExposureMsSaved.getValue());
    }

    public static void setPPMPlusExposureMs(final int exposureMs) {
        ppmPlusExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    public static int getPPMBrightfieldExposureMs() {
        return Integer.parseInt(ppmBrightfieldExposureMsSaved.getValue());
    }

    public static void setPPMBrightfieldExposureMs(final int exposureMs) {
        ppmBrightfieldExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    // ================== UTILITY METHODS ==================

    /**
     * Resets all PPM preferences to defaults.
     */
    public static void resetToDefaults() {
        setPPMMinusSelected(true);
        setPPMZeroSelected(true);
        setPPMPlusSelected(true);
        setPPMBrightfieldSelected(false);

        setPPMMinusExposureMs(500);
        setPPMZeroExposureMs(800);
        setPPMPlusExposureMs(500);
        setPPMBrightfieldExposureMs(10);
    }
}