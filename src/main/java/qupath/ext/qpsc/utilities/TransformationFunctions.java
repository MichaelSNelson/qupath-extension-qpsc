package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TransformationFunctions - Enhanced version with comprehensive coordinate transformations
 *
 * <p>Coordinate Systems:
 * <ul>
 *   <li><b>QuPath Full-Res:</b> Full resolution image pixels in QuPath (already flipped if applicable)</li>
 *   <li><b>Macro Original:</b> Low-resolution macro image pixels (unflipped)</li>
 *   <li><b>Macro Flipped:</b> Low-resolution macro image pixels (with flips applied)</li>
 *   <li><b>Stage:</b> Physical microscope position in micrometers</li>
 * </ul>
 *
 * <p>Transform Chain:
 * <pre>
 * QuPath Full-Res ←→ Macro (Flipped) ←→ Macro (Original) ←→ Stage
 * </pre>
 *
 * @author Mike Nelson
 * @since 0.3.0
 */
public class TransformationFunctions {
    private static final Logger logger = LoggerFactory.getLogger(TransformationFunctions.class);

    // ==================== DIRECT COORDINATE TRANSFORMATIONS ====================

    /**
     * Transforms QuPath full-resolution coordinates to stage micrometers.
     * This is the primary transform used during acquisition.
     *
     * @param qpFullResCoords QuPath full-resolution pixel coordinates [x, y]
     * @param fullResToStageTransform Transform mapping full-res pixels to stage micrometers
     * @return Stage coordinates in micrometers [x, y]
     */
    public static double[] transformQuPathFullResToStage(
            double[] qpFullResCoords,
            AffineTransform fullResToStageTransform) {

        if (qpFullResCoords == null || qpFullResCoords.length != 2) {
            throw new IllegalArgumentException("Coordinates must be [x, y]");
        }

        Point2D.Double src = new Point2D.Double(qpFullResCoords[0], qpFullResCoords[1]);
        Point2D.Double dst = new Point2D.Double();
        fullResToStageTransform.transform(src, dst);

        logger.debug("QuPath full-res ({}, {}) → Stage ({}, {})",
                qpFullResCoords[0], qpFullResCoords[1], dst.x, dst.y);

        return new double[]{dst.x, dst.y};
    }

    /**
     * Transforms macro image coordinates (original/unflipped) to stage micrometers.
     *
     * @param macroCoords Macro image pixel coordinates [x, y] (unflipped)
     * @param macroToStageTransform Transform mapping macro pixels to stage micrometers
     * @return Stage coordinates in micrometers [x, y]
     */
    public static double[] transformMacroOriginalToStage(
            double[] macroCoords,
            AffineTransform macroToStageTransform) {

        if (macroCoords == null || macroCoords.length != 2) {
            throw new IllegalArgumentException("Coordinates must be [x, y]");
        }

        Point2D.Double src = new Point2D.Double(macroCoords[0], macroCoords[1]);
        Point2D.Double dst = new Point2D.Double();
        macroToStageTransform.transform(src, dst);

        logger.debug("Macro original ({}, {}) → Stage ({}, {})",
                macroCoords[0], macroCoords[1], dst.x, dst.y);

        return new double[]{dst.x, dst.y};
    }

    /**
     * Transforms macro image coordinates (with flips applied) to stage micrometers.
     *
     * @param macroFlippedCoords Macro image pixel coordinates [x, y] (already flipped)
     * @param macroFlippedToStageTransform Transform mapping flipped macro pixels to stage
     * @return Stage coordinates in micrometers [x, y]
     */
    public static double[] transformMacroFlippedToStage(
            double[] macroFlippedCoords,
            AffineTransform macroFlippedToStageTransform) {

        return transformMacroOriginalToStage(macroFlippedCoords, macroFlippedToStageTransform);
    }

    /**
     * Transforms stage coordinates back to QuPath full-resolution pixels.
     *
     * @param stageCoords Stage coordinates in micrometers [x, y]
     * @param fullResToStageTransform Transform mapping full-res to stage (will be inverted)
     * @return QuPath full-resolution pixel coordinates [x, y]
     * @throws IllegalStateException if transform is not invertible
     */
    public static double[] transformStageToQuPathFullRes(
            double[] stageCoords,
            AffineTransform fullResToStageTransform) {

        try {
            AffineTransform inverse = fullResToStageTransform.createInverse();
            Point2D.Double src = new Point2D.Double(stageCoords[0], stageCoords[1]);
            Point2D.Double dst = new Point2D.Double();
            inverse.transform(src, dst);

            logger.debug("Stage ({}, {}) → QuPath full-res ({}, {})",
                    stageCoords[0], stageCoords[1], dst.x, dst.y);

            return new double[]{dst.x, dst.y};
        } catch (Exception e) {
            throw new IllegalStateException("Cannot invert transform", e);
        }
    }

