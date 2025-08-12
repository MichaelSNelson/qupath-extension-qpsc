package qupath.ext.qpsc.modalities.ppm;

import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.RotationManager;
import qupath.ext.qpsc.modalities.common.ModalityHandler;
import qupath.ext.qpsc.modalities.ppm.ui.PPMConfigurationPanel;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Modality handler for Polarized Light Microscopy (PPM).
 * Manages rotation angles, exposures, and PPM-specific configurations.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMModalityHandler implements ModalityHandler {
    private static final Logger logger = LoggerFactory.getLogger(PPMModalityHandler.class);

    @Override
    public boolean handles(String modalityName) {
        return modalityName != null && modalityName.startsWith("PPM_");
    }

    @Override
    public String getModalityPrefix() {
        return "PPM";
    }

    @Override
    public boolean requiresRotation() {
        return true;
    }

    @Override
    public CompletableFuture<List<RotationManager.TickExposure>> getRotationAngles(
            String modalityName,
            MicroscopeConfigManager configManager) {

        logger.info("Getting rotation angles for PPM modality: {}", modalityName);

        // Create PPM rotation strategy with angles from config
        PPMRotationStrategy strategy = PPMConfiguration.createRotationStrategy(configManager);

        // Get angles with exposure times via user dialog
        return strategy.getRotationTicksWithExposure();
    }

    @Override
    public Node createConfigurationUI(MicroscopeConfigManager configManager) {
        // Create PPM-specific configuration panel
        return PPMConfigurationPanel.create(configManager);
    }

    @Override
    public boolean validateConfiguration(String modalityName, MicroscopeConfigManager configManager) {
        // Validate basic imaging parameters
        Double pixelSize = configManager.getDouble("imagingMode", modalityName, "pixelSize_um");
        Integer width = configManager.getInteger("imagingMode", modalityName, "detector", "width_px");
        Integer height = configManager.getInteger("imagingMode", modalityName, "detector", "height_px");

        if (pixelSize == null || pixelSize <= 0 ||
                width == null || width <= 0 ||
                height == null || height <= 0) {
            logger.error("Basic imaging parameters missing for {}", modalityName);
            return false;
        }

        // Validate PPM-specific configuration
        Map<String, Object> ppmConfig = configManager.getPPMConfig();
        if (ppmConfig == null || ppmConfig.isEmpty()) {
            logger.error("PPM configuration section missing");
            return false;
        }

        // Check for required PPM angles
        boolean hasAngles = ppmConfig.containsKey("ppm_plus") ||
                ppmConfig.containsKey("ppm_minus") ||
                ppmConfig.containsKey("ppm_zero");

        if (!hasAngles) {
            logger.error("No PPM angles configured");
            return false;
        }

        return true;
    }

    @Override
    public Map<String, Object> getDefaultParameters() {
        Map<String, Object> defaults = new HashMap<>();

        // PPM-specific defaults from preferences
        defaults.put("minusAngleSelected", PPMPreferences.getPPMMinusSelected());
        defaults.put("zeroAngleSelected", PPMPreferences.getPPMZeroSelected());
        defaults.put("plusAngleSelected", PPMPreferences.getPPMPlusSelected());
        defaults.put("brightfieldSelected", PPMPreferences.getPPMBrightfieldSelected());

        defaults.put("minusExposureMs", PPMPreferences.getPPMMinusExposureMs());
        defaults.put("zeroExposureMs", PPMPreferences.getPPMZeroExposureMs());
        defaults.put("plusExposureMs", PPMPreferences.getPPMPlusExposureMs());
        defaults.put("brightfieldExposureMs", PPMPreferences.getPPMBrightfieldExposureMs());

        return defaults;
    }

    @Override
    public AcquisitionCommandBuilder enhanceAcquisitionCommand(
            AcquisitionCommandBuilder builder,
            String modalityName,
            MicroscopeConfigManager configManager,
            Map<String, Object> userParameters) {

        // Add angle exposures if provided
        if (userParameters != null && userParameters.containsKey("angleExposures")) {
            @SuppressWarnings("unchecked")
            List<RotationManager.TickExposure> angleExposures =
                    (List<RotationManager.TickExposure>) userParameters.get("angleExposures");

            if (angleExposures != null && !angleExposures.isEmpty()) {
                builder.angleExposures(angleExposures);
                logger.info("Added {} angle exposures to acquisition command", angleExposures.size());
            }
        }

        return builder;
    }

    @Override
    public String getDisplayName() {
        return "Polarized Light Microscopy (PPM)";
    }

    @Override
    public String getFileSuffix(Map<String, Object> parameters) {
        // PPM doesn't need angle suffixes in filenames anymore
        // Each angle creates a separate file with the angle in the name
        return "";
    }
}