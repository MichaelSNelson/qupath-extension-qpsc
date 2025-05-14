package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
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

    /**
     * Private constructor: loads both the microscope-specific YAML and the shared LOCI resources.
     *
     * @param configPath Filesystem path to the microscope YAML configuration file.
     */


    private MicroscopeConfigManager(String configPath) {
        this.configData = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        this.resourceData = loadConfig(resPath);

        // Dynamically build field-to-section map from the top-level of resources_LOCI.yml
        this.lociSectionMap = new HashMap<>();
        for (String section : resourceData.keySet()) {
            if (section.startsWith("ID_")) {
                String field = section.substring(3) // remove "ID_"
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
     * Computes the path to the shared LOCI resources file based on the microscope config path.
     * For example, transforms ".../microscopes/config_PPM.yml" → ".../resources_LOCI.yml".
     *
     * @param configPath Path to the microscope YAML file.
     * @return Path to resources_LOCI.yml (absolute path).
     * @throws FileNotFoundException if the file does not exist.
     */
    private static String computeResourcePath(String configPath) {
        Path cfg = Paths.get(configPath);
        // Go up two levels: <...>/microscopes/config.yml → <...>/resources_LOCI.yml
        Path resourcePath = cfg.getParent().getParent().resolve("resources_LOCI.yml").toAbsolutePath();

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
     * Retrieves a nested configuration item by a sequence of keys, traversing the main config first.
     * If a value beginning with "LOCI" is encountered, switches to resources_LOCI.yml to resolve details.
     *
     * @param keys Sequence of nested keys (e.g., "imagingMode","BF_10x","detector","width_px")
     * @return The final value (String, Number, Map, List, etc.), or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(String... keys) {
        Object current = configData;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];

            // 1. Standard descent
            if (current instanceof Map<?, ?> map && map.containsKey(key)) {
                current = map.get(key);

                // If this is a LOCI reference string and there are more keys,
                // switch to resources and continue lookup
                if (current instanceof String id && id.startsWith("LOCI") && i+1 < keys.length) {
                    // Try to guess section name from the parent key
                    String parentField = keys[i];
                    String section = findResourceSectionForID(parentField, resourceData);
                    String normalized = id.replace('-', '_');
                    Object sectionObj = resourceData.get(section);
                    if (sectionObj instanceof Map secMap && secMap.containsKey(normalized)) {
                        current = ((Map<?,?>)secMap).get(normalized);
                        // Now continue with the NEXT key in keys
                        continue;
                    } else {
                        logger.warn("Could not find section {} or id {} in resources", section, normalized);
                        return null;
                    }
                }
                // Else, continue as normal
                continue;
            }

            // If at this point current is a Map and has key, descend
            if (current instanceof Map<?,?> map2 && map2.containsKey(key)) {
                current = map2.get(key);
                continue;
            }

            logger.warn("Key '{}' not found at step {}. Current: {}", key, i, current);
            return null;
        }
        return current;
    }

    /**
     * Guess the resource section name from the parent field or search all sections.
     */
    private static String findResourceSectionForID(String parentField, Map<String, Object> resourceData) {
        // Simple mapping based on likely field names (expand if needed)
        for (String section : resourceData.keySet()) {
            // section: "ID_Detector", parentField: "detector"
            if (section.toLowerCase().contains(parentField.toLowerCase())) {
                return section;
            }
        }
        // Fallback: just return the first (warn)
        String fallback = resourceData.keySet().stream().findFirst().orElse(null);
        if (fallback != null)
            logger.warn("Falling back to first resource section '{}'", fallback);
        return fallback;
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
}
