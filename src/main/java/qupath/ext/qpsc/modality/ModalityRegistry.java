package qupath.ext.qpsc.modality;

import qupath.ext.qpsc.modality.ppm.PPMModalityHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple registry for mapping modality name prefixes to handlers.
 * Future modalities can register their handler with a prefix so that
 * sample modality names like {@code ppm_20x} resolve automatically.
 */
public final class ModalityRegistry {

    private static final Map<String, ModalityHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final ModalityHandler NO_OP = new NoOpModalityHandler();

    static {
        registerHandler("ppm", new PPMModalityHandler());
    }

    private ModalityRegistry() {
    }

    /**
     * Register a handler for modality names that start with the given prefix.
     * Prefix matching is case-insensitive.
     *
     * @param prefix  modality name prefix (e.g., "ppm")
     * @param handler handler to return for matching modalities
     */
    public static void registerHandler(String prefix, ModalityHandler handler) {
        if (prefix != null && handler != null) {
            HANDLERS.put(prefix.toLowerCase(), handler);
        }
    }

    /**
     * Returns a handler appropriate for the given modality name.
     * If no handler matches, a no-op handler is returned.
     */
    public static ModalityHandler getHandler(String modalityName) {
        if (modalityName != null) {
            String lower = modalityName.toLowerCase();
            for (Map.Entry<String, ModalityHandler> entry : HANDLERS.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return NO_OP;
    }
}
