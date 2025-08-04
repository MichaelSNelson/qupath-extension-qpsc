
// File: qupath/ext/qpsc/controller/workflow/StitchingHelper.java
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
 * Helper class for stitching operations.
 */
public class StitchingHelper {
    private static final Logger logger = LoggerFactory.getLogger(StitchingHelper.class);

    /**
     * Performs stitching for a single annotation across all rotation angles.
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

            // Stitch each angle sequentially
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
            // Single stitch
            return stitchSingleAngle(
                    annotation, null, sample,
                    modeWithIndex, pixelSize, gui, project, executor
            );
        }
    }

    /**
     * Stitches a single angle for an annotation.
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
                String matchingString = angle != null ? String.valueOf(angle) : annotationName;

                logger.info("Stitching {} for modality {} (angle: {})",
                        annotationName, modeWithIndex, angle);

                String compression = String.valueOf(
                        QPPreferenceDialog.getCompressionTypeProperty());

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
                        1  // downsample
                );

                logger.info("Stitching completed for {} (angle: {}), output: {}",
                        annotationName, angle, outPath);

            } catch (Exception e) {
                logger.error("Stitching failed for {} (angle: {})",
                        annotation.getName(), angle, e);

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
