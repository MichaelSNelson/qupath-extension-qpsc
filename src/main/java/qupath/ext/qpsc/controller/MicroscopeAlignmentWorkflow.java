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
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
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
        logger.info("*******************************************");
        logger.info("Starting Microscope Alignment Workflow...");
        logger.info("********************************************");
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
                    // Check if this is a cancellation - if so, handle gracefully
                    if (ex.getCause() instanceof CancellationException) {
                        logger.info("User cancelled the workflow");
                        return null;
                    }

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
            int macroHeight,
            int cropOffsetX,
            int cropOffsetY,
            int originalMacroWidth,
            int originalMacroHeight
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

        // Get the original macro image
        BufferedImage originalMacroImage = MacroImageUtility.retrieveMacroImage(gui);
        if (originalMacroImage == null) {
            logger.error("No macro image available");
            return null;
        }

        int originalMacroWidth = originalMacroImage.getWidth();
        int originalMacroHeight = originalMacroImage.getHeight();
        logger.info("Original macro dimensions: {}x{}", originalMacroWidth, originalMacroHeight);

        // Crop the macro image to just the slide area
        String scanner = QPPreferenceDialog.getSelectedScannerProperty();
        logger.info("Using scanner '{}' for macro image processing", scanner);

        MacroImageUtility.CroppedMacroResult croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage);
        BufferedImage croppedMacroImage = croppedResult.getCroppedImage();

        int macroWidth = croppedMacroImage.getWidth();
        int macroHeight = croppedMacroImage.getHeight();
        logger.info("Cropped macro dimensions: {}x{} (offset: {}, {})",
                macroWidth, macroHeight, croppedResult.getCropOffsetX(), croppedResult.getCropOffsetY());

        // Get flip settings
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();

        GreenBoxDetector.DetectionResult greenBoxResult = null;
        AffineTransform greenBoxTransform = null;

        // Try green box detection if enabled
        if (config.useGreenBoxDetection()) {
            try {
                // APPLY FLIPS to match what user saw in preview
                BufferedImage imageForDetection = croppedMacroImage;
                if (flipX || flipY) {
                    imageForDetection = MacroImageUtility.flipMacroImage(croppedMacroImage, flipX, flipY);
                    logger.info("Applied flips for green box detection: X={}, Y={}", flipX, flipY);
                }

                greenBoxResult = GreenBoxDetector.detectGreenBox(imageForDetection, config.greenBoxParams());

                if (greenBoxResult != null && greenBoxResult.getConfidence() > 0.7) {
                    logger.info("Green box detected in flipped macro with confidence {}", greenBoxResult.getConfidence());

                    // The green box coordinates are now in FLIPPED cropped image space
                    ROI greenBoxFlipped = greenBoxResult.getDetectedBox();
                    logger.info("Green box in flipped cropped macro: ({}, {}, {}, {})",
                            greenBoxFlipped.getBoundsX(), greenBoxFlipped.getBoundsY(),
                            greenBoxFlipped.getBoundsWidth(), greenBoxFlipped.getBoundsHeight());

                    // Calculate transform using the flipped coordinates directly
                    int mainWidth = gui.getImageData().getServer().getWidth();
                    int mainHeight = gui.getImageData().getServer().getHeight();

                    // Use the transform function for flipped coordinates
                    greenBoxTransform = TransformationFunctions.calculateMacroFlippedToFullResTransform(
                            greenBoxFlipped,
                            mainWidth,
                            mainHeight
                    );
                }
            } catch (Exception e) {
                logger.error("Error during green box detection", e);
            }
        }

        // Try tissue detection - this might need the original image or can work with cropped
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

        return new MacroImageResults(
                greenBoxResult,
                tissueResult,
                greenBoxTransform,
                macroWidth,          // Cropped width
                macroHeight,         // Cropped height
                croppedResult.getCropOffsetX(),
                croppedResult.getCropOffsetY(),
                originalMacroWidth,  // Original width
                originalMacroHeight  // Original height
        );
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

