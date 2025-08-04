// File: qupath/ext/qpsc/controller/workflow/ProjectHelper.java
package qupath.ext.qpsc.controller.workflow;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for project setup operations.
 */
public class ProjectHelper {
    private static final Logger logger = LoggerFactory.getLogger(ProjectHelper.class);

    /**
     * Project setup information.
     */
    public static class ProjectInfo {
        private final Map<String, Object> details;

        public ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }

        public String getTempTileDirectory() {
            return (String) details.get("tempTileDirectory");
        }

        public String getImagingModeWithIndex() {
            return (String) details.get("imagingModeWithIndex");
        }

        public Object getCurrentProject() {
            return details.get("currentQuPathProject");
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }

    /**
     * Sets up or gets the QuPath project.
     */
    public static CompletableFuture<ProjectInfo> setupProject(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<ProjectInfo> future = new CompletableFuture<>();

        logger.info("Setting up project for sample: {}", sample.sampleName());

        Platform.runLater(() -> {
            try {
                Map<String, Object> projectDetails;
                boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();

                logger.debug("Flip settings - X: {}, Y: {}", flippedX, flippedY);

                if (gui.getProject() == null) {
                    logger.info("Creating new project");
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            gui,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality(),
                            flippedX,
                            flippedY
                    );

                    // Save macro dimensions if available
                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                    if (macroImage != null) {
                        projectDetails.put("macroWidth", macroImage.getWidth());
                        projectDetails.put("macroHeight", macroImage.getHeight());
                    }
                } else {
                    logger.info("Using existing project");
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality()
                    );

                    // Handle image import if needed
                    handleExistingProjectImageImport(gui, flippedX, flippedY);
                }

                logger.info("Project setup complete");

                // Give GUI time to update
                PauseTransition pause = new PauseTransition(Duration.millis(500));
                pause.setOnFinished(e -> future.complete(new ProjectInfo(projectDetails)));
                pause.play();

            } catch (Exception e) {
                logger.error("Failed to setup project", e);
                UIFunctions.notifyUserOfError(
                        "Failed to setup project: " + e.getMessage(),
                        "Project Error"
                );
                future.complete(null);
            }
        });

        return future;
    }

    private static void handleExistingProjectImageImport(QuPathGUI gui,
                                                         boolean flippedX, boolean flippedY) {

        var imageData = gui.getImageData();
        if (imageData != null && (flippedX || flippedY)) {
            ProjectImageEntry<BufferedImage> currentEntry = null;

            try {
                currentEntry = gui.getProject().getEntry(imageData);
            } catch (Exception e) {
                logger.debug("Could not get project entry for current image: {}", e.getMessage());
            }

            if (currentEntry == null) {
                logger.info("Current image not in project, adding with flips");
                String imagePath = MinorFunctions.extractFilePath(imageData.getServerPath());
                if (imagePath != null) {
                    try {
                        QPProjectFunctions.addImageToProject(
                                new File(imagePath),
                                gui.getProject(),
                                flippedX,
                                flippedY
                        );

                        gui.refreshProject();

                        // Reopen the image
                        var entries = gui.getProject().getImageList();
                        var newEntry = entries.stream()
                                .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                .findFirst()
                                .orElse(null);

                        if (newEntry != null) {
                            gui.openImageEntry(newEntry);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to add image to project", e);
                    }
                }
            }
        }
    }
}
