package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * UtilityFunctions - High-level file and scripting utilities for QuPath Scope Control.
 *
 * <p>This class provides essential utilities for the QPSC extension including:
 * <ul>
 *   <li>Tile stitching and project integration</li>
 *   <li>File system operations (deletion, compression)</li>
 *   <li>Script modification for tissue detection</li>
 *   <li>Command execution for microscope control</li>
 * </ul>
 *
 * <p>The primary workflow supported is:
 * <ol>
 *   <li>Tiles are acquired by the microscope and saved to disk</li>
 *   <li>Tiles are stitched into pyramidal OME-TIFF files</li>
 *   <li>Stitched images are imported into QuPath projects</li>
 *   <li>Original tiles are archived or deleted based on preferences</li>
 * </ol>
 *
 * @author Mike Nelson
 * @version 3.0
 * @since 1.0
 */
public class TileProcessingUtilities {
    private static final Logger logger = LoggerFactory.getLogger(TileProcessingUtilities.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    /**
     * Stitches all tiles under the given imaging mode folder into one or more OME TIFF files,
     * renames them to include sample, mode and annotation/angle information, then imports & opens
     * them in the given QuPath project (respecting X/Y inversion preferences).
     *
     * <p><b>Directory Structure:</b> The method expects tiles to be organized as:
     * <pre>
     * projectsFolderPath/
     *   sampleLabel/
     *     imagingModeWithIndex/
     *       annotationName/
     *         [angle/]  (optional subdirectories for multi-angle acquisition)
     *           tile_001_001.tif
     *           tile_001_002.tif
     *           ...
     *           TileConfiguration.txt
     * </pre>
     *
     * <p><b>Naming Convention:</b> Output files are named according to the pattern:
     * <ul>
     *   <li>Single angle/standard: {@code sampleLabel_modality_annotation.ome.tif}</li>
     *   <li>Multi-angle: {@code sampleLabel_modality_annotation_angle.ome.tif}</li>
     *   <li>Bounding box (no annotation): {@code sampleLabel_modality.ome.tif}</li>
     * </ul>
     *
     * <p><b>Batch Processing:</b> When matchingString is "." (dot), the method will process
     * all subdirectories under the annotation folder, treating each as a different angle.
     * This is used for multi-angle acquisitions like PPM (Polarized Light Microscopy).
     *
     * @param projectsFolderPath  Root folder containing per-sample subfolders
     * @param sampleLabel         Subfolder name for this sample (e.g., "Sample_001")
     * @param imagingModeWithIndex Subfolder name under sampleLabel (e.g., "ppm_10x_1", "bf_20x_2")
     * @param annotationName      Annotation identifier (e.g., "Tissue_12345_67890") or "bounds" for full slide
     * @param matchingString      Pattern to match subdirectories. Use "." for all subdirectories,
     *                           or specific angle value (e.g., "-5.0", "90.0") for single angle
     * @param qupathGUI           QuPathGUI instance for opening the imported image
     * @param project             QuPath Project to update with the stitched images
     * @param compression         OME pyramid compression type: "DEFAULT", "UNCOMPRESSED", "JPEG", "J2K", "J2K_LOSSY"
     * @param pixelSizeMicrons    Physical pixel size in micrometers for the OME-TIFF metadata
     * @param downsample          Downsample factor for pyramid generation (1 = no downsampling)
     * @param modalityHandler     Handler for modality-specific file naming (can be null)
     * @param stitchParams        Additional parameters including metadata for image import (can be null)
     *
     * @return Absolute path to the last stitched OME-TIFF processed, or null if stitching failed
     *
     * @throws IOException If stitching fails, file I/O errors occur, or no tiles are found
     *
     * @see StitchingWorkflow#run(StitchingConfig)
     * @see QPProjectFunctions#addImageToProject
     * @since 1.0
     */
    public static String stitchImagesAndUpdateProject(
            String projectsFolderPath,
            String sampleLabel,
            String imagingModeWithIndex,
            String annotationName,
            String matchingString,
            QuPathGUI qupathGUI,
            Project<BufferedImage> project,
            String compression,
            double pixelSizeMicrons,
            int downsample,
            ModalityHandler modalityHandler,
            Map<String, Object> stitchParams) throws IOException {

        logger.info("=== Starting stitching workflow ===");
        logger.info("Sample: {}, Mode: {}, Annotation: {}, Matching: '{}'",
                sampleLabel, imagingModeWithIndex, annotationName, matchingString);

        // Construct folder paths
        String tileFolder = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + imagingModeWithIndex + File.separator + annotationName;
        String stitchedFolder = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + "SlideImages";

        logger.info("Tile folder: {}", tileFolder);
        logger.info("Output folder: {}", stitchedFolder);

        // Ensure output directory exists
        File outputDir = new File(stitchedFolder);
        if (!outputDir.exists()) {
            logger.info("Creating output directory: {}", stitchedFolder);
            outputDir.mkdirs();
        }

        // Track files before stitching for batch operations
        Set<String> existingFiles = new HashSet<>();
        if (matchingString.equals(".")) {
            logger.info("Batch mode detected - scanning for existing OME-TIFF files");
            if (outputDir.exists()) {
                File[] existing = outputDir.listFiles((dir, name) -> name.endsWith(".ome.tif"));
                if (existing != null) {
                    for (File f : existing) {
                        existingFiles.add(f.getName());
                    }
                }
            }
            logger.info("Found {} existing OME-TIFF files before stitching", existingFiles.size());
        }

        // Configure and run the stitching workflow
        logger.info("Configuring stitching with compression: {}, pixel size: {} µm, downsample: {}",
                compression, pixelSizeMicrons, downsample);

        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder,
                compression,
                pixelSizeMicrons,
                downsample,
                matchingString,
                1.0  // globalQuality parameter
        );

        logger.info("Starting BasicStitching workflow...");
        String outPath = StitchingWorkflow.run(config);
        logger.info("BasicStitching workflow completed. Output: {}", outPath);

        // Handle null return from stitching
        if (outPath == null) {
            logger.error("Stitching workflow returned null - no tiles were stitched");
            throw new IOException("Stitching workflow returned null - no tiles were stitched");
        }

        final String lastProcessedPath;

        // Handle batch processing (matching string = ".")
        if (matchingString.equals(".")) {
            logger.info("Processing batch stitching results...");

            File[] allOmeTiffs = outputDir.listFiles((dir, name) ->
                    name.endsWith(".ome.tif") && !existingFiles.contains(name));

            if (allOmeTiffs == null || allOmeTiffs.length == 0) {
                logger.error("No new OME-TIFF files found after batch stitching");
                throw new IOException("No new OME-TIFF files found after batch stitching");
            }

            logger.info("Found {} new OME-TIFF files to rename and import", allOmeTiffs.length);
            String lastPath = null;

            // Process each newly created file
            for (File stitchedFile : allOmeTiffs) {
                String originalName = stitchedFile.getName();
                // Extract the angle/subdirectory name from the filename
                String angleOrSubdir = originalName.replace(".ome.tif", "");
                logger.debug("Processing file: {} (angle/subdir: {})", originalName, angleOrSubdir);

                String angleSuffix = angleOrSubdir;
                if (modalityHandler != null) {
                    try {
                        angleSuffix = modalityHandler.getAngleSuffix(Double.parseDouble(angleOrSubdir));
                    } catch (NumberFormatException ignored) {
                        // Keep original angle string if parsing fails
                    }
                }

                // Create the full name with sample, mode, annotation, and angle
                // Sanitize annotation name to replace path separators with underscores for valid filenames
                String sanitizedAnnotationName = annotationName.replace(File.separator, "_")
                                                               .replace("/", "_")
                                                               .replace("\\", "_");
                String baseName = sampleLabel + "_" + imagingModeWithIndex + "_" +
                        sanitizedAnnotationName + "_" + angleSuffix + ".ome.tif";
                File renamed = new File(stitchedFile.getParent(), baseName);

                logger.info("Renaming {} → {}", originalName, baseName);
                if (stitchedFile.renameTo(renamed)) {
                    lastPath = renamed.getAbsolutePath();
                    logger.info("Successfully renamed to: {}", baseName);

                    // Extract metadata if provided
                    StitchingHelper.StitchingMetadata metadata = null;
                    if (stitchParams != null && stitchParams.containsKey("metadata")) {
                        metadata = (StitchingHelper.StitchingMetadata) stitchParams.get("metadata");
                    }

                    // Import this file to the project
                    final String pathToImport = lastPath;
                    final StitchingHelper.StitchingMetadata finalMetadata = metadata;

                    Platform.runLater(() -> {
                        try {
                            logger.debug("Importing {} to project on FX thread", pathToImport);

                            // Add to project with metadata if available
                            if (finalMetadata != null) {
                                QPProjectFunctions.addImageToProjectWithMetadata(
                                        project,
                                        new File(pathToImport),
                                        finalMetadata.parentEntry,
                                        finalMetadata.xOffset,
                                        finalMetadata.yOffset,
                                        finalMetadata.isFlipped,
                                        finalMetadata.sampleName);
                            } else {
                                QPProjectFunctions.addImageToProject(
                                        new File(pathToImport),
                                        project,
                                        false,
                                        false);
                            }

                            logger.info("Successfully imported {} to project", new File(pathToImport).getName());

                            // Optionally open the first image
                            if (allOmeTiffs[0].getName().equals(originalName)) {
                                logger.debug("Opening first image in viewer");
                                List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                                images.stream()
                                        .filter(e -> new File(e.getImageName()).getName()
                                                .equals(new File(pathToImport).getName()))
                                        .findFirst()
                                        .ifPresent(entry -> {
                                            logger.info("Opening image entry: {}", entry.getImageName());
                                            qupathGUI.openImageEntry(entry);
                                        });
                            }
                        } catch (IOException e) {
                            logger.error("Failed to import {}: {}", pathToImport, e.getMessage(), e);
                        }
                    });
                } else {
                    logger.error("Failed to rename {} to {}", originalName, baseName);
                }
            }

            lastProcessedPath = lastPath;

            // Update project on FX thread
            Platform.runLater(() -> {
                logger.info("Refreshing project view");
                qupathGUI.setProject(project);
                qupathGUI.refreshProject();

                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        res.getString("stitching.success.title"),
                        String.format("Successfully stitched and imported %d images", allOmeTiffs.length));
            });

        } else {
            // Single file processing (original behavior)
            logger.info("Processing single stitching result");

            // Defensive check for extension
            if (outPath.endsWith(".ome") && !outPath.endsWith(".ome.tif")) {
                logger.warn("Stitching returned .ome without .tif, appending .tif extension");
                outPath = outPath + ".tif";
            }

            // Determine appropriate filename based on context
            File orig = new File(outPath);
            String baseName;

            // Check if this is angle-based stitching (matching string is different from annotation)
            if (!matchingString.equals(annotationName)) {
                String angleSuffix = matchingString;
                
                // Handle birefringence images specially
                if (matchingString.contains(".biref")) {
                    // Extract base angle and add birefringence indicator
                    String baseAngle = matchingString.replace(".biref", "");
                    if (modalityHandler != null) {
                        try {
                            String baseAngleSuffix = modalityHandler.getAngleSuffix(Double.parseDouble(baseAngle));
                            angleSuffix = baseAngleSuffix + "_biref";
                        } catch (NumberFormatException ignored) {
                            // Keep original if parsing fails
                            angleSuffix = matchingString.replace(".", "_");
                        }
                    } else {
                        angleSuffix = matchingString.replace(".", "_");
                    }
                }
                // Handle sum images specially
                else if (matchingString.contains(".sum")) {
                    // Extract base angle and add sum indicator
                    String baseAngle = matchingString.replace(".sum", "");
                    if (modalityHandler != null) {
                        try {
                            String baseAngleSuffix = modalityHandler.getAngleSuffix(Double.parseDouble(baseAngle));
                            angleSuffix = baseAngleSuffix + "_sum";
                        } catch (NumberFormatException ignored) {
                            // Keep original if parsing fails
                            angleSuffix = matchingString.replace(".", "_");
                        }
                    } else {
                        angleSuffix = matchingString.replace(".", "_");
                    }
                } else if (modalityHandler != null) {
                    try {
                        angleSuffix = modalityHandler.getAngleSuffix(Double.parseDouble(matchingString));
                    } catch (NumberFormatException ignored) {
                        // Keep original string if not a valid number
                    }
                }
                
                // This is angle-based stitching - include both annotation AND angle
                // Format: sampleLabel_modality_annotation_angle.ome.tif
                // Extract original region name from potentially combined path (e.g., "bounds\_temp_7_0" -> "bounds")
                String originalRegionName = extractOriginalRegionName(annotationName);
                // Sanitize annotation name to replace path separators with underscores for valid filenames
                String sanitizedAnnotationName = originalRegionName.replace(File.separator, "_")
                                                                   .replace("/", "_")
                                                                   .replace("\\", "_");
                baseName = sampleLabel + "_" + imagingModeWithIndex + "_" +
                        sanitizedAnnotationName + "_" + angleSuffix + ".ome.tif";
                logger.info("Angle-based stitching detected - including annotation and angle in filename");
            } else {
                // Standard stitching - just annotation (or nothing for "bounds")
                // Extract original region name from potentially combined path (e.g., "bounds\_temp_7_0" -> "bounds")
                String originalRegionName = extractOriginalRegionName(annotationName);
                // Sanitize annotation name to replace path separators with underscores for valid filenames
                String sanitizedAnnotationName = originalRegionName.replace(File.separator, "_")
                                                                   .replace("/", "_")
                                                                   .replace("\\", "_");
                baseName = sampleLabel + "_" + imagingModeWithIndex
                        + (sanitizedAnnotationName.equals("bounds") ? "" : "_" + sanitizedAnnotationName)
                        + ".ome.tif";
                logger.info("Standard stitching - using annotation-based filename");
            }

            File renamed = new File(orig.getParent(), baseName);
            logger.info("Renaming {} → {}", orig.getName(), baseName);

            if (orig.renameTo(renamed)) {
                outPath = renamed.getAbsolutePath();
                logger.info("Successfully renamed. Full path: {}", outPath);
            } else {
                logger.error("Failed to rename {} to {}", orig.getName(), baseName);
                // Continue with original path if rename fails
            }

            // Extract metadata if provided
            StitchingHelper.StitchingMetadata metadata = null;
            if (stitchParams != null && stitchParams.containsKey("metadata")) {
                metadata = (StitchingHelper.StitchingMetadata) stitchParams.get("metadata");
            }

            lastProcessedPath = outPath;
            final StitchingHelper.StitchingMetadata finalMetadata = metadata;

            // Import & open on the FX thread
            Platform.runLater(() -> {
                logger.info("Importing stitched image to project on FX thread");
                ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

                try {
                    // Add to project with metadata if available
                    if (finalMetadata != null) {
                        QPProjectFunctions.addImageToProjectWithMetadata(
                                project,
                                new File(lastProcessedPath),
                                finalMetadata.parentEntry,
                                finalMetadata.xOffset,
                                finalMetadata.yOffset,
                                finalMetadata.isFlipped,
                                finalMetadata.sampleName);
                    } else {
                        QPProjectFunctions.addImageToProject(
                                new File(lastProcessedPath),
                                project,
                                false,
                                false);
                    }

                    logger.info("Successfully added image to project");

                    // Find and open the newly added entry
                    List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                    images.stream()
                            .filter(e -> new File(e.getImageName()).getName()
                                    .equals(new File(lastProcessedPath).getName()))
                            .findFirst()
                            .ifPresent(entry -> {
                                logger.info("Opening image entry: {}", entry.getImageName());
                                qupathGUI.openImageEntry(entry);
                            });

                    // Ensure project is active & refreshed
                    qupathGUI.setProject(project);
                    qupathGUI.refreshProject();
                    logger.info("Project refreshed successfully");

                    // Notify success
                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                            res.getString("stitching.success.title"),
                            res.getString("stitching.success.message"));

                } catch (IOException e) {
                    logger.error("Failed to import stitched image", e);
                    UIFunctions.notifyUserOfError(
                            "Failed to import stitched image:\n" + e.getMessage(),
                            res.getString("stitching.error.title"));
                }
            });
        }

        logger.info("=== Stitching workflow completed ===");
        return lastProcessedPath;
    }

    /**
     * Extracts the original region name from a potentially combined path.
     * When using directory isolation for multi-angle stitching, temporary directories
     * like "_temp_7_0" are added to the annotation name, resulting in paths like
     * "bounds\_temp_7_0". This method extracts just the original region name ("bounds").
     *
     * @param annotationName The annotation name, potentially including temporary directory parts
     * @return The original region name without temporary directory components
     */
    private static String extractOriginalRegionName(String annotationName) {
        if (annotationName == null) {
            return "";
        }

        // Handle both path separators and the specific temporary directory pattern
        String[] parts = annotationName.split("[/\\\\]");

        for (String part : parts) {
            // Return the first part that doesn't start with "_temp_"
            if (!part.startsWith("_temp_")) {
                return part;
            }
        }

        // If all parts start with "_temp_", return the original string
        // This shouldn't happen in normal operation but provides a fallback
        return annotationName;
    }

    /**
     * Convenience overload that uses annotationName as the matching string.
     * This maintains backward compatibility with code that doesn't specify angles.
     *
     * @param projectsFolderPath  Root folder containing per-sample subfolders
     * @param sampleLabel         Sample identifier
     * @param imagingModeWithIndex Imaging modality with index
     * @param annotationName      Annotation name (also used as matching string)
     * @param qupathGUI           QuPath GUI instance
     * @param project             Target project
     * @param compression         Compression type
     * @param pixelSizeMicrons    Pixel size in micrometers
     * @param downsample          Downsample factor
     * @param modalityHandler     Handler for modality-specific file naming
     * @return Path to stitched file
     * @throws IOException If stitching fails
     */
    public static String stitchImagesAndUpdateProject(
            String projectsFolderPath,
            String sampleLabel,
            String imagingModeWithIndex,
            String annotationName,
            QuPathGUI qupathGUI,
            Project<BufferedImage> project,
            String compression,
            double pixelSizeMicrons,
            int downsample,
            ModalityHandler modalityHandler) throws IOException {

        logger.debug("Using convenience method - annotation name as matching string");

        // Call the new method with annotationName as the matching string and null metadata
        return stitchImagesAndUpdateProject(
                projectsFolderPath,
                sampleLabel,
                imagingModeWithIndex,
                annotationName,
                annotationName,  // Use annotation name as matching string for backward compatibility
                qupathGUI,
                project,
                compression,
                pixelSizeMicrons,
                downsample,
                modalityHandler,
                null  // No metadata for backward compatibility
        );
    }

    /**
     * Execute a system command with the given arguments and return the exit code.
     * This method is primarily used for launching microscope control scripts.
     *
     * <p>The process inherits the I/O streams from the parent process, allowing
     * real-time output to be visible in the console.
     *
     * @param args Command and arguments to execute
     * @return The exit code returned by the process (0 typically indicates success)
     * @throws IOException If the command cannot be started
     * @throws InterruptedException If the thread is interrupted while waiting
     */
    public static int execCommand(String... args) throws IOException, InterruptedException {
        logger.info("Executing command: {}", String.join(" ", args));

        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();  // Inherit I/O streams for real-time output

        logger.debug("Starting process...");
        Process process = pb.start();

        int exitCode = process.waitFor();
        logger.info("Process completed with exit code: {}", exitCode);

        return exitCode;
    }

    /**
     * Recursively delete a folder and all its contents.
     * Used for cleaning up temporary tile directories after stitching.
     *
     * <p>This method will log warnings for files that cannot be deleted but
     * will continue attempting to delete remaining files.
     *
     * @param folderPath Path to the folder to delete
     */
    public static void deleteTilesAndFolder(String folderPath) {
        logger.info("Deleting folder and contents: {}", folderPath);

        try {
            Path dir = Paths.get(folderPath);

            if (!Files.exists(dir)) {
                logger.warn("Folder does not exist: {}", folderPath);
                return;
            }

            // First, delete all files
            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            logger.trace("Deleted file: {}", p);
                        } catch(IOException ex) {
                            logger.error("Failed to delete file: {}", p, ex);
                        }
                    });

            // Then delete the directory itself
            Files.delete(dir);
            logger.info("Successfully deleted folder: {}", folderPath);

        } catch (IOException ex) {
            logger.error("Error deleting folder: {}", folderPath, ex);
        }
    }

    /**
     * Compress all tiles in a folder to a ZIP archive and move to a "Compressed tiles" directory.
     * The original folder is preserved until compression is verified successful.
     *
     * <p>The ZIP file will be created in a "Compressed tiles" subdirectory at the same level
     * as the original folder. The ZIP filename will match the original folder name.
     *
     * @param folderPath Path to the folder containing tiles to compress
     */
    public static void zipTilesAndMove(String folderPath) {
        logger.info("Compressing tiles in folder: {}", folderPath);

        try {
            Path dir = Paths.get(folderPath);

            if (!Files.exists(dir)) {
                logger.warn("Folder does not exist: {}", folderPath);
                return;
            }

            Path parent = dir.getParent();
            Path compressed = parent.resolve("Compressed tiles");

            // Create compressed directory if needed
            if (!Files.exists(compressed)) {
                logger.info("Creating compressed tiles directory: {}", compressed);
                Files.createDirectories(compressed);
            }

            // Create ZIP file
            Path zipPath = compressed.resolve(dir.getFileName() + ".zip");
            logger.info("Creating ZIP file: {}", zipPath);

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .forEach(p -> {
                            ZipEntry e = new ZipEntry(dir.relativize(p).toString());
                            try {
                                zos.putNextEntry(e);
                                Files.copy(p, zos);
                                zos.closeEntry();
                                logger.trace("Added to ZIP: {}", p.getFileName());
                            } catch(IOException ex) {
                                logger.error("Failed to zip file: {}", p, ex);
                            }
                        });
            }

            logger.info("Successfully created ZIP archive: {}", zipPath);

        } catch(IOException ex) {
            logger.error("Error zipping tiles", ex);
        }
    }

    /**
     * Modify a tissue detection Groovy script by updating pixel size and classifier path.
     * This allows the same script template to be used with different images and classifiers.
     *
     * <p>The method looks for specific function calls in the script:
     * <ul>
     *   <li>Lines starting with "setPixelSizeMicrons" - updated with the provided pixel size</li>
     *   <li>Lines starting with "createAnnotationsFromPixelClassifier" - updated with the classifier path</li>
     * </ul>
     *
     * @param groovyScriptPath Path to the Groovy script file to modify
     * @param pixelSize Pixel size value to insert (will be used for both X and Y)
     * @param jsonFilePathString Path to the JSON classifier file
     * @return The modified script content as a string
     * @throws IOException If the script file cannot be read or written
     */
    public static String modifyTissueDetectScript(
            String groovyScriptPath,
            String pixelSize,
            String jsonFilePathString) throws IOException {

        logger.info("Modifying tissue detection script: {}", groovyScriptPath);
        logger.debug("Setting pixel size: {} µm, classifier: {}", pixelSize, jsonFilePathString);

        List<String> lines = Files.readAllLines(Paths.get(groovyScriptPath), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size());

        int modificationsCount = 0;

        for (String ln : lines) {
            if (ln.startsWith("setPixelSizeMicrons")) {
                String modified = "setPixelSizeMicrons(" + pixelSize + ", " + pixelSize + ")";
                out.add(modified);
                logger.debug("Modified pixel size line: {}", modified);
                modificationsCount++;
            } else if (ln.startsWith("createAnnotationsFromPixelClassifier")) {
                String modified = ln.replaceFirst("\"[^\"]*\"", "\"" + jsonFilePathString + "\"");
                out.add(modified);
                logger.debug("Modified classifier path line: {}", modified);
                modificationsCount++;
            } else {
                out.add(ln);
            }
        }

        logger.info("Modified {} lines in script", modificationsCount);

        // Write the modified script back
        Files.write(Paths.get(groovyScriptPath), out, StandardCharsets.UTF_8);

        return String.join(System.lineSeparator(), out);
    }
}