package qupath.ext.qpsc.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Manages rotation strategies for different imaging modalities.
 * Reads PPM angles from config and applies appropriate strategy.
 */
public class RotationManager {
    private static final Logger logger = LoggerFactory.getLogger(RotationManager.class);

    private final List<RotationStrategy> strategies = new ArrayList<>();

    /**
     * Creates a RotationManager configured for the given modality.
     * @param modality The imaging modality name
     */
    public RotationManager(String modality) {
        initializeStrategies(modality);
    }

    private void initializeStrategies(String modality) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        // Check if this is a PPM modality by name
        boolean isPPMModality = modality != null && modality.startsWith("PPM_");

        if (isPPMModality) {
            // Get PPM config from global section
            Map<String, Object> ppmConfig = mgr.getPPMConfig();

            Double plusAngle = null;
            Double minusAngle = null;

            // Read from global PPM section
            Map<String, Object> ppmPlus = (Map<String, Object>) ppmConfig.get("ppm_plus");
            Map<String, Object> ppmMinus = (Map<String, Object>) ppmConfig.get("ppm_minus");

            if (ppmPlus != null && ppmPlus.containsKey("degrees")) {
                plusAngle = ((Number) ppmPlus.get("degrees")).doubleValue();
            }

            if (ppmMinus != null && ppmMinus.containsKey("degrees")) {
                minusAngle = ((Number) ppmMinus.get("degrees")).doubleValue();
            }

            // Use defaults if not configured
            if (plusAngle == null || minusAngle == null) {
                logger.warn("PPM angles not configured in YAML. Using defaults (+5, -5).");
                plusAngle = 5.0;
                minusAngle = -5.0;
            }

            strategies.add(new PPMRotationStrategy(plusAngle, minusAngle));
            logger.info("PPM angles configured: {} to {} degrees", minusAngle, plusAngle);
        }

        // Check if this is a BF modality
        boolean isBFModality = modality != null && modality.startsWith("BF_");

        if (isBFModality) {
            // Get brightfield angle from PPM config
            Map<String, Object> ppmConfig = mgr.getPPMConfig();
            Map<String, Object> bfConfig = (Map<String, Object>) ppmConfig.get("brightfield");

            Double bfAngle = 90.0; // default
            if (bfConfig != null && bfConfig.containsKey("degrees")) {
                bfAngle = ((Number) bfConfig.get("degrees")).doubleValue();
            }

            strategies.add(new BrightfieldRotationStrategy(bfAngle));
            logger.info("Brightfield angle configured: {} degrees", bfAngle);
        }

        // Always add NoRotationStrategy as fallback
        strategies.add(new NoRotationStrategy());

        logger.info("Initialized rotation strategies for modality: {}", modality);
    }

    /**
     * Gets the rotation angles required for the current modality.
     * @param modalityName The modality name
     * @return CompletableFuture with list of angles
     */
    public CompletableFuture<List<Double>> getRotationAngles(String modalityName) {
        logger.info("Getting rotation angles for modality: {}", modalityName);

        for (RotationStrategy strategy : strategies) {
            logger.debug("Checking strategy {} for modality {}",
                    strategy.getClass().getSimpleName(), modalityName);

            if (strategy.appliesTo(modalityName)) {
                logger.info("Using {} for modality {}",
                        strategy.getClass().getSimpleName(), modalityName);

                CompletableFuture<List<Double>> anglesFuture = strategy.getRotationAngles();

                // Add logging to see what angles are returned
                return anglesFuture.thenApply(angles -> {
                    logger.info("Strategy {} returned angles: {}",
                            strategy.getClass().getSimpleName(), angles);
                    return angles;
                });
            }
        }
        // Should never reach here due to NoRotationStrategy catch-all
        logger.warn("No rotation strategy found for modality: {}", modalityName);
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Gets the appropriate file suffix for a rotation angle.
     * @param modalityName The modality name
     * @param angle The rotation angle
     * @return Suffix string
     */
    public String getAngleSuffix(String modalityName, double angle) {
        for (RotationStrategy strategy : strategies) {
            if (strategy.appliesTo(modalityName)) {
                return strategy.getAngleSuffix(angle);
            }
        }
        return "";
    }
}