    // ==================== TRANSFORM CALCULATION FUNCTIONS ====================

    /**
     * Creates a transform from macro image coordinates (original/unflipped) to full-resolution QuPath coordinates.
     * This handles the case where we know the green box location in the macro image.
     *
     * @param greenBox ROI of the green box in original macro coordinates
     * @param macroWidth Width of the macro image in pixels
     * @param macroHeight Height of the macro image in pixels
     * @param fullResWidth Width of the full-resolution image in pixels
     * @param fullResHeight Height of the full-resolution image in pixels
     * @param flipX Whether the macro image should be flipped horizontally
     * @param flipY Whether the macro image should be flipped vertically
     * @return Transform mapping macro (original) → full-res coordinates
     */
    public static AffineTransform calculateMacroOriginalToFullResTransform(
            ROI greenBox,
            int macroWidth,
            int macroHeight,
            int fullResWidth,
            int fullResHeight,
            boolean flipX,
            boolean flipY) {

        double gbX = greenBox.getBoundsX();
        double gbY = greenBox.getBoundsY();
        double gbWidth = greenBox.getBoundsWidth();
        double gbHeight = greenBox.getBoundsHeight();

        logger.info("Creating macro→full-res transform:");
        logger.info("  Green box (original): ({}, {}, {}, {})", gbX, gbY, gbWidth, gbHeight);
        logger.info("  Macro size: {} x {}", macroWidth, macroHeight);
        logger.info("  Full-res size: {} x {}", fullResWidth, fullResHeight);
        logger.info("  Flips: X={}, Y={}", flipX, flipY);

        // The green box in the original macro image represents the full resolution image area
        // We need to create a transform that maps points inside the green box (in original coordinates)
        // to the corresponding points in the full resolution image

        // The green box represents the full image area
        double scaleX = fullResWidth / gbWidth;
        double scaleY = fullResHeight / gbHeight;

        // Create the transform
        AffineTransform transform = new AffineTransform();

        // The transform should map:
        // - Green box top-left (gbX, gbY) → (0, 0) in full-res
        // - Green box top-right (gbX + gbWidth, gbY) → (fullResWidth, 0) in full-res
        // - Green box bottom-left (gbX, gbY + gbHeight) → (0, fullResHeight) in full-res
        // - Green box bottom-right (gbX + gbWidth, gbY + gbHeight) → (fullResWidth, fullResHeight) in full-res

        // This is achieved by:
        // 1. Translate by -gbX, -gbY to move green box origin to (0,0)
        // 2. Scale by fullResWidth/gbWidth, fullResHeight/gbHeight

        transform.translate(-gbX, -gbY);
        transform.scale(scaleX, scaleY);

        // No flips are applied here because this transform maps from ORIGINAL macro coordinates
        // The flips are already handled by the fact that the full-res image in QuPath
        // has already been flipped during import

        logger.info("  Transform: translate({}, {}), scale({}, {})", -gbX, -gbY, scaleX, scaleY);

        // Validate the transform by testing the corners
        logger.info("  Validation - green box corners map to:");
        double[][] testPoints = {
                {gbX, gbY},                           // top-left
                {gbX + gbWidth, gbY},                 // top-right
                {gbX, gbY + gbHeight},                // bottom-left
                {gbX + gbWidth, gbY + gbHeight}       // bottom-right
        };
        String[] labels = {"top-left", "top-right", "bottom-left", "bottom-right"};

        for (int i = 0; i < testPoints.length; i++) {
            Point2D src = new Point2D.Double(testPoints[i][0], testPoints[i][1]);
            Point2D dst = new Point2D.Double();
            transform.transform(src, dst);
            logger.info("    {} ({}, {}) → ({}, {})",
                    labels[i], src.getX(), src.getY(), dst.getX(), dst.getY());
        }

        return transform;
    }

