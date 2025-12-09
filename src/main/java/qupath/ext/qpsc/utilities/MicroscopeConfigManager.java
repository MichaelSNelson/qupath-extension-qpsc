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

    // External autofocus settings loaded from autofocus_{microscope}.yml
    // Maps objective ID -> autofocus parameters map
    private final Map<String, Map<String, Object>> autofocusData;

    // External imageprocessing settings loaded from imageprocessing_{microscope}.yml
    // Contains imaging_profiles and background_correction settings
    private final Map<String, Object> imageprocessingData;

    /**
     * Private constructor: loads microscope YAML, shared LOCI resources, external autofocus settings, and imageprocessing settings.
     *
     * @param configPath Filesystem path to the microscope YAML configuration file.
     */
    private MicroscopeConfigManager(String configPath) {
        this.configPath = configPath;
        this.configData = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        this.resourceData = loadConfig(resPath);

        // Load external autofocus settings if available
        this.autofocusData = loadAutofocusConfig(configPath);

        // Load external imageprocessing settings if available
        this.imageprocessingData = loadImageprocessingConfig(configPath);

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
     * Reloads the microscope YAML, shared LOCI resources, external autofocus settings, and imageprocessing settings.
     *
     * @param configPath Path to the microscope YAML file.
     */
    public synchronized void reload(String configPath) {
        configData.clear();
        configData.putAll(loadConfig(configPath));
        String resPath = computeResourcePath(configPath);
        resourceData.clear();
        resourceData.putAll(loadConfig(resPath));
        autofocusData.clear();
        autofocusData.putAll(loadAutofocusConfig(configPath));
        imageprocessingData.clear();
        imageprocessingData.putAll(loadImageprocessingConfig(configPath));
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
     * Loads external autofocus configuration from autofocus_{microscope}.yml file.
     * This file contains per-objective autofocus parameters separate from the main config.
     *
     * @param configPath Path to the main microscope YAML file
     * @return Map of objective ID -> autofocus parameters, or empty map if file doesn't exist
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadAutofocusConfig(String configPath) {
        Map<String, Map<String, Object>> autofocusMap = new LinkedHashMap<>();

        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                logger.debug("Config file not found for autofocus lookup: {}", configPath);
                return autofocusMap;
            }

            // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
            String microscopeName = extractMicroscopeName(configFile.getName());

            // Construct autofocus config path in same directory
            File configDir = configFile.getParentFile();
            File autofocusFile = new File(configDir, "autofocus_" + microscopeName + ".yml");

            if (!autofocusFile.exists()) {
                logger.info("No external autofocus config file found: {}", autofocusFile.getAbsolutePath());
                return autofocusMap;
            }

            // Load and parse autofocus YAML
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(autofocusFile.toPath()));

            if (data != null && data.containsKey("autofocus_settings")) {
                List<Map<String, Object>> settings = (List<Map<String, Object>>) data.get("autofocus_settings");

                for (Map<String, Object> entry : settings) {
                    String objective = (String) entry.get("objective");
                    if (objective != null) {
                        // Store all autofocus parameters for this objective
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("n_steps", entry.get("n_steps"));
                        params.put("search_range_um", entry.get("search_range_um"));
                        params.put("n_tiles", entry.get("n_tiles"));
                        params.put("interp_strength", entry.get("interp_strength"));
                        params.put("interp_kind", entry.get("interp_kind"));
                        params.put("score_metric", entry.get("score_metric"));

                        autofocusMap.put(objective, params);
                    }
                }

                logger.info("Loaded external autofocus settings for {} objectives from: {}",
                        autofocusMap.size(), autofocusFile.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.warn("Error loading external autofocus config", e);
        }

        return autofocusMap;
    }

    /**
     * Loads external imageprocessing configuration from imageprocessing_{microscope}.yml file.
     * This file contains imaging profiles (exposure, gain, white balance) and background correction settings
     * organized by modality -> objective -> detector hierarchy.
     *
     * @param configPath Path to the main microscope YAML file
     * @return Map containing imageprocessing data (imaging_profiles and background_correction), or empty map if file doesn't exist
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadImageprocessingConfig(String configPath) {
        Map<String, Object> imageprocessingMap = new LinkedHashMap<>();

        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                logger.debug("Config file not found for imageprocessing lookup: {}", configPath);
                return imageprocessingMap;
            }

            // Extract microscope name from config filename (e.g., "config_PPM.yml" -> "PPM")
            String microscopeName = extractMicroscopeName(configFile.getName());

            // Construct imageprocessing config path in same directory
            File configDir = configFile.getParentFile();
            File imageprocessingFile = new File(configDir, "imageprocessing_" + microscopeName + ".yml");

            if (!imageprocessingFile.exists()) {
                logger.info("No external imageprocessing config file found: {}", imageprocessingFile.getAbsolutePath());
                return imageprocessingMap;
            }

            // Load and parse imageprocessing YAML
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(imageprocessingFile.toPath()));

            if (data != null) {
                // Store the entire imageprocessing config
                imageprocessingMap.putAll(data);

                logger.info("Loaded external imageprocessing config from: {}", imageprocessingFile.getAbsolutePath());
                if (data.containsKey("imaging_profiles")) {
                    logger.debug("  - Found imaging_profiles section");
                }
                if (data.containsKey("background_correction")) {
                    logger.debug("  - Found background_correction section");
                }
            }

        } catch (Exception e) {
            logger.warn("Error loading external imageprocessing config", e);
        }

        return imageprocessingMap;
    }

    /**
     * Extracts microscope name from config filename.
     * E.g., "config_PPM.yml" -> "PPM", "config_ppm.yml" -> "ppm"
     *
     * @param configFilename The config filename
     * @return Microscope name extracted from filename
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present (case insensitive)
        if (nameWithoutExt.toLowerCase().startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
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
     * @return Map containing the profile keys, or null if combination is invalid
     * @deprecated With simplified hardware config, all modality/objective/detector combinations
     *             are valid. Use {@link #isValidHardwareCombination(String, String, String)} instead.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAcquisitionProfile(String modality, String objective, String detector) {
        // With new hardware config, validate the combination exists
        if (!isValidHardwareCombination(modality, objective, detector)) {
            logger.warn("Invalid hardware combination: modality={}, objective={}, detector={}",
                    modality, objective, detector);
            return null;
        }

        // Return a synthetic profile map for backwards compatibility
        Map<String, Object> profile = new HashMap<>();
        profile.put("modality", modality);
        profile.put("objective", objective);
        profile.put("detector", detector);
        logger.debug("Created synthetic profile for {}/{}/{}", modality, objective, detector);
        return profile;
    }

    /**
     * Check if a modality/objective/detector combination is valid based on hardware configuration.
     * With simplified config, all combinations are valid if the objective and detector exist.
     *
     * @param modality The modality name (checked against available modalities)
     * @param objective The objective ID (checked against hardware.objectives)
     * @param detector The detector ID (checked against hardware.detectors)
     * @return true if all components exist in configuration
     */
    public boolean isValidHardwareCombination(String modality, String objective, String detector) {
        // Check modality exists
        Set<String> availableModalities = getAvailableModalities();
        if (!availableModalities.contains(modality)) {
            logger.debug("Modality {} not found in available modalities: {}", modality, availableModalities);
            return false;
        }

        // Check objective exists in hardware
        Set<String> availableObjectives = getAvailableObjectivesForModality(modality);
        if (!availableObjectives.contains(objective)) {
            logger.debug("Objective {} not found in hardware objectives: {}", objective, availableObjectives);
            return false;
        }

        // Check detector exists in hardware
        Set<String> availableDetectors = getHardwareDetectors();
        if (!availableDetectors.contains(detector)) {
            logger.debug("Detector {} not found in hardware detectors: {}", detector, availableDetectors);
            return false;
        }

        return true;
    }

    /**
     * Get a setting from the external imageprocessing configuration file.
     * Settings like exposures, gains, and white balance are stored in imageprocessing_{microscope}.yml.
     * Pixel sizes are now in the hardware section of the main config.
     *
     * @param modality The modality name
     * @param objective The objective ID
     * @param detector The detector ID
     * @param settingPath Path to the setting within the profile
     * @return The setting value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Object getProfileSetting(String modality, String objective, String detector, String... settingPath) {
        // Check external imageprocessing config
        if (imageprocessingData != null && imageprocessingData.containsKey("imaging_profiles")) {
            Map<String, Object> imagingProfiles = (Map<String, Object>) imageprocessingData.get("imaging_profiles");
            if (imagingProfiles != null && imagingProfiles.containsKey(modality)) {
                Map<String, Object> modalityProfiles = (Map<String, Object>) imagingProfiles.get(modality);
                if (modalityProfiles != null && modalityProfiles.containsKey(objective)) {
                    Map<String, Object> objectiveProfiles = (Map<String, Object>) modalityProfiles.get(objective);
                    if (objectiveProfiles != null && objectiveProfiles.containsKey(detector)) {
                        Map<String, Object> detectorProfile = (Map<String, Object>) objectiveProfiles.get(detector);
                        if (detectorProfile != null) {
                            Object value = getNestedValue(detectorProfile, settingPath);
                            if (value != null) {
                                logger.debug("Found setting in imaging_profiles: {} for {}/{}/{}",
                                        Arrays.toString(settingPath), modality, objective, detector);
                                return value;
                            }
                        }
                    }
                }
            }
        }

        logger.debug("Setting not found: {} for {}/{}/{}",
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
     * Pixel sizes are stored in the hardware.objectives section of the main config.
     *
     * @param modality The modality name (unused, kept for API compatibility)
     * @param objective The objective ID
     * @param detector The detector ID
     * @return Pixel size in microns
     * @throws IllegalArgumentException if pixel size cannot be determined
     */
    public double getModalityPixelSize(String modality, String objective, String detector) {
        Double pixelSize = getHardwarePixelSize(objective, detector);

        if (pixelSize != null && pixelSize > 0) {
            logger.debug("Pixel size for {}/{}/{}: {} um", modality, objective, detector, pixelSize);
            return pixelSize;
        }

        logger.error("No valid pixel size found for {}/{}/{}", modality, objective, detector);
        throw new IllegalArgumentException(
                String.format("Cannot determine pixel size for modality '%s', objective '%s', detector '%s'. " +
                        "Please check hardware configuration.", modality, objective, detector));
    }

    /**
     * Get pixel size for a modality by searching through hardware objectives.
     * This is a convenience method for when you only have the modality name.
     * Returns the pixel size for the first available objective/detector combination.
     *
     * @param modalityName The modality name (e.g., "bf_10x", "ppm_20x", "bf_10x_1")
     * @return Pixel size in microns
     * @throws IllegalArgumentException if no pixel size can be determined
     */
    public double getPixelSizeForModality(String modalityName) {
        logger.debug("Finding pixel size for modality: {}", modalityName);

        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modalityName;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        // Get available hardware and try to find a valid pixel size
        List<Map<String, Object>> objectives = getHardwareObjectives();
        Set<String> detectors = getHardwareDetectors();

        if (objectives.isEmpty()) {
            throw new IllegalArgumentException("No objectives defined in hardware configuration");
        }

        if (detectors.isEmpty()) {
            throw new IllegalArgumentException("No detectors defined in hardware configuration");
        }

        // Try each objective/detector combination until we find a valid pixel size
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null) continue;

            for (String detectorId : detectors) {
                try {
                    double pixelSize = getModalityPixelSize(baseModality, objectiveId, detectorId);
                    if (pixelSize > 0) {
                        logger.debug("Found pixel size {} um for modality {} using {}/{}",
                                     pixelSize, modalityName, objectiveId, detectorId);
                        return pixelSize;
                    }
                } catch (IllegalArgumentException e) {
                    // Continue searching if this combination doesn't have pixel size
                    logger.debug("No pixel size for {}/{}: {}", objectiveId, detectorId, e.getMessage());
                }
            }
        }

        throw new IllegalArgumentException(
                String.format("Cannot determine pixel size for modality '%s'. " +
                        "No valid hardware configuration found.", modalityName));
    }

    /**
     * Get autofocus parameters for a specific objective from the external autofocus file.
     * Autofocus settings are stored in autofocus_{microscope}.yml.
     *
     * @param objective The objective ID
     * @return Map of autofocus parameters, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAutofocusParams(String objective) {
        logger.debug("Getting autofocus parameters for objective: {}", objective);

        // Check external autofocus file
        if (autofocusData != null && autofocusData.containsKey(objective)) {
            Map<String, Object> params = autofocusData.get(objective);
            logger.info("Found autofocus params for {} in external autofocus file", objective);
            return params;
        }

        logger.warn("No autofocus parameters found for objective: {} (check autofocus_{}.yml)",
                objective, getString("microscope", "name"));
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
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> base_folder
     * Returns null if not found.
     */
    @SuppressWarnings("unchecked")
    public String getBackgroundCorrectionFolder(String modality) {
        logger.debug("Getting background correction folder for modality: {}", modality);

        // Check external imageprocessing config
        if (imageprocessingData != null && imageprocessingData.containsKey("background_correction")) {
            Map<String, Object> bgCorrection = (Map<String, Object>) imageprocessingData.get("background_correction");
            if (bgCorrection != null && bgCorrection.containsKey(modality)) {
                Map<String, Object> modalityBg = (Map<String, Object>) bgCorrection.get(modality);
                if (modalityBg != null && modalityBg.containsKey("base_folder")) {
                    String folder = modalityBg.get("base_folder").toString();
                    logger.debug("Found background folder in imageprocessing config: {}", folder);
                    return folder;
                }
            }
        }

        logger.warn("No background correction folder found for {}", modality);
        return null;
    }

    /**
     * Check if background correction is enabled for a modality.
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> enabled
     */
    @SuppressWarnings("unchecked")
    public boolean isBackgroundCorrectionEnabled(String modality) {
        // Check external imageprocessing config
        if (imageprocessingData != null && imageprocessingData.containsKey("background_correction")) {
            Map<String, Object> bgCorrection = (Map<String, Object>) imageprocessingData.get("background_correction");
            if (bgCorrection != null && bgCorrection.containsKey(modality)) {
                Map<String, Object> modalityBg = (Map<String, Object>) bgCorrection.get(modality);
                if (modalityBg != null && modalityBg.containsKey("enabled")) {
                    Boolean enabled = (Boolean) modalityBg.get("enabled");
                    logger.debug("Found background_correction.enabled in imageprocessing config for {}: {}", modality, enabled);
                    return enabled != null && enabled;
                }
            }
        }

        return false;
    }

    /**
     * Get background correction method for a modality.
     * Reads from imageprocessing_{microscope}.yml -> background_correction -> modality -> method
     * Returns null if not configured.
     */
    @SuppressWarnings("unchecked")
    public String getBackgroundCorrectionMethod(String modality) {
        // Check external imageprocessing config
        if (imageprocessingData != null && imageprocessingData.containsKey("background_correction")) {
            Map<String, Object> bgCorrection = (Map<String, Object>) imageprocessingData.get("background_correction");
            if (bgCorrection != null && bgCorrection.containsKey(modality)) {
                Map<String, Object> modalityBg = (Map<String, Object>) bgCorrection.get(modality);
                if (modalityBg != null && modalityBg.containsKey("method")) {
                    String method = modalityBg.get("method").toString();
                    logger.debug("Found background_correction.method in imageprocessing config for {}: {}", modality, method);
                    return method;
                }
            }
        }

        return null;
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
        List<String> errors = new ArrayList<>();

        // Check detailed required configuration keys
        Set<String[]> required = Set.of(
                // Basic microscope info
                new String[]{"microscope", "name"},
                new String[]{"microscope", "type"},

                // Stage configuration - used for bounds checking
                new String[]{"stage", "limits", "x_um", "low"},
                new String[]{"stage", "limits", "x_um", "high"},
                new String[]{"stage", "limits", "y_um", "low"},
                new String[]{"stage", "limits", "y_um", "high"},
                new String[]{"stage", "limits", "z_um", "low"},
                new String[]{"stage", "limits", "z_um", "high"},

                // Core sections
                new String[]{"modalities"},
                new String[]{"hardware"},
                new String[]{"slide_size_um"}
        );

        // First check the basic required keys
        var missing = validateRequiredKeys(required);
        if (!missing.isEmpty()) {
            errors.addAll(missing.stream().map(arr -> String.join(".", arr)).toList());
        }

        // Check modalities section
        Set<String> availableModalities = getAvailableModalities();
        if (availableModalities.isEmpty()) {
            errors.add("No modalities defined in configuration");
        }

        // Check hardware section
        errors.addAll(validateHardwareSection(availableModalities));

        // Check PPM-specific requirements if PPM modality is present
        if (availableModalities.contains("ppm")) {
            errors.addAll(validatePPMConfiguration());
        }

        if (!errors.isEmpty()) {
            logger.error("Configuration validation failed. Errors: {}", errors);
        } else {
            logger.info("Configuration validation passed");
        }

        return errors;
    }

    /**
     * Validates the hardware section for completeness and consistency.
     */
    private List<String> validateHardwareSection(Set<String> availableModalities) {
        List<String> errors = new ArrayList<>();

        // Check hardware.objectives
        List<Map<String, Object>> objectives = getHardwareObjectives();
        if (objectives.isEmpty()) {
            errors.add("No objectives defined in hardware configuration");
            return errors;
        }

        // Check hardware.detectors
        Set<String> detectors = getHardwareDetectors();
        if (detectors.isEmpty()) {
            errors.add("No detectors defined in hardware configuration");
            return errors;
        }

        // Validate each objective has required fields
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null || objectiveId.isBlank()) {
                errors.add("Objective missing required 'id' field");
                continue;
            }

            // Check pixel_size_xy_um exists
            Object pixelSizeMap = objective.get("pixel_size_xy_um");
            if (!(pixelSizeMap instanceof Map)) {
                errors.add(String.format("Objective %s missing pixel_size_xy_um mapping", objectiveId));
                continue;
            }

            // Check pixel sizes exist for all detectors
            @SuppressWarnings("unchecked")
            Map<String, Object> pixelSizes = (Map<String, Object>) pixelSizeMap;
            for (String detectorId : detectors) {
                Object pixelSize = pixelSizes.get(detectorId);
                if (!(pixelSize instanceof Number) || ((Number) pixelSize).doubleValue() <= 0) {
                    errors.add(String.format("Objective %s missing valid pixel size for detector %s",
                            objectiveId, detectorId));
                }
            }
        }

        // Validate each detector exists in resources
        Map<String, Object> detectorSection = getResourceSection("id_detector");
        for (String detectorId : detectors) {
            if (detectorSection == null || !detectorSection.containsKey(detectorId)) {
                errors.add(String.format("Detector %s not found in resources_LOCI.yml", detectorId));
                continue;
            }

            // Validate detector dimensions
            @SuppressWarnings("unchecked")
            Map<String, Object> detectorData = (Map<String, Object>) detectorSection.get(detectorId);
            Integer width = detectorData != null && detectorData.get("width_px") instanceof Number ?
                    ((Number) detectorData.get("width_px")).intValue() : null;
            Integer height = detectorData != null && detectorData.get("height_px") instanceof Number ?
                    ((Number) detectorData.get("height_px")).intValue() : null;

            if (width == null || height == null || width <= 0 || height <= 0) {
                errors.add(String.format("Detector %s has invalid dimensions: width=%s, height=%s",
                        detectorId, width, height));
            }
        }

        // Validate exposure settings exist for at least one modality/objective/detector combo
        boolean hasValidExposures = false;
        for (String modality : availableModalities) {
            for (Map<String, Object> objective : objectives) {
                String objectiveId = (String) objective.get("id");
                if (objectiveId == null) continue;

                for (String detectorId : detectors) {
                    Map<String, Object> exposures = getModalityExposures(modality, objectiveId, detectorId);
                    if (exposures != null && !exposures.isEmpty()) {
                        hasValidExposures = true;
                        logger.debug("Valid exposure settings found for {}/{}/{}", modality, objectiveId, detectorId);
                        break;
                    }
                }
                if (hasValidExposures) break;
            }
            if (hasValidExposures) break;
        }

        if (!hasValidExposures) {
            errors.add("No valid exposure settings found in imageprocessing config for any modality/objective/detector combination");
        }

        // Validate autofocus settings exist for each objective (from external autofocus file)
        for (Map<String, Object> objective : objectives) {
            String objectiveId = (String) objective.get("id");
            if (objectiveId == null) continue;

            Map<String, Object> autofocus = getAutofocusParams(objectiveId);
            if (autofocus != null) {
                Integer nSteps = getAutofocusIntParam(objectiveId, "n_steps");
                Integer searchRange = getAutofocusIntParam(objectiveId, "search_range_um");

                if (nSteps == null || nSteps <= 0 || searchRange == null || searchRange <= 0) {
                    logger.warn("Incomplete autofocus configuration for objective {}: n_steps={}, search_range_um={}",
                            objectiveId, nSteps, searchRange);
                    // Not an error - autofocus is optional
                }
            }
        }

        return errors;
    }

    /**
     * Validates PPM-specific configuration requirements.
     */
    private List<String> validatePPMConfiguration() {
        List<String> errors = new ArrayList<>();

        // Check rotation stage
        String rotationDevice = getString("modalities", "ppm", "rotation_stage", "device");
        if (rotationDevice == null) {
            errors.add("PPM modality missing rotation stage configuration");
        }

        // Check rotation angles
        List<Object> rotationAngles = getList("modalities", "ppm", "rotation_angles");
        if (rotationAngles == null || rotationAngles.isEmpty()) {
            errors.add("PPM modality missing rotation angles");
        }

        return errors;
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

    // ========== HARDWARE SECTION ACCESS METHODS ==========

    /**
     * Get the list of available objectives from the hardware section.
     *
     * @return List of objective configuration maps, or empty list if not found
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getHardwareObjectives() {
        List<Object> objectives = getList("hardware", "objectives");
        if (objectives == null) {
            logger.warn("No objectives found in hardware section");
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object obj : objectives) {
            if (obj instanceof Map) {
                result.add((Map<String, Object>) obj);
            }
        }
        return result;
    }

    /**
     * Get the list of available detector IDs from the hardware section.
     *
     * @return Set of detector IDs, or empty set if not found
     */
    public Set<String> getHardwareDetectors() {
        List<Object> detectors = getList("hardware", "detectors");
        if (detectors == null) {
            logger.warn("No detectors found in hardware section");
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();
        for (Object obj : detectors) {
            if (obj instanceof String) {
                result.add((String) obj);
            }
        }
        return result;
    }

    /**
     * Get pixel size for a specific objective/detector combination from the hardware section.
     *
     * @param objectiveId The objective ID
     * @param detectorId The detector ID
     * @return Pixel size in microns, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Double getHardwarePixelSize(String objectiveId, String detectorId) {
        List<Map<String, Object>> objectives = getHardwareObjectives();

        for (Map<String, Object> objective : objectives) {
            String id = (String) objective.get("id");
            if (objectiveId.equals(id)) {
                Object pixelSizeMap = objective.get("pixel_size_xy_um");
                if (pixelSizeMap instanceof Map) {
                    Object pixelSize = ((Map<String, Object>) pixelSizeMap).get(detectorId);
                    if (pixelSize instanceof Number) {
                        return ((Number) pixelSize).doubleValue();
                    }
                }
            }
        }

        logger.debug("No pixel size found in hardware section for objective {} with detector {}",
                     objectiveId, detectorId);
        return null;
    }

    // ========== METHODS FOR UI DROPDOWNS ==========

    /**
     * Get available objectives for a given modality.
     * With the simplified hardware configuration, all objectives are available for all modalities.
     *
     * @param modalityName The base modality name (e.g., "ppm", "brightfield") - currently unused
     *                     but kept for API compatibility and potential future restrictions
     * @return Set of objective IDs available on this microscope
     */
    public Set<String> getAvailableObjectivesForModality(String modalityName) {
        logger.debug("Finding available objectives for modality: {}", modalityName);

        Set<String> objectives = new HashSet<>();
        List<Map<String, Object>> hardwareObjectives = getHardwareObjectives();

        if (hardwareObjectives.isEmpty()) {
            logger.warn("No objectives found in hardware configuration");
            return objectives;
        }

        // With simplified hardware config, all objectives are available for all modalities
        for (Map<String, Object> objectiveConfig : hardwareObjectives) {
            String id = (String) objectiveConfig.get("id");
            if (id != null) {
                objectives.add(id);
            }
        }

        logger.debug("Found {} objectives for modality {}: {}", objectives.size(), modalityName, objectives);
        return objectives;
    }

    /**
     * Get available detectors for a given modality and objective combination.
     * With the simplified hardware configuration, all detectors are available for all
     * modality+objective combinations.
     *
     * @param modalityName The base modality name (e.g., "ppm", "brightfield") - currently unused
     * @param objectiveId The objective ID - currently unused but kept for API compatibility
     * @return Set of detector IDs available on this microscope
     */
    public Set<String> getAvailableDetectorsForModalityObjective(String modalityName, String objectiveId) {
        logger.debug("Finding available detectors for modality: {}, objective: {}", modalityName, objectiveId);

        // With simplified hardware config, all detectors are available for all combinations
        Set<String> detectors = getHardwareDetectors();

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

        // Default to true if not specified
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
     * @return true if this combination is valid (all components exist in config)
     */
    public boolean isValidModalityObjectiveDetectorCombination(String modalityName, String objectiveId, String detectorId) {
        return isValidHardwareCombination(modalityName, objectiveId, detectorId);
    }
}