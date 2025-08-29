package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;

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
     *   <li>"Delete" - Removes all tiles and the temporary folder</li>
     *   <li>"Zip" - Creates a zip archive then removes originals</li>
     *   <li>Any other value - Keeps tiles in place</li>
     * </ul>
     *
     * @param tempTileDir Path to the temporary tile directory
     */
    public static void performCleanup(String tempTileDir) {
        String handling = QPPreferenceDialog.getTileHandlingMethodProperty();

        logger.info("Performing tile cleanup - method: {}", handling);

        if ("Delete".equals(handling)) {
            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
            logger.info("Deleted temporary tiles at: {}", tempTileDir);
        } else if ("Zip".equals(handling)) {
            TileProcessingUtilities.zipTilesAndMove(tempTileDir);
            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
            logger.info("Zipped and archived temporary tiles from: {}", tempTileDir);
        } else {
            logger.info("Keeping temporary tiles at: {}", tempTileDir);
        }
    }
}