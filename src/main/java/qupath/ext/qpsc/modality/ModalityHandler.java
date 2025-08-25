package qupath.ext.qpsc.modality;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;

/**
 * Provides modality-specific acquisition parameters such as rotation angles.
 */
public interface ModalityHandler {
    /**
     * Returns rotation angles (with exposures) required for this modality.
     * @param modalityName modality identifier
     * @return future containing list of angle/exposure pairs (may be empty)
     */
    CompletableFuture<List<AngleExposure>> getRotationAngles(String modalityName);

    /**
     * Optional UI component for modality-specific controls in the bounding box dialog.
     * @return optional bounding box UI
     */
    default Optional<BoundingBoxUI> createBoundingBoxUI() {
        return Optional.empty();
    }

    /**
     * Applies user-provided angle overrides to the default angles for this modality.
     * @param angles original angle/exposure pairs
     * @param overrides map of override values (implementation-defined keys)
     * @return adjusted list of angle/exposure pairs
     */
    default List<AngleExposure> applyAngleOverrides(
            List<AngleExposure> angles,
            Map<String, Double> overrides) {
        return angles;
    }

    /**
     * Provides a filename-friendly suffix for the given angle.
     * @param angle rotation angle
     * @return suffix string (e.g. "p5", "m5", "90")
     */
    default String getAngleSuffix(double angle) {
        return String.valueOf(angle);
    }

    interface BoundingBoxUI {
        Node getNode();
        Map<String, Double> getAngleOverrides();
    }
}
