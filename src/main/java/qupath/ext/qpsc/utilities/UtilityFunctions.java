package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.StitchingImplementations;
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
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * General utility functions for stitching, Python calls, tiling, etc.
 */
public class UtilityFunctions {
    private static final Logger logger = LoggerFactory.getLogger(UtilityFunctions.class);

    // 1) Stitch, rename & add to project
    public static String stitchImagesAndUpdateProject(
            String projectsFolderPath,
            String sampleLabel,
            String imagingModeWithIndex,
            String annotationName,
            QuPathGUI qupathGUI,
            Project<BufferedImage> currentQuPathProject,
            String compression,
            double pixelSizeMicrons,
            int downsample) throws IOException {

        String stitchedFolder = projectsFolderPath + File.separator + sampleLabel + File.separator + "SlideImages";
        String tileFolder    = projectsFolderPath + File.separator + sampleLabel + File.separator + imagingModeWithIndex;

        logger.info("Calling stitchCore with {}", tileFolder);
        String outPath = StitchingImplementations.stitchCore(
                "Coordinates in TileConfiguration.txt file",
                tileFolder,
                stitchedFolder,
                compression,
                pixelSizeMicrons,
                downsample,
                annotationName);

        File orig = new File(outPath);
        String baseName = sampleLabel + "_" + imagingModeWithIndex +
                ("bounds".equals(annotationName) ? "" : "_" + annotationName) +
                ".ome.tif";
        File renamed = new File(orig.getParent(), baseName);
        if (orig.renameTo(renamed))
            outPath = renamed.getAbsolutePath();

        final String finalOut = outPath;
        Platform.runLater(() -> {
            logger.info("Updating QuPath project with {}", finalOut);
            // For inversion flags:
            boolean invertedX = QPEx.getQuPath()
                    .getPreferencePane()
                    .getPropertySheet()
                    .getItems()
                    .stream()
                    .filter(item -> "Inverted X stage".equals(item.getName()))
                    .findFirst()
                    .map(item -> (Boolean) item.getValue())
                    .orElse(false);
            boolean invertedY = QPEx.getQuPath()
                    .getPreferencePane()
                    .getPropertySheet()
                    .getItems()
                    .stream()
                    .filter(item -> "Inverted Y stage".equals(item.getName()))
                    .findFirst()
                    .map(item -> (Boolean) item.getValue())
                    .orElse(false);

            try {
                QPProjectFunctions.addImageToProject(
                        new File(finalOut), currentQuPathProject, invertedX, invertedY);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            List<ProjectImageEntry<BufferedImage>> images = currentQuPathProject.getImageList();
            images.stream()
                .filter(e -> new File(e.getImageName()).getName().equals(new File(finalOut).getName()))
                .findFirst()
                .ifPresent(qupathGUI::openImageEntry);

            qupathGUI.setProject(currentQuPathProject);
            qupathGUI.refreshProject();
        });

        return outPath;
    }

    // 2) Run a Python script and capture output
    public static List<String> runPythonCommand(
            String anacondaEnvPath,
            String pythonScriptPath,
            List<String> arguments,
            String script) {

        AtomicInteger tifCount = new AtomicInteger(0);
        AtomicReference<String> value1 = new AtomicReference<>();
        AtomicReference<String> value2 = new AtomicReference<>();
        AtomicBoolean errorOccurred = new AtomicBoolean(false);
        List<String> tifLines = new ArrayList<>();

        String argsJoined = (arguments != null)
                ? arguments.stream().map(arg -> "\"" + arg + "\"").collect(Collectors.joining(" "))
                : "";

        try {
            String pythonExe = new File(anacondaEnvPath, "python.exe").getCanonicalPath();
            String scriptFull = (script != null)
                    ? new File(new File(pythonScriptPath).getParent(), script).getCanonicalPath()
                    : pythonScriptPath;

            logger.info("Running Python command");
            String cmd = String.format("\"%s\" -u \"%s\" %s", pythonExe, scriptFull, argsJoined);
            Process process = Runtime.getRuntime().exec(cmd);

            boolean useProgress = (arguments == null || arguments.size() == 2);
            int totalTifs = useProgress ? MinorFunctions.countTifEntriesInTileConfig(arguments) : 0;
            if (useProgress && totalTifs>0)
                UIFunctions.showProgressBar(tifCount, totalTifs, process, 20000);

            BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            Thread tOut = new Thread(() -> outR.lines().forEach(line -> {
                if (line.contains("tiles done")) {
                    tifLines.add(line);
                    tifCount.incrementAndGet();
                } else if (line.startsWith("QuPath:")) {
                    logger.info(line.substring("QuPath:".length()));
                } else if (arguments==null || arguments.size()==2) {
                    String[] p = line.split("\\s+");
                    if (p.length>=2) {
                        value1.set(p[0]);
                        value2.set(p[1]);
                    }
                }
            }));

            Thread tErr = new Thread(() -> {
                try {
                    String ln;
                    while ((ln = errR.readLine()) != null) {
                        logger.error("Error: {}", ln);
                        if ("Exiting".equals(ln)) process.destroy();
                        if ("Out of config".equals(ln))
                            UIFunctions.notifyUserOfError(ln, "Coordinates out of bounds");
                    }
                } catch (IOException e) {
                    logger.error("Error reading stderr", e);
                }
            });

            tOut.start();
            tErr.start();
            tOut.join();
            tErr.join();
            process.waitFor();

            if (errorOccurred.get()) return null;
            if (arguments==null || arguments.size()==2)
                return Arrays.asList(value1.get(), value2.get());
            else
                return tifLines;

        } catch (Exception e) {
            logger.error("runPythonCommand failed", e);
        }
        return null;
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

    // 6) Tiling + config
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

        QP.mkdirs(modalityIndexFolder);
        boolean buffer = true;
        logger.info("performTiling coords: {}", boundingBoxCoordinates);

        if (boundingBoxCoordinates != null && !boundingBoxCoordinates.isEmpty()) {
            // … [ see your Groovy logic above; convert min/max, RectangleROI, createTileConfiguration ➔ below ]
            double x1 = boundingBoxCoordinates.get(0);
            double y1 = boundingBoxCoordinates.get(1);
            double x2 = boundingBoxCoordinates.get(2);
            double y2 = boundingBoxCoordinates.get(3);
            double startX = Math.min(x1, x2) - frameWidth/2.0;
            double startY = Math.min(y1, y2) - frameHeight/2.0;
            double width  = Math.abs(x2 - x1) + frameWidth;
            double height = Math.abs(y2 - y1) + frameHeight;

            ROI roi = ROIs.createRectangleROI(
                    startX, startY,
                    width, height,
                    ImagePlane.getDefaultPlane()
            );
            String configPath = QP.buildFilePath(modalityIndexFolder, "bounds", "TileConfiguration.txt");
            createTileConfiguration(
                    startX, startY, width, height,
                    frameWidth, frameHeight, overlapPercent,
                    configPath, roi, imagingModalityWithIndex, createTiles);
            logger.info("TileConfiguration written to {}", configPath);

        } else {
            // … your “existing annotation” branch (convert closures to loops) …
            String modality = imagingModalityWithIndex.replaceAll("(_\\d+)$", "");
            QP.getDetectionObjects().stream()
                    .filter(o -> o.getPathClass().toString().toLowerCase().contains(modality))
                    .forEach(o -> QP.removeObjects(Collections.singleton(o)));

            annotations.forEach(annotation -> {
                annotation.setName(String.format("%d_%d",
                        (int)annotation.getROI().getCentroidX(),
                        (int)annotation.getROI().getCentroidY()));
                annotation.setLocked(true);
            });

            for (PathObject annotation : annotations) {
                ROI aroi = annotation.getROI();
                double bX = aroi.getBoundsX();
                double bY = aroi.getBoundsY();
                double bW = aroi.getBoundsWidth();
                double bH = aroi.getBoundsHeight();
                String name = annotation.getName();

                PathObject tile = PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(bX - frameWidth/2, bY - frameHeight/2,
                                bW + frameWidth, bH + frameHeight,
                                ImagePlane.getDefaultPlane()),
                        QP.getPathClass(modality));
                tile.setName(name);
                QP.getCurrentHierarchy().addObject(tile);
            }
            QP.fireHierarchyUpdate();
        }
    }

