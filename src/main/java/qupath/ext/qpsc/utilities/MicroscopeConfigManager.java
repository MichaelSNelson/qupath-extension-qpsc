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
 * Singleton manager to load and query microscope configuration from YAML,
 * including support for "LOCI"-based lookups via a shared resources_LOCI.yml file.
 */
public class MicroscopeConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);

    // Singleton instance
    private static MicroscopeConfigManager instance;

    // Primary config data loaded from the chosen microscope YAML
    private final Map<String, Object> configData;

    // Shared LOCI resource data loaded from resources_LOCI.yml
    private final Map<String, Object> resourceData;

    /**
     * Private constructor: loads both the microscope-specific YAML and the shared LOCI resources.
     *
     * @param configPath Filesystem path to the microscope YAML configuration file.
     */
    private MicroscopeConfigManager(String configPath) {
        this.configData = loadConfig(configPath);
        String resPath = computeResourcePath(configPath);
        this.resourceData = loadConfig(resPath);
        if (resourceData.isEmpty()) {
            logger.warn("Could not load LOCI resources from {}", resPath);
        }
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
     * For example, transforms
     * ".../microscopes/config_PPM.yml" → ".../resources_LOCI.yml".
     *
     * @param configPath Path to the microscope YAML file.
     * @return Path to resources_LOCI.yml.
     */
    private static String computeResourcePath(String configPath) {
        Path cfg = Paths.get(configPath);
        // Go up two levels: <...>/microscopes/config.yml → <...>/resources_LOCI.yml
        return cfg.getParent().getParent().resolve("resources_LOCI.yml").toString();
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
     * Retrieves a nested configuration item by a sequence of keys.
     * If a value beginning with "LOCI" is encountered and further nesting is requested,
     * this method will consult the shared resources_LOCI.yml for the corresponding data.
     *
     * @param keys Sequence of nested keys (e.g., "imagingMode","BF_10x","detector","width_px").
     * @return The final value (String, Number, Map, List, etc.), or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(String... keys) {
        Object current = configData;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            // 1) Normal descent in the microscope config
            if (current instanceof Map<?, ?> map && map.containsKey(key)) {
                current = map.get(key);
                continue;
            }
            // 2) If current is a LOCI identifier, switch to resourceData
            if (i > 0 && current instanceof String id && id.startsWith("LOCI")) {
                String parentField = keys[i - 1];
                String section;
                switch (parentField) {
                    case "detector"      -> section = "ID_Detector";
                    case "objectiveLens" -> section = "ID_Objective";
                    case "z_stage"       -> section = "ID_Stage";
                    default                -> section = null;
                }
                if (section != null) {
                    String normalized = id.replace('-', '_');
                    Object sec = resourceData.get(section);
                    if (sec instanceof Map<?, ?> secMap && secMap.containsKey(normalized)) {
                        Object entry = ((Map<String, Object>) secMap).get(normalized);
                        if (entry instanceof Map<?, ?> entryMap && entryMap.containsKey(key)) {
                            current = entryMap.get(key);
                            continue;
                        }
                    }
                }
            }
            // 3) Not found
            return null;
        }
        return current;
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
