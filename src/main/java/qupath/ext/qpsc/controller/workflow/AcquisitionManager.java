package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.AnnotationAcquisitionDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages the acquisition phase of the microscope workflow.
 *
 * <p>This class orchestrates the complete acquisition process including:
 * <ul>
 *   <li>Annotation validation with user confirmation</li>
 *   <li>Rotation angle configuration for multi-modal imaging</li>
 *   <li>Tile generation and transformation to stage coordinates</li>
 *   <li>Acquisition monitoring with progress tracking</li>
 *   <li>Automatic stitching queue management</li>
 *   <li>Error handling and user cancellation support</li>
 * </ul>
 *
 * <p>The acquisition process is performed sequentially for each annotation to ensure
 * proper resource management and allow for user intervention if needed.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AcquisitionManager {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionManager.class);


    /** Maximum time to wait for acquisition completion (5 minutes) */
    private static final int ACQUISITION_TIMEOUT_MS = 300000;

    /** Single-threaded executor for stitching operations to prevent overwhelming system resources */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    private final QuPathGUI gui;
    private final WorkflowState state;

    /**
     * Creates a new acquisition manager.
     *
     * @param gui The QuPath GUI instance
     * @param state The current workflow state containing all necessary information
     */
    public AcquisitionManager(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the complete acquisition phase.
     *
     * <p>This method orchestrates the entire acquisition workflow:
     * <ol>
     *   <li>Validates annotations with user confirmation</li>
     *   <li>Retrieves rotation angles for the imaging modality</li>
     *   <li>Prepares tiles for acquisition</li>
     *   <li>Processes each annotation sequentially</li>
     * </ol>
     *
     * @return CompletableFuture containing the updated workflow state, or null if cancelled/failed
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Starting acquisition phase");

        return validateAnnotations()
                .thenCompose(valid -> {
                    if (!valid) {
                        logger.info("Annotation validation failed or cancelled");
                        return CompletableFuture.completedFuture(null);
                    }
                    return getRotationAngles();
                })
                .thenCompose(this::prepareForAcquisition)
                .thenCompose(this::processAnnotations)
                .thenApply(success -> {
                    if (success) {
                        logger.info("Acquisition phase completed successfully");
                        return state;
                    }
                    throw new RuntimeException("Acquisition failed or was cancelled");
                });
    }

    /**
     * Validates annotations with user confirmation dialog.
     *
     * <p>Shows a dialog listing all valid annotations and allows the user to confirm
     * before proceeding with acquisition.
     *
     * @return CompletableFuture with true if user confirms, false if cancelled
     */
    private CompletableFuture<Boolean> validateAnnotations() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Get all unique annotation classes in current image
        Set<PathClass> existingClasses = MinorFunctions.getExistingClassifications(QP.getCurrentImageData());
        Set<String> existingClassNames = existingClasses.stream()
                .map(PathClass::toString)
                .collect(Collectors.toSet());

        logger.info("Found {} unique annotation classes: {}",
                existingClassNames.size(), existingClassNames);

        // Get preferences
        List<String> preselected = PersistentPreferences.getSelectedAnnotationClasses();

        // Show selection dialog
        return AnnotationAcquisitionDialog.showDialog(existingClassNames, preselected)
                .thenApply(result -> {
                    if (!result.proceed || result.selectedClasses.isEmpty()) {
                        logger.info("Acquisition cancelled or no classes selected");
                        return false;
                    }

                    // Store selected classes in state
                    state.selectedAnnotationClasses = result.selectedClasses;
                    logger.info("User selected {} classes for acquisition: {}",
                            result.selectedClasses.size(), result.selectedClasses);

                    return true;
                });
    }
    /**
     * Retrieves rotation angles configured for the imaging modality.
     *
     * <p>For polarized light imaging or other multi-angle acquisitions, this method
     * retrieves the configured rotation angles and exposure times.
     *
     * @return CompletableFuture containing list of rotation angles with exposure settings
     */
    private CompletableFuture<List<AngleExposure>> getRotationAngles() {
        ModalityHandler handler = ModalityRegistry.getHandler(state.sample.modality());
        logger.info("Getting rotation angles for modality: {}", state.sample.modality());

        return handler.getRotationAngles(state.sample.modality());
    }

    /**
     * Prepares annotations for acquisition by regenerating tiles.
     *
     * <p>This method:
     * <ul>
     *   <li>Retrieves current valid annotations</li>
     *   <li>Cleans up old tiles and directories</li>
     *   <li>Creates fresh tiles based on camera FOV</li>
     *   <li>Transforms tile coordinates to stage space</li>
     * </ul>
     *
     * @param angleExposures List of rotation angles, or null for single acquisition
     * @return CompletableFuture with angle exposures for next phase
     */
    private CompletableFuture<List<AngleExposure>> prepareForAcquisition(
            List<AngleExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            logger.info("Preparing for acquisition with {} angles",
                    angleExposures != null ? angleExposures.size() : 1);

            // Get current annotations
            List<PathObject> currentAnnotations = AnnotationHelper.getCurrentValidAnnotations(
                    state.selectedAnnotationClasses
            );
            if (currentAnnotations.isEmpty()) {
                throw new RuntimeException("No valid annotations for acquisition");
            }

            logger.info("Found {} annotations to acquire", currentAnnotations.size());

            // Clean up old tiles
            TileHelper.deleteAllTiles(gui, state.sample.modality());
            TileHelper.cleanupStaleFolders(
                    state.projectInfo.getTempTileDirectory(),
                    currentAnnotations
            );

            // Create fresh tiles
            TileHelper.createTilesForAnnotations(
                    currentAnnotations,
                    state.sample,
                    state.projectInfo.getTempTileDirectory(),
                    state.projectInfo.getImagingModeWithIndex(),
                    state.pixelSize
            );

            // Transform tile configurations to stage coordinates
            try {
                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                        state.projectInfo.getTempTileDirectory(),
                        state.transform
                );
                logger.info("Transformed tile configurations for: {}", modifiedDirs);
            } catch (IOException e) {
                logger.error("Failed to transform tile configurations", e);
                throw new RuntimeException("Failed to transform tile configurations", e);
            }

            state.annotations = currentAnnotations;
            return angleExposures;
        });
    }

    /**
     * Processes all annotations for acquisition.
     *
     * <p>Annotations are processed sequentially to:
     * <ul>
     *   <li>Provide clear progress feedback</li>
     *   <li>Allow cancellation between annotations</li>
     *   <li>Prevent resource exhaustion</li>
     *   <li>Enable immediate stitching after each acquisition</li>
     * </ul>
     *
     * @param angleExposures Rotation angles for multi-modal acquisition
     * @return CompletableFuture with true if all successful, false if any failed/cancelled
     */
    private CompletableFuture<Boolean> processAnnotations(
            List<AngleExposure> angleExposures) {

        if (state.annotations.isEmpty()) {
            logger.warn("No annotations to process");
            return CompletableFuture.completedFuture(false);
        }

        // Show initial progress notification
        showAcquisitionStartNotification(angleExposures);

        // Process each annotation sequentially
        CompletableFuture<Boolean> acquisitionChain = CompletableFuture.completedFuture(true);

        for (int i = 0; i < state.annotations.size(); i++) {
            final PathObject annotation = state.annotations.get(i);
            final int index = i + 1;
            final int total = state.annotations.size();

            acquisitionChain = acquisitionChain.thenCompose(previousSuccess -> {
                if (!previousSuccess) {
                    logger.info("Stopping acquisition due to previous failure");
                    return CompletableFuture.completedFuture(false);
                }

                logger.info("Processing annotation {} of {}: {}", index, total, annotation.getName());

                showProgressNotification(index, total, annotation.getName());

                return performSingleAnnotationAcquisition(annotation, angleExposures)
                        .thenApply(success -> {
                            if (success) {
                                // Launch stitching asynchronously after successful acquisition
                                launchStitching(annotation, angleExposures);
                            }
                            return success;
                        });
            });
        }

        return acquisitionChain;
    }

    /**
     * Performs acquisition for a single annotation.
     *
     * <p>This method:
     * <ul>
     *   <li>Builds the acquisition command with all parameters</li>
     *   <li>Starts the acquisition on the microscope</li>
     *   <li>Monitors progress with cancellation support</li>
     * </ul>
     *
     * @param annotation The annotation to acquire
     * @param angleExposures Rotation angles for this acquisition
     * @return CompletableFuture with true if successful, false if failed/cancelled
     */
    private CompletableFuture<Boolean> performSingleAnnotationAcquisition(
            PathObject annotation,
            List<AngleExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting acquisition for annotation: {}", annotation.getName());

                // Get configuration file path
                String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configFile);

                // Extract modality base name
                String modalityWithIndex = state.projectInfo.getImagingModeWithIndex();
                String baseModality = modalityWithIndex;
                if (baseModality.matches(".*_\\d+$")) {
                    baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
                }

                // Find hardware configuration
                String objective = null;
                String detector = null;
                double pixelSize = 1.0;

                List<Object> profiles = configManager.getList("acq_profiles_new", "profiles");
                if (profiles != null) {
                    for (Object profileObj : profiles) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> profile = (Map<String, Object>) profileObj;
                        String profileModality = (String) profile.get("modality");

                        if (profileModality != null &&
                                (profileModality.equals(baseModality) ||
                                        modalityWithIndex.startsWith(profileModality))) {
                            objective = (String) profile.get("objective");
                            detector = (String) profile.get("detector");

                            if (objective != null && detector != null) {
                                pixelSize = configManager.getModalityPixelSize(profileModality, objective, detector);
                                logger.debug("Found hardware config: obj={}, det={}, px={}",
                                        objective, detector, pixelSize);
                                break;
                            }
                        }
                    }
                }

                if (objective == null || detector == null) {
                    throw new RuntimeException("Hardware configuration not found for modality: " + modalityWithIndex);
                }

                // Get background correction settings
                boolean bgEnabled = configManager.getBoolean("modalities", baseModality,
                        "background_correction", "enabled");
                String bgMethod = configManager.getString("modalities", baseModality,
                        "background_correction", "method");
                String bgFolder = configManager.getString("modalities", baseModality,
                        "background_correction", "base_folder");

                // Get autofocus parameters for this objective
                //TODO hardcoded values are bad! need to fix
                Map<String, Object> afParams = configManager.getAutofocusParams(objective);
                int afTiles = 5;    // defaults
                int afSteps = 11;
                double afRange = 50.0;

                if (afParams != null) {
                    if (afParams.get("n_tiles") instanceof Number) {
                        afTiles = ((Number) afParams.get("n_tiles")).intValue();
                    }
                    if (afParams.get("n_steps") instanceof Number) {
                        afSteps = ((Number) afParams.get("n_steps")).intValue();
                    }
                    if (afParams.get("search_range_um") instanceof Number) {
                        afRange = ((Number) afParams.get("search_range_um")).doubleValue();
                    }
                }

                // Determine processing pipeline dynamically
                List<String> processingSteps = new ArrayList<>();

                if (configManager.detectorRequiresDebayering(detector)) {
                    processingSteps.add("debayer");
                }

                if (bgEnabled && bgFolder != null) {
                    processingSteps.add("background_correction");
                }

                // Build enhanced acquisition command
                AcquisitionCommandBuilder acquisitionBuilder = AcquisitionCommandBuilder.builder()
                        .yamlPath(configFile)
                        .projectsFolder(state.sample.projectsFolder().getAbsolutePath())
                        .sampleLabel(state.sample.sampleName())
                        .scanType(modalityWithIndex)
                        .regionName(annotation.getName())
                        .angleExposures(angleExposures)
                        .hardware(objective, detector, pixelSize)
                        .autofocus(afTiles, afSteps, afRange)
                        .processingPipeline(processingSteps);

                // Only add background correction if enabled and configured
                if (bgEnabled && bgMethod != null && bgFolder != null) {
                    acquisitionBuilder.backgroundCorrection(true, bgMethod, bgFolder);
                }

                logger.info("Acquisition parameters for {}:", annotation.getName());
                logger.info("  Config: {}", configFile);
                logger.info("  Sample: {}", state.sample.sampleName());
                logger.info("  Hardware: {} / {} @ {} µm/px", objective, detector, pixelSize);
                logger.info("  Autofocus: {} tiles, {} steps, {} µm range", afTiles, afSteps, afRange);
                logger.info("  Processing: {}", processingSteps);
                if (bgEnabled) {
                    logger.info("  Background correction: {} method from {}", bgMethod, bgFolder);
                }
                if (angleExposures != null && !angleExposures.isEmpty()) {
                    logger.info("  Angles: {}", angleExposures);
                }
                String commandString = acquisitionBuilder.buildSocketMessage();
                MinorFunctions.saveAcquisitionCommand(
                        commandString,
                        state.sample.projectsFolder().getAbsolutePath(),
                        state.sample.sampleName(),
                        modalityWithIndex,
                        annotation.getName()
                );
                // Start acquisition
                MicroscopeController.getInstance().startAcquisition(acquisitionBuilder);

                // Monitor progress
                return monitorAcquisition(annotation, angleExposures);

            } catch (Exception e) {
                logger.error("Acquisition failed for {}", annotation.getName(), e);
                showAcquisitionError(annotation.getName(), e.getMessage());
                return false;
            }
        });
    }
    /**
     * Monitors acquisition progress with cancellation support.
     *
     * <p>This method:
     * <ul>
     *   <li>Calculates expected file count based on tiles and angles</li>
     *   <li>Shows a progress bar with cancel button</li>
     *   <li>Polls the microscope server for status updates</li>
     *   <li>Handles user cancellation requests</li>
     * </ul>
     *
     * @param annotation The annotation being acquired
     * @param angleExposures Rotation angles for calculating expected files
     * @return true if completed successfully, false if cancelled/failed
     * @throws IOException if communication with microscope fails
     */
    private boolean monitorAcquisition(PathObject annotation,
                                       List<AngleExposure> angleExposures) throws IOException {

        MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

        // Calculate expected files
        String tileDirPath = Paths.get(
                state.sample.projectsFolder().getAbsolutePath(),
                state.sample.sampleName(),
                state.projectInfo.getImagingModeWithIndex(),
                annotation.getName()
        ).toString();

        int tilesPerAngle = MinorFunctions.countTifEntriesInTileConfig(List.of(tileDirPath));
        int expectedFiles = tilesPerAngle;
        if (angleExposures != null && !angleExposures.isEmpty()) {
            expectedFiles *= angleExposures.size();
        }

        logger.info("Expected files: {} ({}x{} angles)", expectedFiles, tilesPerAngle,
                angleExposures != null ? angleExposures.size() : 1);

        // Create progress counter
        AtomicInteger progressCounter = new AtomicInteger(0);

        // Show progress bar with cancel support
        UIFunctions.ProgressHandle progressHandle = null;
        if (expectedFiles > 0) {
            progressHandle = UIFunctions.showProgressBarAsync(
                    progressCounter,
                    expectedFiles,
                    ACQUISITION_TIMEOUT_MS,
                    true // Show cancel button
            );

            // Set up cancel callback
            final UIFunctions.ProgressHandle finalHandle = progressHandle;
            progressHandle.setCancelCallback(v -> {
                logger.info("User requested acquisition cancellation");
                try {
                    socketClient.cancelAcquisition();
                } catch (IOException e) {
                    logger.error("Failed to send cancel command", e);
                }
            });
        }

        try {
            // Monitor acquisition with regular status updates
            MicroscopeSocketClient.AcquisitionState finalState =
                    socketClient.monitorAcquisition(
                            progress -> progressCounter.set(progress.current),
                            500,    // Poll every 500ms for responsive UI
                            ACQUISITION_TIMEOUT_MS
                    );

            // Check final state
            switch (finalState) {
                case COMPLETED:
                    logger.info("Acquisition completed successfully for {}", annotation.getName());
                    return true;

                case CANCELLED:
                    logger.warn("Acquisition was cancelled for {}", annotation.getName());
                    showCancellationNotification();
                    return false;

                case FAILED:
                    throw new RuntimeException("Acquisition failed on server");

                default:
                    logger.warn("Unexpected acquisition state: {}", finalState);
                    return false;
            }

        } catch (InterruptedException e) {
            logger.error("Acquisition monitoring interrupted", e);
            throw new RuntimeException(e);
        } finally {
            if (progressHandle != null) {
                progressHandle.close();
            }
        }
    }

    /**
     * Launches stitching for a completed acquisition.
     *
     * <p>Stitching is performed asynchronously on a dedicated thread to:
     * <ul>
     *   <li>Allow the next acquisition to start immediately</li>
     *   <li>Prevent UI blocking during intensive stitching operations</li>
     *   <li>Process stitching jobs sequentially to manage resources</li>
     * </ul>
     *
     * @param annotation The annotation that was acquired
     * @param angleExposures Rotation angles used in acquisition
     */
    private void launchStitching(PathObject annotation,
                                 List<AngleExposure> angleExposures) {

        // Get required parameters for stitching
        String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFile);

        double pixelSize;
        try {
            pixelSize = mgr.getPixelSizeForModality(state.sample.modality());

            // Continue with stitching using the determined pixel size...
        } catch (IllegalArgumentException e) {
            logger.error("Failed to determine pixel size for stitching: {}", e.getMessage());
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "Cannot determine pixel size for modality: " + state.sample.modality() +
                                    "\n\nPlease check acquisition profile configuration.",
                            "Configuration Error"
                    )
            );
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        ModalityHandler handler = ModalityRegistry.getHandler(state.sample.modality());

        // Calculate offset for this annotation (for metadata)
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(
                annotation, state.transform);
        logger.info("Annotation {} offset from slide corner: ({}, {}) µm",
                annotation.getName(), offset[0], offset[1]);

        // Create stitching future
        CompletableFuture<Void> stitchFuture = StitchingHelper.performAnnotationStitching(
                annotation,
                state.sample,
                state.projectInfo.getImagingModeWithIndex(),
                angleExposures,
                pixelSize,
                gui,
                project,
                STITCH_EXECUTOR,
                handler
        );

        state.stitchingFutures.add(stitchFuture);
        logger.info("Launched stitching for annotation: {}", annotation.getName());
    }
    // UI notification methods

    /**
     * Shows initial notification about acquisition start.
     */
    private void showAcquisitionStartNotification(List<AngleExposure> angleExposures) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Acquisition Progress");
            alert.setHeaderText("Starting acquisition workflow");
            alert.setContentText(String.format(
                    "Processing %d annotations with %d rotation angles each.\n" +
                            "This may take several minutes per annotation.",
                    state.annotations.size(),
                    angleExposures == null || angleExposures.isEmpty() ? 1 : angleExposures.size()
            ));
            alert.show();

            // Auto-close after 3 seconds
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pause.setOnFinished(e -> alert.close());
            pause.play();
        });
    }

    /**
     * Shows progress notification for current annotation.
     */
    private void showProgressNotification(int current, int total, String annotationName) {
        Platform.runLater(() -> {
            String message = String.format("Acquiring annotation %d of %d: %s",
                    current, total, annotationName);
            Dialogs.showInfoNotification("Acquisition Progress", message);
        });
    }

    /**
     * Shows error notification for failed acquisition.
     */
    private void showAcquisitionError(String annotationName, String errorMessage) {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition failed for " + annotationName + ":\n\n" + errorMessage,
                        "Acquisition Error"
                )
        );
    }

    /**
     * Shows notification that acquisition was cancelled.
     */
    private void showCancellationNotification() {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition was cancelled by user request",
                        "Acquisition Cancelled"
                )
        );
    }
}