    /**
     * Creates a transform from macro image coordinates (flipped) to full-resolution QuPath coordinates.
     *
     * @param greenBoxFlipped ROI of the green box in flipped macro coordinates
     * @param fullResWidth Width of the full-resolution image in pixels
     * @param fullResHeight Height of the full-resolution image in pixels
     * @return Transform mapping macro (flipped) → full-res coordinates
     */
    public static AffineTransform calculateMacroFlippedToFullResTransform(
            ROI greenBoxFlipped,
            int fullResWidth,
            int fullResHeight) {

        double gbX = greenBoxFlipped.getBoundsX();
        double gbY = greenBoxFlipped.getBoundsY();
        double gbWidth = greenBoxFlipped.getBoundsWidth();
        double gbHeight = greenBoxFlipped.getBoundsHeight();

        // Scale factors
        double scaleX = fullResWidth / gbWidth;
        double scaleY = fullResHeight / gbHeight;

        // Transform: translate by green box offset, then scale
        AffineTransform transform = new AffineTransform();
        transform.translate(-gbX, -gbY);
        transform.scale(scaleX, scaleY);

        logger.info("Macro (flipped) → Full-res transform: translate({}, {}), scale({}, {})",
                -gbX, -gbY, scaleX, scaleY);

        return transform;
    }

    /**
     * Calculates a complete transform from QuPath full-resolution pixels to stage micrometers.
     * This is typically created during the alignment workflow.
     *
     * @param alignmentPoints Map of full-res pixel coordinates to stage coordinates
     * @param imagePixelSizeMicrons Pixel size of the full-resolution image in micrometers
     * @param stageInvertedX Whether the stage X axis is inverted
     * @param stageInvertedY Whether the stage Y axis is inverted
     * @return Transform mapping full-res pixels → stage micrometers
     */
    public static AffineTransform calculateFullResToStageTransform(
            Map<Point2D, Point2D> alignmentPoints,
            double imagePixelSizeMicrons,
            boolean stageInvertedX,
            boolean stageInvertedY) {

        if (alignmentPoints.size() < 2) {
            // Simple scaling based on pixel size
            double scaleX = stageInvertedX ? -imagePixelSizeMicrons : imagePixelSizeMicrons;
            double scaleY = stageInvertedY ? -imagePixelSizeMicrons : imagePixelSizeMicrons;

            return AffineTransform.getScaleInstance(scaleX, scaleY);
        }

        // TODO: Implement least-squares fitting for multiple points
        // For now, use first two points to calculate transform
        Iterator<Map.Entry<Point2D, Point2D>> iter = alignmentPoints.entrySet().iterator();
        Map.Entry<Point2D, Point2D> p1 = iter.next();
        Map.Entry<Point2D, Point2D> p2 = iter.next();

        Point2D qp1 = p1.getKey();
        Point2D stage1 = p1.getValue();
        Point2D qp2 = p2.getKey();
        Point2D stage2 = p2.getValue();

        // Calculate scale and rotation
        double dx_qp = qp2.getX() - qp1.getX();
        double dy_qp = qp2.getY() - qp1.getY();
        double dx_stage = stage2.getX() - stage1.getX();
        double dy_stage = stage2.getY() - stage1.getY();

        double scaleX = dx_stage / dx_qp;
        double scaleY = dy_stage / dy_qp;

        if (stageInvertedX) scaleX = -scaleX;
        if (stageInvertedY) scaleY = -scaleY;

        // Create transform with scale
        AffineTransform transform = AffineTransform.getScaleInstance(scaleX, scaleY);

        // Add translation to match first point
        Point2D transformed = new Point2D.Double();
        transform.transform(qp1, transformed);
        double tx = stage1.getX() - transformed.getX();
        double ty = stage1.getY() - transformed.getY();
        transform.translate(tx / scaleX, ty / scaleY);

        return transform;
    }

