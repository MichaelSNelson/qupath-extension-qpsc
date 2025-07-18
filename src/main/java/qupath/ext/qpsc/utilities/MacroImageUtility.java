package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Utility class for retrieving and working with macro images from QuPath projects.
 * Provides centralized access to macro image functionality used across different workflows.
 */
/**
 * Utility class for retrieving and working with macro images from QuPath projects.
 */
public class MacroImageUtility {
    private static final Logger logger = LoggerFactory.getLogger(MacroImageUtility.class);

    // Default slide boundaries in macro image coordinates (in pixels)
    // These define the physical slide area within the macro image
    private static final int DEFAULT_SLIDE_X_MIN = 0;
    private static final int DEFAULT_SLIDE_X_MAX = 985;
    private static final int DEFAULT_SLIDE_Y_MIN = 19;
    private static final int DEFAULT_SLIDE_Y_MAX = 331;

    // Tolerance for slide boundary detection (in pixels)
    private static final int BOUNDARY_TOLERANCE = 2; // ~80µm at 40µm/pixel

    /**
     * Container for cropped macro image information.
     * Holds the cropped image and adjusted coordinates.
     */
    public static class CroppedMacroResult {
        private final BufferedImage croppedImage;
        private final int originalWidth;
        private final int originalHeight;
        private final int cropOffsetX;
        private final int cropOffsetY;

        public CroppedMacroResult(BufferedImage croppedImage,
                                  int originalWidth, int originalHeight,
                                  int cropOffsetX, int cropOffsetY) {
            this.croppedImage = croppedImage;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.cropOffsetX = cropOffsetX;
            this.cropOffsetY = cropOffsetY;
        }

        public BufferedImage getCroppedImage() {
            return croppedImage;
        }

        public int getOriginalWidth() {
            return originalWidth;
        }

        public int getOriginalHeight() {
            return originalHeight;
        }

        public int getCropOffsetX() {
            return cropOffsetX;
        }

        public int getCropOffsetY() {
            return cropOffsetY;
        }

        /**
         * Adjusts an ROI from original macro coordinates to cropped coordinates.
         */
        public ROI adjustROI(ROI originalROI) {
            if (originalROI == null) return null;

            double adjustedX = originalROI.getBoundsX() - cropOffsetX;
            double adjustedY = originalROI.getBoundsY() - cropOffsetY;

            // Ensure the adjusted ROI is within the cropped bounds
            if (adjustedX < 0 || adjustedY < 0 ||
                    adjustedX + originalROI.getBoundsWidth() > croppedImage.getWidth() ||
                    adjustedY + originalROI.getBoundsHeight() > croppedImage.getHeight()) {
                logger.warn("Adjusted ROI extends beyond cropped image bounds");
            }

            return ROIs.createRectangleROI(
                    adjustedX, adjustedY,
                    originalROI.getBoundsWidth(),
                    originalROI.getBoundsHeight(),
                    originalROI.getImagePlane()
            );
        }

        /**
         * Converts coordinates from cropped image back to original macro image.
         */
        public double[] toOriginalCoordinates(double croppedX, double croppedY) {
            return new double[] {
                    croppedX + cropOffsetX,
                    croppedY + cropOffsetY
            };
        }


    }

    /**
     * Crops the macro image to just the slide area, removing surrounding holder/background.
     * Uses default slide boundaries or attempts to detect them automatically.
     *
     * @param macroImage The original macro image
     * @return CroppedMacroResult containing the cropped image and offset information
     */
    public static CroppedMacroResult cropToSlideArea(BufferedImage macroImage) {
        return cropToSlideArea(macroImage, DEFAULT_SLIDE_X_MIN, DEFAULT_SLIDE_X_MAX,
                DEFAULT_SLIDE_Y_MIN, DEFAULT_SLIDE_Y_MAX);
    }

