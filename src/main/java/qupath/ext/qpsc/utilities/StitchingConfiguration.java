package qupath.ext.qpsc.utilities;

import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * Centralized configuration for stitching operations across all workflows.
 * Provides consistent stitching parameters and settings.
 */
public class StitchingConfiguration {

    /**
     * Immutable record containing all stitching configuration parameters
     */
    public record StitchingParams(
        String compressionType,
        int downsampleFactor,
        double qualityFactor,
        StitchingConfig.OutputFormat outputFormat
    ) {}
    
    /**
     * Creates a standard stitching configuration using current preferences.
     * This ensures consistent stitching parameters across all workflows.
     *
     * @return StitchingParams with current preference values
     */
    public static StitchingParams getStandardConfiguration() {
        String compressionType = String.valueOf(QPPreferenceDialog.getCompressionTypeProperty());
        int downsampleFactor = 1; // Standard downsample factor for all workflows
        double qualityFactor = 0.85; // Standard quality factor for compression
        StitchingConfig.OutputFormat outputFormat = QPPreferenceDialog.getOutputFormatProperty();

        return new StitchingParams(
            compressionType,
            downsampleFactor,
            qualityFactor,
            outputFormat
        );
    }
    
    /**
     * Creates a custom stitching configuration with specified parameters.
     * Use this for specialized stitching requirements.
     *
     * @param compressionType The compression type (e.g., "JPEG", "LZW", "JPEG2000")
     * @param downsampleFactor Downsample factor for the stitched image
     * @param qualityFactor Quality factor for lossy compression (0.0 to 1.0)
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     * @return StitchingParams with custom values
     */
    public static StitchingParams createCustomConfiguration(
            String compressionType,
            int downsampleFactor,
            double qualityFactor,
            StitchingConfig.OutputFormat outputFormat) {
        return new StitchingParams(compressionType, downsampleFactor, qualityFactor, outputFormat);
    }
    
    /**
     * Gets the appropriate compression type based on modality requirements.
     * Some modalities may require lossless compression while others can use lossy.
     * 
     * @param modalityBase The base modality name (e.g., "ppm", "brightfield")
     * @return Appropriate compression type for the modality
     */
    public static String getModalitySpecificCompression(String modalityBase) {
        // For now, use the standard compression from preferences
        // In the future, this could be extended to check modality-specific requirements
        // from the microscope configuration
        return String.valueOf(QPPreferenceDialog.getCompressionTypeProperty());
    }
}