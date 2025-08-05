package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects green bounding boxes in macro images that indicate the scanned region.
 * These boxes are commonly added by slide scanners to show which area was digitized.
 *
 * @since 0.3.1
 */
public class GreenBoxDetector {
    private static final Logger logger = LoggerFactory.getLogger(GreenBoxDetector.class);

    /**
     * Parameters for green box detection.
     */
    public static class DetectionParams {
        public double greenThreshold;     // How much green vs other channels
        public double saturationMin;      // Minimum saturation for green
        public double brightnessMin;      // Minimum brightness
        public double brightnessMax;      // Maximum brightness
        public int minBoxWidth;           // Minimum box width in pixels
        public int minBoxHeight;          // Minimum box height in pixels
        public int edgeThickness;        // Expected thickness of box edges
        public boolean requireRectangle = true; // Only accept rectangular shapes

        /**
         * Default constructor - loads from persistent preferences or uses defaults.
         */
        public DetectionParams() {
            try {
                this.greenThreshold = PersistentPreferences.getGreenThreshold();
                this.saturationMin = PersistentPreferences.getGreenSaturationMin();
                this.brightnessMin = PersistentPreferences.getGreenBrightnessMin();
                this.brightnessMax = PersistentPreferences.getGreenBrightnessMax();
                this.edgeThickness = PersistentPreferences.getGreenEdgeThickness();
                this.minBoxWidth = PersistentPreferences.getGreenMinBoxWidth();
                this.minBoxHeight = PersistentPreferences.getGreenMinBoxHeight();
            } catch (Exception e) {
                // If preferences fail to load, use defaults
                loadDefaults();
            }
        }

        /**
         * Constructor with all parameters specified.
         */
        public DetectionParams(double greenThreshold, double saturationMin,
                               double brightnessMin, double brightnessMax,
                               int minBoxWidth, int minBoxHeight, int edgeThickness) {
            this.greenThreshold = greenThreshold;
            this.saturationMin = saturationMin;
            this.brightnessMin = brightnessMin;
            this.brightnessMax = brightnessMax;
            this.minBoxWidth = minBoxWidth;
            this.minBoxHeight = minBoxHeight;
            this.edgeThickness = edgeThickness;
        }

        /**
         * Loads default values.
         */
        private void loadDefaults() {
            this.greenThreshold = 0.4;
            this.saturationMin = 0.3;
            this.brightnessMin = 0.3;
            this.brightnessMax = 0.9;
            this.minBoxWidth = 100;
            this.minBoxHeight = 100;
            this.edgeThickness = 3;
        }

        /**
         * Saves current parameters to persistent preferences.
         */
        public void saveToPreferences() {
            PersistentPreferences.setGreenThreshold(greenThreshold);
            PersistentPreferences.setGreenSaturationMin(saturationMin);
            PersistentPreferences.setGreenBrightnessMin(brightnessMin);
            PersistentPreferences.setGreenBrightnessMax(brightnessMax);
            PersistentPreferences.setGreenEdgeThickness(edgeThickness);
            PersistentPreferences.setGreenMinBoxWidth(minBoxWidth);
            PersistentPreferences.setGreenMinBoxHeight(minBoxHeight);
        }
    }

    /**
     * Result of green box detection.
     */
    public static class DetectionResult {
        private final ROI detectedBox;
        private final BufferedImage debugImage;
        private final double confidence;

        public DetectionResult(ROI detectedBox, BufferedImage debugImage, double confidence) {
            this.detectedBox = detectedBox;
            this.debugImage = debugImage;
            this.confidence = confidence;
        }

        public ROI getDetectedBox() { return detectedBox; }
        public BufferedImage getDebugImage() { return debugImage; }
        public double getConfidence() { return confidence; }
    }

