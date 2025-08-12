package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modalities.common.ModalityHandler;
import qupath.ext.qpsc.modalities.common.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Utility class for managing rotation requirements for different imaging modalities.
 * Delegates to the modality architecture for cleaner separation.
 *
 * @version 4.0
 */
public class RotationManager {
    private static final Logger logger = LoggerFactory.getLogger(RotationManager.class);

    private final String modality;
    private final ModalityHandler modalityHandler;
    private final MicroscopeConfigManager configManager;

    /**
     * Container for angle and exposure time data.
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
        this.modality = modality;
        this.configManager = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        // Get the appropriate handler from the registry
        ModalityRegistry registry = ModalityRegistry.getInstance();
        this.modalityHandler = registry.getHandler(modality);

        logger.info("Initialized RotationManager for modality: {} using handler: {}",
                modality, modalityHandler.getClass().getSimpleName());
    }

    /**
     * Gets the rotation angles required for the current modality.
     * @param modalityName The modality name
     * @return CompletableFuture with list of angles (ticks)
     */
    public CompletableFuture<List<Double>> getRotationTicks(String modalityName) {
        logger.info("Getting rotation angles for modality: {}", modalityName);

        if (!modalityHandler.requiresRotation()) {
            logger.info("Modality {} does not require rotation", modalityName);
            return CompletableFuture.completedFuture(List.of());
        }

        // Get rotation angles from the handler
        return modalityHandler.getRotationAngles(modalityName, configManager)
                .thenApply(tickExposures -> {
                    List<Double> angles = tickExposures.stream()
                            .map(te -> te.ticks)
                            .toList();
                    logger.info("Modality {} returned angles: {}", modalityName, angles);
                    return angles;
                });
    }

    /**
     * Gets the rotation angles with exposure times for the current modality.
     * @param modalityName The modality name
     * @return CompletableFuture with list of TickExposure objects
     */
    public CompletableFuture<List<TickExposure>> getRotationTicksWithExposure(String modalityName) {
        logger.info("Getting rotation angles with exposure for modality: {}", modalityName);

        if (!modalityHandler.requiresRotation()) {
            logger.info("Modality {} does not require rotation", modalityName);
            return CompletableFuture.completedFuture(List.of());
        }

        return modalityHandler.getRotationAngles(modalityName, configManager);
    }

    /**
     * Gets the appropriate file suffix for a rotation angle.
     * @param modalityName The modality name
     * @param tick The rotation angle
     * @return Suffix string
     */
    public String getAngleSuffix(String modalityName, double tick) {
        // Most modalities don't use angle suffixes anymore
        // Each angle is saved as a separate file
        return "";
    }

    /**
     * Checks if the current modality requires rotation.
     * @return true if rotation is required
     */
    public boolean requiresRotation() {
        return modalityHandler.requiresRotation();
    }
}