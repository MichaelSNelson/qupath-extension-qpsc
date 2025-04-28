package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Singleton manager to load and query microscope configuration from YAML,
 * and write project-specific metadata to JSON. Provides type-safe getters
 * and validation of required keys.
 */
public class MicroscopeConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeConfigManager.class);

    private static MicroscopeConfigManager instance;
    private final Map<String, Object> configData;

    /**
     * Private constructor: loads YAML config into a map.
     * @param configPath filesystem path to the YAML configuration file
     */
    private MicroscopeConfigManager(String configPath) {
        this.configData = loadConfig(configPath);
    }

    /**
     * Obtain singleton instance, loading config on first call.
     * @param configPath path to microscope YAML
     * @return shared MicroscopeConfigManager
     */
    public static synchronized MicroscopeConfigManager getInstance(String configPath) {
        if (instance == null) {
            instance = new MicroscopeConfigManager(configPath);
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig(String configPath) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(configPath)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) loaded);
            } else {
                logger.error("Unexpected YAML root type: {}", loaded.getClass());
            }
        } catch (FileNotFoundException e) {
            logger.error("YAML file not found: {}", configPath, e);
        } catch (Exception e) {
            logger.error("Error parsing YAML file: {}", configPath, e);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Navigate nested config keys to retrieve a value.
     * @param keys sequence of map-keys to traverse
     * @return the value at nested key, or null if missing
     */
    @SuppressWarnings("unchecked")
    public Object getConfigItem(String... keys) {
        Object current = configData;
        for (String key : keys) {
            if (current instanceof Map && ((Map<?, ?>) current).containsKey(key)) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Type-safe getString: returns String or null
     */
    public String getString(String... keys) {
        Object v = getConfigItem(keys);
        return (v instanceof String) ? (String) v : null;
    }

    /**
     * Type-safe getInteger: returns Integer or null
     */
    public Integer getInteger(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return (v != null) ? Integer.parseInt(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected integer at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Type-safe getDouble: returns Double or null
     */
    public Double getDouble(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return (v != null) ? Double.parseDouble(v.toString()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Expected double at {} but got {}", String.join("/", keys), v);
            return null;
        }
    }

    /**
     * Type-safe getBoolean: returns Boolean or null
     */
    public Boolean getBoolean(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Boolean) return (Boolean) v;
        if (v != null) return Boolean.parseBoolean(v.toString());
        return null;
    }

    /**
     * Type-safe getList: returns List or null
     */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof List) return (List<Object>) v;
        return null;
    }

    /**
     * Type-safe getSection: returns nested Map or null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String... keys) {
        Object v = getConfigItem(keys);
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    /**
     * Validate that a set of required key paths exist. Each path is an array of keys.
     * @param requiredPaths set of String[] representing nested keys
     * @return missing paths (empty if all present)
     */
    public Set<String[]> validateRequiredKeys(Set<String[]> requiredPaths) {
        Set<String[]> missing = new HashSet<>();
        for (String[] path : requiredPaths) {
            if (getConfigItem(path) == null) missing.add(path);
        }
        if (!missing.isEmpty())
            logger.error("Missing required configuration keys: {}",
                    missing.stream()
                            .map(p -> String.join("/", p))
                            .toList());
        return missing;
    }

    /**
     * Write out a JSON file containing the given metadata map.
     * @param metadata map of properties to serialize
     * @param outputPath target JSON file path
     * @throws IOException on write error
     */
    public void writeMetadataAsJson(Map<String, Object> metadata, Path outputPath) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            gson.toJson(metadata, writer);
        }
    }

    /**
     * Reloads the YAML configuration from disk.
     * @param configPath path to the YAML file
     */
    public synchronized void reload(String configPath) {
        Map<String, Object> fresh = loadConfig(configPath);
        configData.clear();
        configData.putAll(fresh);
    }

    /**
     * Access the raw configuration map (read-only).
     * @return unmodifiable view of the config data
     */
    public Map<String, Object> getAllConfig() {
        return Collections.unmodifiableMap(configData);
    }

}
