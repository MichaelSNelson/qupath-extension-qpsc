package qupath.ext.qpsc.modalities.common;

import javafx.scene.Node;
import qupath.ext.qpsc.utilities.RotationManager;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default modality handler for imaging modes that don't require rotation.
 * This includes standard brightfield, fluorescence, and other basic imaging modes.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class NoRotationModalityHandler implements ModalityHandler {

    @Override
    public boolean handles(String modalityName) {
        // This is the catch-all handler - it can handle anything
        // but other handlers should be checked first
        return true;
    }

    @Override
    public String getModalityPrefix() {
        return "DEFAULT";
    }

    @Override
    public boolean requiresRotation() {
        return false;
    }

    @Override
    public CompletableFuture<List<RotationManager.TickExposure>> getRotationAngles(
            String modalityName,
            MicroscopeConfigManager configManager) {
        // No rotation for standard modalities
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public Node createConfigurationUI(MicroscopeConfigManager configManager) {
        // No special UI needed for standard modalities
        return null;
    }

    @Override
    public boolean validateConfiguration(String modalityName, MicroscopeConfigManager configManager) {
        // Check basic requirements - pixel size, detector dimensions
        Double pixelSize = configManager.getDouble("imagingMode", modalityName, "pixelSize_um");
        Integer width = configManager.getInteger("imagingMode", modalityName, "detector", "width_px");
        Integer height = configManager.getInteger("imagingMode", modalityName, "detector", "height_px");

        return pixelSize != null && pixelSize > 0 &&
                width != null && width > 0 &&
                height != null && height > 0;
    }

    @Override
    public Map<String, Object> getDefaultParameters() {
        Map<String, Object> defaults = new HashMap<>();
        // Standard modalities might have exposure time but no rotation
        defaults.put("exposureMs", 100);
        return defaults;
    }

    @Override
    public AcquisitionCommandBuilder enhanceAcquisitionCommand(
            AcquisitionCommandBuilder builder,
            String modalityName,
            MicroscopeConfigManager configManager,
            Map<String, Object> userParameters) {
        // No special enhancements needed for standard modalities
        // The base command is sufficient
        return builder;
    }

    @Override
    public String getDisplayName() {
        return "Standard Imaging";
    }
}