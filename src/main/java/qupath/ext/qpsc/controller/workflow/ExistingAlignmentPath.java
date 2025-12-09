package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.ui.RefinementSelectionController.RefinementChoice;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.GreenBoxPreviewController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.scripting.QP;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles Path A: Using existing alignment with green box detection.
 *
 * <p>This path is used when:
 * <ul>
 *   <li>A macro image is available in the current image</li>
 *   <li>A saved alignment transform exists for the scanner</li>
 *   <li>Green box detection can locate the tissue position</li>
 * </ul>
 *
 * <p>The workflow:
 * <ol>
 *   <li>Loads scanner-specific pixel size configuration</li>
 *   <li>Crops and processes the macro image</li>
 *   <li>Detects the green box with user preview</li>
 *   <li>Creates full-resolution to stage transform</li>
 *   <li>Optionally performs single-tile refinement</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ExistingAlignmentPath {
    private static final Logger logger = LoggerFactory.getLogger(ExistingAlignmentPath.class);

    private final QuPathGUI gui;
    private final WorkflowState state;

    /**
     * Creates a new existing alignment path handler.
     *
     * @param gui QuPath GUI instance
     * @param state Current workflow state
     */
    public ExistingAlignmentPath(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the existing alignment path workflow.
     *
     * <p>This method orchestrates the complete Path A workflow including
     * green box detection, transform creation, and optional refinement.
     *
     * @return CompletableFuture containing the updated workflow state
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Path A: Using existing alignment with green box detection");

        // Validate macro image availability
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImageWithFallback(
                gui,
                state.sample != null ? state.sample.sampleName() : null
        );

        if (macroImage == null) {
            logger.error("No macro image available (neither from slide nor saved)");
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No macro image found in slide and no saved macro image available.\n" +
                                    "Cannot use existing alignment without a macro image.",
                            "Macro Image Required"
                    )
            );
            return CompletableFuture.completedFuture(null);
        }

        return loadPixelSize()
                .thenCompose(this::processMacroImage)
                .thenCompose(this::detectGreenBox)
                .thenCompose(this::setupProject)
                .thenCompose(this::validateAndFlipImage)
                .thenCompose(this::createTransform);
    }

    /**
     * Loads pixel size from scanner configuration.
     *
     * <p>The pixel size is critical for accurate scaling between macro
     * and full-resolution images.
     *
     * @return CompletableFuture containing the macro pixel size in micrometers
     */
    private CompletableFuture<Double> loadPixelSize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String scannerName = state.alignmentChoice.selectedTransform().getMountingMethod();
                logger.info("Loading pixel size for scanner: {}", scannerName);

                return loadScannerPixelSize(scannerName);
            } catch (Exception e) {
                logger.error("Failed to load pixel size", e);
                throw new RuntimeException("Cannot load macro pixel size: " + e.getMessage());
            }
        });
    }

    /**
     * Processes the macro image (crop and flip).
     *
     * <p>Applies scanner-specific cropping to remove slide holder areas
     * and flips based on user preferences.
     *
     * @param pixelSize Macro image pixel size
     * @return CompletableFuture containing processed macro image context
     */
    private CompletableFuture<MacroImageContext> processMacroImage(double pixelSize) {
        return CompletableFuture.supplyAsync(() -> {
            state.pixelSize = pixelSize;
            logger.info("Loaded macro pixel size: {} µm", pixelSize);

            // Get macro image (might be from slide or saved)
            BufferedImage macroImage = MacroImageUtility.retrieveMacroImageWithFallback(
                    gui,
                    state.sample.sampleName()
            );

            if (macroImage == null) {
                throw new RuntimeException("Cannot retrieve macro image");
            }

            // Check if this is a saved macro image (already processed)
            boolean isSavedImage = !MacroImageUtility.isMacroImageAvailable(gui);

            if (isSavedImage) {
                logger.info("Using saved macro image - already cropped and flipped");
                // Image is already processed, create dummy cropped result
                MacroImageUtility.CroppedMacroResult croppedResult =
                        new MacroImageUtility.CroppedMacroResult(
                                macroImage,
                                macroImage.getWidth(),
                                macroImage.getHeight(),
                                0, 0
                        );
                // The saved image is already flipped, so use it directly
                return new MacroImageContext(croppedResult, macroImage, macroImage);
            } else {
                // Normal processing for fresh macro image
                logger.info("Processing macro image from slide");
                MacroImageUtility.CroppedMacroResult croppedResult = cropMacroImage(macroImage);
                BufferedImage displayImage = applyFlips(croppedResult.getCroppedImage());
                return new MacroImageContext(croppedResult, displayImage, displayImage);
            }
        });
    }

    /**
     * Shows green box detection dialog with preview.
     *
     * <p>Allows the user to adjust detection parameters and preview results
     * before confirming the green box location.
     *
     * @param macroContext Processed macro image context
     * @return CompletableFuture containing green box detection results
     */
    private CompletableFuture<GreenBoxContext> detectGreenBox(MacroImageContext macroContext) {
        // Get initial parameters from saved transform
        GreenBoxDetector.DetectionParams params = new GreenBoxDetector.DetectionParams();
        if (state.alignmentChoice.selectedTransform() != null &&
                state.alignmentChoice.selectedTransform().getGreenBoxParams() != null) {
            params = state.alignmentChoice.selectedTransform().getGreenBoxParams();
        }

        return GreenBoxPreviewController.showPreviewDialog(macroContext.displayImage, params)
                .thenApply(result -> {
                    if (result == null) {
                        throw new RuntimeException("Green box detection cancelled");
                    }

                    logger.info("Green box detected with confidence {}", result.getConfidence());
                    return new GreenBoxContext(macroContext, result);
                });
    }

    /**
     * Sets up the QuPath project.
     *
     * @param context Green box detection context
     * @return CompletableFuture with context passed through
     */
    private CompletableFuture<GreenBoxContext> setupProject(GreenBoxContext context) {
        return ProjectHelper.setupProject(gui, state.sample)
                .thenApply(projectInfo -> {
                    if (projectInfo == null) {
                        throw new RuntimeException("Project setup failed");
                    }

                    state.projectInfo = projectInfo;
                    return context;
                });
    }

    /**
     * Validates and flips the full-resolution image if needed.
     *
     * <p>This step ensures the image has the correct orientation BEFORE
     * any operations that depend on it (annotations, white space detection,
     * tile creation, alignment refinement).
     *
     * @param context Green box detection context
     * @return CompletableFuture with context passed through
     */
    private CompletableFuture<GreenBoxContext> validateAndFlipImage(GreenBoxContext context) {
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        return ImageFlipHelper.validateAndFlipIfNeeded(gui, project, state.sample)
                .thenApply(validated -> {
                    if (!validated) {
                        throw new RuntimeException("Image validation and flip preparation failed");
                    }
                    logger.info("Image flip validation complete - ready for operations");
                    return context;
                });
    }

    /**
     * Creates the transform and prepares for acquisition.
     *
     * <p>This method:
     * <ol>
     *   <li>Gets or creates annotations</li>
     *   <li>Creates full-resolution to stage transform</li>
     *   <li>Validates transform boundaries</li>
     *   <li>Optionally performs single-tile refinement</li>
     *   <li>Saves the final transform</li>
     * </ol>
     *
     * @param context Green box detection context
     * @return CompletableFuture containing the updated workflow state
     */
    private CompletableFuture<WorkflowState> createTransform(GreenBoxContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // Get annotations
            state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize, state.selectedAnnotationClasses);
            if (state.annotations.isEmpty()) {
                throw new RuntimeException("No valid annotations found");
            }

            // Create transform
            AffineTransform fullResToStage = createFullResToStageTransform(context);

            // Validate transform
            if (!validateTransform(fullResToStage)) {
                throw new RuntimeException("Transform validation failed - produces out-of-bounds coordinates");
            }

            state.transform = fullResToStage;
            MicroscopeController.getInstance().setCurrentTransform(fullResToStage);

            // Save if not requesting refinement
            if (state.refinementChoice == RefinementChoice.NONE) {
                saveSlideAlignment(context);
            }

            return state;
        }).thenCompose(currentState -> {
            // Handle refinement if requested
            if (state.refinementChoice != RefinementChoice.NONE) {
                return performRefinement(context);
            } else {
                return CompletableFuture.completedFuture(currentState);
            }
        });
    }

    /**
     * Creates the full-resolution to stage transform.
     *
     * <p>Combines the macro-to-stage transform with the green box location
     * to create an accurate full-resolution to stage mapping.
     *
     * @param context Green box detection context
     * @return AffineTransform mapping full-res pixels to stage micrometers
     */
    private AffineTransform createFullResToStageTransform(GreenBoxContext context) {
        // Get the saved transform
        AffineTransform savedTransform = state.alignmentChoice.selectedTransform().getTransform();

        // Detect if this is a slide-specific alignment (full-res → stage) or a preset (macro → stage)
        // Slide-specific alignments have scale close to the full-res pixel size (~0.25 µm/px)
        // Macro → stage transforms have scale close to the macro pixel size (~81 µm/px)
        double transformScale = savedTransform.getScaleX();
        double fullResPixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        // If transform scale is close to full-res pixel size, it's already a full-res → stage transform
        boolean isSlideSpecific = Math.abs(transformScale - fullResPixelSize) < 1.0;

        if (isSlideSpecific) {
            logger.info("Using slide-specific alignment (already full-res → stage)");
            logger.info("  Transform scale: {} µm/px (matches full-res pixel size: {})",
                    transformScale, fullResPixelSize);
            return new AffineTransform(savedTransform);
        }

        // Otherwise, it's a macro → stage transform, so build full-res → stage
        logger.info("Using saved preset (macro → stage), building full-res → stage transform");
        logger.info("  Macro transform scale: {} µm/px, Full-res pixel size: {}",
                transformScale, fullResPixelSize);

        AffineTransform macroToStage = savedTransform;

        // Get image dimensions and detect data bounds
        int reportedWidth = gui.getImageData().getServer().getWidth();
        int reportedHeight = gui.getImageData().getServer().getHeight();

        Rectangle dataBounds = detectDataBounds(context, reportedWidth, reportedHeight);

        // Calculate pixel-based scaling
        double pixelSizeRatio = fullResPixelSize / state.pixelSize;

        // Create transform
        ROI greenBox = context.greenBoxResult.getDetectedBox();
        AffineTransform fullResToMacro = new AffineTransform();
        fullResToMacro.scale(pixelSizeRatio, pixelSizeRatio);
        fullResToMacro.translate(
                (greenBox.getBoundsX() - dataBounds.x * pixelSizeRatio) / pixelSizeRatio,
                (greenBox.getBoundsY() - dataBounds.y * pixelSizeRatio) / pixelSizeRatio
        );

        // Combine transforms
        AffineTransform fullResToStage = new AffineTransform(macroToStage);
        fullResToStage.concatenate(fullResToMacro);

        // Test if the saved transform expects unflipped macro coordinates

        logger.info("Green box in flipped macro: ({}, {})", greenBox.getBoundsX(), greenBox.getBoundsY());
//
//// Calculate where this would be in unflipped coordinates
//        double unflippedX = context.macroContext.croppedResult.getCroppedImage().getWidth() - greenBox.getBoundsX() - greenBox.getBoundsWidth();
//        double unflippedY = context.macroContext.croppedResult.getCroppedImage().getHeight() - greenBox.getBoundsY() - greenBox.getBoundsHeight();
//        logger.info("Green box in unflipped macro would be: ({}, {})", unflippedX, unflippedY);

// Test both versions
        Point2D flippedGreenBoxCenter = new Point2D.Double(
                greenBox.getCentroidX(), greenBox.getCentroidY());


        Point2D stageFlipped = new Point2D.Double();
        macroToStage.transform(flippedGreenBoxCenter, stageFlipped);

        logger.info("Macro center → Stage (using flipped): ({}, {})", stageFlipped.getX(), stageFlipped.getY());

        logger.info("Created full-res→stage transform");
        return fullResToStage;
    }

    /**
     * Performs single-tile refinement if requested.
     *
     * <p>Allows the user to manually adjust alignment using a single tile
     * for improved accuracy. The selected tile is stored in the workflow state
     * to ensure its parent annotation is acquired first (so the sample is already in focus).
     *
     * @return CompletableFuture containing the refined workflow state
     */
    private CompletableFuture<WorkflowState> performRefinement(GreenBoxContext context) {
        // For EXISTING images (already flipped or not), annotations are already in the
        // correct coordinate space. We should NOT apply any axis inversion when creating
        // tiles for display in QuPath - tiles should match the annotation positions directly.
        //
        // The invertX/invertY flags affect WHERE tiles are placed within annotation bounds
        // (e.g., starting from top-left vs bottom-left). For existing images, we want
        // tiles to appear exactly at the annotation coordinates, so no inversion needed.
        boolean invertedX = false;
        boolean invertedY = false;

        var currentEntry = QP.getProjectEntry();
        boolean isFlipped = currentEntry != null && ImageMetadataManager.isFlipped(currentEntry);

        logger.info("Creating tiles for refinement: isFlipped={}, invertedX={}, invertedY={} (no inversion for existing images)",
                isFlipped, invertedX, invertedY);

        // Create tiles for refinement - no axis inversion for existing images
        TileHelper.createTilesForAnnotations(
                state.annotations,
                state.sample,
                state.projectInfo.getTempTileDirectory(),
                state.projectInfo.getImagingModeWithIndex(),
                state.pixelSize,
                invertedX,
                invertedY
        );

        return SingleTileRefinement.performRefinement(
                gui, state.annotations, state.transform
        ).thenApply(result -> {
            if (result.transform != null) {
                state.transform = result.transform;
                MicroscopeController.getInstance().setCurrentTransform(result.transform);
            }
            // Store the selected refinement tile for acquisition prioritization
            state.refinementTile = result.selectedTile;
            if (result.selectedTile != null) {
                logger.info("Stored refinement tile '{}' for acquisition prioritization",
                        result.selectedTile.getName());
            }
            saveSlideAlignment(context);
            return state;
        });
    }

    /**
     * Saves the slide-specific alignment.
     *
     * <p>Saves the transform to the project for future reuse with this
     * specific slide. Uses the IMAGE name (not sample/project name) for storage.
     */
    private void saveSlideAlignment(GreenBoxContext context) {
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        // Get the actual image file name (not metadata name which may be project name)
        String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());

        if (imageName == null) {
            logger.error("Cannot save slide alignment - no image name available");
            return;
        }

        // Get the processed macro image from the state
        BufferedImage processedMacroImage = context.getProcessedMacroImage();

        AffineTransformManager.saveSlideAlignment(
                project,
                imageName,  // Use image name instead of sample name
                state.sample.modality(),
                state.transform,
                processedMacroImage

        );
        logger.info("Saved slide-specific alignment for image: {}", imageName);
    }

    // Helper methods

    /**
     * Loads scanner-specific pixel size from configuration.
     */
    private double loadScannerPixelSize(String scannerName) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        File configDir = new File(configPath).getParentFile();
        File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

        if (scannerConfigFile.exists()) {
            Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
            Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");

            if (pixelSize != null && pixelSize > 0) {
                return pixelSize;
            }
        }

        // No fallback - throw exception
        throw new IllegalStateException(
                "Scanner '" + scannerName + "' has no valid macro pixel size configured.\n" +
                        "Please add 'macro.pixel_size_um' to the scanner configuration file:\n" +
                        scannerConfigFile.getAbsolutePath()
        );
    }

    /**
     * Crops macro image based on scanner configuration.
     */
    private MacroImageUtility.CroppedMacroResult cropMacroImage(BufferedImage originalMacro) {
        String scannerName = state.alignmentChoice.selectedTransform().getMountingMethod();
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        File configDir = new File(configPath).getParentFile();
        File scannerConfigFile = new File(configDir, "config_" + scannerName + ".yml");

        if (scannerConfigFile.exists()) {
            Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());
            Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requires_cropping");

            if (requiresCropping != null && requiresCropping) {
                Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_min");
                Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "x_max");
                Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_min");
                Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slide_bounds", "y_max");

                if (xMin != null && xMax != null && yMin != null && yMax != null) {
                    return MacroImageUtility.cropToSlideArea(originalMacro, xMin, xMax, yMin, yMax);
                }
            }
        }

        // No cropping needed
        return new MacroImageUtility.CroppedMacroResult(
                originalMacro, originalMacro.getWidth(), originalMacro.getHeight(), 0, 0);
    }

    /**
     * Applies flips to macro image based on preferences.
     */
    private BufferedImage applyFlips(BufferedImage image) {
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        BufferedImage processedImage;
        if (flipX || flipY) {
            processedImage = MacroImageUtility.flipMacroImage(image, flipX, flipY);
        } else {
            processedImage = image;
        }

        return processedImage;
    }

    /**
     * Detects actual data bounds, excluding white padding.
     */
    private Rectangle detectDataBounds(GreenBoxContext context, int reportedWidth, int reportedHeight) {
        String scannerName = state.alignmentChoice.selectedTransform().getMountingMethod();

        // Special handling for Ocus40 scanner
        if ("Ocus40".equalsIgnoreCase(scannerName)) {
            try {
                String tissueScriptPath = QPPreferenceDialog.getTissueDetectionScriptProperty();
                if (tissueScriptPath != null && !tissueScriptPath.isBlank()) {
                    File scriptFile = new File(tissueScriptPath);
                    String scriptDirectory = scriptFile.getParent();

                    Rectangle bounds = UIFunctions.executeWithProgress(
                            "Processing Image",
                            "Detecting image boundaries...\nThis may take a moment for large images.",
                            () -> ImageProcessing.detectOcus40DataBounds(gui, scriptDirectory)
                    );
                    if (bounds != null) {
                        return bounds;
                    }
                }
            } catch (Exception e) {
                logger.warn("Ocus40 detection failed!!!! Using green box without changes to bounds, which will likely be wrong", e);
            }
        }

        // Fallback calculation based on green box size
        ROI greenBox = context.greenBoxResult.getDetectedBox();
        double pixelSizeRatio = state.pixelSize / gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        int calculatedWidth = (int) Math.round(greenBox.getBoundsWidth() * pixelSizeRatio);
        int calculatedHeight = (int) Math.round(greenBox.getBoundsHeight() * pixelSizeRatio);

        int widthDiff = reportedWidth - calculatedWidth;
        int heightDiff = reportedHeight - calculatedHeight;

        return new Rectangle(
                widthDiff / 2,
                heightDiff / 2,
                calculatedWidth,
                calculatedHeight
        );
    }

    /**
     * Validates transform against stage boundaries with detailed logging.
     */
    private boolean validateTransform(AffineTransform transform) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double stageXMin = mgr.getStageLimit("x", "low");
        double stageXMax = mgr.getStageLimit("x", "high");
        double stageYMin = mgr.getStageLimit("y", "low");
        double stageYMax = mgr.getStageLimit("y", "high");

        logger.info("Stage boundaries from config:");
        logger.info("  X: {} to {} µm", stageXMin, stageXMax);
        logger.info("  Y: {} to {} µm", stageYMin, stageYMax);

        int width = gui.getImageData().getServer().getWidth();
        int height = gui.getImageData().getServer().getHeight();

        logger.info("Image dimensions: {} x {} pixels", width, height);

        // Log transform details
        TransformationFunctions.logTransformDetails("Full-res to stage", transform);

        // Test key points with detailed logging
        double[][] testPoints = {
                {0, 0},                    // Top-left
                {width/2, 0},              // Top-center
                {width, 0},                // Top-right
                {0, height/2},             // Middle-left
                {width/2, height/2},       // Center
                {width, height/2},         // Middle-right
                {0, height},               // Bottom-left
                {width/2, height},         // Bottom-center
                {width, height}            // Bottom-right
        };

        String[] labels = {
                "Top-left", "Top-center", "Top-right",
                "Middle-left", "Center", "Middle-right",
                "Bottom-left", "Bottom-center", "Bottom-right"
        };

        boolean allValid = true;
        logger.info("Testing transform at key image points:");

        for (int i = 0; i < testPoints.length; i++) {
            double[] qpCoords = testPoints[i];
            double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                    qpCoords, transform);

            boolean xValid = stageCoords[0] >= stageXMin && stageCoords[0] <= stageXMax;
            boolean yValid = stageCoords[1] >= stageYMin && stageCoords[1] <= stageYMax;

            String status = (xValid && yValid) ? "VALID" : "INVALID";
            logger.info("  {} ({}, {}) → ({}, {}) [{}]",
                    labels[i], qpCoords[0], qpCoords[1],
                    stageCoords[0], stageCoords[1], status);

            if (!xValid || !yValid) {
                allValid = false;
                if (!xValid) {
                    logger.warn("    X coordinate {} is outside range [{}, {}]",
                            stageCoords[0], stageXMin, stageXMax);
                }
                if (!yValid) {
                    logger.warn("    Y coordinate {} is outside range [{}, {}]",
                            stageCoords[1], stageYMin, stageYMax);
                }
            }
        }

        return allValid;
    }
    // Inner context classes

    /**
     * Context for macro image processing results.
     */
    private static class MacroImageContext {
        final MacroImageUtility.CroppedMacroResult croppedResult;
        final BufferedImage displayImage;
        final BufferedImage processedMacroImage;  // Add this

        MacroImageContext(MacroImageUtility.CroppedMacroResult croppedResult,
                          BufferedImage displayImage,
                          BufferedImage processedMacroImage) {  // Add parameter
            this.croppedResult = croppedResult;
            this.displayImage = displayImage;
            this.processedMacroImage = processedMacroImage;  // Store it
        }
    }

    /**
     * Context for green box detection results.
     */
    private static class GreenBoxContext {
        final MacroImageContext macroContext;
        final GreenBoxDetector.DetectionResult greenBoxResult;

        GreenBoxContext(MacroImageContext macroContext, GreenBoxDetector.DetectionResult greenBoxResult) {
            this.macroContext = macroContext;
            this.greenBoxResult = greenBoxResult;
        }
        BufferedImage getProcessedMacroImage() {
            return macroContext.processedMacroImage;
        }
    }
}