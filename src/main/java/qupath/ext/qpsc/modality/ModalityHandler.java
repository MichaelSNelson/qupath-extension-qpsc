package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;

/**
 * Core extensibility interface for the QPSC modality plugin system.
 * 
 * <p>The ModalityHandler interface defines the contract for modality-specific acquisition 
 * parameters and behavior in the QuPath Scope Controller (QPSC) extension. This interface 
 * enables support for different imaging modalities (e.g., polarized light microscopy, 
 * fluorescence, brightfield) through a pluggable architecture.</p>
 * 
 * <h3>Plugin Architecture Overview</h3>
 * <p>The modality system uses a prefix-based registration mechanism where handlers register 
 * with {@link ModalityRegistry} using a prefix string. Modality names from configuration 
 * files are matched against these prefixes to resolve the appropriate handler. For example, 
 * a handler registered with prefix "ppm" will handle modalities like "ppm_20x", "ppm_40x", etc.</p>
 * 
 * <h3>Core Responsibilities</h3>
 * <ul>
 *   <li><strong>Rotation Parameters:</strong> Define rotation angles and decimal exposure times for multi-angle acquisitions</li>
 *   <li><strong>UI Integration:</strong> Provide optional JavaFX UI components for modality-specific parameters</li>
 *   <li><strong>Parameter Customization:</strong> Apply user overrides to default acquisition parameters</li>
 *   <li><strong>File Naming:</strong> Generate filename-safe suffixes for different rotation angles</li>
 * </ul>
 * 
 * <h3>Implementation Guidelines</h3>
 * <p>Implementations should be stateless and thread-safe as they may be accessed concurrently 
 * during acquisition workflows. All methods should handle null inputs gracefully and return 
 * sensible defaults when appropriate.</p>
 * 
 * <h3>Registration Example</h3>
 * <pre>{@code
 * // Register a new modality handler
 * public class MyModalityHandler implements ModalityHandler {
 *     // Implementation details...
 * }
 * 
 * // In static initializer or extension setup
 * ModalityRegistry.registerHandler("mymodality", new MyModalityHandler());
 * 
 * // Now modality names like "mymodality_20x" will use this handler
 * }</pre>
 * 
 * <h3>UI Integration Pattern</h3>
 * <p>Modality handlers can provide custom UI components that integrate with the bounding box 
 * dialog. These components allow users to override default parameters:</p>
 * 
 * <pre>{@code
 * @Override
 * public Optional<BoundingBoxUI> createBoundingBoxUI() {
 *     return Optional.of(new MyModalityBoundingBoxUI());
 * }
 * }</pre>
 * 
 * @author Mike Nelson
 * @since 1.0
 * @see ModalityRegistry
 * @see AngleExposure
 * @see qupath.ext.qpsc.modality.ppm.PPMModalityHandler
 */
public interface ModalityHandler {
    /**
     * Retrieves the rotation angles and decimal exposure times required for this modality's acquisition sequence.
     * 
     * <p>This method defines the core acquisition parameters for the modality, typically loaded from 
     * configuration files or computed dynamically. For multi-angle modalities like polarized light 
     * microscopy, this would return multiple angles with their associated decimal exposure times. 
     * Single-angle modalities may return a single entry or an empty list for default behavior.</p>
     * 
     * <p>The returned {@code CompletableFuture} allows for asynchronous parameter loading, which is 
     * useful when angles are computed from configuration files or external resources. Implementations 
     * should complete the future promptly to avoid blocking the acquisition workflow.</p>
     * 
     * @param modalityName the full modality identifier from the configuration (e.g., "ppm_20x", "bf_40x").
     *                     Must not be null. Used to lookup modality-specific parameters like objective 
     *                     magnification and angle sets
     * @param objective the objective ID for hardware-specific parameter lookup (may be null)
     * @param detector the detector ID for hardware-specific parameter lookup (may be null)
     * @return a {@code CompletableFuture} containing an immutable list of {@link AngleExposure} pairs 
     *         representing the rotation sequence. Returns empty list if no rotations are required.
     *         Never returns null
     * @implNote Implementations should handle null parameters gracefully by falling back to defaults.
     *           Consider caching results for repeated calls with the same parameters to improve performance
     * @see AngleExposure
     * @see ModalityRegistry#getHandler(String)
     */
    CompletableFuture<List<AngleExposure>> getRotationAngles(String modalityName, String objective, String detector);