    /**
     * Creates a general macro-to-stage transform given:
     * 1. Green box location in original macro image
     * 2. A full-res-to-stage transform (where full-res is already flipped)
     *
     * This is the key function for creating the general transform in MicroscopeAlignmentWorkflow.
     *
     * @param greenBox ROI of the green box in original macro coordinates
     * @param macroWidth Width of the macro image in pixels
     * @param macroHeight Height of the macro image in pixels
     * @param fullResWidth Width of the full-resolution image in pixels
     * @param fullResHeight Height of the full-resolution image in pixels
     * @param fullResToStage Transform from full-res (flipped) pixels to stage
     * @param flipX Whether the full-res image was flipped horizontally
     * @param flipY Whether the full-res image was flipped vertically
     * @return Transform mapping macro (original/unflipped) pixels → stage micrometers
     */
    public static AffineTransform createGeneralMacroToStageTransform(
            ROI greenBox,
            int macroWidth,
            int macroHeight,
            int fullResWidth,
            int fullResHeight,
            AffineTransform fullResToStage,
            boolean flipX,
            boolean flipY) {

        logger.info("Creating general macro→stage transform");
        logger.info("  Green box (original): ({}, {}, {}, {})",
                greenBox.getBoundsX(), greenBox.getBoundsY(),
                greenBox.getBoundsWidth(), greenBox.getBoundsHeight());
        logger.info("  Macro size: {} x {}", macroWidth, macroHeight);
        logger.info("  Full-res size: {} x {} (flipped: X={}, Y={})",
                fullResWidth, fullResHeight, flipX, flipY);

        double gbX = greenBox.getBoundsX();
        double gbY = greenBox.getBoundsY();
        double gbWidth = greenBox.getBoundsWidth();
        double gbHeight = greenBox.getBoundsHeight();

        // Calculate scale factors
        double scaleX = fullResWidth / gbWidth;
        double scaleY = fullResHeight / gbHeight;

        // Create the macro-to-fullres transform
        AffineTransform macroToFullRes = new AffineTransform();

        // IMPORTANT: Both the macro image AND the full-res image are already flipped in QuPath
        // So when flipX=true and flipY=true, the green box in the unflipped cropped macro
        // should map directly to the full-res image without additional flipping

        // The green box represents the full resolution image area
        // Simple mapping: green box → full-res image
        macroToFullRes.scale(scaleX, scaleY);
        macroToFullRes.translate(-gbX, -gbY);

        // Log for debugging
        logger.info("  Macro→FullRes transform: {}", macroToFullRes);

        // Test the green box corners to verify the transform
        logger.info("  Testing green box corner mappings:");
        double[][] corners = {
                {gbX, gbY},                             // top-left
                {gbX + gbWidth, gbY},                   // top-right
                {gbX, gbY + gbHeight},                  // bottom-left
                {gbX + gbWidth, gbY + gbHeight}         // bottom-right
        };
        String[] labels = {"top-left", "top-right", "bottom-left", "bottom-right"};

        for (int i = 0; i < corners.length; i++) {
            Point2D src = new Point2D.Double(corners[i][0], corners[i][1]);
            Point2D dst = new Point2D.Double();
            macroToFullRes.transform(src, dst);
            logger.info("    {} ({}, {}) → ({}, {})",
                    labels[i], src.getX(), src.getY(), dst.getX(), dst.getY());
        }

        // Now combine with full-res to stage
        AffineTransform macroToStage = new AffineTransform(fullResToStage);
        macroToStage.concatenate(macroToFullRes);

        logger.info("  FullRes→Stage transform: {}", fullResToStage);
        logger.info("  Combined Macro→Stage: {}", macroToStage);

        return macroToStage;
    }
    // ==================== VALIDATION FUNCTIONS ====================

    /**
     * Validates a transform by checking if it produces reasonable stage coordinates.
     *
     * @param transform The transform to validate
     * @param inputWidth Width of input coordinate space
     * @param inputHeight Height of input coordinate space
     * @param stageXMin Minimum valid stage X coordinate
     * @param stageXMax Maximum valid stage X coordinate
     * @param stageYMin Minimum valid stage Y coordinate
     * @param stageYMax Maximum valid stage Y coordinate
     * @return true if transform produces valid coordinates
     */
    public static boolean validateTransform(
            AffineTransform transform,
            double inputWidth,
            double inputHeight,
            double stageXMin,
            double stageXMax,
            double stageYMin,
            double stageYMax) {

        // Test key points
        double[][] testPoints = {
                {0, 0},                          // Top-left
                {inputWidth, 0},                 // Top-right
                {0, inputHeight},                // Bottom-left
                {inputWidth, inputHeight},       // Bottom-right
                {inputWidth/2, inputHeight/2}    // Center
        };

        boolean allValid = true;

        for (double[] point : testPoints) {
            Point2D.Double src = new Point2D.Double(point[0], point[1]);
            Point2D.Double dst = new Point2D.Double();
            transform.transform(src, dst);

            boolean xValid = dst.x >= stageXMin && dst.x <= stageXMax;
            boolean yValid = dst.y >= stageYMin && dst.y <= stageYMax;

            if (!xValid || !yValid) {
                logger.warn("Transform validation failed at ({}, {}) → ({}, {}) - X valid: {}, Y valid: {}",
                        point[0], point[1], dst.x, dst.y, xValid, yValid);
                allValid = false;
            }
        }

        return allValid;
    }

