package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Helper class for image stitching operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Stitching acquired tiles into pyramidal OME-TIFF images</li>
 *   <li>Handling multi-angle acquisitions (e.g., polarized light)</li>
 *   <li>Updating the QuPath project with stitched images and metadata</li>
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
     * Container for metadata to be applied to stitched images.
     */
    public static class StitchingMetadata {
        public final ProjectImageEntry<BufferedImage> parentEntry;
        public final double xOffset;
        public final double yOffset;
        public final boolean isFlipped;
        public final String sampleName;

        public StitchingMetadata(ProjectImageEntry<BufferedImage> parentEntry,
                                 double xOffset, double yOffset,
                                 boolean isFlipped, String sampleName) {
            this.parentEntry = parentEntry;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.isFlipped = isFlipped;
            this.sampleName = sampleName;
        }
    }

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

        // Get transform from current controller
        AffineTransform fullResToStage = MicroscopeController.getInstance().getCurrentTransform();

        return performAnnotationStitching(
                annotation, sample, modeWithIndex, angleExposures,
                pixelSize, gui, project, executor, handler, fullResToStage
        );
    }

    /**
     * Performs stitching for a single annotation across all rotation angles.
     * Enhanced version that accepts transform for metadata calculation.
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
     * @param fullResToStage Transform from full-res pixels to stage coordinates
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
            ModalityHandler handler,
            AffineTransform fullResToStage) {

        // Calculate metadata for this annotation
        StitchingMetadata metadata = calculateMetadata(
                annotation, sample, gui, project, fullResToStage
        );

        if (angleExposures != null && angleExposures.size() > 1) {
            logger.info("Stitching {} angles for annotation: {}",
                    angleExposures.size(), annotation.getName());

            // For multi-angle acquisitions, do ONE batch stitch with "." as matching string
            return CompletableFuture.runAsync(() -> {
                try {
                    String annotationName = annotation.getName();

                    logger.info("Performing batch stitching for {} with {} angles",
                            annotationName, angleExposures.size());
                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    // Get compression type from preferences
                    String compression = String.valueOf(
                            QPPreferenceDialog.getCompressionTypeProperty());

                    // Create enhanced parameters map for UtilityFunctions
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);

                    // Perform ONE batch stitching operation with "." to process all angles
                    String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
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
                            handler,
                            stitchParams  // Pass metadata in parameters
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
                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    String compression = String.valueOf(
                            QPPreferenceDialog.getCompressionTypeProperty());

                    // Create enhanced parameters map
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);

                    // For non-angle acquisitions, use annotation name as matching string
                    String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
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
                            handler,
                            stitchParams  // Pass metadata
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

    /**
     * Calculates metadata for a stitched image based on its parent annotation.
     */
    private static StitchingMetadata calculateMetadata(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            QuPathGUI gui,
            Project<BufferedImage> project,
            AffineTransform fullResToStage) {

        // Get parent entry (the current open image)
        ProjectImageEntry<BufferedImage> parentEntry = project.getEntry(gui.getImageData());

        // Calculate offset from slide corner
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(
                annotation, fullResToStage);

        // Check flip status from parent or preferences
        boolean isFlipped = false;
        if (parentEntry != null) {
            isFlipped = ImageMetadataManager.isFlipped(parentEntry);
        } else {
            // If no parent, use preferences
            isFlipped = QPPreferenceDialog.getFlipMacroXProperty() ||
                    QPPreferenceDialog.getFlipMacroYProperty();
        }

        return new StitchingMetadata(
                parentEntry,
                offset[0],
                offset[1],
                isFlipped,
                sample.sampleName()
        );
    }
}