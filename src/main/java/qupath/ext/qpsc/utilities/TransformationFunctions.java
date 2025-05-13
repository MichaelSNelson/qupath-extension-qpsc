package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TransformationFunctions
 *
 * <p>Geometry utilities for coordinate transforms and tile configs:
 *   - AffineTransform setup and composition (scaling + translation).
 *   - Convert between QuPath pixel coords and stage real world coords.
 *   - Read/transform TileConfiguration.txt files, compute image boundaries.
 */

public class TransformationFunctions {
    private static final Logger logger = LoggerFactory.getLogger(TransformationFunctions.class);

    /**
     * Transforms a 2D coordinate pair from QuPath image space into microscope stage coordinates
     * using the provided AffineTransform.
     *
     * @param qpCoordinates  a List<Double> of exactly two elements, [x, y], in QuPath pixel units
     * @param transform       an AffineTransform mapping from QuPath space to stage coordinates
     * @return a double[2] array containing the transformed coordinates [x′, y′] in stage units
     * @throws IllegalArgumentException if {@code qpCoordinates} is null or does not contain exactly two values
     * @throws NullPointerException     if {@code transform} is null
     */
    public static double[] qpToMicroscopeCoordinates(double[] qpCoordinates, AffineTransform transform) {
        if (qpCoordinates == null || qpCoordinates.length != 2) {
            throw new IllegalArgumentException("qpCoordinates must be a List of exactly two Doubles");
        }
        // Source point in QuPath pixel space
        Point2D.Double src = new Point2D.Double(qpCoordinates[0], qpCoordinates[1]);
        // Destination point will hold the transformed (stage) coordinates
        Point2D.Double dst = new Point2D.Double();
        transform.transform(src, dst);
        // Return as a primitive array for ease of use downstream
        return new double[]{ dst.x, dst.y };
    }

    /**
     * Walks each subdirectory of parentDirPath looking for TileConfiguration_QP.txt,
     * applies transform and optional offset, writes TileConfiguration.txt, returns modified dirs.
     */
    public static List<String> transformTileConfiguration(
            String parentDirPath,
            AffineTransform transform,
            double [] offset) throws IOException {
        File parent = new File(parentDirPath);
        List<String> modified = new ArrayList<>();
        if (!parent.isDirectory()) return modified;
        File[] subdirs = parent.listFiles(File::isDirectory);
        if (subdirs == null) return modified;
        for (File sub : subdirs) {
            File inFile = new File(sub, "TileConfiguration_QP.txt");
            if (inFile.exists()) {
                processTileConfigurationFile(inFile, transform, offset);
                modified.add(sub.getName());
            }
        }
        return modified;
    }

