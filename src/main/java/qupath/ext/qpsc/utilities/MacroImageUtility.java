package qupath.ext.qpsc.utilities;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;

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