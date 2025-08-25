package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Helper class for image stitching operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Stitching acquired tiles into pyramidal OME-TIFF images</li>
 *   <li>Handling multi-angle acquisitions (e.g., polarized light)</li>
 *   <li>Updating the QuPath project with stitched images</li>
 *   <li>Error handling and user notification</li>
 * </ul>
 *
 * <p>Stitching is performed asynchronously to avoid blocking the UI or
 * subsequent acquisitions.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StitchingHelper {
    private static final Logger logger = LoggerFactory.getLogger(StitchingHelper.class);

    /**
     * Performs stitching for a single annotation across all rotation angles.
     *
     * <p>For multi-angle acquisitions (e.g., polarized light), this method
     * performs a single batch stitching operation that processes all angles
     * at once. The BasicStitching extension will create separate output files
     * for each angle, which are then renamed and imported into the project.</p>
     *
     * <p>For single acquisitions without rotation angles, standard stitching
     * is performed using the annotation name as the matching pattern.</p>
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler) {

        if (angleExposures != null && angleExposures.size() > 1) {
            logger.info("Stitching {} angles for annotation: {}",
                    angleExposures.size(), annotation.getName());

            // For multi-angle acquisitions, do ONE batch stitch with "." as matching string
            return CompletableFuture.runAsync(() -> {
                try {
                    String annotationName = annotation.getName();

                    logger.info("Performing batch stitching for {} with {} angles",
                            annotationName, angleExposures.size());

                    // Get compression type from preferences
                    String compression = String.valueOf(
                            QPPreferenceDialog.getCompressionTypeProperty());

                    // Perform ONE batch stitching operation with "." to process all angles
                    String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName,
                            ".",  // Use "." to process ALL subdirectories at once
                            gui,
                            project,
                            compression,
                            pixelSize,
                            1,  // downsample factor
                            handler
                    );

                    logger.info("Batch stitching completed for {}, output: {}",
                            annotationName, outPath);

                } catch (Exception e) {
                    logger.error("Stitching failed for {}", annotation.getName(), e);
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError(
                                    String.format("Stitching failed for %s: %s",
                                            annotation.getName(), e.getMessage()),
                                    "Stitching Error"
                            )
                    );
                }
            }, executor);
        } else {
            // Single stitch for non-rotational acquisition (no angles)
            return CompletableFuture.runAsync(() -> {
                try {
                    String annotationName = annotation.getName();

                    logger.info("Stitching single acquisition for {}", annotationName);

                    String compression = String.valueOf(
                            QPPreferenceDialog.getCompressionTypeProperty());

                    // For non-angle acquisitions, use annotation name as matching string
                    String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName,
                            annotationName,  // Use annotation name as matching string
                            gui,
                            project,
                            compression,
                            pixelSize,
                            1,
                            handler
                    );

                    logger.info("Stitching completed for {}, output: {}",
                            annotationName, outPath);

                } catch (Exception e) {
                    logger.error("Stitching failed for {}", annotation.getName(), e);
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError(
                                    String.format("Stitching failed for %s: %s",
                                            annotation.getName(), e.getMessage()),
                                    "Stitching Error"
                            )
                    );
                }
            }, executor);
        }
    }

}