    /**
     * Detects green bounding box in the macro image that indicates the scanned region.
     * These boxes are commonly added by slide scanners to show which area was digitized.
     *
     * @param macroImage The macro image to analyze
     * @param params Detection parameters controlling color thresholds and size constraints
     * @return Detection result containing the found box ROI, debug visualization, and confidence score,
     *         or null if no suitable green box is found
     */
    public static DetectionResult detectGreenBox(BufferedImage macroImage, DetectionParams params) {
        logger.info("Starting green box detection on {}x{} image",
                macroImage.getWidth(), macroImage.getHeight());

        // Create binary mask of green pixels
        BufferedImage greenMask = createGreenMask(macroImage, params);

        // Find connected components that could be box edges
        List<Rectangle> edges = findBoxEdges(greenMask, params);

        // Try to form a complete box from edges
        ROI detectedBox = findCompleteBox(edges, macroImage.getWidth(), macroImage.getHeight(), params);

        if (detectedBox != null) {

            // TEMPORARY EDGE ADJUSTMENT - REMOVE AFTER TESTING
            // 0.0 = outer edge (default), 0.5 = middle, 1.0 = inner edge
            double EDGE_ADJUSTMENT_FACTOR = 0;
            if (EDGE_ADJUSTMENT_FACTOR > 0 && params.edgeThickness > 0) {
                double shrink = params.edgeThickness * EDGE_ADJUSTMENT_FACTOR;
                detectedBox = ROIs.createRectangleROI(
                        detectedBox.getBoundsX() + shrink,
                        detectedBox.getBoundsY() + shrink,
                        detectedBox.getBoundsWidth() - 2 * shrink,
                        detectedBox.getBoundsHeight() - 2 * shrink,
                        detectedBox.getImagePlane()
                );
                logger.info("Adjusted green box bounds inward by {} pixels (factor {})",
                        shrink, EDGE_ADJUSTMENT_FACTOR);
            }
            // END TEMPORARY ADJUSTMENT
            // Calculate confidence based on how well it matches expected characteristics
            double confidence = calculateConfidence(detectedBox, greenMask, params);

            // Create debug image showing detection
            BufferedImage debugImage = createDebugImage(macroImage, greenMask, detectedBox);

            logger.info("Green box detected at ({}, {}, {}, {}) with confidence {}",
                    detectedBox.getBoundsX(), detectedBox.getBoundsY(),
                    detectedBox.getBoundsWidth(), detectedBox.getBoundsHeight(),
                    confidence);

            return new DetectionResult(detectedBox, debugImage, confidence);
        }

        logger.warn("No green box detected in macro image");
        return null;
    }

