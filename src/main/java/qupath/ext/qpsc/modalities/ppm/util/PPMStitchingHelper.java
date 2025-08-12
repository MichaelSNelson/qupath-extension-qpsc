package qupath.ext.qpsc.modalities.ppm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modalities.ppm.PPMConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for PPM-specific stitching operations.
 * Handles the unique directory structure created by PPM acquisitions
 * where tiles are organized in angle-based subdirectories.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMStitchingHelper {
    private static final Logger logger = LoggerFactory.getLogger(PPMStitchingHelper.class);

    /**
     * Expected directory structure for PPM acquisitions:
     * <pre>
     * projectsFolder/
     *   sampleLabel/
     *     modalityWithIndex/
     *       annotationName/
     *         -5.0/           (minus angle subfolder)
     *           tile_001.tif
     *           tile_002.tif
     *           TileConfiguration.txt
     *         0.0/            (zero angle subfolder)
     *           tile_001.tif
     *           tile_002.tif
     *           TileConfiguration.txt
     *         5.0/            (plus angle subfolder)
     *           ...
     *         90.0/           (brightfield subfolder)
     *           ...
     * </pre>
     */

    /**
     * Verifies that the expected angle subdirectories exist for PPM acquisition.
     *
     * @param annotationPath Path to the annotation directory
     * @param expectedAngles List of expected angle values in ticks
     * @return List of missing angle directories, empty if all present
     */
    public static List<String> verifyAngleDirectories(Path annotationPath, List<Double> expectedAngles) {
        List<String> missingDirs = new ArrayList<>();

        for (Double angle : expectedAngles) {
            String angleDirName = formatAngleAsDirectoryName(angle);
            Path angleDir = annotationPath.resolve(angleDirName);

            if (!angleDir.toFile().exists()) {
                missingDirs.add(angleDirName);
                logger.warn("Missing expected angle directory: {}", angleDir);
            } else {
                // Check if TileConfiguration.txt exists
                Path tileConfig = angleDir.resolve("TileConfiguration.txt");
                if (!tileConfig.toFile().exists()) {
                    logger.warn("Missing TileConfiguration.txt in: {}", angleDir);
                }
            }
        }

        return missingDirs;
    }

    /**
     * Formats an angle value as a directory name.
     * This matches the format used by the Python acquisition script.
     *
     * @param angleTicks Angle in ticks
     * @return Directory name (e.g., "-5.0", "0.0", "5.0", "90.0")
     */
    public static String formatAngleAsDirectoryName(double angleTicks) {
        // Format to one decimal place to match Python script output
        return String.format("%.1f", angleTicks);
    }

    /**
     * Gets the expected output filename for a stitched PPM image.
     *
     * @param sampleLabel Sample name
     * @param modalityWithIndex Modality with index (e.g., "PPM_10x_1")
     * @param annotationName Annotation name or "bounds"
     * @param angleTicks Angle in ticks
     * @return Expected filename (e.g., "Sample1_PPM_10x_1_Tissue_12345_-5.0.ome.tif")
     */
    public static String getStitchedFilename(String sampleLabel, String modalityWithIndex,
                                             String annotationName, double angleTicks) {
        String angleDir = formatAngleAsDirectoryName(angleTicks);

        // For "bounds" annotation, don't include it in the filename
        if ("bounds".equals(annotationName)) {
            return String.format("%s_%s_%s.ome.tif",
                    sampleLabel, modalityWithIndex, angleDir);
        } else {
            return String.format("%s_%s_%s_%s.ome.tif",
                    sampleLabel, modalityWithIndex, annotationName, angleDir);
        }
    }

    /**
     * Finds all angle subdirectories under an annotation folder.
     *
     * @param annotationPath Path to the annotation directory
     * @return List of angle values found as subdirectories
     */
    public static List<Double> findAngleDirectories(Path annotationPath) {
        List<Double> angles = new ArrayList<>();
        File annotationDir = annotationPath.toFile();

        if (!annotationDir.exists() || !annotationDir.isDirectory()) {
            logger.warn("Annotation directory does not exist: {}", annotationPath);
            return angles;
        }

        File[] subdirs = annotationDir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                try {
                    // Try to parse directory name as angle
                    double angle = Double.parseDouble(subdir.getName());
                    angles.add(angle);
                    logger.debug("Found angle directory: {} ticks", angle);
                } catch (NumberFormatException e) {
                    // Not an angle directory, skip
                    logger.debug("Skipping non-angle directory: {}", subdir.getName());
                }
            }
        }

        angles.sort(Double::compareTo);
        logger.info("Found {} angle directories: {}", angles.size(), angles);
        return angles;
    }

    /**
     * Determines the stitching strategy for PPM acquisitions.
     *
     * @param angles List of angle values to stitch
     * @return "." for batch stitching all angles, or specific angle string for single angle
     */
    public static String getStitchingMatchPattern(List<Double> angles) {
        if (angles == null || angles.isEmpty()) {
            logger.warn("No angles provided for stitching");
            return ".";
        }

        if (angles.size() == 1) {
            // Single angle - use specific directory name
            return formatAngleAsDirectoryName(angles.get(0));
        } else {
            // Multiple angles - use batch mode
            return ".";
        }
    }

    /**
     * Logs the expected file structure for debugging.
     *
     * @param projectsFolder Root projects folder
     * @param sampleLabel Sample name
     * @param modalityWithIndex Modality with index
     * @param annotationName Annotation name
     * @param angles List of angles
     */
    public static void logExpectedStructure(String projectsFolder, String sampleLabel,
                                            String modalityWithIndex, String annotationName,
                                            List<Double> angles) {
        logger.info("Expected PPM directory structure:");
        logger.info("  Root: {}", projectsFolder);
        logger.info("  Sample: {}/{}", projectsFolder, sampleLabel);
        logger.info("  Modality: {}/{}/{}", projectsFolder, sampleLabel, modalityWithIndex);
        logger.info("  Annotation: {}/{}/{}/{}", projectsFolder, sampleLabel, modalityWithIndex, annotationName);

        for (Double angle : angles) {
            String angleDir = formatAngleAsDirectoryName(angle);
            logger.info("    Angle {}: {}/{}/{}/{}/{}",
                    PPMConfiguration.formatTicksForDisplay(angle),
                    projectsFolder, sampleLabel, modalityWithIndex, annotationName, angleDir);
        }
    }

    /**
     * Creates a human-readable description of the angle.
     *
     * @param angleTicks Angle in ticks
     * @return Description like "minus angle (-5.0°)" or "brightfield (90.0°)"
     */
    public static String getAngleDescription(double angleTicks) {
        double degrees = PPMConfiguration.ticksToAngle(angleTicks);

        if (angleTicks == 90.0) {
            return String.format("brightfield (%.1f°)", degrees);
        } else if (angleTicks < 0) {
            return String.format("minus angle (%.1f°)", degrees);
        } else if (angleTicks > 0) {
            return String.format("plus angle (%.1f°)", degrees);
        } else {
            return String.format("zero angle (%.1f°)", degrees);
        }
    }
}