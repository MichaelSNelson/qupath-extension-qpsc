package qupath.ext.qpsc.modalities.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modalities.ppm.PPMModalityHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all modality handlers.
 * Automatically discovers and registers modality implementations.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class ModalityRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ModalityRegistry.class);

    // Singleton instance
    private static final ModalityRegistry INSTANCE = new ModalityRegistry();

    // Map of modality prefix to handler
    private final Map<String, ModalityHandler> handlers = new ConcurrentHashMap<>();

    // Default handler for unrecognized modalities
    private final ModalityHandler defaultHandler = new NoRotationModalityHandler();

    /**
     * Private constructor - initializes with built-in modalities
     */
    private ModalityRegistry() {
        // Register built-in modalities
        registerModality(new PPMModalityHandler());
        registerModality(new NoRotationModalityHandler());

        logger.info("Modality registry initialized with {} handlers", handlers.size());
    }

    /**
     * Gets the singleton instance.
     *
     * @return The registry instance
     */
    public static ModalityRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a modality handler.
     *
     * @param handler The handler to register
     */
    public void registerModality(ModalityHandler handler) {
        String prefix = handler.getModalityPrefix();
        handlers.put(prefix, handler);
        logger.info("Registered modality handler for prefix '{}': {}",
                prefix, handler.getClass().getSimpleName());
    }

    /**
     * Gets the appropriate handler for a modality name.
     *
     * @param modalityName The modality name (e.g., "PPM_10x", "BF_20x")
     * @return The appropriate handler, or default if none found
     */
    public ModalityHandler getHandler(String modalityName) {
        if (modalityName == null || modalityName.isEmpty()) {
            return defaultHandler;
        }

        // Find handler that can handle this modality
        for (ModalityHandler handler : handlers.values()) {
            if (handler.handles(modalityName)) {
                logger.debug("Found handler {} for modality {}",
                        handler.getClass().getSimpleName(), modalityName);
                return handler;
            }
        }

        logger.debug("No specific handler found for modality {}, using default", modalityName);
        return defaultHandler;
    }

    /**
     * Gets all registered modality prefixes.
     *
     * @return Set of modality prefixes
     */
    public Set<String> getRegisteredPrefixes() {
        return new HashSet<>(handlers.keySet());
    }

    /**
     * Checks if a modality requires rotation.
     *
     * @param modalityName The modality name
     * @return true if rotation is required
     */
    public boolean requiresRotation(String modalityName) {
        ModalityHandler handler = getHandler(modalityName);
        return handler.requiresRotation();
    }

    /**
     * Utility method to detect modality type from name.
     *
     * @param modalityName The modality name
     * @return The modality prefix (e.g., "PPM" from "PPM_10x")
     */
    public static String detectModalityPrefix(String modalityName) {
        if (modalityName == null || modalityName.isEmpty()) {
            return "";
        }

        // Extract prefix before underscore
        int underscoreIndex = modalityName.indexOf('_');
        if (underscoreIndex > 0) {
            return modalityName.substring(0, underscoreIndex);
        }

        return modalityName;
    }

    /**
     * Checks if a modality is PPM-based.
     *
     * @param modalityName The modality name
     * @return true if PPM modality
     */
    public static boolean isPPMModality(String modalityName) {
        return modalityName != null && modalityName.startsWith("PPM_");
    }

    /**
     * Checks if a modality is standard brightfield.
     *
     * @param modalityName The modality name
     * @return true if brightfield modality
     */
    public static boolean isBrightfieldModality(String modalityName) {
        return modalityName != null && modalityName.startsWith("BF_");
    }
}