package qupath.ext.qpsc.model;

import qupath.ext.qpsc.model.RotationManager.TickExposure;
import qupath.ext.qpsc.ui.PPMAngleSelectionController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for handling stage rotation based on imaging modality.
 * Different modalities require different rotation approaches:
 * - BF modes: fixed 90° rotation
 * - PPM modes: multiple angles for polarization
 * - Other modes: no rotation needed
 */
public interface RotationStrategy {

    /**
     * Determines if this strategy applies to the given modality.
     * @param modalityName The imaging modality (e.g., "BF_10x", "PPM_20x")
     * @return true if this strategy should handle the modality
     */
    boolean appliesTo(String modalityName);

    /**
     * Gets the rotation angles required for this modality.
     * May show a dialog for user selection in PPM modes.
     * @return CompletableFuture with list of rotation angles in ticks
     */
    CompletableFuture<List<Double>> getRotationTicks();

    /**
     * Gets the rotation angles with exposure times for this modality.
     * @return CompletableFuture with list of TickExposure objects
     */
    CompletableFuture<List<TickExposure>> getRotationTicksWithExposure();

    /**
     * Gets a suffix to append to file/folder names for each angle.
     * @param angle The rotation angle in ticks
     * @return String suffix (e.g., "_p5", "_m5", "_90deg")
     */
    String getAngleSuffix(double angle);
}


/**
 * Implementation for Brightfield modalities - rotates to configured angle (default 90°)
 */
class BrightfieldRotationStrategy implements RotationStrategy {
    private final TickExposure tickExposure;

    public BrightfieldRotationStrategy() {
        this(new TickExposure(90.0, 10)); // default
    }

    public BrightfieldRotationStrategy(TickExposure tickExposure) {
        this.tickExposure = tickExposure;
    }

    @Override
    public boolean appliesTo(String modalityName) {
        return modalityName != null && modalityName.startsWith("BF_");
    }

    @Override
    public CompletableFuture<List<Double>> getRotationTicks() {
        // BF always uses the configured angle
        return CompletableFuture.completedFuture(List.of(tickExposure.ticks));
    }

    @Override
    public CompletableFuture<List<TickExposure>> getRotationTicksWithExposure() {
        return CompletableFuture.completedFuture(List.of(tickExposure));
    }

    @Override
    public String getAngleSuffix(double angle) {
        return ""; // No suffix needed for BF
    }
}

/**
 * Implementation for PPM (Polarized Light Microscopy) modalities
 */
class PPMRotationStrategy implements RotationStrategy {

    private final TickExposure plusAngleExposure;
    private final TickExposure minusAngleExposure;
    private final TickExposure zeroAngleExposure;

    public PPMRotationStrategy(TickExposure plusAngleExposure,
                               TickExposure minusAngleExposure,
                               TickExposure zeroAngleExposure) {
        this.plusAngleExposure = plusAngleExposure;
        this.minusAngleExposure = minusAngleExposure;
        this.zeroAngleExposure = zeroAngleExposure;
    }

    @Override
    public boolean appliesTo(String modalityName) {
        return modalityName != null && modalityName.startsWith("PPM_");
    }

    @Override
    public CompletableFuture<List<Double>> getRotationTicks() {
        // Show dialog for angle selection, then extract just the angles
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks, minusAngleExposure.ticks);
    }

    @Override
    public CompletableFuture<List<TickExposure>> getRotationTicksWithExposure() {
        // Show dialog for angle selection, then map to AngleExposure objects
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks, minusAngleExposure.ticks)
                .thenApply(selectedAngles -> {
                    List<TickExposure> result = new ArrayList<>();
                    for (Double angle : selectedAngles) {
                        if (angle == minusAngleExposure.ticks) {
                            result.add(minusAngleExposure);
                        } else if (angle == 0.0) {
                            result.add(zeroAngleExposure);
                        } else if (angle == plusAngleExposure.ticks) {
                            result.add(plusAngleExposure);
                        }
                    }
                    return result;
                });
    }

    @Override
    public String getAngleSuffix(double angle) {
        if (angle == 0) return "_0deg";
        if (angle > 0) return "_p" + (int)angle;
        return "_m" + (int)Math.abs(angle);
    }
}

/**
 * Default strategy for modalities that don't need rotation
 */
class NoRotationStrategy implements RotationStrategy {

    @Override
    public boolean appliesTo(String modalityName) {
        return true; // Catch-all
    }

    @Override
    public CompletableFuture<List<Double>> getRotationTicks() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<TickExposure>> getRotationTicksWithExposure() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public String getAngleSuffix(double angle) {
        return "";
    }
}