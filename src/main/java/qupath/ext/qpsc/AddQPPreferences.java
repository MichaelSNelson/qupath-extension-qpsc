package qupath.ext.qpsc;


import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This class adds and manages various persistent preferences for the QP Scope extension.
 * It registers numerous preference items with QuPath’s preference pane using PathPrefs and PropertyItemBuilder.
 */
public class AddQPPreferences {

    private static AddQPPreferences instance;

    // A list to hold details of each preference (if needed for later lookup).
    // Here we use a List of Maps, where each map might store keys like "name" and "property".
    private List<Map<String, Object>> preferencesList = new ArrayList<>();

    // A category name to group these preferences in the QuPath GUI.
    private static final String EXTENSION_NAME = "Microscopy in QuPath";

    private AddQPPreferences() {
        initializePreferences();
    }

    public static synchronized AddQPPreferences getInstance() {
        if (instance == null) {
            instance = new AddQPPreferences();
        }
        return instance;
    }

    /**
     * Initializes and registers a comprehensive set of persistent preferences with QuPath's PreferencePane.
     * This method directly adds each property to the PreferenceSheet via QPEx.getQuPath().
     */
    private static void initializePreferences() {
        QuPathGUI qupath = QPEx.getQuPath();
        if (qupath == null) {
            // QuPath GUI not available; preferences cannot be registered.
            return;
        }
        // Get the items list from the PreferencePane.
        ObservableList items = qupath.getPreferencePane().getPropertySheet().getItems();

        // --- Flip settings ---
        BooleanProperty isFlippedXProperty = PathPrefs.createPersistentPreference("isFlippedXProperty", false);
        items.add(new PropertyItemBuilder<>(isFlippedXProperty, Boolean.class)
                .name("Flip macro image X")
                .category(EXTENSION_NAME)
                .description("Allows the slide to be flipped horizontally so that the coordinates can be matched correctly with the stage.")
                .build());

        BooleanProperty isFlippedYProperty = PathPrefs.createPersistentPreference("isFlippedYProperty", false);
        items.add(new PropertyItemBuilder<>(isFlippedYProperty, Boolean.class)
                .name("Flip macro image Y")
                .category(EXTENSION_NAME)
                .description("Allows the slide to be flipped vertically so that the coordinates can be matched correctly with the stage.")
                .build());

        // --- Stage inversion settings ---
        BooleanProperty isInvertedXProperty = PathPrefs.createPersistentPreference("isInvertedXProperty", false);
        items.add(new PropertyItemBuilder<>(isInvertedXProperty, Boolean.class)
                .name("Inverted X stage")
                .category(EXTENSION_NAME)
                .description("Stage axis is inverted in X relative to QuPath.")
                .build());

        BooleanProperty isInvertedYProperty = PathPrefs.createPersistentPreference("isInvertedYProperty", true);
        items.add(new PropertyItemBuilder<>(isInvertedYProperty, Boolean.class)
                .name("Inverted Y stage")
                .category(EXTENSION_NAME)
                .description("Stage axis is inverted in Y relative to QuPath.")
                .build());

        // --- Script and environment paths ---
        StringProperty pycromanagerProperty = PathPrefs.createPersistentPreference("pycromanagerProperty",
                "C:\\Users\\Michael Nelson\\OneDrive - UW-Madison\\GitHub_clones\\smart-wsi-scanner\\minimal_qupathrunner.py");
        items.add(new PropertyItemBuilder<>(pycromanagerProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("PycroManager Path")
                .category(EXTENSION_NAME)
                .description("Path to the PycroManager script used for controlling microscopes.")
                .build());

        StringProperty pythonEnvironmentProperty = PathPrefs.createPersistentPreference("pythonEnvironmentProperty",
                "C:\\Anaconda\\envs\\ls_control");
        items.add(new PropertyItemBuilder<>(pythonEnvironmentProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Python Environment")
                .category(EXTENSION_NAME)
                .description("Path to the Python environment.")
                .build());

        StringProperty microscopeConfigFileProperty = PathPrefs.createPersistentPreference("microscopeConfigFileProperty",
                "C:\\ImageAnalysis\\QPExtension0.5.0\\config\\config_CAMM.yml");
        items.add(new PropertyItemBuilder<>(microscopeConfigFileProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Microscope Config File")
                .category(EXTENSION_NAME)
                .description("Microscope Config File.")
                .build());

        // --- Folder paths ---
        StringProperty projectsFolderProperty = PathPrefs.createPersistentPreference("projectsFolderProperty",
                "C:\\ImageAnalysis\\QPExtension0.5.0\\data\\slides");
        items.add(new PropertyItemBuilder<>(projectsFolderProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Projects Folder")
                .category(EXTENSION_NAME)
                .description("Path to the projects folder.")
                .build());

        StringProperty extensionPathProperty = PathPrefs.createPersistentPreference("extensionPathProperty",
                "C:\\ImageAnalysis\\QPExtension0.5.0\\qp-scope");
        items.add(new PropertyItemBuilder<>(extensionPathProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Extension Location")
                .category(EXTENSION_NAME)
                .description("Path to the extension directory in order to find included scripts.")
                .build());

        // --- Tissue detection script ---
        String pyScript = extensionPathProperty.getValue().toString() + "/src/main/groovyScripts/DetectTissue.groovy";
        StringProperty tissueDetectionScriptProperty = PathPrefs.createPersistentPreference("tissueDetectionScriptProperty", pyScript);
        items.add(new PropertyItemBuilder<>(tissueDetectionScriptProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Tissue Detection Script")
                .category(EXTENSION_NAME)
                .description("Tissue detection script.")
                .build());

        // --- Imaging mode settings ---
        StringProperty firstImagingModeProperty = PathPrefs.createPersistentPreference("firstImagingModeProperty", "4x_bf");
        items.add(new PropertyItemBuilder<>(firstImagingModeProperty, String.class)
                .name("First Scan Type")
                .category(EXTENSION_NAME)
                .description("Type of the first scan (e.g., magnification and method).")
                .build());

        StringProperty secondImagingModeProperty = PathPrefs.createPersistentPreference("secondImagingModeProperty", "20x_bf");
        items.add(new PropertyItemBuilder<>(secondImagingModeProperty, String.class)
                .name("Second Scan Type")
                .category(EXTENSION_NAME)
                .description("Type of the second scan (e.g., magnification and method).")
                .build());

        // --- Tile handling ---
        StringProperty tileHandlingProperty = PathPrefs.createPersistentPreference("tileHandlingProperty", "None");
        items.add(new PropertyItemBuilder<>(tileHandlingProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Tile Handling Method")
                .category(EXTENSION_NAME)
                .choices(Arrays.asList("None", "Zip", "Delete"))
                .description("Specifies how tiles are handled during scanning. " +
                        "\n'None' will leave the files in the folder where they were written." +
                        "\n'Zip' will compress the tiles and their associated TileConfiguration file into a file and place it in a separate folder." +
                        "\n'Delete' will delete the tiles and keep NO COPIES. Only use this if you are confident in your system and need the space.")
                .build());

        // --- Pixel sizes ---
        DoubleProperty pixelSizeFirstImagingModeProperty = PathPrefs.createPersistentPreference("pixelSizeFirstImagingModeProperty", 1.105);
        items.add(new PropertyItemBuilder<>(pixelSizeFirstImagingModeProperty, Double.class)
                .name("1st scan pixel size um")
                .category(EXTENSION_NAME)
                .description("Pixel size for the first scan type, in micrometers.")
                .build());

        DoubleProperty pixelSizeSecondImagingModeProperty = PathPrefs.createPersistentPreference("pixelSizeSecondImagingModeProperty", 0.5);
        items.add(new PropertyItemBuilder<>(pixelSizeSecondImagingModeProperty, Double.class)
                .name("2nd scan pixel size um")
                .category(EXTENSION_NAME)
                .description("Pixel size for the second scan type, in micrometers.")
                .build());

        // --- Camera frame dimensions ---
        IntegerProperty cameraFrameWidthPxProperty = PathPrefs.createPersistentPreference("cameraFrameWidthPxProperty", 1392);
        items.add(new PropertyItemBuilder<>(cameraFrameWidthPxProperty, Integer.class)
                .name("Camera Frame Width #px")
                .category(EXTENSION_NAME)
                .description("Width of the camera frame in pixels.")
                .build());

        IntegerProperty cameraFrameHeightPxProperty = PathPrefs.createPersistentPreference("cameraFrameHeightPxProperty", 1040);
        items.add(new PropertyItemBuilder<>(cameraFrameHeightPxProperty, Integer.class)
                .name("Camera Frame Height #px")
                .category(EXTENSION_NAME)
                .description("Height of the camera frame in pixels.")
                .build());

        // --- Tile overlap ---
        DoubleProperty tileOverlapPercentProperty = PathPrefs.createPersistentPreference("tileOverlapPercentProperty", 0.0);
        items.add(new PropertyItemBuilder<>(tileOverlapPercentProperty, Double.class)
                .name("Tile Overlap Percent")
                .category(EXTENSION_NAME)
                .description("Percentage of overlap between adjacent tiles.")
                .build());

        // --- Compression type for OME Pyramid Writer ---
        ObjectProperty<OMEPyramidWriter.CompressionType> compressionType = PathPrefs.createPersistentPreference(
                "compressionType",
                OMEPyramidWriter.CompressionType.DEFAULT,
                OMEPyramidWriter.CompressionType.class);
        items.add(new PropertyItemBuilder<>(compressionType, OMEPyramidWriter.CompressionType.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Compression type")
                .category(EXTENSION_NAME)
                .choices(Arrays.asList(OMEPyramidWriter.CompressionType.values()))
                .description("Type of compression used for final images.")
                .build());
    }

    /**
     * Returns a persistent preference property by its name.
     * (This implementation assumes preferencesList stores maps containing keys "name" and "property".
     * You may wish to maintain a Map<String, Object> instead.)
     *
     * @param name The name of the preference.
     * @return The associated property, or null if not found.
     */
    public Object getProperty(String name) {
        for (Map<String, Object> pref : preferencesList) {
            if (name.equals(pref.get("name"))) {
                return pref.get("property");
            }
        }
        return null;
    }
}



