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
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for QuPath project setup operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Creating new projects or using existing ones</li>
 *   <li>Importing images with appropriate flip settings</li>
 *   <li>Setting up temporary tile directories</li>
 *   <li>Managing project metadata</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ProjectHelper {
    private static final Logger logger = LoggerFactory.getLogger(ProjectHelper.class);

    /**
     * Container for project setup information.
     *
     * <p>Contains all necessary paths and identifiers for the acquisition workflow.
     */
    public static class ProjectInfo {
        private final Map<String, Object> details;

        /**
         * Creates a new project info container.
         *
         * @param details Map containing project details
         */
        public ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }

        /**
         * Gets the temporary directory for tile storage.
         *
         * @return Path to temporary tile directory
         */
        public String getTempTileDirectory() {
            return (String) details.get("tempTileDirectory");
        }

        /**
         * Gets the imaging mode with index suffix.
         *
         * @return Imaging mode string (e.g., "Brightfield_1")
         */
        public String getImagingModeWithIndex() {
            return (String) details.get("imagingModeWithIndex");
        }

        /**
         * Gets the current QuPath project object.
         *
         * @return The QuPath project
         */
        public Object getCurrentProject() {
            return details.get("currentQuPathProject");
        }

        /**
         * Gets all project details.
         *
         * @return Map of all project details
         */
        public Map<String, Object> getDetails() {
            return details;
        }
    }

    /**
     * Sets up or retrieves the QuPath project.
     *
     * <p>This method:
     * <ol>
     *   <li>Creates a new project if none exists</li>
     *   <li>Uses existing project if available</li>
     *   <li>Imports the current image with flip settings</li>
     *   <li>Sets up tile directories</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information
     * @return CompletableFuture containing project information
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
                    
                    // Use enhanced modality name for consistent folder structure
                    String enhancedModality = ObjectiveUtils.createEnhancedFolderName(
                            sample.modality(), sample.objective());
                    logger.info("Using enhanced modality for project: {} -> {}", 
                            sample.modality(), enhancedModality);
                    
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            gui,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            enhancedModality,
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
                    
                    // Use enhanced modality name for consistent folder structure  
                    String enhancedModality = ObjectiveUtils.createEnhancedFolderName(
                            sample.modality(), sample.objective());
                    logger.info("Using enhanced modality for existing project: {} -> {}", 
                            sample.modality(), enhancedModality);
                    
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            enhancedModality
                    );

                    // Handle image import if needed
                    handleExistingProjectImageImport(gui, flippedX, flippedY);
                }

                logger.info("Project setup complete");

                // Give GUI time to update before proceeding
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

    /**
     * Handles image import for existing projects.
     *
     * <p>If the current image is not in the project, imports it with
     * appropriate flip settings.
     *
     * @param gui QuPath GUI instance
     * @param flippedX Whether to flip X axis
     * @param flippedY Whether to flip Y axis
     */
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

                        // Reopen the image with flips applied
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