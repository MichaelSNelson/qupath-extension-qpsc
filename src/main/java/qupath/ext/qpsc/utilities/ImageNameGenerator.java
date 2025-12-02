package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * ImageNameGenerator
 *
 * <p>Generates user-friendly image filenames based on configured preferences.
 * Replaces the hard-coded naming scheme with a flexible, preference-based system.
 *
 * <p>Default naming pattern: {@code SampleName_001.extension}
 *
 * <p>Users can optionally include additional components via preferences:
 * <ul>
 *   <li>Objective/Magnification (e.g., "20x")
 *   <li>Modality (e.g., "ppm", "bf")
 *   <li>Annotation name
 *   <li>Angle (for multi-angle acquisitions)
 * </ul>
 *
 * <p>All information is stored in QuPath metadata regardless of filename configuration.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ImageNameGenerator {
    private static final Logger logger = LoggerFactory.getLogger(ImageNameGenerator.class);

    /**
     * Generates a filename based on user preferences and provided metadata.
     *
     * <p><b>Important:</b> The imageIndex increments per acquisition/annotation, NOT per angle.
     * For multi-angle modalities like PPM:
     * <ul>
     *   <li>Acquisition 1, angle 7.0: {@code SampleName_001_7.0.ome.zarr}
     *   <li>Acquisition 1, angle -7.0: {@code SampleName_001_-7.0.ome.zarr}
     *   <li>Acquisition 2, angle 7.0: {@code SampleName_002_7.0.ome.zarr}
     * </ul>
     *
     * @param sampleName The sample name (required)
     * @param imageIndex The image index number - increments per acquisition/annotation, not per angle
     * @param modality The imaging modality (e.g., "ppm", "bf")
     * @param objective The objective/magnification (e.g., "20x", "10x")
     * @param annotationName The annotation name (null if not applicable)
     * @param angle The angle for multi-angle acquisitions (null if not applicable)
     * @param extension The file extension (e.g., ".ome.tif", ".ome.zarr")
     * @return The generated filename
     */
    public static String generateImageName(
            String sampleName,
            int imageIndex,
            String modality,
            String objective,
            String annotationName,
            String angle,
            String extension) {

        if (sampleName == null || sampleName.isEmpty()) {
            logger.warn("Sample name is null or empty, using 'Unknown'");
            sampleName = "Unknown";
        }

        if (extension == null || extension.isEmpty()) {
            extension = ".ome.tif";
        }

        StringBuilder nameBuilder = new StringBuilder();

        // Always start with sample name
        nameBuilder.append(sampleName);

        // Add components based on preferences
        if (QPPreferenceDialog.getFilenameIncludeModality() && modality != null && !modality.isEmpty()) {
            nameBuilder.append("_").append(modality);
        }

        if (QPPreferenceDialog.getFilenameIncludeObjective() && objective != null && !objective.isEmpty()) {
            nameBuilder.append("_").append(objective);
        }

        if (QPPreferenceDialog.getFilenameIncludeAnnotation() && annotationName != null && !annotationName.isEmpty()) {
            // Sanitize annotation name to remove path separators
            String sanitized = sanitizeForFilename(annotationName);
            if (!sanitized.equals("bounds")) {  // Skip "bounds" as it's the default
                nameBuilder.append("_").append(sanitized);
            }
        }

        if (QPPreferenceDialog.getFilenameIncludeAngle() && angle != null && !angle.isEmpty()) {
            nameBuilder.append("_").append(angle);
        }

        // Always append the index (formatted as 3-digit number)
        nameBuilder.append("_").append(String.format("%03d", imageIndex));

        // Add extension
        nameBuilder.append(extension);

        String generatedName = nameBuilder.toString();
        logger.debug("Generated image name: {} (modality={}, objective={}, annotation={}, angle={}, index={})",
                generatedName, modality, objective, annotationName, angle, imageIndex);

        return generatedName;
    }

    /**
     * Sanitizes a string for use in filenames by replacing invalid characters with underscores.
     * Uses the same pattern as QuPath's GeneralTools.stripInvalidFilenameChars() but replaces
     * instead of removing characters for better readability.
     *
     * @param input The input string
     * @return The sanitized string safe for use in filenames
     */
    public static String sanitizeForFilename(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // Replace invalid filename characters with underscores
        // Pattern matches GeneralTools but we replace instead of remove
        // Includes: / \ : * ? " < > | newlines carriage-returns
        return input.replaceAll("[\\\\/:\"*?<>|\\n\\r]+", "_");
    }

    /**
     * Extracts modality and objective from a combined imaging mode string.
     * Example: "ppm_20x" -> ["ppm", "20x"]
     *
     * @param imagingMode The imaging mode string (e.g., "ppm_20x", "bf_10x")
     * @return Array of [modality, objective], or [imagingMode, null] if no separator found
     */
    public static String[] parseImagingMode(String imagingMode) {
        if (imagingMode == null || imagingMode.isEmpty()) {
            return new String[]{null, null};
        }

        // Remove any trailing index (e.g., "ppm_20x_1" -> "ppm_20x")
        String withoutIndex = imagingMode.replaceAll("_\\d+$", "");

        // Split on underscore
        String[] parts = withoutIndex.split("_", 2);
        if (parts.length == 2) {
            return parts;  // [modality, objective]
        } else {
            return new String[]{withoutIndex, null};  // No separator found
        }
    }

    /**
     * Extracts the image index from an imaging mode string.
     * Example: "ppm_20x_1" -> 1, "bf_10x_3" -> 3
     *
     * @param imagingMode The imaging mode string with index
     * @return The index number, or 1 if not found
     */
    public static int extractImageIndex(String imagingMode) {
        if (imagingMode == null || imagingMode.isEmpty()) {
            return 1;
        }

        // Look for trailing "_number" pattern
        String[] parts = imagingMode.split("_");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            try {
                return Integer.parseInt(lastPart);
            } catch (NumberFormatException e) {
                // Not a number, return default
                return 1;
            }
        }

        return 1;
    }
}
