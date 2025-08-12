package qupath.ext.qpsc.modalities.ppm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.RotationManager;
import qupath.ext.qpsc.modalities.ppm.ui.PPMAngleSelectionController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * PPM-specific rotation handling.
 * Handles multiple polarization angles including optional brightfield at 90°.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMRotationStrategy {
    private static final Logger logger = LoggerFactory.getLogger(PPMRotationStrategy.class);

    private final RotationManager.TickExposure plusAngleExposure;
    private final RotationManager.TickExposure minusAngleExposure;
    private final RotationManager.TickExposure zeroAngleExposure;

    /**
     * Creates a PPM rotation strategy with the standard angles.
     *
     * @param plusAngleExposure Configuration for positive angle
     * @param minusAngleExposure Configuration for negative angle
     * @param zeroAngleExposure Configuration for zero angle
     */
    public PPMRotationStrategy(RotationManager.TickExposure plusAngleExposure,
                               RotationManager.TickExposure minusAngleExposure,
                               RotationManager.TickExposure zeroAngleExposure) {
        this.plusAngleExposure = plusAngleExposure;
        this.minusAngleExposure = minusAngleExposure;
        this.zeroAngleExposure = zeroAngleExposure;

        logger.info("PPM rotation strategy initialized with angles: minus={}, zero={}, plus={}",
                minusAngleExposure.ticks, zeroAngleExposure.ticks, plusAngleExposure.ticks);
    }

    /**
     * Gets the rotation angles by showing a selection dialog.
     *
     * @return CompletableFuture with list of angles in ticks
     */
    public CompletableFuture<List<Double>> getRotationTicks() {
        // Show dialog for angle selection
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks, minusAngleExposure.ticks)
                .thenApply(result -> {
                    if (result == null) {
                        return new ArrayList<>();
                    }
                    return result.getAngles();
                });
    }

    /**
     * Gets the rotation angles with exposure times by showing a selection dialog.
     *
     * @return CompletableFuture with list of TickExposure objects
     */
    public CompletableFuture<List<RotationManager.TickExposure>> getRotationTicksWithExposure() {
        // Show dialog for angle selection with exposure times
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks, minusAngleExposure.ticks)
                .thenApply(result -> {
                    if (result == null) {
                        return new ArrayList<>();
                    }

                    List<RotationManager.TickExposure> tickExposures = new ArrayList<>();
                    for (PPMAngleSelectionController.AngleExposure ae : result.angleExposures) {
                        tickExposures.add(new RotationManager.TickExposure(ae.angle, ae.exposureMs));
                    }

                    logger.info("User selected {} angles for PPM acquisition", tickExposures.size());
                    return tickExposures;
                });
    }
}