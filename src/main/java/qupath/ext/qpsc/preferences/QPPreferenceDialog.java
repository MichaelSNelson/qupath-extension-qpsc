package qupath.ext.qpsc.preferences;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MacroImageAnalyzer;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;

/**
 * QPPreferenceDialog
 *
 * <p>Registers and exposes the subset of extension preferences you
 * want in QuPath’s Preferences Pane:
 *   - Flip, invert, script paths, directories, compression, overlap, etc.
 *   - Provides typed getters so other code can simply call invertedXProperty(), etc.
 */

public class QPPreferenceDialog {

    private static final Logger logger = LoggerFactory.getLogger(QPPreferenceDialog.class);
    private static final String CATEGORY = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings").getString("name");

    // --- Preference definitions ---

    private static final BooleanProperty flipMacroXProperty =
            PathPrefs.createPersistentPreference("isFlippedXProperty", false);
    private static final BooleanProperty flipMacroYProperty =
            PathPrefs.createPersistentPreference("isFlippedYProperty", false);
    private static final BooleanProperty invertedXProperty =
            PathPrefs.createPersistentPreference("isInvertedXProperty", false);
    private static final BooleanProperty invertedYProperty =
            PathPrefs.createPersistentPreference("isInvertedYProperty", true);
    private static final StringProperty microscopeServerHostProperty =
            PathPrefs.createPersistentPreference("microscope.server.host", "127.0.0.1");

    private static final IntegerProperty microscopeServerPortProperty =
            PathPrefs.createPersistentPreference("microscope.server.port", 5000);

    private static final BooleanProperty useSocketConnectionProperty =
            PathPrefs.createPersistentPreference("microscope.useSocketConnection", true);

    private static final BooleanProperty autoConnectToServerProperty =
            PathPrefs.createPersistentPreference("microscope.autoConnectToServer", true);
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
            PathPrefs.createPersistentPreference("tileOverlapPercentProperty", 10.0);
    private static final ObjectProperty<OMEPyramidWriter.CompressionType> compressionTypeProperty =
            PathPrefs.createPersistentPreference(
                    "compressionType",
                    OMEPyramidWriter.CompressionType.DEFAULT,
                    OMEPyramidWriter.CompressionType.class);

    private static final ObjectProperty<StitchingConfig.OutputFormat> outputFormatProperty =
            PathPrefs.createPersistentPreference(
                    "stitchingOutputFormat",
                    StitchingConfig.OutputFormat.OME_TIFF,
                    StitchingConfig.OutputFormat.class);

    // Filename configuration preferences
    // Note: These control what information appears in the filename
    // ALL information is stored in QuPath metadata regardless of these settings
    private static final BooleanProperty includeObjectiveInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeObjective", false);

    private static final BooleanProperty includeModalityInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeModality", false);

    private static final BooleanProperty includeAnnotationInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeAnnotation", false);

    // Angle defaults to TRUE for multi-angle modalities like PPM
    // where distinguishing between angles is critical
    private static final BooleanProperty includeAngleInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeAngle", true);

