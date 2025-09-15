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

                    // Process each angle directory individually to avoid processing .biref directories
                    // This prevents the stitching plugin from processing birefringence analysis results
                    logger.info("Processing {} angle directories individually to exclude .biref directories", angleExposures.size());
                    
                    String lastOutputPath = null;
                    for (AngleExposure angleExposure : angleExposures) {
                        String angleStr = String.valueOf(angleExposure.ticks());
                        logger.info("Processing angle directory: {}", angleStr);
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing angle " + angleStr + " for " + annotationName + "...");
                        }
                        
                        try {
                            String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                                    sample.projectsFolder().getAbsolutePath(),
                                    sample.sampleName(),
                                    modeWithIndex,
                                    annotationName,
                                    angleStr,  // Process one angle at a time
                                    gui,
                                    project,
                                    compression,
                                    pixelSize,
                                    stitchingConfig.downsampleFactor(),
                                    handler,
                                    stitchParams  // Pass metadata in parameters
                            );
                            lastOutputPath = outPath;
                            logger.info("Successfully processed angle {} - output: {}", angleStr, outPath);
                        } catch (Exception e) {
                            logger.error("Failed to stitch angle {}: {}", angleStr, e.getMessage(), e);
                            // Continue with next angle rather than failing completely
                        }
                    }
                    
                    String outPath = lastOutputPath;

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
                    
                    for (AngleExposure angleExposure : angleExposures) {
                        String angleStr = String.valueOf(angleExposure.ticks());
                        logger.info("Processing angle directory: {}", angleStr);
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing angle " + angleStr + " for " + regionName + "...");
                        }
                        
                        try {
                            // Temporarily isolate this angle directory for processing
                            String outPath = processAngleWithIsolation(
                                    tileBaseDir, angleStr, sample, modeWithIndex, regionName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (outPath != null) {
                                stitchedImages.add(outPath);
                                logger.info("Successfully processed angle {} - output: {}", angleStr, outPath);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch angle {}: {}", angleStr, e.getMessage(), e);
                            // Continue with next angle rather than failing completely
                        }
                    }
                    
                    // Process birefringence image if it exists
                    if (blockingDialog != null) {
                        blockingDialog.updateStatus("Checking for birefringence results for " + regionName + "...");
                    }
                    
                    String birefAngleStr = angleExposures.get(0).ticks() + ".biref";
                    Path birefPath = tileBaseDir.resolve(birefAngleStr);
                    
                    if (Files.exists(birefPath)) {
                        logger.info("Found birefringence directory: {}", birefPath);
                        
                        if (blockingDialog != null) {
                            blockingDialog.updateStatus("Processing birefringence image for " + regionName + "...");
                        }
                        
                        try {
                            String birefOutPath = processAngleWithIsolation(
                                    tileBaseDir, birefAngleStr, sample, modeWithIndex, regionName,
                                    compression, pixelSize, stitchingConfig.downsampleFactor(),
                                    gui, project, handler, stitchParams
                            );
                            
                            if (birefOutPath != null) {
                                stitchedImages.add(birefOutPath);
                                logger.info("Successfully processed birefringence image - output: {}", birefOutPath);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to stitch birefringence image: {}", e.getMessage(), e);
                        }
                    } else {
                        logger.info("No birefringence directory found at: {}", birefPath);
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
        
        logger.info("Processing angle {} with directory isolation", angleStr);
        
        Path angleDir = tileBaseDir.resolve(angleStr);
        if (!Files.exists(angleDir)) {
            logger.warn("Angle directory does not exist: {}", angleDir);
            return null;
        }
        
        // Create a temporary isolation directory
        Path tempIsolationDir = tileBaseDir.resolve("_temp_" + angleStr.replace("-", "neg").replace(".", "_"));
        Path tempAngleDir = tempIsolationDir.resolve(angleStr);
        
        try {
            // Create temporary structure and move angle directory
            Files.createDirectories(tempIsolationDir);
            logger.info("Created temporary isolation directory: {}", tempIsolationDir);
            
            // Move the target angle directory to isolation
            Files.move(angleDir, tempAngleDir);
            logger.info("Moved {} to isolation: {}", angleDir, tempAngleDir);
            
            // Process the isolated directory
            String outPath = TileProcessingUtilities.stitchImagesAndUpdateProject(
                    sample.projectsFolder().getAbsolutePath(),
                    sample.sampleName(),
                    modeWithIndex,
                    "_temp_" + angleStr.replace("-", "neg").replace(".", "_"), // Use temp dir as region
                    angleStr, // Now this will only match the single directory
                    gui,
                    project,
                    compression,
                    pixelSize,
                    downsampleFactor,
                    handler,
                    stitchParams
            );
            
            logger.info("Isolation processing completed for angle {}, output: {}", angleStr, outPath);
            return outPath;
            
        } finally {
            // Always restore the directory structure
            try {
                if (Files.exists(tempAngleDir)) {
                    Files.move(tempAngleDir, angleDir);
                    logger.info("Restored {} from isolation", angleStr);
                }
                if (Files.exists(tempIsolationDir)) {
                    Files.delete(tempIsolationDir);
                    logger.info("Cleaned up temporary isolation directory");
                }
            } catch (IOException e) {
                logger.error("Failed to restore directory structure after isolation: {}", e.getMessage());
            }
        }
    }
}