    /**
     * Crops the macro image to the specified slide area.
     *
     * @param macroImage The original macro image
     * @param slideXMin Minimum X coordinate of the slide area
     * @param slideXMax Maximum X coordinate of the slide area
     * @param slideYMin Minimum Y coordinate of the slide area
     * @param slideYMax Maximum Y coordinate of the slide area
     * @return CroppedMacroResult containing the cropped image and offset information
     */
    public static CroppedMacroResult cropToSlideArea(BufferedImage macroImage,
                                                     int slideXMin, int slideXMax,
                                                     int slideYMin, int slideYMax) {
        if (macroImage == null) {
            throw new IllegalArgumentException("Macro image cannot be null");
        }

        // Store original dimensions
        int originalWidth = macroImage.getWidth();
        int originalHeight = macroImage.getHeight();

        // Validate boundaries
        slideXMin = Math.max(0, slideXMin);
        slideXMax = Math.min(originalWidth, slideXMax);
        slideYMin = Math.max(0, slideYMin);
        slideYMax = Math.min(originalHeight, slideYMax);

        if (slideXMin >= slideXMax || slideYMin >= slideYMax) {
            logger.error("Invalid slide boundaries: X[{}-{}], Y[{}-{}]",
                    slideXMin, slideXMax, slideYMin, slideYMax);
            throw new IllegalArgumentException("Invalid slide boundaries");
        }

        int cropWidth = slideXMax - slideXMin;
        int cropHeight = slideYMax - slideYMin;

        logger.info("Cropping macro image from {}x{} to {}x{} (offset: {}, {})",
                originalWidth, originalHeight, cropWidth, cropHeight, slideXMin, slideYMin);

        // Perform the crop
        BufferedImage cropped = macroImage.getSubimage(slideXMin, slideYMin, cropWidth, cropHeight);

        // Create a copy to ensure it's independent of the original
        BufferedImage croppedCopy = new BufferedImage(cropWidth, cropHeight, macroImage.getType());
        Graphics2D g2d = croppedCopy.createGraphics();
        g2d.drawImage(cropped, 0, 0, null);
        g2d.dispose();

        return new CroppedMacroResult(croppedCopy, originalWidth, originalHeight,
                slideXMin, slideYMin);
    }
    /**
     * Crops the macro image based on scanner configuration.
     *
     * @param macroImage The original macro image
     * @param scannerName The scanner that produced this image (e.g., "Ocus40")
     * @return CroppedMacroResult containing the cropped image and offset information
     */
    public static CroppedMacroResult cropToSlideArea(BufferedImage macroImage, String scannerName) {
        // Implementation remains the same as before
        if (macroImage == null) {
            throw new IllegalArgumentException("Macro image cannot be null");
        }

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        // Check if scanner is configured
        if (!mgr.isScannerConfigured(scannerName)) {
            logger.warn("Scanner '{}' not configured. Available scanners: {}. Using generic settings.",
                    scannerName, mgr.getAvailableScanners());
            scannerName = "Generic";
        }


        // Check if cropping is needed
        if (!mgr.scannerRequiresCropping(scannerName)) {
            logger.info("Scanner '{}' does not require cropping, returning original image", scannerName);
            return new CroppedMacroResult(macroImage, macroImage.getWidth(), macroImage.getHeight(), 0, 0);
        }

        // Get slide bounds
        MicroscopeConfigManager.SlideBounds bounds = mgr.getScannerSlideBounds(scannerName);
        if (bounds == null) {
            logger.error("Scanner '{}' requires cropping but has no configured bounds. Using defaults.", scannerName);
            return cropToSlideArea(macroImage, DEFAULT_SLIDE_X_MIN, DEFAULT_SLIDE_X_MAX,
                    DEFAULT_SLIDE_Y_MIN, DEFAULT_SLIDE_Y_MAX);
        }

        logger.info("Cropping macro image for scanner '{}' using bounds: {}", scannerName, bounds);
        return cropToSlideArea(macroImage, bounds.xMin, bounds.xMax, bounds.yMin, bounds.yMax);
    }

