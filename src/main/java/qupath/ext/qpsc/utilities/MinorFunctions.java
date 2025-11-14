package qupath.ext.qpsc.utilities;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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

    /**
     * @return true if running on Windows, false otherwise
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
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

    /**
     * Robustly counts expected tiles from TileConfiguration file with retry logic.
     * This method addresses timing issues where files may not be immediately available.
     *
     * @param arguments Path components to build the tile directory path
     * @param maxRetries Maximum number of retry attempts
     * @param retryDelayMs Delay between retries in milliseconds
     * @return Number of .tif entries found, or 0 if file not found after retries
     */
    public static int countExpectedTilesWithRetry(List<String> arguments, int maxRetries, long retryDelayMs) {
        int count = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            count = countTifEntriesInTileConfig(arguments);

            if (count > 0) {
                if (attempt > 0) {
                    logger.info("Successfully counted {} tiles after {} retries", count, attempt);
                }
                return count;
            }

            if (attempt < maxRetries) {
                logger.debug("Tile count is 0, retrying in {}ms (attempt {}/{})",
                            retryDelayMs, attempt + 1, maxRetries);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Retry interrupted", e);
                    break;
                }
            }
        }

        // If we get here, all retries failed
        String base = String.join(File.separator, arguments);
        logger.error("Failed to count tiles after {} retries. Directory: {}", maxRetries, base);
        logger.error("This may indicate missing TileConfiguration files or timing issues");
        return 0;
    }

    /** Returns a Map of two script-related paths based on the Groovy script’s folder. */
    public static Map<String,String> calculateScriptPaths(String groovyScriptPath) {
        Path dir = Paths.get(groovyScriptPath).getParent();
        String jsonPath   = dir.resolve("tissue.json").toString().replace("\\","/");
        Map<String,String> map = new HashMap<>();
        map.put("jsonTissueClassfierPathString", jsonPath);
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
     * Extracts a file path from various QuPath server path formats.
     * Handles multiple formats including:
     * - Standard file URLs: "file:/C:/path/to/file.ext"
     * - QuPath URIs with parameters: "file:/path[--series, 0]"
     * - Already extracted paths: "C:/path/to/file.ext"
     *
     * @param serverPath The server path from QuPath ImageData
     * @return The extracted file system path, or null if extraction fails
     */
    public static String extractFilePath(String serverPath) {
        if (serverPath == null || serverPath.isEmpty()) {
            logger.warn("Server path is null or empty");
            return null;
        }

        logger.debug("Attempting to extract file path from: {}", serverPath);

        // First, check if it's already a valid file path
        File directFile = new File(serverPath);
        if (directFile.exists() && directFile.isFile()) {
            logger.debug("Server path is already a valid file path");
            return serverPath;
        }

        // Try to parse as URI
        try {
            // Handle QuPath URIs that might have parameters
            String cleanPath = serverPath;
            int bracketIndex = cleanPath.indexOf('[');
            if (bracketIndex != -1) {
                cleanPath = cleanPath.substring(0, bracketIndex).trim();
            }

            URI uri = new URI(cleanPath);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri);
                if (file.exists()) {
                    logger.debug("Extracted path via URI parsing: {}", file.getAbsolutePath());
                    return file.getAbsolutePath();
                }

                // Try alternative parsing for Windows paths
                String path = uri.getPath();
                if (path != null) {
                    // Remove leading slash for Windows paths
                    if (path.matches("^/[A-Za-z]:.*")) {
                        path = path.substring(1);
                    }
                    path = path.replace("%20", " ");

                    File altFile = new File(path);
                    if (altFile.exists()) {
                        logger.debug("Extracted path via alternative parsing: {}", altFile.getAbsolutePath());
                        return altFile.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("URI parsing failed: {}", e.getMessage());
        }

        // Fallback to regex pattern matching
        // Pattern to match file:/ followed by a path
        Pattern filePattern = Pattern.compile("file:/+(.+?)(?:\\[|$)");
        Matcher matcher = filePattern.matcher(serverPath);

        if (matcher.find()) {
            String path = matcher.group(1);
            // Clean up the path
            path = path.replace("%20", " ");

            // Handle Windows paths that might have lost their initial slash
            if (path.matches("^[A-Za-z]:.*")) {
                // It's already a Windows path
            } else if (path.matches("^/[A-Za-z]:.*")) {
                // Remove leading slash for Windows
                path = path.substring(1);
            }

            File file = new File(path);
            if (file.exists()) {
                logger.debug("Extracted path via regex: {}", file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        logger.warn("Could not extract valid file path from: {}", serverPath);
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



    /**
     * Loads a YAML file directly into a Map structure.
     * This bypasses the MicroscopeConfigManager singleton to avoid caching issues.
     *
     * @param yamlPath Path to the YAML file
     * @return Map containing the YAML data, or empty map if loading fails
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadYamlFile(String yamlPath) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(yamlPath)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return (Map<String, Object>) loaded;
            }
        } catch (Exception e) {
            logger.error("Error loading YAML file: {}", yamlPath, e);
        }
        return new HashMap<>();
    }

    /**
     * Gets a nested value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value (e.g., "macro", "pixel_size_um")
     * @return The value at the specified path, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static Object getYamlValue(Map<String, Object> yamlData, String... keys) {
        Object current = yamlData;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Gets a Double value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Double value, or null if not found or not a number
     */
    public static Double getYamlDouble(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Gets a Boolean value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Boolean value, or null if not found
     */
    public static Boolean getYamlBoolean(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    /**
     * Gets an Integer value from a YAML map structure.
     *
     * @param yamlData The loaded YAML data
     * @param keys Path to the value
     * @return The Integer value, or null if not found or not a number
     */
    public static Integer getYamlInteger(Map<String, Object> yamlData, String... keys) {
        Object value = getYamlValue(yamlData, keys);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Retrieves all unique classifications from annotations in the current image
     * @param imageData The current ImageData
     * @return Set of unique PathClass objects found in the hierarchy
     */
    public static Set<PathClass> getExistingClassifications(ImageData<?> imageData) {
        Set<PathClass> classifications = new HashSet<>();

        if (imageData == null) {
            logger.warn("No image data available");
            return classifications;
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        // Iterate through all objects and collect unique classifications
        for (PathObject pathObject : hierarchy.getAnnotationObjects()) {
            PathClass pathClass = pathObject.getPathClass();

            // Only add valid, non-ignored classes
            if (PathClassTools.isValidClass(pathClass) &&
                    !PathClassTools.isIgnoredClass(pathClass)) {
                classifications.add(pathClass);
                logger.debug("Found classification: {}", pathClass);
            }
        }

        logger.info("Found {} unique classifications in hierarchy", classifications.size());
        return classifications;
    }
    /**
     * Saves the acquisition command to a text file for debugging and reproducibility.
     * The file is saved in the acquisition folder with a timestamp.
     *
     * @param command The full acquisition command string that will be sent to the server
     * @param projectsFolder The base projects folder path
     * @param sampleName The sample name
     * @param scanType The scan type/modality with index
     * @param regionName The region/annotation name
     * @return The path to the saved command file, or null if saving failed
     */
    public static String saveAcquisitionCommand(String command, String projectsFolder,
                                                String sampleName, String scanType,
                                                String regionName) {
        try {
            // Build the acquisition folder path
            Path acquisitionDir = Paths.get(projectsFolder, sampleName, scanType, regionName);

            // Create directory if it doesn't exist
            Files.createDirectories(acquisitionDir);

            // Create filename with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = String.format("acquisition_command_%s.txt", timestamp);
            Path commandFile = acquisitionDir.resolve(filename);

            // Format the command for readability (each flag on new line)
            String formattedCommand = formatAcquisitionCommand(command);

            // Write the command to file
            try (BufferedWriter writer = Files.newBufferedWriter(commandFile, StandardCharsets.UTF_8)) {
                writer.write("# QuPath Acquisition Command");
                writer.newLine();
                writer.write("# Generated: " + new Date());
                writer.newLine();
                writer.write("# Sample: " + sampleName);
                writer.newLine();
                writer.write("# Scan Type: " + scanType);
                writer.newLine();
                writer.write("# Region: " + regionName);
                writer.newLine();
                writer.write("# =================================");
                writer.newLine();
                writer.newLine();
                writer.write(formattedCommand);
            }

            logger.info("Saved acquisition command to: {}", commandFile);
            return commandFile.toString();

        } catch (IOException e) {
            logger.error("Failed to save acquisition command", e);
            return null;
        }
    }

    /**
     * Formats an acquisition command string for better readability.
     * Puts each flag and its value on a separate line.
     *
     * @param command The raw command string
     * @return The formatted command string
     */
    private static String formatAcquisitionCommand(String command) {
        // Split by spaces, but respect quoted strings
        List<String> parts = new ArrayList<>();
        Pattern pattern = Pattern.compile("([^\"]\\S*|\".+?\")\\s*");
        Matcher matcher = pattern.matcher(command);

        while (matcher.find()) {
            parts.add(matcher.group(1));
        }

        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);

            // Remove quotes for display
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            }

            // If this is a flag (starts with --), add newline before it (except first)
            if (part.startsWith("--") && formatted.length() > 0) {
                formatted.append(" \\\n    ");
            }

            formatted.append(part);

            // Add space after if not last element and next isn't a flag
            if (i < parts.size() - 1 && !parts.get(i + 1).startsWith("--")) {
                formatted.append(" ");
            }
        }

        return formatted.toString();
    }
}


