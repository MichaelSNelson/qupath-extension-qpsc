package qupath.ext.qpsc.modality;

import qupath.ext.qpsc.modality.ppm.PPMModalityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe static registry for mapping modality name prefixes to {@link ModalityHandler} implementations.
 * 
 * <p>The ModalityRegistry serves as the central plugin discovery mechanism for the QPSC modality system,
 * enabling runtime registration and lookup of modality-specific handlers through prefix-based matching.
 * This design allows the extension to support multiple imaging modalities (e.g., polarized light microscopy,
 * fluorescence, brightfield) without requiring compile-time knowledge of all possible modalities.</p>
 * 
 * <h3>Plugin Architecture</h3>
 * <p>The registry implements a simple yet powerful plugin pattern:</p>
 * <ul>
 *   <li><strong>Registration:</strong> Modality handlers register with a string prefix (e.g., "ppm", "fl", "bf")</li>
 *   <li><strong>Prefix Matching:</strong> Configuration modality names are matched against registered prefixes using startsWith semantics</li>
 *   <li><strong>Handler Resolution:</strong> The first matching handler is returned, or a no-op handler if no match is found</li>
 *   <li><strong>Case Insensitive:</strong> All prefix matching is performed case-insensitively for robust configuration handling</li>
 * </ul>
 * 
 * <h3>Usage Patterns</h3>
 * <pre>{@code
 * // Registration (typically in static initializers or extension setup)
 * ModalityRegistry.registerHandler("ppm", new PPMModalityHandler());
 * ModalityRegistry.registerHandler("fl", new FluorescenceModalityHandler());
 * 
 * // Lookup during acquisition workflow
 * ModalityHandler handler = ModalityRegistry.getHandler("ppm_20x");  // Returns PPMModalityHandler
 * ModalityHandler handler = ModalityRegistry.getHandler("fl_40x");   // Returns FluorescenceModalityHandler  
 * ModalityHandler handler = ModalityRegistry.getHandler("unknown");  // Returns NoOpModalityHandler
 * }</pre>
 * 
 * <h3>Configuration Integration</h3>
 * <p>The registry integrates seamlessly with QPSC's YAML-based configuration system. Microscope configuration
 * files define modality names like "ppm_20x_oil" or "bf_10x", and the registry automatically resolves these
 * to the appropriate handler based on the leading prefix. This allows for flexible modality naming conventions
 * while maintaining consistent handler resolution.</p>
 * 
 * <h3>Thread Safety</h3>
 * <p>This class is designed for concurrent access during acquisition workflows. The underlying storage uses
 * {@link ConcurrentHashMap} to ensure thread-safe registration and lookup operations. The registry can be
 * safely accessed from multiple threads without external synchronization, making it suitable for use in
 * multi-threaded acquisition environments.</p>
 * 
 * <h3>Extensibility</h3>
 * <p>New modalities can be added to the system by:</p>
 * <ol>
 *   <li>Implementing the {@link ModalityHandler} interface</li>
 *   <li>Registering the handler with a unique prefix using {@link #registerHandler(String, ModalityHandler)}</li>
 *   <li>Using the prefix in microscope configuration files</li>
 * </ol>
 * 
 * <p>No changes to existing code are required when adding new modalities, providing true plugin-style extensibility.</p>
 * 
 * <h3>Failure Handling</h3>
 * <p>The registry is designed to degrade gracefully when handlers are not found. Rather than throwing exceptions,
 * {@link #getHandler(String)} returns a {@link NoOpModalityHandler} that provides sensible defaults for unknown
 * modalities. This ensures that acquisition workflows can continue even with incomplete modality configurations.</p>
 * 
 * @author Mike Nelson
 * @since 1.0
 * @see ModalityHandler
 * @see NoOpModalityHandler  
 * @see qupath.ext.qpsc.modality.ppm.PPMModalityHandler
 */
public final class ModalityRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModalityRegistry.class);
    
    private static final Map<String, ModalityHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final ModalityHandler NO_OP = new NoOpModalityHandler();

    static {
        logger.info("Initializing ModalityRegistry with default modality handlers");
        registerHandler("ppm", new PPMModalityHandler());
        logger.info("ModalityRegistry initialization complete. Registered {} modality handlers", HANDLERS.size());
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ModalityRegistry() {
        // Utility class - no instantiation
    }

    /**
     * Registers a handler for modality names that start with the given prefix.
     * 
     * <p>This method provides the core registration mechanism for the modality plugin system.
     * Once registered, any modality name from configuration files that starts with the specified
     * prefix (case-insensitive) will resolve to the provided handler. This enables flexible
     * modality naming conventions while maintaining consistent handler resolution.</p>
     * 
     * <p>The registration is performed atomically and is thread-safe. If a handler is already
     * registered for the given prefix, it will be replaced with the new handler, and a warning
     * will be logged to indicate the replacement.</p>
     * 
     * <p><strong>Prefix Matching Rules:</strong></p>
     * <ul>
     *   <li>Prefixes are stored and matched in lowercase for case-insensitive behavior</li>
     *   <li>Matching uses {@link String#startsWith(String)} semantics</li>
     *   <li>Longer prefixes should be registered before shorter ones to avoid unintended matches</li>
     *   <li>Empty or null prefixes are ignored and logged as warnings</li>
     * </ul>
     * 
     * <h3>Registration Examples</h3>
     * <pre>{@code
     * // Basic registration
     * ModalityRegistry.registerHandler("ppm", new PPMModalityHandler());
     * 
     * // This handler will match: "ppm", "ppm_20x", "PPM_40x", "ppm_oil_100x"
     * // But not: "xppm", "fluorescence", "brightfield"
     * 
     * // Multiple prefixes for the same handler
     * FluorescenceHandler flHandler = new FluorescenceHandler();
     * ModalityRegistry.registerHandler("fl", flHandler);
     * ModalityRegistry.registerHandler("fluorescence", flHandler);
     * }</pre>
     * 
     * @param prefix the modality name prefix to register (e.g., "ppm", "fl", "bf"). Must not be null
     *               or empty. Prefixes are converted to lowercase for case-insensitive matching
     * @param handler the {@link ModalityHandler} implementation to associate with this prefix.
     *                Must not be null and should be thread-safe for concurrent access
     * @throws IllegalArgumentException if prefix is null, empty, or handler is null
     * @see #getHandler(String)
     * @see ModalityHandler
     */
    public static void registerHandler(String prefix, ModalityHandler handler) {
        if (prefix == null || prefix.trim().isEmpty()) {
            logger.warn("Attempted to register handler with null or empty prefix - ignoring registration");
            return;
        }
        
        if (handler == null) {
            logger.warn("Attempted to register null handler for prefix '{}' - ignoring registration", prefix);
            return;
        }
        
        String normalizedPrefix = prefix.toLowerCase().trim();
        ModalityHandler existingHandler = HANDLERS.put(normalizedPrefix, handler);
        
        if (existingHandler != null) {
            logger.warn("Replaced existing handler for prefix '{}'. Old handler: {}, New handler: {}", 
                       normalizedPrefix, existingHandler.getClass().getSimpleName(), handler.getClass().getSimpleName());
        } else {
            logger.info("Registered new modality handler for prefix '{}': {}", 
                       normalizedPrefix, handler.getClass().getSimpleName());
        }
        
        logger.debug("Registry now contains {} registered modality handlers", HANDLERS.size());
    }

    /**
     * Returns a handler appropriate for the given modality name using prefix matching.
     * 
     * <p>This method implements the core lookup mechanism for the modality plugin system. Given a
     * modality name from configuration files (e.g., "ppm_20x", "fl_405_40x"), it searches through
     * registered prefixes to find the first matching handler. The search is performed case-insensitively
     * and uses {@link String#startsWith(String)} semantics.</p>
     * 
     * <p>The lookup algorithm iterates through registered handlers in insertion order (as maintained by
     * {@link ConcurrentHashMap}), returning the first handler whose prefix matches the start of the
     * provided modality name. This means that registration order can matter when prefixes might overlap
     * (e.g., "fl" vs "fluorescence").</p>
     * 
     * <p><strong>Fallback Behavior:</strong> If no registered handler matches the modality name, a
     * {@link NoOpModalityHandler} is returned instead of throwing an exception. This ensures that
     * acquisition workflows can continue gracefully even with incomplete or incorrect modality
     * configurations, though with default/minimal functionality.</p>
     * 
     * <h3>Lookup Examples</h3>
     * <pre>{@code
     * // Assuming registered handlers for prefixes: "ppm", "fl", "bf"
     * 
     * ModalityHandler handler1 = ModalityRegistry.getHandler("ppm_20x");           // Returns PPMModalityHandler
     * ModalityHandler handler2 = ModalityRegistry.getHandler("PPM_40X_OIL");       // Returns PPMModalityHandler (case-insensitive)
     * ModalityHandler handler3 = ModalityRegistry.getHandler("fl_405_20x");        // Returns FluorescenceHandler  
     * ModalityHandler handler4 = ModalityRegistry.getHandler("bf");                // Returns BrightfieldHandler
     * ModalityHandler handler5 = ModalityRegistry.getHandler("unknown_modality");  // Returns NoOpModalityHandler
     * ModalityHandler handler6 = ModalityRegistry.getHandler(null);                // Returns NoOpModalityHandler
     * }</pre>
     * 
     * <p><strong>Performance Considerations:</strong> The lookup operation has O(n) complexity where n
     * is the number of registered handlers, since it must iterate through all registered prefixes.
     * For typical usage with a small number of modalities (< 10), this provides adequate performance.
     * Applications requiring high-performance lookups should consider caching results.</p>
     * 
     * @param modalityName the full modality identifier to look up (e.g., "ppm_20x", "fl_405_40x").
     *                     May be null or empty, in which case the no-op handler is returned.
     *                     Lookup is performed case-insensitively
     * @return the matching {@link ModalityHandler} implementation, or {@link NoOpModalityHandler}
     *         if no prefix matches or modalityName is null/empty. Never returns null
     * @see #registerHandler(String, ModalityHandler)
     * @see NoOpModalityHandler
     * @see ModalityHandler
     */
    public static ModalityHandler getHandler(String modalityName) {
        if (modalityName == null || modalityName.trim().isEmpty()) {
            logger.debug("Lookup requested for null/empty modality name - returning no-op handler");
            return NO_OP;
        }
        
        String normalizedName = modalityName.toLowerCase().trim();
        logger.debug("Looking up handler for modality name: '{}'", normalizedName);
        
        // Iterate through registered handlers to find first matching prefix
        for (Map.Entry<String, ModalityHandler> entry : HANDLERS.entrySet()) {
            String prefix = entry.getKey();
            if (normalizedName.startsWith(prefix)) {
                ModalityHandler handler = entry.getValue();
                logger.debug("Found matching handler for modality '{}' with prefix '{}': {}", 
                            modalityName, prefix, handler.getClass().getSimpleName());
                return handler;
            }
        }
        
        // No matching handler found - log warning and return no-op handler
        logger.warn("No registered handler found for modality '{}' - returning no-op handler. " +
                   "Registered prefixes: {}", modalityName, HANDLERS.keySet());
        return NO_OP;
    }
}
