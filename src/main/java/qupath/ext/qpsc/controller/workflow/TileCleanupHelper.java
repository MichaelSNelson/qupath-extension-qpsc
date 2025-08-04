// File: qupath/ext/qpsc/controller/workflow/TileCleanupHelper.java
package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.UtilityFunctions;

/**
 * Helper for tile cleanup operations.
 */
public class TileCleanupHelper {
    private static final Logger logger = LoggerFactory.getLogger(TileCleanupHelper.class);

    /**
     * Performs tile cleanup based on user preferences.
     */
    public static void performCleanup(String tempTileDir) {
        String handling = QPPreferenceDialog.getTileHandlingMethodProperty();

        logger.info("Performing tile cleanup - method: {}", handling);

        if ("Delete".equals(handling)) {
            UtilityFunctions.deleteTilesAndFolder(tempTileDir);
            logger.info("Deleted temporary tiles");
        } else if ("Zip".equals(handling)) {
            UtilityFunctions.zipTilesAndMove(tempTileDir);
            UtilityFunctions.deleteTilesAndFolder(tempTileDir);
            logger.info("Zipped and archived temporary tiles");
        } else {
            logger.info("Keeping temporary tiles");
        }
    }
}