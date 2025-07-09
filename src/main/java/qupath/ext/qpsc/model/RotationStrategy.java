package qupath.ext.qpsc.model;

import qupath.ext.qpsc.ui.PPMAngleSelectionController;

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
     * @return CompletableFuture with list of rotation angles in degrees
     */
    CompletableFuture<List<Double>> getRotationAngles();

    /**
     * Gets a suffix to append to file/folder names for each angle.
     * @param angle The rotation angle
     * @return String suffix (e.g., "_p5", "_m5", "_90deg")
     */
    String getAngleSuffix(double angle);
}


/**
 * Implementation for Brightfield modalities - rotates to configured angle (default 90°)
 */
class BrightfieldRotationStrategy implements RotationStrategy {
    private final double rotationAngle;

    public BrightfieldRotationStrategy() {
        this(90.0); // default
    }

    public BrightfieldRotationStrategy(double angle) {
        this.rotationAngle = angle;
    }

    @Override
    public boolean appliesTo(String modalityName) {
        return modalityName != null && modalityName.startsWith("BF_");
    }

    @Override
    public CompletableFuture<List<Double>> getRotationAngles() {
        // BF always uses the configured angle
        return CompletableFuture.completedFuture(List.of(rotationAngle));
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

    private final double plusAngle;
    private final double minusAngle;

    public PPMRotationStrategy(double plusAngle, double minusAngle) {
        this.plusAngle = plusAngle;
        this.minusAngle = minusAngle;
    }

    @Override
    public boolean appliesTo(String modalityName) {
        return modalityName != null && modalityName.startsWith("PPM_");
    }

    @Override
    public CompletableFuture<List<Double>> getRotationAngles() {
        // Show dialog for angle selection
        return PPMAngleSelectionController.showDialog(plusAngle, minusAngle);
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
    public CompletableFuture<List<Double>> getRotationAngles() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public String getAngleSuffix(double angle) {
        return "";
    }
}