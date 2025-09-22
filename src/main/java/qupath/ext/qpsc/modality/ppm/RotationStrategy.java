package qupath.ext.qpsc.modality.ppm;

import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for handling stage rotation based on imaging modality.
 * PPM modalities may require multiple angles while other modalities
 * typically perform no rotation.
 */
public interface RotationStrategy {

    /**
     * Determines if this strategy applies to the given modality.
     * @param modalityName The imaging modality (e.g., "bf_10x", "ppm_20x")
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
     * @return CompletableFuture with list of AngleExposure objects
     */
    CompletableFuture<List<AngleExposure>> getRotationTicksWithExposure();

    /**
     * Gets a suffix to append to file/folder names for each angle.
     * @param angle The rotation angle in ticks
     * @return String suffix (e.g., "_p5", "_m5", "_90deg")
     */
    String getAngleSuffix(double angle);
}


/**
 * Implementation for PPM (Polarized Light Microscopy) modalities
 */
class PPMRotationStrategy implements RotationStrategy {

    private final AngleExposure plusAngleExposure;
    private final AngleExposure minusAngleExposure;
    private final AngleExposure zeroAngleExposure;
    private final AngleExposure uncrossedAngleExposure;
    private final String modality;
    private final String objective;
    private final String detector;

    public PPMRotationStrategy(AngleExposure plusAngleExposure,
                               AngleExposure minusAngleExposure,
                               AngleExposure zeroAngleExposure,
                               AngleExposure uncrossedAngleExposure,
                               String modality,
                               String objective,
                               String detector) {
        this.plusAngleExposure = plusAngleExposure;
        this.minusAngleExposure = minusAngleExposure;
        this.zeroAngleExposure = zeroAngleExposure;
        this.uncrossedAngleExposure = uncrossedAngleExposure;
        this.modality = modality;
        this.objective = objective;
        this.detector = detector;
    }

    @Override
    public boolean appliesTo(String modalityName) {
        return modalityName != null && modalityName.startsWith("ppm");
    }

    @Override
    public CompletableFuture<List<Double>> getRotationTicks() {
        // Show dialog for angle selection with exposure times
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks(), minusAngleExposure.ticks(), uncrossedAngleExposure.ticks(), 
                modality, objective, detector)
                .thenApply(result -> {
                    if (result == null) {
                        return new ArrayList<>();
                    }
                    return result.getAngles();
                });
    }

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationTicksWithExposure() {
        // Show dialog for angle selection with exposure times
        return PPMAngleSelectionController.showDialog(plusAngleExposure.ticks(), minusAngleExposure.ticks(), uncrossedAngleExposure.ticks(), 
                modality, objective, detector)
                .thenApply(result -> {
                    if (result == null) {
                        return new ArrayList<>();
                    }

                    List<AngleExposure> tickExposures = new ArrayList<>();
                    for (PPMAngleSelectionController.AngleExposure ae : result.angleExposures) {
                        tickExposures.add(new AngleExposure(ae.angle, ae.exposureMs));
                    }
                    return tickExposures;
                });
    }

    @Override
    public String getAngleSuffix(double angle) {
        return "";
    }

    /**
     * Gets the configured angles without showing any dialog.
     * Used for applying overrides before showing the user dialog.
     * @return List of default AngleExposure objects from configuration
     */
    public List<AngleExposure> getConfiguredAngles() {
        List<AngleExposure> configuredAngles = new ArrayList<>();
        configuredAngles.add(plusAngleExposure);
        configuredAngles.add(minusAngleExposure);
        configuredAngles.add(zeroAngleExposure);
        configuredAngles.add(uncrossedAngleExposure);
        return configuredAngles;
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
    public CompletableFuture<List<AngleExposure>> getRotationTicksWithExposure() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public String getAngleSuffix(double angle) {
        return "";
    }
}