    /**
     * Creates an optional JavaFX UI component for modality-specific parameter controls.
     * 
     * <p>This method allows modalities to provide custom user interface components that integrate 
     * with the bounding box dialog. These UI components typically allow users to override default 
     * acquisition parameters such as rotation angles, decimal exposure times, or other modality-specific 
     * settings before starting an acquisition.</p>
     * 
     * <p>The returned {@link BoundingBoxUI} component is embedded in the main acquisition dialog 
     * and provides real-time parameter adjustment capabilities. When the user triggers an acquisition, 
     * the overrides from this UI are passed to {@link #applyAngleOverrides(List, Map)} to modify 
     * the default parameters.</p>
     * 
     * <p><strong>UI Thread Safety:</strong> The JavaFX {@code Node} returned by the BoundingBoxUI 
     * must be created and accessed only on the JavaFX Application Thread. Implementations should 
     * ensure thread-safe access to any backing data models.</p>
     * 
     * @return an {@code Optional} containing a {@link BoundingBoxUI} instance if this modality 
     *         provides custom UI controls, or {@code Optional.empty()} if no custom UI is needed.
     *         The default implementation returns empty
     * @implNote Consider implementing this method for modalities that benefit from user parameter 
     *           adjustment. Simple modalities with fixed parameters may safely use the default 
     *           implementation. UI components should be lightweight and responsive
     * @see BoundingBoxUI#getAngleOverrides()
     * @see #applyAngleOverrides(List, Map)
     * @see qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI
     */
    default Optional<BoundingBoxUI> createBoundingBoxUI() {
        return Optional.empty();
    }

    /**
     * Applies user-provided parameter overrides to the default acquisition angles for this modality.
     * 
     * <p>This method provides the mechanism for incorporating user customizations from the 
     * {@link BoundingBoxUI} into the acquisition sequence. The overrides map contains key-value 
     * pairs where keys are implementation-defined parameter identifiers and values are the 
     * user-specified replacement values.</p>
     * 
     * <p>Common override patterns include:</p>
     * <ul>
     *   <li><strong>Angle replacement:</strong> Replace specific angles while preserving exposure times</li>
     *   <li><strong>Conditional modification:</strong> Apply overrides based on angle sign, magnitude, or position</li>
     *   <li><strong>Range filtering:</strong> Remove or modify angles outside specified ranges</li>
     * </ul>
     * 
     * <p>The default implementation returns the original angles unmodified, which is appropriate 
     * for modalities that do not support parameter customization.</p>
     * 
     * @param angles the original list of {@link AngleExposure} pairs from {@link #getRotationAngles(String)}.
     *               May be empty but never null. Implementations should not modify this list directly
     * @param overrides a map of parameter overrides from the UI component. Keys are implementation-specific 
     *                  parameter identifiers (e.g., "plus", "minus", "angle1"). Values are the user-provided 
     *                  replacement values. May be null or empty if no overrides are specified
     * @return a new list of {@link AngleExposure} pairs with overrides applied. Must not be null.
     *         May be the same instance as the input if no changes are made
     * @implNote Implementations should validate override values and handle edge cases gracefully.
     *           Consider preserving exposure times when only angle values are overridden. Document
     *           the expected override keys in the implementation class
     * @see #createBoundingBoxUI()
     * @see BoundingBoxUI#getAngleOverrides()
     * @see qupath.ext.qpsc.modality.ppm.PPMModalityHandler#applyAngleOverrides(List, Map)
     */
    default List<AngleExposure> applyAngleOverrides(
            List<AngleExposure> angles,
            Map<String, Double> overrides) {
        return angles;
    }

