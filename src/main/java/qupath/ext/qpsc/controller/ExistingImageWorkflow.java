package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.*;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * ExistingImageWorkflow - Refactored version with improved structure and readability.
 *
 * <p>This workflow handles acquisition for images already open in QuPath through two main paths:</p>
 * <ul>
 *   <li><b>Path A:</b> Using existing alignment (slide-specific or general)</li>
 *   <li><b>Path B:</b> Creating new manual alignment</li>
 * </ul>
 *
 * @author Mike Nelson
 * @version 4.0 - Refactored with metadata support
 * @since 2.0
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    /**
     * Main entry point - runs the complete workflow from start to finish.
     */
    public static void run() {
        logger.info("************************************");
        logger.info("Starting Existing Image Workflow...");
        logger.info("************************************");

        try {
            // Create workflow executor to manage the entire process
            WorkflowExecutor executor = new WorkflowExecutor();
            executor.execute();
        } catch (Exception e) {
            logger.error("Workflow failed with unexpected error", e);
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "Workflow failed: " + e.getMessage(),
                            "Workflow Error"
                    )
            );
        }
    }

    /**
     * Main workflow executor that manages the complete workflow lifecycle.
     */
    private static class WorkflowExecutor {
        private final QuPathGUI gui;
        private WorkflowState state;

        public WorkflowExecutor() {
            this.gui = QuPathGUI.getInstance();
            this.state = new WorkflowState();
        }

        /**
         * Executes the complete workflow from validation through acquisition and stitching.
         */
        public void execute() {
            // Step 1: Validate prerequisites
            if (!validatePrerequisites()) {
                return;
            }

            // Step 2: Setup sample
            setupSample()
                    .thenCompose(this::checkExistingAlignment)
                    .thenCompose(this::processAlignment)
                    .thenCompose(this::performAcquisition)
                    .thenCompose(this::waitForCompletion)
                    .thenRun(this::cleanup)
                    .exceptionally(this::handleError);
        }

        /**
         * Validates that all prerequisites are met before starting.
         */
        private boolean validatePrerequisites() {
            // Check for open image
            if (gui.getImageData() == null) {
                logger.warn("No image is currently open in QuPath");
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "No image is currently open. Please open an image first.",
                                "No Image"
                        )
                );
                return false;
            }

            logger.info("Current image: {}", gui.getImageData().getServer().getPath());

            // Ensure microscope connection
            try {
                if (!MicroscopeController.getInstance().isConnected()) {
                    logger.info("Connecting to microscope server");
                    MicroscopeController.getInstance().connect();
                }

                return true;
            } catch (IOException e) {
                logger.error("Failed to connect to microscope server", e);
                UIFunctions.notifyUserOfError(
                        "Cannot connect to microscope server.\nPlease check server is running.",
                        "Connection Error"
                );
                return false;
            }
        }

        /**
         * Validates image flip status and creates flipped duplicate if needed.
         * This should be called after the project is created/opened.
         */
        /**
         * Validates image flip status and creates flipped duplicate if needed.
         * This should be called after the project is created/opened.
         */
        private CompletableFuture<Boolean> validateAndPrepareImage() {
            return CompletableFuture.supplyAsync(() -> {
                // Get flip requirements from preferences
                boolean requiresFlipX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean requiresFlipY = QPPreferenceDialog.getFlipMacroYProperty();

                // If no flipping required, we're good
                if (!requiresFlipX && !requiresFlipY) {
                    logger.info("No image flipping required by preferences");
                    return true;
                }

                // Check if we're in a project
                Project<BufferedImage> project = gui.getProject();
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

                boolean isFlipped = ImageMetadataManager.isFlipped(currentEntry);

                if (isFlipped || (!requiresFlipX && !requiresFlipY)) {
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
                            state.sample != null ? state.sample.sampleName() : null
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
                                                        //ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(currentData);
                                                        if (currentEntry != null && currentEntry.equals(flippedEntry)) {
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
         * Shows sample setup dialog and stores results.
         */
        private CompletableFuture<WorkflowState> setupSample() {
            logger.info("Showing sample setup dialog");

            return SampleSetupController.showDialog()
                    .thenApply(sample -> {
                        if (sample == null) {
                            throw new CancellationException("Sample setup cancelled");
                        }

                        logger.info("Sample setup complete - Name: {}, Modality: {}, Folder: {}",
                                sample.sampleName(), sample.modality(), sample.projectsFolder());

                        state.sample = sample;
                        return state;
                    });
        }

        /**
         * Checks for existing slide-specific alignment.
         */
        private CompletableFuture<WorkflowState> checkExistingAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Checking for existing slide alignment");

            return AlignmentHelper.checkForSlideAlignment(gui, state.sample)
                    .thenApply(slideTransform -> {
                        if (slideTransform != null) {
                            state.useExistingSlideAlignment = true;
                            state.transform = slideTransform;
                        }
                        return state;
                    });
        }

        /**
         * Processes alignment based on user choices.
         */
        private CompletableFuture<WorkflowState> processAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            if (state.useExistingSlideAlignment) {
                // Fast path - use existing alignment
                return processExistingSlideAlignment(state);
            } else {
                // Normal path - alignment selection
                return showAlignmentSelection(state)
                        .thenCompose(this::executeAlignmentPath);
            }
        }

        /**
         * Fast path processing for existing slide alignment.
         */
        private CompletableFuture<WorkflowState> processExistingSlideAlignment(WorkflowState state) {
            logger.info("Using existing slide-specific alignment - fast path");

            MicroscopeController.getInstance().setCurrentTransform(state.transform);

            return ProjectHelper.setupProject(gui, state.sample)
                    .thenCompose(projectInfo -> {
                        if (projectInfo == null) {
                            throw new RuntimeException("Project setup failed");
                        }

                        state.projectInfo = projectInfo;

                        // Now validate and prepare image after project is set up
                        return validateAndPrepareImage();
                    })
                    .thenApply(validated -> {
                        if (!validated) {
                            throw new RuntimeException("Image validation failed");
                        }

                        state.annotations = AnnotationHelper.ensureAnnotationsExist(gui,
                                getPixelSizeFromPreferences());

                        if (state.annotations.isEmpty()) {
                            throw new RuntimeException("No valid annotations found");
                        }

                        return state;
                    });
        }

        /**
         * Shows alignment selection dialog.
         */
        private CompletableFuture<WorkflowState> showAlignmentSelection(WorkflowState state) {
            logger.info("Showing alignment selection dialog");

            return AlignmentSelectionController.showDialog(gui, state.sample.modality())
                    .thenApply(choice -> {
                        if (choice == null) {
                            throw new CancellationException("Alignment selection cancelled");
                        }

                        state.alignmentChoice = choice;
                        return state;
                    });
        }

        /**
         * Executes the selected alignment path.
         */
        private CompletableFuture<WorkflowState> executeAlignmentPath(WorkflowState state) {
            if (state == null || state.alignmentChoice == null) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<WorkflowState> pathResult;

            if (state.alignmentChoice.useExistingAlignment()) {
                // Path A: Use existing general alignment
                pathResult = new ExistingAlignmentPath(gui, state).execute();
            } else {
                // Path B: Create manual alignment
                pathResult = new ManualAlignmentPath(gui, state).execute();
            }

            // After alignment path completes, validate image
            return pathResult.thenCompose(alignedState -> {
                if (alignedState == null) {
                    return CompletableFuture.completedFuture(null);
                }

                return validateAndPrepareImage()
                        .thenApply(validated -> {
                            if (!validated) {
                                throw new RuntimeException("Image validation failed after alignment");
                            }
                            return alignedState;
                        });
            });
        }

        /**
         * Performs the acquisition phase.
         */
        private CompletableFuture<WorkflowState> performAcquisition(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Starting acquisition phase");

            return new AcquisitionManager(gui, state).execute();
        }

        /**
         * Waits for all operations to complete.
         */
        private CompletableFuture<Void> waitForCompletion(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Waiting for {} stitching operations to complete",
                    state.stitchingFutures.size());

            return CompletableFuture.allOf(
                    state.stitchingFutures.toArray(new CompletableFuture[0])
            );
        }

        /**
         * Performs cleanup after workflow completion.
         */
        private void cleanup() {
            logger.info("Performing workflow cleanup");

            if (state != null && state.projectInfo != null) {
                String tempTileDir = state.projectInfo.getTempTileDirectory();
                TileCleanupHelper.performCleanup(tempTileDir);
            }

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Workflow Complete");
                alert.setHeaderText("Acquisition workflow completed successfully");
                alert.setContentText("All annotations have been acquired and stitched.");
                alert.showAndWait();
            });
        }

        /**
         * Handles any errors during workflow execution.
         */
        private Void handleError(Throwable ex) {
            if (ex.getCause() instanceof CancellationException) {
                logger.info("Workflow cancelled by user");
                return null;
            }

            logger.error("Workflow error", ex);
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(),
                            "Error"
                    )
            );
            return null;
        }

        /**
         * Gets pixel size from preferences.
         */
        private double getPixelSizeFromPreferences() {
            String pixelSizeStr = PersistentPreferences.getMacroImagePixelSizeInMicrons();

            if (pixelSizeStr == null || pixelSizeStr.trim().isEmpty()) {
                logger.error("Macro image pixel size is not configured in preferences");
                throw new IllegalStateException(
                        "Macro image pixel size is not configured.\n" +
                                "This value must be set before running the workflow."
                );
            }

            try {
                double pixelSize = Double.parseDouble(pixelSizeStr.trim());
                if (pixelSize <= 0) {
                    logger.error("Invalid macro image pixel size: {} µm", pixelSize);
                    throw new IllegalStateException(
                            "Invalid macro image pixel size: " + pixelSize + " µm.\n" +
                                    "Pixel size must be greater than zero."
                    );
                }
                logger.debug("Using macro pixel size from preferences: {} µm", pixelSize);
                return pixelSize;
            } catch (NumberFormatException e) {
                logger.error("Cannot parse macro image pixel size: '{}'", pixelSizeStr);
                throw new IllegalStateException(
                        "Invalid macro image pixel size format: '" + pixelSizeStr + "'.\n" +
                                "Please enter a valid number in Edit > Preferences."
                );
            }
        }
    }

    /**
     * Container for workflow state that gets passed between stages.
     */
    public static class WorkflowState {
        public SampleSetupController.SampleSetupResult sample;
        public AlignmentSelectionController.AlignmentChoice alignmentChoice;
        public AffineTransform transform;
        public ProjectHelper.ProjectInfo projectInfo;
        public List<PathObject> annotations = new ArrayList<>();
        public List<CompletableFuture<Void>> stitchingFutures = new ArrayList<>();
        public boolean useExistingSlideAlignment = false;
        public double pixelSize;
        public List<String> selectedAnnotationClasses = Arrays.asList("Tissue", "Scanned Area", "Bounding Box");
        public java.util.Map<String, Double> angleOverrides; // User-specified angle overrides for modality

    }
}