    // 7) Core tile‐writing logic
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

        logger.info("Creating TileConfiguration at {}", tilePath);
        double xStep = frameWidth - overlapPercent/100.0 * frameWidth;
        double yStep = frameHeight - overlapPercent/100.0 * frameHeight;

        List<String> lines = new ArrayList<>();
        lines.add("dim = 2");

        int index=0, row=0;
        double y=bBoxY, x=bBoxX;
        List<PathObject> newTiles = new ArrayList<>();

        while (y < bBoxY + bBoxH) {
            while (x <= bBoxX + bBoxW && x >= bBoxX - bBoxW * overlapPercent/100.0) {
                ROI tileROI = ROIs.createRectangleROI(x, y, frameWidth, frameHeight, ImagePlane.getDefaultPlane());
                if (annotationROI==null || annotationROI.getGeometry().intersects(tileROI.getGeometry())) {
                    PathObject tile = PathObjects.createDetectionObject(tileROI, QP.getPathClass(imagingModality));
                    tile.setName(String.valueOf(index));
                    tile.getMeasurements().put("TileNumber", index);
                    newTiles.add(tile);
                    lines.add(String.format("%d.tif; ; (%.3f, %.3f)", index,
                            tileROI.getCentroidX(), tileROI.getCentroidY()));
                }
                x = (row % 2 == 0) ? x + xStep : x - xStep;
                index++;
            }
            row++;
            y += yStep;
            x = (row % 2 == 0) ? bBoxX : (bBoxX + bBoxW);
        }

