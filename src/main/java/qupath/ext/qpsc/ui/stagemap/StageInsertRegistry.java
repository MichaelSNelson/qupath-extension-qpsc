package qupath.ext.qpsc.ui.stagemap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for available stage insert configurations.
 * <p>
 * Loads insert configurations from the microscope YAML configuration file
 * and provides lookup by ID. Configurations include:
 * <ul>
 *   <li>Single slide horizontal (standard)</li>
 *   <li>Single slide vertical</li>
 *   <li>Four slides vertical</li>
 *   <li>Custom configurations</li>
 * </ul>
 * <p>
 * If no configuration is found in YAML, provides sensible defaults based on
 * standard slide dimensions (25mm x 75mm) and typical insert sizes.
 */
public class StageInsertRegistry {

    private static final Logger logger = LoggerFactory.getLogger(StageInsertRegistry.class);

    /** Cached insert configurations keyed by ID */
    private static final Map<String, StageInsert> inserts = new ConcurrentHashMap<>();

    /** ID of the default insert configuration */
    private static String defaultInsertId = "single_h";

    /** Flag indicating if configurations have been loaded */
    private static boolean initialized = false;

    // Default safety margin around slides
    private static final double DEFAULT_SLIDE_MARGIN_UM = 2000.0;

    // Standard slide dimensions (1" x 3")
    private static final double STANDARD_SLIDE_WIDTH_MM = 75.0;
    private static final double STANDARD_SLIDE_HEIGHT_MM = 25.0;

    private StageInsertRegistry() {
        // Static utility class
    }

    /**
     * Loads insert configurations from the MicroscopeConfigManager.
     * <p>
     * Each configuration uses 4 calibration reference points that are easy to measure:
     * <ul>
     *   <li>aperture_left_x_um: Stage X when left aperture edge is centered in FOV</li>
     *   <li>aperture_right_x_um: Stage X when right aperture edge is centered in FOV</li>
     *   <li>slide_top_y_um: Stage Y when top edge of slide is centered in FOV</li>
     *   <li>slide_bottom_y_um: Stage Y when bottom edge of slide is centered in FOV</li>
     * </ul>
     * <p>
     * Expected YAML structure under 'stage.inserts':
     * <pre>
     * stage:
     *   inserts:
     *     slide_margin_um: 2000
     *     default: single_h
     *     configurations:
     *       single_h:
     *         name: "Single Slide (Horizontal)"
     *         aperture_left_x_um: 5000
     *         aperture_right_x_um: 60000
     *         slide_top_y_um: -2000
     *         slide_bottom_y_um: 23000
     *         aperture_height_mm: 60.0
     *         slide_width_mm: 75
     *         slide_height_mm: 25
     * </pre>
     *
     * @param configManager The MicroscopeConfigManager instance
     */
    @SuppressWarnings("unchecked")
    public static synchronized void loadFromConfig(MicroscopeConfigManager configManager) {
        inserts.clear();

        if (configManager == null) {
            logger.warn("MicroscopeConfigManager is null, using default insert configurations");
            loadDefaultConfigurations();
            initialized = true;
            return;
        }

        try {
            // Get insert configuration section
            Map<String, Object> insertsConfig = configManager.getSection("stage", "inserts");

            if (insertsConfig == null || insertsConfig.isEmpty()) {
                logger.info("No stage.inserts configuration found, using defaults");
                loadDefaultConfigurations();
                initialized = true;
                return;
            }

            // Read shared parameters
            double slideMarginUm = getDoubleValue(insertsConfig, "slide_margin_um", DEFAULT_SLIDE_MARGIN_UM);
            defaultInsertId = (String) insertsConfig.getOrDefault("default", "single_h");

            // Load individual configurations (each has its own aperture size and origin)
            Object configsObj = insertsConfig.get("configurations");
            if (configsObj instanceof Map) {
                Map<String, Object> configs = (Map<String, Object>) configsObj;
                for (Map.Entry<String, Object> entry : configs.entrySet()) {
                    String insertId = entry.getKey();
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> insertConfig = (Map<String, Object>) entry.getValue();
                        StageInsert insert = StageInsert.fromConfigMap(
                                insertId, insertConfig, slideMarginUm);
                        inserts.put(insertId, insert);
                        logger.debug("Loaded insert configuration: {} ({}) - aperture: {}x{} mm, origin: ({}, {}) um",
                                insertId, insert.getName(),
                                insert.getWidthUm() / 1000.0, insert.getHeightUm() / 1000.0,
                                insert.getOriginXUm(), insert.getOriginYUm());
                    }
                }
            }

            // If no configurations were loaded, use defaults
            if (inserts.isEmpty()) {
                logger.info("No insert configurations found in YAML, using defaults");
                loadDefaultConfigurations();
            }

            logger.info("Loaded {} insert configurations, default: {}", inserts.size(), defaultInsertId);

        } catch (Exception e) {
            logger.error("Error loading insert configurations: {}", e.getMessage(), e);
            loadDefaultConfigurations();
        }

