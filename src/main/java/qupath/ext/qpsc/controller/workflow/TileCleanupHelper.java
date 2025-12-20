package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;

import java.io.File;

/**
 * Helper for tile cleanup operations after acquisition.
 *
 * <p>This class manages the post-acquisition cleanup of temporary tile files
 * based on user preferences:
 * <ul>
 *   <li><b>Delete:</b> Remove all temporary tiles immediately</li>
 *   <li><b>Zip:</b> Archive tiles to a zip file then delete originals</li>
 *   <li><b>Keep:</b> Retain tiles for debugging or manual inspection</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class TileCleanupHelper {
    private static final Logger logger = LoggerFactory.getLogger(TileCleanupHelper.class);

    /**
     * Performs tile cleanup based on user preferences.
     *
     * <p>The cleanup method is determined by the tile handling preference:
     * <ul>
     *   <li>"Delete" - Removes all tiles, subdirectories, and the temporary folder</li>
     *   <li>"Zip" - Creates a zip archive then removes originals (only deletes if zip succeeds)</li>
     *   <li>Any other value - Keeps tiles in place</li>
     * </ul>
     *
     * @param tempTileDir Path to the temporary tile directory
     */
    public static void performCleanup(String tempTileDir) {
        String handling = QPPreferenceDialog.getTileHandlingMethodProperty();

        logger.info("Performing tile cleanup - method: {}, path: {}", handling, tempTileDir);

        // Validate the path exists
        File tileDir = new File(tempTileDir);
        if (!tileDir.exists()) {
            logger.warn("Tile directory does not exist, nothing to clean up: {}", tempTileDir);
            return;
        }

        if (!tileDir.isDirectory()) {
            logger.warn("Path is not a directory: {}", tempTileDir);
            return;
        }

        if ("Delete".equals(handling)) {
            logger.info("Deleting all tiles and subdirectories in: {}", tempTileDir);
            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
        } else if ("Zip".equals(handling)) {
            logger.info("Zipping tiles before deletion: {}", tempTileDir);
            boolean zipSuccess = TileProcessingUtilities.zipTilesAndMove(tempTileDir);
            if (zipSuccess) {
                logger.info("Zip successful, now deleting original tiles");
                TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                logger.info("Zipped and archived temporary tiles from: {}", tempTileDir);
            } else {
                logger.error("Zip failed - keeping original tiles for safety");
            }
        } else {
            logger.info("Keeping temporary tiles at: {}", tempTileDir);
        }
    }
}