package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TilingRequest;
import qupath.ext.qpsc.utilities.TilingUtilities;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper class for tile management operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Creating tiles based on camera field of view</li>
 *   <li>Validating tile counts to prevent excessive tiling</li>
 *   <li>Cleaning up old tiles from previous runs</li>
 *   <li>Managing tile directories and organization</li>
 * </ul>
 *
 * <p>Tiles represent individual camera frames that will be acquired and
 * later stitched together to form the complete image.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class TileHelper {
    private static final Logger logger = LoggerFactory.getLogger(TileHelper.class);

    /** Maximum tiles allowed per annotation to prevent runaway tiling */
    private static final int MAX_TILES_PER_ANNOTATION = 10000;

    /**
     * Creates tiles for the given annotations.
     * UPDATED: Now delegates to unified method in TilingUtilities.
     *
     * @param annotations List of annotations to tile
     * @param sample Sample setup information
     * @param tempTileDirectory Directory for tile configuration files
     * @param modeWithIndex Imaging mode identifier
     * @param macroPixelSize Macro image pixel size (no longer used)
     */
    public static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize) {

        logger.info("Creating tiles for {} annotations in modality {}",
                annotations.size(), modeWithIndex);

        try {
            // Delegate to the unified method in TilingUtilities
            TilingUtilities.createTilesForAnnotations(
                    annotations,
                    sample,
                    tempTileDirectory,
                    modeWithIndex
            );

        } catch (IOException e) {
            logger.error("Failed to get camera FOV from server", e);
            throw new RuntimeException(
                    "Failed to get camera FOV from server: " + e.getMessage() +
                            "\nPlease check server connection.", e);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid tile configuration", e);
            throw new RuntimeException(
                    "Invalid tile configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Creates tiles for the given annotations with explicit flip parameters.
     *
     * <p>This overload is used when the flip status should come from image metadata
     * rather than global preferences, such as when working with existing flipped images.</p>
     *
     * @param annotations List of annotations to tile
     * @param sample Sample setup information
     * @param tempTileDirectory Directory for tile configuration files
     * @param modeWithIndex Imaging mode identifier
     * @param macroPixelSize Macro image pixel size (no longer used)
     * @param invertedX Whether to invert X axis (from image metadata)
     * @param invertedY Whether to invert Y axis (from image metadata)
     */
    public static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        logger.info("Creating tiles for {} annotations in modality {} (invertX={}, invertY={})",
                annotations.size(), modeWithIndex, invertedX, invertedY);

        try {
            // Delegate to the unified method in TilingUtilities with explicit flip params
            TilingUtilities.createTilesForAnnotations(
                    annotations,
                    sample,
                    tempTileDirectory,
                    modeWithIndex,
                    invertedX,
                    invertedY
            );

        } catch (IOException e) {
            logger.error("Failed to get camera FOV from server", e);
            throw new RuntimeException(
                    "Failed to get camera FOV from server: " + e.getMessage() +
                            "\nPlease check server connection.", e);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid tile configuration", e);
            throw new RuntimeException(
                    "Invalid tile configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that tile counts are reasonable.
     *
     * <p>Prevents creation of excessive tiles that could indicate:
     * <ul>
     *   <li>Incorrect pixel size configuration</li>
     *   <li>Extremely large annotations</li>
     *   <li>Configuration errors</li>
     * </ul>
     *
     * @param annotations Annotations to validate
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @param overlapPercent Overlap percentage
     * @throws RuntimeException if any annotation would create too many tiles
     */
    private static void validateTileCounts(List<PathObject> annotations,
                                           double frameWidth, double frameHeight, double overlapPercent) {

        for (PathObject ann : annotations) {
            if (ann.getROI() != null) {
                double annWidth = ann.getROI().getBoundsWidth();
                double annHeight = ann.getROI().getBoundsHeight();

                // Calculate effective frame size considering overlap
                double effectiveFrameWidth = frameWidth * (1 - overlapPercent/100.0);
                double effectiveFrameHeight = frameHeight * (1 - overlapPercent/100.0);

                double tilesX = Math.ceil(annWidth / effectiveFrameWidth);
                double tilesY = Math.ceil(annHeight / effectiveFrameHeight);
                double totalTiles = tilesX * tilesY;

                if (totalTiles > MAX_TILES_PER_ANNOTATION) {
                    throw new RuntimeException(String.format(
                            "Annotation '%s' would require %.0f tiles (%.0fx%.0f). Maximum allowed is %d.\n" +
                                    "This usually indicates incorrect pixel size settings.\n" +
                                    "Annotation size: %.0fx%.0f pixels, Frame size: %.0fx%.0f pixels",
                            ann.getName(), totalTiles, tilesX, tilesY, MAX_TILES_PER_ANNOTATION,
                            annWidth, annHeight, frameWidth, frameHeight));
                }

                logger.debug("Annotation '{}' will create {} tiles ({}x{})",
                        ann.getName(), totalTiles, tilesX, tilesY);
            }
        }
    }

    /**
     * Deletes all detection tiles for a given modality.
     *
     * <p>Removes existing tiles from the QuPath hierarchy to prepare for
     * new tile creation. Uses efficient batch removal for large tile counts.
     *
     * @param gui QuPath GUI instance
     * @param modality Base modality name (without index suffix)
     */
    public static void deleteAllTiles(QuPathGUI gui, String modality) {
        var hierarchy = gui.getViewer().getHierarchy();
        int totalDetections = hierarchy.getDetectionObjects().size();

        String modalityBase = modality.replaceAll("(_\\d+)$", "");

        // Find tiles to remove based on class name
        List<PathObject> tilesToRemove = hierarchy.getDetectionObjects().stream()
                .filter(o -> o.getPathClass() != null &&
                        o.getPathClass().toString().contains(modalityBase))
                .collect(Collectors.toList());

        if (!tilesToRemove.isEmpty()) {
            logger.info("Removing {} of {} total detections for modality: {}",
                    tilesToRemove.size(), totalDetections, modalityBase);

            // Use batch removal for performance
            if (tilesToRemove.size() > totalDetections * 0.8) {
                // If removing most detections, it's faster to clear all and re-add the rest
                List<PathObject> toKeep = hierarchy.getDetectionObjects().stream()
                        .filter(o -> !tilesToRemove.contains(o))
                        .collect(Collectors.toList());

                QP.removeDetections();
                if (!toKeep.isEmpty()) {
                    hierarchy.addObjects(toKeep);
                }
            } else {
                // Remove specific objects
                hierarchy.removeObjects(tilesToRemove, true);
            }
        }
    }

    /**
     * Cleans up stale folders from previous tile creation.
     *
     * <p>Removes directories for annotations that no longer exist to prevent
     * confusion and save disk space.
     *
     * @param tempTileDirectory Root temporary tile directory
     * @param currentAnnotations List of current valid annotations
     */
    public static void cleanupStaleFolders(String tempTileDirectory,
                                           List<PathObject> currentAnnotations) {

        try {
            File tempDir = new File(tempTileDirectory);
            if (!tempDir.exists() || !tempDir.isDirectory()) {
                return;
            }

            // Get current annotation names
            Set<String> currentNames = currentAnnotations.stream()
                    .map(PathObject::getName)
                    .filter(name -> name != null && !name.trim().isEmpty())
                    .collect(Collectors.toSet());

            // Check each subdirectory
            File[] subdirs = tempDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    String dirName = subdir.getName();

                    // Skip special directories
                    if ("bounds".equals(dirName)) {
                        continue;
                    }

                    // Remove if not in current annotations
                    if (!currentNames.contains(dirName)) {
                        logger.info("Removing stale folder: {}", dirName);
                        deleteDirectoryRecursively(subdir);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error cleaning up stale folders", e);
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param dir Directory to delete
     */
    private static void deleteDirectoryRecursively(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        if (!dir.delete()) {
            logger.warn("Failed to delete: {}", dir.getAbsolutePath());
        }
    }
}