package qupath.ext.qpsc.modality.ppm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
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
            // Read rotation angles from modality configuration
            List<?> anglesList = mgr.getList("modalities", modality, "rotation_angles");


            double plusTick = 5.0;
            double minusTick = -5.0;
            double zeroTick = 0.0;


            if (anglesList != null) {
                for (Object angleObj : anglesList) {
                    if (angleObj instanceof Map<?, ?> angle) {
                        Object name = angle.get("name");
                        Object tickObj = angle.get("tick");
                        if (name != null && tickObj instanceof Number) {
                            double tick = ((Number) tickObj).doubleValue();
                            switch (name.toString()) {
                                case "positive" -> plusTick = tick;
                                case "negative" -> minusTick = tick;
                                case "crossed" -> zeroTick = tick;
                            }
                        }
                    }
                }
            } else {
                logger.warn("No rotation angles found for modality {} - using defaults", modality);
            }

            // Get exposure times from PPMPreferences
            int plusExposure = PPMPreferences.getPlusExposureMs();
            int minusExposure = PPMPreferences.getMinusExposureMs();
            int zeroExposure = PPMPreferences.getZeroExposureMs();

            strategies.add(new PPMRotationStrategy(
                    new AngleExposure(plusTick, plusExposure),
                    new AngleExposure(minusTick, minusExposure),
                    new AngleExposure(zeroTick, zeroExposure)
            ));

            logger.info("PPM ticks configured");
        }

        // Always add NoRotationStrategy as fallback for non-PPM modalities
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
    public CompletableFuture<List<AngleExposure>> getRotationTicksWithExposure(String modalityName) {
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