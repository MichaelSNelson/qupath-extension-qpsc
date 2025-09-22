package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.StitchingConfiguration;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.StitchingBlockingDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Helper class for image stitching operations.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Stitching acquired tiles into pyramidal OME-TIFF images</li>
 *   <li>Handling multi-angle acquisitions (e.g., polarized light)</li>
 *   <li>Updating the QuPath project with stitched images and metadata</li>
 *   <li>Error handling and user notification</li>
 * </ul>
 *
 * <p>Stitching is performed asynchronously to avoid blocking the UI or
 * subsequent acquisitions.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StitchingHelper {
    private static final Logger logger = LoggerFactory.getLogger(StitchingHelper.class);

    /**
     * Container for metadata to be applied to stitched images.
     */
    public static class StitchingMetadata {
        public final ProjectImageEntry<BufferedImage> parentEntry;
        public final double xOffset;
        public final double yOffset;
        public final boolean isFlipped;
        public final String sampleName;

        public StitchingMetadata(ProjectImageEntry<BufferedImage> parentEntry,
                                 double xOffset, double yOffset,
                                 boolean isFlipped, String sampleName) {
            this.parentEntry = parentEntry;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.isFlipped = isFlipped;
            this.sampleName = sampleName;
        }
    }

    /**
     * Performs stitching for a single annotation across all rotation angles.
     *
     * <p>For multi-angle acquisitions (e.g., polarized light), this method
     * performs a single batch stitching operation that processes all angles
     * at once. The BasicStitching extension will create separate output files
     * for each angle, which are then renamed and imported into the project.</p>
     *
     * <p>For single acquisitions without rotation angles, standard stitching
     * is performed using the annotation name as the matching pattern.</p>
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler) {

        // Get transform from current controller
        AffineTransform fullResToStage = MicroscopeController.getInstance().getCurrentTransform();

        return performAnnotationStitching(
                annotation, sample, modeWithIndex, angleExposures,
                pixelSize, gui, project, executor, handler, fullResToStage
        );
    }

    /**
     * Performs stitching for a single annotation across all rotation angles.
     * Enhanced version that accepts transform for metadata calculation.
     *
     * @param annotation The annotation that was acquired
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @param fullResToStage Transform from full-res pixels to stage coordinates
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performAnnotationStitching(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler,
            AffineTransform fullResToStage) {

        // Calculate metadata for this annotation
        StitchingMetadata metadata = calculateMetadata(
                annotation, sample, gui, project, fullResToStage
        );

        // Create blocking dialog on JavaFX thread before starting stitching
        final StitchingBlockingDialog[] dialogRef = {null};
        try {
            Platform.runLater(() -> {
                dialogRef[0] = StitchingBlockingDialog.show(sample.sampleName() + " - " + annotation.getName());
            });
            // Wait briefly for dialog creation
            Thread.sleep(100);
        } catch (Exception e) {
            logger.warn("Failed to create stitching blocking dialog", e);
        }
        final StitchingBlockingDialog blockingDialog = dialogRef[0];

        if (angleExposures != null && angleExposures.size() > 1) {
            logger.info("Stitching {} angles for annotation: {}",
                    angleExposures.size(), annotation.getName());

            // For multi-angle acquisitions, do ONE batch stitch with "." as matching string
            return CompletableFuture.runAsync(() -> {
                try {
                    String annotationName = annotation.getName();
                    
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Initializing multi-angle stitching for " + annotationName + "...");
                    }

                    logger.info("Performing batch stitching for {} with {} angles",
                            annotationName, angleExposures.size());
                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    // Get standard stitching configuration
                    StitchingConfiguration.StitchingParams stitchingConfig = 
                        StitchingConfiguration.getStandardConfiguration();
                    String compression = stitchingConfig.compressionType();

                    // Create enhanced parameters map for UtilityFunctions
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);
                    
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Processing " + angleExposures.size() + " angles for " + annotationName + "...");
                    }

                    // Process each angle individually using directory isolation to prevent cross-matching
                    logger.info("Processing {} angle directories using isolation approach", angleExposures.size());
                    
                    List<String> stitchedImages = new ArrayList<>();
                    Path tileBaseDir = Paths.get(sample.projectsFolder().getAbsolutePath(), 
                                               sample.sampleName(), modeWithIndex, annotationName);
                    
                    logger.info("Starting multi-angle processing for {} angles in directory: {}", angleExposures.size(), tileBaseDir);
                    
                    // Log initial directory state
                    try {
                        if (Files.exists(tileBaseDir)) {
                            logger.info("Initial tile base directory contents:");
                            Files.list(tileBaseDir)
                                 .filter(Files::isDirectory)
                                 .forEach(path -> logger.info("  - {}", path.getFileName()));
                        } else {
                            logger.warn("Tile base directory does not exist: {}", tileBaseDir);
                        }
                    } catch (IOException e) {
                        logger.warn("Could not list initial tile base directory: {}", e.getMessage());
                    }
                    
                    for (int i = 0; i < angleExposures.size(); i++) {
                        AngleExposure angleExposure = angleExposures.get(i);
                        String angleStr = String.valueOf(angleExposure.ticks());
                        logger.info("Processing angle {} of {} - angle directory: {}", i + 1, angleExposures.size(), angleStr);
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing angle " + angleStr + " (" + (i + 1) + "/" + angleExposures.size() + ") for " + annotationName + "...");
                        }
                        
                        try {
                            // Temporarily isolate this angle directory for processing
                            logger.info("Starting isolation processing for angle: {}", angleStr);
                            String outPath = processAngleWithIsolation(
                                    tileBaseDir, angleStr, sample, modeWithIndex, annotationName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (outPath != null) {
                                stitchedImages.add(outPath);
                                logger.info("Successfully processed angle {} ({}/{}) - output: {}", angleStr, i + 1, angleExposures.size(), outPath);
                            } else {
                                logger.error("Angle processing returned null output path for angle: {}", angleStr);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch angle {} ({}/{}): {}", angleStr, i + 1, angleExposures.size(), e.getMessage(), e);
                            // Continue with next angle rather than failing completely
                        }
                        
                        // Log directory state after each angle
                        try {
                            if (Files.exists(tileBaseDir)) {
                                logger.info("Directory state after processing angle {}:", angleStr);
                                Files.list(tileBaseDir)
                                     .filter(Files::isDirectory)
                                     .forEach(path -> logger.info("  - {}", path.getFileName()));
                            }
                        } catch (IOException e) {
                            logger.warn("Could not list directory after processing angle {}: {}", angleStr, e.getMessage());
                        }
                    }
                    
                    logger.info("Completed processing {} angles. Successfully stitched {} images.", angleExposures.size(), stitchedImages.size());
                    
                    // Process birefringence image if it exists
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Checking for birefringence results for " + annotationName + "...");
                    }
                    
                    String birefAngleStr = angleExposures.get(0).ticks() + ".biref";
                    Path birefPath = tileBaseDir.resolve(birefAngleStr);
                    
                    logger.info("Checking for birefringence directory at: {}", birefPath);
                    logger.info("Birefringence directory exists: {}", Files.exists(birefPath));
                    
                    if (Files.exists(birefPath)) {
                        logger.info("Found birefringence directory: {}", birefPath);
                        
                        // Log directory contents to understand what's inside
                        try {
                            logger.info("Birefringence directory contents:");
                            Files.list(birefPath).forEach(path -> logger.info("  - {}", path));
                        } catch (IOException e) {
                            logger.warn("Could not list birefringence directory contents: {}", e.getMessage());
                        }
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing birefringence image for " + annotationName + "...");
                        }
                        
                        try {
                            logger.info("Starting birefringence isolation processing for angle string: {}", birefAngleStr);
                            String birefOutPath = processAngleWithIsolation(
                                    tileBaseDir, birefAngleStr, sample, modeWithIndex, annotationName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (birefOutPath != null) {
                                stitchedImages.add(birefOutPath);
                                logger.info("Successfully processed birefringence image - output: {}", birefOutPath);
                            } else {
                                logger.error("Birefringence processing returned null output path");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch birefringence image for angle {}: {}", birefAngleStr, e.getMessage(), e);
                        }
                    } else {
                        logger.info("No birefringence directory found at: {}", birefPath);
                        
                        // Log what directories DO exist to help debug
                        try {
                            logger.info("Available directories in tile base ({}):)", tileBaseDir);
                            if (Files.exists(tileBaseDir)) {
                                Files.list(tileBaseDir)
                                     .filter(Files::isDirectory)
                                     .forEach(path -> logger.info("  - {}", path.getFileName()));
                            } else {
                                logger.warn("Tile base directory does not exist: {}", tileBaseDir);
                            }
                        } catch (IOException e) {
                            logger.warn("Could not list tile base directory: {}", e.getMessage());
                        }
                    }

                    // Process sum image if it exists
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Checking for sum results for " + annotationName + "...");
                    }

                    String sumAngleStr = angleExposures.get(0).ticks() + ".sum";
                    Path sumPath = tileBaseDir.resolve(sumAngleStr);

                    logger.info("Checking for sum directory at: {}", sumPath);
                    logger.info("Sum directory exists: {}", Files.exists(sumPath));

                    if (Files.exists(sumPath)) {
                        logger.info("Found sum directory: {}", sumPath);

                        // Log directory contents to understand what's inside
                        try {
                            logger.info("Sum directory contents:");
                            Files.list(sumPath).forEach(path -> logger.info("  - {}", path));
                        } catch (IOException e) {
                            logger.warn("Could not list sum directory contents: {}", e.getMessage());
                        }

                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing sum image for " + annotationName + "...");
                        }

                        try {
                            logger.info("Starting sum isolation processing for angle string: {}", sumAngleStr);
                            String sumOutPath = processAngleWithIsolation(
                                    tileBaseDir, sumAngleStr, sample, modeWithIndex, annotationName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );

                            if (sumOutPath != null) {
                                stitchedImages.add(sumOutPath);
                                logger.info("Successfully processed sum image - output: {}", sumOutPath);
                            } else {
                                logger.error("Sum processing returned null output path");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch sum image for angle {}: {}", sumAngleStr, e.getMessage(), e);
                        }
                    } else {
                        logger.info("No sum directory found at: {}", sumPath);
                    }

                    // Return path of last successfully processed image
                    String outPath = stitchedImages.isEmpty() ? null : stitchedImages.get(stitchedImages.size() - 1);

                    logger.info("Batch stitching completed for {}, output: {}",
                            annotationName, outPath);
                    
                    // Close blocking dialog on success
                    if (blockingDialog != null) {
                        blockingDialog.close();
                    }

                } catch (Exception e) {
                    logger.error("Stitching failed for {}", annotation.getName(), e);
                    
                    // Close blocking dialog with error
                    if (blockingDialog != null) {
                        blockingDialog.closeWithError(e.getMessage());
                    } else {
                        Platform.runLater(() ->
                                UIFunctions.notifyUserOfError(
                                        String.format("Stitching failed for %s: %s",
                                                annotation.getName(), e.getMessage()),
                                        "Stitching Error"
                                )
                        );
                    }
                }
            }, executor);
        } else {
            // Single stitch for non-rotational acquisition (no angles)
            return CompletableFuture.runAsync(() -> {
                try {
                    String annotationName = annotation.getName();
                    
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Initializing single stitching for " + annotationName + "...");
                    }

                    logger.info("Stitching single acquisition for {}", annotationName);
                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    String compression = String.valueOf(
                            QPPreferenceDialog.getCompressionTypeProperty());

                    // Create enhanced parameters map
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);
                    
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Processing single acquisition for " + annotationName + "...");
                    }

                    // Check if we have exactly one angle (tiles are in angle subfolder)
                    String matchingString = annotationName;
                    if (angleExposures != null && angleExposures.size() == 1) {
                        // Single angle case - tiles are in angle subfolder (e.g., "5.0")
                        matchingString = String.valueOf(angleExposures.get(0).ticks());
                        logger.info("Single angle acquisition - looking in subfolder: {}", matchingString);
                    }
                    
                    String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName,
                            matchingString,  // Use angle folder name as matching string for single-angle acquisitions
                            gui,
                            project,
                            compression,
                            pixelSize,
                            1,
                            handler,
                            stitchParams  // Pass metadata
                    );

                    logger.info("Stitching completed for {}, output: {}",
                            annotationName, outPath);
                    
                    // Close blocking dialog on success
                    if (blockingDialog != null) {
                        blockingDialog.close();
                    }

                } catch (Exception e) {
                    logger.error("Stitching failed for {}", annotation.getName(), e);
                    
                    // Close blocking dialog with error
                    if (blockingDialog != null) {
                        blockingDialog.closeWithError(e.getMessage());
                    } else {
                        Platform.runLater(() ->
                                UIFunctions.notifyUserOfError(
                                        String.format("Stitching failed for %s: %s",
                                                annotation.getName(), e.getMessage()),
                                        "Stitching Error"
                                )
                        );
                    }
                }
            }, executor);
        }
    }

    /**
     * Performs stitching for a region identified by name (for BoundingBoxWorkflow).
     * This is used when there's no actual PathObject annotation, just a region name like "bounds".
     *
     * @param regionName The name of the region (e.g., "bounds" for BoundingBoxWorkflow)
     * @param sample Sample setup information
     * @param modeWithIndex Imaging mode with index suffix
     * @param angleExposures Rotation angles with exposure settings (empty for single acquisition)
     * @param pixelSize Pixel size in micrometers
     * @param gui QuPath GUI instance
     * @param project QuPath project to update
     * @param executor Executor service for async execution
     * @param handler Modality handler for file naming
     * @return CompletableFuture that completes when all stitching is done
     */
    public static CompletableFuture<Void> performRegionStitching(
            String regionName,
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex,
            List<AngleExposure> angleExposures,
            double pixelSize,
            QuPathGUI gui,
            Project<BufferedImage> project,
            ExecutorService executor,
            ModalityHandler handler) {

        // Calculate metadata for BoundingBox case (no actual annotation)
        StitchingMetadata metadata = calculateMetadataForRegion(
                regionName, sample, gui, project
        );

        // Create blocking dialog on JavaFX thread before starting stitching
        final StitchingBlockingDialog[] dialogRef = {null};
        try {
            Platform.runLater(() -> {
                dialogRef[0] = StitchingBlockingDialog.show(sample.sampleName() + " - " + regionName);
            });
            // Wait briefly for dialog creation
            Thread.sleep(100);
        } catch (Exception e) {
            logger.warn("Failed to create stitching blocking dialog", e);
        }
        final StitchingBlockingDialog blockingDialog = dialogRef[0];

        if (angleExposures != null && angleExposures.size() > 1) {
            logger.info("Stitching {} angles for region: {}", angleExposures.size(), regionName);

            // For multi-angle acquisitions, do ONE batch stitch with "." as matching string
            return CompletableFuture.runAsync(() -> {
                try {
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Initializing multi-angle stitching for " + regionName + "...");
                    }

                    logger.info("Performing batch stitching for {} with {} angles",
                            regionName, angleExposures.size());
                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    // Get standard stitching configuration
                    StitchingConfiguration.StitchingParams stitchingConfig = 
                        StitchingConfiguration.getStandardConfiguration();
                    String compression = stitchingConfig.compressionType();

                    // Create enhanced parameters map for UtilityFunctions
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);
                    
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Processing " + angleExposures.size() + " angles for " + regionName + "...");
                    }

                    // Process each angle individually using directory isolation to prevent cross-matching
                    logger.info("Processing {} angle directories using isolation approach", angleExposures.size());
                    
                    List<String> stitchedImages = new ArrayList<>();
                    Path tileBaseDir = Paths.get(sample.projectsFolder().getAbsolutePath(), 
                                               sample.sampleName(), modeWithIndex, regionName);
                    
                    logger.info("Starting multi-angle processing for {} angles in directory: {}", angleExposures.size(), tileBaseDir);
                    
                    // Log initial directory state
                    try {
                        if (Files.exists(tileBaseDir)) {
                            logger.info("Initial tile base directory contents:");
                            Files.list(tileBaseDir)
                                 .filter(Files::isDirectory)
                                 .forEach(path -> logger.info("  - {}", path.getFileName()));
                        } else {
                            logger.warn("Tile base directory does not exist: {}", tileBaseDir);
                        }
                    } catch (IOException e) {
                        logger.warn("Could not list initial tile base directory: {}", e.getMessage());
                    }
                    
                    for (int i = 0; i < angleExposures.size(); i++) {
                        AngleExposure angleExposure = angleExposures.get(i);
                        String angleStr = String.valueOf(angleExposure.ticks());
                        logger.info("Processing angle {} of {} - angle directory: {}", i + 1, angleExposures.size(), angleStr);
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing angle " + angleStr + " (" + (i + 1) + "/" + angleExposures.size() + ") for " + regionName + "...");
                        }
                        
                        try {
                            // Temporarily isolate this angle directory for processing
                            logger.info("Starting isolation processing for angle: {}", angleStr);
                            String outPath = processAngleWithIsolation(
                                    tileBaseDir, angleStr, sample, modeWithIndex, regionName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (outPath != null) {
                                stitchedImages.add(outPath);
                                logger.info("Successfully processed angle {} ({}/{}) - output: {}", angleStr, i + 1, angleExposures.size(), outPath);
                            } else {
                                logger.error("Angle processing returned null output path for angle: {}", angleStr);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch angle {} ({}/{}): {}", angleStr, i + 1, angleExposures.size(), e.getMessage(), e);
                            // Continue with next angle rather than failing completely
                        }
                        
                        // Log directory state after each angle
                        try {
                            if (Files.exists(tileBaseDir)) {
                                logger.info("Directory state after processing angle {}:", angleStr);
                                Files.list(tileBaseDir)
                                     .filter(Files::isDirectory)
                                     .forEach(path -> logger.info("  - {}", path.getFileName()));
                            }
                        } catch (IOException e) {
                            logger.warn("Could not list directory after processing angle {}: {}", angleStr, e.getMessage());
                        }
                    }
                    
                    logger.info("Completed processing {} angles. Successfully stitched {} images.", angleExposures.size(), stitchedImages.size());
                    
                    // Process birefringence image if it exists
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Checking for birefringence results for " + regionName + "...");
                    }
                    
                    String birefAngleStr = angleExposures.get(0).ticks() + ".biref";
                    Path birefPath = tileBaseDir.resolve(birefAngleStr);
                    
                    logger.info("Checking for birefringence directory at: {}", birefPath);
                    logger.info("Birefringence directory exists: {}", Files.exists(birefPath));
                    
                    if (Files.exists(birefPath)) {
                        logger.info("Found birefringence directory: {}", birefPath);
                        
                        // Log directory contents to understand what's inside
                        try {
                            logger.info("Birefringence directory contents:");
                            Files.list(birefPath).forEach(path -> logger.info("  - {}", path));
                        } catch (IOException e) {
                            logger.warn("Could not list birefringence directory contents: {}", e.getMessage());
                        }
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing birefringence image for " + regionName + "...");
                        }
                        
                        try {
                            logger.info("Starting birefringence isolation processing for angle string: {}", birefAngleStr);
                            String birefOutPath = processAngleWithIsolation(
                                    tileBaseDir, birefAngleStr, sample, modeWithIndex, regionName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (birefOutPath != null) {
                                stitchedImages.add(birefOutPath);
                                logger.info("Successfully processed birefringence image - output: {}", birefOutPath);
                            } else {
                                logger.error("Birefringence processing returned null output path");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch birefringence image for angle {}: {}", birefAngleStr, e.getMessage(), e);
                        }
                    } else {
                        logger.info("No birefringence directory found at: {}", birefPath);
                        
                        // Log what directories DO exist to help debug
                        try {
                            logger.info("Available directories in tile base ({}):)", tileBaseDir);
                            if (Files.exists(tileBaseDir)) {
                                Files.list(tileBaseDir)
                                     .filter(Files::isDirectory)
                                     .forEach(path -> logger.info("  - {}", path.getFileName()));
                            } else {
                                logger.warn("Tile base directory does not exist: {}", tileBaseDir);
                            }
                        } catch (IOException e) {
                            logger.warn("Could not list tile base directory: {}", e.getMessage());
                        }
                    }

                    // Process sum image if it exists
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Checking for sum results for " + regionName + "...");
                    }

                    String sumAngleStr = angleExposures.get(0).ticks() + ".sum";
                    Path sumPath = tileBaseDir.resolve(sumAngleStr);

                    logger.info("Checking for sum directory at: {}", sumPath);
                    logger.info("Sum directory exists: {}", Files.exists(sumPath));

                    if (Files.exists(sumPath)) {
                        logger.info("Found sum directory: {}", sumPath);

                        // Log directory contents to understand what's inside
                        try {
                            logger.info("Sum directory contents:");
                            Files.list(sumPath).forEach(path -> logger.info("  - {}", path));
                        } catch (IOException e) {
                            logger.warn("Could not list sum directory contents: {}", e.getMessage());
                        }

                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing sum image for " + regionName + "...");
                        }

                        try {
                            logger.info("Starting sum isolation processing for angle string: {}", sumAngleStr);
                            String sumOutPath = processAngleWithIsolation(
                                    tileBaseDir, sumAngleStr, sample, modeWithIndex, regionName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );

                            if (sumOutPath != null) {
                                stitchedImages.add(sumOutPath);
                                logger.info("Successfully processed sum image - output: {}", sumOutPath);
                            } else {
                                logger.error("Sum processing returned null output path");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch sum image for angle {}: {}", sumAngleStr, e.getMessage(), e);
                        }
                    } else {
                        logger.info("No sum directory found at: {}", sumPath);
                    }

                    // Return path of last successfully processed image
                    String outPath = stitchedImages.isEmpty() ? null : stitchedImages.get(stitchedImages.size() - 1);

                    logger.info("Batch stitching completed for {}, output: {}",
                            regionName, outPath);

                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Stitching completed for " + regionName);
                        blockingDialog.close();
                    }

                } catch (Exception e) {
                    logger.error("Multi-angle stitching failed for region {}", regionName, e);
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Stitching failed: " + e.getMessage());
                        blockingDialog.close();
                    }
                    throw new RuntimeException(e);
                }
            }, executor);

        } else {
            // Single angle or no rotation angles - simpler case
            logger.info("Stitching region: {} (single angle)", regionName);

            return CompletableFuture.runAsync(() -> {
                try {
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Stitching " + regionName + "...");
                    }

                    logger.info("Metadata - offset: ({}, {}) µm, flipped: {}, parent: {}",
                            metadata.xOffset, metadata.yOffset, metadata.isFlipped,
                            metadata.parentEntry != null ? metadata.parentEntry.getImageName() : "none");

                    // Get standard stitching configuration
                    StitchingConfiguration.StitchingParams stitchingConfig = 
                        StitchingConfiguration.getStandardConfiguration();
                    String compression = stitchingConfig.compressionType();

                    // Create enhanced parameters map for UtilityFunctions
                    Map<String, Object> stitchParams = new HashMap<>();
                    stitchParams.put("metadata", metadata);

                    // For single angle, use the region name as the matching pattern
                    String matchingPattern = regionName;
                    
                    String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            modeWithIndex,
                            regionName,
                            matchingPattern,
                            gui,
                            project,
                            compression,
                            pixelSize,
                            stitchingConfig.downsampleFactor(),
                            handler,
                            stitchParams  // Pass metadata in parameters
                    );

                    logger.info("Single-angle stitching completed for {}, output: {}",
                            regionName, outPath);

                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Stitching completed for " + regionName);
                        blockingDialog.close();
                    }

                } catch (Exception e) {
                    logger.error("Single-angle stitching failed for region {}", regionName, e);
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Stitching failed: " + e.getMessage());
                        blockingDialog.close();
                    }
                    throw new RuntimeException(e);
                }
            }, executor);
        }
    }

    /**
     * Calculates metadata for a region-based acquisition (BoundingBoxWorkflow).
     * Since there's no actual annotation, we create default metadata.
     */
    private static StitchingMetadata calculateMetadataForRegion(
            String regionName,
            SampleSetupController.SampleSetupResult sample,
            QuPathGUI gui,
            Project<BufferedImage> project) {
        
        // Get parent entry (the current open image) - may be null in BoundingBox workflow
        ProjectImageEntry<BufferedImage> parentEntry = null;
        // In BoundingBox workflow, there's typically no current image open
        // Use QuPath's proper method to check for open image
        if (gui.getViewer().hasServer() && gui.getImageData() != null) {
            try {
                parentEntry = project.getEntry(gui.getImageData());
            } catch (Exception e) {
                logger.warn("Could not get parent entry from project: {}", e.getMessage());
                parentEntry = null;
            }
        }
        
        // For BoundingBox workflow, we don't have actual annotation coordinates
        // The offset should be 0,0 since it's a full-slide acquisition
        double xOffset = 0.0;
        double yOffset = 0.0;
        
        // Check flip status from parent or preferences
        boolean isFlipped = false;
        if (parentEntry != null) {
            isFlipped = ImageMetadataManager.isFlipped(parentEntry);
        } else {
            // If no parent, use preferences
            isFlipped = QPPreferenceDialog.getFlipMacroXProperty() ||
                    QPPreferenceDialog.getFlipMacroYProperty();
        }
        
        return new StitchingMetadata(
                parentEntry,
                xOffset,
                yOffset,
                isFlipped,
                sample.sampleName()
        );
    }

    /**
     * Calculates metadata for a stitched image based on its parent annotation.
     */
    private static StitchingMetadata calculateMetadata(
            PathObject annotation,
            SampleSetupController.SampleSetupResult sample,
            QuPathGUI gui,
            Project<BufferedImage> project,
            AffineTransform fullResToStage) {

        // Get parent entry (the current open image)
        ProjectImageEntry<BufferedImage> parentEntry = null;
        if (gui.getViewer().hasServer() && gui.getImageData() != null) {
            parentEntry = project.getEntry(gui.getImageData());
        }

        // Calculate offset from slide corner
        double[] offset = TransformationFunctions.calculateAnnotationOffsetFromSlideCorner(
                annotation, fullResToStage);

        // Check flip status from parent or preferences
        boolean isFlipped = false;
        if (parentEntry != null) {
            isFlipped = ImageMetadataManager.isFlipped(parentEntry);
        } else {
            // If no parent, use preferences
            isFlipped = QPPreferenceDialog.getFlipMacroXProperty() ||
                    QPPreferenceDialog.getFlipMacroYProperty();
        }

        return new StitchingMetadata(
                parentEntry,
                offset[0],
                offset[1],
                isFlipped,
                sample.sampleName()
        );
    }

    /**
     * Processes a single angle directory in isolation to prevent cross-matching issues
     * with the TileConfigurationTxtStrategy contains() logic.
     */
    private static String processAngleWithIsolation(
            Path tileBaseDir, String angleStr, 
            SampleSetupController.SampleSetupResult sample,
            String modeWithIndex, String regionName,
            String compression, double pixelSize, int downsampleFactor,
            QuPathGUI gui, Project<BufferedImage> project, 
            ModalityHandler handler, Map<String, Object> stitchParams) throws IOException {
        
        logger.info("Processing angle {} with directory isolation for region {}", angleStr, regionName);
        logger.info("Tile base directory: {}", tileBaseDir);
        
        Path angleDir = tileBaseDir.resolve(angleStr);
        if (!Files.exists(angleDir)) {
            logger.warn("Angle directory does not exist: {}", angleDir);
            return null;
        }
        
        // Create a temporary isolation directory
        String tempDirName = "_temp_" + angleStr.replace("-", "neg").replace(".", "_");
        Path tempIsolationDir = tileBaseDir.resolve(tempDirName);
        Path tempAngleDir = tempIsolationDir.resolve(angleStr);
        
        logger.info("Temporary isolation directory: {}", tempIsolationDir);
        logger.info("Target angle directory: {}", angleDir);
        
        try {
            // Create temporary structure and move angle directory
            Files.createDirectories(tempIsolationDir);
            logger.info("Created temporary isolation directory: {}", tempIsolationDir);
            
            // Move the target angle directory to isolation
            Files.move(angleDir, tempAngleDir);
            logger.info("Moved {} to isolation: {}", angleDir, tempAngleDir);
            
            // CRITICAL FIX: Pass the combined region path that includes both region and temp directory
            // The stitching method will look in: projects/sample/mode/[regionName/tempDirName]/
            // which now contains only the single angle directory we want to process
            String combinedRegion = regionName + File.separator + tempDirName;
            String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                    sample.projectsFolder().getAbsolutePath(),
                    sample.sampleName(),
                    modeWithIndex,
                    combinedRegion, // FIXED: Use combined region path so path resolves correctly
                    angleStr, // Now this will only match the single directory in isolation
                    gui,
                    project,
                    compression,
                    pixelSize,
                    downsampleFactor,
                    handler,
                    stitchParams
            );
            
            logger.info("Isolation processing completed for angle {}, output: {}", angleStr, outPath);
            logger.info("Final stitched file path: {}", outPath);
            return outPath;
            
        } finally {
            // Always restore the directory structure
            logger.info("Starting cleanup - restoring directory structure for angle {}", angleStr);
            try {
                if (Files.exists(tempAngleDir)) {
                    logger.info("Restoring directory from {} to {}", tempAngleDir, angleDir);
                    Files.move(tempAngleDir, angleDir);
                    logger.info("Successfully restored {} from isolation", angleStr);
                } else {
                    logger.warn("Temporary angle directory no longer exists: {}", tempAngleDir);
                }
                if (Files.exists(tempIsolationDir)) {
                    logger.info("Cleaning up temporary isolation directory: {}", tempIsolationDir);
                    Files.delete(tempIsolationDir);
                    logger.info("Successfully cleaned up temporary isolation directory");
                } else {
                    logger.warn("Temporary isolation directory no longer exists: {}", tempIsolationDir);
                }
                logger.info("Directory structure restoration completed for angle {}", angleStr);
            } catch (IOException e) {
                logger.error("Failed to restore directory structure after isolation for angle {}: {}", angleStr, e.getMessage(), e);
                // Log the current state for debugging
                logger.error("Current state - tempAngleDir exists: {}, tempIsolationDir exists: {}, originalAngleDir exists: {}", 
                    Files.exists(tempAngleDir), Files.exists(tempIsolationDir), Files.exists(angleDir));
            }
        }
    }
}