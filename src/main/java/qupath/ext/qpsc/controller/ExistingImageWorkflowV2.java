package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.*;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.ExistingImageAcquisitionConfig;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.RefinementChoice;
import qupath.ext.qpsc.utilities.*;
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

/**
 * ExistingImageWorkflowV2 - Consolidated dialog version of the Existing Image workflow.
 *
 * <p>This workflow uses the new {@link ExistingImageAcquisitionController} consolidated
 * dialog to gather all configuration in a single step, then routes to appropriate
 * sub-workflows based on user selections.
 *
 * <p>Key differences from ExistingImageWorkflow:
 * <ul>
 *   <li>Single consolidated dialog instead of multiple sequential dialogs</li>
 *   <li>Confidence-based recommendations integrated into the main dialog</li>
 *   <li>Refinement options presented upfront</li>
 *   <li>Green box parameters editable in advanced section</li>
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

            // Step 2: Get annotation count for preview
            int annotationCount = countAnnotations();

            // Step 3: Get default sample name from current image
            String defaultSampleName = getDefaultSampleName();

            // Step 4: Show consolidated dialog and process results
            ExistingImageAcquisitionController.showDialog(defaultSampleName, annotationCount)
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
         * Counts annotations in the current image.
         */
        private int countAnnotations() {
            ImageData<?> imageData = gui.getImageData();
            if (imageData == null || imageData.getHierarchy() == null) {
                return 0;
            }

            // Count annotations matching our target classes
            List<String> targetClasses = Arrays.asList("Tissue", "Scanned Area", "Bounding Box");
            return (int) imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(ann -> {
                        String className = ann.getPathClass() != null ? ann.getPathClass().getName() : "";
                        return targetClasses.contains(className) || targetClasses.isEmpty();
                    })
                    .count();
        }

        /**
         * Gets the default sample name from the current image.
         */
        private String getDefaultSampleName() {
            if (gui.getImageData() != null) {
                String fullName = gui.getImageData().getServer().getMetadata().getName();
                return qupath.lib.common.GeneralTools.stripExtension(fullName);
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
                    config.modality() + "_" + config.objective()
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

            // Store green box params if using existing alignment
            state.greenBoxParams = config.greenBoxParams();

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
         */
        private CompletableFuture<WorkflowState> routeSubWorkflow(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // If we have slide-specific alignment, we may be able to skip to acquisition
            if (state.useExistingSlideAlignment &&
                    state.refinementChoice == RefinementSelectionController.RefinementChoice.NONE) {
                logger.info("Using slide-specific alignment directly - skipping sub-workflows");
                return setupProjectAndAnnotations(state);
            }

            // Route based on alignment choice
            if (state.alignmentChoice != null && state.alignmentChoice.useExistingAlignment()) {
                logger.info("Routing to existing alignment path");
                return processExistingAlignmentPath(state);
            } else {
                logger.info("Routing to manual alignment path");
                return processManualAlignmentPath(state);
            }
        }

        /**
         * Processes the existing alignment path (green box detection, etc.).
         */
        private CompletableFuture<WorkflowState> processExistingAlignmentPath(WorkflowState state) {
            return setupProjectAndAnnotations(state)
                    .thenCompose(s -> {
                        if (s == null) return CompletableFuture.completedFuture(null);

                        // Set up transform from selected preset
                        if (s.alignmentChoice != null && s.alignmentChoice.selectedTransform() != null) {
                            s.transform = s.alignmentChoice.selectedTransform().getTransform();
                            MicroscopeController.getInstance().setCurrentTransform(s.transform);
                            logger.info("Applied transform from preset: {}",
                                    s.alignmentChoice.selectedTransform().getName());
                        }

                        return CompletableFuture.completedFuture(s);
                    })
                    .thenCompose(this::processGreenBoxDetection);
        }

        /**
         * Processes green box detection for existing alignment path.
         */
        private CompletableFuture<WorkflowState> processGreenBoxDetection(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            // Use stored green box params if available
            if (state.greenBoxParams != null) {
                logger.info("Using green box params from consolidated dialog");
                // Green box detection would happen here
                // For now, we proceed - the actual detection runs during tile creation
            }

            return CompletableFuture.completedFuture(state);
        }

        /**
         * Processes the manual alignment path.
         */
        private CompletableFuture<WorkflowState> processManualAlignmentPath(WorkflowState state) {
            return setupProjectAndAnnotations(state)
                    .thenCompose(s -> {
                        if (s == null) return CompletableFuture.completedFuture(null);

                        // Manual alignment path uses ManualAlignmentPath class
                        return new ManualAlignmentPath(gui, convertToLegacyState(s)).execute()
                                .thenApply(legacyState -> {
                                    // Copy back relevant state
                                    s.transform = legacyState.transform;
                                    s.annotations = legacyState.annotations;
                                    return s;
                                });
                    });
        }

        /**
         * Sets up project and loads annotations.
         */
        private CompletableFuture<WorkflowState> setupProjectAndAnnotations(WorkflowState state) {
            if (state == null) return CompletableFuture.completedFuture(null);

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Setup project
                    ProjectHelper.ProjectInfo projectInfo = ProjectHelper.setupProject(
                            gui, state.sample.sampleName(), state.sample.projectsFolder());
                    state.projectInfo = projectInfo;

                    // Get pixel size
                    ImageData<?> imageData = gui.getImageData();
                    state.pixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();

                    // Load annotations
                    List<String> targetClasses = Arrays.asList("Tissue", "Scanned Area", "Bounding Box");
                    state.annotations = new ArrayList<>(
                            imageData.getHierarchy().getAnnotationObjects().stream()
                                    .filter(ann -> {
                                        String className = ann.getPathClass() != null ?
                                                ann.getPathClass().getName() : "";
                                        return targetClasses.contains(className);
                                    })
                                    .toList()
                    );

                    logger.info("Loaded {} annotations for acquisition", state.annotations.size());

                    return state;
                } catch (Exception e) {
                    logger.error("Failed to setup project and annotations", e);
                    throw new CompletionException(e);
                }
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

            String imageName = null;
            if (gui.getImageData() != null) {
                String fullName = gui.getImageData().getServer().getMetadata().getName();
                imageName = qupath.lib.common.GeneralTools.stripExtension(fullName);
            }

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

            return AcquisitionHelper.executeAcquisition(
                    gui, state.annotations, state.transform,
                    state.sample, state.projectInfo,
                    state.pixelSize, state.angleOverrides
            ).thenApply(futures -> {
                state.stitchingFutures.addAll(futures);
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
         * Converts V2 state to legacy state format for compatibility.
         */
        private ExistingImageWorkflow.WorkflowState convertToLegacyState(WorkflowState v2State) {
            ExistingImageWorkflow.WorkflowState legacyState = new ExistingImageWorkflow.WorkflowState();
            legacyState.sample = v2State.sample;
            legacyState.alignmentChoice = v2State.alignmentChoice;
            legacyState.transform = v2State.transform;
            legacyState.projectInfo = v2State.projectInfo;
            legacyState.annotations = v2State.annotations;
            legacyState.pixelSize = v2State.pixelSize;
            legacyState.angleOverrides = v2State.angleOverrides;
            return legacyState;
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
    }
}
