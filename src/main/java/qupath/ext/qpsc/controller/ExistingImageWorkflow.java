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
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
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
                        logger.info("Opening flipped duplicate: {}", flippedEntry.getImageName());

                        // Open on UI thread
                        CompletableFuture<Boolean> openFuture = new CompletableFuture<>();
                        Platform.runLater(() -> {
                            gui.openImageEntry(flippedEntry);

                            // Wait for image to load
                            QPProjectFunctions.onImageLoadedInViewer(gui,
                                    flippedEntry.getImageName(),
                                    () -> openFuture.complete(true)
                            );
                        });

                        return openFuture.get(10, TimeUnit.SECONDS);
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

    }
}