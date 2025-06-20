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
        // Try to read PPM angles from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        // Check for new structured format first
        Map<String, Object> ppmPlus = mgr.getSection("imagingMode", modality, "ppm_plus");
        Map<String, Object> ppmMinus = mgr.getSection("imagingMode", modality, "ppm_minus");

        Double plusAngle = null;
        Double minusAngle = null;

        if (ppmPlus != null && ppmPlus.containsKey("degrees")) {
            plusAngle = ((Number) ppmPlus.get("degrees")).doubleValue();
        } else {
            // Fall back to old format
            plusAngle = mgr.getDouble("imagingMode", modality, "ppm_plus");
        }

        if (ppmMinus != null && ppmMinus.containsKey("degrees")) {
            minusAngle = ((Number) ppmMinus.get("degrees")).doubleValue();
        } else {
            // Fall back to old format
            minusAngle = mgr.getDouble("imagingMode", modality, "ppm_minus");
        }

        // Add strategies in priority order
        if (plusAngle != null && minusAngle != null) {
            strategies.add(new PPMRotationStrategy(plusAngle, minusAngle));
            logger.info("PPM angles configured: {} to {} degrees", minusAngle, plusAngle);
        }
        strategies.add(new BrightfieldRotationStrategy());
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