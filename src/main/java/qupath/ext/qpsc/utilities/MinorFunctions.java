package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.scene.control.Alert;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.scripting.QPEx;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MinorFunctions
 *
 * <p>Miscellaneous small utilities:
 *   - Converting List<?> → List<Double> or double[] arrays.
 *   - Regex helpers, string parsing, filename manipulation.
 *   - Anything too small to justify its own class.
 */

public class MinorFunctions {
    private static final Logger logger = LoggerFactory.getLogger(MinorFunctions.class);

    /** Converts any list of objects into a List<Double>, skipping unparseable entries. */
    public static List<Double> convertListToDouble(List<?> list) {
        List<Double> result = new ArrayList<>();
        for (Object o : list) {
            try {
                result.add(Double.parseDouble(o.toString()));
            } catch (NumberFormatException e) {
                logger.warn("Skipping unconvertible element '{}'", o);
            }
        }
        return result;
    }
    public static double[] convertListToPrimitiveArray(List<?> list) {
        List<Double> doubles = convertListToDouble(list);
        double[] out = new double[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            out[i] = doubles.get(i);
        }
        return out;
    }

    /**
     * @return true if running on Windows, false otherwise
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
    /**
     * Reads a JSON file via Gson into a nested Map.
     * @throws IOException if file access fails
     */
    public static Map<String,Object> readJsonFileToMapWithGson(String filePath) throws IOException {
        String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String,Object>>(){}.getType();
        return gson.fromJson(content, type);
    }

    /**
     * Counts ".tif" lines in either TileConfiguration_QP.txt or TileConfiguration.txt
     * under the directory built by joining the `arguments` list.
     */
    public static int countTifEntriesInTileConfig(List<String> arguments) {
        String base = String.join(File.separator, arguments);
        Path pathQP  = Paths.get(base, "TileConfiguration_QP.txt");
        Path pathStd = Paths.get(base, "TileConfiguration.txt");
        Path file    = Files.exists(pathQP) ? pathQP : pathStd;

        if (! Files.exists(file)) {
            logger.warn("TileConfiguration not found at {} or {}",
                    pathQP, pathStd);
            return 0;
        }

        int count = 0, total = 0;
        try {
            for (String line : Files.readAllLines(file)) {
                total++;
                if (line.contains(".tif")) count++;
            }
            logger.info("Found {} .tif entries out of {} lines in {}", count, total, file);
        } catch (IOException e) {
            logger.error("Failed reading {}", file, e);
        }
        return count;
    }

    /** Returns a Map of two script-related paths based on the Groovy script’s folder. */
    public static Map<String,String> calculateScriptPaths(String groovyScriptPath) {
        Path dir = Paths.get(groovyScriptPath);
        String jsonPath   = dir.resolve("tissue.json").toString().replace("\\","/");
        //TODO remove this?
        String exportPath = dir.resolve("save4xMacroTiling.groovy").toString().replace("\\","/");

        Map<String,String> map = new HashMap<>();
        map.put("jsonTissueClassfierPathString", jsonPath);
        map.put("exportScriptPathString",       exportPath);
        return map;
    }

    /**
     * Appends _1, _2, ... to the original folder name until it’s unique.
     * Returns only the new folder name (not full path).
     */
    public static String getUniqueFolderName(String originalFolderPath) {
        Path path = Paths.get(originalFolderPath);
        String base = path.getFileName().toString();
        Path parent = path.getParent();

        int idx = 1;
        Path candidate = parent.resolve(base + "_" + idx);
        while (Files.exists(candidate)) {
            idx++;
            candidate = parent.resolve(base + "_" + idx);
        }
        return candidate.getFileName().toString();
    }

