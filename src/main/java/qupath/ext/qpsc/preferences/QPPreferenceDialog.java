package qupath.ext.qpsc.preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.util.Arrays;

/**
 * QPPreferenceDialog
 *
 * <p>Registers and exposes the subset of extension preferences you
 * want in QuPath’s Preferences Pane:
 *   - Flip, invert, script paths, directories, compression, overlap, etc.
 *   - Provides typed getters so other code can simply call invertedXProperty(), etc.
 */

public class QPPreferenceDialog {
    private static final String CATEGORY = "Microscopy in QuPath";

    // --- Preference definitions ---
    private static final BooleanProperty flipMacroXProperty =
            PathPrefs.createPersistentPreference("isFlippedXProperty", false);
    private static final BooleanProperty flipMacroYProperty =
            PathPrefs.createPersistentPreference("isFlippedYProperty", false);
    private static final BooleanProperty invertedXProperty =
            PathPrefs.createPersistentPreference("isInvertedXProperty", false);
    private static final BooleanProperty invertedYProperty =
            PathPrefs.createPersistentPreference("isInvertedYProperty", true);

    private static final StringProperty microscopeConfigFileProperty =
            PathPrefs.createPersistentPreference(
                    "microscopeConfigFileProperty",
                    "F:/QPScopeExtension/smartpath_configurations/microscopes/config_PPM.yml");

    private static final StringProperty projectsFolderProperty =
            PathPrefs.createPersistentPreference(
                    "projectsFolderProperty",
                    "F:/QPScopeExtension/data/slides");
    private static final StringProperty extensionLocationProperty =
            PathPrefs.createPersistentPreference(
                    "extensionPathProperty",
                    "F:\\QPScopeExtension\\qupath-extension-qpsc");

    private static final StringProperty tissueDetectionScriptProperty =
            PathPrefs.createPersistentPreference(
                    "tissueDetectionScriptProperty",
                    extensionLocationProperty.getValue() + "/src/main/groovyScripts/DetectTissue.groovy");

    private static final StringProperty tileHandlingMethodProperty =
            PathPrefs.createPersistentPreference("tileHandlingProperty", "None");
    private static final DoubleProperty tileOverlapPercentProperty =
            PathPrefs.createPersistentPreference("tileOverlapPercentProperty", 0.0);
    private static final ObjectProperty<OMEPyramidWriter.CompressionType> compressionTypeProperty =
            PathPrefs.createPersistentPreference(
                    "compressionType",
                    OMEPyramidWriter.CompressionType.DEFAULT,
                    OMEPyramidWriter.CompressionType.class);
    private static final StringProperty cliFolderProperty =
            PathPrefs.createPersistentPreference(
                    "smartpath.cliFolder",
                    "C:\\Users\\lociuser\\Codes\\smartpath\\smart-wsi-scanner\\.venv\\Scripts"
            );
    /**
     * Register all preferences in QuPath’s PreferencePane. Call once during extension installation.
     */
    public static void installPreferences(QuPathGUI qupath) {
        if (qupath == null)
            return;
        ObservableList<org.controlsfx.control.PropertySheet.Item> items =
                qupath.getPreferencePane()
                        .getPropertySheet()
                        .getItems();

        items.add(new PropertyItemBuilder<>(flipMacroXProperty, Boolean.class)
                .name("Flip macro image X")
                .category(CATEGORY)
                .description("Allows the slide to be flipped horizontally for coordinate alignment.")
                .build());
        items.add(new PropertyItemBuilder<>(flipMacroYProperty, Boolean.class)
                .name("Flip macro image Y")
                .category(CATEGORY)
                .description("Allows the slide to be flipped vertically for coordinate alignment.")
                .build());
        items.add(new PropertyItemBuilder<>(invertedXProperty, Boolean.class)
                .name("Inverted X stage")
                .category(CATEGORY)
                .description("Stage X axis is inverted relative to QuPath.")
                .build());
        items.add(new PropertyItemBuilder<>(invertedYProperty, Boolean.class)
                .name("Inverted Y stage")
                .category(CATEGORY)
                .description("Stage Y axis is inverted relative to QuPath.")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeConfigFileProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Microscope Config File")
                .category(CATEGORY)
                .description("Path to YAML config describing your microscope setup.")
                .build());
        items.add(new PropertyItemBuilder<>(cliFolderProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("SmartPath commands folder")
                .category(CATEGORY)
                .description("Path to the folder where QuPath can find the SmartPath commands for controlling the microscope.")
                .build());

        items.add(new PropertyItemBuilder<>(projectsFolderProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Projects Folder")
                .category(CATEGORY)
                .description("Root folder where slide projects and data are stored.")
                .build());
        items.add(new PropertyItemBuilder<>(extensionLocationProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Extension Location")
                .category(CATEGORY)
                .description("Directory of the extension, used to locate built‑in scripts.")
                .build());

        items.add(new PropertyItemBuilder<>(tissueDetectionScriptProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Tissue Detection Script")
                .category(CATEGORY)
                .description("Groovy script for tissue detection before imaging.")
                .build());

        items.add(new PropertyItemBuilder<>(tileHandlingMethodProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Tile Handling Method")
                .choices(Arrays.asList("None", "Zip", "Delete"))
                .category(CATEGORY)
                .description("How to handle intermediate tiles: none, zip them, or delete them.")
                .build());
        items.add(new PropertyItemBuilder<>(tileOverlapPercentProperty, Double.class)
                .name("Tile Overlap Percent")
                .category(CATEGORY)
                .description("Overlap percentage between adjacent tiles in acquisition.")
                .build());
        items.add(new PropertyItemBuilder<>(compressionTypeProperty, OMEPyramidWriter.CompressionType.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .choices(Arrays.asList(OMEPyramidWriter.CompressionType.values()))
                .name("Compression type")
                .category(CATEGORY)
                .description("Compression for OME Pyramid output.")
                .build());
    }


    // --- Typed getters for use throughout your code ---
    public static boolean getFlipMacroXProperty() {
        return flipMacroXProperty.get();
    }
    public static boolean getFlipMacroYProperty() {
        return flipMacroYProperty.get();
    }
    public static boolean getInvertedXProperty() {
        return invertedXProperty.get();
    }
    public static boolean getInvertedYProperty() {
        return invertedYProperty.get();
    }

    public static String getMicroscopeConfigFileProperty() {
        return microscopeConfigFileProperty.get();
    }
    public static String getProjectsFolderProperty() {
        return projectsFolderProperty.get();
    }
    public static String getExtensionLocationProperty() {
        return extensionLocationProperty.get();
    }
    public static String getTissueDetectionScriptProperty() {
        return tissueDetectionScriptProperty.get();
    }
    public static String getTileHandlingMethodProperty() {
        return tileHandlingMethodProperty.get();
    }
    public static Double getTileOverlapPercentProperty() {
        return tileOverlapPercentProperty.get();
    }
    public static OMEPyramidWriter.CompressionType getCompressionTypeProperty() {
        return compressionTypeProperty.get();
    }
    public static String getCliFolder() {
        return cliFolderProperty.getValue();
    }
}
