package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * BackgroundSettingsReader - Utility for reading background collection settings files
 * 
 * <p>This class reads the background_settings.yml files created during background collection
 * and provides access to the stored acquisition parameters. This enables validation and
 * autofilling of background correction settings to ensure consistency.</p>
 */
public class BackgroundSettingsReader {
    
    private static final Logger logger = LoggerFactory.getLogger(BackgroundSettingsReader.class);
    
    /**
     * Container for background settings read from file
     */
    public static class BackgroundSettings {
        public final String modality;
        public final String objective;
        public final String detector;
        public final String magnification;
        public final List<AngleExposure> angleExposures;
        public final String settingsFilePath;
        
        public BackgroundSettings(String modality, String objective, String detector, 
                String magnification, List<AngleExposure> angleExposures, String settingsFilePath) {
            this.modality = modality;
            this.objective = objective;
            this.detector = detector;
            this.magnification = magnification;
            this.angleExposures = angleExposures;
            this.settingsFilePath = settingsFilePath;
        }
        
        @Override
        public String toString() {
            return String.format("BackgroundSettings[modality=%s, objective=%s, detector=%s, angles=%d]",
                    modality, objective, detector, angleExposures.size());
        }
    }
    
    /**
     * Attempt to find and read background settings for a given modality/objective/detector combination.
     * 
     * @param baseBackgroundFolder The base background correction folder from config
     * @param modality The modality name (e.g., "ppm")
     * @param objective The objective ID (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector The detector ID (e.g., "LOCI_DETECTOR_JAI_001")
     * @return BackgroundSettings if found and valid, null otherwise
     */
    public static BackgroundSettings findBackgroundSettings(String baseBackgroundFolder, 
            String modality, String objective, String detector) {
        
        if (baseBackgroundFolder == null || modality == null || objective == null || detector == null) {
            logger.debug("Cannot search for background settings - missing required parameters");
            return null;
        }
        
        try {
            // Extract magnification from objective
            String magnification = extractMagnificationFromObjective(objective);
            
            // Construct expected path: baseFolder/detector/modality/magnification/background_settings.yml
            File settingsFile = new File(baseBackgroundFolder, 
                    detector + File.separator + modality + File.separator + magnification + File.separator + "background_settings.yml");
            
            logger.debug("Looking for background settings at: {}", settingsFile.getAbsolutePath());
            
            if (!settingsFile.exists()) {
                logger.debug("Background settings file not found: {}", settingsFile.getAbsolutePath());
                return null;
            }
            
            return readBackgroundSettings(settingsFile);
            
        } catch (Exception e) {
            logger.error("Error searching for background settings", e);
            return null;
        }
    }
    