    private static void processTileConfigurationFile(
            File inFile,
            AffineTransform transform,
            double [] offset) throws IOException {
        List<String> lines = Files.readAllLines(inFile.toPath());
        List<String> out = new ArrayList<>(lines.size());
        Pattern p = Pattern.compile("\\d+\\.tif; ; \\(.*?\\),\\s*(.*?)\\)");
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                double[] coords = qpToMicroscopeCoordinates(new double[]{x, y}, transform);
                coords = applyOffset(coords, offset, true);
                out.add(line.replaceFirst("\\(.*?\\)", String.format("(%.3f, %.3f)", coords[0], coords[1])));
            } else {
                out.add(line);
            }
        }
        File outFile = new File(inFile.getParent(), "TileConfiguration.txt");
        Files.write(outFile.toPath(), out);
    }

    /**
     * Reads min & max X,Y from a TileConfiguration file.
     */
    public static List<List<Double>> findImageBoundaries(File tileConfigFile) throws IOException {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        Pattern p = Pattern.compile("\\d+\\.tif; ; \\(.*?\\),\\s*(.*?)\\)");
        for (String line : Files.readAllLines(tileConfigFile.toPath())) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1));
                double y = Double.parseDouble(m.group(2));
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return Arrays.asList(Arrays.asList(minX, minY), Arrays.asList(maxX, maxY));
    }

    /**
     * Adds translation to a scaling-only AffineTransform based on a single control point.
     */
    public static AffineTransform addTranslationToScaledAffine(
            AffineTransform scalingTransform,
            double [] qpCoordinateArray,
            double [] stageCoordinateArray,
            double [] offset) {
        // Reset to pure scale
        scalingTransform.setTransform(
                scalingTransform.getScaleX(), 0,
                0, scalingTransform.getScaleY(),
                0, 0);
//        double[] qp = qpList.stream().mapToDouble(Double::doubleValue).toArray();
//        double[] st = stageList.stream().mapToDouble(Double::doubleValue).toArray();
        Point2D.Double scaled = new Point2D.Double();
        scalingTransform.transform(new Point2D.Double(qpCoordinateArray[0], qpCoordinateArray[1]), scaled);
        double tx = (stageCoordinateArray[0] - scaled.x) / scalingTransform.getScaleX();
        double ty = (stageCoordinateArray[1] - scaled.y) / scalingTransform.getScaleY();
        AffineTransform out = new AffineTransform(scalingTransform);
        out.translate(tx, ty);
        return out;
    }

    /**
     * Picks the tile whose centroid is top-most, then closest to the median X among those.
     */
    public static PathObject getTopCenterTile(Collection<PathObject> detections) {
        List<PathObject> sorted = detections.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(o -> o.getROI().getCentroidY()))
                .toList();
        if (sorted.isEmpty()) return null;
        double minY = sorted.get(0).getROI().getCentroidY();
        List<PathObject> top = sorted.stream()
                .filter(o -> o.getROI().getCentroidY() == minY)
                .toList();
        List<Double> xs = top.stream()
                .map(o -> o.getROI().getCentroidX())
                .sorted()
                .toList();
        double medianX = xs.get(xs.size() / 2);
        return top.stream()
                .min(Comparator.comparingDouble(o -> Math.abs(o.getROI().getCentroidX() - medianX)))
                .orElse(null);
    }

    /**
     * Picks the tile whose centroid is left-most, then closest to the median Y among those.
     */
    public static PathObject getLeftCenterTile(Collection<PathObject> detections) {
        List<PathObject> sorted = detections.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(o -> o.getROI().getCentroidX()))
                .toList();
        if (sorted.isEmpty()) return null;
        double minX = sorted.get(0).getROI().getCentroidX();
        List<PathObject> left = sorted.stream()
                .filter(o -> o.getROI().getCentroidX() == minX)
                .toList();
        List<Double> ys = left.stream()
                .map(o -> o.getROI().getCentroidY())
                .sorted()
                .toList();
        double medianY = ys.get(ys.size() / 2);
        return left.stream()
                .min(Comparator.comparingDouble(o -> Math.abs(o.getROI().getCentroidY() - medianY)))
                .orElse(null);
    }

    /**
     * Initializes a scaling transform from pixel size and flip prefs.
     * In MVC, actual alignment GUI belongs in the controller/view.
     */
    public static AffineTransform setupAffineTransformation(
            double pixelSizeSourceImage,
            boolean invertedX,
            boolean invertedY) {
        double sx = invertedX ? -pixelSizeSourceImage : pixelSizeSourceImage;
        double sy = invertedY ? -pixelSizeSourceImage : pixelSizeSourceImage;
        AffineTransform at = new AffineTransform();
        at.scale(sx, sy);
        return at;
    }

    /**
     * Applies an offset to a vector of coordinates, either for sending to the stage
     * (adding the offset) or for retrieving from the stage (subtracting the offset).
     *
     * <p>For example, if your QuPath derived stage coordinates are [x, y] and you need
     * to compensate by an offset [dx, dy], then calling
     * {@code applyOffset(List.of(x, y), List.of(dx, dy), true)} will yield
     * {@code new double[]{ x + dx, y + dy }}.</p>
     *
     * @param input       a List of Double representing the original coordinates;
     *                    must be non-null and of length N.
     * @param offset      a List of Double of the same length N, representing the
     *                    values to add (or subtract) for each coordinate.
     * @param sendToStage if true, the returned array is {@code input[i] + offset[i]};
     *                    if false, it is {@code input[i] - offset[i]}.
     * @return a new {@code double[]} of length N containing the adjusted coordinates.
     * @throws IllegalArgumentException if {@code input.size() != offset.size()}.
     */
    public static double[] applyOffset(
            double[] input,
            double[] offset,
            boolean sendToStage) {

        if (input == null || offset == null || input.length != offset.length) {
            throw new IllegalArgumentException("Input array and offset list must be non-null and the same length");
        }

        double[] result = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            double off = offset[i];
            result[i] = sendToStage
                    ? input[i] + off
                    : input[i] - off;
        }
        return result;
    }

    /**
     * Offsets coordinates for sending to/retrieving from stage.
     *
     * @param input        A List<Double> of length 2 ([x, y]).
     * @param offset       A List<Double> of length 2 ([dx, dy]).
     * @param sendToStage  If true, adds the offset; if false, subtracts it.
     * @return             A new List<Double> with the offset applied.
     * @throws IllegalArgumentException if either list isn’t size 2.
     */
    public static List<Double> applyOffset(
            List<Double> input,
            List<Double> offset,
            boolean sendToStage) {

        if (input.size() != 2 || offset.size() != 2)
            throw new IllegalArgumentException("Both input and offset must have exactly two elements");

        double x = input.get(0);
        double y = input.get(1);
        double dx = offset.get(0);
        double dy = offset.get(1);

        if (sendToStage) {
            return List.of(x + dx, y + dy);
        } else {
            return List.of(x - dx, y - dy);
        }
    }

    /**
     * Placeholder for computing a transform from a previous low-res to new high-res image.
     */
    public static AffineTransform calculateNewImageTransform(
            AffineTransform originalTransform,
            List<Double> upperLeftStageCoord,
            List<Double> offset,
            double pixelSize) {
        // Implementation depends on future requirements
        return new AffineTransform();
    }
}
