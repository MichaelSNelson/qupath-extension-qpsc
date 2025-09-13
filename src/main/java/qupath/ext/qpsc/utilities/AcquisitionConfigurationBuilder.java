package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.ui.SampleSetupController;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Centralized builder for acquisition configuration that consolidates duplicated logic
 * from BoundingBoxWorkflow and AcquisitionManager.
 */
public class AcquisitionConfigurationBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionConfigurationBuilder.class);
    
    /**
     * Configuration record containing all acquisition parameters
     */
    public record AcquisitionConfiguration(
        String objective,
        String detector,
        double WSI_pixelSize_um,
        boolean bgEnabled,
        String bgMethod,
        String bgFolder,
        int afTiles,
        int afSteps,
        double afRange,
        List<String> processingSteps,
        boolean whiteBalanceEnabled,
        AcquisitionCommandBuilder commandBuilder
    ) {}
    
    /**
     * Builds a complete acquisition configuration from the provided parameters
     */
    public static AcquisitionConfiguration buildConfiguration(
            SampleSetupController.SampleSetupResult sample,
            String configFileLocation,
            String modalityWithIndex,
            String regionName,
            List<AngleExposure> angleExposures,
            String projectsFolder,
            double explicitPixelSize) {
        
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFileLocation);
        
        // Use explicit hardware selections from sample setup
        String objective = sample.objective();
        String detector = sample.detector();
        String baseModality = sample.modality();
        
        // Get background correction settings
        boolean bgEnabled = configManager.getBoolean("modalities", baseModality, "background_correction", "enabled");
        String bgMethod = configManager.getString("modalities", baseModality, "background_correction", "method");
        String bgBaseFolder = configManager.getString("modalities", baseModality, "background_correction", "base_folder");
        
        // Construct detector-specific background folder path
        String bgFolder = null;
        if (bgEnabled && bgBaseFolder != null) {
            String magnification = extractMagnificationFromObjective(objective);
            bgFolder = Paths.get(bgBaseFolder, detector, baseModality, magnification).toString();
            logger.info("Constructed background folder path: {}", bgFolder);
        }
        
        // Get autofocus parameters
        Map<String, Object> afParams = configManager.getAutofocusParams(objective);
        int afTiles = 5;    // defaults
        int afSteps = 11;
        double afRange = 50.0;
        
        if (afParams != null) {
            if (afParams.get("n_tiles") instanceof Number) {
                afTiles = ((Number) afParams.get("n_tiles")).intValue();
            }
            if (afParams.get("n_steps") instanceof Number) {
                afSteps = ((Number) afParams.get("n_steps")).intValue();
            }
            if (afParams.get("search_range_um") instanceof Number) {
                afRange = ((Number) afParams.get("search_range_um")).doubleValue();
            }
        }
        
        // Determine processing pipeline based on detector properties
        List<String> processingSteps = new ArrayList<>();
        if (configManager.detectorRequiresDebayering(detector)) {
            processingSteps.add("debayer");
        }
        if (bgEnabled && bgFolder != null) {
            processingSteps.add("background_correction");
        }
        
        // Get white balance setting from config for this profile
        boolean whiteBalanceEnabled = configManager.isWhiteBalanceEnabled(baseModality, objective, detector);
        logger.debug("White balance enabled for {}/{}/{}: {}", baseModality, objective, detector, whiteBalanceEnabled);
        
        // Build enhanced acquisition command
        AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                .yamlPath(configFileLocation)
                .projectsFolder(projectsFolder)
                .sampleLabel(sample.sampleName())
                .scanType(modalityWithIndex)
                .regionName(regionName)
                .angleExposures(angleExposures)
                .hardware(objective, detector, explicitPixelSize)
                .autofocus(afTiles, afSteps, afRange)
                .processingPipeline(processingSteps)
                .whiteBalance(whiteBalanceEnabled);
        
        // Only add background correction if enabled and configured
        if (bgEnabled && bgMethod != null && bgFolder != null) {
            acquisitionBuilder.backgroundCorrection(true, bgMethod, bgFolder);
        }
        
        return new AcquisitionConfiguration(
            objective,
            detector,
            explicitPixelSize,
            bgEnabled,
            bgMethod,
            bgFolder,
            afTiles,
            afSteps,
            afRange,
            processingSteps,
            whiteBalanceEnabled,
            acquisitionBuilder
        );
    }
    
    /**
     * Helper method to extract magnification from objective name
     * Extracted from duplicated code in both workflows
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective != null && objective.contains("_")) {
            String[] parts = objective.split("_");
            for (String part : parts) {
                if (part.toUpperCase().endsWith("X")) {
                    return part.toLowerCase();
                }
            }
        }
        return "unknown";
    }
}