    /**
     * Validates transform with known ground truth points.
     *
     * @param transform Transform to validate
     * @param groundTruthPoints Map of input coordinates to expected output coordinates
     * @param tolerance Maximum acceptable error in micrometers
     * @return true if all points are within tolerance
     */
    public static boolean validateTransformWithGroundTruth(
            AffineTransform transform,
            Map<Point2D, Point2D> groundTruthPoints,
            double tolerance) {

        boolean allValid = true;

        for (Map.Entry<Point2D, Point2D> entry : groundTruthPoints.entrySet()) {
            Point2D input = entry.getKey();
            Point2D expected = entry.getValue();
            Point2D actual = new Point2D.Double();

            transform.transform(input, actual);

            double errorX = Math.abs(actual.getX() - expected.getX());
            double errorY = Math.abs(actual.getY() - expected.getY());
            double totalError = Math.sqrt(errorX * errorX + errorY * errorY);

            if (totalError > tolerance) {
                logger.warn("Ground truth validation failed:");
                logger.warn("  Input: ({}, {})", input.getX(), input.getY());
                logger.warn("  Expected: ({}, {})", expected.getX(), expected.getY());
                logger.warn("  Actual: ({}, {})", actual.getX(), actual.getY());
                logger.warn("  Error: {} µm (X: {}, Y: {})", totalError, errorX, errorY);
                allValid = false;
            } else {
                logger.debug("Ground truth point validated with error {} µm", totalError);
            }
        }

        return allValid;
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Applies flips to macro coordinates.
     * This function applies flips, converting from unflipped to flipped coordinates.
     *
     * @param coords Original coordinates [x, y]
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param flipX Apply horizontal flip
     * @param flipY Apply vertical flip
     * @return Flipped coordinates [x, y]
     */
    public static double[] applyFlipsToCoordinates(
            double[] coords,
            double imageWidth,
            double imageHeight,
            boolean flipX,
            boolean flipY) {

        double x = coords[0];
        double y = coords[1];

        if (flipX) {
            x = imageWidth - x;
        }
        if (flipY) {
            y = imageHeight - y;
        }

        return new double[]{x, y};
    }

    /**
     * Reverses flips applied to coordinates.
     * This function reverses flips, converting from flipped to unflipped coordinates.
     *
     * @param flippedCoords Flipped coordinates [x, y]
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param flipX Whether horizontal flip was applied
     * @param flipY Whether vertical flip was applied
     * @return Original unflipped coordinates [x, y]
     */
    public static double[] reverseFlipsOnCoordinates(
            double[] flippedCoords,
            double imageWidth,
            double imageHeight,
            boolean flipX,
            boolean flipY) {

        // Reversing a flip is the same as applying it again
        return applyFlipsToCoordinates(flippedCoords, imageWidth, imageHeight, flipX, flipY);
    }

    /**
     * Applies flips to an ROI (e.g., green box).
     *
     * @param roi Original ROI
     * @param imageWidth Image width
     * @param imageHeight Image height
     * @param flipX Apply horizontal flip
     * @param flipY Apply vertical flip
     * @return New coordinates [x, y, width, height] after flips
     */
    public static double[] applyFlipsToROI(
            ROI roi,
            double imageWidth,
            double imageHeight,
            boolean flipX,
            boolean flipY) {

        double x = roi.getBoundsX();
        double y = roi.getBoundsY();
        double width = roi.getBoundsWidth();
        double height = roi.getBoundsHeight();

        if (flipX) {
            x = imageWidth - x - width;
        }
        if (flipY) {
            y = imageHeight - y - height;
        }

        return new double[]{x, y, width, height};
    }

    /**
     * Logs detailed transform information for debugging.
     *
     * @param name Transform name/description
     * @param transform The transform to log
     */
    public static void logTransformDetails(String name, AffineTransform transform) {
        double[] matrix = new double[6];
        transform.getMatrix(matrix);

        logger.info("{} transform details:", name);
        logger.info("  Matrix: [m00={}, m10={}, m01={}, m11={}, m02={}, m12={}]",
                matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);
        logger.info("  Scale X: {}, Scale Y: {}", transform.getScaleX(), transform.getScaleY());
        logger.info("  Shear X: {}, Shear Y: {}", transform.getShearX(), transform.getShearY());
        logger.info("  Translate X: {}, Translate Y: {}", transform.getTranslateX(), transform.getTranslateY());
        logger.info("  Determinant: {}", transform.getDeterminant());
        logger.info("  Type: {}", describeTransformType(transform.getType()));
    }

    /**
     * Describes the type of affine transform in human-readable form.
     */
    private static String describeTransformType(int type) {
        List<String> types = new ArrayList<>();
        if ((type & AffineTransform.TYPE_IDENTITY) != 0) types.add("IDENTITY");
        if ((type & AffineTransform.TYPE_TRANSLATION) != 0) types.add("TRANSLATION");
        if ((type & AffineTransform.TYPE_UNIFORM_SCALE) != 0) types.add("UNIFORM_SCALE");
        if ((type & AffineTransform.TYPE_GENERAL_SCALE) != 0) types.add("GENERAL_SCALE");
        if ((type & AffineTransform.TYPE_FLIP) != 0) types.add("FLIP");
        if ((type & AffineTransform.TYPE_QUADRANT_ROTATION) != 0) types.add("QUADRANT_ROTATION");
        if ((type & AffineTransform.TYPE_GENERAL_ROTATION) != 0) types.add("GENERAL_ROTATION");
        if ((type & AffineTransform.TYPE_GENERAL_TRANSFORM) != 0) types.add("GENERAL_TRANSFORM");
        return String.join(" | ", types);
    }

    // ==================== LEGACY FUNCTIONS (kept for compatibility) ====================

    /**
     * Legacy function - transforms QuPath coordinates to microscope coordinates.
     * @deprecated Use {@link #transformQuPathFullResToStage} instead
     */
    @Deprecated
    public static double[] qpToMicroscopeCoordinates(double[] qpCoordinates, AffineTransform transform) {
        return transformQuPathFullResToStage(qpCoordinates, transform);
    }

    /**
     * Walks each subdirectory looking for TileConfiguration files and transforms them.
     */
    public static List<String> transformTileConfiguration(
            String parentDirPath,
            AffineTransform transform) throws IOException {

        File parent = new File(parentDirPath);
        List<String> modified = new ArrayList<>();

        logger.info("Looking for TileConfiguration files in: {}", parentDirPath);

        File[] subdirs = parent.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File sub : subdirs) {
                File inFile = new File(sub, "TileConfiguration.txt");
                if (inFile.exists()) {
                    logger.info("Found and transforming: {}", inFile.getAbsolutePath());
                    processTileConfigurationFile(inFile, transform);
                    modified.add(sub.getName());
                }
            }
        }

