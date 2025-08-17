package qupath.ext.qpsc.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
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
        boolean isPPMModality = modality != null && modality.startsWith("ppm_");

        if (isPPMModality) {
            // Get PPM config from global section
            Map<String, Object> ppmConfig = mgr.getPPMConfig();

            Double plusTick = null;
            Double minusTick = null;
            Double zeroTick = null;

            // Read from global PPM section
            Map<String, Object> ppmPlus = (Map<String, Object>) ppmConfig.get("ppm_plus");
            Map<String, Object> ppmMinus = (Map<String, Object>) ppmConfig.get("ppm_minus");
            Map<String, Object> ppmZero = (Map<String, Object>) ppmConfig.get("ppm_zero");

            if (ppmPlus != null) {
                if (ppmPlus.containsKey("tick")) {
                    plusTick = ((Number) ppmPlus.get("tick")).doubleValue();
                }
            }

            if (ppmMinus != null) {
                if (ppmMinus.containsKey("tick")) {
                    minusTick = ((Number) ppmMinus.get("tick")).doubleValue();
                }

            }

            if (ppmZero != null) {
                if (ppmZero.containsKey("tick")) {
                    zeroTick = ((Number) ppmZero.get("tick")).doubleValue();
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

            // Get exposure times from PersistentPreferences
            int plusExposure = PersistentPreferences.getPPMPlusExposureMs();
            int minusExposure = PersistentPreferences.getPPMMinusExposureMs();
            int zeroExposure = PersistentPreferences.getPPMZeroExposureMs();

            strategies.add(new PPMRotationStrategy(
                    new TickExposure(plusTick, plusExposure),
                    new TickExposure(minusTick, minusExposure),
                    new TickExposure(zeroTick, zeroExposure)
            ));

            logger.info("PPM ticks configured");
        }

        // Check if this is a BF modality
        boolean isBFModality = modality != null && modality.startsWith("BF_");

        if (isBFModality) {
            // Get brightfield tick and exposure from PPM config
            Map<String, Object> ppmConfig = mgr.getPPMConfig();
            Map<String, Object> bfConfig = (Map<String, Object>) ppmConfig.get("brightfield");
            //TODO these should be set in the controller dialog
            Double bfTick = 90.0; // default

            if (bfConfig != null && bfConfig.containsKey("tick")) {
                bfTick = ((Number) bfConfig.get("tick")).doubleValue();
            }

            // Get exposure from PersistentPreferences
            int bfExposure = PersistentPreferences.getPPMBrightfieldExposureMs();

            strategies.add(new BrightfieldRotationStrategy(new TickExposure(bfTick, bfExposure)));
            logger.info("Brightfield tick configured: {} degrees with {} ms exposure from preferences", bfTick, bfExposure);
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
     * @param tick The rotation angle
     * @return Suffix string
     */
    public String getAngleSuffix(String modalityName, double tick) {
        for (RotationStrategy strategy : strategies) {
            if (strategy.appliesTo(modalityName)) {
                return strategy.getAngleSuffix(tick);
            }
        }
        return "";
    }
}