    /**
     * Get macro pixel size for a specific scanner.
     * @param scannerName The scanner name
     * @return The macro pixel size in microns
     * @throws IllegalStateException if scanner is not configured or pixel size is missing
     */
    public static double getMacroPixelSize(String scannerName) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                QPPreferenceDialog.getMicroscopeConfigFileProperty()
        );

        if (!mgr.isScannerConfigured(scannerName)) {
            String error = String.format(
                    "Scanner '%s' is not configured in the microscope configuration file. " +
                            "Available scanners: %s. Please add scanner configuration or select a different scanner.",
                    scannerName, mgr.getAvailableScanners()
            );
            logger.error(error);
            throw new IllegalStateException(error);
        }

        Double pixelSize = mgr.getScannerMacroPixelSize(scannerName);
        if (pixelSize == null || pixelSize <= 0) {
            String error = String.format(
                    "Scanner '%s' has no valid macro pixel size configured. " +
                            "This is required for accurate alignment. " +
                            "Please add 'macro.pixelSize_um' to the scanner configuration.",
                    scannerName
            );
            logger.error(error);
            throw new IllegalStateException(error);
        }

        logger.info("Using macro pixel size {} µm for scanner '{}'", pixelSize, scannerName);
        return pixelSize;
    }

    /**
     * Get macro pixel size for the scanner selected in preferences.
     * @return The macro pixel size in microns
     * @throws IllegalStateException if scanner is not configured or pixel size is missing
     */
    public static double getMacroPixelSize() {
        String scannerName = QPPreferenceDialog.getSelectedScannerProperty();
        if (scannerName == null || scannerName.isEmpty()) {
            String error = "No scanner selected in preferences. Please select a scanner in Edit > Preferences.";
            logger.error(error);
            throw new IllegalStateException(error);
        }
        return getMacroPixelSize(scannerName);
    }

    /**
     * Applies X and/or Y flips to a macro image.
     *
     * @param image The image to flip
     * @param flipX Whether to flip horizontally
     * @param flipY Whether to flip vertically
     * @return The flipped image
     */
    public static BufferedImage flipMacroImage(BufferedImage image, boolean flipX, boolean flipY) {
        if (!flipX && !flipY) {
            return image; // No flipping needed
        }

        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage flipped = new BufferedImage(width, height, image.getType());
        Graphics2D g2d = flipped.createGraphics();

        // Apply flips using affine transform
        if (flipX && flipY) {
            g2d.translate(width, height);
            g2d.scale(-1, -1);
        } else if (flipX) {
            g2d.translate(width, 0);
            g2d.scale(-1, 1);
        } else { // flipY only
            g2d.translate(0, height);
            g2d.scale(1, -1);
        }

        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();

        logger.info("Applied flips to macro image: flipX={}, flipY={}", flipX, flipY);

        return flipped;
    }


    /**
     * Retrieves the macro image from the current QuPath image data.
     * Searches through associated images for entries containing "macro" and handles
     * various naming formats used by different scanner vendors.
     *
     * @param gui The QuPath GUI instance
     * @return The macro image as BufferedImage, or null if not available or retrieval fails
     */
    public static BufferedImage retrieveMacroImage(QuPathGUI gui) {
        try {
            var imageData = gui.getImageData();
            if (imageData == null) return null;

            var associatedList = imageData.getServer().getAssociatedImageList();
            if (associatedList == null) return null;

            // Find macro image key
            String macroKey = null;
            for (String name : associatedList) {
                if (name.toLowerCase().contains("macro")) {
                    macroKey = name;
                    break;
                }
            }

            if (macroKey == null) return null;

            // Try to retrieve it
            Object image = imageData.getServer().getAssociatedImage(macroKey);
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

            // Try variations if needed
            if (macroKey.startsWith("Series ")) {
                String seriesOnly = macroKey.split("\\s*\\(")[0].trim();
                image = imageData.getServer().getAssociatedImage(seriesOnly);
                if (image instanceof BufferedImage) {
                    return (BufferedImage) image;
                }
            }

            // Try simple "macro"
            image = imageData.getServer().getAssociatedImage("macro");
            if (image instanceof BufferedImage) {
                return (BufferedImage) image;
            }

        } catch (Exception e) {
            logger.error("Error retrieving macro image", e);
        }

        return null;
    }

    /**
     * Checks if a macro image is available without actually retrieving it.
     * Useful for enabling/disabling UI elements based on macro availability.
     *
     * @param gui The QuPath GUI instance
     * @return true if a macro image exists in the associated images list, false otherwise
     */
    public static boolean isMacroImageAvailable(QuPathGUI gui) {
        if (gui.getImageData() == null) {
            return false;
        }

        try {
            var associatedImages = gui.getImageData().getServer().getAssociatedImageList();
            if (associatedImages != null) {
                return associatedImages.stream()
                        .anyMatch(name -> name.toLowerCase().contains("macro"));
            }
        } catch (Exception e) {
            logger.error("Error checking for macro image", e);
        }
        return false;
    }

}
