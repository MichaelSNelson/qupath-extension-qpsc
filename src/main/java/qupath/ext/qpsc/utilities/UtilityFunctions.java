package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * UtilityFunctions
 *
 * <p>Higher level file and scripting utilities:
 *   - Build tile configurations (tiling an ROI, writing out TileConfiguration.txt).
 *   - Anything that doesn’t fit neatly into "micro controller " or "UI " or "model. "
 */

public class UtilityFunctions {
    private static final Logger logger = LoggerFactory.getLogger(UtilityFunctions.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    /**
     * Stitches all tiles under the given imaging mode folder into one or more OME TIFF files,
     * renames them to include sample, mode and annotation/angle information, then imports & opens
     * them in the given QuPath project (respecting X/Y inversion prefs).
     *
     * <p>When the matching string is "." (indicating batch processing of multiple subdirectories),
     * this method will find and rename all generated OME-TIFF files, preserving the subdirectory
     * name (e.g., angle) in the final filename.</p>
     *
     * @param projectsFolderPath  Root folder containing per sample subfolders.
     * @param sampleLabel         Subfolder name for this sample.
     * @param imagingModeWithIndex Subfolder name under sampleLabel, e.g. "PPM_10x_1".
     * @param annotationName      "bounds" or any annotation identifier.
     * @param matchingString      Pattern to match subdirectories (use "." for all).
     * @param qupathGUI           QuPathGUI instance (to open the new image).
     * @param project             QuPath Project to update.
     * @param compression         OME pyramid compression type, e.g. "DEFAULT".
     * @param pixelSizeMicrons    Pixel size (µm) for the final volume metadata.
     * @param downsample          Downsample factor for pyramid generation.
     * @return Absolute path to the last stitched OME TIFF processed.
     * @throws IOException If stitching or file I/O fails.
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
            int downsample) throws IOException {

        // Construct folder paths
        String tileFolder = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + imagingModeWithIndex + File.separator + annotationName;
        String stitchedFolder = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + "SlideImages";

        logger.info("Stitching tiles in '{}' → output in '{}'", tileFolder, stitchedFolder);
        logger.info("Using matching string: '{}'", matchingString);

        // Track files before stitching for batch operations
        Set<String> existingFiles = new HashSet<>();
        if (matchingString.equals(".")) {
            File outputDir = new File(stitchedFolder);
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

        // Run the core stitching routine with the explicit matching string
        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder,
                compression,
                pixelSizeMicrons,
                downsample,
                matchingString,
                1.0
        );

        String outPath = StitchingWorkflow.run(config);
        logger.info("Stitching returned path: {}", outPath);

        // Handle null return from stitching
        if (outPath == null) {
            throw new IOException("Stitching workflow returned null - no tiles were stitched");
        }

        final String lastProcessedPath;

        // Handle batch processing (matching string = ".")
        if (matchingString.equals(".")) {
            logger.info("Batch stitching detected, looking for all newly created files...");

            File outputDir = new File(stitchedFolder);
            File[] allOmeTiffs = outputDir.listFiles((dir, name) ->
                    name.endsWith(".ome.tif") && !existingFiles.contains(name));

            if (allOmeTiffs == null || allOmeTiffs.length == 0) {
                throw new IOException("No new OME-TIFF files found after batch stitching");
            }

            logger.info("Found {} new OME-TIFF files to rename and import", allOmeTiffs.length);
            String lastPath = null;

            // Process each newly created file
            for (File stitchedFile : allOmeTiffs) {
                String originalName = stitchedFile.getName();
                // Extract the angle/subdirectory name from the filename (e.g., "-5.0.ome.tif" -> "-5.0")
                String angleOrSubdir = originalName.replace(".ome.tif", "");

                // Create the full name with sample, mode, and angle
                String baseName = sampleLabel + "_" + imagingModeWithIndex + "_" + angleOrSubdir + ".ome.tif";
                File renamed = new File(stitchedFile.getParent(), baseName);

                if (stitchedFile.renameTo(renamed)) {
                    lastPath = renamed.getAbsolutePath();
                    logger.info("Renamed {} → {}", originalName, baseName);

                    // Import this file to the project
                    final String pathToImport = lastPath;

                    Platform.runLater(() -> {
                        try {
                            // Are acquired images in the correct orientation for stitching?
                            boolean invertedX = false;
                            boolean invertedY = true;

                            // Add to project with calculated flip values
                            QPProjectFunctions.addImageToProject(
                                    new File(pathToImport),
                                    project,
                                    invertedX,
                                    invertedY);

                            // Optionally open the first image
                            if (allOmeTiffs[0].getName().equals(originalName)) {
                                List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                                images.stream()
                                        .filter(e -> new File(e.getImageName()).getName()
                                                .equals(new File(pathToImport).getName()))
                                        .findFirst()
                                        .ifPresent(qupathGUI::openImageEntry);
                            }
                        } catch (IOException e) {
                            logger.error("Failed to import {}: {}", pathToImport, e.getMessage());
                        }
                    });
                } else {
                    logger.error("Failed to rename {} to {}", originalName, baseName);
                }
            }

            lastProcessedPath = lastPath;

            // Update project on FX thread
            Platform.runLater(() -> {
                qupathGUI.setProject(project);
                qupathGUI.refreshProject();

                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        res.getString("stitching.success.title"),
                        String.format("Successfully stitched and imported %d images", allOmeTiffs.length));
            });

        } else {
            // Single file processing (original behavior)

            // Defensive check for extension
            if (outPath.endsWith(".ome") && !outPath.endsWith(".ome.tif")) {
                logger.warn("Stitching returned .ome without .tif, appending .tif extension");
                outPath = outPath + ".tif";
            }

            // Rename according to sample/mode/annotation
            File orig = new File(outPath);
            String baseName;
            if (!matchingString.equals(annotationName)) {
                // This is angle-based stitching with specific angle
                baseName = sampleLabel + "_" + imagingModeWithIndex + "_" + matchingString + ".ome.tif";
            } else {
                // Standard stitching
                baseName = sampleLabel + "_" + imagingModeWithIndex
                        + (annotationName.equals("bounds") ? "" : "_" + annotationName)
                        + ".ome.tif";
            }

            File renamed = new File(orig.getParent(), baseName);
            if (orig.renameTo(renamed)) {
                outPath = renamed.getAbsolutePath();
                logger.info("Renamed stitched file → {}", baseName);
                logger.info("Full renamed path: {}", outPath);
            }

            lastProcessedPath = outPath;

            // Import & open on the FX thread
            Platform.runLater(() -> {
                ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

                // Read inversion prefs
                // WARNING - UNTESTED ON ENOUGH DIFFERENT SYSTEMS TO VALIDATE THAT THIS IS ALL CORRECT
                //TODO FIGURE OUT WHY AND POSSIBLY ADD EXTRA QPPREFERENCES
                boolean invertedX = false;
                boolean invertedY = true;

                try {
                    // Add to project (handles flipping)
                    QPProjectFunctions.addImageToProject(
                            new File(lastProcessedPath),
                            project,
                            invertedX,
                            invertedY);

                    // Find and open the newly added entry
                    List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                    images.stream()
                            .filter(e -> new File(e.getImageName()).getName()
                                    .equals(new File(lastProcessedPath).getName()))
                            .findFirst()
                            .ifPresent(qupathGUI::openImageEntry);

                    // Ensure project is active & refreshed
                    qupathGUI.setProject(project);
                    qupathGUI.refreshProject();

                    // Notify success
                    qupath.fx.dialogs.Dialogs.showInfoNotification(
                            res.getString("stitching.success.title"),
                            res.getString("stitching.success.message"));

                } catch (IOException e) {
                    UIFunctions.notifyUserOfError(
                            "Failed to import stitched image:\n" + e.getMessage(),
                            res.getString("stitching.error.title"));
                }
            });
        }

        return lastProcessedPath;
    }

    public static String stitchImagesAndUpdateProject(
            String projectsFolderPath,
            String sampleLabel,
            String imagingModeWithIndex,
            String annotationName,
            QuPathGUI qupathGUI,
            Project<BufferedImage> project,
            String compression,
            double pixelSizeMicrons,
            int downsample) throws IOException {
        // Call the new method with annotationName as the matching string
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
                downsample
        );
    }
    /**
     * Execute the microscope CLI with the given arguments and return the exit code.
     *
     * @param args everything
     * @return the exit-code returned by the process (0 = success)
     */
    public static int execCommand(String... args) throws IOException, InterruptedException {

            List<String> cmd = new ArrayList<>();

            cmd.addAll(List.of(args));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process process = pb.start();

        return process.waitFor();
    }


    // 3) Delete folder + its files
    public static void deleteTilesAndFolder(String folderPath) {
        try {
            Path dir = Paths.get(folderPath);
            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch(IOException ex){ logger.error("Delete file failed",ex); }
                    });
            Files.delete(dir);

        } catch (IOException ex) {
            logger.error("Error deleting folder: {}", folderPath, ex);
        }
    }

    // 4) Zip tiles & move
    public static void zipTilesAndMove(String folderPath) {
        try {
            Path dir  = Paths.get(folderPath);
            Path parent = dir.getParent();
            Path compressed = parent.resolve("Compressed tiles");
            if (!Files.exists(compressed))
                Files.createDirectories(compressed);

            Path zipPath = compressed.resolve(dir.getFileName() + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .forEach(p -> {
                            ZipEntry e = new ZipEntry(dir.relativize(p).toString());
                            try {
                                zos.putNextEntry(e);
                                Files.copy(p, zos);
                                zos.closeEntry();
                            } catch(IOException ex){
                                logger.error("Zipping failed: {}", p, ex);
                            }
                        });
            }
        } catch(IOException ex){
            logger.error("Error zipping tiles", ex);
        }
    }

    // 5) Modify a Groovy script in-place
    public static String modifyTissueDetectScript(
            String groovyScriptPath,
            String pixelSize,
            String jsonFilePathString) throws IOException {

        logger.info("Modifying script {}", groovyScriptPath);
        List<String> lines = Files.readAllLines(Paths.get(groovyScriptPath), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size());
        for (String ln : lines) {
            if (ln.startsWith("setPixelSizeMicrons"))
                out.add("setPixelSizeMicrons(" + pixelSize + ", " + pixelSize + ")");
            else if (ln.startsWith("createAnnotationsFromPixelClassifier"))
                out.add(ln.replaceFirst("\"[^\"]*\"", "\"" + jsonFilePathString + "\""));
            else
                out.add(ln);
        }
        Files.write(Paths.get(groovyScriptPath), out, StandardCharsets.UTF_8);
        return String.join(System.lineSeparator(), out);
    }

}
