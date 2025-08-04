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
 */
public class ExistingAlignmentPath {
    private static final Logger logger = LoggerFactory.getLogger(ExistingAlignmentPath.class);

    private final QuPathGUI gui;
    private final WorkflowState state;

    public ExistingAlignmentPath(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the existing alignment path workflow.
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Path A: Using existing alignment with green box detection");

        // Validate macro image
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

            // Apply flips
            BufferedImage displayImage = applyFlips(croppedResult.getCroppedImage());

            return new MacroImageContext(croppedResult, displayImage);
        });
    }

    /**
     * Shows green box detection dialog.
     */
    private CompletableFuture<GreenBoxContext> detectGreenBox(MacroImageContext macroContext) {
        // Get initial parameters
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
     * Sets up the project.
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
     */
    private AffineTransform createFullResToStageTransform(GreenBoxContext context) {
        // Get base transform
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

    private BufferedImage applyFlips(BufferedImage image) {
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        if (flipX || flipY) {
            return MacroImageUtility.flipMacroImage(image, flipX, flipY);
        }
        return image;
    }

    private Rectangle detectDataBounds(GreenBoxContext context, int reportedWidth, int reportedHeight) {
        String scannerName = state.alignmentChoice.selectedTransform().getMountingMethod();

        if ("Ocus40".equalsIgnoreCase(scannerName)) {
            try {
                String tissueScriptPath = QPPreferenceDialog.getTissueDetectionScriptProperty();
                if (tissueScriptPath != null && !tissueScriptPath.isBlank()) {
                    File scriptFile = new File(tissueScriptPath);
                    String scriptDirectory = scriptFile.getParent();

                    Rectangle bounds = ImageProcessing.detectOcus40DataBounds(gui, scriptDirectory);
                    if (bounds != null) {
                        return bounds;
                    }
                }
            } catch (Exception e) {
                logger.warn("Ocus40 detection failed, using calculation", e);
            }
        }

        // Fallback calculation
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

    private static class MacroImageContext {
        final MacroImageUtility.CroppedMacroResult croppedResult;
        final BufferedImage displayImage;

        MacroImageContext(MacroImageUtility.CroppedMacroResult croppedResult, BufferedImage displayImage) {
            this.croppedResult = croppedResult;
            this.displayImage = displayImage;
        }
    }

    private static class GreenBoxContext {
        final MacroImageContext macroContext;
        final GreenBoxDetector.DetectionResult greenBoxResult;

        GreenBoxContext(MacroImageContext macroContext, GreenBoxDetector.DetectionResult greenBoxResult) {
            this.macroContext = macroContext;
            this.greenBoxResult = greenBoxResult;
        }
    }
}