// File: qupath/ext/qpsc/controller/workflow/TileHelper.java
package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
public class TileHelper {
    private static final Logger logger = LoggerFactory.getLogger(TileHelper.class);
    private static final int MAX_TILES_PER_ANNOTATION = 10000;

    /**
     * Creates tiles for the given annotations.
     */
    public static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize) {

        logger.info("Creating tiles for {} annotations in modality {}",
                annotations.size(), modeWithIndex);

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
        int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
        int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

        // Get the actual image pixel size
        QuPathGUI gui = QuPathGUI.getInstance();
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        // Calculate frame dimensions in microns
        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to image pixels
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        logger.info("Frame size in QuPath pixels: {} x {}", frameWidthPixels, frameHeightPixels);

        try {
            // Remove existing tiles
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            deleteAllTiles(gui, modalityBase);

            // Validate tile counts
            validateTileCounts(annotations, frameWidthPixels, frameHeightPixels, overlapPercent);

            // Create new tiles
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthPixels, frameHeightPixels)
                    .overlapPercent(overlapPercent)
                    .annotations(annotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created tiles for {} annotations", annotations.size());

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            throw new RuntimeException("Failed to create tiles: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that tile counts are reasonable.
     */
    private static void validateTileCounts(List<PathObject> annotations,
                                           double frameWidth, double frameHeight, double overlapPercent) {

        for (PathObject ann : annotations) {
            if (ann.getROI() != null) {
                double annWidth = ann.getROI().getBoundsWidth();
                double annHeight = ann.getROI().getBoundsHeight();
                double tilesX = Math.ceil(annWidth / (frameWidth * (1 - overlapPercent/100.0)));
                double tilesY = Math.ceil(annHeight / (frameHeight * (1 - overlapPercent/100.0)));
                double totalTiles = tilesX * tilesY;

                if (totalTiles > MAX_TILES_PER_ANNOTATION) {
                    throw new RuntimeException(String.format(
                            "Annotation '%s' would require %.0f tiles. Maximum allowed is %d.\n" +
                                    "This usually indicates incorrect pixel size settings.",
                            ann.getName(), totalTiles, MAX_TILES_PER_ANNOTATION));
                }
            }
        }
    }

    /**
     * Deletes all detection tiles for a given modality.
     */
    public static void deleteAllTiles(QuPathGUI gui, String modality) {
        var hierarchy = gui.getViewer().getHierarchy();
        int totalDetections = hierarchy.getDetectionObjects().size();

        String modalityBase = modality.replaceAll("(_\\d+)$", "");

        List<PathObject> tilesToRemove = hierarchy.getDetectionObjects().stream()
                .filter(o -> o.getPathClass() != null &&
                        o.getPathClass().toString().contains(modalityBase))
                .collect(Collectors.toList());

        if (!tilesToRemove.isEmpty()) {
            logger.info("Removing {} of {} total detections for modality: {}",
                    tilesToRemove.size(), totalDetections, modalityBase);

            // Batch removal for performance
            if (tilesToRemove.size() > totalDetections * 0.8) {
                List<PathObject> toKeep = hierarchy.getDetectionObjects().stream()
                        .filter(o -> !tilesToRemove.contains(o))
                        .collect(Collectors.toList());

                QP.removeDetections();
                if (!toKeep.isEmpty()) {
                    hierarchy.addObjects(toKeep);
                }
            } else {
                hierarchy.removeObjects(tilesToRemove, true);
            }
        }
    }

    /**
     * Cleans up stale folders from previous tile creation.
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
