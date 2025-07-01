package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Analyzes macro images to detect tissue regions and compute bounding boxes
 * for targeted acquisition. Provides multiple thresholding strategies.
 *
 * @since 0.3.0
 */
public class MacroImageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MacroImageAnalyzer.class);

    /**
     * Available thresholding methods.
     */
    public enum ThresholdMethod {
        OTSU("Otsu's method"),
        MEAN("Mean threshold"),
        PERCENTILE("Percentile-based"),
        FIXED("Fixed value"),
        IJ_AUTO("ImageJ Auto threshold");

        private final String description;

        ThresholdMethod(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Result of macro image analysis containing tissue bounds and metadata.
     */
    public static class MacroAnalysisResult {
        private final BufferedImage macroImage;
        private final BufferedImage thresholdedImage;
        private final ROI tissueBounds;
        private final List<ROI> tissueRegions;
        private final double scaleFactorX;
        private final double scaleFactorY;
        private final int threshold;

        public MacroAnalysisResult(BufferedImage macroImage, BufferedImage thresholdedImage,
                                   ROI tissueBounds, List<ROI> tissueRegions,
                                   double scaleFactorX, double scaleFactorY, int threshold) {
            this.macroImage = macroImage;
            this.thresholdedImage = thresholdedImage;
            this.tissueBounds = tissueBounds;
            this.tissueRegions = tissueRegions;
            this.scaleFactorX = scaleFactorX;
            this.scaleFactorY = scaleFactorY;
            this.threshold = threshold;
        }

        // Getters
        public BufferedImage getMacroImage() { return macroImage; }
        public BufferedImage getThresholdedImage() { return thresholdedImage; }
        public ROI getTissueBounds() { return tissueBounds; }
        public List<ROI> getTissueRegions() { return tissueRegions; }
        public double getScaleFactorX() { return scaleFactorX; }
        public double getScaleFactorY() { return scaleFactorY; }
        public int getThreshold() { return threshold; }

        /**
         * Converts a ROI from macro coordinates to main image coordinates.
         */
        public ROI scaleToMainImage(ROI macroROI) {
            double minX = macroROI.getBoundsX() * scaleFactorX;
            double minY = macroROI.getBoundsY() * scaleFactorY;
            double width = macroROI.getBoundsWidth() * scaleFactorX;
            double height = macroROI.getBoundsHeight() * scaleFactorY;

            return ROIs.createRectangleROI(minX, minY, width, height,
                    ImagePlane.getDefaultPlane());
        }
    }

    /**
     * Extracts and analyzes the macro image from the current image data.
     *
     * @param imageData The current QuPath image data
     * @param method The thresholding method to use
     * @param params Additional parameters for the threshold method
     * @return Analysis results, or null if no macro image is available
     */
    public static MacroAnalysisResult analyzeMacroImage(ImageData<?> imageData,
                                                        ThresholdMethod method,
                                                        Map<String, Object> params) {
        logger.info("Starting macro image analysis with method: {}", method);

        ImageServer<?> server = imageData.getServer();

        // Check for associated macro image
        if (!server.getAssociatedImageList().contains("macro")) {
            logger.warn("No macro image found in the current image");
            return null;
        }

        // Extract macro image
        BufferedImage macro = (BufferedImage) server.getAssociatedImage("macro");
        if (macro == null) {
            logger.error("Failed to extract macro image");
            return null;
        }

        logger.info("Extracted macro image: {}x{} pixels",
                macro.getWidth(), macro.getHeight());

        // Calculate scale factors between macro and main image
        double scaleX = (double) server.getWidth() / macro.getWidth();
        double scaleY = (double) server.getHeight() / macro.getHeight();

        // Apply thresholding
        int threshold = calculateThreshold(macro, method, params);
        BufferedImage thresholded = applyThreshold(macro, threshold);

        // Find tissue regions
        List<ROI> regions = findTissueRegions(thresholded);
        ROI bounds = computeBoundingBox(regions);

        logger.info("Found {} tissue regions with overall bounds: ({}, {}, {}, {})",
                regions.size(), bounds.getBoundsX(), bounds.getBoundsY(),
                bounds.getBoundsWidth(), bounds.getBoundsHeight());

        return new MacroAnalysisResult(macro, thresholded, bounds, regions,
                scaleX, scaleY, threshold);

    }

    /**
     * Calculates the threshold value using the specified method.
     */
    private static int calculateThreshold(BufferedImage image, ThresholdMethod method,
                                          Map<String, Object> params) {
        // Convert to grayscale if needed
        BufferedImage gray = convertToGrayscale(image);

        // Build histogram
        int[] histogram = new int[256];
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int pixel = gray.getRGB(x, y) & 0xFF;
                histogram[pixel]++;
            }
        }

        return switch (method) {
            case OTSU -> calculateOtsuThreshold(histogram);
            case MEAN -> calculateMeanThreshold(histogram);
            case PERCENTILE -> {
                double percentile = (Double) params.getOrDefault("percentile", 0.5);
                yield calculatePercentileThreshold(histogram, percentile);
            }
            case FIXED -> {
                int fixed = (Integer) params.getOrDefault("threshold", 128);
                yield fixed;
            }
            case IJ_AUTO -> {
                // Could integrate with ImageJ here
                logger.warn("ImageJ auto threshold not implemented, using Otsu");
                yield calculateOtsuThreshold(histogram);
            }
        };
    }

    /**
     * Converts image to grayscale.
     */
    private static BufferedImage convertToGrayscale(BufferedImage image) {
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return gray;
    }

    /**
     * Implements Otsu's thresholding method.
     */
    private static int calculateOtsuThreshold(int[] histogram) {
        int total = Arrays.stream(histogram).sum();
        float sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * histogram[i];
        }

        float sumB = 0;
        int wB = 0;
        int wF = 0;

        float varMax = 0;
        int threshold = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) continue;

            wF = total - wB;
            if (wF == 0) break;

            sumB += i * histogram[i];

            float mB = sumB / wB;
            float mF = (sum - sumB) / wF;

            float varBetween = (float) wB * (float) wF * (mB - mF) * (mB - mF);

            if (varBetween > varMax) {
                varMax = varBetween;
                threshold = i;
            }
        }

        logger.info("Otsu threshold calculated: {}", threshold);
        return threshold;
    }

    /**
     * Calculates mean threshold from histogram.
     */
    private static int calculateMeanThreshold(int[] histogram) {
        long sum = 0;
        long count = 0;
        for (int i = 0; i < histogram.length; i++) {
            sum += i * histogram[i];
            count += histogram[i];
        }
        int threshold = (int) (sum / count);
        logger.info("Mean threshold calculated: {}", threshold);
        return threshold;
    }

    /**
     * Calculates percentile-based threshold.
     */
    private static int calculatePercentileThreshold(int[] histogram, double percentile) {
        int total = Arrays.stream(histogram).sum();
        int target = (int) (total * percentile);
        int sum = 0;

        for (int i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= target) {
                logger.info("Percentile {} threshold calculated: {}", percentile, i);
                return i;
            }
        }
        return 128; // fallback
    }

    /**
     * Applies threshold to create binary image.
     */
    private static BufferedImage applyThreshold(BufferedImage image, int threshold) {
        BufferedImage binary = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_BYTE_BINARY);

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int gray = (int) (0.299 * ((rgb >> 16) & 0xFF) +
                        0.587 * ((rgb >> 8) & 0xFF) +
                        0.114 * (rgb & 0xFF));

                // Tissue is typically darker than background
                int newPixel = gray < threshold ? 0x000000 : 0xFFFFFF;
                binary.setRGB(x, y, newPixel);
            }
        }

        return binary;
    }

    /**
     * Finds connected tissue regions using simple flood fill.
     * Note: This is a basic implementation - could be enhanced with
     * morphological operations, size filtering, etc.
     */
    private static List<ROI> findTissueRegions(BufferedImage binary) {
        List<ROI> regions = new ArrayList<>();
        int width = binary.getWidth();
        int height = binary.getHeight();
        boolean[][] visited = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!visited[y][x] && (binary.getRGB(x, y) & 0xFF) == 0) {
                    // Found tissue pixel - flood fill to find region
                    Rectangle bounds = floodFill(binary, visited, x, y);
                    if (bounds.width > 10 && bounds.height > 10) { // Filter small regions
                        ROI roi = ROIs.createRectangleROI(bounds.x, bounds.y,
                                bounds.width, bounds.height,
                                ImagePlane.getDefaultPlane());
                        regions.add(roi);
                    }
                }
            }
        }

        return regions;
    }

    /**
     * Simple flood fill to find connected region bounds.
     */
    private static Rectangle floodFill(BufferedImage binary, boolean[][] visited,
                                       int startX, int startY) {
        Queue<Point> queue = new LinkedList<>();
        queue.add(new Point(startX, startY));

        int minX = startX, maxX = startX;
        int minY = startY, maxY = startY;

        while (!queue.isEmpty()) {
            Point p = queue.poll();
            if (p.x < 0 || p.x >= binary.getWidth() ||
                    p.y < 0 || p.y >= binary.getHeight() ||
                    visited[p.y][p.x]) {
                continue;
            }

            if ((binary.getRGB(p.x, p.y) & 0xFF) != 0) {
                continue; // Not tissue
            }

            visited[p.y][p.x] = true;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);

            // Add neighbors
            queue.add(new Point(p.x + 1, p.y));
            queue.add(new Point(p.x - 1, p.y));
            queue.add(new Point(p.x, p.y + 1));
            queue.add(new Point(p.x, p.y - 1));
        }

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Computes overall bounding box for all regions.
     */
    private static ROI computeBoundingBox(List<ROI> regions) {
        if (regions.isEmpty()) {
            return ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane());
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

        for (ROI roi : regions) {
            minX = Math.min(minX, roi.getBoundsX());
            minY = Math.min(minY, roi.getBoundsY());
            maxX = Math.max(maxX, roi.getBoundsX() + roi.getBoundsWidth());
            maxY = Math.max(maxY, roi.getBoundsY() + roi.getBoundsHeight());
        }

        return ROIs.createRectangleROI(minX, minY, maxX - minX, maxY - minY,
                ImagePlane.getDefaultPlane());
    }

    /**
     * Saves the analysis images for debugging/review.
     */
    public static void saveAnalysisImages(MacroAnalysisResult result, String outputPath)
            throws IOException {
        File dir = new File(outputPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Save original macro
        ImageIO.write(result.getMacroImage(), "png",
                new File(dir, "macro_original.png"));

        // Save thresholded
        ImageIO.write(result.getThresholdedImage(), "png",
                new File(dir, "macro_thresholded.png"));

        // Save with bounds overlay
        BufferedImage overlay = new BufferedImage(
                result.getMacroImage().getWidth(),
                result.getMacroImage().getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = overlay.createGraphics();
        g.drawImage(result.getMacroImage(), 0, 0, null);
        g.setColor(new Color(255, 0, 0, 128));
        g.setStroke(new BasicStroke(2));

        ROI bounds = result.getTissueBounds();
        g.drawRect((int) bounds.getBoundsX(), (int) bounds.getBoundsY(),
                (int) bounds.getBoundsWidth(), (int) bounds.getBoundsHeight());
        g.dispose();

        ImageIO.write(overlay, "png", new File(dir, "macro_bounds.png"));

        logger.info("Saved analysis images to {}", outputPath);
    }
}