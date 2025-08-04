// File: qupath/ext/qpsc/controller/workflow/AcquisitionManager.java
package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TilingRequest;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages the acquisition phase of the workflow.
 */
public class AcquisitionManager {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionManager.class);
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};
    private static final int ACQUISITION_TIMEOUT_MS = 300000; // 5 minutes

    private final QuPathGUI gui;
    private final WorkflowState state;

    // Single-threaded executor for stitching
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    public AcquisitionManager(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the acquisition phase.
     */
    public CompletableFuture<WorkflowState> execute() {
        return validateAnnotations()
                .thenCompose(valid -> {
                    if (!valid) return CompletableFuture.completedFuture(null);
                    return getRotationAngles();
                })
                .thenCompose(this::prepareForAcquisition)
                .thenCompose(this::processAnnotations)
                .thenApply(success -> {
                    if (success) return state;
                    throw new RuntimeException("Acquisition failed or was cancelled");
                });
    }

    /**
     * Validates annotations with user confirmation.
     */
    private CompletableFuture<Boolean> validateAnnotations() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        UIFunctions.checkValidAnnotationsGUI(
                Arrays.asList(VALID_ANNOTATION_CLASSES),
                future::complete
        );

        return future;
    }

    /**
     * Gets rotation angles for the modality.
     */
    private CompletableFuture<List<RotationManager.TickExposure>> getRotationAngles() {
        RotationManager rotationManager = new RotationManager(state.sample.modality());
        logger.info("Getting rotation angles for modality: {}", state.sample.modality());

        return rotationManager.getRotationTicksWithExposure(state.sample.modality());
    }

    /**
     * Prepares annotations for acquisition by regenerating tiles.
     */
    private CompletableFuture<List<RotationManager.TickExposure>> prepareForAcquisition(
            List<RotationManager.TickExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            logger.info("Preparing for acquisition with {} angles",
                    angleExposures != null ? angleExposures.size() : 1);

            // Regenerate tiles
            List<PathObject> currentAnnotations = AnnotationHelper.getCurrentValidAnnotations();

            if (currentAnnotations.isEmpty()) {
                throw new RuntimeException("No valid annotations for acquisition");
            }

            // Delete old tiles and regenerate
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

            // Transform tile configurations
            try {
                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                        state.projectInfo.getTempTileDirectory(),
                        state.transform
                );
                logger.info("Transformed tile configurations for: {}", modifiedDirs);
            } catch (IOException e) {
                throw new RuntimeException("Failed to transform tile configurations", e);
            }

            state.annotations = currentAnnotations;
            return angleExposures;
        });
    }

    /**
     * Processes all annotations for acquisition.
     */
    private CompletableFuture<Boolean> processAnnotations(
            List<RotationManager.TickExposure> angleExposures) {

        if (state.annotations.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        // Show progress notification
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
                                // Launch stitching asynchronously
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
     */
    private CompletableFuture<Boolean> performSingleAnnotationAcquisition(
            PathObject annotation,
            List<RotationManager.TickExposure> angleExposures) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting acquisition for annotation: {}", annotation.getName());

                // Build acquisition command
                String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                AcquisitionCommandBuilder builder = AcquisitionCommandBuilder.builder()
                        .yamlPath(configFile)
                        .projectsFolder(state.sample.projectsFolder().getAbsolutePath())
                        .sampleLabel(state.sample.sampleName())
                        .scanType(state.projectInfo.getImagingModeWithIndex())
                        .regionName(annotation.getName())
                        .angleExposures(angleExposures);

                // Start acquisition
                MicroscopeController.getInstance().startAcquisition(builder);

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
     */
    private boolean monitorAcquisition(PathObject annotation,
                                       List<RotationManager.TickExposure> angleExposures) throws IOException {

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

        // Show progress bar
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
            // Monitor acquisition
            MicroscopeSocketClient.AcquisitionState finalState =
                    socketClient.monitorAcquisition(
                            progress -> progressCounter.set(progress.current),
                            500,    // Poll every 500ms
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
            throw new RuntimeException(e);
        } finally {
            if (progressHandle != null) {
                progressHandle.close();
            }
        }
    }

    /**
     * Launches stitching for completed acquisition.
     */
    private void launchStitching(PathObject annotation,
                                 List<RotationManager.TickExposure> angleExposures) {

        // Extract rotation angles
        List<Double> rotationAngles = angleExposures.stream()
                .map(ae -> ae.ticks)
                .collect(Collectors.toList());

        // Get required parameters
        String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFile);
        double pixelSize = mgr.getDouble("imagingMode", state.sample.modality(), "pixelSize_um");

        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        // Create stitching future
        CompletableFuture<Void> stitchFuture = StitchingHelper.performAnnotationStitching(
                annotation,
                state.sample,
                state.projectInfo.getImagingModeWithIndex(),
                rotationAngles,
                pixelSize,
                gui,
                project,
                STITCH_EXECUTOR
        );

        state.stitchingFutures.add(stitchFuture);
        logger.info("Launched stitching for annotation: {}", annotation.getName());
    }

    // UI notification methods

    private void showAcquisitionStartNotification(List<RotationManager.TickExposure> angleExposures) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Acquisition Progress");
            alert.setHeaderText("Starting acquisition workflow");
            alert.setContentText(String.format(
                    "Processing %d annotations with %d rotation angles each.\n" +
                            "This may take several minutes per annotation.",
                    state.annotations.size(),
                    angleExposures != null ? angleExposures.size() : 1
            ));
            alert.show();

            // Auto-close after 3 seconds
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pause.setOnFinished(e -> alert.close());
            pause.play();
        });
    }

    private void showProgressNotification(int current, int total, String annotationName) {
        Platform.runLater(() -> {
            String message = String.format("Acquiring annotation %d of %d: %s",
                    current, total, annotationName);
            Dialogs.showInfoNotification("Acquisition Progress", message);
        });
    }

    private void showAcquisitionError(String annotationName, String errorMessage) {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition failed for " + annotationName + ":\n\n" + errorMessage,
                        "Acquisition Error"
                )
        );
    }

    private void showCancellationNotification() {
        Platform.runLater(() ->
                UIFunctions.notifyUserOfError(
                        "Acquisition was cancelled by user request",
                        "Acquisition Cancelled"
                )
        );
    }
}