// Get macro pixel size from scanner configuration
                double macroPixelSize;
                try {
                    macroPixelSize = MacroImageUtility.getMacroPixelSize();
                    logger.info("Using macro pixel size {} µm from scanner configuration", macroPixelSize);
                } catch (IllegalStateException e) {
                    logger.error("Failed to get macro pixel size: {}", e.getMessage());
                    UIFunctions.notifyUserOfError(
                            e.getMessage() + "\n\nCannot proceed with alignment.",
                            "Configuration Error"
                    );
                    return;
                }

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
     * Saves a general macro-to-stage transform that can be reused across different imaging sessions.
     *
     * <p>This method creates and persists an affine transform that maps coordinates from a macro image
     * to physical stage coordinates in micrometers. The macro image is expected to be in the same
     * orientation as the whole slide image and as the user sees the tissue through the microscope.</p>
     *
     * <h3>Transform Details:</h3>
     * <ul>
     *   <li><b>Input:</b> Macro image coordinates as displayed to the user (pixels)</li>
     *   <li><b>Output:</b> Physical stage coordinates (micrometers)</li>
     *   <li><b>Green box:</b> If detected, provides accurate registration between macro and full-res images</li>
     *   <li><b>Fallback:</b> Uses pixel size scaling if no green box is detected (less accurate)</li>
     * </ul>
     *
     * <h3>Coordinate System Expectations:</h3>
     * <ul>
     *   <li>The macro image should show tissue in the same orientation as seen through the microscope</li>
     *   <li>The macro image should match the orientation of the whole slide image in QuPath</li>
     *   <li>Any flips or rotations needed for display consistency should be applied before this method</li>
     * </ul>
     *
     * <h3>Validation:</h3>
     * <p>The transform is validated using:</p>
     * <ul>
     *   <li>Ground truth points with known macro-to-stage mappings</li>
     *   <li>Stage boundary checks to ensure coordinates fall within valid ranges</li>
     *   <li>Transform consistency checks</li>
     * </ul>
     *
     * <h3>Saved Information:</h3>
     * <p>The transform preset includes:</p>
     * <ul>
     *   <li>Transform matrix coefficients</li>
     *   <li>Microscope name and configuration</li>
     *   <li>Macro image dimensions and crop information</li>
     *   <li>Green box detection parameters (if applicable)</li>
     *   <li>Creation timestamp and validation results</li>
     * </ul>
     *
     * @param gui QuPath GUI instance providing access to the current image data
     * @param config Alignment configuration containing transform name and detection parameters
     * @param fullResToStageTransform Transform mapping full-resolution pixels to stage micrometers,
     *                                created during manual alignment
     * @param macroImageResults Detection results containing green box location and macro dimensions
     * @param macroPixelSize Physical size of each macro image pixel in micrometers
     * @param invertedX Whether the microscope stage X-axis is inverted (unused, kept for compatibility)
     * @param invertedY Whether the microscope stage Y-axis is inverted (unused, kept for compatibility)
     * @param transformManager Manager responsible for persisting transforms to disk
     *
     * @throws IllegalStateException if the transform calculation fails or produces invalid results
     *
     * @since 0.3.0
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
            // Get image dimensions
            int fullResWidth = gui.getImageData().getServer().getWidth();
            int fullResHeight = gui.getImageData().getServer().getHeight();

            // Create the transform based on whether we have green box detection
            AffineTransform macroToStageTransform;

            if (macroImageResults.greenBoxResult() != null &&
                    macroImageResults.greenBoxResult().getDetectedBox() != null) {

                // Get the green box ROI - it's in the displayed macro image coordinates
                ROI greenBox = macroImageResults.greenBoxResult().getDetectedBox();

                logger.info("Using green box at ({}, {}, {}, {}) in displayed macro image",
                        greenBox.getBoundsX(), greenBox.getBoundsY(),
                        greenBox.getBoundsWidth(), greenBox.getBoundsHeight());

                // The green box in the macro image corresponds to the full resolution image
                // Create transform: macro → full-res
                double scaleX = fullResWidth / greenBox.getBoundsWidth();
                double scaleY = fullResHeight / greenBox.getBoundsHeight();

                AffineTransform macroToFullRes = new AffineTransform();
                // Scale first, then translate
                macroToFullRes.scale(scaleX, scaleY);
                macroToFullRes.translate(-greenBox.getBoundsX(), -greenBox.getBoundsY());

                logger.info("Macro→FullRes transform: scale({}, {}), translate({}, {})",
                        scaleX, scaleY, -greenBox.getBoundsX(), -greenBox.getBoundsY());

                // Test the macro to full-res transform
                Point2D gbTopLeft = new Point2D.Double(greenBox.getBoundsX(), greenBox.getBoundsY());
                Point2D gbBottomRight = new Point2D.Double(
                        greenBox.getBoundsX() + greenBox.getBoundsWidth(),
                        greenBox.getBoundsY() + greenBox.getBoundsHeight());

                Point2D fullResTopLeft = new Point2D.Double();
                Point2D fullResBottomRight = new Point2D.Double();
                macroToFullRes.transform(gbTopLeft, fullResTopLeft);
                macroToFullRes.transform(gbBottomRight, fullResBottomRight);

                logger.info("Green box corners map to full-res: ({}, {}) → ({}, {}), ({}, {}) → ({}, {})",
                        gbTopLeft.getX(), gbTopLeft.getY(), fullResTopLeft.getX(), fullResTopLeft.getY(),
                        gbBottomRight.getX(), gbBottomRight.getY(), fullResBottomRight.getX(), fullResBottomRight.getY());

                // Now combine: macro → full-res → stage
                macroToStageTransform = new AffineTransform(fullResToStageTransform);
                macroToStageTransform.concatenate(macroToFullRes);

                logger.info("Created macro→stage transform: {}", macroToStageTransform);

                // Validate with test points
                validateTransformWithTestPoints(
                        macroToStageTransform,
                        macroImageResults
                );

            } else {
                // No green box - fallback
                logger.warn("No green box detected - cannot create accurate transform");
                throw new IllegalStateException("Green box detection is required for alignment");
            }

            // Log final transform
            TransformationFunctions.logTransformDetails("Final Macro→Stage", macroToStageTransform);

            // Validate the transform
            boolean isValid = TransformationFunctions.validateTransform(
                    macroToStageTransform,
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    -21000, 33000,  // Stage X limits
                    -9000, 11000     // Stage Y limits
            );

            if (!isValid) {
                logger.warn("Transform validation failed - may produce out-of-bounds coordinates!");
            }

            // Create description
            String description = String.format(
                    "Macro-to-stage transform for %dx%d macro image. " +
                            "Green box detected: %s. Validation: %s",
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    (macroImageResults.greenBoxResult() != null),
                    isValid ? "PASSED" : "FAILED"
            );

            // Get microscope name
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            String microscopeName = mgr.getString("microscope", "name");

            // Generate transform name if needed
            String transformName = config.transformName();
            if (transformName == null || transformName.isBlank()) {
                transformName = microscopeName + "_Transform_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            }

            // Create and save transform preset
            AffineTransformManager.TransformPreset preset =
                    new AffineTransformManager.TransformPreset(
                            transformName,
                            microscopeName,
                            "Standard",
                            macroToStageTransform,
                            description,
                            config.greenBoxParams()
                    );

            transformManager.savePreset(preset);
            logger.info("Saved macro→stage transform: {}", transformName);

            // Save to preferences for future use
            QPPreferenceDialog.setSavedTransformName(transformName);

            // Notify user
            String finalTransformName = transformName;
            Platform.runLater(() -> {
                String message = String.format(
                        "Successfully saved alignment transform: %s\n\n" +
                                "This transform maps macro image coordinates to stage coordinates.\n" +
                                "Transform validation: %s",
                        finalTransformName,
                        isValid ? "PASSED ✓" : "WARNING - May produce out-of-bounds coordinates!"
                );

                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "Transform Saved",
                        message
                );
            });

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
     * Validates transform with test points for debugging.
     */
    private static void validateTransformWithTestPoints(
            AffineTransform macroToStageTransform,
            MacroImageResults macroImageResults) {

        logger.info("Testing transform with ground truth values:");

        // Test point 1: Top center tissue in displayed (flipped/cropped) macro
        double[] topCenterMacro = {700, 43};  // <-- Use the actual tissue coordinates
        double[] topCenterStage = TransformationFunctions.transformMacroOriginalToStage(
                topCenterMacro,
                macroToStageTransform
        );

        logger.info("  Top center: macro ({}, {}) → stage ({}, {}), expected (~16286, ~-8112)",
                topCenterMacro[0], topCenterMacro[1],
                topCenterStage[0], topCenterStage[1]);

        // Test point 2: Center left tissue in displayed (flipped/cropped) macro
        double[] centerLeftMacro = {486, 202};  // <-- Use the actual tissue coordinates
        double[] centerLeftStage = TransformationFunctions.transformMacroOriginalToStage(
                centerLeftMacro,
                macroToStageTransform
        );

        logger.info("  Center left: macro ({}, {}) → stage ({}, {}), expected (~-1275, ~4664)",
                centerLeftMacro[0], centerLeftMacro[1],
                centerLeftStage[0], centerLeftStage[1]);

        // Create ground truth map for validation
        Map<Point2D, Point2D> groundTruth = new HashMap<>();
        groundTruth.put(
                new Point2D.Double(topCenterMacro[0], topCenterMacro[1]),
                new Point2D.Double(16286, -8112)
        );
        groundTruth.put(
                new Point2D.Double(centerLeftMacro[0], centerLeftMacro[1]),
                new Point2D.Double(-1275, 4664)
        );

        boolean groundTruthValid = TransformationFunctions.validateTransformWithGroundTruth(
                macroToStageTransform,
                groundTruth,
                500  // 500 µm tolerance
        );

        if (!groundTruthValid) {
            logger.warn("Ground truth validation failed - transform may be incorrect!");
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
}