    /**
     * Register all preferences in QuPath's PreferencePane. Call once during extension installation.
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
        items.add(new PropertyItemBuilder<>(outputFormatProperty, StitchingConfig.OutputFormat.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .choices(Arrays.asList(StitchingConfig.OutputFormat.values()))
                .name("Stitching output format")
                .category(CATEGORY)
                .description("Output format for stitched images.\n" +
                             "OME-TIFF: Traditional single-file format, widely compatible (standard as of 2025).\n" +
                             "OME-ZARR: Cloud-native directory format with better compression and parallel writing,\n" +
                             "but less commonly used. ZARR provides 2-3x faster writing and 20-30% smaller files.")
                .build());
        items.add(new PropertyItemBuilder<>(useSocketConnectionProperty, Boolean.class)
                .name("Use Socket Connection")
                .category(CATEGORY)
                .description("Use direct socket connection instead of CLI commands (faster but requires server)")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeServerHostProperty, String.class)
                .name("Microscope Server Host")
                .category(CATEGORY)
                .description("IP address or hostname of the microscope control server")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeServerPortProperty, Integer.class)
                .name("Microscope Server Port")
                .category(CATEGORY)
                .description("Port number for the microscope control server (default: 5000)")
                .build());

        items.add(new PropertyItemBuilder<>(autoConnectToServerProperty, Boolean.class)
                .name("Auto-connect to Server")
                .category(CATEGORY)
                .description("Automatically connect to microscope server when QuPath starts")
                .build());

        // Filename configuration section
        items.add(new PropertyItemBuilder<>(includeObjectiveInFilenameProperty, Boolean.class)
                .name("Image name includes: Objective")
                .category(CATEGORY)
                .description("Include objective/magnification (e.g., '20x') in image filenames.\n" +
                             "Default: SampleName_001.ome.tif\n" +
                             "With objective: SampleName_20x_001.ome.tif\n" +
                             "Note: All metadata is always stored in QuPath regardless of filename settings.")
                .build());

        items.add(new PropertyItemBuilder<>(includeModalityInFilenameProperty, Boolean.class)
                .name("Image name includes: Modality")
                .category(CATEGORY)
                .description("Include imaging modality (e.g., 'ppm', 'bf') in image filenames.\n" +
                             "With modality: SampleName_ppm_001.ome.tif\n" +
                             "Note: All metadata is always stored in QuPath regardless of filename settings.")
                .build());

        items.add(new PropertyItemBuilder<>(includeAnnotationInFilenameProperty, Boolean.class)
                .name("Image name includes: Annotation")
                .category(CATEGORY)
                .description("Include annotation name in image filenames when acquiring specific regions.\n" +
                             "With annotation: SampleName_Tissue_001.ome.tif\n" +
                             "Note: All metadata is always stored in QuPath regardless of filename settings.")
                .build());

        items.add(new PropertyItemBuilder<>(includeAngleInFilenameProperty, Boolean.class)
                .name("Image name includes: Angle")
                .category(CATEGORY)
                .description("Include angle information in image filenames for multi-angle acquisitions.\n" +
                             "Critical for PPM and other polarized imaging modalities.\n" +
                             "With angle: SampleName_001_7.0.ome.zarr, SampleName_001_-7.0.ome.zarr\n" +
                             "Note: All metadata is always stored in QuPath regardless of filename settings.")
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

    public static String getMicroscopeServerHost() {
        return microscopeServerHostProperty.get();
    }

    public static void setMicroscopeServerHost(String host) {
        microscopeServerHostProperty.set(host);
    }

    public static int getMicroscopeServerPort() {
        return microscopeServerPortProperty.get();
    }

    public static void setMicroscopeServerPort(int port) {
        microscopeServerPortProperty.set(port);
    }

    public static boolean getUseSocketConnection() {
        return useSocketConnectionProperty.get();
    }

    public static void setUseSocketConnection(boolean useSocket) {
        useSocketConnectionProperty.set(useSocket);
    }

    public static boolean getAutoConnectToServer() {
        return autoConnectToServerProperty.get();
    }

    public static void setAutoConnectToServer(boolean autoConnect) {
        autoConnectToServerProperty.set(autoConnect);
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
    public static StitchingConfig.OutputFormat getOutputFormatProperty() {
        return outputFormatProperty.get();
    }
    //TODO should this be here?

    private static ObservableList<String> getScannerChoices() {
        List<String> choices = new ArrayList<>();

        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                    microscopeConfigFileProperty.get()
            );
            List<String> availableScanners = mgr.getAvailableScanners();

            if (!availableScanners.isEmpty()) {
                choices.addAll(availableScanners);
            } else {
                logger.warn("No scanners found in configuration, using Generic only");
                choices.add("Generic");
            }
        } catch (Exception e) {
            logger.error("Error loading scanner choices", e);
            choices.add("Generic");
        }

        return FXCollections.observableArrayList(choices);
    }



    private static List<String> getAvailableTransforms() {
        List<String> transforms = new ArrayList<>();
        transforms.add(""); // Empty option for no transform

        try {
            String configPath = getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                AffineTransformManager manager = new AffineTransformManager(
                        new File(configPath).getParent());
                manager.getAllTransforms().forEach(t -> transforms.add(t.getName()));
            }
        } catch (Exception e) {
            logger.debug("Could not load transforms: {}", e.getMessage());
        }

        return transforms;
    }

    // Filename configuration getters
    public static boolean getFilenameIncludeObjective() {
        return includeObjectiveInFilenameProperty.get();
    }

    public static void setFilenameIncludeObjective(boolean include) {
        includeObjectiveInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeModality() {
        return includeModalityInFilenameProperty.get();
    }

    public static void setFilenameIncludeModality(boolean include) {
        includeModalityInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeAnnotation() {
        return includeAnnotationInFilenameProperty.get();
    }

    public static void setFilenameIncludeAnnotation(boolean include) {
        includeAnnotationInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeAngle() {
        return includeAngleInFilenameProperty.get();
    }

    public static void setFilenameIncludeAngle(boolean include) {
        includeAngleInFilenameProperty.set(include);
    }
}
