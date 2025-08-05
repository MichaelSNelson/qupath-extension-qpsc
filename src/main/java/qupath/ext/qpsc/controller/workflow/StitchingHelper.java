package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * stitches each angle separately and sequentially.
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param rotationAngles List of rotation angles (null for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            List<Double> rotationAngles,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor) {

        if (rotationAngles != null && !rotationAngles.isEmpty()) {
            logger.info("Stitching {} angles for annotation: {}",
                    rotationAngles.size(), annotation.getName());

            // Stitch each angle sequentially to manage resources
            CompletableFuture<Void> stitchChain = CompletableFuture.completedFuture(null);

            for (Double angle : rotationAngles) {
                stitchChain = stitchChain.thenCompose(v ->
                        stitchSingleAngle(
                                annotation, angle, sample,
                                modeWithIndex, pixelSize, gui, project, executor
                        )
                );
            }

            return stitchChain;
        } else {
            // Single stitch for non-rotational acquisition
            return stitchSingleAngle(
                    annotation, null, sample,
                    modeWithIndex, pixelSize, gui, project, executor
            );
        }
    }

    /**
     * Stitches a single angle for an annotation.
     *
     * <p>This method:
     * <ol>
     *   <li>Determines the matching string for tile selection</li>
     *   <li>Calls the stitching utility with appropriate parameters</li>
     *   <li>Updates the QuPath project with the result</li>
     *   <li>Handles errors with user notification</li>
     * </ol>
     *
     * @param annotation The annotation to stitch
     * @param angle Rotation angle (null for non-rotational)
     * @param sample Sample information
     * @param modeWithIndex Imaging mode identifier
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project
     * @param executor Executor for async execution
     * @return CompletableFuture that completes when stitching is done
     */
    private static CompletableFuture<Void> stitchSingleAngle(
            PathObject annotation,
            Double angle,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor) {

        return CompletableFuture.runAsync(() -> {
            try {
                String annotationName = annotation.getName();
                // For multi-angle, use angle as matching string; otherwise use annotation name
                String matchingString = angle != null ? String.valueOf(angle) : annotationName;

                logger.info("Stitching {} for modality {} (angle: {})",
                        annotationName, modeWithIndex, angle);

                // Get compression type from preferences
                String compression = String.valueOf(
                        QPPreferenceDialog.getCompressionTypeProperty());

                // Perform stitching
                String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                        sample.projectsFolder().getAbsolutePath(),
                        sample.sampleName(),
                        modeWithIndex,
                        annotationName,
                        matchingString,
                        gui,
                        project,
                        compression,
                        pixelSize,
                        1  // downsample factor
                );

                logger.info("Stitching completed for {} (angle: {}), output: {}",
                        annotationName, angle, outPath);

            } catch (Exception e) {
                logger.error("Stitching failed for {} (angle: {})",
                        annotation.getName(), angle, e);

                // Notify user of error on JavaFX thread
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