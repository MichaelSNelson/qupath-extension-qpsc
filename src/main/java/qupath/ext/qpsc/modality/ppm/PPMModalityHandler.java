package qupath.ext.qpsc.modality.ppm;

import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Modality handler for Polarized light Microscopy (PPM) multi-angle acquisition sequences.
 * 
 * <p>This handler manages PPM imaging workflows that require multiple polarizer rotation
 * angles with specific exposure times for each angle. PPM is commonly used for analyzing
 * birefringent materials, collagen organization, and other optically anisotropic structures.</p>
 * 
 * <p>The handler supports decimal exposure times for precise control of illumination duration
 * at each polarizer angle. Typical PPM sequences include crossed polarizers (0°), positive
 * and negative angles (±5°), and uncrossed polarizers (45°) with different exposure requirements.</p>
 * 
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Automatic angle sequence loading from microscope configuration</li>
 *   <li>Decimal precision exposure times (e.g., 1.2ms, 500.0ms, 0.8ms)</li>
 *   <li>User-customizable angle overrides via {@link PPMBoundingBoxUI}</li>
 *   <li>Integration with {@link RotationManager} for hardware-specific angle conversion</li>
 * </ul>
 * 
 * <p><strong>Angle Override Support:</strong><br>
 * Users can override the default plus and minus angles while preserving the original
 * exposure times. This is useful for optimizing imaging conditions for different samples.</p>
 * 
 * @author Mike Nelson
 * @since 1.0
 * @see qupath.ext.qpsc.modality.ModalityHandler
 * @see qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI
 * @see RotationManager
 */
public class PPMModalityHandler implements ModalityHandler {
    /**
     * Retrieves PPM rotation angles and their associated decimal exposure times.
     * 
     * <p>This method loads the PPM angle sequence from the microscope configuration
     * via {@link RotationManager}. The returned angles include both the hardware tick
     * values and precise decimal exposure times for each polarizer position.</p>
     * 
     * @param modalityName the PPM modality identifier (e.g., "ppm_20x", "ppm_40x")
     * @param objective the objective ID for hardware-specific parameter lookup
     * @param detector the detector ID for hardware-specific parameter lookup
     * @return a future containing the angle-exposure pairs for this PPM configuration
     */
    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(String modalityName, String objective, String detector) {
        RotationManager rotationManager = new RotationManager(modalityName, objective, detector);
        return rotationManager.getRotationTicksWithExposure(modalityName);
    }

    /**
     * Creates the PPM-specific UI component for angle parameter customization.
     * 
     * <p>Returns a {@link PPMBoundingBoxUI} that allows users to override the default
     * plus and minus angles while preserving the original exposure times. This enables
     * fine-tuning of polarization angles for different sample types.</p>
     * 
     * @return a PPM bounding box UI component for angle override controls
     */
    @Override
    public java.util.Optional<BoundingBoxUI> createBoundingBoxUI() {
        return java.util.Optional.of(new PPMBoundingBoxUI());
    }

    /**
     * Applies user-specified angle overrides while preserving original decimal exposure times.
     * 
     * <p>This method enables users to customize the plus and minus polarizer angles while
     * keeping the configured exposure times unchanged. The override map uses "plus" and "minus"
     * keys to identify which angles to replace.</p>
     * 
     * <p>Only positive and negative tick angles are subject to override; zero-degree (crossed)
     * and uncrossed angles retain their original values.</p>
     * 
     * @param angles the original PPM angle-exposure sequence with decimal exposure times
     * @param overrides map containing "plus" and/or "minus" angle replacements
     * @return new angle sequence with overrides applied, preserving original exposures
     */
    @Override
    public List<AngleExposure> applyAngleOverrides(List<AngleExposure> angles,
                                                   Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return angles;
        }

        List<AngleExposure> adjusted = new ArrayList<>();
        for (AngleExposure ae : angles) {
            if (ae.ticks() < 0 && overrides.containsKey("minus")) {
                adjusted.add(new AngleExposure(overrides.get("minus"), ae.exposureMs()));
            } else if (ae.ticks() > 0 && overrides.containsKey("plus")) {
                adjusted.add(new AngleExposure(overrides.get("plus"), ae.exposureMs()));
            } else {
                adjusted.add(ae);
            }
        }
        return adjusted;
    }
}
