package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.ui.MacroImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow for creating and saving microscope alignment transforms.
 * This workflow does NOT perform acquisition - it only creates the transform
 * that can be used later in the Existing Image workflow.
 *
 * The saved transform is a GENERAL macro-to-stage transform that works
 * regardless of where the green box appears in future macro images.
 *
 * <h3>Coordinate Systems:</h3>
 * <ul>
 *   <li><b>Macro coordinates:</b> Low-resolution macro image pixels (may be flipped)</li>
 *   <li><b>Full-res coordinates:</b> Full resolution image pixels in QuPath (already flipped)</li>
 *   <li><b>Stage coordinates:</b> Physical microscope position in micrometers</li>
 * </ul>
 *
 * <h3>Transform Chain:</h3>
 * <pre>
 * Macro pixels → Full-res pixels → Stage micrometers
 * </pre>
 *
 * @since 0.3.0
 * @author Mike Nelson
 */
public class MicroscopeAlignmentWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeAlignmentWorkflow.class);

    // Default macro pixel size if not available from metadata
    private static final double DEFAULT_MACRO_PIXEL_SIZE = 80.0; // microns

    // Valid annotation classes for acquisition
    private static final List<String> VALID_ANNOTATION_CLASSES =
            Arrays.asList("Tissue", "Scanned Area", "Bounding Box");

    /**
     * Entry point for the microscope alignment workflow.
     * Creates or refines an affine transform between macro and main images.
     *
     * @implNote The workflow creates a general macro→stage transform that can be reused
     *           with any green box position in future acquisitions
     */
    public static void run() {
        logger.info("Starting Microscope Alignment Workflow...");

        QuPathGUI gui = QuPathGUI.getInstance();

        // Check prerequisites
        if (gui.getImageData() == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No image is currently open. Please open an image first.",
                    "No Image"
            ));
            return;
        }

        // Check for macro image
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (macroImage == null) {
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "No macro image found in the current image. " +
                            "A macro image is required for alignment workflow.",
                    "No Macro Image"));
            return;
        }

        // Initialize transform manager
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        AffineTransformManager transformManager = new AffineTransformManager(
                new File(configPath).getParent());

        // Get current microscope from config
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
        String microscopeName = mgr.getString("microscope", "name");

        // First, show sample setup dialog to get modality
        SampleSetupController.showDialog()
                .thenCompose(sampleSetup -> {
                    if (sampleSetup == null) {
                        logger.info("User cancelled at sample setup");
                        return CompletableFuture.completedFuture(null);
                    }

                    // Now show alignment dialog with proper parameters
                    return MacroImageController.showAlignmentDialog(gui, transformManager, microscopeName)
                            .thenApply(alignConfig -> {
                                if (alignConfig == null) {
                                    return null;
                                }

                                // Run detection NOW before project creation
                                MacroImageResults macroImageResults = performDetection(gui, alignConfig);

                                // Package everything together
                                return new CombinedConfig(sampleSetup, alignConfig, macroImageResults);
                            });
                })
                .thenAccept(combinedConfig -> {
                    if (combinedConfig == null) {
                        logger.info("User cancelled alignment workflow");
                        return;
                    }

                    // Process the alignment with project setup
                    processAlignmentWithProject(gui, combinedConfig, transformManager);
                })
                .exceptionally(ex -> {
                    logger.error("Alignment workflow failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(),
                            "Alignment Error"));
                    return null;
                });
    }

    /**
     * Container for sample setup, alignment config, and detection results.
     */
    private record CombinedConfig(
            SampleSetupController.SampleSetupResult sampleSetup,
            MacroImageController.AlignmentConfig alignmentConfig,
            MacroImageResults macroImageResults
    ) {}

    /**
     * Container for detection results that need to survive project creation.
     */
    private record MacroImageResults(
            GreenBoxDetector.DetectionResult greenBoxResult,
            MacroImageAnalyzer.MacroAnalysisResult tissueResult,
            AffineTransform greenBoxTransform,
            int macroWidth,
            int macroHeight
    ) {}

    /**
     * Performs all detection BEFORE project creation while macro image is still available.
     *
     * @param gui QuPath GUI instance
     * @param config Alignment configuration
     * @return Detection results including green box location and tissue regions
     */
    private static MacroImageResults performDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config) {

        logger.info("Performing detection while macro image is available");
        // Capture macro dimensions while we still have access
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        int macroWidth = 0;
        int macroHeight = 0;

        if (macroImage != null) {
            macroWidth = macroImage.getWidth();
            macroHeight = macroImage.getHeight();
            logger.info("Captured macro dimensions: {}x{}", macroWidth, macroHeight);
        }

        GreenBoxDetector.DetectionResult greenBoxResult = null;
        AffineTransform greenBoxTransform = null;

        // Try green box detection if enabled
        if (config.useGreenBoxDetection()) {
            try {
                if (macroImage != null) {
                    greenBoxResult = GreenBoxDetector.detectGreenBox(macroImage, config.greenBoxParams());

                    if (greenBoxResult != null && greenBoxResult.getConfidence() > 0.7) {
                        logger.info("Green box detected with confidence {}", greenBoxResult.getConfidence());

                        // Calculate transform
                        int mainWidth = gui.getImageData().getServer().getWidth();
                        int mainHeight = gui.getImageData().getServer().getHeight();
                        double mainPixelSize = gui.getImageData().getServer()
                                .getPixelCalibration().getAveragedPixelSizeMicrons();

                        greenBoxTransform = GreenBoxDetector.calculateInitialTransform(
                                greenBoxResult.getDetectedBox(),
                                mainWidth,
                                mainHeight,
                                DEFAULT_MACRO_PIXEL_SIZE,
                                mainPixelSize
                        );
                    }
                }
            } catch (Exception e) {
                logger.error("Error during green box detection", e);
            }
        }

        // Try tissue detection
        MacroImageAnalyzer.MacroAnalysisResult tissueResult = null;
        try {
            tissueResult = MacroImageAnalyzer.analyzeMacroImage(
                    gui.getImageData(),
                    config.thresholdMethod(),
                    config.thresholdParams()
            );

            if (tissueResult != null) {
                logger.info("Tissue analysis found {} regions", tissueResult.getTissueRegions().size());
            }
        } catch (Exception e) {
            logger.error("Error during tissue analysis", e);
        }

        return new MacroImageResults(greenBoxResult, tissueResult, greenBoxTransform, macroWidth, macroHeight);
    }

    /**
     * Processes the alignment after detection is complete.
     *
     * @param gui QuPath GUI instance
     * @param combinedConfig Combined configuration and detection results
     * @param transformManager Manager for saving/loading transforms
     */
    private static void processAlignmentWithProject(
            QuPathGUI gui,
            CombinedConfig combinedConfig,
            AffineTransformManager transformManager) {

        Platform.runLater(() -> {
            try {
                var sampleSetup = combinedConfig.sampleSetup();
                var alignConfig = combinedConfig.alignmentConfig();
                var detectionResults = combinedConfig.macroImageResults();

                // Import to project
                boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
                boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

                Map<String, Object> projectDetails;

                if (gui.getProject() == null) {
                    // Create new project
                    String imagePath = MinorFunctions.extractFilePath(
                            gui.getImageData().getServerPath()
                    );

                    if (imagePath == null) {
                        UIFunctions.notifyUserOfError(
                                "Cannot extract image path",
                                "Import Error"
                        );
                        return;
                    }

                    Project<BufferedImage> project = QPProjectFunctions.createProject(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName()
                    );

                    if (project == null) {
                        UIFunctions.notifyUserOfError(
                                "Failed to create project",
                                "Project Error"
                        );
                        return;
                    }

                    gui.setProject(project);

                    QPProjectFunctions.addImageToProject(
                            new File(imagePath),
                            project,
                            flipX,
                            flipY
                    );

                    gui.refreshProject();

                    // Find and open the entry
                    var entries = project.getImageList();
                    var newEntry = entries.stream()
                            .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                            .findFirst()
                            .orElse(null);

                    if (newEntry != null) {
                        gui.openImageEntry(newEntry);
                        logger.info("Reopened image with flips applied");
                    }

                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName(),
                            sampleSetup.modality()
                    );
                } else {
                    // Use existing project
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sampleSetup.projectsFolder().getAbsolutePath(),
                            sampleSetup.sampleName(),
                            sampleSetup.modality()
                    );
                }

                // Create annotations from detection
                runTissueDetectionScript(gui);

                // Get inverted axes settings
                boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

                double macroPixelSize = DEFAULT_MACRO_PIXEL_SIZE;
                double mainPixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                // Create tiles for manual alignment
                String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
                String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

                // Create tiles for alignment (in full resolution coordinates)
                createAlignmentTiles(gui, sampleSetup, tempTileDirectory,
                        modeWithIndex, invertedX, invertedY);

                // Setup manual transform - this returns a full-res→stage transform
                AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                mainPixelSize, invertedX, invertedY)
                        .thenAccept(fullResToStageTransform -> {
                            if (fullResToStageTransform == null) {
                                logger.info("Transform setup cancelled");
                                return;
                            }

                            logger.info("Alignment transform complete (full-res→stage): {}", fullResToStageTransform);

                            // Set the current transform for immediate use
                            MicroscopeController.getInstance().setCurrentTransform(fullResToStageTransform);

                            // Create and save the general macro→stage transform
                            saveGeneralTransform(gui, alignConfig, fullResToStageTransform,
                                    detectionResults, macroPixelSize, invertedX, invertedY,
                                    transformManager);

                        }).exceptionally(ex -> {
                            logger.error("Error in transform setup", ex);
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Transform setup failed: " + ex.getMessage(),
                                    "Transform Error"
                            ));
                            return null;
                        });

            } catch (Exception e) {
                logger.error("Error processing alignment", e);
                UIFunctions.notifyUserOfError(
                        "Failed to process alignment: " + e.getMessage(),
                        "Alignment Error"
                );
            }
        });
    }

    /**
     * Creates an affine transform that maps coordinates from macro image space to full resolution image space.
     *
     * <p><b>Important:</b> This creates a transform for the ORIGINAL (unflipped) macro coordinates.
     * The flips are handled separately when creating the general transform.</p>
     *
     * @param detectionResults Results from macro image analysis including green box detection and dimensions
     * @param macroPixelSize Pixel size of the macro image in micrometers (typically ~80 µm)
     * @param mainPixelSize Pixel size of the full resolution image in micrometers (typically ~0.25 µm)
     * @return AffineTransform that converts macro image coordinates (original/unflipped) to full resolution image coordinates
     */
    private static AffineTransform createMacroToFullResTransform(
            MacroImageResults detectionResults,
            double macroPixelSize,
            double mainPixelSize) {

        AffineTransform transform = new AffineTransform();

        if (detectionResults.greenBoxResult() != null &&
                detectionResults.greenBoxResult().getDetectedBox() != null) {

            ROI greenBox = detectionResults.greenBoxResult().getDetectedBox();
            double greenBoxX = greenBox.getBoundsX();
            double greenBoxY = greenBox.getBoundsY();
            double greenBoxWidth = greenBox.getBoundsWidth();
            double greenBoxHeight = greenBox.getBoundsHeight();

            // Get full resolution image dimensions
            QuPathGUI gui = QuPathGUI.getInstance();
            double fullResWidth = gui.getImageData().getServer().getWidth();
            double fullResHeight = gui.getImageData().getServer().getHeight();

            logger.info("Creating macro to full-res transform:");
            logger.info("  Green box in original macro: ({}, {}, {}, {})",
                    greenBoxX, greenBoxY, greenBoxWidth, greenBoxHeight);
            logger.info("  Full-res image: {} x {}", fullResWidth, fullResHeight);

            // The green box represents the full image area
            // We need to map from macro coordinates to full-res coordinates

            // Calculate scale factors
            double scaleX = fullResWidth / greenBoxWidth;
            double scaleY = fullResHeight / greenBoxHeight;

            // Transform: translate by green box offset, then scale
            transform.translate(-greenBoxX, -greenBoxY);
            transform.scale(scaleX, scaleY);

            logger.info("  Transform: translate({}, {}), scale({}, {})",
                    -greenBoxX, -greenBoxY, scaleX, scaleY);

        } else {
            // No green box - this shouldn't happen in normal workflow
            logger.error("No green box detected - cannot create accurate transform!");
            // Fallback to pixel size scaling (not recommended)
            double scale = macroPixelSize / mainPixelSize;
            transform.scale(scale, scale);
        }

        return transform;
    }

    /**
     * Creates tiles for alignment based on available annotations.
     *
     * @implNote Tiles are created in full resolution coordinates for use with the alignment UI
     */
    private static void createAlignmentTiles(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) {

        // First, try to get tissue annotations specifically
        var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        "Tissue".equals(a.getClassification()))
                .toList();

        // Get flip settings to log them
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
        logger.info("Creating alignment tiles with flips - X: {}, Y: {}", flipX, flipY);

        // If we have tissue annotations, use those for tiling
        if (!tissueAnnotations.isEmpty()) {
            logger.info("Found {} tissue annotations for tiling", tissueAnnotations.size());
            createTilesForAnnotations(gui, tissueAnnotations, sampleSetup, tempTileDirectory,
                    modeWithIndex, invertedX, invertedY);
            return;
        }

        // Otherwise fall back to any valid annotation class
        logger.info("No tissue annotations found, checking for other valid annotation types");
        var annotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        VALID_ANNOTATION_CLASSES.contains(a.getClassification()))
                .toList();

        if (annotations.isEmpty()) {
            logger.warn("No annotations found for tiling. Looking for classes: {}",
                    VALID_ANNOTATION_CLASSES);
            return;
        }

        logger.info("Found {} annotations for tiling (non-tissue)", annotations.size());
        createTilesForAnnotations(gui, annotations, sampleSetup, tempTileDirectory,
                modeWithIndex, invertedX, invertedY);
    }

    /**
     * Helper method to create tiles for given annotations.
     *
     * @implNote Tiles are created in full resolution coordinates
     */
    private static void createTilesForAnnotations(
            QuPathGUI gui,
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) {

        try {
            // Remove existing tiles from previous runs of this modality
            String modalityBase = sampleSetup.modality().replaceAll("(_\\d+)$", "");
            gui.getViewer().getHierarchy().getDetectionObjects().stream()
                    .filter(o -> o.getClassification() != null &&
                            o.getClassification().contains(modalityBase))
                    .toList()  // Collect to list first to avoid concurrent modification
                    .forEach(o -> gui.getViewer().getHierarchy().removeObject(o, false));
            gui.getViewer().getHierarchy().fireHierarchyChangedEvent(gui.getViewer());
            logger.info("Removed existing tiles for modality base: {}", modalityBase);

            // Get camera parameters from config
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            double pixelSize = mgr.getDouble("imagingMode", sampleSetup.modality(), "pixelSize_um");
            int cameraWidth = mgr.getInteger("imagingMode", sampleSetup.modality(), "detector", "width_px");
            int cameraHeight = mgr.getInteger("imagingMode", sampleSetup.modality(), "detector", "height_px");

            double mainPixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();

            double frameWidthMicrons = pixelSize * cameraWidth;
            double frameHeightMicrons = pixelSize * cameraHeight;

            // Convert to QuPath pixels (full resolution)
            double frameWidthQP = frameWidthMicrons / mainPixelSize;
            double frameHeightQP = frameHeightMicrons / mainPixelSize;
            double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

            logger.info("Creating tiles: frame size {}x{} QP pixels ({}x{} µm) for modality {}",
                    frameWidthQP, frameHeightQP, frameWidthMicrons, frameHeightMicrons, sampleSetup.modality());

            // Build tiling request - tiles in full resolution coordinates
            TilingRequest request = new TilingRequest.Builder()
                    .outputFolder(tempTileDirectory)
                    .modalityName(modeWithIndex)
                    .frameSize(frameWidthQP, frameHeightQP)
                    .overlapPercent(overlapPercent)
                    .annotations(annotations)
                    .invertAxes(invertedX, invertedY)
                    .createDetections(true)  // Important for alignment
                    .addBuffer(true)
                    .build();

            TilingUtilities.createTiles(request);
            logger.info("Created detection tiles for alignment");

        } catch (IOException e) {
            logger.error("Failed to create tiles", e);
            UIFunctions.notifyUserOfError(
                    "Failed to create tiles: " + e.getMessage(),
                    "Tiling Error"
            );
        }
    }

    /**
     * Saves the general transform with metadata.
     *
     * @param gui QuPath GUI instance
     * @param config Alignment configuration
     * @param fullResToStageTransform Transform that maps full-res pixels → stage micrometers
     * @param macroImageResults Macro image detection results
     * @param macroPixelSize Macro image pixel size in micrometers
     * @param invertedX Whether X axis is inverted
     * @param invertedY Whether Y axis is inverted
     * @param transformManager Manager for saving transforms
     */
    private static void saveGeneralTransform(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            AffineTransform fullResToStageTransform,
            MacroImageResults macroImageResults,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            AffineTransformManager transformManager) {

        try {
            // The fullResToStageTransform maps: full-res pixels → stage µm
            // We need to create: macro pixels (flipped) → stage µm

            // Get flip settings
            boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

            // First, create the macro (unflipped) to full-res transform
            double mainPixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();

            AffineTransform macroUnflippedToFullRes = createMacroToFullResTransform(
                    macroImageResults, macroPixelSize, mainPixelSize);

            // For a flipped macro image, we need to account for the green box position
            // The green box in the flipped image needs special handling
            if (flipX || flipY) {
                // Get the green box details
                ROI greenBox = macroImageResults.greenBoxResult().getDetectedBox();
                double gbX = greenBox.getBoundsX();
                double gbY = greenBox.getBoundsY();
                double gbWidth = greenBox.getBoundsWidth();
                double gbHeight = greenBox.getBoundsHeight();

                // Apply flips to green box position
                if (flipX) {
                    gbX = macroImageResults.macroWidth() - gbX - gbWidth;
                }
                if (flipY) {
                    gbY = macroImageResults.macroHeight() - gbY - gbHeight;
                }

                // Create transform for flipped macro directly to full-res
                double fullResWidth = gui.getImageData().getServer().getWidth();
                double fullResHeight = gui.getImageData().getServer().getHeight();

                double scaleX = fullResWidth / gbWidth;
                double scaleY = fullResHeight / gbHeight;

                // The transform needs to map points inside the green box to the full image
                // For a point (x,y) in flipped macro coordinates:
                // 1. If it's inside the green box area, it should map to the corresponding full-res position
                // 2. The green box area (gbX to gbX+gbWidth) maps to (0 to fullResWidth)

                AffineTransform macroFlippedToFullRes = new AffineTransform();
                // First translate so green box origin goes to (0,0)
                macroFlippedToFullRes.translate(-gbX, -gbY);
                // Then scale to match full resolution size
                macroFlippedToFullRes.scale(scaleX, scaleY);

                logger.info("  Scale factors: X={}, Y={}", scaleX, scaleY);
                logger.info("  Transform steps: translate({}, {}), then scale({}, {})",
                        -gbX, -gbY, scaleX, scaleY);

                // Now create the final transform: macro (flipped) → full-res → stage
                AffineTransform macroFlippedToStage = new AffineTransform(fullResToStageTransform);
                macroFlippedToStage.concatenate(macroFlippedToFullRes);

                logger.info("Creating general macro→stage transform:");
                logger.info("  Flips applied: X={}, Y={}", flipX, flipY);
                logger.info("  Green box (original): ({}, {}, {}, {})",
                        greenBox.getBoundsX(), greenBox.getBoundsY(),
                        greenBox.getBoundsWidth(), greenBox.getBoundsHeight());
                logger.info("  Green box (flipped): ({}, {}, {}, {})", gbX, gbY, gbWidth, gbHeight);
                logger.info("  Macro (flipped)→FullRes: {}", macroFlippedToFullRes);
                logger.info("  FullRes→Stage: {}", fullResToStageTransform);
                logger.info("  Result Macro (flipped)→Stage: {}", macroFlippedToStage);

                // Detailed transform validation
                logger.info("Transform validation with key points:");

                // Test point inside green box (top-left of green box)
                double[] gbTopLeft = {gbX, gbY};
                double[] gbTopLeftStage = new double[2];
                macroFlippedToStage.transform(gbTopLeft, 0, gbTopLeftStage, 0, 1);
                logger.info("  Green box top-left ({}, {}) → stage ({}, {})",
                        gbTopLeft[0], gbTopLeft[1], gbTopLeftStage[0], gbTopLeftStage[1]);

                // Test point at green box center
                double[] gbCenter = {gbX + gbWidth/2, gbY + gbHeight/2};
                double[] gbCenterStage = new double[2];
                macroFlippedToStage.transform(gbCenter, 0, gbCenterStage, 0, 1);
                logger.info("  Green box center ({}, {}) → stage ({}, {})",
                        gbCenter[0], gbCenter[1], gbCenterStage[0], gbCenterStage[1]);

                // Calculate what the center of the full-res image should map to
                double[] fullResCenter = {fullResWidth/2, fullResHeight/2};
                double[] fullResCenterStage = new double[2];
                fullResToStageTransform.transform(fullResCenter, 0, fullResCenterStage, 0, 1);
                logger.info("  Full-res center ({}, {}) → stage ({}, {})",
                        fullResCenter[0], fullResCenter[1], fullResCenterStage[0], fullResCenterStage[1]);

                // Log the matrix values for debugging
                double[] matrix = new double[6];
                macroFlippedToStage.getMatrix(matrix);
                logger.info("  Macro→Stage matrix: [a={}, b={}, c={}, d={}, e={}, f={}]",
                        matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);

                // Validate the transform using the built-in validation method
                boolean isValid = AffineTransformManager.validateTransform(
                        macroFlippedToStage,
                        macroImageResults.macroWidth(),
                        macroImageResults.macroHeight(),
                        -21000, 33000,  // Stage X limits
                        -9000, 11000    // Stage Y limits
                );

                if (!isValid) {
                    logger.warn("Transform validation failed - may produce out-of-bounds coordinates!");

                    // Test with green box center in FLIPPED coordinates
                    double[] testPoint = {gbX + gbWidth/2, gbY + gbHeight/2};
                    double[] stageCoords = new double[2];
                    macroFlippedToStage.transform(testPoint, 0, stageCoords, 0, 1);

                    logger.warn("Green box center in flipped macro: ({}, {})",
                            testPoint[0], testPoint[1]);
                    logger.warn("Transformed to stage: ({}, {})",
                            stageCoords[0], stageCoords[1]);
                }

                // Test with known ground truth values
                logger.info("Testing with ground truth values:");

                // Test point 1: Top center tissue (flipped macro: 971, 109)
                double[] topCenter = {971, 109};
                double[] topCenterStage = new double[2];
                macroFlippedToStage.transform(topCenter, 0, topCenterStage, 0, 1);
                logger.info("  Top center (971, 109) → stage ({}, {}), expected (~16286, ~-8112)",
                        topCenterStage[0], topCenterStage[1]);

                // Test point 2: Center left tissue (flipped macro: 751, 270)
                double[] centerLeft = {751, 270};
                double[] centerLeftStage = new double[2];
                macroFlippedToStage.transform(centerLeft, 0, centerLeftStage, 0, 1);
                logger.info("  Center left (751, 270) → stage ({}, {}), expected (~-1275, ~4664)",
                        centerLeftStage[0], centerLeftStage[1]);

                // Get configuration path
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                String description = String.format(
                        "General macro-to-stage transform created via alignment workflow. " +
                                "Includes flips: X=%s, Y=%s. Macro size: %dx%d. " +
                                "Validation: %s",
                        QPPreferenceDialog.getFlipMacroXProperty(),
                        QPPreferenceDialog.getFlipMacroYProperty(),
                        macroImageResults.macroWidth(),
                        macroImageResults.macroHeight(),
                        isValid ? "PASSED" : "FAILED"
                );

                // Get the actual microscope name from config
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
                String microscopeName = mgr.getString("microscope", "name");

                // Generate a default name if not provided
                String transformName = config.transformName();
                if (transformName == null || transformName.isBlank()) {
                    transformName = microscopeName + "_Transform_" +
                            new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                }

                // Create transform preset with available data
                AffineTransformManager.TransformPreset preset =
                        new AffineTransformManager.TransformPreset(
                                transformName,
                                microscopeName,
                                "Standard", // Default mounting method
                                macroFlippedToStage,
                                description,
                                config.greenBoxParams()
                        );

                // Save using AffineTransformManager
                transformManager.savePreset(preset);

                logger.info("Saved general macro→stage transform: {}", transformName);

                // Save the transform name to preferences for future use
                QPPreferenceDialog.setSavedTransformName(transformName);

                // Notify user
                String finalTransformName = transformName;
                Platform.runLater(() -> {
                    String message = String.format(
                            "Successfully saved general alignment transform: %s\n\n" +
                                    "This transform maps macro image coordinates to stage coordinates " +
                                    "and can be used with any green box position in future workflows.\n\n" +
                                    "Transform validation: %s",
                            finalTransformName,
                            isValid ? "PASSED ✓" : "WARNING - May produce out-of-bounds coordinates!"
                    );

                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                            "Transform Saved",
                            message
                    );
                });

            } else {
                // No flips - use the original transform chain
                AffineTransform macroToStage = new AffineTransform(fullResToStageTransform);
                macroToStage.concatenate(macroUnflippedToFullRes);

                logger.info("Creating general macro→stage transform (no flips):");
                logger.info("  Macro→FullRes: {}", macroUnflippedToFullRes);
                logger.info("  FullRes→Stage: {}", fullResToStageTransform);
                logger.info("  Result Macro→Stage: {}", macroToStage);

                // Save the transform...
                // (rest of the save logic)
            }

        } catch (Exception e) {
            logger.error("Failed to save transform", e);
            Platform.runLater(() -> {
                UIFunctions.notifyUserOfError(
                        "Failed to save transform: " + e.getMessage(),
                        "Save Error"
                );
            });
        }
    }

    /**
     * Creates annotations based on detection results.
     * Priority: Green box > Tissue bounds > Full image fallback
     *
     * @deprecated This method is no longer used as tissue detection is handled separately
     */
    @Deprecated
    private static void createAnnotationsFromDetection(
            QuPathGUI gui,
            MacroImageResults macroImageResults,
            boolean flipX,
            boolean flipY) {

        logger.info("Creating annotations from detection results");

        var hierarchy = gui.getViewer().getHierarchy();
        var server = gui.getImageData().getServer();
        int imageWidth = server.getWidth();
        int imageHeight = server.getHeight();

        boolean createdAnnotation = false;

        // If green box detected, create scanned area from it
        if (macroImageResults.greenBoxResult() != null &&
                macroImageResults.greenBoxResult().getDetectedBox() != null) {

            logger.info("Creating scanned area from green box detection");

            // The green box is already in macro coordinates, we need to scale it to main image
            var greenBoxResult = macroImageResults.greenBoxResult();
            ROI greenBoxMacro = greenBoxResult.getDetectedBox();

            // Use the MacroAnalysisResult's scaling if available, otherwise calculate it
            double scaleX = (double) imageWidth / macroImageResults.macroWidth();
            double scaleY = (double) imageHeight / macroImageResults.macroHeight();

            // Scale the green box to main image coordinates
            double x = greenBoxMacro.getBoundsX() * scaleX;
            double y = greenBoxMacro.getBoundsY() * scaleY;
            double width = greenBoxMacro.getBoundsWidth() * scaleX;
            double height = greenBoxMacro.getBoundsHeight() * scaleY;

            // Apply flips to match the flipped image
            if (flipX) {
                x = imageWidth - (x + width);
            }
            if (flipY) {
                y = imageHeight - (y + height);
            }

            // Ensure bounds are within image
            x = Math.max(0, x);
            y = Math.max(0, y);
            width = Math.min(imageWidth - x, width);
            height = Math.min(imageHeight - y, height);

            ROI flippedBox = ROIs.createRectangleROI(x, y, width, height,
                    ImagePlane.getDefaultPlane());

            PathObject greenBoxAnnotation = PathObjects.createAnnotationObject(flippedBox);
            greenBoxAnnotation.setName("Scanned Area (green box)");
            greenBoxAnnotation.setClassification("Scanned Area");

            hierarchy.addObject(greenBoxAnnotation);
            logger.info("Created green box annotation");
            createdAnnotation = true;

            // Now run tissue detection within the scanned area
            runTissueDetectionScript(gui);
        }
        // If no green box, but we have tissue bounds from macro, use that as a starting point
        else if (macroImageResults.tissueResult() != null &&
                macroImageResults.tissueResult().getTissueBounds() != null) {

            logger.info("No green box detected, creating approximate tissue area from macro analysis");

            var tissueResult = macroImageResults.tissueResult();
            ROI tissueBounds = tissueResult.scaleToMainImage(tissueResult.getTissueBounds());

            if (tissueBounds != null) {
                // Apply flips to match the flipped image
                double x = tissueBounds.getBoundsX();
                double y = tissueBounds.getBoundsY();
                double width = tissueBounds.getBoundsWidth();
                double height = tissueBounds.getBoundsHeight();

                if (flipX) {
                    x = imageWidth - (x + width);
                }
                if (flipY) {
                    y = imageHeight - (y + height);
                }

                // Ensure bounds are within image
                x = Math.max(0, x);
                y = Math.max(0, y);
                width = Math.min(imageWidth - x, width);
                height = Math.min(imageHeight - y, height);

                ROI flippedBounds = ROIs.createRectangleROI(x, y, width, height,
                        ImagePlane.getDefaultPlane());

                PathObject searchArea = PathObjects.createAnnotationObject(flippedBounds);
                searchArea.setName("Search Area (from macro)");
                searchArea.setClassification("Scanned Area");

                gui.getViewer().getHierarchy().addObject(searchArea);
                logger.info("Created search area annotation for tissue detection");
                createdAnnotation = true;

                // Run tissue detection within this area
                runTissueDetectionScript(gui);
            }
        }

        // Create full image fallback only if nothing else worked
        if (!createdAnnotation) {
            logger.warn("No detection results available, creating full image annotation");

            ROI roi = ROIs.createRectangleROI(0, 0, imageWidth, imageHeight,
                    ImagePlane.getDefaultPlane());

            PathObject annotation = PathObjects.createAnnotationObject(roi);
            annotation.setName("Full Image (no detection)");
            annotation.setClassification("Bounding Box");

            gui.getViewer().getHierarchy().addObject(annotation);
            logger.info("Created full image annotation as fallback");

            // Still try tissue detection on the full image
            runTissueDetectionScript(gui);
        }

        gui.getViewer().repaint();

        // Save data
        try {
            QPProjectFunctions.saveCurrentImageData();
        } catch (IOException e) {
            logger.warn("Failed to save annotations: {}", e.getMessage());
        }
    }

    /**
     * Runs the tissue detection script if configured.
     * The script should detect tissue within any existing "Scanned Area" annotations.
     */
    private static void runTissueDetectionScript(QuPathGUI gui) {
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured");
            return;
        }

        logger.info("Running tissue detection script");

        try {
            // Get the pixel size for the script
            double pixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();

            // If we have a scanned area annotation, select it first so tissue detection
            // runs within it
            var scannedAreas = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null &&
                            ("Scanned Area".equals(a.getClassification()) ||
                                    "Bounding Box".equals(a.getClassification())))
                    .toList();

            if (!scannedAreas.isEmpty()) {
                // Select the scanned area annotations
                gui.getViewer().getHierarchy().getSelectionModel().selectObjects(scannedAreas);
                logger.info("Selected {} scanned area annotations for tissue detection", scannedAreas.size());
            }

            // Prepare the script with proper parameters
            Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
            String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                    tissueScript,
                    String.valueOf(pixelSize),
                    scriptPaths.get("jsonTissueClassfierPathString")
            );

            // Run the script
            gui.runScript(null, modifiedScript);
            logger.info("Tissue detection script completed");

            // Clear selection
            gui.getViewer().getHierarchy().getSelectionModel().clearSelection();

            // Log results
            var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getClassification() != null &&
                            "Tissue".equals(a.getClassification()))
                    .toList();
            logger.info("Found {} tissue annotations after script", tissueAnnotations.size());

        } catch (Exception e) {
            logger.error("Error running tissue detection script", e);
        }
    }

    /**
     * Validates a transform by checking if it produces reasonable stage coordinates
     * for a given set of known points. This is used for debugging transform issues.
     *
     * @param fullResToStage Transform from full-res pixels to stage micrometers
     * @param knownFullResPoint Known point in full resolution coordinates
     * @param expectedStagePoint Expected stage coordinates for the known point
     */
    private static void validateTransform(AffineTransform fullResToStage,
                                          double[] knownFullResPoint,
                                          double[] expectedStagePoint) {
        double[] calculated = new double[2];
        fullResToStage.transform(knownFullResPoint, 0, calculated, 0, 1);

        double errorX = Math.abs(calculated[0] - expectedStagePoint[0]);
        double errorY = Math.abs(calculated[1] - expectedStagePoint[1]);

        logger.info("Transform validation:");
        logger.info("  Input: ({}, {})", knownFullResPoint[0], knownFullResPoint[1]);
        logger.info("  Expected: ({}, {})", expectedStagePoint[0], expectedStagePoint[1]);
        logger.info("  Calculated: ({}, {})", calculated[0], calculated[1]);
        logger.info("  Error: ({}, {})", errorX, errorY);

        if (errorX > 100 || errorY > 100) {
            logger.warn("Transform validation failed - error exceeds 100 µm!");
        }
    }
}