        logger.info("Modified directories: {}", modified);
        return modified;
    }

    private static void processTileConfigurationFile(
            File inFile,
            AffineTransform transform) throws IOException {

        // Backup original
        File backupFile = new File(inFile.getParent(), "TileConfiguration_QP.txt");
        Files.copy(inFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("Backed up original to: {}", backupFile.getAbsolutePath());

        List<String> lines = Files.readAllLines(inFile.toPath());
        List<String> out = new ArrayList<>(lines.size());
        Pattern p = Pattern.compile("(\\d+\\.tif); ; \\((.*?), (.*?)\\)");

        int transformedCount = 0;
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                String filename = m.group(1);
                double x = Double.parseDouble(m.group(2).trim());
                double y = Double.parseDouble(m.group(3).trim());

                double[] coords = transformQuPathFullResToStage(new double[]{x, y}, transform);

                String transformedLine = String.format("%s; ; (%.3f, %.3f)",
                        filename, coords[0], coords[1]);
                out.add(transformedLine);
                transformedCount++;
            } else {
                out.add(line);
            }
        }

        Files.write(inFile.toPath(), out);
        logger.info("Transformed {} tile coordinates", transformedCount);
    }

    /**
     * Reads min & max X,Y from a TileConfiguration file.
     */
    public static List<List<Double>> findImageBoundaries(File tileConfigFile) throws IOException {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        Pattern p = Pattern.compile("\\d+\\.tif; ; \\((.*?), (.*?)\\)");

        for (String line : Files.readAllLines(tileConfigFile.toPath())) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                double x = Double.parseDouble(m.group(1).trim());
                double y = Double.parseDouble(m.group(2).trim());
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
            double[] qpCoordinateArray,
            double[] stageCoordinateArray) {

        // Reset to pure scale
        scalingTransform.setTransform(
                scalingTransform.getScaleX(), 0,
                0, scalingTransform.getScaleY(),
                0, 0);

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
}