        // Write to file
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(tilePath), StandardCharsets.UTF_8)) {
            for (String ln : lines) writer.write(ln + "\n");
        } catch (IOException e) {
            logger.error("Failed to write tile config", e);
        }

        if (createTiles) {
            QP.getCurrentHierarchy().addObjects(newTiles);
            QP.fireHierarchyUpdate();
        }
    }

    // 8) Move stage to selected tile
    public static void moveStageToSelectedTile() {
        List<PathObject> sel = QP.getSelectedObjects().stream()
                .filter(o -> o.isDetection() && o.getROI() instanceof RectangleROI)
                .toList();
        if (sel.size() != 1) {
            UIFunctions.showAlertDialog("There needs to be exactly one tile selected.");
            return;
        }
        RectangleROI roi = (RectangleROI) sel.getFirst().getROI();
        double[] coords = {roi.getBoundsX(), roi.getBoundsY()};

        // Find the first URI via Stream API (and handle the empty‐case if needed):
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

        // Read from preferences:
        String envPath = QPEx.getQuPath().getPreferencePane()
                .getPropertySheet().getItems().stream()
                .filter(i -> "Python Environment".equals(i.getName()))
                .findFirst()
                .map(i -> (String) i.getValue())
                .orElseThrow();

        String scriptPath = QPEx.getQuPath().getPreferencePane()
                .getPropertySheet().getItems().stream()
                .filter(i -> "PycroManager Path".equals(i.getName()))
                .findFirst()
                .map(i -> (String)i.getValue())
                .orElseThrow();

        runPythonCommand(envPath, scriptPath, target, "moveStageToCoordinates.py");
        logger.info("Moving stage to selected tile...");
    }
}
