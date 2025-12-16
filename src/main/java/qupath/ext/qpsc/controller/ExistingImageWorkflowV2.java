package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.*;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.ExistingImageAcquisitionConfig;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.RefinementChoice;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.objects.classes.PathClass;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflowV2 - Existing Image acquisition workflow.
 *
 * <p>This workflow uses the {@link ExistingImageAcquisitionController} consolidated
 * dialog to gather all configuration in a single step, then routes to appropriate
 * sub-workflows based on user selections.
 *
 * <p>Key features:
 * <ul>
 *   <li>Single consolidated dialog for all configuration</li>
 *   <li>Confidence-based alignment recommendations</li>
 *   <li>Refinement options presented upfront</li>
 *   <li>Green box detection parameters editable in advanced section</li>
 * </ul>
 *
 * <p>Workflow stages:
 * <ol>
 *   <li>Validate prerequisites</li>
 *   <li>Show consolidated dialog</li>
 *   <li>Check for slide-specific alignment (may skip to acquisition)</li>
 *   <li>Route sub-workflow (existing alignment path or manual alignment)</li>
 *   <li>Handle refinement if selected</li>
 *   <li>Perform acquisition</li>
 *   <li>Wait for stitching completion</li>
 * </ol>
 */
public class ExistingImageWorkflowV2 {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflowV2.class);

    /**
     * Starts the workflow.
     */
    public static void start() {
        new WorkflowOrchestrator().execute();
    }

    /**
     * Internal orchestrator class that manages the workflow execution.
     */
    private static class WorkflowOrchestrator {
        private final QuPathGUI gui;
        private final WorkflowState state;

        WorkflowOrchestrator() {
            this.gui = QuPathGUI.getInstance();
            this.state = new WorkflowState();
        }

        /**
         * Executes the complete workflow.
         */
        public void execute() {
            // Step 1: Validate prerequisites
            if (!validatePrerequisites()) {
                return;
            }

            // Step 2: Check if annotations exist and show annotation dialog FIRST
            Set<String> existingClasses = getExistingAnnotationClasses();

            if (!existingClasses.isEmpty()) {
                // Annotations exist - show annotation selection dialog first
                logger.info("Found {} annotation classes in image, showing selection dialog first", existingClasses.size());

                List<String> preselected = PersistentPreferences.getSelectedAnnotationClasses();
                String defaultSampleName = getDefaultSampleName();

                // Show annotation selection dialog (modality options are in the combined dialog's Advanced Options)
                AnnotationAcquisitionDialog.showDialog(existingClasses, preselected)
                        .thenCompose(annotationResult -> {
                            if (!annotationResult.proceed || annotationResult.selectedClasses.isEmpty()) {
                                throw new CancellationException("Annotation selection cancelled");
                            }

                            // Store selected classes in state for later use
                            // Note: angle overrides will come from the combined dialog's Advanced Options
                            state.selectedAnnotationClasses = annotationResult.selectedClasses;
                            logger.info("User selected {} classes: {}",
                                    annotationResult.selectedClasses.size(), annotationResult.selectedClasses);

                            // Get actual annotations for the selected classes
                            List<PathObject> selectedAnnotations = getAnnotationsForClasses(annotationResult.selectedClasses);
                            return ExistingImageAcquisitionController.showDialog(defaultSampleName, selectedAnnotations);
                        })
                        .thenCompose(this::initializeFromConfig)
                        .thenCompose(this::checkExistingSlideAlignment)
                        .thenCompose(this::routeSubWorkflow)
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
                            cleanup();
                            showSuccessNotification();
                        })
                        .exceptionally(this::handleError);
            } else {
                // No annotations - proceed with consolidated dialog which will handle annotation creation
                logger.info("No annotations found in image, proceeding with consolidated dialog");

                String defaultSampleName = getDefaultSampleName();
                List<PathObject> emptyAnnotations = new ArrayList<>();

                ExistingImageAcquisitionController.showDialog(defaultSampleName, emptyAnnotations)
                        .thenCompose(this::initializeFromConfig)
                        .thenCompose(this::checkExistingSlideAlignment)
                        .thenCompose(this::routeSubWorkflow)
                        .thenCompose(this::ensureAnnotationsExist)  // Call annotation helper if none exist
                        .thenCompose(this::handleRefinement)
                        .thenCompose(this::performAcquisition)
                        .thenCompose(this::waitForCompletion)
                        .thenAccept(result -> {
                            cleanup();
                            showSuccessNotification();
                        })
                        .exceptionally(this::handleError);
            }
        }

        /**
         * Gets all annotation class names from the current image.
         */
        private Set<String> getExistingAnnotationClasses() {
            ImageData<?> imageData = gui.getImageData();
            if (imageData == null || imageData.getHierarchy() == null) {
                return Collections.emptySet();
            }

            return imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null)
                    .map(ann -> ann.getPathClass().getName())
                    .collect(Collectors.toSet());
        }

        /**
         * Counts annotations matching the selected classes.
         */
        private int countAnnotationsForClasses(List<String> selectedClasses) {
            return getAnnotationsForClasses(selectedClasses).size();
        }

        /**
         * Gets annotations matching the selected classes.
         */
        private List<PathObject> getAnnotationsForClasses(List<String> selectedClasses) {
            ImageData<?> imageData = gui.getImageData();
            if (imageData == null || imageData.getHierarchy() == null) {
                return new ArrayList<>();
            }

            return imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null &&
                            selectedClasses.contains(ann.getPathClass().getName()))
                    .collect(Collectors.toList());
        }

        /**
         * Ensures annotations exist for acquisition, prompting for creation if needed.
         */
        private CompletableFuture<WorkflowState> ensureAnnotationsExist(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // If we already have annotations from the annotation dialog, skip
            if (state.annotations != null && !state.annotations.isEmpty()) {
                return CompletableFuture.completedFuture(state);
            }

            // Use AnnotationHelper to ensure annotations exist
            List<String> validClasses = state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty()
                    ? state.selectedAnnotationClasses
                    : PersistentPreferences.getSelectedAnnotationClasses();

            state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize, validClasses);

            if (state.annotations.isEmpty()) {
                throw new RuntimeException("No valid annotations found. Please create annotations before acquisition.");
            }

            logger.info("Ensured {} annotations exist for acquisition", state.annotations.size());
            return CompletableFuture.completedFuture(state);
        }

        /**
         * Validates prerequisites before starting.
         */
        private boolean validatePrerequisites() {
            if (gui.getImageData() == null) {
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "No Image Open",
                        "Please open an image before starting the workflow."));
                return false;
            }

            if (!MicroscopeController.getInstance().isConnected()) {
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "Not Connected",
                        "Please connect to the microscope server first."));
                return false;
            }

            return true;
        }

        /**
         * Gets the default sample name from the current image.
         * Uses the actual image file name, not metadata which may be project name.
         */
        private String getDefaultSampleName() {
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName != null) {
                return imageName;
            }
            return "Sample_" + System.currentTimeMillis();
        }

        /**
         * Initializes workflow state from the consolidated dialog config.
         */
        private CompletableFuture<WorkflowState> initializeFromConfig(ExistingImageAcquisitionConfig config) {
            if (config == null) {
                return CompletableFuture.completedFuture(null);
            }

            logger.info("Initializing workflow from consolidated config");

            // Create sample setup result
            state.sample = new SampleSetupController.SampleSetupResult(
                    config.sampleName(),
                    config.projectsFolder(),
                    config.modality(),
                    config.objective(),
                    config.detector()
            );

            // Store alignment choice
            state.alignmentChoice = new AlignmentSelectionController.AlignmentChoice(
                    config.useExistingAlignment(),
                    config.selectedTransform(),
                    config.alignmentConfidence(),
                    false  // Not auto-selected since user explicitly chose
            );

            // Store hardware selections
            state.modality = config.modality();
            state.objective = config.objective();
            state.detector = config.detector();

            // Store refinement choice
            state.refinementChoice = convertRefinementChoice(config.refinementChoice());

            // Note: Green box params are handled by ExistingAlignmentPath, not stored here

            // Store angle overrides
            state.angleOverrides = config.angleOverrides();

            // Store whether this is an existing project
            state.isExistingProject = config.isExistingProject();

            logger.info("Config initialized: sample={}, modality={}, useExisting={}, refinement={}",
                    config.sampleName(), config.modality(),
                    config.useExistingAlignment(), config.refinementChoice());

            return CompletableFuture.completedFuture(state);
        }

        /**
         * Converts the dialog's refinement choice to the workflow's enum.
         */
        private RefinementSelectionController.RefinementChoice convertRefinementChoice(RefinementChoice choice) {
            return switch (choice) {
                case NONE -> RefinementSelectionController.RefinementChoice.NONE;
                case SINGLE_TILE -> RefinementSelectionController.RefinementChoice.SINGLE_TILE;
                case FULL_MANUAL -> RefinementSelectionController.RefinementChoice.FULL_MANUAL;
            };
        }

        /**
         * Checks for existing slide-specific alignment.
         */
        private CompletableFuture<WorkflowState> checkExistingSlideAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Checking for existing slide-specific alignment");

            return AlignmentHelper.checkForSlideAlignment(gui, state.sample)
                    .thenApply(slideResult -> {
                        if (slideResult != null) {
                            state.useExistingSlideAlignment = true;
                            state.transform = slideResult.getTransform();
                            state.alignmentConfidence = slideResult.getConfidence();
                            state.alignmentSource = slideResult.getSource();
                            logger.info("Found slide-specific alignment - confidence: {}",
                                    String.format("%.2f", state.alignmentConfidence));
                        }
                        return state;
                    });
        }

        /**
         * Routes to the appropriate sub-workflow based on selections.
         * All paths delegate to the existing working implementations.
         */
        private CompletableFuture<WorkflowState> routeSubWorkflow(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // If we have slide-specific alignment with no refinement, use the fast path
            // This still delegates to the working implementation
            if (state.useExistingSlideAlignment &&
                    state.refinementChoice == RefinementSelectionController.RefinementChoice.NONE) {
                logger.info("Using slide-specific alignment - delegating to existing workflow logic");
                return processSlideSpecificAlignment(state);
            }

            // Route based on alignment choice - both delegate to working implementations
            if (state.alignmentChoice != null && state.alignmentChoice.useExistingAlignment()) {
                logger.info("Routing to existing alignment path");
                return processExistingAlignmentPath(state);
            } else {
                logger.info("Routing to manual alignment path");
                return processManualAlignmentPath(state);
            }
        }

        /**
         * Processes slide-specific alignment (fast path when alignment already exists).
         * Reuses the working workflow logic for proper image validation and annotation handling.
         */
        private CompletableFuture<WorkflowState> processSlideSpecificAlignment(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Processing slide-specific alignment with existing transform");

            // Set the transform
            MicroscopeController.getInstance().setCurrentTransform(state.transform);

            // Delegate to ProjectHelper for proper project setup
            return ProjectHelper.setupProject(gui, state.sample)
                    .thenCompose(projectInfo -> {
                        if (projectInfo == null) {
                            throw new RuntimeException("Project setup failed");
                        }
                        state.projectInfo = projectInfo;

                        // Validate and flip image if needed (important for correct coordinates)
                        @SuppressWarnings("unchecked")
                        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.getCurrentProject();
                        return ImageFlipHelper.validateAndFlipIfNeeded(gui, project, state.sample);
                    })
                    .thenApply(validated -> {
                        if (!validated) {
                            throw new RuntimeException("Image validation failed");
                        }

                        // Get pixel size from preferences (macro pixel size for annotation creation)
                        state.pixelSize = getPixelSizeFromPreferences();

                        // Use selected classes or preferences
                        List<String> validClasses = (state.selectedAnnotationClasses != null && !state.selectedAnnotationClasses.isEmpty())
                                ? state.selectedAnnotationClasses
                                : PersistentPreferences.getSelectedAnnotationClasses();

                        // Ensure annotations exist using the working implementation
                        state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize, validClasses);

                        if (state.annotations.isEmpty()) {
                            throw new RuntimeException("No valid annotations found");
                        }

                        logger.info("Slide-specific alignment ready with {} annotations", state.annotations.size());
                        return state;
                    });
        }

        /**
         * Gets pixel size from preferences (macro image pixel size).
         * Reuses the same logic as the working workflow.
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
                    throw new IllegalStateException("Invalid macro image pixel size: " + pixelSize);
                }
                logger.debug("Using macro pixel size from preferences: {} um", pixelSize);
                return pixelSize;
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid macro image pixel size format: '" + pixelSizeStr + "'");
            }
        }

        /**
         * Processes the existing alignment path (green box detection, transform creation, etc.).
         * Delegates to the existing working ExistingAlignmentPath class.
         */
        private CompletableFuture<WorkflowState> processExistingAlignmentPath(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Delegating to ExistingAlignmentPath for transform pipeline");

            // Delegate to the existing working implementation
            return new ExistingAlignmentPath(gui, state).execute()
                    .thenApply(legacyState -> {
                        // Copy back relevant state from the working implementation
                        state.transform = legacyState.transform;
                        state.annotations = legacyState.annotations;
                        state.projectInfo = legacyState.projectInfo;
                        state.pixelSize = legacyState.pixelSize;
                        return state;
                    });
        }

        /**
         * Processes the manual alignment path.
         * Delegates to the existing working ManualAlignmentPath class.
         */
        private CompletableFuture<WorkflowState> processManualAlignmentPath(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Delegating to ManualAlignmentPath for alignment");

            // Delegate to the existing working implementation
            return new ManualAlignmentPath(gui, state).execute()
                    .thenApply(legacyState -> {
                        // Copy back relevant state
                        state.transform = legacyState.transform;
                        state.annotations = legacyState.annotations;
                        state.projectInfo = legacyState.projectInfo;
                        state.pixelSize = legacyState.pixelSize;
                        return state;
                    });
        }

        /**
         * Handles refinement based on user's choice.
         */
        private CompletableFuture<WorkflowState> handleRefinement(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            switch (state.refinementChoice) {
                case NONE:
                    logger.info("Proceeding without refinement");
                    return CompletableFuture.completedFuture(state);

                case SINGLE_TILE:
                    logger.info("Performing single-tile refinement");
                    return performSingleTileRefinement(state);

                case FULL_MANUAL:
                    logger.info("Full manual alignment requested - switching to manual path");
                    return processManualAlignmentPath(state);

                default:
                    return CompletableFuture.completedFuture(state);
            }
        }

        /**
         * Performs single-tile refinement.
         */
        private CompletableFuture<WorkflowState> performSingleTileRefinement(WorkflowState state) {
            if (state.annotations == null || state.annotations.isEmpty()) {
                logger.warn("No annotations available for refinement");
                return CompletableFuture.completedFuture(state);
            }

            // Create tiles if needed
            // Uses same inversion settings as acquisition (from global preferences)
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
                state.refinementTile = result.selectedTile;
                saveRefinedAlignment(state);
                return state;
            });
        }

        /**
         * Saves the refined alignment.
         */
        private void saveRefinedAlignment(WorkflowState state) {
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = state.projectInfo != null
                    ? (Project<BufferedImage>) state.projectInfo.getCurrentProject()
                    : gui.getProject();

            if (project == null || state.transform == null) {
                return;
            }

            // Get the actual image file name (not metadata name which may be project name)
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

            if (imageName != null) {
                AffineTransformManager.saveSlideAlignment(
                        project, imageName, state.modality, state.transform, null);
                logger.info("Saved refined alignment for image: {}", imageName);
            }
        }

        /**
         * Performs the acquisition phase.
         */
        private CompletableFuture<WorkflowState> performAcquisition(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            logger.info("Starting acquisition phase");

            return new AcquisitionManager(gui, state).execute()
                    .thenApply(legacyState -> {
                        if (legacyState != null) {
                            // Copy stitching futures back from legacy state to V2 state
                            state.stitchingFutures.addAll(legacyState.stitchingFutures);
                        }
                        return state;
                    });
        }

        /**
         * Waits for all stitching operations to complete.
         */
        private CompletableFuture<WorkflowState> waitForCompletion(WorkflowState state) {
            if (state == null || state.stitchingFutures.isEmpty()) {
                return CompletableFuture.completedFuture(state);
            }

            logger.info("Waiting for {} stitching operations to complete", state.stitchingFutures.size());

            return CompletableFuture.allOf(
                    state.stitchingFutures.toArray(new CompletableFuture[0])
            ).thenApply(v -> state);
        }

        /**
         * Cleans up resources after workflow completion.
         */
        private void cleanup() {
            logger.info("Workflow completed - cleaning up");
        }

        /**
         * Shows success notification.
         */
        private void showSuccessNotification() {
            Platform.runLater(() -> {
                Dialogs.showInfoNotification("Acquisition Complete",
                        "All acquisitions have completed successfully.");
            });
        }

        /**
         * Handles errors during workflow execution.
         */
        private Void handleError(Throwable ex) {
            if (ex instanceof CancellationException) {
                logger.info("Workflow cancelled by user");
            } else {
                logger.error("Workflow error", ex);
                Platform.runLater(() -> {
                    Dialogs.showErrorMessage("Workflow Error",
                            "An error occurred: " + ex.getMessage());
                });
            }
            cleanup();
            return null;
        }
    }

    /**
     * Workflow state container for V2.
     */
    public static class WorkflowState {
        public SampleSetupController.SampleSetupResult sample;
        public AlignmentSelectionController.AlignmentChoice alignmentChoice;
        public AffineTransform transform;
        public ProjectHelper.ProjectInfo projectInfo;
        public List<PathObject> annotations = new ArrayList<>();
        public List<CompletableFuture<Void>> stitchingFutures = new ArrayList<>();
        public double pixelSize;
        public Map<String, Double> angleOverrides;
        public PathObject refinementTile;

        // V2-specific fields
        public String modality;
        public String objective;
        public String detector;
        public boolean isExistingProject;
        public boolean useExistingSlideAlignment;
        public double alignmentConfidence;
        public String alignmentSource;
        public RefinementSelectionController.RefinementChoice refinementChoice =
                RefinementSelectionController.RefinementChoice.NONE;
        public GreenBoxDetector.DetectionParams greenBoxParams;
        public List<String> selectedAnnotationClasses = new ArrayList<>();
    }
}
