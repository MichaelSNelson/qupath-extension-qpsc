package qupath.ext.qpsc.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.scripting.QP;
import qupath.fx.dialogs.Dialogs;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow - Handles acquisition workflows for images already open in QuPath.
 * <p>
 * Supports two paths:
 * - Path A: Use existing saved alignment with green box detection
 * - Path B: Create new alignment through manual annotation workflow
 *
 * @author Mike Nelson
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Single-threaded executor for stitching operations
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

    /**
     * Entry point for the workflow. Follows the sequence:
     * 1. Check for open image
     * 2. Sample setup dialog
     * 3. Alignment selection (existing or manual)
     * 4. Path-specific dialogs and project creation
     * 5. Acquisition and stitching
     */
    public static void run() {
        logger.info("Starting Existing Image Workflow...");

        QuPathGUI gui = QuPathGUI.getInstance();

        // Verify we have an open image
        if (gui.getImageData() == null) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No image is currently open. Please open an image first.",
                            "No Image")
            );
            return;
        }

        // Step 1: Sample Setup Dialog
        SampleSetupController.showDialog()
                .thenCompose(sample -> {
                    if (sample == null) {
                        logger.info("Sample setup cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Step 2: Alignment Selection Dialog
                    return AlignmentSelectionController.showDialog(gui, sample.modality())
                            .thenApply(alignment -> alignment != null ? new WorkflowContext(sample, alignment) : null);
                })
                .thenCompose(context -> {
                    if (context == null) {
                        logger.info("Workflow cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Step 3: Path-specific handling
                    if (context.alignment.useExistingAlignment()) {
                        return handleExistingAlignmentPath(gui, context);
                    } else {
                        return handleManualAlignmentPath(gui, context);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Workflow failed", ex);
                    Platform.runLater(() ->
                            UIFunctions.notifyUserOfError(
                                    "Workflow failed: " + ex.getMessage(),
                                    "Error")
                    );
                    return null;
                });
    }

    /**
     * Handle Path A: Using existing alignment
     */
    private static CompletableFuture<Void> handleExistingAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path A: Using existing alignment");

        // Check macro image availability
        if (!MacroImageUtility.isMacroImageAvailable(gui)) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No macro image found. Cannot use existing alignment.",
                            "Macro Image Required")
            );
            return CompletableFuture.completedFuture(null);
        }

        // Get macro pixel size
        return getMacroPixelSize()
                .thenCompose(pixelSize -> {
                    if (pixelSize == null) return CompletableFuture.completedFuture(null);

                    // Show green box preview
                    BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
                    GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();

                    return GreenBoxPreviewController.showPreviewDialog(macroImage, params)
                            .thenApply(greenBoxResult -> {
                                if (greenBoxResult == null) return null;
                                return new AlignmentContext(context, pixelSize, greenBoxResult);
                            });
                })
                .thenCompose(alignContext -> {
                    if (alignContext == null) return CompletableFuture.completedFuture(null);

                    // Now create/setup project
                    return setupProject(gui, alignContext.context.sample)
                            .thenCompose(projectInfo -> {
                                if (projectInfo == null) return CompletableFuture.completedFuture(null);

                                // Continue with existing alignment workflow
                                return processExistingAlignment(gui, alignContext, projectInfo);
                            });
                });
    }

    /**
     * Handle Path B: Manual alignment
     */
    private static CompletableFuture<Void> handleManualAlignmentPath(
            QuPathGUI gui, WorkflowContext context) {

        logger.info("Path B: Manual alignment");

        // Get macro pixel size
        return getMacroPixelSize()
                .thenCompose(pixelSize -> {
                    if (pixelSize == null) return CompletableFuture.completedFuture(null);

                    // Create/setup project
                    return setupProject(gui, context.sample)
                            .thenApply(projectInfo -> {
                                if (projectInfo == null) return null;
                                return new ManualAlignmentContext(context, pixelSize, projectInfo);
                            });
                })
                .thenCompose(manualContext -> {
                    if (manualContext == null) return CompletableFuture.completedFuture(null);

                    // Continue with manual alignment workflow
                    return processManualAlignment(gui, manualContext);
                });
    }

    /**
     * Get macro pixel size from user
     */
    private static CompletableFuture<Double> getMacroPixelSize() {
        CompletableFuture<Double> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog(
                    PersistentPreferences.getMacroImagePixelSizeInMicrons()
            );
            dialog.setTitle("Macro Image Pixel Size");
            dialog.setHeaderText("Enter the pixel size for the macro image");
            dialog.setContentText("Pixel size (microns):");
            dialog.initModality(Modality.APPLICATION_MODAL);

            Optional<String> result = dialog.showAndWait();
            if (result.isPresent()) {
                try {
                    double pixelSize = Double.parseDouble(result.get());
                    if (pixelSize <= 0 || pixelSize > 100) {
                        throw new NumberFormatException("Invalid pixel size");
                    }
                    PersistentPreferences.setMacroImagePixelSizeInMicrons(result.get());
                    future.complete(pixelSize);
                } catch (NumberFormatException e) {
                    UIFunctions.notifyUserOfError(
                            "Please enter a valid pixel size (0-100 microns)",
                            "Invalid Input"
                    );
                    future.complete(null);
                }
            } else {
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Setup project - create new or use existing
     */
    private static CompletableFuture<ProjectInfo> setupProject(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<ProjectInfo> future = new CompletableFuture<>();

        // Run on JavaFX thread since QPProjectFunctions manipulates GUI
        Platform.runLater(() -> {
            try {
                Map<String, Object> projectDetails;
                boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();

                if (gui.getProject() == null) {
                    logger.info("Creating new project");
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            gui,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality(),
                            flippedX,
                            flippedY
                    );
                } else {
                    logger.info("Using existing project");
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality()
                    );

                    // Handle flips for existing image if needed
                    var imageData = gui.getImageData();
                    if (imageData != null && (flippedX || flippedY)) {
                        String imagePath = MinorFunctions.extractFilePath(imageData.getServerPath());
                        if (imagePath != null) {
                            QPProjectFunctions.addImageToProject(
                                    new File(imagePath),
                                    gui.getProject(),
                                    flippedX,
                                    flippedY
                            );

                            // Refresh and reopen the image
                            gui.refreshProject();
                            var entries = gui.getProject().getImageList();
                            var newEntry = entries.stream()
                                    .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                    .findFirst()
                                    .orElse(null);
                            if (newEntry != null) {
                                gui.openImageEntry(newEntry);
                            }
                        }
                    }
                }

                // Give GUI time to update
                PauseTransition pause = new PauseTransition(Duration.millis(500));
                pause.setOnFinished(e -> {
                    future.complete(new ProjectInfo(projectDetails));
                });
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
     * Process existing alignment workflow
     */
    private static CompletableFuture<Void> processExistingAlignment(
            QuPathGUI gui, AlignmentContext alignContext, ProjectInfo projectInfo) {


        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");

        // Get the base transform
        AffineTransform baseTransform = alignContext.context.alignment.selectedTransform().getTransform();

        // Get or create annotations first (we need them regardless of which path we take)
        List<PathObject> annotations = collectOrCreateAnnotations(gui, alignContext.pixelSize);

        if (annotations.isEmpty()) {
            logger.warn("No annotations available");
            return CompletableFuture.completedFuture(null);
        }

        // Check if we have a slide-specific alignment already
        AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, alignContext.context.sample.sampleName());

        if (slideTransform != null) {
            logger.info("Found existing slide-specific alignment");

            // Ask user if they want to use it or refine it
            CompletableFuture<Boolean> useExisting = new CompletableFuture<>();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Existing Slide Alignment");
                alert.setHeaderText("Found existing alignment for this slide");
                alert.setContentText("Would you like to use the existing slide alignment or create a new one?");

                ButtonType useButton = new ButtonType("Use Existing");
                ButtonType refineButton = new ButtonType("Create New");
                alert.getButtonTypes().setAll(useButton, refineButton);

                Optional<ButtonType> result = alert.showAndWait();
                useExisting.complete(result.isPresent() && result.get() == useButton);
            });

            return useExisting.thenCompose(useExistingAlignment -> {
                if (useExistingAlignment) {
                    // Use the existing slide alignment
                    MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                    logger.info("Using existing slide-specific alignment");

                    // Create tiles for all annotations
                    String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
                    String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");
                    boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                    createTilesForAnnotations(annotations, alignContext.context.sample, tempTileDir,
                            modeWithIndex, alignContext.pixelSize, invertedX, invertedY);

                    // Skip refinement and continue directly to acquisition
                    return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                            annotations, alignContext.pixelSize, slideTransform);
                } else {
                    // User wants to create new alignment, continue with normal flow
                    return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                            annotations, baseTransform);
                }
            });
        } else {
            // No existing slide alignment, proceed with normal flow
            return proceedWithAlignmentRefinement(gui, alignContext, projectInfo,
                    annotations, baseTransform);
        }
    }

    /**
     * Helper method to handle the alignment refinement process
     */
    private static CompletableFuture<Void> proceedWithAlignmentRefinement(
            QuPathGUI gui,
            AlignmentContext alignContext,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            AffineTransform baseTransform) {

        MicroscopeController.getInstance().setCurrentTransform(baseTransform);

        logger.info("Green box detected at ({}, {}, {}, {})",
                alignContext.greenBoxResult.getDetectedBox().getBoundsX(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsY(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsWidth(),
                alignContext.greenBoxResult.getDetectedBox().getBoundsHeight());

        // Create tiles for all annotations
        String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
        String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        createTilesForAnnotations(annotations, alignContext.context.sample, tempTileDir,
                modeWithIndex, alignContext.pixelSize, invertedX, invertedY);

        if (alignContext.context.alignment.refinementRequested()) {
            // Do single-tile refinement (tiles already exist)
            return performSingleTileRefinement(gui, alignContext, projectInfo, annotations, baseTransform)
                    .thenCompose(refinedTransform -> {
                        if (refinedTransform == null) refinedTransform = baseTransform;
                        return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                                annotations, alignContext.pixelSize, refinedTransform);
                    });
        } else {
            // Continue without refinement
            return continueToAcquisition(gui, alignContext.context.sample, projectInfo,
                    annotations, alignContext.pixelSize, baseTransform);
        }
    }
    /**
     * Process manual alignment workflow
     */
    private static CompletableFuture<Void> processManualAlignment(
            QuPathGUI gui, ManualAlignmentContext context) {

        // Get or create annotations
        List<PathObject> annotations = collectOrCreateAnnotations(gui, context.pixelSize);

        if (annotations.isEmpty()) {
            logger.warn("No annotations available for manual workflow");
            return CompletableFuture.completedFuture(null);
        }

        // Create tiles
        String tempTileDir = (String) context.projectInfo.details.get("tempTileDirectory");
        String modeWithIndex = (String) context.projectInfo.details.get("imagingModeWithIndex");
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        createTilesForAnnotations(annotations, context.context.sample, tempTileDir,
                modeWithIndex, context.pixelSize, invertedX, invertedY);

        // Setup manual transform
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                        context.pixelSize, invertedX, invertedY)
                .thenCompose(transform -> {
                    if (transform == null) {
                        logger.info("Transform setup cancelled");
                        return CompletableFuture.completedFuture(null);
                    }

                    MicroscopeController.getInstance().setCurrentTransform(transform);

                    // Offer to save transform
                    return offerToSaveTransform(transform, context.context.sample.modality())
                            .thenCompose(saved -> continueToAcquisition(gui, context.context.sample,
                                    context.projectInfo, annotations, context.pixelSize, transform));
                });
    }

    /**
     * Collect existing annotations or create new ones via tissue detection
     */
    private static List<PathObject> collectOrCreateAnnotations(
            QuPathGUI gui, double macroPixelSize) {

        // Get existing annotations
        List<PathObject> annotations = QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing annotations", annotations.size());
            return annotations;
        }

        // Run tissue detection if configured
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
        if (tissueScript != null && !tissueScript.isBlank()) {
            try {
                double pixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                        tissueScript,
                        String.valueOf(pixelSize),
                        scriptPaths.get("jsonTissueClassfierPathString")
                );

                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Re-collect annotations
                annotations = QP.getAnnotationObjects().stream()
                        .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                        .filter(ann -> ann.getPathClass() != null &&
                                Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                        .collect(Collectors.toList());

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
            }
        }

        if (annotations.isEmpty()) {
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No valid annotations found. Please create annotations with one of these classes:\n" +
                                    String.join(", ", VALID_ANNOTATION_CLASSES),
                            "No Annotations")
            );
        }

        return annotations;
    }

    /**
     * Create tiles for the given annotations
     */
    private static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sample,
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY) {

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
        int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
        int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

        // Get the actual image pixel size to determine correct scaling
        QuPathGUI gui = QuPathGUI.getInstance();
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        logger.info("Image pixel size: {} µm, Macro pixel size: {} µm, Camera pixel size: {} µm",
                imagePixelSize, macroPixelSize, pixelSize);

        // Calculate frame dimensions in image pixels
        double frameWidthMicrons = pixelSize * cameraWidth;
        double frameHeightMicrons = pixelSize * cameraHeight;

        // Convert to image pixels (not macro pixels if they're different)
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        logger.info("Frame size in pixels: {} x {} (from {} x {} µm)",
                frameWidthPixels, frameHeightPixels, frameWidthMicrons, frameHeightMicrons);

        try {
            // Remove existing tiles
            String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass() != null &&
                            o.getPathClass().toString().contains(modalityBase))
                    .forEach(QP::removeObject);
            QP.fireHierarchyUpdate();

            // Validate tile size before creating tiles
            for (PathObject ann : annotations) {
                if (ann.getROI() != null) {
                    double annWidth = ann.getROI().getBoundsWidth();
                    double annHeight = ann.getROI().getBoundsHeight();
                    double tilesX = Math.ceil(annWidth / (frameWidthPixels * (1 - overlapPercent/100.0)));
                    double tilesY = Math.ceil(annHeight / (frameHeightPixels * (1 - overlapPercent/100.0)));
                    double totalTiles = tilesX * tilesY;

                    if (totalTiles > 10000) {
                        logger.error("Annotation '{}' would require {} tiles ({} x {}). This is too many!",
                                ann.getName(), totalTiles, tilesX, tilesY);
                        throw new RuntimeException(String.format(
                                "Annotation '%s' would require %.0f tiles. Maximum allowed is 10000.\n" +
                                        "This usually indicates incorrect pixel size settings.\n" +
                                        "Annotation size: %.0f x %.0f pixels\n" +
                                        "Tile size: %.0f x %.0f pixels",
                                ann.getName(), totalTiles, annWidth, annHeight,
                                frameWidthPixels, frameHeightPixels));
                    }
                }
            }

            // Create new tiles
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthPixels, frameHeightPixels)
                    .overlapPercent(overlapPercent)
                    .annotations(annotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created tiles for {} annotations", annotations.size());

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            throw new RuntimeException("Failed to create tiles: " + e.getMessage(), e);
        }
    }

    /**
     * Perform single-tile refinement
     */
    private static CompletableFuture<AffineTransform> performSingleTileRefinement(
            QuPathGUI gui,
            AlignmentContext alignContext,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            AffineTransform initialTransform) {

        CompletableFuture<AffineTransform> future = new CompletableFuture<>();

        // Get the actual image pixel size
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        logger.info("Refining transform - Image pixel size: {} µm, Macro pixel size: {} µm",
                imagePixelSize, alignContext.pixelSize);

        // Select tile for refinement (tiles should already exist)
        UIFunctions.promptTileSelectionDialogAsync(
                "Select a tile for alignment refinement.\n" +
                        "The microscope will move to approximately the correct position.\n" +
                        "You can then fine-tune the alignment if needed."
        ).thenAccept(selectedTile -> {
            if (selectedTile == null) {
                future.complete(initialTransform);
                return;
            }

            try {
                // Get tile coordinates in image pixels
                double[] tileCoords = {
                        selectedTile.getROI().getCentroidX(),
                        selectedTile.getROI().getCentroidY()
                };

                logger.info("Tile coordinates in image pixels: ({}, {})",
                        tileCoords[0], tileCoords[1]);

                // Convert to macro coordinates for the transform
                double scaleFactor = alignContext.pixelSize / imagePixelSize;
                double[] macroCoords = {
                        tileCoords[0] / scaleFactor,
                        tileCoords[1] / scaleFactor
                };

                logger.info("Tile coordinates in macro pixels: ({}, {})",
                        macroCoords[0], macroCoords[1]);

                // Transform to stage coordinates using initial transform
                double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(
                        macroCoords, initialTransform
                );

                logger.info("Moving to tile '{}' at stage position: {}",
                        selectedTile.getName(), Arrays.toString(stageCoords));

                MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                boolean refined = UIFunctions.stageToQuPathAlignmentGUI2();

                if (refined) {
                    double[] refinedStageCoords = MicroscopeController.getInstance().getStagePositionXY();

                    // Calculate the offset in stage coordinates
                    AffineTransform refinedTransform = TransformationFunctions.addTranslationToScaledAffine(
                            initialTransform, macroCoords, refinedStageCoords
                    );
                    logger.info("Refined transform: {}", refinedTransform);
//                    double stageOffsetX = refinedStageCoords[0] - stageCoords[0];
//                    double stageOffsetY = refinedStageCoords[1] - stageCoords[1];
//
//                    logger.info("Stage offset: ({}, {})", stageOffsetX, stageOffsetY);
//
//                    // Create refined transform by adding the offset
//                    AffineTransform refinedTransform = new AffineTransform(initialTransform);
//                    refinedTransform.translate(stageOffsetX, stageOffsetY);
//
//                    logger.info("Refined transform: {}", refinedTransform);
                    // Save the slide-specific alignment
                    Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");
                    AffineTransformManager.saveSlideAlignment(
                            project,
                            alignContext.context.sample.sampleName(),
                            alignContext.context.sample.modality(),
                            refinedTransform
                    );

                    future.complete(refinedTransform);
                } else {
                    future.complete(initialTransform);
                }

            } catch (Exception e) {
                logger.error("Error during refinement", e);
                future.complete(initialTransform);
            }
        });

        return future;
    }

    /**
     * Offer to save transform for future use
     */
    private static CompletableFuture<Boolean> offerToSaveTransform(
            AffineTransform transform, String modality) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            var result = Dialogs.showYesNoDialog(
                    "Save Transform",
                    "Would you like to save this transform for future use?"
            );

            if (result) {
                TextInputDialog nameDialog = new TextInputDialog();
                nameDialog.setTitle("Save Transform");
                nameDialog.setHeaderText("Enter a name for this transform");
                nameDialog.setContentText("Transform name:");

                Optional<String> name = nameDialog.showAndWait();
                if (name.isPresent() && !name.get().trim().isEmpty()) {
                    try {
                        // Get microscope name from config
                        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                        String microscopeName = mgr.getString("microscope", "name");

                        // Create transform manager
                        AffineTransformManager transformManager = new AffineTransformManager(
                                new File(configPath).getParent());

                        // Create and save preset
                        AffineTransformManager.TransformPreset preset =
                                new AffineTransformManager.TransformPreset(
                                        name.get(),
                                        microscopeName,
                                        "Standard", // mounting method
                                        transform,
                                        "Created via manual alignment",
                                        null // no green box params for manual alignment
                                );

                        transformManager.savePreset(preset);
                        logger.info("Transform saved as: {}", name.get());
                        future.complete(true);
                    } catch (Exception e) {
                        logger.error("Failed to save transform", e);
                        UIFunctions.notifyUserOfError(
                                "Failed to save transform: " + e.getMessage(),
                                "Save Error"
                        );
                        future.complete(false);
                    }
                } else {
                    future.complete(false);
                }
            } else {
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Continue to acquisition phase
     */
    private static CompletableFuture<Void> continueToAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            double macroPixelSize,
            AffineTransform transform) {

        // Validate annotations
        CompletableFuture<Boolean> validationFuture = new CompletableFuture<>();

        UIFunctions.checkValidAnnotationsGUI(
                Arrays.asList(VALID_ANNOTATION_CLASSES),
                userConfirmed -> validationFuture.complete(userConfirmed)
        );

        return validationFuture.thenCompose(confirmed -> {
            if (!confirmed) {
                logger.info("User cancelled annotation validation");
                return CompletableFuture.completedFuture(null);
            }

            // Get rotation angles
            RotationManager rotationManager = new RotationManager(sample.modality());

            return rotationManager.getRotationAngles(sample.modality())
                    .thenCompose(rotationAngles -> {
                        logger.info("Rotation angles: {}", rotationAngles);

                        // Process annotations
                        return processAnnotationsForAcquisition(
                                gui, sample, projectInfo, annotations,
                                macroPixelSize, transform, rotationAngles, rotationManager
                        );
                    });
        });
    }

    /**
     * Process annotations for acquisition and stitching
     */
    private static CompletableFuture<Void> processAnnotationsForAcquisition(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sample,
            ProjectInfo projectInfo,
            List<PathObject> annotations,
            double macroPixelSize,
            AffineTransform transform,
            List<Double> rotationAngles,
            RotationManager rotationManager) {

        String configFile = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();
        String modeWithIndex = (String) projectInfo.details.get("imagingModeWithIndex");

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFile);
        double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");

        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) projectInfo.details.get("currentQuPathProject");

        // Process each annotation
        List<CompletableFuture<Void>> acquisitionFutures = annotations.stream()
                .map(annotation -> processAnnotation(
                        annotation, sample, projectsFolder, modeWithIndex,
                        configFile, rotationAngles, rotationManager, pixelSize,
                        gui, project
                ))
                .collect(Collectors.toList());

        // Wait for all to complete
        return CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    logger.info("All acquisitions completed");

                    // Handle tile cleanup
                    String tempTileDir = (String) projectInfo.details.get("tempTileDirectory");
                    String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                    if ("Delete".equals(handling)) {
                        UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                    } else if ("Zip".equals(handling)) {
                        UtilityFunctions.zipTilesAndMove(tempTileDir);
                        UtilityFunctions.deleteTilesAndFolder(tempTileDir);
                    }
                    // Note: "None" option requires no action

                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Workflow Complete");
                        alert.setHeaderText("Acquisition workflow completed successfully");
                        alert.setContentText("All annotations have been acquired and stitched.");
                        alert.showAndWait();
                    });
                });
    }

    /**
     * Process a single annotation through acquisition and stitching
     */
    private static CompletableFuture<Void> processAnnotation(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            String configFile,
            List<Double> rotationAngles,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        String annotationName = annotation.getName();
        logger.info("Processing annotation: {}", annotationName);

        // Launch acquisition
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> cliArgs = new ArrayList<>(List.of(
                        res.getString("command.acquisitionWorkflow"),
                        configFile,
                        projectsFolder,
                        sample.sampleName(),
                        modeWithIndex,
                        annotationName
                ));

                // Add rotation angles if present
                if (rotationAngles != null && !rotationAngles.isEmpty()) {
                    String anglesStr = rotationAngles.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(" ", "(", ")"));
                    cliArgs.add(anglesStr);
                }

                logger.info("Starting acquisition with args: {}", cliArgs);

                int timeoutSeconds = 60;
                if (rotationAngles != null && !rotationAngles.isEmpty()) {
                    timeoutSeconds = 60 * rotationAngles.size();
                }

                CliExecutor.ExecResult result = CliExecutor.execComplexCommand(
                        timeoutSeconds,
                        res.getString("acquisition.cli.progressRegex"),
                        cliArgs.toArray(new String[0])
                );

                if (result.exitCode() != 0) {
                    throw new RuntimeException("Acquisition failed with exit code " + result.exitCode());
                }

                logger.info("Acquisition completed for {}", annotationName);
                return null;

            } catch (Exception e) {
                logger.error("Acquisition failed for {}", annotationName, e);
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "Acquisition failed for " + annotationName + ": " + e.getMessage(),
                                "Acquisition Error"
                        )
                );
                throw new RuntimeException(e);
            }
        }).thenCompose(v -> {
            // Schedule stitching
            if (rotationAngles != null && !rotationAngles.isEmpty()) {
                // Stitch each angle
                List<CompletableFuture<Void>> stitchFutures = rotationAngles.stream()
                        .map(angle -> stitchAnnotationAngle(
                                annotation, angle, sample, projectsFolder,
                                modeWithIndex, rotationManager, pixelSize, gui, project
                        ))
                        .collect(Collectors.toList());

                return CompletableFuture.allOf(stitchFutures.toArray(new CompletableFuture[0]));
            } else {
                // Single stitch
                return stitchAnnotation(
                        annotation, null, sample, projectsFolder,
                        modeWithIndex, null, pixelSize, gui, project
                );
            }
        });
    }

    /**
     * Stitch a single annotation/angle combination
     */
    private static CompletableFuture<Void> stitchAnnotation(
            PathObject annotation,
            Double angle,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        return CompletableFuture.runAsync(() -> {
            try {
                String annotationName = annotation.getName();
                String modalityName = modeWithIndex;

                if (angle != null && rotationManager != null) {
                    modalityName += rotationManager.getAngleSuffix(sample.modality(), angle);
                }

                logger.info("Stitching {} for modality {}", annotationName, modalityName);

                String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                        projectsFolder,
                        sample.sampleName(),
                        modalityName,
                        annotationName,
                        gui,
                        project,
                        String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                        pixelSize,
                        1  // downsample
                );

                logger.info("Stitching completed: {}", outPath);

            } catch (Exception e) {
                logger.error("Stitching failed for {}", annotation.getName(), e);
                Platform.runLater(() ->
                        UIFunctions.notifyUserOfError(
                                "Stitching failed for " + annotation.getName() + ": " + e.getMessage(),
                                "Stitching Error"
                        )
                );
            }
        }, STITCH_EXECUTOR);
    }

    /**
     * Convenience method for stitching with angle
     */
    private static CompletableFuture<Void> stitchAnnotationAngle(
            PathObject annotation,
            Double angle,
            SampleSetupController.SampleSetupResult sample,
            String projectsFolder,
            String modeWithIndex,
            RotationManager rotationManager,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        return stitchAnnotation(
                annotation, angle, sample, projectsFolder,
                modeWithIndex, rotationManager, pixelSize, gui, project
        );
    }

    // Helper classes for context passing

    private static class WorkflowContext {
        final SampleSetupController.SampleSetupResult sample;
        final AlignmentSelectionController.AlignmentChoice alignment;

        WorkflowContext(SampleSetupController.SampleSetupResult sample,
                        AlignmentSelectionController.AlignmentChoice alignment) {
            this.sample = sample;
            this.alignment = alignment;
        }
    }

    private static class AlignmentContext {
        final WorkflowContext context;
        final double pixelSize;
        final GreenBoxDetector.DetectionResult greenBoxResult;

        AlignmentContext(WorkflowContext context, double pixelSize,
                         GreenBoxDetector.DetectionResult greenBoxResult) {
            this.context = context;
            this.pixelSize = pixelSize;
            this.greenBoxResult = greenBoxResult;
        }
    }

    private static class ManualAlignmentContext {
        final WorkflowContext context;
        final double pixelSize;
        final ProjectInfo projectInfo;

        ManualAlignmentContext(WorkflowContext context, double pixelSize, ProjectInfo projectInfo) {
            this.context = context;
            this.pixelSize = pixelSize;
            this.projectInfo = projectInfo;
        }
    }

    private static class ProjectInfo {
        final Map<String, Object> details;

        ProjectInfo(Map<String, Object> details) {
            this.details = details;
        }
    }
}