package qupath.ext.qpsc;

import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

public class PersistentPreferences {

    private static final StringProperty slideLabelSaved = PathPrefs.createPersistentPreference("SlideLabel", "First_Test");

    public static String getSlideLabel() {
        return slideLabelSaved.getValue();
    }
    public static void setSlideLabel(final String slideLabel) {
        slideLabelSaved.setValue(slideLabel);
    }

    private static final StringProperty boundingBoxString = PathPrefs.createPersistentPreference("BoundingBox", "27000,7000,20000,10000");

    public static String getBoundingBoxString() {
        return boundingBoxString.getValue();
    }
    public static void setBoundingBoxString(final String boundingBox) {
        boundingBoxString.setValue(boundingBox);
    }

    private static final StringProperty macroImagePixelSizeInMicrons = PathPrefs.createPersistentPreference("macroImagePixelSizeInMicrons", "7.2");

    public static String getMacroImagePixelSizeInMicrons() {
        return macroImagePixelSizeInMicrons.getValue();
    }
    public static void setMacroImagePixelSizeInMicrons(final String macroPixelSize) {
        macroImagePixelSizeInMicrons.setValue(macroPixelSize);
    }

    private static final StringProperty classListSaved = PathPrefs.createPersistentPreference("classList", "Tumor, Stroma, Immune");

    public static String getClassList() {
        return classListSaved.getValue();
    }
    public static void setClassList(final String classList) {
        classListSaved.setValue(classList);
    }

    private static final StringProperty modalityForAutomationSaved = PathPrefs.createPersistentPreference("modalityForAutomation", "20x_bf");

    public static String getModalityForAutomation() {
        return modalityForAutomationSaved.getValue();
    }
    public static void setModalityForAutomation(final String modality) {
        modalityForAutomationSaved.setValue(modality);
    }

    private static final StringProperty analysisScriptForAutomationSaved = PathPrefs.createPersistentPreference("analysisScriptForAutomation", "DetectROI.groovy");

    public static String getAnalysisScriptForAutomation() {
        return analysisScriptForAutomationSaved.getValue();
    }
    public static void setAnalysisScriptForAutomation(final String analysisScript) {
        analysisScriptForAutomationSaved.setValue(analysisScript);
    }
}