    /**
     * Creates a binary mask of pixels that match the green box color.
     */
    private static BufferedImage createGreenMask(BufferedImage image, DetectionParams params) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);

        int greenPixelCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);

                if (isGreenBoxPixel(rgb, params)) {
                    mask.setRGB(x, y, 0xFFFFFF);
                    greenPixelCount++;
                } else {
                    mask.setRGB(x, y, 0x000000);
                }
            }
        }

        logger.debug("Found {} green pixels ({}% of image)",
                greenPixelCount, (100.0 * greenPixelCount) / (width * height));

        return mask;
    }

    /**
     * Checks if a pixel matches the expected green box color.
     */
    private static boolean isGreenBoxPixel(int rgb, DetectionParams params) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // Convert to normalized values
        double rNorm = r / 255.0;
        double gNorm = g / 255.0;
        double bNorm = b / 255.0;

        // Check if green is dominant
        double total = rNorm + gNorm + bNorm;
        if (total == 0) return false;

        double greenRatio = gNorm / total;
        if (greenRatio < params.greenThreshold) return false;

        // Check saturation and brightness
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float hue = hsb[0];
        float saturation = hsb[1];
        float brightness = hsb[2];

        // Green hue is around 120 degrees (0.33 in normalized)
        boolean isGreenHue = hue > 0.25 && hue < 0.42;
        boolean goodSaturation = saturation > params.saturationMin;
        boolean goodBrightness = brightness > params.brightnessMin && brightness < params.brightnessMax;

        return isGreenHue && goodSaturation && goodBrightness;
    }

    /**
     * Finds potential box edges in the binary mask.
     */
    private static List<Rectangle> findBoxEdges(BufferedImage mask, DetectionParams params) {
        List<Rectangle> edges = new ArrayList<>();
        int width = mask.getWidth();
        int height = mask.getHeight();

        // Scan for horizontal edges
        for (int y = 0; y < height - params.edgeThickness; y++) {
            for (int x = 0; x < width; x++) {
                if (isHorizontalEdge(mask, x, y, params)) {
                    // Find extent of this edge
                    int startX = x;
                    while (x < width && isHorizontalEdge(mask, x, y, params)) {
                        x++;
                    }
                    int endX = x;

                    if (endX - startX > params.minBoxWidth) {
                        edges.add(new Rectangle(startX, y, endX - startX, params.edgeThickness));
                    }
                }
            }
        }

        // Scan for vertical edges
        for (int x = 0; x < width - params.edgeThickness; x++) {
            for (int y = 0; y < height; y++) {
                if (isVerticalEdge(mask, x, y, params)) {
                    // Find extent of this edge
                    int startY = y;
                    while (y < height && isVerticalEdge(mask, x, y, params)) {
                        y++;
                    }
                    int endY = y;

                    if (endY - startY > params.minBoxHeight) {
                        edges.add(new Rectangle(x, startY, params.edgeThickness, endY - startY));
                    }
                }
            }
        }

        logger.debug("Found {} potential box edges", edges.size());
        return edges;
    }

    /**
     * Checks if a position contains a horizontal edge.
     */
    private static boolean isHorizontalEdge(BufferedImage mask, int x, int y, DetectionParams params) {
        // Check if we have a thick horizontal line
        for (int dy = 0; dy < params.edgeThickness && y + dy < mask.getHeight(); dy++) {
            if ((mask.getRGB(x, y + dy) & 0xFF) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a position contains a vertical edge.
     */
    private static boolean isVerticalEdge(BufferedImage mask, int x, int y, DetectionParams params) {
        // Check if we have a thick vertical line
        for (int dx = 0; dx < params.edgeThickness && x + dx < mask.getWidth(); dx++) {
            if ((mask.getRGB(x + dx, y) & 0xFF) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Attempts to find a complete rectangular box from the detected edges.
     */
    private static ROI findCompleteBox(List<Rectangle> edges, int imageWidth, int imageHeight,
                                       DetectionParams params) {
        // Find the four edges that form the most likely box
        Rectangle topEdge = null, bottomEdge = null, leftEdge = null, rightEdge = null;

        // Find horizontal edges (top and bottom)
        for (Rectangle edge : edges) {
            if (edge.width > edge.height) { // Horizontal edge
                if (topEdge == null || edge.y < topEdge.y) {
                    bottomEdge = topEdge;
                    topEdge = edge;
                } else if (bottomEdge == null || edge.y > bottomEdge.y) {
                    bottomEdge = edge;
                }
            }
        }

        // Find vertical edges (left and right)
        for (Rectangle edge : edges) {
            if (edge.height > edge.width) { // Vertical edge
                if (leftEdge == null || edge.x < leftEdge.x) {
                    rightEdge = leftEdge;
                    leftEdge = edge;
                } else if (rightEdge == null || edge.x > rightEdge.x) {
                    rightEdge = edge;
                }
            }
        }

        // Check if we have all four edges
        if (topEdge != null && bottomEdge != null && leftEdge != null && rightEdge != null) {
            // Calculate box bounds from edges
            int boxX = leftEdge.x + leftEdge.width / 2;
            int boxY = topEdge.y + topEdge.height / 2;
            int boxWidth = (rightEdge.x + rightEdge.width / 2) - boxX;
            int boxHeight = (bottomEdge.y + bottomEdge.height / 2) - boxY;

            // Validate box dimensions
            if (boxWidth >= params.minBoxWidth && boxHeight >= params.minBoxHeight) {
                return ROIs.createRectangleROI(boxX, boxY, boxWidth, boxHeight,
                        ImagePlane.getDefaultPlane());
            }
        }

        // Alternative: look for the largest connected green region
        return findLargestGreenRegion(edges, imageWidth, imageHeight, params);
    }

    /**
     * Fallback method to find the largest green region if edge detection fails.
     */
    private static ROI findLargestGreenRegion(List<Rectangle> edges, int width, int height,
                                              DetectionParams params) {
        // This would use connected component analysis
        // For now, return null
        return null;
    }

    /**
     * Calculates confidence score for the detected box.
     */
    private static double calculateConfidence(ROI box, BufferedImage mask, DetectionParams params) {
        // Check how well the edges match expected characteristics
        double edgeScore = 0.0;
        double rectangularityScore = 1.0; // Since we enforce rectangles
        double sizeScore = Math.min(1.0, box.getBoundsWidth() * box.getBoundsHeight() / (mask.getWidth() * mask.getHeight() * 0.5));

        return (edgeScore + rectangularityScore + sizeScore) / 3.0;
    }

    /**
     * Creates a debug image showing the detection result.
     */
    private static BufferedImage createDebugImage(BufferedImage original, BufferedImage mask, ROI box) {
        BufferedImage debug = new BufferedImage(original.getWidth(), original.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = debug.createGraphics();

        // Draw original
        g.drawImage(original, 0, 0, null);

        // Overlay mask with transparency
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g.setColor(Color.GREEN);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if ((mask.getRGB(x, y) & 0xFF) > 0) {
                    g.fillRect(x, y, 1, 1);
                }
            }
        }

        // Draw detected box
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3));
        g.drawRect((int)box.getBoundsX(), (int)box.getBoundsY(),
                (int)box.getBoundsWidth(), (int)box.getBoundsHeight());

        g.dispose();
        return debug;
    }

    /**
     * Calculates initial affine transform based on green box detection.
     * The green box in the macro image represents the exact area of the main image,
     * so this creates a transform that maps macro pixel coordinates to main image pixels.
     *
     * @param greenBoxInMacro The detected green box ROI in macro coordinates (pixels)
     * @param mainImageWidth Width of the main image in pixels
     * @param mainImageHeight Height of the main image in pixels
     * @param macroPixelSize Pixel size of the macro image in microns (typically ~80)
     * @param mainPixelSize Pixel size of the main image in microns
     * @return Initial affine transform from macro to main image coordinates
     */
    public static AffineTransform calculateInitialTransform(
            ROI greenBoxInMacro,
            int mainImageWidth,
            int mainImageHeight,
            double macroPixelSize,
            double mainPixelSize) {

        // The green box area in the macro represents the entire main image
        // We need to map macro pixels to main image pixels

        // Physical size of the green box in microns
        double boxWidthMicrons = greenBoxInMacro.getBoundsWidth() * macroPixelSize;
        double boxHeightMicrons = greenBoxInMacro.getBoundsHeight() * macroPixelSize;

        // Physical size of the main image in microns
        double mainWidthMicrons = mainImageWidth * mainPixelSize;
        double mainHeightMicrons = mainImageHeight * mainPixelSize;

        // These should be approximately equal - log any discrepancy
        logger.info("Green box physical size: {}x{} µm", boxWidthMicrons, boxHeightMicrons);
        logger.info("Main image physical size: {}x{} µm", mainWidthMicrons, mainHeightMicrons);

        // Calculate pixel-to-pixel scale factors
        double scaleX = mainImageWidth / greenBoxInMacro.getBoundsWidth();
        double scaleY = mainImageHeight / greenBoxInMacro.getBoundsHeight();

        // Translation to align top-left corners
        double translateX = -greenBoxInMacro.getBoundsX() * scaleX;
        double translateY = -greenBoxInMacro.getBoundsY() * scaleY;

        // Create transform
        AffineTransform transform = new AffineTransform();
        transform.translate(translateX, translateY);
        transform.scale(scaleX, scaleY);

        logger.info("Green box transform: scale=({}, {}), translate=({}, {})",
                scaleX, scaleY, translateX, translateY);
        logger.info("Maps macro box ({}, {}, {}, {}) to main image (0, 0, {}, {})",
                greenBoxInMacro.getBoundsX(), greenBoxInMacro.getBoundsY(),
                greenBoxInMacro.getBoundsWidth(), greenBoxInMacro.getBoundsHeight(),
                mainImageWidth, mainImageHeight);

        return transform;
    }
}