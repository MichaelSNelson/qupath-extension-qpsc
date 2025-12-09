package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper utility for managing image flipping in workflows.
 *
 * <p>This class handles the validation and creation of flipped image duplicates
 * when required by the microscope configuration preferences. It ensures that
 * the correct image orientation is loaded before subsequent workflow operations
 * (annotations, white space detection, tile creation, alignment) are performed.
 *
 * <p>The flipping process:
 * <ol>
 *   <li>Checks if flipping is required based on preferences</li>
 *   <li>Validates current image flip status</li>
 *   <li>Creates flipped duplicate if needed</li>
 *   <li>Loads the flipped image in QuPath</li>
 *   <li>Waits for load to complete with timeout handling</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 4.1
 */
public class ImageFlipHelper {
    private static final Logger logger = LoggerFactory.getLogger(ImageFlipHelper.class);

    /**
     * Validates image flip status and creates flipped duplicate if needed.
     *
     * <p>This method should be called after the project is created/opened and
     * before any operations that depend on the image orientation (annotations,
     * white space detection, tile creation, alignment refinement).
     *
     * @param gui QuPath GUI instance
     * @param project Current QuPath project
     * @param sampleName Sample name for the flipped image (can be null)
     * @return CompletableFuture that completes with true if successful, false if failed
     */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui,
            Project<BufferedImage> project,
            String sampleName) {

        return CompletableFuture.supplyAsync(() -> {
            // Get flip requirements from preferences
            boolean requiresFlipX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean requiresFlipY = QPPreferenceDialog.getFlipMacroYProperty();

            // Check if we're in a project
            if (project == null) {
                logger.warn("No project available to check flip status");
                return true; // Project will handle flipping during import
            }

            // Check current image's flip status
            ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(gui.getImageData());
            if (currentEntry == null) {
                logger.info("Current image not in project yet - will be handled during import");
                return true;
            }

            // Always ensure base_image is set on the current entry
            ensureBaseImageSet(currentEntry, project);

            // If no flipping required, we're done (base_image is now set)
            if (!requiresFlipX && !requiresFlipY) {
                logger.info("No image flipping required by preferences");
                return true;
            }

            boolean isFlipped = ImageMetadataManager.isFlipped(currentEntry);

            if (isFlipped) {
                logger.info("Current image flip status matches requirements");
                return true;
            }

            // Image needs to be flipped - create duplicate
            logger.info("Creating flipped duplicate of image for acquisition");

            // Show notification
            Platform.runLater(() ->
                    Dialogs.showInfoNotification(
                            "Image Preparation",
                            "Creating flipped image for acquisition..."
                    )
            );

            try {
                ProjectImageEntry<BufferedImage> flippedEntry = QPProjectFunctions.createFlippedDuplicate(
                        project,
                        currentEntry,
                        requiresFlipX,
                        requiresFlipY,
                        sampleName
                );

                if (flippedEntry != null) {
                    logger.info("Created flipped duplicate: {}", flippedEntry.getImageName());

                    // CRITICAL: Sync project changes BEFORE attempting to open
                    project.syncChanges();
                    logger.info("Project synchronized after adding flipped image");

                    // Create a future to track the image load
                    CompletableFuture<Boolean> loadFuture = new CompletableFuture<>();

                    // Set up the listener and open the image on the UI thread
                    Platform.runLater(() -> {
                        try {
                            // Refresh project UI first
                            gui.refreshProject();

                            // Create a listener that detects when the flipped image is loaded
                            // IMPORTANT: We check by ProjectImageEntry reference, not by name/path
                            // because TransformedServer paths may not match the entry name
                            javafx.beans.value.ChangeListener<ImageData<BufferedImage>> loadListener =
                                    new javafx.beans.value.ChangeListener<ImageData<BufferedImage>>() {
                                        @Override
                                        public void changed(
                                                javafx.beans.value.ObservableValue<? extends ImageData<BufferedImage>> observable,
                                                ImageData<BufferedImage> oldValue,
                                                ImageData<BufferedImage> newValue) {

                                            if (newValue != null && !loadFuture.isDone()) {
                                                // Check if this is our flipped entry by comparing with project entries
                                                try {
                                                    ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(newValue);
                                                    if (currentEntry != null && currentEntry.equals(flippedEntry)) {
                                                        logger.info("Flipped image loaded successfully - detected by entry match");
                                                        // Remove this listener
                                                        gui.getViewer().imageDataProperty().removeListener(this);
                                                        // Complete the future
                                                        loadFuture.complete(true);
                                                    }
                                                } catch (Exception e) {
                                                    logger.debug("Error checking image entry: {}", e.getMessage());
                                                }
                                            }
                                        }
                                    };

                            // Add the listener
                            gui.getViewer().imageDataProperty().addListener(loadListener);

                            // Now open the image
                            logger.info("Opening image entry: {}", flippedEntry.getImageName());
                            gui.openImageEntry(flippedEntry);

                            // Set up timeout handler
                            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
                                if (!loadFuture.isDone()) {
                                    // Check one more time if image actually loaded (must be on JavaFX thread)
                                    Platform.runLater(() -> {
                                        if (!loadFuture.isDone()) {
                                            // Remove the listener
                                            gui.getViewer().imageDataProperty().removeListener(loadListener);

                                            // Check if image actually loaded by comparing entries
                                            try {
                                                ImageData<BufferedImage> currentData = gui.getImageData();
                                                if (currentData != null) {
                                                    ProjectImageEntry<BufferedImage> loadedEntry = project.getEntry(currentData);
                                                    if (loadedEntry != null && loadedEntry.equals(flippedEntry)) {
                                                        logger.warn("Image loaded but listener didn't fire - completing anyway");
                                                        loadFuture.complete(true);
                                                        return;
                                                    }
                                                }
                                            } catch (Exception e) {
                                                logger.debug("Error in timeout check: {}", e.getMessage());
                                            }

                                            // Image truly didn't load
                                            logger.error("Image failed to load within 30 seconds");
                                            loadFuture.completeExceptionally(
                                                    new TimeoutException("Image failed to load within 30 seconds"));
                                        }
                                    });
                                }
                            });

                        } catch (Exception ex) {
                            logger.error("Error in UI thread while opening image", ex);
                            loadFuture.completeExceptionally(ex);
                        }
                    });

                    // Wait for the load to complete
                    try {
                        return loadFuture.get(35, TimeUnit.SECONDS); // Slightly longer than internal timeout
                    } catch (TimeoutException e) {
                        logger.error("Timeout waiting for flipped image to load");
                        throw new RuntimeException("Failed to load flipped image within timeout period", e);
                    } catch (Exception e) {
                        logger.error("Error waiting for flipped image to load", e);
                        throw new RuntimeException("Failed to load flipped image", e);
                    }

                } else {
                    logger.error("Failed to create flipped duplicate");
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError(
                                    "Failed to create flipped image duplicate",
                                    "Image Error"
                            )
                    );
                    return false;
                }

            } catch (Exception e) {
                logger.error("Error creating flipped duplicate", e);
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "Error creating flipped image: " + e.getMessage(),
                                "Image Error"
                        )
                );
                return false;
            }
        });
    }

    /**
     * Overload that accepts a SampleSetupResult for convenience.
     *
     * @param gui QuPath GUI instance
     * @param project Current QuPath project
     * @param sample Sample setup result containing sample name
     * @return CompletableFuture that completes with true if successful, false if failed
     */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui,
            Project<BufferedImage> project,
            SampleSetupController.SampleSetupResult sample) {

        String sampleName = sample != null ? sample.sampleName() : null;
        return validateAndFlipIfNeeded(gui, project, sampleName);
    }

    /**
     * Ensures the base_image metadata is set on the given entry.
     *
     * <p>If base_image is not already set, it will be set to the image's own name
     * (without extension). This ensures all images have a base_image value for
     * consistent tracking and sorting.
     *
     * @param entry The project image entry to check/update
     * @param project The project (for syncing changes)
     */
    private static void ensureBaseImageSet(ProjectImageEntry<BufferedImage> entry, Project<BufferedImage> project) {
        if (entry == null) {
            return;
        }

        Map<String, String> metadata = entry.getMetadata();
        if (metadata.get(ImageMetadataManager.BASE_IMAGE) == null) {
            String baseImage = GeneralTools.stripExtension(entry.getImageName());
            metadata.put(ImageMetadataManager.BASE_IMAGE, baseImage);
            logger.info("Set base_image='{}' on entry: {}", baseImage, entry.getImageName());

            // Sync project to persist the change
            if (project != null) {
                try {
                    project.syncChanges();
                } catch (IOException e) {
                    logger.error("Failed to sync project after setting base_image", e);
                }
            }
        }
    }
}
