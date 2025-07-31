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

    private static final StringProperty selectedScannerProperty =
            PathPrefs.createPersistentPreference("selectedScanner", "Generic");
    private static final StringProperty savedTransformNameProperty  =
            PathPrefs.createPersistentPreference("savedMicroscopeTransform", "");
    public static String getSavedTransformName() {
        return savedTransformNameProperty.get();
    }
    public static String getSelectedScannerProperty() {
        return selectedScannerProperty.get();
    }
    public static void setSelectedScannerProperty(String scanner) {
        selectedScannerProperty.set(scanner);
    }

    public static StringProperty selectedScannerProperty() {
        return selectedScannerProperty;
    }
    public static void setSavedTransformName(String name) {
        savedTransformNameProperty.set(name);
    }

    // Add getter for scanner property (already exists but let's ensure it's complete)
    public static String getSelectedScanner() {
        return selectedScannerProperty.get();
    }

    public static void setSelectedScanner(String scanner) {
        selectedScannerProperty.set(scanner);
    }
// ================== BOUNDING BOX WORKFLOW ==================
    private static final StringProperty boundingBoxString =
            PathPrefs.createPersistentPreference("BoundingBox", "27000,7000,20000,10000");

    private static final StringProperty boundingBoxInFocusSaved =
            PathPrefs.createPersistentPreference("BoundingBoxInFocus", "false");

    private static final StringProperty boundingBoxWidthSaved =
            PathPrefs.createPersistentPreference("BoundingBoxWidth", "2000");

    private static final StringProperty boundingBoxHeightSaved =
            PathPrefs.createPersistentPreference("BoundingBoxHeight", "2000");

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

    public static String getBoundingBoxWidth() {
        return boundingBoxWidthSaved.getValue();
    }

    public static void setBoundingBoxWidth(final String width) {
        boundingBoxWidthSaved.setValue(width);
    }

    public static String getBoundingBoxHeight() {
        return boundingBoxHeightSaved.getValue();
    }

    public static void setBoundingBoxHeight(final String height) {
        boundingBoxHeightSaved.setValue(height);
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
            PathPrefs.createPersistentPreference("TissueMinRegionSize", "50000");

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
            PathPrefs.createPersistentPreference("TissueBrightnessMin", "0.6");

    private static final StringProperty tissueBrightnessMaxSaved =
            PathPrefs.createPersistentPreference("TissueBrightnessMax", "0.8");

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

    // ================== PPM TICK SELECTION ==================
    private static final StringProperty ppmMinusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMMinusSelected", "true");

    private static final StringProperty ppmZeroSelectedSaved =
            PathPrefs.createPersistentPreference("PPMZeroSelected", "true");

    private static final StringProperty ppmPlusSelectedSaved =
            PathPrefs.createPersistentPreference("PPMPlusSelected", "true");

    private static final StringProperty ppmBrightfieldSelectedSaved =
            PathPrefs.createPersistentPreference("PPMBrightfieldSelected", "false");
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
    /**
     * Gets whether PPM brightfield is selected
     * @return true if selected
     */
    public static boolean getPPMBrightfieldSelected() {
        return Boolean.parseBoolean(ppmBrightfieldSelectedSaved.getValue());
    }

    /**
     * Sets whether PPM brightfield is selected
     * @param selected true to select
     */
    public static void setPPMBrightfieldSelected(final boolean selected) {
        ppmBrightfieldSelectedSaved.setValue(String.valueOf(selected));
    }
    // ================== PPM EXPOSURE SETTINGS ==================
// Exposure times in milliseconds for each PPM angle
    private static final StringProperty ppmMinusExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMMinusExposureMs", "500");

    private static final StringProperty ppmZeroExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMZeroExposureMs", "800");

    private static final StringProperty ppmPlusExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMPlusExposureMs", "500");

    private static final StringProperty ppmBrightfieldExposureMsSaved =
            PathPrefs.createPersistentPreference("PPMBrightfieldExposureMs", "10");


    /**
     * Gets the exposure time in milliseconds for PPM minus angle
     * @return exposure time in ms
     */
    public static int getPPMMinusExposureMs() {
        return Integer.parseInt(ppmMinusExposureMsSaved.getValue());
    }

    /**
     * Sets the exposure time in milliseconds for PPM minus angle
     * @param exposureMs exposure time in ms
     */
    public static void setPPMMinusExposureMs(final int exposureMs) {
        ppmMinusExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    /**
     * Gets the exposure time in milliseconds for PPM zero angle
     * @return exposure time in ms
     */
    public static int getPPMZeroExposureMs() {
        return Integer.parseInt(ppmZeroExposureMsSaved.getValue());
    }

    /**
     * Sets the exposure time in milliseconds for PPM zero angle
     * @param exposureMs exposure time in ms
     */
    public static void setPPMZeroExposureMs(final int exposureMs) {
        ppmZeroExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    /**
     * Gets the exposure time in milliseconds for PPM plus angle
     * @return exposure time in ms
     */
    public static int getPPMPlusExposureMs() {
        return Integer.parseInt(ppmPlusExposureMsSaved.getValue());
    }

    /**
     * Sets the exposure time in milliseconds for PPM plus angle
     * @param exposureMs exposure time in ms
     */
    public static void setPPMPlusExposureMs(final int exposureMs) {
        ppmPlusExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    /**
     * Gets the exposure time in milliseconds for PPM brightfield
     * @return exposure time in ms
     */
    public static int getPPMBrightfieldExposureMs() {
        return Integer.parseInt(ppmBrightfieldExposureMsSaved.getValue());
    }

    /**
     * Sets the exposure time in milliseconds for PPM brightfield
     * @param exposureMs exposure time in ms
     */
    public static void setPPMBrightfieldExposureMs(final int exposureMs) {
        ppmBrightfieldExposureMsSaved.setValue(String.valueOf(exposureMs));
    }

    // ================== ALIGNMENT SELECTION ==================
    private static final StringProperty useExistingAlignmentSaved =
            PathPrefs.createPersistentPreference("UseExistingAlignment", "false");

    private static final StringProperty lastSelectedTransformSaved =
            PathPrefs.createPersistentPreference("LastSelectedTransform", "");

    private static final StringProperty refineAlignmentSaved =
            PathPrefs.createPersistentPreference("RefineAlignment", "false");

    public static boolean getUseExistingAlignment() {
        return Boolean.parseBoolean(useExistingAlignmentSaved.getValue());
    }

    public static void setUseExistingAlignment(final boolean useExisting) {
        useExistingAlignmentSaved.setValue(String.valueOf(useExisting));
    }

    public static String getLastSelectedTransform() {
        return lastSelectedTransformSaved.getValue();
    }

    public static void setLastSelectedTransform(final String transformName) {
        lastSelectedTransformSaved.setValue(transformName != null ? transformName : "");
    }

    public static boolean getRefineAlignment() {
        return Boolean.parseBoolean(refineAlignmentSaved.getValue());
    }

    public static void setRefineAlignment(final boolean refine) {
        refineAlignmentSaved.setValue(String.valueOf(refine));
    }

    // ================== SOCKET CONNECTION SETTINGS ==================
    private static final StringProperty socketConnectionTimeoutMsSaved =
            PathPrefs.createPersistentPreference("SocketConnectionTimeoutMs", "3000");

    private static final StringProperty socketReadTimeoutMsSaved =
            PathPrefs.createPersistentPreference("SocketReadTimeoutMs", "5000");

    private static final StringProperty socketMaxReconnectAttemptsSaved =
            PathPrefs.createPersistentPreference("SocketMaxReconnectAttempts", "3");

    private static final StringProperty socketReconnectDelayMsSaved =
            PathPrefs.createPersistentPreference("SocketReconnectDelayMs", "5000");

    private static final StringProperty socketHealthCheckIntervalMsSaved =
            PathPrefs.createPersistentPreference("SocketHealthCheckIntervalMs", "30000");

    private static final StringProperty socketAutoFallbackToCLISaved =
            PathPrefs.createPersistentPreference("SocketAutoFallbackToCLI", "true");

    private static final StringProperty socketLastConnectionStatusSaved =
            PathPrefs.createPersistentPreference("SocketLastConnectionStatus", "");

    private static final StringProperty socketLastConnectionTimeSaved =
            PathPrefs.createPersistentPreference("SocketLastConnectionTime", "");

    public static int getSocketConnectionTimeoutMs() {
        return Integer.parseInt(socketConnectionTimeoutMsSaved.getValue());
    }

    public static void setSocketConnectionTimeoutMs(int timeout) {
        socketConnectionTimeoutMsSaved.setValue(String.valueOf(timeout));
    }

    public static int getSocketReadTimeoutMs() {
        return Integer.parseInt(socketReadTimeoutMsSaved.getValue());
    }

    public static void setSocketReadTimeoutMs(int timeout) {
        socketReadTimeoutMsSaved.setValue(String.valueOf(timeout));
    }

    public static int getSocketMaxReconnectAttempts() {
        return Integer.parseInt(socketMaxReconnectAttemptsSaved.getValue());
    }

    public static void setSocketMaxReconnectAttempts(int attempts) {
        socketMaxReconnectAttemptsSaved.setValue(String.valueOf(attempts));
    }

    public static long getSocketReconnectDelayMs() {
        return Long.parseLong(socketReconnectDelayMsSaved.getValue());
    }

    public static void setSocketReconnectDelayMs(long delay) {
        socketReconnectDelayMsSaved.setValue(String.valueOf(delay));
    }

    public static long getSocketHealthCheckIntervalMs() {
        return Long.parseLong(socketHealthCheckIntervalMsSaved.getValue());
    }

    public static void setSocketHealthCheckIntervalMs(long interval) {
        socketHealthCheckIntervalMsSaved.setValue(String.valueOf(interval));
    }

    public static boolean getSocketAutoFallbackToCLI() {
        return Boolean.parseBoolean(socketAutoFallbackToCLISaved.getValue());
    }

    public static void setSocketAutoFallbackToCLI(boolean fallback) {
        socketAutoFallbackToCLISaved.setValue(String.valueOf(fallback));
    }

    public static String getSocketLastConnectionStatus() {
        return socketLastConnectionStatusSaved.getValue();
    }

    public static void setSocketLastConnectionStatus(String status) {
        socketLastConnectionStatusSaved.setValue(status);
    }

    public static String getSocketLastConnectionTime() {
        return socketLastConnectionTimeSaved.getValue();
    }

    public static void setSocketLastConnectionTime(String time) {
        socketLastConnectionTimeSaved.setValue(time);
    }
}