package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.ui.*;
import qupath.ext.qpsc.utilities.*;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced workflow for creating and saving microscope alignment transforms.
 * This workflow does NOT perform acquisition - it only creates the transform
 * that can be used later in the Existing Image workflow.
 *
 * The saved transform is a GENERAL macro-to-stage transform that works
 * regardless of where the green box appears in future macro images.
 *
 * <h3>Key Improvements:</h3>
 * <ul>
 *   <li>Early bounds detection before alignment</li>
 *   <li>Complete transform validation (macro→stage)</li>
 *   <li>Ground truth validation support</li>
 *   <li>Proper scaling based on pixel size only</li>
 * </ul>
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

        // First, show microscope selection dialog
        MicroscopeSelectionDialog.showDialog()
                .thenAccept(microscopeSelection -> {
                    if (microscopeSelection == null) {
                        logger.info("User cancelled microscope selection");
                        return;
                    }

                    // Check if microscope supports macro images
                    if (!microscopeSelection.hasMacroSupport()) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "The selected microscope '" + microscopeSelection.microscopeName() +
                                        "' does not support macro images.\n\n" +
                                        "Manual alignment is the only option for non-macro image scanners.\n" +
                                        "Please use a different workflow or select a microscope with macro image support.",
                                "No Macro Image Support"
                        ));
                        return;
                    }

                    // Store the selected scanner for later use
                    String selectedScanner = microscopeSelection.microscopeName();
                    logger.info("Selected source microscope: {}", selectedScanner);

                    // Check for macro image in the current image
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
                    String currentMicroscope = mgr.getString("microscope", "name");

                    // Continue with sample setup dialog...
                    SampleSetupController.showDialog()
                            .thenCompose(sampleSetup -> {
                                if (sampleSetup == null) {
                                    logger.info("User cancelled at sample setup");
                                    return CompletableFuture.completedFuture(null);
                                }

                                // Now show alignment dialog with proper parameters
                                return MacroImageController.showAlignmentDialog(gui, transformManager, currentMicroscope)
                                        .thenApply(alignConfig -> {
                                            if (alignConfig == null) {
                                                return null;
                                            }

                                            // Run detection NOW before project creation
                                            MacroImageResults macroImageResults = performDetection(gui, alignConfig,
                                                    selectedScanner, microscopeSelection.configPath());

                                            // Package everything together with the selected scanner
                                            return new CombinedConfig(sampleSetup, alignConfig, macroImageResults,
                                                    selectedScanner, microscopeSelection.configPath());
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

                })
                .exceptionally(ex -> {
                    logger.error("Microscope selection failed", ex);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Failed to select microscope: " + ex.getMessage(),
                            "Selection Error"));
                    return null;
                });
    }

    /**
     * Container for sample setup, alignment config, detection results, and selected scanner.
     */
    private record CombinedConfig(
            SampleSetupController.SampleSetupResult sampleSetup,
            MacroImageController.AlignmentConfig alignmentConfig,
            MacroImageResults macroImageResults,
            String selectedScanner,
            String selectedScannerConfigPath
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
            int originalMacroHeight,
            Rectangle dataBounds  // NEW: Store detected data bounds
    ) {}

    /**
     * Performs all detection BEFORE project creation while macro image is still available.
     * ENHANCED: Now also detects data bounds early.
     */
    private static MacroImageResults performDetection(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            String selectedScanner,
            String selectedScannerConfigPath) {

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

        // ENHANCED: Detect data bounds EARLY
        // NOTE: This is just a placeholder - actual detection will happen after project creation
        Rectangle dataBounds = null;
        logger.info("Data bounds detection will occur after image is added to project with flips");

        // Crop the macro image using the SELECTED SCANNER settings
        logger.info("Using scanner '{}' for macro image processing", selectedScanner);

        // Load the scanner configuration directly to avoid singleton caching
        Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(selectedScannerConfigPath);

        // Check if scanner requires cropping
        boolean requiresCropping = false;
        MacroImageUtility.CroppedMacroResult croppedResult;

        // Check if this scanner has macro configuration
        Map<String, Object> macroConfig = (Map<String, Object>) scannerConfig.get("macro");
        if (macroConfig != null) {
            Boolean cropRequired = MinorFunctions.getYamlBoolean(scannerConfig, "macro", "requiresCropping");
            requiresCropping = cropRequired != null && cropRequired;
        }

        if (requiresCropping) {
            // Get slide bounds from scanner config
            Integer xMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMin");
            Integer xMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "xMax");
            Integer yMin = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMin");
            Integer yMax = MinorFunctions.getYamlInteger(scannerConfig, "macro", "slideBounds", "yMax");

            if (xMin != null && xMax != null && yMin != null && yMax != null) {
                croppedResult = MacroImageUtility.cropToSlideArea(originalMacroImage, xMin, xMax, yMin, yMax);
                logger.info("Cropped macro image using bounds from {}: X[{}-{}], Y[{}-{}]",
                        selectedScanner, xMin, xMax, yMin, yMax);
            } else {
                logger.warn("Scanner '{}' requires cropping but bounds are not properly configured", selectedScanner);
                croppedResult = new MacroImageUtility.CroppedMacroResult(
                        originalMacroImage, originalMacroWidth, originalMacroHeight, 0, 0);
            }
        } else {
            // No cropping needed
            croppedResult = new MacroImageUtility.CroppedMacroResult(
                    originalMacroImage, originalMacroWidth, originalMacroHeight, 0, 0);
            logger.info("Scanner '{}' does not require cropping", selectedScanner);
        }

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

                    // Use data bounds if available, otherwise full image dimensions
                    int mainWidth = dataBounds != null ? dataBounds.width : gui.getImageData().getServer().getWidth();
                    int mainHeight = dataBounds != null ? dataBounds.height : gui.getImageData().getServer().getHeight();

                    logger.info("Creating green box transform for main image size: {}x{}", mainWidth, mainHeight);
                    logger.info("Green box size in macro: {}x{}",
                            greenBoxFlipped.getBoundsWidth(), greenBoxFlipped.getBoundsHeight());

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
                originalMacroHeight, // Original height
                dataBounds          // NEW: Include data bounds
        );
    }

    /**
     * Processes the alignment after detection is complete.
     * ENHANCED: Uses early-detected bounds for proper alignment.
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
                var selectedScanner = combinedConfig.selectedScanner();
                var selectedScannerConfigPath = combinedConfig.selectedScannerConfigPath();

                // Create a mutable holder for detection results that may be updated
                final MacroImageResults[] detectionResultsHolder = {detectionResults};

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

                // Get macro pixel size using the SELECTED SCANNER config
                double macroPixelSize;
                try {
                    // Load the scanner configuration directly
                    Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(selectedScannerConfigPath);
                    Double pixelSize = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixelSize_um");

                    if (pixelSize == null || pixelSize <= 0) {
                        String error = String.format(
                                "Scanner '%s' has no valid macro pixel size configured. " +
                                        "This is required for accurate alignment. " +
                                        "Please add 'macro: pixelSize_um:' to the scanner configuration.",
                                selectedScanner
                        );
                        logger.error(error);
                        UIFunctions.notifyUserOfError(error + "\n\nCannot proceed with alignment.", "Configuration Error");
                        return;
                    }

                    macroPixelSize = pixelSize;
                    logger.info("Using macro pixel size {} µm from scanner '{}' configuration",
                            macroPixelSize, selectedScanner);
                } catch (Exception e) {
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
                        modeWithIndex, invertedX, invertedY, null);  // Pass null for bounds

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
                                    detectionResultsHolder[0], macroPixelSize, invertedX, invertedY,
                                    transformManager, selectedScanner);

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
     * ENHANCED: Now accepts bounds parameter for proper tile placement.
     */
    private static void createAlignmentTiles(
            QuPathGUI gui,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY,
            Rectangle dataBounds) {

        // First, try to get tissue annotations specifically
        var tissueAnnotations = gui.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getClassification() != null &&
                        "Tissue".equals(a.getClassification()))
                .toList();

        // Get flip settings to log them
        boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
        logger.info("Creating alignment tiles with flips - X: {}, Y: {}", flipX, flipY);

        // ENHANCED: Use data bounds for tile creation if available
        Rectangle boundsForTiling = dataBounds;
        if (boundsForTiling == null) {
            // Fallback to full image
            boundsForTiling = new Rectangle(0, 0,
                    gui.getImageData().getServer().getWidth(),
                    gui.getImageData().getServer().getHeight());
        }
        logger.info("Using data bounds for tiling: x={}, y={}, width={}, height={}",
                boundsForTiling.x, boundsForTiling.y, boundsForTiling.width, boundsForTiling.height);

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
     * Saves a general macro-to-stage transform that maps coordinates from the source scanner's
     * macro image to the target microscope's stage coordinates.
     *
     * <p>The transform is created by:</p>
     * <ol>
     *   <li>Finding the green box center in the macro image</li>
     *   <li>Finding the corresponding data region center in the full-resolution image</li>
     *   <li>Using the manual alignment to map that center to stage coordinates</li>
     *   <li>Creating a transform that aligns these centers with the macro pixel size as scale</li>
     * </ol>
     *
     * @param gui The QuPath GUI instance
     * @param config Alignment configuration including transform name and parameters
     * @param fullResToStageTransform The manually-aligned transform from full-res to stage coordinates
     * @param macroImageResults Detection results from the macro image analysis
     * @param macroPixelSize Physical size of macro pixels in micrometers (typically 81.0)
     * @param invertedX Whether X axis is inverted (unused in current implementation)
     * @param invertedY Whether Y axis is inverted (unused in current implementation)
     * @param transformManager Manager for saving transform presets
     * @param selectedScanner Name of the source scanner that created the macro image
     */
    private static void saveGeneralTransform(
            QuPathGUI gui,
            MacroImageController.AlignmentConfig config,
            AffineTransform fullResToStageTransform,
            MacroImageResults macroImageResults,
            double macroPixelSize,
            boolean invertedX,
            boolean invertedY,
            AffineTransformManager transformManager,
            String selectedScanner) {

        try {
            // Step 1: Get or detect data bounds (required for accurate alignment)
            Rectangle dataBounds = macroImageResults.dataBounds();
            if (dataBounds == null) {
                // Attempt to detect bounds now
                String scriptDir = new File(QPPreferenceDialog.getTissueDetectionScriptProperty()).getParent();
                if (scriptDir != null) {
                    logger.info("Detecting data bounds...");
                    dataBounds = UIFunctions.executeWithProgress(
                            "Processing Image",
                            "Detecting image boundaries...\nAnalyzing image data - this may take a moment for large images.",
                            () -> ImageProcessing.detectOcus40DataBounds(gui, scriptDir)
                    );
                }

                if (dataBounds == null) {
                    throw new IllegalStateException("Cannot create transform without data bounds detection");
                }
            }

            // Data dimensions represent the actual tissue area excluding padding
            int fullResWidth = dataBounds.width;
            int fullResHeight = dataBounds.height;
            logger.info("Using data bounds: {}x{} at ({}, {})",
                    fullResWidth, fullResHeight, dataBounds.x, dataBounds.y);

            // Step 2: Verify green box detection exists
            if (macroImageResults.greenBoxResult() == null ||
                    macroImageResults.greenBoxResult().getDetectedBox() == null) {
                throw new IllegalStateException("Green box detection is required for alignment");
            }

            ROI greenBox = macroImageResults.greenBoxResult().getDetectedBox();

            // Step 3: Log key measurements for debugging
            logger.info("=== TRANSFORM CALCULATION ===");
            logger.info("Macro pixel size: {} µm/pixel", macroPixelSize);
            logger.info("Green box: ({}, {}) size {} x {} pixels",
                    greenBox.getBoundsX(), greenBox.getBoundsY(),
                    greenBox.getBoundsWidth(), greenBox.getBoundsHeight());
            logger.info("Data region: {} x {} pixels", fullResWidth, fullResHeight);

            // Calculate and log the scale relationship
            double fullResPixelSize = gui.getImageData().getServer()
                    .getPixelCalibration().getAveragedPixelSizeMicrons();
            double pixelScale = macroPixelSize / fullResPixelSize;
            logger.info("Scale relationship: {} macro pixels = 1 full-res pixel", pixelScale);

            // Step 4: Calculate center points
            // Green box center in macro image coordinates
            double greenBoxCenterX = greenBox.getBoundsX() + greenBox.getBoundsWidth() / 2.0;
            double greenBoxCenterY = greenBox.getBoundsY() + greenBox.getBoundsHeight() / 2.0;

            // Data region center in full-resolution image coordinates
            double fullResCenterX = dataBounds.x + dataBounds.width / 2.0;
            double fullResCenterY = dataBounds.y + dataBounds.height / 2.0;
            logger.info("Green box center: ({}, {})", greenBoxCenterX, greenBoxCenterY);
            logger.info("Data region center: ({}, {})", fullResCenterX, fullResCenterY);

            // Step 5: Transform data center to stage coordinates using manual alignment
            Point2D fullResCenter = new Point2D.Double(fullResCenterX, fullResCenterY);
            Point2D stageCenter = new Point2D.Double();
            fullResToStageTransform.transform(fullResCenter, stageCenter);
            logger.info("Data center maps to stage: ({}, {})",
                    stageCenter.getX(), stageCenter.getY());

            // Step 6: Create the macro-to-stage transform
            // This transform will map macro pixel coordinates directly to stage micrometers
            AffineTransform macroToStageTransform = new AffineTransform();

            // Scale by macro pixel size (converts pixels to micrometers)
            macroToStageTransform.scale(macroPixelSize, macroPixelSize);

            // Translate to align the centers
            // After scaling, we need to translate so green box center maps to stage center
            double translateX = stageCenter.getX() / macroPixelSize - greenBoxCenterX;
            double translateY = stageCenter.getY() / macroPixelSize - greenBoxCenterY;
            macroToStageTransform.translate(translateX, translateY);

            logger.info("Transform created with scale {} and translation ({}, {})",
                    macroPixelSize, translateX, translateY);

            // Step 7: Validate the transform
            // Log some key point transformations for debugging
            TransformationFunctions.logTransformDetails("Macro→Stage", macroToStageTransform);

            // Check if transform produces valid stage coordinates
            boolean isValid = TransformationFunctions.validateTransform(
                    macroToStageTransform,
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    -21000, 33000,  // Stage X limits in micrometers
                    -9000, 11000     // Stage Y limits in micrometers
            );

            if (!isValid) {
                logger.warn("Transform may produce out-of-bounds stage coordinates");
            }

            // Optional: Validate against ground truth if debug mode and data exists
            if (logger.isDebugEnabled()) {
                validateWithKnownGroundTruth(macroToStageTransform, fullResToStageTransform);
            }

            // Step 8: Save the transform
            String transformName = config.transformName();
            if (transformName == null || transformName.isBlank()) {
                transformName = String.format("%s_to_%s_%s",
                        selectedScanner,
                        MicroscopeConfigManager.getInstance(
                                        QPPreferenceDialog.getMicroscopeConfigFileProperty())
                                .getString("microscope", "name"),
                        new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
            }

            String description = String.format(
                    "Transform from %s macro to stage. Macro: %dx%d, Green box detected, Validation: %s",
                    selectedScanner,
                    macroImageResults.macroWidth(),
                    macroImageResults.macroHeight(),
                    isValid ? "PASSED" : "WARNING"
            );

            AffineTransformManager.TransformPreset preset = new AffineTransformManager.TransformPreset(
                    transformName,
                    MicroscopeConfigManager.getInstance(
                                    QPPreferenceDialog.getMicroscopeConfigFileProperty())
                            .getString("microscope", "name"),
                    selectedScanner,
                    macroToStageTransform,
                    description,
                    config.greenBoxParams()
            );

            transformManager.savePreset(preset);
            PersistentPreferences.setSavedTransformName(transformName);
            logger.info("Saved transform: {}", transformName);

            // Step 9: Notify user
            String finalTransformName = transformName;
            Platform.runLater(() -> {
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "Transform Saved",
                        String.format("Successfully saved alignment transform: %s", finalTransformName)
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
     * Validates transforms against known ground truth points if available.
     * This is primarily for debugging and development.
     */
    private static void validateWithKnownGroundTruth(
            AffineTransform macroToStageTransform,
            AffineTransform fullResToStageTransform) {

        // Validate macro→stage transform with known tissue points
        Map<Point2D, Point2D> macroGroundTruth = new HashMap<>();
        macroGroundTruth.put(new Point2D.Double(700, 43), new Point2D.Double(16286.456640000002, -8112.192));
        macroGroundTruth.put(new Point2D.Double(486, 202), new Point2D.Double(-1275, 4864));

        logger.info("=== MACRO→STAGE GROUND TRUTH VALIDATION ===");
        boolean macroValid = TransformationFunctions.validateTransformWithGroundTruth(
                macroToStageTransform, macroGroundTruth, 500.0); // 500µm tolerance for macro

        // Validate full-res→stage transform with known points
        Map<Point2D, Point2D> fullResGroundTruth = new HashMap<>();
        fullResGroundTruth.put(new Point2D.Double(88945, 2680.11807528976),
                new Point2D.Double(16286.456640000002, -8112.192));
        fullResGroundTruth.put(new Point2D.Double(15934, 54469),
                new Point2D.Double(-1275, 4864));

        logger.info("=== FULL-RES→STAGE GROUND TRUTH VALIDATION ===");
        boolean fullResValid = TransformationFunctions.validateTransformWithGroundTruth(
                fullResToStageTransform, fullResGroundTruth, 100.0); // 100µm tolerance for full-res

        if (!macroValid || !fullResValid) {
            logger.warn("Ground truth validation failed - transform may need adjustment");
        }
    }
    /**
     * Runs the tissue detection script if configured.
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