    /**
     * Read background settings from a specific file.
     * 
     * @param settingsFile The background_settings.yml file to read
     * @return BackgroundSettings if valid, null otherwise
     */
    public static BackgroundSettings readBackgroundSettings(File settingsFile) {
        logger.info("Reading background settings from: {}", settingsFile.getAbsolutePath());
        
        try (FileReader reader = new FileReader(settingsFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(reader);
            
            if (yamlData == null) {
                logger.warn("YAML file is empty or invalid: {}", settingsFile.getAbsolutePath());
                return null;
            }
            
            // Extract hardware configuration
            Map<String, Object> hardware = getMap(yamlData, "hardware");
            String modality = getString(hardware, "modality");
            String objective = getString(hardware, "objective");
            String detector = getString(hardware, "detector");
            String magnification = getString(hardware, "magnification");
            
            // Extract angle-exposure pairs from the structured list
            List<AngleExposure> angleExposures = new ArrayList<>();
            List<Map<String, Object>> angleExposureList = getMapList(yamlData, "angle_exposures");
            
            if (angleExposureList != null) {
                for (Map<String, Object> pair : angleExposureList) {
                    Double angle = getDouble(pair, "angle");
                    Double exposure = getDouble(pair, "exposure");
                    
                    if (angle != null && exposure != null) {
                        angleExposures.add(new AngleExposure(angle, exposure));
                        logger.debug("Parsed angle exposure: {}° = {}ms", angle, exposure);
                    }
                }
            }
            
            // Validate that we have the essential information
            if (modality == null || angleExposures.isEmpty()) {
                logger.warn("Background settings file is missing essential information: {}", settingsFile.getAbsolutePath());
                return null;
            }
            
            BackgroundSettings settings = new BackgroundSettings(modality, objective, detector, 
                    magnification, angleExposures, settingsFile.getAbsolutePath());
            
            logger.info("Successfully read background settings: {}", settings);
            return settings;
            
        } catch (IOException e) {
            logger.error("Failed to read background settings file: {}", settingsFile.getAbsolutePath(), e);
            return null;
        } catch (Exception e) {
            logger.error("Error parsing YAML background settings file: {}", settingsFile.getAbsolutePath(), e);
            return null;
        }
    }
    
    /**
     * Check if the provided angle-exposure list matches the background settings.
     * 
     * @param settings The background settings to compare against
     * @param currentAngleExposures The current angle-exposure list
     * @param tolerance Tolerance for exposure time comparison (e.g., 0.1 for 0.1ms tolerance)
     * @return true if they match within tolerance, false otherwise
     */
    public static boolean validateAngleExposures(BackgroundSettings settings, 
            List<AngleExposure> currentAngleExposures, double tolerance) {
        
        if (settings == null || currentAngleExposures == null) {
            return false;
        }
        
        if (settings.angleExposures.size() != currentAngleExposures.size()) {
            logger.debug("Angle count mismatch: settings={}, current={}", 
                    settings.angleExposures.size(), currentAngleExposures.size());
            return false;
        }
        
        // Sort both lists by angle for comparison
        List<AngleExposure> sortedSettings = new ArrayList<>(settings.angleExposures);
        List<AngleExposure> sortedCurrent = new ArrayList<>(currentAngleExposures);
        sortedSettings.sort((a, b) -> Double.compare(a.ticks(), b.ticks()));
        sortedCurrent.sort((a, b) -> Double.compare(a.ticks(), b.ticks()));
        
        for (int i = 0; i < sortedSettings.size(); i++) {
            AngleExposure settingsAe = sortedSettings.get(i);
            AngleExposure currentAe = sortedCurrent.get(i);
            
            // Check angle match (exact)
            if (Math.abs(settingsAe.ticks() - currentAe.ticks()) > 0.001) {
                logger.debug("Angle mismatch at index {}: settings={}°, current={}°", 
                        i, settingsAe.ticks(), currentAe.ticks());
                return false;
            }
            
            // Check exposure match (within tolerance)
            if (Math.abs(settingsAe.exposureMs() - currentAe.exposureMs()) > tolerance) {
                logger.debug("Exposure mismatch at index {}: settings={}ms, current={}ms (tolerance={}ms)", 
                        i, settingsAe.exposureMs(), currentAe.exposureMs(), tolerance);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Extract magnification from objective identifier.
     * Examples:
     * - "LOCI_OBJECTIVE_OLYMPUS_10X_001" -> "10x"
     * - "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x"
     * - "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001" -> "40x"
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective == null) return "unknown";
        
        // Look for pattern like "10X", "20X", "40X"
        Pattern pattern = Pattern.compile("(\\d+)X", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(objective);
        
        if (matcher.find()) {
            return matcher.group(1).toLowerCase() + "x";  // "20X" -> "20x"
        }
        
        // Fallback: return "unknown" if pattern not found
        return "unknown";
    }
    
    /**
     * Safely extract a Map from YAML data
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }
    
    /**
     * Safely extract a String from YAML data
     */
    private static String getString(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        return (value != null) ? value.toString() : null;
    }
    
    /**
     * Safely extract a Double from YAML data
     */
    private static Double getDouble(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Safely extract a List of Maps from YAML data
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getMapList(Map<String, Object> data, String key) {
        if (data == null || key == null) return null;
        Object value = data.get(key);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }
        return null;
    }
}