        initialized = true;
    }

    /**
     * Loads default insert configurations when YAML config is unavailable.
     * Uses aperture-based model with reasonable defaults for a single slide holder.
     */
    private static void loadDefaultConfigurations() {
        // Default aperture dimensions (viewable area)
        double apertureWidthMm = 55.0;   // 5.5cm horizontal
        double apertureHeightMm = 60.0;  // 6cm vertical

        // Single slide horizontal
        // Slide (75mm) extends beyond aperture (55mm) by 10mm on each side
        List<StageInsert.SlidePosition> singleHSlides = new ArrayList<>();
        singleHSlides.add(new StageInsert.SlidePosition(
                "Slide 1",
                -10.0,  // Starts 10mm left of aperture
                (apertureHeightMm - STANDARD_SLIDE_HEIGHT_MM) / 2.0,  // Centered vertically
                STANDARD_SLIDE_WIDTH_MM, STANDARD_SLIDE_HEIGHT_MM, 0));

        inserts.put("single_h", new StageInsert(
                "single_h", "Single Slide (Horizontal)",
                apertureWidthMm, apertureHeightMm,
                0, 0, DEFAULT_SLIDE_MARGIN_UM, singleHSlides));

        // Single slide vertical
        List<StageInsert.SlidePosition> singleVSlides = new ArrayList<>();
        singleVSlides.add(new StageInsert.SlidePosition(
                "Slide 1",
                (apertureHeightMm - STANDARD_SLIDE_HEIGHT_MM) / 2.0,  // Centered horizontally
                -10.0,  // Starts 10mm above aperture
                STANDARD_SLIDE_HEIGHT_MM, STANDARD_SLIDE_WIDTH_MM, 90));

        inserts.put("single_v", new StageInsert(
                "single_v", "Single Slide (Vertical)",
                apertureHeightMm, apertureWidthMm,  // Swapped for vertical
                0, 0, DEFAULT_SLIDE_MARGIN_UM, singleVSlides));

        // Four slides vertical (larger aperture)
        double quadApertureWidth = 120.0;
        List<StageInsert.SlidePosition> quadVSlides = new ArrayList<>();
        double spacing = 5.0;
        for (int i = 0; i < 4; i++) {
            double xOffset = spacing + i * (STANDARD_SLIDE_HEIGHT_MM + spacing);
            quadVSlides.add(new StageInsert.SlidePosition(
                    "Slide " + (i + 1), xOffset, -10.0,
                    STANDARD_SLIDE_HEIGHT_MM, STANDARD_SLIDE_WIDTH_MM, 90));
        }

        inserts.put("quad_v", new StageInsert(
                "quad_v", "Four Slides (Vertical)",
                quadApertureWidth, apertureWidthMm,
                0, 0, DEFAULT_SLIDE_MARGIN_UM, quadVSlides));

        defaultInsertId = "single_h";
        logger.info("Loaded default insert configurations");
    }

    private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Ensures configurations are loaded, loading defaults if necessary.
     */
    private static void ensureInitialized() {
        if (!initialized) {
            logger.info("StageInsertRegistry not initialized, loading defaults");
            loadDefaultConfigurations();
            initialized = true;
        }
    }

    /**
     * Returns all available insert configurations.
     *
     * @return List of all StageInsert configurations
     */
    public static List<StageInsert> getAvailableInserts() {
        ensureInitialized();
        return new ArrayList<>(inserts.values());
    }

    /**
     * Returns the insert configuration with the specified ID.
     *
     * @param id The insert ID (e.g., "single_h", "quad_v")
     * @return The StageInsert, or null if not found
     */
    public static StageInsert getInsert(String id) {
        ensureInitialized();
        return inserts.get(id);
    }

    /**
     * Returns the default insert configuration.
     *
     * @return The default StageInsert
     */
    public static StageInsert getDefaultInsert() {
        ensureInitialized();
        StageInsert defaultInsert = inserts.get(defaultInsertId);
        if (defaultInsert == null && !inserts.isEmpty()) {
            // Fall back to first available
            defaultInsert = inserts.values().iterator().next();
        }
        return defaultInsert;
    }

    /**
     * Returns the ID of the default insert configuration.
     *
     * @return The default insert ID
     */
    public static String getDefaultInsertId() {
        ensureInitialized();
        return defaultInsertId;
    }

    /**
     * Checks if the registry has been initialized.
     *
     * @return true if configurations have been loaded
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Clears all cached configurations. Primarily for testing.
     */
    public static synchronized void reset() {
        inserts.clear();
        defaultInsertId = "single_h";
        initialized = false;
    }
}
