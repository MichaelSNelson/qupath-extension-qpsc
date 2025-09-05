package qupath.ext.qpsc.modality;

/**
 * Immutable data container that pairs a rotation angle with its associated exposure time for
 * modality-based microscopy acquisitions.
 * 
 * <p>This record is a core component of the QPSC modality system, used to define rotation
 * sequences for polarized light microscopy and other angle-dependent imaging techniques.
 * Each {@code AngleExposure} represents a single acquisition step in a multi-angle sequence,
 * containing both the rotation position and the camera exposure duration for that position.</p>
 * 
 * <p><strong>Usage in Acquisition Workflows:</strong></p>
 * <ul>
 *   <li>Returned by {@link ModalityHandler#getRotationAngles(String)} to define acquisition sequences</li>
 *   <li>Used by {@link qupath.ext.qpsc.service.AcquisitionCommandBuilder} to generate Pycro-Manager commands</li>
 *   <li>Processed by modality-specific handlers like {@link qupath.ext.qpsc.modality.ppm.PPMModalityHandler}</li>
 *   <li>Modified through {@link ModalityHandler#applyAngleOverrides(java.util.List, java.util.Map)} for runtime customization</li>
 * </ul>
 * 
 * <p><strong>Coordinate System:</strong><br>
 * The {@code ticks} parameter represents rotation angles in hardware-specific tick units.
 * For PPM (Polarized light microscopy) systems, typical conversions are:
 * <ul>
 *   <li>1 tick = 2 degrees (Thorlabs rotation stages)</li>
 *   <li>Common values: -5 ticks (negative), 0 ticks (crossed), +5 ticks (positive), 45 ticks (uncrossed)</li>
 *   <li>Tick values are passed directly to microscope control software without conversion</li>
 * </ul>
 * 
 * <p><strong>Exposure Time Constraints:</strong><br>
 * The {@code exposureMs} parameter must be a positive integer representing milliseconds:
 * <ul>
 *   <li>Typical range: 10ms - 5000ms depending on illumination and detector sensitivity</li>
 *   <li>PPM sequences often use different exposures per angle (e.g., 500ms for polarized, 10ms for uncrossed)</li>
 *   <li>Values are validated by the microscope control system before acquisition</li>
 * </ul>
 * 
 * <p><strong>Command Building Integration:</strong><br>
 * When building Pycro-Manager acquisition commands, {@code AngleExposure} lists are processed as:
 * <pre>{@code
 * --angles (-5,0,5) --exposures (500,800,500)
 * }</pre>
 * The {@link #toString()} method provides a comma-separated format for debugging and logging.</p>
 * 
 * <p><strong>Thread Safety:</strong><br>
 * As a record, this class is immutable and inherently thread-safe. All instances can be safely
 * shared across threads without synchronization. Collections of {@code AngleExposure} objects
 * should use immutable list implementations when passed between acquisition components.</p>
 * 
 * <p><strong>Examples:</strong></p>
 * <pre>{@code
 * // PPM three-angle sequence
 * List<AngleExposure> ppmSequence = List.of(
 *     new AngleExposure(-5.0, 500),  // Negative polarizer angle
 *     new AngleExposure(0.0, 800),   // Crossed polarizers
 *     new AngleExposure(5.0, 500)    // Positive polarizer angle
 * );
 * 
 * // Single brightfield acquisition
 * List<AngleExposure> brightfield = List.of(
 *     new AngleExposure(0.0, 50)     // No rotation, short exposure
 * );
 * 
 * // Runtime angle override (maintaining original exposures)
 * List<AngleExposure> modified = modalityHandler.applyAngleOverrides(
 *     original, Map.of("plus", 7.5, "minus", -7.5));
 * }</pre>
 * 
 * @param ticks the rotation angle in hardware-specific tick units. Must be finite.
 *              Negative values represent counter-clockwise rotation, positive values represent 
 *              clockwise rotation. Zero represents the reference/home position.
 * @param exposureMs the camera exposure time in milliseconds. Must be positive (> 0).
 *                   Typical range is 10-5000ms depending on illumination conditions and 
 *                   detector sensitivity.
 * 
 * @author Mike Nelson
 * @since 1.0
 * @see ModalityHandler#getRotationAngles(String)
 * @see qupath.ext.qpsc.service.AcquisitionCommandBuilder#angleExposures(java.util.List)
 * @see qupath.ext.qpsc.modality.ppm.PPMModalityHandler
 * @see qupath.ext.qpsc.modality.ModalityRegistry
 */
public record AngleExposure(double ticks, int exposureMs) {
    
    /**
     * Creates an {@code AngleExposure} with validation of input parameters.
     * 
     * @param ticks the rotation angle in tick units
     * @param exposureMs the exposure time in milliseconds
     * @throws IllegalArgumentException if {@code exposureMs} is not positive or if {@code ticks} is not finite
     */
    public AngleExposure {
        if (exposureMs <= 0) {
            throw new IllegalArgumentException("Exposure time must be positive, got: " + exposureMs + "ms");
        }
        if (!Double.isFinite(ticks)) {
            throw new IllegalArgumentException("Ticks must be finite, got: " + ticks);
        }
    }
    
    /**
     * Returns a comma-separated string representation for debugging and logging purposes.
     * 
     * <p>The format is: {@code "ticks,exposureMs"} (e.g., {@code "-5.0,500"})</p>
     * 
     * <p>This format is primarily used for:
     * <ul>
     *   <li>Debug logging during acquisition setup</li>
     *   <li>Error messages and troubleshooting</li>
     *   <li>Configuration file validation output</li>
     * </ul>
     * 
     * Note: This is NOT the format used for Pycro-Manager command generation.
     * Command building uses separate --angles and --exposures parameters.</p>
     * 
     * @return comma-separated string representation of this angle-exposure pair
     */
    @Override
    public String toString() {
        return ticks + "," + exposureMs;
    }
}
