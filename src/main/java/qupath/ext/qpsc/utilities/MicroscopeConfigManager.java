package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MicroscopeConfigManager
 *
 * <p>Singleton for loading and querying your microscope YAML configuration:
 *   - Parses nested YAML into a Map<String,Object>.
 *   - Offers type safe getters (getDouble, getSection, getList, etc.).
 *   - Validates required keys and reports missing paths.
 *   - Supports new acquisition profile format with defaults and specific profiles
 */
public class MicroscopeConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);

    // Singleton instance
    private static MicroscopeConfigManager instance;

    // Primary config data loaded from the chosen microscope YAML
    private final Map<String, Object> configData;

    // Shared LOCI resource data loaded from resources_LOCI.yml
    private final Map<String, Object> resourceData;
    private final Map<String, String> lociSectionMap;
    private final String configPath;

    /**
     * Private constructor: loads both the microscope-specific YAML and the shared LOCI resources.
     *
     * @param configPath Filesystem path to the microscope YAML configuration file.
     */
    private MicroscopeConfigManager(String configPath) {
        this.configPath = configPath;
        this.configData = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        this.resourceData = loadConfig(resPath);

        // Dynamically build field-to-section map from the top-level of resources_LOCI.yml
        this.lociSectionMap = new HashMap<>();
        for (String section : resourceData.keySet()) {
            if (section.startsWith("ID_") || section.startsWith("id_")) {
                String field = section.substring(3) // remove "id_"
                        .replaceAll("_", "")           // e.g. "OBJECTIVE_LENS" → "OBJECTIVELENS"
                        .toLowerCase();                // "OBJECTIVELENS" → "objectivelens"
                lociSectionMap.put(field, section);
            }
        }
        if (lociSectionMap.isEmpty())
            logger.warn("No LOCI sections found in shared resources!");
    }

    /**
     * Initializes and returns the singleton instance. Must be called first with the path to the microscope YAML.
     *
     * @param configPath Path to the microscope YAML file.
     * @return Shared MicroscopeConfigManager instance.
     */
    public static synchronized MicroscopeConfigManager getInstance(String configPath) {
        if (instance == null) {
            instance = new MicroscopeConfigManager(configPath);
        }
        return instance;
    }

    /**
     * Retrieves an unmodifiable view of the entire configuration map currently loaded
     * from the microscope-specific YAML file.
     *
     * <p>This method provides a convenient way to inspect the entire loaded configuration,
     * which can be helpful for debugging or validation purposes.</p>
     *
     * @return An unmodifiable Map containing the full configuration data.
     */
    public Map<String, Object> getAllConfig() {
        return Collections.unmodifiableMap(configData);
    }

    /**
     * Reloads both the microscope YAML and shared LOCI resources.
     *
     * @param configPath Path to the microscope YAML file.
     */
    public synchronized void reload(String configPath) {
        configData.clear();
        configData.putAll(loadConfig(configPath));
        String resPath = computeResourcePath(configPath);
        resourceData.clear();
        resourceData.putAll(loadConfig(resPath));
    }

    /**
     * Retrieves a section from the resources file directly.
     * This bypasses the normal config lookup and goes straight to resources.
     *
     * @param sectionName The top-level section name in resources (e.g., "id_detector")
     * @return Map containing the section data, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getResourceSection(String sectionName) {
        Object section = resourceData.get(sectionName);
        return (section instanceof Map<?, ?>) ? (Map<String, Object>) section : null;
    }

    /**
     * Computes the path to the shared LOCI resources file based on the microscope config path.
     * For example, transforms ".../microscopes/config_PPM.yml" → ".../resources_LOCI.yml".
     *
     * @param configPath Path to the microscope YAML file.
     * @return Path to resources_LOCI.yml.
     */
    private static String computeResourcePath(String configPath) {
        Path cfg = Paths.get(configPath);
        // Get the parent folder of the config file
        Path baseDir = cfg.getParent();
        // Append "resources/resources_LOCI.yml"
        Path resourcePath = baseDir.resolve("resources").resolve("resources_LOCI.yml").toAbsolutePath();

        File resourceFile = resourcePath.toFile();
        if (!resourceFile.exists()) {
            Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);
            logger.warn("Could not find shared LOCI resource file at: {}", resourcePath);
            // Optionally, throw an error if this file is required:
            // throw new FileNotFoundException("Shared LOCI resource file not found: " + resourcePath);
        }
        return resourcePath.toString();
    }

    /**
     * Loads a YAML file into a Map.
     *
     * @param path Filesystem path to the YAML file.
     * @return Map of YAML data, or empty map on error.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig(String path) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) loaded);
            } else {
                logger.error("YAML root is not a map: {}", path);
            }
        } catch (FileNotFoundException e) {
            logger.error("YAML file not found: {}", path, e);
        } catch (Exception e) {
            logger.error("Error parsing YAML: {}", path, e);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Retrieve a deeply nested value from the microscope configuration,
     * following references to resources_LOCI.yml dynamically if needed.
     * <p>
     * If a String value matching "LOCI-..." is encountered during traversal,
     * this method will search all top-level sections of resources_LOCI.yml to find
     * the corresponding entry and continue the lookup there.
     *
     * @param keys Sequence of keys (e.g., "modalities", "bf_10x", "objective", "id").
     * @return The value at the end of the key path, or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(String... keys) {
        ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
        Object current = configData;

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            // Standard descent into the Map
            if (current instanceof Map<?, ?> map && map.containsKey(key)) {
                current = map.get(key);

                // If this is a LOCI reference and more keys remain, switch to resourceData and continue
                if (current instanceof String id && id.startsWith("LOCI") && i+1 < keys.length) {
                    logger.info(res.getString("configManager.switchingToResource"),
                            id, Arrays.toString(keys), i, key);
                    String section = findResourceSectionForID(key, resourceData, res);
                    if (section == null) {
                        logger.warn(res.getString("configManager.resourceSectionNotFound"),
                                key, id, Arrays.toString(keys));
                        return null;
                    }
                    String normalized = id.replace('-', '_');
                    Object sectionObj = resourceData.get(section);
                    if (sectionObj instanceof Map<?, ?> secMap && secMap.containsKey(normalized)) {
                        current = ((Map<?, ?>) secMap).get(normalized);
                        logger.info(res.getString("configManager.foundResourceEntry"),
                                section, normalized, current);
                        continue; // proceed with remaining keys
                    } else {
                        logger.warn(res.getString("configManager.resourceEntryNotFound"),
                                normalized, section, Arrays.toString(keys));
                        return null;
                    }
                }
                continue;
            }
            // If at this point current is a Map, attempt descent
            if (current instanceof Map<?,?> map2 && map2.containsKey(key)) {
                current = map2.get(key);
                continue;
            }
            // Not found – log full context
            logger.warn(res.getString("configManager.keyNotFound"),
                    key, i, current, Arrays.toString(keys));
            return null;
        }
        logger.debug(res.getString("configManager.lookupSuccess"),
                Arrays.toString(keys), current);
        return current;
    }

    /**
     * Helper to guess the correct resource section for a given parent field (e.g., "detector").
     * Dynamically searches all top-level keys in resources_LOCI.yml.
     *
     * @param parentField   The key referring to a hardware part ("detector", "objectiveLens", etc.)
     * @param resourceData  The parsed LOCI resource map
     * @param res           The strings ResourceBundle
     * @return Section name in resourceData (e.g., "id_detector"), or null if not found
     */
    private static String findResourceSectionForID(String parentField, Map<String, Object> resourceData, ResourceBundle res) {
        for (String section : resourceData.keySet()) {
            if (section.toLowerCase().contains(parentField.toLowerCase())) {
                return section;
            }
        }
        logger.warn(res.getString("configManager.sectionGuessFallback"), parentField, resourceData.keySet());
        // Fallback: just use first section, but warn!
        return resourceData.keySet().stream().findFirst().orElse(null);
    }

    /**
     * Retrieves a String value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return String value or null.
     */
    public String getString(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof String) ? (String) v : null;
    }

    /**
     * Retrieves an Integer value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Integer value or null.
     */
    public Integer getInteger(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number n) return n.intValue();
        try {
            return (v != null) ? Integer.parseInt(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected int at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Retrieves a Double value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Double value or null.
     */
    public Double getDouble(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return (v != null) ? Double.parseDouble(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected double at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Retrieves a Boolean value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Boolean value or null.
     */
    public Boolean getBoolean(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    /**
     * Retrieves a List value from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return List<Object> or null.
     */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof List<?>) ? (List<Object>) v : null;
    }

    /**
     * Retrieves a nested Map section from the config or resources.
     *
     * @param keys Sequence of keys.
     * @return Map<String,Object> or null.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof Map<?, ?>) ? (Map<String, Object>) v : null;
    }

    /**
     * Gets a modality-specific configuration section.
     * @param key Top-level modality key (e.g., "PPM")
     * @return Map containing settings, or empty map if not found
     */
    public Map<String, Object> getModalityConfig(String key) {
        Object section = configData.get(key);
        if (section instanceof Map) {
            return (Map<String, Object>) section;
        }
        return new HashMap<>();
    }

    // ========== NEW ACQUISITION PROFILE METHODS ==========

    /**
     * Get a specific acquisition profile by modality, objective, and detector.
     *
     * @param modality The modality name (e.g., "ppm", "brightfield")
     * @param objective The objective ID (e.g., "LOCI_OBJECTIVE_OLYMPUS_10X_001")
     * @param detector The detector ID (e.g., "LOCI_DETECTOR_JAI_001")
     * @return Map containing the profile, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAcquisitionProfile(String modality, String objective, String detector) {
        List<Object> profiles = getList("acq_profiles_new", "profiles");
        if (profiles == null) {
            logger.warn("No acquisition profiles found in configuration");
            return null;
        }

        for (Object profile : profiles) {
            if (profile instanceof Map<?, ?>) {
                Map<String, Object> p = (Map<String, Object>) profile;
                if (modality.equals(p.get("modality")) &&
                        objective.equals(p.get("objective")) &&
                        detector.equals(p.get("detector"))) {
                    logger.debug("Found profile for {}/{}/{}", modality, objective, detector);
                    return p;
                }
            }
        }

        logger.warn("No profile found for modality: {}, objective: {}, detector: {}",
                modality, objective, detector);
        return null;
    }

    /**
     * Get a setting from acquisition profile with defaults fallback.
     * First checks specific profile, then falls back to defaults.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @param settingPath Path to the setting within the profile
     * @return The setting value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object getProfileSetting(String modality, String objective, String detector, String... settingPath) {
        // First check specific profile
        Map<String, Object> profile = getAcquisitionProfile(modality, objective, detector);
        if (profile != null && profile.containsKey("settings")) {
            Object value = getNestedValue((Map<String, Object>) profile.get("settings"), settingPath);
            if (value != null) {
                logger.debug("Found setting in specific profile: {}", Arrays.toString(settingPath));
                return value;
            }
        }

        // Fall back to defaults
        List<Object> defaults = getList("acq_profiles_new", "defaults");
        if (defaults != null) {
            for (Object def : defaults) {
                if (def instanceof Map<?, ?>) {
                    Map<String, Object> d = (Map<String, Object>) def;
                    if (objective.equals(d.get("objective")) && d.containsKey("settings")) {
                        Object value = getNestedValue((Map<String, Object>) d.get("settings"), settingPath);
                        if (value != null) {
                            logger.debug("Found setting in defaults: {}", Arrays.toString(settingPath));
                            return value;
                        }
                    }
                }
            }
        }

        logger.warn("Setting not found: {} for {}/{}/{}",
                Arrays.toString(settingPath), modality, objective, detector);
        return null;
    }

    /**
     * Helper method to get nested value from a map using a path.
     *
     * @param map The map to search in
     * @param path The path to the value
     * @return The value at the path, or null if not found
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String... path) {
        Object current = map;
        for (String key : path) {
            if (current instanceof Map<?, ?>) {
                current = ((Map<?, ?>) current).get(key);
                if (current == null) return null;
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Get exposure settings for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of exposure settings, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModalityExposures(String modality, String objective, String detector) {
        Object exposures = getProfileSetting(modality, objective, detector, "exposures_ms");
        return (exposures instanceof Map<?, ?>) ? (Map<String, Object>) exposures : null;
    }

    /**
     * Get gain settings for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of gain settings, single gain value, or null if not found
     */
    public Object getModalityGains(String modality, String objective, String detector) {
        return getProfileSetting(modality, objective, detector, "gains");
    }

    /**
     * Get pixel size for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Pixel size in microns
     * @throws IllegalArgumentException if pixel size cannot be determined
     */
    public double getModalityPixelSize(String modality, String objective, String detector) {
        Object pixelSize = getProfileSetting(modality, objective, detector, "pixel_size_xy_um", detector);

        if (pixelSize instanceof Number) {
            double size = ((Number) pixelSize).doubleValue();
            if (size > 0) {
                logger.debug("Pixel size for {}/{}/{}: {} µm", modality, objective, detector, size);
                return size;
            }
        }

        logger.error("No valid pixel size found for {}/{}/{}", modality, objective, detector);
        throw new IllegalArgumentException(
                String.format("Cannot determine pixel size for modality '%s', objective '%s', detector '%s'. " +
                        "Please check acquisition profile configuration.", modality, objective, detector));
    }

    /**
     * Get pixel size for a modality by searching through acquisition profiles.
     * This is a convenience method for when you only have the modality name.
     *
     * @param modalityName The modality name (e.g., "bf_10x", "ppm_20x", "bf_10x_1")
     * @return Pixel size in microns
     * @throws IllegalArgumentException if no matching profile found or pixel size cannot be determined
     */
    public double getPixelSizeForModality(String modalityName) {
        logger.debug("Finding pixel size for modality: {}", modalityName);

        List<Object> profiles = getList("acq_profiles_new", "profiles");
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("No acquisition profiles defined in configuration");
        }

        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modalityName;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        // Search for exact match first
        for (Object profileObj : profiles) {
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) profileObj;

            String profileModality = (String) profile.get("modality");
            String objective = (String) profile.get("objective");
            String detector = (String) profile.get("detector");

            if (profileModality != null &&
                    (profileModality.equals(baseModality) ||
                            profileModality.equals(modalityName) ||
                            modalityName.startsWith(profileModality))) {

                if (objective != null && detector != null) {
                    try {
                        return getModalityPixelSize(profileModality, objective, detector);
                    } catch (IllegalArgumentException e) {
                        // Continue searching if this profile doesn't have pixel size
                        logger.debug("Profile found but no pixel size: {}", e.getMessage());
                    }
                }
            }
        }

        throw new IllegalArgumentException(
                String.format("Cannot determine pixel size for modality '%s'. " +
                        "No matching acquisition profile found.", modalityName));
    }

    /**
     * Get autofocus parameters for a specific objective.
     * Uses the new profile system with defaults.
     *
     * @param objective The objective ID
     * @return Map of autofocus parameters, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAutofocusParams(String objective) {
        logger.debug("Getting autofocus parameters for objective: {}", objective);

        // Check defaults section for this objective
        List<Object> defaults = getList("acq_profiles_new", "defaults");
        if (defaults != null) {
            for (Object def : defaults) {
                if (def instanceof Map<?, ?>) {
                    Map<String, Object> d = (Map<String, Object>) def;
                    if (objective.equals(d.get("objective")) && d.containsKey("settings")) {
                        Map<String, Object> settings = (Map<String, Object>) d.get("settings");
                        if (settings.containsKey("autofocus")) {
                            logger.debug("Found autofocus params for {}", objective);
                            return (Map<String, Object>) settings.get("autofocus");
                        }
                    }
                }
            }
        }

        logger.error("No autofocus parameters found for objective: {}", objective);
        return null;
    }

    /**
     * Get a specific autofocus parameter as integer.
     *
     * @param objective The objective ID
     * @param parameter The parameter name
     * @return The parameter value as Integer, or null if not found
     */
    public Integer getAutofocusIntParam(String objective, String parameter) {
        Map<String, Object> params = getAutofocusParams(objective);
        if (params == null) {
            return null;
        }

        Object value = params.get(parameter);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        logger.error("Autofocus parameter {} not found for objective {}", parameter, objective);
        return null;
    }

    // ========== UPDATED METHODS FOR NEW NAMING ==========

    /**
     * Get default detector for the microscope.
     *
     * @return Default detector ID, or empty string if not found
     */
    public String getDefaultDetector() {
        String detector = getString("microscope", "default_detector");
        if (detector == null) {
            logger.error("No default detector configured");
            return "";
        }
        return detector;
    }

    /**
     * Get detector dimensions (width or height) from resources.
     *
     * @param detector Detector ID
     * @param dimension "width_px" or "height_px"
     * @return Dimension in pixels, or -1 if not found
     */
    @SuppressWarnings("unchecked")
    public int getDetectorDimension(String detector, String dimension) {
        Map<String, Object> detectorSection = getResourceSection("id_detector");
        if (detectorSection != null && detectorSection.containsKey(detector)) {
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detector);
            if (detectorData != null && detectorData.get(dimension) instanceof Number) {
                return ((Number) detectorData.get(dimension)).intValue();
            }
        }
        logger.warn("Detector {} {} not found", detector, dimension);
        return -1;
    }

    /**
     * Get detector dimensions from resources.
     *
     * @param detector Detector ID
     * @return Array of [width, height] in pixels, or null if not found
     */
    @SuppressWarnings("unchecked")
    public int[] getDetectorDimensions(String detector) {
        logger.debug("Getting dimensions for detector: {}", detector);

        Map<String, Object> detectorSection = getResourceSection("id_detector");
        if (detectorSection != null && detectorSection.containsKey(detector)) {
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detector);

            if (detectorData != null) {
                Integer width = null;
                Integer height = null;

                if (detectorData.get("width_px") instanceof Number) {
                    width = ((Number) detectorData.get("width_px")).intValue();
                }
                if (detectorData.get("height_px") instanceof Number) {
                    height = ((Number) detectorData.get("height_px")).intValue();
                }

                if (width != null && height != null && width > 0 && height > 0) {
                    logger.debug("Detector {} dimensions: {}x{}", detector, width, height);
                    return new int[]{width, height};
                }
            }
        }

        logger.error("Detector dimensions not found for: {}", detector);
        return null;
    }

    /**
     * Calculate field of view for a modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Array of [width, height] in microns, or null if cannot calculate
     */
    public double[] getModalityFOV(String modality, String objective, String detector) {
        logger.debug("Calculating FOV for modality: {}, objective: {}, detector: {}",
                modality, objective, detector);

        if (detector == null) {
            detector = getDefaultDetector();
            if (detector.isEmpty()) {
                logger.error("No detector specified and no default detector configured");
                return null;
            }
        }

        double pixelSize = getModalityPixelSize(modality, objective, detector);
        if (pixelSize <= 0) {
            logger.error("Invalid pixel size for {}/{}/{}", modality, objective, detector);
            return null;
        }

        int[] dimensions = getDetectorDimensions(detector);
        if (dimensions == null) {
            logger.error("Cannot calculate FOV - detector dimensions not found");
            return null;
        }

        double width = dimensions[0] * pixelSize;
        double height = dimensions[1] * pixelSize;
        logger.info("FOV for {}/{}/{}: {:.1f} x {:.1f} µm", modality, objective, detector, width, height);
        return new double[]{width, height};
    }

    // ========== COMPATIBILITY METHODS ==========

    /**
     * Get rotation angles configuration for PPM modalities.
     * Returns empty list if none found.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRotationAngles(String modality) {
        logger.debug("Getting rotation angles for modality: {}", modality);

        // Check modality-specific angles
        List<Object> angles = getList("modalities", modality, "rotation_angles");

        if (angles != null && !angles.isEmpty()) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object angle : angles) {
                if (angle instanceof Map) {
                    result.add((Map<String, Object>) angle);
                }
            }
            logger.debug("Found {} rotation angles for {}", result.size(), modality);
            return result;
        }

        logger.debug("No rotation angles found for {}", modality);
        return new ArrayList<>();
    }

    /**
     * Check if a modality uses PPM (polarized light) rotation.
     */
    public boolean isPPMModality(String modality) {
        if (modality != null && modality.toLowerCase().startsWith("ppm")) {
            return true;
        }
        List<Map<String, Object>> angles = getRotationAngles(modality);
        return !angles.isEmpty();
    }

    // ========== EXISTING METHODS THAT REMAIN UNCHANGED ==========

    /**
     * Validates that each of the provided key paths exists in the config or resources.
     *
     * @param requiredPaths Set of String[] representing nested key paths.
     * @return Set of missing paths (empty if all are present).
     */
    public Set<String[]> validateRequiredKeys(Set<String[]> requiredPaths) {
        Set<String[]> missing = new LinkedHashSet<>();
        for (String[] path : requiredPaths) {
            if (getConfigItem(path) == null) missing.add(path);
        }
        if (!missing.isEmpty()) {
            logger.error("Missing required configuration keys: {}",
                    missing.stream()
                            .map(p -> String.join("/", p))
                            .collect(Collectors.toList())
            );
        }
        return missing;
    }

    /**
     * Container for slide boundary information
     */
    public static class SlideBounds {
        public final int xMin, xMax, yMin, yMax;

        public SlideBounds(int xMin, int xMax, int yMin, int yMax) {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }

        public int getWidth() {
            return xMax - xMin;
        }

        public int getHeight() {
            return yMax - yMin;
        }

        @Override
        public String toString() {
            return String.format("SlideBounds[x:%d-%d, y:%d-%d]", xMin, xMax, yMin, yMax);
        }
    }

    /**
     * Writes the provided metadata map out as pretty-printed JSON for debugging or record-keeping.
     *
     * @param metadata   Map of properties to serialize.
     * @param outputPath Target JSON file path.
     * @throws IOException On write error.
     */
    public void writeMetadataAsJson(Map<String, Object> metadata, Path outputPath) throws IOException {
        try (Writer w = new FileWriter(outputPath.toFile())) {
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata, w);
        }
    }

    /**
     * Get list of available scanners from configuration
     */
    public List<String> getAvailableScanners() {
        List<String> scanners = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> scannersMap = (Map<String, Object>) configData.get("scanners");
            if (scannersMap != null) {
                scanners.addAll(scannersMap.keySet());
            }
        } catch (Exception e) {
            logger.warn("No scanners section found in configuration");
        }
        return scanners;
    }

    /**
     * Check if Z coordinate is within stage bounds.
     *
     * @param z Z coordinate in microns
     * @return true if Z is within bounds
     */
    public boolean isWithinStageBounds(double z) {
        Double zLow = getDouble("stage", "limits", "z_um", "low");
        Double zHigh = getDouble("stage", "limits", "z_um", "high");

        if (zLow == null || zHigh == null) {
            logger.warn("Stage Z limits not configured properly");
            return true; // Allow movement if not configured
        }

        boolean valid = z >= zLow && z <= zHigh;
        if (!valid) {
            logger.warn("Z position {} outside stage bounds [{}, {}]", z, zLow, zHigh);
        }
        return valid;
    }

    /**
     * Check if XY coordinates are within stage bounds.
     *
     * @param x X coordinate in microns
     * @param y Y coordinate in microns
     * @return true if both X and Y are within bounds
     */
    public boolean isWithinStageBounds(double x, double y) {
        Double xLow = getDouble("stage", "limits", "x_um", "low");
        Double xHigh = getDouble("stage", "limits", "x_um", "high");
        Double yLow = getDouble("stage", "limits", "y_um", "low");
        Double yHigh = getDouble("stage", "limits", "y_um", "high");

        if (xLow == null || xHigh == null || yLow == null || yHigh == null) {
            logger.warn("Stage XY limits not configured properly");
            return true; // Allow movement if not configured
        }

        boolean valid = x >= xLow && x <= xHigh && y >= yLow && y <= yHigh;
        if (!valid) {
            logger.warn("Position ({}, {}) outside stage bounds: X[{}, {}], Y[{}, {}]",
                    x, y, xLow, xHigh, yLow, yHigh);
        }
        return valid;
    }

    /**
     * Check if XYZ coordinates are within stage bounds.
     *
     * @param x X coordinate in microns
     * @param y Y coordinate in microns
     * @param z Z coordinate in microns
     * @return true if all coordinates are within bounds
     */
    public boolean isWithinStageBounds(double x, double y, double z) {
        // Check XY bounds
        boolean xyValid = isWithinStageBounds(x, y);

        // Check Z bounds
        boolean zValid = isWithinStageBounds(z);

        return xyValid && zValid;
    }

    /**
     * Get specific stage limit for more complex checks.
     *
     * @param axis Stage axis ('x', 'y', or 'z')
     * @param limitType Limit type ('low' or 'high')
     * @return Stage limit value in microns
     */
    public double getStageLimit(String axis, String limitType) {
        Double limit = getDouble("stage", "limits", axis + "_um", limitType);

        if (limit == null) {
            logger.error("Stage {} {} limit not found in configuration", axis, limitType);
            // Return safe defaults
            if ("low".equals(limitType)) {
                return axis.equals("z") ? -1000.0 : -20000.0;
            } else {
                return axis.equals("z") ? 1000.0 : 20000.0;
            }
        }

        return limit;
    }

    /**
     * Check if a scanner is configured
     */
    public boolean isScannerConfigured(String scannerName) {
        return getAvailableScanners().contains(scannerName);
    }

    /**
     * Get microscope name.
     *
     * @return Microscope name, or "Unknown" if not configured
     */
    public String getMicroscopeName() {
        String name = getString("microscope", "name");
        return name != null ? name : "Unknown";
    }

    /**
     * Get microscope type.
     *
     * @return Microscope type, or "Unknown" if not configured
     */
    public String getMicroscopeType() {
        String type = getString("microscope", "type");
        return type != null ? type : "Unknown";
    }

    /**
     * Get stage component IDs.
     *
     * @return Map with keys "xy", "z", "r" containing stage component IDs
     */
    public Map<String, String> getStageComponents() {
        Map<String, String> components = new HashMap<>();

        String stageId = getString("stage", "stage_id");
        components.put("xy", stageId);
        components.put("z", stageId); // Same stage handles Z for Prior

        // Get rotation stage from modality if available
        String rotationStage = getString("modalities", "ppm", "rotation_stage", "device");
        components.put("r", rotationStage);

        return components;
    }

    /**
     * Get slide dimensions.
     *
     * @param dimension "x" or "y"
     * @return Slide size in microns
     */
    public int getSlideDimension(String dimension) {
        Integer size = getInteger("slide_size_um", dimension);
        if (size == null) {
            logger.warn("Slide {} dimension not configured, using default", dimension);
            return dimension.equals("x") ? 40000 : 20000;
        }
        return size;
    }

    /**
     * Get background correction folder for a specific modality.
     * Returns null if not found.
     */
    public String getBackgroundCorrectionFolder(String modality) {
        logger.debug("Getting background correction folder for modality: {}", modality);

        // Check modality-specific setting
        Map<String, Object> bgCorrection = getSection("modalities", modality, "background_correction");
        if (bgCorrection != null && bgCorrection.containsKey("base_folder")) {
            return bgCorrection.get("base_folder").toString();
        }

        logger.warn("No background correction folder found for {}", modality);
        return null;
    }

    /**
     * Check if background correction is enabled for a modality.
     */
    public boolean isBackgroundCorrectionEnabled(String modality) {
        Boolean enabled = getBoolean("modalities", modality, "background_correction", "enabled");
        return enabled != null && enabled;
    }

    /**
     * Get background correction method for a modality.
     * Returns null if not configured.
     */
    public String getBackgroundCorrectionMethod(String modality) {
        return getString("modalities", modality, "background_correction", "method");
    }

    /**
     * Get all available modality names.
     */
    public Set<String> getAvailableModalities() {
        Map<String, Object> modalities = getSection("modalities");
        if (modalities != null) {
            logger.debug("Found {} modalities: {}", modalities.size(), modalities.keySet());
            return modalities.keySet();
        }

        logger.warn("No modalities section found in configuration");
        return new HashSet<>();
    }

    /**
     * Check if a modality exists and is valid.
     */
    public boolean isValidModality(String modality) {
        if (modality == null || modality.isEmpty()) {
            return false;
        }

        Map<String, Object> modalityConfig = getSection("modalities", modality);
        return modalityConfig != null && modalityConfig.containsKey("type");
    }

    /**
     * Get slide dimensions.
     * Returns null array if not configured.
     */
    public int[] getSlideSize() {
        Integer width = getInteger("slide_size_um", "x");
        Integer height = getInteger("slide_size_um", "y");

        if (width == null || height == null) {
            logger.error("Slide size not configured");
            return null;
        }

        return new int[]{width, height};
    }

    /**
     * Load scanner-specific configuration.
     * This loads a separate YAML file for scanner configurations.
     */
    public Map<String, Object> loadScannerConfig(String scannerName) {
        logger.debug("Loading scanner config for: {}", scannerName);

        if (this.configPath == null) {
            logger.error("Cannot determine scanner config path - configPath not set");
            return new HashMap<>();
        }

        java.io.File configDir = new java.io.File(this.configPath).getParentFile();
        java.io.File scannerFile = new java.io.File(configDir, "config_" + scannerName + ".yml");

        if (!scannerFile.exists()) {
            logger.error("Scanner config not found: {}", scannerFile.getAbsolutePath());
            return new HashMap<>();
        }

        return MinorFunctions.loadYamlFile(scannerFile.getAbsolutePath());
    }

    /**
     * Get macro pixel size for a scanner.
     * Returns -1 if not found.
     */
    public double getScannerMacroPixelSize(String scannerName) {
        Map<String, Object> scannerConfig = loadScannerConfig(scannerName);
        Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

        if (pixelSize == null || pixelSize <= 0) {
            logger.error("No valid macro pixel size for scanner: {}", scannerName);
            return -1;
        }

        return pixelSize;
    }

    /**
     * Get scanner crop bounds if cropping is required.
     * Returns null if no cropping needed or bounds incomplete.
     */
    public Map<String, Integer> getScannerCropBounds(String scannerName) {
        Map<String, Object> scannerConfig = loadScannerConfig(scannerName);

        Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requires_cropping");
        if (requiresCropping == null || !requiresCropping) {
            return null;
        }

        Map<String, Integer> bounds = new HashMap<>();
        bounds.put("x_min", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_min_px"));
        bounds.put("x_max", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_max_px"));
        bounds.put("y_min", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_min_px"));
        bounds.put("y_max", MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_max_px"));

        if (bounds.values().stream().anyMatch(Objects::isNull)) {
            logger.error("Incomplete crop bounds for scanner: {} - some values are null", scannerName);
            return null;
        }

        return bounds;
    }

    /**
     * Get all stage limits as a convenient structure.
     */
    public Map<String, Double> getAllStageLimits() {
        Map<String, Double> limits = new HashMap<>();

        for (String axis : Arrays.asList("x", "y", "z")) {
            for (String type : Arrays.asList("low", "high")) {
                String key = axis + "_" + type;
                Double limit = getDouble("stage", "limits", axis + "_um", type);
                if (limit == null) {
                    logger.error("Stage limit {} not found", key);
                    return null;
                }
                limits.put(key, limit);
            }
        }

        logger.debug("Retrieved all stage limits: {}", limits);
        return limits;
    }

    /**
     * Validate that all required configuration sections exist.
     * Returns list of missing sections.
     */
    public List<String> validateConfiguration() {
        List<String> missing = new ArrayList<>();

        // Check required top-level sections
        String[] required = {"microscope", "stage", "modalities", "acq_profiles_new"};
        for (String section : required) {
            if (getSection(section) == null) {
                missing.add(section);
            }
        }

        // Check stage limits
        if (getSection("stage", "limits") == null) {
            missing.add("stage.limits");
        }

        // Check at least one modality
        Set<String> modalities = getAvailableModalities();
        if (modalities.isEmpty()) {
            missing.add("modalities (at least one required)");
        }

        // Check acquisition profiles
        List<Object> profiles = getList("acq_profiles_new", "profiles");
        if (profiles == null || profiles.isEmpty()) {
            missing.add("acq_profiles_new.profiles (at least one required)");
        }


        if (!missing.isEmpty()) {
            logger.error("Configuration validation failed. Missing: {}", missing);
        } else {
            logger.info("Configuration validation passed");
        }

        return missing;
    }

    /**
     * Determines if a detector requires debayering based on configuration or detector properties.
     *
     * @param detectorId The detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @return true if debayering is required, false otherwise
     */
    public boolean detectorRequiresDebayering(String detectorId) {
        logger.debug("Checking debayering requirement for detector: {}", detectorId);

        Map<String, Object> detectorSection = getResourceSection("id_detector");
        if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
            logger.warn("Detector {} not found in resources, defaulting to requires debayering", detectorId);
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);

        // First check for explicit configuration
        Object debayerFlag = detectorData.get("requires_debayering");
        if (debayerFlag instanceof Boolean) {
            boolean requires = (Boolean) debayerFlag;
            logger.debug("Detector {} has explicit debayering flag: {}", detectorId, requires);
            return requires;
        }

        // Default to requiring debayering for standard Bayer pattern sensors
        logger.debug("Detector {} defaulting to no deBayering as there is no indication for this in the config file", detectorId);
        return false;
    }

    // ========== NEW METHODS FOR UI DROPDOWNS ==========

    /**
     * Get available objectives for a given modality.
     * 
     * @param modalityName The base modality name (e.g., "ppm", "brightfield")
     * @return Set of objective IDs that have profiles for this modality
     */
    public Set<String> getAvailableObjectivesForModality(String modalityName) {
        logger.debug("Finding available objectives for modality: {}", modalityName);
        
        Set<String> objectives = new HashSet<>();
        List<Object> profiles = getList("acq_profiles_new", "profiles");
        
        if (profiles == null) {
            logger.warn("No acquisition profiles found in configuration - profiles is null");
            return objectives;
        }
        
        logger.debug("Found {} total profiles", profiles.size());
        
        for (Object profileObj : profiles) {
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) profileObj;
            
            String profileModality = (String) profile.get("modality");
            String objective = (String) profile.get("objective");
            
            logger.debug("Profile: modality={}, objective={}", profileModality, objective);
            
            if (modalityName.equals(profileModality) && objective != null) {
                objectives.add(objective);
                logger.debug("Added objective {} for modality {}", objective, modalityName);
            }
        }
        
        logger.debug("Found {} objectives for modality {}: {}", objectives.size(), modalityName, objectives);
        return objectives;
    }

    /**
     * Get available detectors for a given modality and objective combination.
     * 
     * @param modalityName The base modality name (e.g., "ppm", "brightfield")
     * @param objectiveId The objective ID (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Set of detector IDs that have profiles for this modality+objective combo
     */
    public Set<String> getAvailableDetectorsForModalityObjective(String modalityName, String objectiveId) {
        logger.debug("Finding available detectors for modality: {}, objective: {}", modalityName, objectiveId);
        
        Set<String> detectors = new HashSet<>();
        List<Object> profiles = getList("acq_profiles_new", "profiles");
        
        if (profiles != null) {
            for (Object profileObj : profiles) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = (Map<String, Object>) profileObj;
                
                String profileModality = (String) profile.get("modality");
                String profileObjective = (String) profile.get("objective");
                String detector = (String) profile.get("detector");
                
                if (modalityName.equals(profileModality) && 
                    objectiveId.equals(profileObjective) && 
                    detector != null) {
                    detectors.add(detector);
                }
            }
        }
        
        logger.debug("Found {} detectors for modality {} + objective {}: {}", 
                     detectors.size(), modalityName, objectiveId, detectors);
        return detectors;
    }
    /**
     * Check if white balance is enabled for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return true if white balance is enabled, false otherwise
     */
    public boolean isWhiteBalanceEnabled(String modality, String objective, String detector) {
        Object wbSetting = getProfileSetting(modality, objective, detector, "white_balance", "enabled");

        if (wbSetting instanceof Boolean) {
            return (Boolean) wbSetting;
        }

        // Default to true for backward compatibility
        logger.debug("No white balance setting found for {}/{}/{}, defaulting to enabled",
                modality, objective, detector);
        return true;
    }

    /**
     * Get white balance gains for a specific modality/objective/detector combination.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Map of angle to RGB gains, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Double>> getWhiteBalanceGains(String modality, String objective, String detector) {
        Object wbGains = getProfileSetting(modality, objective, detector, "white_balance", "gains");

        if (wbGains instanceof Map<?, ?>) {
            return (Map<String, Map<String, Double>>) wbGains;
        }

        return null;
    }
    /**
     * Get friendly names for objectives from the resources file.
     * 
     * @param objectiveIds Set of objective IDs to get names for
     * @return Map of objective ID to friendly name
     */
    public Map<String, String> getObjectiveFriendlyNames(Set<String> objectiveIds) {
        Map<String, String> friendlyNames = new HashMap<>();
        Map<String, Object> objectiveSection = getResourceSection("id_objective_lens");
        
        if (objectiveSection != null) {
            for (String objectiveId : objectiveIds) {
                if (objectiveSection.containsKey(objectiveId)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> objData = (Map<String, Object>) objectiveSection.get(objectiveId);
                    String name = (String) objData.get("name");
                    if (name != null) {
                        friendlyNames.put(objectiveId, name);
                    } else {
                        friendlyNames.put(objectiveId, objectiveId); // fallback
                    }
                }
            }
        }
        
        return friendlyNames;
    }

    /**
     * Get friendly names for detectors from the resources file.
     * 
     * @param detectorIds Set of detector IDs to get names for
     * @return Map of detector ID to friendly name
     */
    public Map<String, String> getDetectorFriendlyNames(Set<String> detectorIds) {
        Map<String, String> friendlyNames = new HashMap<>();
        Map<String, Object> detectorSection = getResourceSection("id_detector");
        
        if (detectorSection != null) {
            for (String detectorId : detectorIds) {
                if (detectorSection.containsKey(detectorId)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detData = (Map<String, Object>) detectorSection.get(detectorId);
                    String name = (String) detData.get("name");
                    if (name != null) {
                        friendlyNames.put(detectorId, name);
                    } else {
                        friendlyNames.put(detectorId, detectorId); // fallback
                    }
                }
            }
        }
        
        return friendlyNames;
    }

    /**
     * Get the default detector for a modality+objective combination.
     * Returns the first available detector if no explicit default is configured.
     * 
     * @param modalityName The base modality name
     * @param objectiveId The objective ID
     * @return The default detector ID, or null if none available
     */
    public String getDefaultDetectorForModalityObjective(String modalityName, String objectiveId) {
        Set<String> detectors = getAvailableDetectorsForModalityObjective(modalityName, objectiveId);
        
        if (detectors.isEmpty()) {
            return null;
        }
        
        // For now, just return the first one
        // In the future, could add logic to check for a "default" flag in config
        return detectors.iterator().next();
    }

    /**
     * Validate that a modality+objective+detector combination exists in the configuration.
     * 
     * @param modalityName The base modality name
     * @param objectiveId The objective ID
     * @param detectorId The detector ID
     * @return true if this combination has a configured profile
     */
    public boolean isValidModalityObjectiveDetectorCombination(String modalityName, String objectiveId, String detectorId) {
        Map<String, Object> profile = getAcquisitionProfile(modalityName, objectiveId, detectorId);
        return profile != null;
    }
}