    /**
     * Generates a filename-safe suffix string for the specified rotation angle.
     * 
     * <p>This method creates human-readable suffixes used in image filenames to distinguish 
     * acquisitions at different rotation angles. The suffix is appended to base filenames 
     * during the acquisition process to create unique identifiers for each angle in a 
     * multi-angle sequence.</p>
     * 
     * <p>The default implementation simply converts the angle to a string representation, 
     * but modalities may override this to provide more intuitive naming conventions. 
     * Common patterns include:</p>
     * <ul>
     *   <li><strong>Sign prefixes:</strong> "p45" for +45°, "m45" for -45°</li>
     *   <li><strong>Degree symbols:</strong> "45deg", "90deg"</li>
     *   <li><strong>Ordinal naming:</strong> "angle1", "angle2" for sequence position</li>
     *   <li><strong>Descriptive names:</strong> "parallel", "perpendicular" for specific angles</li>
     * </ul>
     * 
     * <p><strong>Filename Safety:</strong> The returned string must be safe for use in filenames 
     * across different operating systems. Avoid characters like {@code / \ : * ? " < > |} and 
     * consider length limitations for very long angle sequences.</p>
     * 
     * @param angle the rotation angle value in the same units used by {@link AngleExposure#ticks()}.
     *              Typically represents physical rotation in degrees or encoder ticks
     * @return a filename-safe string suffix representing the angle. Must not be null or empty.
     *         Should be unique within the angle set to avoid filename collisions
     * @implNote Keep suffixes concise but descriptive. Consider the total filename length when 
     *           combined with base names and extensions. Use consistent formatting across all 
     *           angles in a modality
     * @see AngleExposure#ticks()
     */
    default String getAngleSuffix(double angle) {
        return String.valueOf(angle);
    }

    /**
     * Interface for modality-specific UI components that integrate with the acquisition dialog.
     * 
     * <p>BoundingBoxUI implementations provide custom JavaFX controls for modality-specific 
     * parameter adjustment within the main acquisition workflow. These components are embedded 
     * in the bounding box dialog and allow users to override default acquisition parameters 
     * before starting an acquisition.</p>
     * 
     * <p>The interface defines a simple contract: provide a JavaFX node for display and 
     * extract parameter overrides when the user initiates an acquisition. This design allows 
     * modalities to implement arbitrarily complex UI controls while maintaining a consistent 
     * integration pattern.</p>
     * 
     * <h3>Implementation Example</h3>
     * <pre>{@code
     * public class MyModalityBoundingBoxUI implements BoundingBoxUI {
     *     private final VBox rootNode;
     *     private final Slider angleSlider;
     *     
     *     public MyModalityBoundingBoxUI() {
     *         angleSlider = new Slider(-180, 180, 0);
     *         rootNode = new VBox(new Label("Custom Angle:"), angleSlider);
     *     }
     *     
     *     @Override
     *     public Node getNode() {
     *         return rootNode;
     *     }
     *     
     *     @Override
     *     public Map<String, Double> getAngleOverrides() {
     *         return Map.of("customAngle", angleSlider.getValue());
     *     }
     * }
     * }</pre>
     * 
     * @see #createBoundingBoxUI()
     * @see #applyAngleOverrides(List, Map)
     * @since 1.0
     */
    interface BoundingBoxUI {
        /**
         * Returns the root JavaFX node for this UI component.
         * 
         * <p>The returned node is embedded directly in the bounding box dialog and should 
         * contain all necessary controls for parameter adjustment. The node should be 
         * properly sized and styled to integrate well with the existing dialog layout.</p>
         * 
         * <p><strong>Thread Safety:</strong> This method must be called only on the JavaFX 
         * Application Thread. The returned node and all its children must be created and 
         * accessed only on the FX thread.</p>
         * 
         * @return the root JavaFX {@code Node} containing all UI controls for this modality.
         *         Must not be null
         * @implNote Consider using layout containers like {@code VBox} or {@code GridPane} 
         *           to organize multiple controls. Apply appropriate spacing and padding for 
         *           visual integration with the parent dialog
         */
        Node getNode();
        
        /**
         * Extracts current parameter override values from the UI controls.
         * 
         * <p>This method is called when the user initiates an acquisition to collect any 
         * parameter overrides specified through the UI. The returned map contains key-value 
         * pairs where keys are parameter identifiers and values are the user-specified 
         * override values.</p>
         * 
         * <p>The parameter keys in the returned map should correspond to the keys expected 
         * by the {@link #applyAngleOverrides(List, Map)} method of the parent modality handler. 
         * This provides the linkage between UI controls and parameter application.</p>
         * 
         * @return a map of parameter overrides where keys are implementation-specific parameter 
         *         identifiers and values are the user-provided override values. May be empty 
         *         if no overrides are specified, but must not be null
         * @implNote Document the expected parameter keys in the implementation class. Consider 
         *           validating values before returning them to catch user input errors early
         * @see #applyAngleOverrides(List, Map)
         */
        Map<String, Double> getAngleOverrides();
    }
}