    /**
     * Extracts a Windows-style path out of a URI like "file:/C:/...TIF".
     * Returns null if no match.
     */
    public static String extractFilePath(String serverPath) {
        Pattern p = Pattern.compile("file:/(.*?\\.TIF)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(serverPath);
        if (m.find()) {
            return m.group(1).replaceFirst("^/", "").replaceAll("%20", " ");
        }
        return null;
    }

    /**
     * Writes the two extreme coordinate pairs (minX,minY / maxX,maxY) into
     * a text file named "<image>_StageCoordinates.txt".
     */
    //TODO re-include this in the exported metadata, or validate that the python side does this
    public static void writeTileExtremesToFile(
            String imagePath, List<List<Double>> extremes) {
        String out = imagePath.replaceAll("\\.[^\\.]+$", "") + "_StageCoordinates.txt";
        logger.info("Writing tile extremes to: {}", out);

        try (BufferedWriter w = Files.newBufferedWriter(
                Paths.get(out), StandardCharsets.UTF_8)) {
            List<Double> min = extremes.get(0);
            List<Double> max = extremes.get(1);
            w.write(String.format("%f, %f%n", min.get(0), min.get(1)));
            w.write(String.format("%f, %f%n", max.get(0), max.get(1)));
        } catch (IOException e) {
            logger.error("Failed writing extremes file", e);
        }
    }

    /**
     * Reads the previously written _StageCoordinates.txt file for an image and parses out
     * the minimum and maximum stage coordinate extremes.
     * <p>
     * The file is expected to live alongside the image (same base name with "_StageCoordinates.txt")
     * and contain exactly two lines, each with two comma separated doubles:
     * <ul>
     *   <li>Line 1: &lt;minX&gt;, &lt;minY&gt;</li>
     *   <li>Line 2: &lt;maxX&gt;, &lt;maxY&gt;</li>
     * </ul>
     * On success, a 2×2 array is returned where:
     * <pre>
     *   result[0][0] == minX
     *   result[0][1] == minY
     *   result[1][0] == maxX
     *   result[1][1] == maxY
     * </pre>
     * If the file is missing, cannot be parsed, or does not contain exactly two lines of two numbers,
     * the method logs an error and returns {@code null}.
     *
     * @param imagePath  the full path to the original image file (including its extension);
     *                   the method will replace the extension with _StageCoordinates.txt to locate the extremes file
     * @return a {@code double[2][2]} of {minX,minY} and {maxX,maxY}, or {@code null} on any I/O or format error
     */
    public static double[][] readTileExtremesFromFile(String imagePath) {
        String inPath = imagePath.replaceAll("\\.[^\\.]+$", "") + "_StageCoordinates.txt";
        Path file    = Paths.get(inPath);
        if (!Files.exists(file)) {
            logger.error("Coordinate file missing: {}", inPath);
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() != 2) {
                logger.error("Expected 2 lines, got {} in {}", lines.size(), inPath);
                return null;
            }

            double[][] result = new double[2][2];
            for (int i = 0; i < 2; i++) {
                String[] parts = lines.get(i).split(",");
                result[i][0] = Double.parseDouble(parts[0].trim());
                result[i][1] = Double.parseDouble(parts[1].trim());
            }

            logger.info("Read extremes: [[{}, {}], [{}, {}]]",
                    result[0][0], result[0][1],
                    result[1][0], result[1][1]);
            return result;

        } catch (IOException | NumberFormatException e) {
            logger.error("Error reading extremes from {}", inPath, e);
            return null;
        }
    }
    /**
     * Retrieves the current stage to QuPath offset.
     * <p>
     * In the future this will load from your YAML config or QuPath properties.
     * For now, it simply returns half the camera frame size in microns using stub values.
     *
     * @return a two element list [offsetX, offsetY] in microns
     */
    public static double[] getCurrentOffset() {
        // TODO: replace with values read from YAML (e.g. via MicroscopeConfigManager)
        final double acquisitionPixelSizeMicrons = 0.2201;  // stub: µm per pixel
        final int cameraWidthPixels = 1392;                 // stub: camera resolution
        final int cameraHeightPixels = 1040;

        double offsetX = 0.5 * cameraWidthPixels * acquisitionPixelSizeMicrons;
        double offsetY = 0.5 * cameraHeightPixels * acquisitionPixelSizeMicrons;
        return new double[]{ offsetX, offsetY };
    }

    public static String firstLines(String text, int maxLines) {
        String[] lines = text.split("\r?\n");
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("... (truncated, see log for full details)");
        return sb.toString();
    }
}


