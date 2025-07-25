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
 * Reads PPM angles in ticks (double the angle) from config and applies appropriate strategy.
 */
public class RotationManager {
    private static final Logger logger = LoggerFactory.getLogger(RotationManager.class);

    private final List<RotationStrategy> strategies = new ArrayList<>();

    /**
     * Container for angle and exposure time data
     */
    public static class TickExposure {
        public final double ticks;
        public final int exposureMs;

        public TickExposure(double ticks, int exposureMs) {
            this.ticks = ticks;
            this.exposureMs = exposureMs;
        }

        @Override
        public String toString() {
            return ticks + "," + exposureMs;
        }
    }

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

            Double plusTick = null;
            Double minusTick = null;
            Double zeroTick = null;
            Integer plusExposure = null;
            Integer minusExposure = null;
            Integer zeroExposure = null;

            // Read from global PPM section
            Map<String, Object> ppmPlus = (Map<String, Object>) ppmConfig.get("ppm_plus");
            Map<String, Object> ppmMinus = (Map<String, Object>) ppmConfig.get("ppm_minus");
            Map<String, Object> ppmZero = (Map<String, Object>) ppmConfig.get("ppm_zero");

            if (ppmPlus != null) {
                if (ppmPlus.containsKey("tick")) {
                    plusTick = ((Number) ppmPlus.get("tick")).doubleValue();
                }
                if (ppmPlus.containsKey("exposure_ms")) {
                    plusExposure = ((Number) ppmPlus.get("exposure_ms")).intValue();
                }
            }

            if (ppmMinus != null) {
                if (ppmMinus.containsKey("tick")) {
                    minusTick = ((Number) ppmMinus.get("tick")).doubleValue();
                }
                if (ppmMinus.containsKey("exposure_ms")) {
                    minusExposure = ((Number) ppmMinus.get("exposure_ms")).intValue();
                }
            }

            if (ppmZero != null) {
                if (ppmZero.containsKey("tick")) {
                    zeroTick = ((Number) ppmZero.get("tick")).doubleValue();
                }
                if (ppmZero.containsKey("exposure_ms")) {
                    zeroExposure = ((Number) ppmZero.get("exposure_ms")).intValue();
                }
            }

            // Use defaults if not configured
            if (plusTick == null || minusTick == null) {
                logger.warn("PPM ticks not configured in YAML. Using defaults (+5, -5).");
                plusTick = 5.0;
                minusTick = -5.0;
            }
            if (zeroTick == null) {
                zeroTick = 0.0;
            }

            // Default exposures if not specified
            if (plusExposure == null) plusExposure = 500;
            if (minusExposure == null) minusExposure = 500;
            if (zeroExposure == null) zeroExposure = 800;

            strategies.add(new PPMRotationStrategy(
                    new TickExposure(plusTick, plusExposure),
                    new TickExposure(minusTick, minusExposure),
                    new TickExposure(zeroTick, zeroExposure)
            ));
            logger.info("PPM angles configured: {} to {} ticks with exposures", minusTick, plusTick);
        }

        // Check if this is a BF modality
        boolean isBFModality = modality != null && modality.startsWith("BF_");

        if (isBFModality) {
            // Get brightfield tick and exposure from PPM config
            Map<String, Object> ppmConfig = mgr.getPPMConfig();
            Map<String, Object> bfConfig = (Map<String, Object>) ppmConfig.get("brightfield");

            Double bfTick = 90.0; // default
            Integer bfExposure = 10; // default

            if (bfConfig != null) {
                if (bfConfig.containsKey("tick")) {
                    bfTick = ((Number) bfConfig.get("tick")).doubleValue();
                }
                if (bfConfig.containsKey("exposure_ms")) {
                    bfExposure = ((Number) bfConfig.get("exposure_ms")).intValue();
                }
            }

            strategies.add(new BrightfieldRotationStrategy(new TickExposure(bfTick, bfExposure)));
            logger.info("Brightfield tick configured: {} ticks with {} ms exposure", bfTick, bfExposure);
        }

        // Always add NoRotationStrategy as fallback
        strategies.add(new NoRotationStrategy());

        logger.info("Initialized rotation strategies for modality: {}", modality);
    }

    /**
     * Gets the rotation angles required for the current modality.
     * @param modalityName The modality name
     * @return CompletableFuture with list of angles (ticks)
     */
    public CompletableFuture<List<Double>> getRotationTicks(String modalityName) {
        logger.info("Getting rotation angles for modality: {}", modalityName);

        for (RotationStrategy strategy : strategies) {
            logger.debug("Checking strategy {} for modality {}",
                    strategy.getClass().getSimpleName(), modalityName);

            if (strategy.appliesTo(modalityName)) {
                logger.info("Using {} for modality {}",
                        strategy.getClass().getSimpleName(), modalityName);

                CompletableFuture<List<Double>> anglesFuture = strategy.getRotationTicks();

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
     * Gets the rotation angles with exposure times for the current modality.
     * @param modalityName The modality name
     * @return CompletableFuture with list of AngleExposure objects
     */
    public CompletableFuture<List<TickExposure>> getRotationTicksWithExposure(String modalityName) {
        logger.info("Getting rotation angles with exposure for modality: {}", modalityName);

        for (RotationStrategy strategy : strategies) {
            if (strategy.appliesTo(modalityName)) {
                logger.info("Using {} for modality {}",
                        strategy.getClass().getSimpleName(), modalityName);
                return strategy.getRotationTicksWithExposure();
            }
        }
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Gets the appropriate file suffix for a rotation angle.
     * @param modalityName The modality name
     * @param angle The rotation angle
     * @return Suffix string
     */
    public String getTickSuffix(String modalityName, double angle) {
        for (RotationStrategy strategy : strategies) {
            if (strategy.appliesTo(modalityName)) {
                return strategy.getAngleSuffix(angle);
            }
        }
        return "";
    }
}