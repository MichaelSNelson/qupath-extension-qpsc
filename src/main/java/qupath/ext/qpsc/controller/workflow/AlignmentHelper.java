// File: qupath/ext/qpsc/controller/workflow/AlignmentHelper.java
package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Helper class for alignment-related operations.
 */
public class AlignmentHelper {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentHelper.class);

    /**
     * Checks for existing slide-specific alignment and prompts user.
     */
    public static CompletableFuture<AffineTransform> checkForSlideAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        logger.info("Checking for slide-specific alignment for sample: {}", sample.sampleName());

        // Try to load slide-specific alignment
        AffineTransform slideTransform = null;

        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            slideTransform = AffineTransformManager.loadSlideAlignment(project, sample.sampleName());
        } else {
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(
                        projectDir, sample.sampleName());
            }
        }

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment");
            AffineTransform finalTransform = slideTransform;

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
                    future.complete(finalTransform);
                } else {
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



