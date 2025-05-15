package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.StitchingImplementations;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        String outPath = StitchingImplementations.stitchCore(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder,
                compression,
                pixelSizeMicrons,
                downsample,
                annotationName);

        // 3) Rename according to sample/mode/annotation
        File orig = new File(outPath);
        String baseName = sampleLabel + "_" + imagingModeWithIndex
                + (annotationName.equals("bounds") ? "" : "_" + annotationName)
                + ".ome.tif";
        File renamed = new File(orig.getParent(), baseName);
        if (orig.renameTo(renamed)) {
            outPath = renamed.getAbsolutePath();
            logger.info("Renamed stitched file → {}", baseName);
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

    /**
     * Computes a tile grid (for stage scanning) from a bounding box or annotation,
     * generates a TileConfiguration.txt file, and (optionally) creates QuPath detection tiles.
     * <p>
     * The function supports two modes:
     *   - Bounding box: Compute a regular grid of tile locations based on the camera frame size, overlap, and bounding box coordinates.
     *   - Annotations: Use provided annotation objects as regions to tile and add detection objects for each.
     * <p>
     * In both cases, the resulting tile configuration is written to a standard Bio-Formats/QuPath file for downstream stitching.
     *
     * @param modalityIndexFolder        Folder for the current imaging mode (e.g., "BF_10x_2").
     * @param imagingModalityWithIndex   Name of imaging modality + index (e.g., "BF_10x_2").
     * @param frameWidth                 Width of a single camera frame, in microns.
     * @param frameHeight                Height of a single camera frame, in microns.
     * @param overlapPercent             Desired overlap between tiles (0-100, percent of frame size).
     * @param boundingBoxCoordinates     List of four numbers [x1, y1, x2, y2] for bounding box (can be null/empty to use annotations).
     * @param createTiles                If true, create and add QuPath detection objects for each tile.
     * @param annotations                Collection of QuPath annotation PathObjects (used if bounding box not provided).
     * @param invertYAxis                Whether to invert Y axis (for display or stage correction).
     * @param invertXAxis                Whether to invert X axis (for display or stage correction).
     */
    public static void performTilingAndSaveConfiguration(
            String modalityIndexFolder,
            String imagingModalityWithIndex,
            double frameWidth,
            double frameHeight,
            double overlapPercent,
            List<Double> boundingBoxCoordinates,
            boolean createTiles,
            Collection<PathObject> annotations,
            boolean invertYAxis,
            boolean invertXAxis) {

        // --- Ensure modality folder exists (parent) ---
        QP.mkdirs(modalityIndexFolder);
        logger.info(res.getString("tiling.perform.start"), boundingBoxCoordinates);

        // --- Main branch: bounding box tiling ---
        if (boundingBoxCoordinates != null && !boundingBoxCoordinates.isEmpty()) {
            // Parse input: [x1, y1, x2, y2]
            double x1 = boundingBoxCoordinates.get(0);
            double y1 = boundingBoxCoordinates.get(1);
            double x2 = boundingBoxCoordinates.get(2);
            double y2 = boundingBoxCoordinates.get(3);

            // The grid covers the area defined by (x1, y1) and (x2, y2)
            double startX = Math.min(x1, x2) - frameWidth / 2.0;
            double startY = Math.min(y1, y2) - frameHeight / 2.0;
            double width  = Math.abs(x2 - x1) + frameWidth;
            double height = Math.abs(y2 - y1) + frameHeight;

            // Create a rectangular ROI covering the tiling area
            ROI roi = ROIs.createRectangleROI(
                    startX, startY, width, height, ImagePlane.getDefaultPlane()
            );

            // Output file location for tile configuration
            String configPath = QP.buildFilePath(modalityIndexFolder, "bounds", "TileConfiguration.txt");

            // Write tile grid and (optionally) detection objects
            createTileConfiguration(
                    startX, startY, width, height,
                    frameWidth, frameHeight, overlapPercent,
                    configPath, roi, imagingModalityWithIndex, createTiles);

            logger.info(res.getString("tiling.perform.success"), configPath);

        } else {
            // --- Alternative: tiling from annotations ---
            // Remove existing detection objects for this modality
            String modality = imagingModalityWithIndex.replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass().toString().toLowerCase().contains(modality))
                    .forEach(o -> QP.removeObjects(Collections.singleton(o)));

            // Name and lock each annotation
            annotations.forEach(annotation -> {
                annotation.setName(String.format("%d_%d",
                        (int) annotation.getROI().getCentroidX(),
                        (int) annotation.getROI().getCentroidY()));
                annotation.setLocked(true);
            });

            // For each annotation, create and add a detection tile that covers it, padded by frame size
            for (PathObject annotation : annotations) {
                ROI aroi = annotation.getROI();
                double bX = aroi.getBoundsX();
                double bY = aroi.getBoundsY();
                double bW = aroi.getBoundsWidth();
                double bH = aroi.getBoundsHeight();
                String name = annotation.getName();

                PathObject tile = PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(
                                bX - frameWidth / 2, bY - frameHeight / 2,
                                bW + frameWidth, bH + frameHeight, ImagePlane.getDefaultPlane()
                        ),
                        QP.getPathClass(modality)
                );
                tile.setName(name);
                QP.getCurrentHierarchy().addObject(tile);
            }
            QP.fireHierarchyUpdate();
        }
    }

    /**
     * Creates a QuPath TileConfiguration.txt for stage tiling, based on bounding box and tile size.
     * <p>
     * Given the bounding box area and camera/tile dimensions, this function determines all tile positions,
     * generates a standard QuPath/Bio-Formats TileConfiguration.txt, and (optionally) creates detection
     * objects in the current QuPath project for visualization.
     *
     * <p>
     * Ensures the parent directory for the tile config file exists before attempting to write.
     *
     * @param bBoxX             Bounding box top-left X coordinate (in stage units, e.g. microns)
     * @param bBoxY             Bounding box top-left Y coordinate
     * @param bBoxW             Bounding box width (stage units)
     * @param bBoxH             Bounding box height (stage units)
     * @param frameWidth        Width of one image tile (microns)
     * @param frameHeight       Height of one image tile (microns)
     * @param overlapPercent    Overlap between adjacent tiles, as a percent of tile size (0-100)
     * @param tilePath          Full file path to write the TileConfiguration.txt (including filename)
     * @param annotationROI     Optional: restrict to a specific annotation (may be null for full box)
     * @param imagingModality   Name of modality (used to set the PathClass for QuPath detections)
     * @param createTiles       If true, detection objects will be created and added to the QuPath project
     */
    public static void createTileConfiguration(
            double bBoxX,
            double bBoxY,
            double bBoxW,
            double bBoxH,
            double frameWidth,
            double frameHeight,
            double overlapPercent,
            String tilePath,
            ROI annotationROI,
            String imagingModality,
            boolean createTiles) {

        logger.info(res.getString("tileConfig.create.start"), tilePath);

        // Compute the stage step size between tile centers (considering overlap)
        double xStep = frameWidth  - overlapPercent / 100.0 * frameWidth;
        double yStep = frameHeight - overlapPercent / 100.0 * frameHeight;

        // Lines to write to the TileConfiguration.txt
        List<String> lines = new ArrayList<>();
        lines.add("dim = 2");

        int index = 0, row = 0;
        double y = bBoxY, x = bBoxX;
        List<PathObject> newTiles = new ArrayList<>();

        // --- Loop through stage positions and generate tile positions ---
        while (y < bBoxY + bBoxH) {
            while (x <= bBoxX + bBoxW && x >= bBoxX - bBoxW * overlapPercent / 100.0) {
                ROI tileROI = ROIs.createRectangleROI(x, y, frameWidth, frameHeight, ImagePlane.getDefaultPlane());
                // Only add tile if inside annotation, or if no annotation specified
                if (annotationROI == null || annotationROI.getGeometry().intersects(tileROI.getGeometry())) {
                    PathObject tile = PathObjects.createDetectionObject(tileROI, QP.getPathClass(imagingModality));
                    tile.setName(String.valueOf(index));
                    tile.getMeasurements().put("TileNumber", index);
                    newTiles.add(tile);
                    lines.add(String.format("%d.tif; ; (%.3f, %.3f)", index, tileROI.getCentroidX(), tileROI.getCentroidY()));
                }
                // Serpentine tiling (row-wise, zig-zag)
                x = (row % 2 == 0) ? x + xStep : x - xStep;
                index++;
            }
            row++;
            y += yStep;
            x = (row % 2 == 0) ? bBoxX : (bBoxX + bBoxW);
        }

        // --- Ensure parent directories exist before writing the file ---
        try {
            Files.createDirectories(Paths.get(tilePath).getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(tilePath), StandardCharsets.UTF_8)) {
                for (String ln : lines) writer.write(ln + "\n");
            }
            logger.info(res.getString("tileConfig.write.success"), tilePath);
        } catch (IOException e) {
            logger.error(res.getString("tileConfig.write.fail"), e);
        }

        // Optionally add tile detection objects to the QuPath project
        if (createTiles) {
            QP.getCurrentHierarchy().addObjects(newTiles);
            QP.fireHierarchyUpdate();
        }
    }


    // 8) Move stage to selected tile
    public static void moveStageToSelectedTile(PathObject sel) throws IOException, InterruptedException {

        RectangleROI roi = (RectangleROI) sel.getROI();
        double[] coords = {roi.getBoundsX(), roi.getBoundsY()};

        // Find the first URI via Stream API (and handle the empty case if needed):
        String uri = QP.getCurrentServer()
                .getURIs()
                .stream()
                .findFirst()
                .map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("No URIs available"));

        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8).replace("file:/", "");
        double[][] stageBounds = MinorFunctions.readTileExtremesFromFile(decoded);

        assert stageBounds != null;
        double scaleX = (stageBounds[1][0] - stageBounds[0][0]) / QP.getCurrentServer().getMetadata().getWidth();
        double scaleY = (stageBounds[1][1] - stageBounds[0][1]) / QP.getCurrentServer().getMetadata().getHeight();

        List<String> target = Arrays.asList(
                String.valueOf(stageBounds[0][0] + coords[0]*scaleX),
                String.valueOf(stageBounds[0][1] + coords[1]*scaleY)
        );

        execCommand(String.valueOf(target), "moveStageToCoordinates.py");
        logger.info("Moving stage to selected tile...");
    }


}
