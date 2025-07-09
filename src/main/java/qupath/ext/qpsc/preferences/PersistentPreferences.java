package qupath.ext.qpsc.preferences;

import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * PersistentPreferences
 *
 * <p>Helper for storing extension specific settings that should not appear
 * in the main QuPath Preferences UI.
 *   - Wraps JavaFX Properties in a singleton for easy access.
 */
public class PersistentPreferences {

    // ================== SAMPLE/PROJECT SETTINGS ==================
    private static final StringProperty slideLabelSaved =
            PathPrefs.createPersistentPreference("SlideLabel", "First_Test");

    public static String getSlideLabel() {
        return slideLabelSaved.getValue();
    }

    public static void setSlideLabel(final String slideLabel) {
        slideLabelSaved.setValue(slideLabel);
    }

    // ================== BOUNDING BOX WORKFLOW ==================
    private static final StringProperty boundingBoxString =
            PathPrefs.createPersistentPreference("BoundingBox", "27000,7000,20000,10000");

    private static final StringProperty boundingBoxInFocusSaved =
            PathPrefs.createPersistentPreference("BoundingBoxInFocus", "false");

    public static String getBoundingBoxString() {
        return boundingBoxString.getValue();
    }

    public static void setBoundingBoxString(final String boundingBox) {
        boundingBoxString.setValue(boundingBox);
    }

    public static boolean getBoundingBoxInFocus() {
        return Boolean.parseBoolean(boundingBoxInFocusSaved.getValue());
    }

    public static void setBoundingBoxInFocus(final boolean inFocus) {
        boundingBoxInFocusSaved.setValue(String.valueOf(inFocus));
    }

    // ================== GREEN BOX DETECTION PARAMETERS ==================
    private static final StringProperty greenThresholdSaved =
            PathPrefs.createPersistentPreference("GreenBoxThreshold", "0.4");

    private static final StringProperty greenSaturationMinSaved =
            PathPrefs.createPersistentPreference("GreenBoxSaturationMin", "0.3");

    private static final StringProperty greenBrightnessMinSaved =
            PathPrefs.createPersistentPreference("GreenBoxBrightnessMin", "0.3");

    private static final StringProperty greenBrightnessMaxSaved =
            PathPrefs.createPersistentPreference("GreenBoxBrightnessMax", "0.9");

    private static final StringProperty greenEdgeThicknessSaved =
            PathPrefs.createPersistentPreference("GreenBoxEdgeThickness", "3");

    private static final StringProperty greenMinBoxWidthSaved =
            PathPrefs.createPersistentPreference("GreenBoxMinWidth", "100");

    private static final StringProperty greenMinBoxHeightSaved =
            PathPrefs.createPersistentPreference("GreenBoxMinHeight", "100");

    public static double getGreenThreshold() {
        return Double.parseDouble(greenThresholdSaved.getValue());
    }

