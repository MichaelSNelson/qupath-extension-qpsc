package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.GreenBoxPreviewController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.geom.AffineTransform;
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
        if (!MacroImageUtility.isMacroImageAvailable(gui)) {
            logger.error("No macro image available for green box detection");
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No macro image found. Cannot use existing alignment.",
                            "Macro Image Required"
                    )
            );
            return CompletableFuture.completedFuture(null);
        }

        return loadPixelSize()
                .thenCompose(this::processMacroImage)
                .thenCompose(this::detectGreenBox)
                .thenCompose(this::setupProject)
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

            BufferedImage originalMacro = MacroImageUtility.retrieveMacroImage(gui);
            if (originalMacro == null) {
                throw new RuntimeException("Cannot retrieve macro image");
            }

            // Crop based on scanner configuration
            MacroImageUtility.CroppedMacroResult croppedResult = cropMacroImage(originalMacro);

            // Apply flips for display
            BufferedImage displayImage = applyFlips(croppedResult.getCroppedImage());

            return new MacroImageContext(croppedResult, displayImage);
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
            state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize);
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
            if (!state.alignmentChoice.refinementRequested()) {
                saveSlideAlignment();
            }

            return state;
        }).thenCompose(currentState -> {
            // Handle refinement if requested
            if (state.alignmentChoice.refinementRequested()) {
                return performRefinement();
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
        // Get base macro-to-stage transform
        AffineTransform macroToStage = state.alignmentChoice.selectedTransform().getTransform();

        // Get image dimensions and detect data bounds
        int reportedWidth = gui.getImageData().getServer().getWidth();
        int reportedHeight = gui.getImageData().getServer().getHeight();

        Rectangle dataBounds = detectDataBounds(context, reportedWidth, reportedHeight);

        // Calculate pixel-based scaling
        double fullResPixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();
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

        logger.info("Created full-res→stage transform");
        return fullResToStage;
    }

    /**
     * Performs single-tile refinement if requested.
     *
     * <p>Allows the user to manually adjust alignment using a single tile
     * for improved accuracy.
     *
     * @return CompletableFuture containing the refined workflow state
     */
    private CompletableFuture<WorkflowState> performRefinement() {
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
        ).thenApply(refinedTransform -> {
            if (refinedTransform != null) {
                state.transform = refinedTransform;
                MicroscopeController.getInstance().setCurrentTransform(refinedTransform);
            }
            saveSlideAlignment();
            return state;
        });
    }

    /**
     * Saves the slide-specific alignment.
     *
     * <p>Saves the transform to the project for future reuse with this
     * specific slide.
     */
    private void saveSlideAlignment() {
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();
        AffineTransformManager.saveSlideAlignment(
                project,
                state.sample.sampleName(),
                state.sample.modality(),
                state.transform
        );
        logger.info("Saved slide-specific alignment");
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
            Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixelSize_um");

            if (pixelSize != null && pixelSize > 0) {
                return pixelSize;
            }
        }

        // No fallback - throw exception
        throw new IllegalStateException(
                "Scanner '" + scannerName + "' has no valid macro pixel size configured.\n" +
                        "Please add 'macro.pixelSize_um' to the scanner configuration file:\n" +
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
            Boolean requiresCropping = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requiresCropping");

            if (requiresCropping != null && requiresCropping) {
                Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMin");
                Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMax");
                Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMin");
                Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMax");

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

        if (flipX || flipY) {
            return MacroImageUtility.flipMacroImage(image, flipX, flipY);
        }
        return image;
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
     * Validates transform against stage boundaries.
     */
    private boolean validateTransform(AffineTransform transform) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty());

        double stageXMin = mgr.getDouble("stage", "xlimit", "low");
        double stageXMax = mgr.getDouble("stage", "xlimit", "high");
        double stageYMin = mgr.getDouble("stage", "ylimit", "low");
        double stageYMax = mgr.getDouble("stage", "ylimit", "high");

        int width = gui.getImageData().getServer().getWidth();
        int height = gui.getImageData().getServer().getHeight();

        return TransformationFunctions.validateTransform(
                transform, width, height,
                stageXMin, stageXMax, stageYMin, stageYMax
        );
    }

    // Inner context classes

    /**
     * Context for macro image processing results.
     */
    private static class MacroImageContext {
        final MacroImageUtility.CroppedMacroResult croppedResult;
        final BufferedImage displayImage;

        MacroImageContext(MacroImageUtility.CroppedMacroResult croppedResult, BufferedImage displayImage) {
            this.croppedResult = croppedResult;
            this.displayImage = displayImage;
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
    }
}