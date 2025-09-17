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
     * Creates a RotationManager configured for the given modality with hardware parameters.
     * @param modality The imaging modality name
     * @param objective The objective ID for priority exposure lookup
     * @param detector The detector ID for priority exposure lookup
     */
    public RotationManager(String modality, String objective, String detector) {
        initializeStrategies(modality, modality, objective, detector);
    }

    private void initializeStrategies(String modality, String modalityForExposure, String objective, String detector) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        // Check if this is a PPM modality by name
        boolean isPPMModality = modality != null && modality.startsWith("ppm");

        if (isPPMModality) {
            // Read rotation angles from modality configuration
            List<?> anglesList = mgr.getList("modalities", modality, "rotation_angles");

            double plusTick = 5.0;
            double minusTick = -5.0;
            double zeroTick = 0.0;
            double uncrossedTick = 90.0;

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
                                case "uncrossed" -> uncrossedTick = tick;
                            }
                        }
                    }
                }
            } else {
                logger.warn("No rotation angles found for modality {} - using defaults", modality);
            }

            // Get exposure times using priority order - no longer hardcoded from PPMPreferences
            // These will be determined by the PPMAngleSelectionController using the proper priority
            double plusExposure = PPMPreferences.getPlusExposureMs();
            double minusExposure = PPMPreferences.getMinusExposureMs();
            double zeroExposure = PPMPreferences.getZeroExposureMs();
            double uncrossedExposure = PPMPreferences.getUncrossedExposureMs();

            strategies.add(new PPMRotationStrategy(
                    new AngleExposure(plusTick, plusExposure),
                    new AngleExposure(minusTick, minusExposure),
                    new AngleExposure(zeroTick, zeroExposure),
                    new AngleExposure(uncrossedTick, uncrossedExposure),
                    modalityForExposure,
                    objective,
                    detector
            ));

            logger.info("PPM ticks configured with hardware parameters: modality={}, objective={}, detector={}", 
                    modalityForExposure, objective, detector);
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