    public static void setGreenThreshold(final double threshold) {
        greenThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getGreenSaturationMin() {
        return Double.parseDouble(greenSaturationMinSaved.getValue());
    }

    public static void setGreenSaturationMin(final double saturation) {
        greenSaturationMinSaved.setValue(String.valueOf(saturation));
    }

    public static double getGreenBrightnessMin() {
        return Double.parseDouble(greenBrightnessMinSaved.getValue());
    }

    public static void setGreenBrightnessMin(final double brightness) {
        greenBrightnessMinSaved.setValue(String.valueOf(brightness));
    }

    public static double getGreenBrightnessMax() {
        return Double.parseDouble(greenBrightnessMaxSaved.getValue());
    }

    public static void setGreenBrightnessMax(final double brightness) {
        greenBrightnessMaxSaved.setValue(String.valueOf(brightness));
    }

    public static int getGreenEdgeThickness() {
        return Integer.parseInt(greenEdgeThicknessSaved.getValue());
    }

    public static void setGreenEdgeThickness(final int thickness) {
        greenEdgeThicknessSaved.setValue(String.valueOf(thickness));
    }

    public static int getGreenMinBoxWidth() {
        return Integer.parseInt(greenMinBoxWidthSaved.getValue());
    }

    public static void setGreenMinBoxWidth(final int width) {
        greenMinBoxWidthSaved.setValue(String.valueOf(width));
    }

    public static int getGreenMinBoxHeight() {
        return Integer.parseInt(greenMinBoxHeightSaved.getValue());
    }

    public static void setGreenMinBoxHeight(final int height) {
        greenMinBoxHeightSaved.setValue(String.valueOf(height));
    }

    // ================== TISSUE DETECTION PARAMETERS ==================
    private static final StringProperty tissueMethodSaved =
            PathPrefs.createPersistentPreference("TissueDetectionMethod", "COLOR_DECONVOLUTION");

    private static final StringProperty tissueMinRegionSizeSaved =
            PathPrefs.createPersistentPreference("TissueMinRegionSize", "1000");

    private static final StringProperty tissuePercentileSaved =
            PathPrefs.createPersistentPreference("TissuePercentile", "0.5");

    private static final StringProperty tissueFixedThresholdSaved =
            PathPrefs.createPersistentPreference("TissueFixedThreshold", "128");

    private static final StringProperty tissueEosinThresholdSaved =
            PathPrefs.createPersistentPreference("TissueEosinThreshold", "0.15");

    private static final StringProperty tissueHematoxylinThresholdSaved =
            PathPrefs.createPersistentPreference("TissueHematoxylinThreshold", "0.15");

    private static final StringProperty tissueSaturationThresholdSaved =
            PathPrefs.createPersistentPreference("TissueSaturationThreshold", "0.1");

    private static final StringProperty tissueBrightnessMinSaved =
            PathPrefs.createPersistentPreference("TissueBrightnessMin", "0.2");

    private static final StringProperty tissueBrightnessMaxSaved =
            PathPrefs.createPersistentPreference("TissueBrightnessMax", "0.95");

    public static String getTissueDetectionMethod() {
        return tissueMethodSaved.getValue();
    }

    public static void setTissueDetectionMethod(final String method) {
        tissueMethodSaved.setValue(method);
    }

    public static int getTissueMinRegionSize() {
        return Integer.parseInt(tissueMinRegionSizeSaved.getValue());
    }

    public static void setTissueMinRegionSize(final int size) {
        tissueMinRegionSizeSaved.setValue(String.valueOf(size));
    }

    public static double getTissuePercentile() {
        return Double.parseDouble(tissuePercentileSaved.getValue());
    }

    public static void setTissuePercentile(final double percentile) {
        tissuePercentileSaved.setValue(String.valueOf(percentile));
    }

    public static int getTissueFixedThreshold() {
        return Integer.parseInt(tissueFixedThresholdSaved.getValue());
    }

    public static void setTissueFixedThreshold(final int threshold) {
        tissueFixedThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueEosinThreshold() {
        return Double.parseDouble(tissueEosinThresholdSaved.getValue());
    }

    public static void setTissueEosinThreshold(final double threshold) {
        tissueEosinThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueHematoxylinThreshold() {
        return Double.parseDouble(tissueHematoxylinThresholdSaved.getValue());
    }

    public static void setTissueHematoxylinThreshold(final double threshold) {
        tissueHematoxylinThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueSaturationThreshold() {
        return Double.parseDouble(tissueSaturationThresholdSaved.getValue());
    }

    public static void setTissueSaturationThreshold(final double threshold) {
        tissueSaturationThresholdSaved.setValue(String.valueOf(threshold));
    }

    public static double getTissueBrightnessMin() {
        return Double.parseDouble(tissueBrightnessMinSaved.getValue());
    }

    public static void setTissueBrightnessMin(final double brightness) {
        tissueBrightnessMinSaved.setValue(String.valueOf(brightness));
    }

    public static double getTissueBrightnessMax() {
        return Double.parseDouble(tissueBrightnessMaxSaved.getValue());
    }

    public static void setTissueBrightnessMax(final double brightness) {
        tissueBrightnessMaxSaved.setValue(String.valueOf(brightness));
    }

    // ================== EXISTING IMAGE WORKFLOW ==================
    private static final StringProperty macroImagePixelSizeInMicrons =
            PathPrefs.createPersistentPreference("macroImagePixelSizeInMicrons", "7.2");

    public static String getMacroImagePixelSizeInMicrons() {
        return macroImagePixelSizeInMicrons.getValue();
    }

    public static void setMacroImagePixelSizeInMicrons(final String macroPixelSize) {
        macroImagePixelSizeInMicrons.setValue(macroPixelSize);
    }

    // ================== AUTOMATION SETTINGS ==================
    private static final StringProperty classListSaved =
            PathPrefs.createPersistentPreference("classList", "Tumor, Stroma, Immune");

    private static final StringProperty modalityForAutomationSaved =
            PathPrefs.createPersistentPreference("modalityForAutomation", "20x_bf");

    private static final StringProperty analysisScriptForAutomationSaved =
            PathPrefs.createPersistentPreference("analysisScriptForAutomation", "DetectROI.groovy");

    public static String getClassList() {
        return classListSaved.getValue();
    }

    public static void setClassList(final String classList) {
        classListSaved.setValue(classList);
    }

    public static String getModalityForAutomation() {
        return modalityForAutomationSaved.getValue();
    }

    public static void setModalityForAutomation(final String modality) {
        modalityForAutomationSaved.setValue(modality);
    }

    public static String getAnalysisScriptForAutomation() {
        return analysisScriptForAutomationSaved.getValue();
    }

    public static void setAnalysisScriptForAutomation(final String analysisScript) {
        analysisScriptForAutomationSaved.setValue(analysisScript);
    }

    // ================== SAMPLE SETUP DIALOG ==================
    private static final StringProperty lastSampleNameSaved =
            PathPrefs.createPersistentPreference("LastSampleName", "");

    private static final StringProperty lastModalitySaved =
            PathPrefs.createPersistentPreference("LastModality", "");

    public static String getLastSampleName() {
        return lastSampleNameSaved.getValue();
    }

    public static void setLastSampleName(final String sampleName) {
        lastSampleNameSaved.setValue(sampleName);
    }

    public static String getLastModality() {
        return lastModalitySaved.getValue();
    }

    public static void setLastModality(final String modality) {
        lastModalitySaved.setValue(modality);
    }


    // ================== TRANSFORM SETTINGS ==================
    private static final StringProperty saveTransformDefaultSaved =
            PathPrefs.createPersistentPreference("SaveTransformDefault", "true");

    private static final StringProperty lastTransformNameSaved =
            PathPrefs.createPersistentPreference("LastTransformName", "");

    public static boolean getSaveTransformDefault() {
        return Boolean.parseBoolean(saveTransformDefaultSaved.getValue());
    }

    public static void setSaveTransformDefault(final boolean save) {
        saveTransformDefaultSaved.setValue(String.valueOf(save));
    }

    public static String getLastTransformName() {
        return lastTransformNameSaved.getValue();
    }

    public static void setLastTransformName(final String name) {
        lastTransformNameSaved.setValue(name);
    }

    // ================== PPM ANGLE SELECTION ==================
    private static final StringProperty ppmMinusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");

    private static final StringProperty ppmZeroSelectedSaved =
            PathPrefs.createPersistentPreference("PPMZeroSelected", "true");

    private static final StringProperty ppmPlusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMPlusSelected", "true");

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
}