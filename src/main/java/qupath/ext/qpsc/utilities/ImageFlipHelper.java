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
            // CRITICAL FIX: When project.getEntry() returns null, it might be because
            // the GUI hasn't updated to the new project entry yet. We need to find
            // the entry by other means to ensure proper flip validation.
            ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(gui.getImageData());

            if (currentEntry == null) {
                logger.info("Current image not directly matched to project entry - searching by criteria");

                // Try to find an entry that matches the current image
                // This handles the case where project was just created and GUI hasn't synced
                currentEntry = findMatchingEntry(gui, project);

                if (currentEntry == null) {
                    // Still can't find it - check if there's only one entry and flip is required
                    var entries = project.getImageList();
                    if (entries.size() == 1) {
                        currentEntry = entries.get(0);
                        logger.info("Using only project entry: {}", currentEntry.getImageName());
                    } else if (!entries.isEmpty()) {
                        // Multiple entries - try to find one that's already flipped and matches requirements
                        for (var entry : entries) {
                            if (ImageMetadataManager.isFlipped(entry)) {
                                boolean flipXMatch = !requiresFlipX || ImageMetadataManager.isFlippedX(entry);
                                boolean flipYMatch = !requiresFlipY || ImageMetadataManager.isFlippedY(entry);
                                if (flipXMatch && flipYMatch) {
                                    logger.info("Found matching flipped entry: {}", entry.getImageName());
                                    // Open this entry to ensure it's the current image
                                    return openAndVerifyEntry(gui, project, entry);
                                }
                            }
                        }
                        logger.warn("Could not find matching entry in project with {} entries", entries.size());
                    }
                }

                if (currentEntry == null) {
                    logger.error("Cannot find current image in project - flip validation failed");
                    return false;
                }
            }

            // Always ensure base_image is set on the current entry
            ensureBaseImageSet(currentEntry, project);

            // If no flipping required, we're done (base_image is now set)
            if (!requiresFlipX && !requiresFlipY) {
                logger.info("No image flipping required by preferences");
                return true;
            }

            boolean isFlipped = ImageMetadataManager.isFlipped(currentEntry);
            boolean flipXMatches = !requiresFlipX || ImageMetadataManager.isFlippedX(currentEntry);
            boolean flipYMatches = !requiresFlipY || ImageMetadataManager.isFlippedY(currentEntry);

            if (isFlipped && flipXMatches && flipYMatches) {
                logger.info("Current image flip status matches requirements (flipX={}, flipY={})",
                        ImageMetadataManager.isFlippedX(currentEntry),
                        ImageMetadataManager.isFlippedY(currentEntry));

                // CRITICAL: Verify this entry is actually open in the GUI
                // If not, we need to open it
                ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                if (openEntry == null || !openEntry.equals(currentEntry)) {
                    logger.info("Flipped entry found but not currently open - opening it");
                    return openAndVerifyEntry(gui, project, currentEntry);
                }

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
                // CRITICAL FIX: Save the current GUI hierarchy to the project entry BEFORE
                // creating the flipped duplicate. This ensures that any annotations drawn
                // by the user (which may not have been saved yet) are captured in the saved
                // hierarchy. createFlippedDuplicate() reads from the saved hierarchy, so
                // without this step, unsaved annotations would be lost/not transformed.
                ImageData<BufferedImage> currentImageData = gui.getImageData();
                if (currentImageData != null) {
                    logger.info("Saving current image data to ensure annotations are persisted before flip");
                    try {
                        currentEntry.saveImageData(currentImageData);
                        project.syncChanges();
                        logger.info("Successfully saved {} annotations before creating flipped duplicate",
                                currentImageData.getHierarchy().getAnnotationObjects().size());
                    } catch (IOException e) {
                        logger.warn("Failed to save current image data before flip: {}", e.getMessage());
                        // Continue anyway - the flip might still work if annotations were already saved
                    }
                }

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

    /**
     * Tries to find a project entry that matches the current image in the GUI.
     *
     * <p>This is used when project.getEntry(gui.getImageData()) returns null,
     * which can happen when the project was just created and the GUI hasn't
     * fully synchronized yet.
     *
     * @param gui QuPath GUI instance
     * @param project Current project
     * @return Matching entry, or null if not found
     */
    private static ProjectImageEntry<BufferedImage> findMatchingEntry(
            QuPathGUI gui,
            Project<BufferedImage> project) {

        ImageData<BufferedImage> currentData = gui.getImageData();
        if (currentData == null || currentData.getServer() == null) {
            return null;
        }

        String currentPath = currentData.getServer().getPath();
        String currentName = null;

        // Try to extract just the filename from the path
        if (currentPath != null) {
            int lastSlash = Math.max(currentPath.lastIndexOf('/'), currentPath.lastIndexOf('\\'));
            if (lastSlash >= 0 && lastSlash < currentPath.length() - 1) {
                currentName = currentPath.substring(lastSlash + 1);
            } else {
                currentName = currentPath;
            }
        }

        logger.debug("Looking for entry matching path: {} or name: {}", currentPath, currentName);

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String entryName = entry.getImageName();

            // Try exact name match
            if (entryName != null && currentName != null) {
                if (entryName.equals(currentName) || entryName.contains(currentName) ||
                        currentName.contains(entryName)) {
                    logger.info("Found matching entry by name: {}", entryName);
                    return entry;
                }
            }

            // Try to read the entry's server path
            try {
                var entryBuilder = entry.getServerBuilder();
                if (entryBuilder != null) {
                    var uris = entryBuilder.getURIs();
                    for (var uri : uris) {
                        if (uri.toString().contains(currentName != null ? currentName : currentPath)) {
                            logger.info("Found matching entry by URI: {}", entryName);
                            return entry;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error checking entry URI: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Opens a project entry and waits for it to load.
     *
     * <p>This is used when we've found the correct flipped entry but it's not
     * currently open in the GUI. We need to open it and verify the load completes.
     *
     * @param gui QuPath GUI instance
     * @param project Current project
     * @param entry Entry to open
     * @return true if entry was successfully opened, false otherwise
     */
    private static boolean openAndVerifyEntry(
            QuPathGUI gui,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> entry) {

        CompletableFuture<Boolean> loadFuture = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Create a listener for load completion
                javafx.beans.value.ChangeListener<ImageData<BufferedImage>> loadListener =
                        new javafx.beans.value.ChangeListener<>() {
                            @Override
                            public void changed(
                                    javafx.beans.value.ObservableValue<? extends ImageData<BufferedImage>> observable,
                                    ImageData<BufferedImage> oldValue,
                                    ImageData<BufferedImage> newValue) {

                                if (newValue != null && !loadFuture.isDone()) {
                                    try {
                                        ProjectImageEntry<BufferedImage> loadedEntry = project.getEntry(newValue);
                                        if (loadedEntry != null && loadedEntry.equals(entry)) {
                                            logger.info("Entry loaded successfully: {}", entry.getImageName());
                                            gui.getViewer().imageDataProperty().removeListener(this);
                                            loadFuture.complete(true);
                                        }
                                    } catch (Exception e) {
                                        logger.debug("Error checking loaded entry: {}", e.getMessage());
                                    }
                                }
                            }
                        };

                gui.getViewer().imageDataProperty().addListener(loadListener);
                logger.info("Opening entry: {}", entry.getImageName());
                gui.openImageEntry(entry);

                // Timeout handler
                CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
                    if (!loadFuture.isDone()) {
                        Platform.runLater(() -> {
                            gui.getViewer().imageDataProperty().removeListener(loadListener);
                            // Final check
                            try {
                                var currentEntry = project.getEntry(gui.getImageData());
                                if (currentEntry != null && currentEntry.equals(entry)) {
                                    loadFuture.complete(true);
                                    return;
                                }
                            } catch (Exception e) {
                                logger.debug("Error in timeout check: {}", e.getMessage());
                            }
                            logger.error("Failed to open entry within timeout: {}", entry.getImageName());
                            loadFuture.complete(false);
                        });
                    }
                });

            } catch (Exception e) {
                logger.error("Error opening entry", e);
                loadFuture.complete(false);
            }
        });

        try {
            return loadFuture.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Error waiting for entry to open", e);
            return false;
        }
    }
}
