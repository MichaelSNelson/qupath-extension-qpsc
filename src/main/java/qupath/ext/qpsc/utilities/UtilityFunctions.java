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
     * Stitches all tiles under the given imaging mode folder into a single OME TIFF,
     * renames it to include sample, mode and annotationName, then imports & opens it
     * in the given QuPath project (respecting X/Y inversion prefs).
     *
     * @param projectsFolderPath  Root folder containing per sample subfolders.
     * @param sampleLabel         Subfolder name for this sample.
     * @param imagingModeWithIndex Subfolder name under sampleLabel, e.g. "4x_bf_1".
     * @param annotationName      "bounds " or any annotation identifier.
     * @param qupathGUI           QuPathGUI instance (to open the new image).
     * @param project             QuPath Project to update.
     * @param compression         OME pyramid compression type, e.g. "DEFAULT".
     * @param pixelSizeMicrons    Pixel size (µm) for the final volume metadata.
     * @param downsample          Downsample factor for pyramid generation.
     * @return Absolute path to the stitched OME TIFF.
     * @throws IOException If stitching or file I/O fails.
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
            int downsample) throws IOException {

        // 1) Construct folder paths
        String tileFolder    = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + imagingModeWithIndex;
        String stitchedFolder = projectsFolderPath + File.separator
                + sampleLabel + File.separator
                + "SlideImages";

        logger.info("Stitching tiles in '{}' → output in '{}'", tileFolder, stitchedFolder);

        // 2) Run the core stitching routine
        // The annotationName here is used as the "matching string" filter (like before).
        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file", // stitchingType
                tileFolder,          // folderPath (tile input root)
                stitchedFolder,      // outputPath (where OME-TIFF is saved)
                compression,         // compressionType
                pixelSizeMicrons,    // pixel size (microns)
                downsample,          // downsample factor
                annotationName,      // matching string for subfolder/file filtering
                1.0                  // zSpacingMicrons (can be set as needed)
        );
        String outPath = StitchingWorkflow.run(config);
        //TODO clean up if not needed
        logger.info("Stitching returned path: {}", outPath); // ADD THIS
        // Defensive check for extension
        if (outPath.endsWith(".ome") && !outPath.endsWith(".ome.tif")) {
            logger.warn("Stitching returned .ome without .tif, appending .tif extension");
            outPath = outPath + ".tif";
        }

        // 3) Rename according to sample/mode/annotation
        File orig = new File(outPath);
        String baseName = sampleLabel + "_" + imagingModeWithIndex
                + (annotationName.equals("bounds") ? "" : "_" + annotationName)
                + ".ome.tif";
        File renamed = new File(orig.getParent(), baseName);
        if (orig.renameTo(renamed)) {
            outPath = renamed.getAbsolutePath();
            logger.info("Renamed stitched file → {}", baseName);
            logger.info("Full renamed path: {}", outPath); // ADD THIS
        }

        final String finalOut = outPath;  // for use in the lambda below

        // 4) Import & open on the FX thread
        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            // 4a) Read inversion prefs
            boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
            boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

            try {
                // 4b) Add to project (handles flipping)
                QPProjectFunctions.addImageToProject(
                        new File(finalOut),
                        project,
                        invertedX,
                        invertedY);

                // 4c) Find and open the newly added entry
                List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                images.stream()
                        .filter(e -> new File(e.getImageName()).getName()
                                .equals(new File(finalOut).getName()))
                        .findFirst()
                        .ifPresent(qupathGUI::openImageEntry);

                // 4d) Ensure project is active & refreshed
                qupathGUI.setProject(project);
                qupathGUI.refreshProject();

                // 4e) Notify success
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        res.getString("stitching.success.title"),
                        res.getString("stitching.success.message"));

            } catch (IOException e) {
                UIFunctions.notifyUserOfError(
                        "Failed to import stitched image:\n" + e.getMessage(),
                        res.getString("stitching.error.title"));
            }
        });

        return outPath;
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



    /**
     * Executes a command and returns all lines written to stdout.
     *
     * @param cmd the executable + its arguments
     * @return list of stdout lines (never {@code null})
     */
    public static List<String> execCommandAndCapture(String... cmd)
            throws IOException, InterruptedException {

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r =
                     new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null)
                out.add(line);
        }
        int exit = p.waitFor();
        if (exit != 0)
            throw new IOException("Command " + String.join(" ", cmd) +
                    " exited with " + exit);
        return out;
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
