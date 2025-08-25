package qupath.ext.qpsc.modality.ppm;

import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Modality handler for polarized light (PPM) imaging.
 */
public class PPMModalityHandler implements ModalityHandler {
    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(String modalityName) {
        RotationManager rotationManager = new RotationManager(modalityName);
        return rotationManager.getRotationTicksWithExposure(modalityName);
    }

    @Override
    public java.util.Optional<BoundingBoxUI> createBoundingBoxUI() {
        return java.util.Optional.of(new PPMBoundingBoxUI());
    }

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
