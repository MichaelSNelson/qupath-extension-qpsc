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

//TODO NEXT TIME - function to check the list of files in smartpath_configurations,
// and create a dropdown with those file names (minus extension)

/**
 * This class adds and manages various persistent preferences for the QP Scope extension.
 * It registers numerous preference items with QuPath’s preference pane using PathPrefs and PropertyItemBuilder.
 */
public class QPPreferenceDialog {

    private static QPPreferenceDialog instance;

    // A list to hold details of each preference (if needed for later lookup).
    // Here we use a List of Maps, where each map might store keys like "name" and "property".
    private final List<Map<String,Object>> preferencesList = new ArrayList<>();

    // A category name to group these preferences in the QuPath GUI.
    private static final String EXTENSION_NAME = "Microscopy in QuPath";

    private QPPreferenceDialog() {
        initializePreferences();
    }

    public static synchronized QPPreferenceDialog getInstance() {
        if (instance == null) {
            instance = new QPPreferenceDialog();
        }
        return instance;
    }

    /**
     * Initializes and registers a comprehensive set of persistent preferences with QuPath's PreferencePane.
     * This method directly adds each property to the PreferenceSheet via QPEx.getQuPath().
     */
    private void initializePreferences() {
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

        preferencesList.add(Map.of("name","Flip macro image X", "property", isFlippedXProperty));

        BooleanProperty isFlippedY =
                PathPrefs.createPersistentPreference("isFlippedYProperty", false);
        items.add(new PropertyItemBuilder<>(isFlippedY, Boolean.class)
                .name("Flip macro image Y")
                .category(EXTENSION_NAME)
                .description("Allows the slide to be flipped vertically for coordinate alignment.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Flip macro image Y",
                "property", isFlippedY
        ));

        // --- Stage inversion settings ---
        // Inverted X stage
        BooleanProperty isInvertedX =
                PathPrefs.createPersistentPreference("isInvertedXProperty", false);
        items.add(new PropertyItemBuilder<>(isInvertedX, Boolean.class)
                .name("Inverted X stage")
                .category(EXTENSION_NAME)
                .description("Stage X axis is inverted relative to QuPath.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Inverted X stage",
                "property", isInvertedX
        ));

        // Inverted Y stage
        BooleanProperty isInvertedY =
                PathPrefs.createPersistentPreference("isInvertedYProperty", true);
        items.add(new PropertyItemBuilder<>(isInvertedY, Boolean.class)
                .name("Inverted Y stage")
                .category(EXTENSION_NAME)
                .description("Stage Y axis is inverted relative to QuPath.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Inverted Y stage",
                "property", isInvertedY
        ));
        // --- Script and environment paths ---
        StringProperty runCommandProperty= PathPrefs.createPersistentPreference("runCommandName",
                "smartpath");
        items.add(new PropertyItemBuilder<>(runCommandProperty, String.class)
                //.propertyType(PropertyItemBuilder.PropertyType.String.class)
                .name("Command line call")
                .category(EXTENSION_NAME)
                .description("The 'run' command for PycroManager control of the microscope, e.g. if smartpath, smartpath getStageCoordinates")
                .build());

//TODO convert directly targeting file to target folder and build combo box from yml file contents
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
                "F:\\QPScopeExtension\\qupath-extension-qpsc");
        items.add(new PropertyItemBuilder<>(extensionPathProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Extension Location")
                .category(EXTENSION_NAME)
                .description("Path to the extension directory in order to find included scripts.")
                .build());

        // --- Tissue detection script ---
        String tissueScript = extensionPathProperty.getValue().toString() + "/src/main/groovyScripts/DetectTissue.groovy";
        StringProperty tissueDetectionScriptProperty = PathPrefs.createPersistentPreference("tissueDetectionScriptProperty", tissueScript);
        items.add(new PropertyItemBuilder<>(tissueDetectionScriptProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Tissue Detection Script")
                .category(EXTENSION_NAME)
                .description("Tissue detection script.")
                .build());

        //Imaging modalities moved to microscope config file

        // --- Tile handling ---
        StringProperty tileHandlingProperty =
                PathPrefs.createPersistentPreference("tileHandlingProperty", "None");
        items.add(new PropertyItemBuilder<>(tileHandlingProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Tile Handling Method")
                .category(EXTENSION_NAME)
                .choices(Arrays.asList("None", "Zip", "Delete"))
                .description("Specifies how tiles are handled during scanning. " +
                        "\n'None' will leave the files in place. " +
                        "\n'Zip' will compress tiles into a single archive. " +
                        "\n'Delete' will remove intermediate tiles.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Tile Handling Method",
                "property", tileHandlingProperty
        ));

        // --- Pixel sizes ---
        // pixel sizes moved to config file

        // --- Camera frame dimensions ---
        // camera pixel dimensions moved to config file

        // --- Tile overlap ---
        DoubleProperty tileOverlapPercentProperty =
                PathPrefs.createPersistentPreference("tileOverlapPercentProperty", 0.0);
        items.add(new PropertyItemBuilder<>(tileOverlapPercentProperty, Double.class)
                .name("Tile Overlap Percent")
                .category(EXTENSION_NAME)
                .description("Percentage of overlap between adjacent tiles.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Tile Overlap Percent",
                "property", tileOverlapPercentProperty
        ));

        // --- Compression type for OME Pyramid Writer ---
        ObjectProperty<OMEPyramidWriter.CompressionType> compressionType =
                PathPrefs.createPersistentPreference(
                        "compressionType",
                        OMEPyramidWriter.CompressionType.DEFAULT,
                        OMEPyramidWriter.CompressionType.class
                );
        items.add(new PropertyItemBuilder<>(compressionType, OMEPyramidWriter.CompressionType.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Compression type")
                .category(EXTENSION_NAME)
                .choices(Arrays.asList(OMEPyramidWriter.CompressionType.values()))
                .description("Type of compression used for final images.")
                .build());
        preferencesList.add(Map.of(
                "name",     "Compression type",
                "property", compressionType
        ));
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
        return preferencesList.stream()
                .filter(m -> name.equals(m.get("name")))
                .map(m -> m.get("property"))
                .findFirst().orElse(null);
    }
    /** Typed getters for ease of use elsewhere in your code: */
    public static BooleanProperty flipXProperty() {
        return (BooleanProperty) getInstance().getProperty("Flip macro image X");
    }

    public static BooleanProperty flipYProperty() {
        return (BooleanProperty) getInstance().getProperty("Flip macro image Y");
    }

    public static BooleanProperty invertedXProperty() {
        return (BooleanProperty) getInstance().getProperty("Inverted X stage");
    }

    public static BooleanProperty invertedYProperty() {
        return (BooleanProperty) getInstance().getProperty("Inverted Y stage");
    }

}



