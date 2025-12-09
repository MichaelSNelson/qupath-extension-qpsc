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
import qupath.ext.qpsc.utilities.QPProjectFunctions;
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

// Import for RefinementSelectionController
import qupath.ext.qpsc.ui.RefinementSelectionController.RefinementChoice;
import qupath.ext.qpsc.ui.RefinementSelectionController.AlignmentInfo;

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
         *
         * <p>Workflow stages:
         * <ol>
         *   <li>Validate prerequisites (image open, microscope connected)</li>
         *   <li>Sample setup dialog</li>
         *   <li>Check for existing slide-specific alignment</li>
         *   <li>Alignment selection (if no slide alignment found)</li>
         *   <li>Refinement selection (for existing alignments)</li>
         *   <li>Execute alignment path (existing or manual)</li>
         *   <li>Perform acquisition</li>
         *   <li>Wait for stitching completion</li>
         * </ol>
         */
        public void execute() {
            // Step 1: Validate prerequisites
            if (!validatePrerequisites()) {
                return;
            }

            // Step 2: Setup sample and process workflow
            setupSample()
                    .thenCompose(this::checkExistingAlignment)
                    .thenCompose(this::processAlignment)
                    .thenCompose(this::showRefinementSelection)  // Phase 2: New refinement stage
                    .thenCompose(this::handleRefinementChoice)   // Phase 2: Execute refinement
                    .thenCompose(this::performAcquisition)
                    .thenCompose(this::waitForCompletion)
                    .thenAccept(result -> {
                        // Only cleanup and show success if workflow completed successfully
                        cleanup();
                        showSuccessNotification();
                    })
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
         * Shows sample setup dialog and stores results.
         * Defaults sample name to current image name without extension.
         */
        private CompletableFuture<WorkflowState> setupSample() {
            logger.info("Showing sample setup dialog");

            // Get the actual image file name for default sample name
            // Uses QPProjectFunctions.getActualImageFileName() to get the real file name
            // rather than metadata name which may be different (e.g., project name)
            String defaultSampleName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            logger.debug("Default sample name from image file: {}", defaultSampleName);

            return SampleSetupController.showDialog(defaultSampleName)
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
         *
         * <p>If found, stores the transform and confidence information in the state.
         * Refinement selection is now handled by the separate {@code showRefinementSelection}
         * stage using {@link RefinementSelectionController}.
         */
        private CompletableFuture<WorkflowState> checkExistingAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Checking for existing slide alignment");

            return AlignmentHelper.checkForSlideAlignment(gui, state.sample)
                    .thenApply(slideAlignmentResult -> {
                        if (slideAlignmentResult != null) {
                            state.useExistingSlideAlignment = true;
                            state.transform = slideAlignmentResult.getTransform();
                            // Store confidence and source for refinement dialog
                            state.alignmentConfidence = slideAlignmentResult.getConfidence();
                            state.alignmentSource = slideAlignmentResult.getSource();
                            logger.info("Found slide-specific alignment - confidence: {}, source: {}",
                                    String.format("%.2f", state.alignmentConfidence), state.alignmentSource);
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
         * If refinement was requested, performs single-tile refinement after setup.
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
                        @SuppressWarnings("unchecked")
                        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.getCurrentProject();
                        return ImageFlipHelper.validateAndFlipIfNeeded(gui, project, state.sample);
                    })
                    .thenApply(validated -> {
                        if (!validated) {
                            throw new RuntimeException("Image validation failed");
                        }

                        state.pixelSize = getPixelSizeFromPreferences();
                        state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize);

                        if (state.annotations.isEmpty()) {
                            throw new RuntimeException("No valid annotations found");
                        }

                        return state;
                    })
                    .thenCompose(currentState -> {
                        // If refinement was requested, perform it now
                        if (state.slideAlignmentNeedsRefinement) {
                            logger.info("Performing single-tile refinement of existing alignment");
                            return performSlideAlignmentRefinement();
                        } else {
                            return CompletableFuture.completedFuture(currentState);
                        }
                    });
        }

        /**
         * Performs single-tile refinement on existing slide alignment.
         * Creates tiles and prompts user to select one for refinement.
         */
        private CompletableFuture<WorkflowState> performSlideAlignmentRefinement() {
            // Create tiles for refinement
            TileHelper.createTilesForAnnotations(
                    state.annotations,
                    state.sample,
                    state.projectInfo.getTempTileDirectory(),
                    state.projectInfo.getImagingModeWithIndex(),
                    state.pixelSize
            );

            return SingleTileRefinement.performRefinement(
                    gui, state.annotations, state.transform
            ).thenApply(result -> {
                if (result.transform != null) {
                    state.transform = result.transform;
                    MicroscopeController.getInstance().setCurrentTransform(result.transform);
                    logger.info("Updated transform with refined alignment");
                }
                // Store the selected refinement tile for acquisition prioritization
                state.refinementTile = result.selectedTile;
                if (result.selectedTile != null) {
                    logger.info("Stored refinement tile '{}' for acquisition prioritization",
                            result.selectedTile.getName());
                }

                // Save the refined alignment
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

                // Get the actual image file name (not metadata name which may be project name)
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

                if (imageName != null) {
                    AffineTransformManager.saveSlideAlignment(
                            project,
                            imageName,
                            state.sample.modality(),
                            state.transform,
                            null  // No processed macro image for fast path refinement
                    );
                    logger.info("Saved refined slide-specific alignment for image: {}", imageName);
                } else {
                    logger.warn("Cannot save refined alignment - no image name available");
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
         *
         * <p>NOTE: Image flipping now happens INSIDE the alignment paths
         * (after project setup, before annotations/tiles/white space detection).
         * This ensures all operations work on the correctly oriented image.
         */
        private CompletableFuture<WorkflowState> executeAlignmentPath(WorkflowState state) {
            if (state == null || state.alignmentChoice == null) {
                return CompletableFuture.completedFuture(null);
            }

            if (state.alignmentChoice.useExistingAlignment()) {
                // Path A: Use existing general alignment
                return new ExistingAlignmentPath(gui, state).execute();
            } else {
                // Path B: Create manual alignment
                return new ManualAlignmentPath(gui, state).execute();
            }
        }

        /**
         * Shows the refinement selection dialog for existing alignments.
         *
         * <p>This stage is only shown when using an existing alignment (not manual).
         * It allows users to choose between:
         * <ul>
         *   <li>Proceed without refinement (fastest)</li>
         *   <li>Single-tile refinement (quick verification)</li>
         *   <li>Full manual alignment (start over)</li>
         * </ul>
         *
         * @param state Current workflow state
         * @return Updated workflow state with refinement choice
         */
        private CompletableFuture<WorkflowState> showRefinementSelection(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Skip refinement dialog for manual alignment path
            if (state.alignmentChoice != null && !state.alignmentChoice.useExistingAlignment()) {
                logger.info("Manual alignment selected - skipping refinement selection");
                return CompletableFuture.completedFuture(state);
            }

            // Also skip if we're using slide-specific alignment (already handled)
            if (state.useExistingSlideAlignment && !state.slideAlignmentNeedsRefinement) {
                logger.info("Using slide-specific alignment - skipping refinement selection");
                return CompletableFuture.completedFuture(state);
            }

            // Build alignment info for the dialog
            String source;
            double confidence;

            if (state.useExistingSlideAlignment) {
                source = "Slide-specific";
                // Get confidence from the slide alignment result
                confidence = state.alignmentConfidence;
            } else if (state.alignmentChoice != null && state.alignmentChoice.selectedTransform() != null) {
                source = AlignmentHelper.getSourceDescription(state.alignmentChoice.selectedTransform());
                confidence = state.alignmentChoice.confidence();
            } else {
                source = "Unknown";
                confidence = 0.5;
            }

            String transformName = state.alignmentChoice != null && state.alignmentChoice.selectedTransform() != null
                    ? state.alignmentChoice.selectedTransform().getName()
                    : "Current alignment";

            AlignmentInfo alignmentInfo = new AlignmentInfo(confidence, source, transformName);

            logger.info("Showing refinement selection - confidence: {}, source: {}",
                    String.format("%.2f", confidence), source);

            return RefinementSelectionController.showDialog(gui, alignmentInfo)
                    .thenApply(result -> {
                        if (result == null) {
                            // User pressed Back - this should go back to alignment selection
                            // For now, treat as cancellation
                            throw new CancellationException("Refinement selection cancelled");
                        }

                        state.refinementChoice = result.choice();
                        logger.info("Refinement choice: {} (auto-selected: {})",
                                result.choice(), result.wasAutoSelected());

                        return state;
                    });
        }

        /**
         * Handles the user's refinement choice.
         *
         * @param state Current workflow state with refinement choice
         * @return Updated workflow state after handling refinement
         */
        private CompletableFuture<WorkflowState> handleRefinementChoice(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            switch (state.refinementChoice) {
                case NONE:
                    logger.info("Proceeding without refinement");
                    return CompletableFuture.completedFuture(state);

                case SINGLE_TILE:
                    logger.info("Performing single-tile refinement");
                    return performSingleTileRefinement(state);

                case FULL_MANUAL:
                    logger.info("User requested full manual alignment - starting over");
                    // Clear existing alignment and restart with manual path
                    state.alignmentChoice = new AlignmentSelectionController.AlignmentChoice(
                            false, null, 0.0, false);
                    state.useExistingSlideAlignment = false;
                    state.transform = null;
                    return new ManualAlignmentPath(gui, state).execute();

                default:
                    logger.warn("Unknown refinement choice: {}", state.refinementChoice);
                    return CompletableFuture.completedFuture(state);
            }
        }

        /**
         * Performs single-tile refinement on the current alignment.
         */
        private CompletableFuture<WorkflowState> performSingleTileRefinement(WorkflowState state) {
            // Ensure we have tiles to work with
            if (state.annotations == null || state.annotations.isEmpty()) {
                logger.warn("No annotations available for refinement");
                return CompletableFuture.completedFuture(state);
            }

            // Create tiles for refinement if not already done
            if (state.projectInfo != null) {
                TileHelper.createTilesForAnnotations(
                        state.annotations,
                        state.sample,
                        state.projectInfo.getTempTileDirectory(),
                        state.projectInfo.getImagingModeWithIndex(),
                        state.pixelSize
                );
            }

            return SingleTileRefinement.performRefinement(
                    gui, state.annotations, state.transform
            ).thenApply(result -> {
                if (result.transform != null) {
                    state.transform = result.transform;
                    MicroscopeController.getInstance().setCurrentTransform(result.transform);
                    logger.info("Updated transform with refined alignment");
                }
                // Store the selected refinement tile for acquisition prioritization
                state.refinementTile = result.selectedTile;
                if (result.selectedTile != null) {
                    logger.info("Stored refinement tile '{}' for acquisition prioritization",
                            result.selectedTile.getName());
                }

                // Save the refined alignment
                saveRefinedAlignment(state);

                return state;
            });
        }

        /**
         * Saves the refined alignment to the project.
         */
        private void saveRefinedAlignment(WorkflowState state) {
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = state.projectInfo != null
                    ? (Project<BufferedImage>) state.projectInfo.getCurrentProject()
                    : gui.getProject();

            if (project == null) {
                logger.warn("Cannot save refined alignment - no project available");
                return;
            }

            // Get the actual image file name (not metadata name which may be project name)
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

            if (imageName != null && state.transform != null) {
                AffineTransformManager.saveSlideAlignment(
                        project,
                        imageName,
                        state.sample.modality(),
                        state.transform,
                        null  // No processed macro image for refinement
                );
                logger.info("Saved refined slide-specific alignment for image: {}", imageName);
            }
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
        }

        /**
         * Shows success notification to user.
         */
        private void showSuccessNotification() {
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

            // Extract the most informative error message
            String message = ex.getMessage();
            if (message == null || message.isEmpty()) {
                Throwable cause = ex.getCause();
                message = cause != null ? cause.getMessage() : "Unknown error occurred";
            }

            // Make final for lambda expression
            final String errorMessage = message;

            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            errorMessage,
                            "Acquisition Workflow"
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
        public boolean slideAlignmentNeedsRefinement = false; // User requested refinement of existing slide alignment
        public boolean cancelled = false; // Tracks user cancellation
        public double pixelSize;
        public List<String> selectedAnnotationClasses = Arrays.asList("Tissue", "Scanned Area", "Bounding Box");
        public java.util.Map<String, Double> angleOverrides; // User-specified angle overrides for modality
        public PathObject refinementTile = null; // Tile used for alignment refinement (to prioritize in acquisition)

        // Phase 2: Refinement selection
        public RefinementChoice refinementChoice = RefinementChoice.NONE; // Default to no refinement
        public double alignmentConfidence = 0.7; // Confidence score for current alignment
        public String alignmentSource = "Unknown"; // Source description for alignment
    }
}