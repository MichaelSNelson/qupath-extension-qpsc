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
            // Determine bounding box min/max for each axis
            double minX = Math.min(x1, x2);
            double maxX = Math.max(x1, x2);
            double minY = Math.min(y1, y2);
            double maxY = Math.max(y1, y2);

            // If axis is inverted, swap start/end
            if (invertXAxis) {
                double temp = minX;
                minX = maxX;
                maxX = temp;
            }
            if (invertYAxis) {
                double temp = minY;
                minY = maxY;
                maxY = temp;
            }

            // Calculate tiling grid origin and size
            double startX = minX - frameWidth / 2.0;
            double startY = minY - frameHeight / 2.0;
            double width  = Math.abs(maxX - minX) + frameWidth;
            double height = Math.abs(maxY - minY) + frameHeight;

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
                    configPath, roi, imagingModalityWithIndex, createTiles, invertXAxis, invertYAxis);

            logger.info(res.getString("tiling.perform.success"), configPath);

        } else {
            // --- Alternative: tiling from annotations ---
            performTilingFromAnnotations(
                    modalityIndexFolder,
                    imagingModalityWithIndex,
                    frameWidth,
                    frameHeight,
                    overlapPercent,
                    annotations,
                    createTiles,
                    invertXAxis,
                    invertYAxis,
                    /*buffer=*/true  // or false, as needed
            );
        }
    }
    /**
     * Handles annotation-based tiling:
     *  - For each annotation: creates its own folder and TileConfiguration.txt
     *  - Generates a grid of tiles that covers the annotation bounds (plus buffer if desired)
     *  - Only writes out tiles whose centroids are inside the annotation shape
     *  - Handles axis inversion and snaking
     */
    public static void performTilingFromAnnotations(
            String modalityIndexFolder,
            String imagingModalityWithIndex,
            double frameWidth,
            double frameHeight,
            double overlapPercent,
            Collection<PathObject> annotations,
            boolean createTiles,
            boolean invertXAxis,
            boolean invertYAxis,
            boolean buffer // if you want a half-frame buffer around annotation
    ) {
        Logger logger = LoggerFactory.getLogger("Tiling");

        // Remove index suffix from modality string for PathClass matching
        String modality = imagingModalityWithIndex.replaceAll("(_\\d+)$", "");

        // Remove any old tiles for this modality
        QP.getDetectionObjects().stream()
                .filter(o -> o.getPathClass().toString().toLowerCase().contains(modality))
                .forEach(o -> QP.removeObjects(Collections.singleton(o)));

        // Name and lock all annotations
        for (PathObject annotation : annotations) {
            annotation.setName(String.format("%d_%d",
                    (int) annotation.getROI().getCentroidX(),
                    (int) annotation.getROI().getCentroidY()));
            annotation.setLocked(true);
        }
        QP.fireHierarchyUpdate();

        // For each annotation, tile its region and write TileConfiguration.txt
        for (PathObject annotation : annotations) {
            ROI annotationROI = annotation.getROI();
            double bBoxX = annotationROI.getBoundsX();
            double bBoxY = annotationROI.getBoundsY();
            double bBoxW = annotationROI.getBoundsWidth();
            double bBoxH = annotationROI.getBoundsHeight();
            String annotationName = annotation.getName();

            // Optional: add buffer to bounds to ensure coverage
            if (buffer) {
                bBoxX -= frameWidth / 2.0;
                bBoxY -= frameHeight / 2.0;
                bBoxW += frameWidth;
                bBoxH += frameHeight;
            }

            // Each annotation gets its own folder and tile config
            String tileFolder = QP.buildFilePath(modalityIndexFolder, annotationName);
            QP.mkdirs(tileFolder);

            String tileConfigPath = QP.buildFilePath(tileFolder, "TileConfiguration.txt");

            // Call robust tiling function for this region
            createTileConfiguration(
                    bBoxX, bBoxY, bBoxW, bBoxH,
                    frameWidth, frameHeight, overlapPercent,
                    tileConfigPath, annotationROI, imagingModalityWithIndex, createTiles, invertXAxis, invertYAxis
            );

            logger.info("Tiled annotation {} into folder {}", annotationName, tileFolder);
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
     * @param bBoxX           Bounding box top-left X coordinate (in stage units, e.g. microns)
     * @param bBoxY           Bounding box top-left Y coordinate
     * @param bBoxW           Bounding box width (stage units)
     * @param bBoxH           Bounding box height (stage units)
     * @param frameWidth      Width of one image tile (microns)
     * @param frameHeight     Height of one image tile (microns)
     * @param overlapPercent  Overlap between adjacent tiles, as a percent of tile size (0-100)
     * @param tilePath        Full file path to write the TileConfiguration.txt (including filename)
     * @param annotationROI   Optional: restrict to a specific annotation (may be null for full box)
     * @param imagingModality Name of modality (used to set the PathClass for QuPath detections)
     * @param createTiles     If true, detection objects will be created and added to the QuPath project
     * @param invertXAxis     Whether to invert the X axis when generating tiles
     * @param invertYAxis     Whether to invert the Y axis when generating tiles
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
            boolean createTiles,
            boolean invertXAxis,
            boolean invertYAxis) {

        Logger logger = LoggerFactory.getLogger("Tiling");

        logger.info("Creating tile configuration:");
        logger.info("  Tile path: {}", tilePath);
        logger.info("  Bounding box: X={} Y={} W={} H={}", bBoxX, bBoxY, bBoxW, bBoxH);
        logger.info("  Frame size: W={} H={}", frameWidth, frameHeight);
        logger.info("  Overlap: {}%", overlapPercent);
        logger.info("  InvertX: {}, InvertY: {}", invertXAxis, invertYAxis);

        // Step sizes (distance between tile centers)
        double xStep = frameWidth  - overlapPercent / 100.0 * frameWidth;
        double yStep = frameHeight - overlapPercent / 100.0 * frameHeight;

        // Calculate grid start and end coordinates, depending on inversion
        double xStart = bBoxX;
        double xEnd   = bBoxX + bBoxW;
        double yStart = bBoxY;
        double yEnd   = bBoxY + bBoxH;

        if (invertXAxis) {
            double tmp = xStart;
            xStart = xEnd;
            xEnd = tmp;
            xStep = -xStep;
        }
        if (invertYAxis) {
            double tmp = yStart;
            yStart = yEnd;
            yEnd = tmp;
            yStep = -yStep;
        }

        logger.info("  xStart: {}, xEnd: {}, xStep: {}", xStart, xEnd, xStep);
        logger.info("  yStart: {}, yEnd: {}, yStep: {}", yStart, yEnd, yStep);

        // Prepare output
        List<String> lines = new ArrayList<>();
        lines.add("dim = 2");
        List<PathObject> newTiles = new ArrayList<>();
        int tileIndex = 0;

        // Calculate number of rows and columns to cover the region fully (guaranteed coverage)
        int nRows = (int)Math.ceil(Math.abs((yEnd - yStart)) / Math.abs(yStep));
        int nCols = (int)Math.ceil(Math.abs((xEnd - xStart)) / Math.abs(xStep));

        logger.info("  Calculated nRows: {}, nCols: {}", nRows, nCols);

        // Generate grid with serpentine snaking (each row alternates direction)
        for (int row = 0; row <= nRows; row++) {
            // Y coordinate for this row
            double y = yStart + row * yStep;

            // If overshot (e.g., last row past end), break
            if ((!invertYAxis && y > yEnd) || (invertYAxis && y < yEnd)) break;

            // Row snaking direction: even rows L->R (or R->L if inverted), odd rows reverse
            boolean snakeReverse = (row % 2 == 1);

            // Determine column indices for this row
            for (int col = 0; col <= nCols; col++) {
                int actualCol = snakeReverse ? (nCols - col) : col;
                double x = xStart + actualCol * xStep;

                if ((!invertXAxis && x > xEnd) || (invertXAxis && x < xEnd)) continue; // skip out of bounds

                // Create ROI for this tile
                ROI tileROI = ROIs.createRectangleROI(
                        x, y, frameWidth, frameHeight, ImagePlane.getDefaultPlane());

                // If restricting to annotation, skip tiles not overlapping
                if (annotationROI != null && !annotationROI.getGeometry().intersects(tileROI.getGeometry()))
                    continue;

                // Create PathObject for QuPath (if requested)
                if (createTiles) {
                    PathObject tile = PathObjects.createDetectionObject(tileROI, QP.getPathClass(imagingModality));
                    tile.setName(String.valueOf(tileIndex));
                    tile.getMeasurements().put("TileNumber", tileIndex);
                    newTiles.add(tile);
                }

                // Write entry for TileConfiguration.txt
                lines.add(String.format("%d.tif; ; (%.3f, %.3f)", tileIndex, tileROI.getCentroidX(), tileROI.getCentroidY()));

                tileIndex++;
            }
        }

        // --- Ensure parent directory exists, then write TileConfiguration.txt ---
        try {
            Files.createDirectories(Paths.get(tilePath).getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(tilePath), StandardCharsets.UTF_8)) {
                for (String ln : lines) writer.write(ln + "\n");
            }
            logger.info("Wrote tile configuration: {}", tilePath);
        } catch (IOException e) {
            logger.error("Failed to write tile configuration", e);
        }

        // Optionally add tile detection objects to the QuPath project
        if (createTiles && !newTiles.isEmpty()) {
            QP.getCurrentHierarchy().addObjects(newTiles);
            QP.fireHierarchyUpdate();
            logger.info("Added {} detection tiles to QuPath project", newTiles.size());
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
