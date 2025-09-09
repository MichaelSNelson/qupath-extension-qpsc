package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for handling objective lens information, particularly
 * extracting magnification values from LOCI objective identifiers.
 * 
 * @author Mike Nelson
 */
public class ObjectiveUtils {
    private static final Logger logger = LoggerFactory.getLogger(ObjectiveUtils.class);
    
    // Pattern to match magnification in various formats: 10X, 20x, 4X_POL, etc.
    private static final Pattern MAGNIFICATION_PATTERN = Pattern.compile("(\\d+)[Xx](?:_|$|\\s)");
    
    /**
     * Extracts magnification value from an objective identifier.
     * 
     * <p>This method handles various objective naming formats including:
     * <ul>
     *   <li>LOCI_OBJECTIVE_OLYMPUS_20X_POL_001 → "20x"</li>
     *   <li>LOCI_OBJECTIVE_OLYMPUS_10X_001 → "10x"</li>
     *   <li>LOCI_OBJECTIVE_NIKON_4X_002 → "4x"</li>
     * </ul>
     * 
     * @param objectiveIdentifier The objective identifier string
     * @return The magnification string in format "##x" (e.g., "20x"), or null if not found
     */
    public static String extractMagnification(String objectiveIdentifier) {
        if (objectiveIdentifier == null || objectiveIdentifier.isEmpty()) {
            logger.debug("Null or empty objective identifier");
            return null;
        }
        
        logger.debug("Extracting magnification from: {}", objectiveIdentifier);
        
        Matcher matcher = MAGNIFICATION_PATTERN.matcher(objectiveIdentifier);
        if (matcher.find()) {
            String mag = matcher.group(1) + "x";
            logger.debug("Extracted magnification: {}", mag);
            return mag;
        }
        
        logger.warn("Could not extract magnification from objective: {}", objectiveIdentifier);
        return null;
    }
    
    /**
     * Creates an enhanced folder name that includes magnification from the objective.
     * 
     * <p>This method takes a base scan type (like "ppm_1") and an objective identifier,
     * extracts the magnification, and creates a folder name like "ppm_20x_1".</p>
     * 
     * @param baseScanType The base scan type (e.g., "ppm_1")
     * @param objectiveIdentifier The objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Enhanced folder name with magnification (e.g., "ppm_20x_1"), or original baseScanType if extraction fails
     */
    public static String createEnhancedFolderName(String baseScanType, String objectiveIdentifier) {
        if (baseScanType == null || baseScanType.isEmpty()) {
            logger.warn("Base scan type is null or empty");
            return baseScanType;
        }
        
        String magnification = extractMagnification(objectiveIdentifier);
        if (magnification == null) {
            logger.debug("No magnification extracted, using base scan type: {}", baseScanType);
            return baseScanType;
        }
        
        // Split the base scan type to insert magnification
        // Expected format: "modality_count" -> "modality_magnification_count"
        String[] parts = baseScanType.split("_");
        if (parts.length >= 2) {
            // Insert magnification before the last part (count)
            StringBuilder enhanced = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                enhanced.append(parts[i]).append("_");
            }
            enhanced.append(magnification).append("_").append(parts[parts.length - 1]);
            
            String result = enhanced.toString();
            logger.info("Enhanced folder name: {} -> {}", baseScanType, result);
            return result;
        } else {
            // Fallback: just append magnification
            String result = baseScanType + "_" + magnification;
            logger.info("Enhanced folder name (fallback): {} -> {}", baseScanType, result);
            return result;
        }
    }
}