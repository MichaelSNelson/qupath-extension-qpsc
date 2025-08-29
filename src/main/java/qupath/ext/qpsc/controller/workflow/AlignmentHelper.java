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
     * Checks for existing slide-specific alignment and prompts user.
     *
     * <p>This method:
     * <ol>
     *   <li>Attempts to load a saved alignment for the specific slide</li>
     *   <li>If found, prompts the user to reuse or create new alignment</li>
     *   <li>Returns the loaded transform or null if user chooses new alignment</li>
     * </ol>
     *
     * <p>The alignment is slide-specific, meaning it's saved per sample name and includes
     * any refinements made during previous acquisitions.
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information including name and project location
     * @return CompletableFuture containing the loaded transform or null
     */
    public static CompletableFuture<AffineTransform> checkForSlideAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        logger.info("Checking for slide-specific alignment for sample: {}", sample.sampleName());

        // Try to load slide-specific alignment
        AffineTransform slideTransform = null;

        // First try from current project
        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            slideTransform = AffineTransformManager.loadSlideAlignment(project, sample.sampleName());
        } else {
            // Try from project directory if no project is open
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(
                        projectDir, sample.sampleName());
            }
        }

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment");
            AffineTransform finalTransform = slideTransform;

            // Show dialog on JavaFX thread
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment Found");
                alert.setHeaderText("Found existing alignment for slide '" + sample.sampleName() + "'");
                alert.setContentText(
                        "Would you like to use the existing slide alignment?\n\n" +
                                "Choose 'Yes' to proceed directly to acquisition.\n" +
                                "Choose 'No' to select a different alignment or create a new one."
                );

                ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
                ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
                alert.getButtonTypes().setAll(yesButton, noButton);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == yesButton) {
                    logger.info("User chose to reuse existing alignment");
                    future.complete(finalTransform);
                } else {
                    logger.info("User chose to create new alignment");
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