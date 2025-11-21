package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for alignment-related operations in the workflow.
 *
 * <p>This class provides utilities for:
 * <ul>
 *   <li>Checking for existing slide-specific alignments</li>
 *   <li>Prompting users about alignment reuse</li>
 *   <li>Loading saved alignment transforms</li>
 * </ul>
 *
 * <p>Slide-specific alignments allow users to skip the alignment process if they've
 * already aligned this specific slide in a previous session.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AlignmentHelper {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentHelper.class);

    /**
     * Result from checking for existing slide alignment.
     */
    public static class SlideAlignmentResult {
        private final AffineTransform transform;
        private final boolean refineRequested;

        public SlideAlignmentResult(AffineTransform transform, boolean refineRequested) {
            this.transform = transform;
            this.refineRequested = refineRequested;
        }

        public AffineTransform getTransform() {
            return transform;
        }

        public boolean isRefineRequested() {
            return refineRequested;
        }
    }

    /**
     * Checks for existing slide-specific alignment and prompts user.
     *
     * <p>This method:
     * <ol>
     *   <li>Attempts to load a saved alignment for the specific slide</li>
     *   <li>If found, prompts the user with three options: use as-is, refine with single tile, or start over</li>
     *   <li>Returns the result containing the transform and whether refinement was requested</li>
     * </ol>
     *
     * <p>The alignment is slide-specific, meaning it's saved per image name and includes
     * any refinements made during previous acquisitions.
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information including name and project location
     * @return CompletableFuture containing the SlideAlignmentResult or null if starting over
     */
    public static CompletableFuture<SlideAlignmentResult> checkForSlideAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<SlideAlignmentResult> future = new CompletableFuture<>();

        // Get the image name (without extension) from the current image
        String imageName = null;
        if (gui.getImageData() != null) {
            String fullImageName = gui.getImageData().getServer().getMetadata().getName();
            imageName = qupath.lib.common.GeneralTools.stripExtension(fullImageName);
        }

        if (imageName == null) {
            logger.warn("No image is currently open, cannot check for slide alignment");
            future.complete(null);
            return future;
        }

        logger.info("Checking for slide-specific alignment for image: {}", imageName);

        // Try to load slide-specific alignment using IMAGE name (not sample name)
        AffineTransform slideTransform = null;

        // First try from current project
        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);
        } else {
            // Try from project directory if no project is open
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(
                        projectDir, imageName);
            }
        }

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment");
            AffineTransform finalTransform = slideTransform;
            String finalImageName = imageName;  // Make final for lambda

            // Show dialog on JavaFX thread
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment Found");
                alert.setHeaderText("Found existing alignment for image '" + finalImageName + "'");
                alert.setContentText(
                        "An existing alignment was found for this image.\n\n" +
                                "How would you like to proceed?\n\n" +
                                "'Use Existing' - Proceed directly to acquisition with saved alignment\n" +
                                "'Refine Alignment' - Use single-tile refinement to improve accuracy\n" +
                                "'Start Over' - Ignore saved alignment and create new alignment"
                );

                ButtonType useExistingButton = new ButtonType("Use Existing", ButtonBar.ButtonData.YES);
                ButtonType refineButton = new ButtonType("Refine Alignment", ButtonBar.ButtonData.OK_DONE);
                ButtonType startOverButton = new ButtonType("Start Over", ButtonBar.ButtonData.NO);
                alert.getButtonTypes().setAll(useExistingButton, refineButton, startOverButton);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent()) {
                    if (result.get() == useExistingButton) {
                        logger.info("User chose to use existing alignment as-is");
                        future.complete(new SlideAlignmentResult(finalTransform, false));
                    } else if (result.get() == refineButton) {
                        logger.info("User chose to refine existing alignment with single tile");
                        future.complete(new SlideAlignmentResult(finalTransform, true));
                    } else {
                        logger.info("User chose to start over with new alignment");
                        future.complete(null);
                    }
                } else {
                    // Dialog closed without selection - treat as start over
                    logger.info("Dialog closed, starting over with new alignment");
                    future.complete(null);
                }
            });
        } else {
            logger.info("No slide-specific alignment found");
            future.complete(null);
        }

        return future;
    }
}