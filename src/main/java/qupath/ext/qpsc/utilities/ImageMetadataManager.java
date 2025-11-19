package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * ImageMetadataManager
 *
 * <p>Manages metadata for images in multi-sample projects:
 *   - Image collection grouping
 *   - Position offsets
 *   - Flip status tracking
 *   - Sample name management
 *
 * <p>This enables support for multiple samples per project with proper
 * metadata tracking and validation.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ImageMetadataManager {
    private static final Logger logger = LoggerFactory.getLogger(ImageMetadataManager.class);

    // Metadata keys - using underscores for consistency with QuPath conventions
    public static final String IMAGE_COLLECTION = "image_collection";
    public static final String XY_OFFSET_X = "xy_offset_x_microns";
    public static final String XY_OFFSET_Y = "xy_offset_y_microns";
    public static final String IS_FLIPPED = "is_flipped";
    public static final String SAMPLE_NAME = "sample_name";
    public static final String ORIGINAL_IMAGE_ID = "original_image_id";

    // Additional metadata keys for image identification
    public static final String MODALITY = "modality";
    public static final String OBJECTIVE = "objective";
    public static final String ANGLE = "angle";
    public static final String ANNOTATION_NAME = "annotation_name";
    public static final String IMAGE_INDEX = "image_index";

    /**
     * Gets the next available image collection number for a project.
     * Scans all existing images to find the highest collection number and returns that + 1.
     *
     * @param project The QuPath project
     * @return The next available collection number (minimum 1)
     */
    public static int getNextImageCollectionNumber(Project<?> project) {
        if (project == null) {
            logger.warn("Project is null, returning collection number 1");
            return 1;
        }

        int maxCollection = 0;

        for (ProjectImageEntry<?> entry : project.getImageList()) {
            Map<String, String> metadata = entry.getMetadata();
            String collectionStr = metadata.get(IMAGE_COLLECTION);
            if (collectionStr != null) {
                try {
                    int collection = Integer.parseInt(collectionStr);
                    maxCollection = Math.max(maxCollection, collection);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid image_collection value '{}' for entry: {}",
                            collectionStr, entry.getImageName());
                }
            }
        }

        int nextCollection = maxCollection + 1;
        logger.debug("Next image collection number: {}", nextCollection);
        return nextCollection;
    }

    /**
     * Applies comprehensive metadata to a new image entry including all identification fields.
     *
     * @param entry The image entry to apply metadata to
     * @param parentEntry Optional parent entry for collection inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param isFlipped Whether the image has been flipped
     * @param sampleName The sample name
     * @param modality The imaging modality (e.g., "ppm", "bf")
     * @param objective The objective/magnification (e.g., "20x", "10x")
     * @param angle The angle for multi-angle acquisitions (null if not applicable)
     * @param annotationName The annotation name (null if not applicable)
     * @param imageIndex The image index number
     */
    public static void applyImageMetadata(ProjectImageEntry<?> entry,
                                          ProjectImageEntry<?> parentEntry,
                                          double xOffset, double yOffset,
                                          boolean isFlipped, String sampleName,
                                          String modality, String objective,
                                          String angle, String annotationName,
                                          Integer imageIndex) {
        if (entry == null) {
            logger.error("Cannot apply metadata to null entry");
            return;
        }

        Map<String, String> metadata = entry.getMetadata();

        // Determine collection number
        String collectionNumber;
        if (parentEntry != null && parentEntry.getMetadata().get(IMAGE_COLLECTION) != null) {
            // Inherit from parent
            collectionNumber = parentEntry.getMetadata().get(IMAGE_COLLECTION);
            logger.info("Inheriting image_collection {} from parent: {}",
                    collectionNumber, parentEntry.getImageName());
        } else {
            // Get next available number
            Project<?> project = QP.getProject();
            collectionNumber = String.valueOf(getNextImageCollectionNumber(project));
            logger.info("Assigning new image_collection: {}", collectionNumber);
        }

        // Apply all metadata
        metadata.put(IMAGE_COLLECTION, collectionNumber);
        metadata.put(XY_OFFSET_X, String.valueOf(xOffset));
        metadata.put(XY_OFFSET_Y, String.valueOf(yOffset));
        metadata.put(IS_FLIPPED, String.valueOf(isFlipped));

        if (sampleName != null && !sampleName.isEmpty()) {
            metadata.put(SAMPLE_NAME, sampleName);
        }

        if (modality != null && !modality.isEmpty()) {
            metadata.put(MODALITY, modality);
        }

        if (objective != null && !objective.isEmpty()) {
            metadata.put(OBJECTIVE, objective);
        }

        if (angle != null && !angle.isEmpty()) {
            metadata.put(ANGLE, angle);
        }

        if (annotationName != null && !annotationName.isEmpty()) {
            metadata.put(ANNOTATION_NAME, annotationName);
        }

        if (imageIndex != null) {
            metadata.put(IMAGE_INDEX, String.valueOf(imageIndex));
        }

        // If this is a flipped duplicate, store reference to original
        if (parentEntry != null && isFlipped) {
            metadata.put(ORIGINAL_IMAGE_ID, parentEntry.getID());
        }

        logger.debug("Applied metadata to {}: collection={}, offset=({},{}), flipped={}, sample={}, modality={}, objective={}, angle={}, annotation={}, index={}",
                entry.getImageName(), collectionNumber, xOffset, yOffset, isFlipped, sampleName,
                modality, objective, angle, annotationName, imageIndex);
    }

    /**
     * Applies metadata to a new image entry based on its parent (if any).
     * If parent exists, inherits the image_collection value.
     * This is a convenience method that calls the full version with null for optional fields.
     *
     * @param entry The image entry to apply metadata to
     * @param parentEntry Optional parent entry for collection inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param isFlipped Whether the image has been flipped
     * @param sampleName The sample name
     */
    public static void applyImageMetadata(ProjectImageEntry<?> entry,
                                          ProjectImageEntry<?> parentEntry,
                                          double xOffset, double yOffset,
                                          boolean isFlipped, String sampleName) {
        applyImageMetadata(entry, parentEntry, xOffset, yOffset, isFlipped, sampleName,
                null, null, null, null, null);
    }

    /**
     * Validates if an image can be used for acquisition based on flip requirements.
     *
     * @param entry The image entry to validate
     * @return true if valid for acquisition, false otherwise
     */
    public static boolean isValidForAcquisition(ProjectImageEntry<?> entry) {
        if (entry == null) {
            logger.error("Cannot validate null entry");
            return false;
        }

        // Get flip requirements from QPPreferenceDialog
        boolean requiresFlipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean requiresFlipY = QPPreferenceDialog.getFlipMacroYProperty();

        // If no flipping required, all images are valid
        if (!requiresFlipX && !requiresFlipY) {
            logger.debug("No flip required - image {} is valid", entry.getImageName());
            return true;
        }

        // Check if image is marked as flipped
        String isFlippedStr = entry.getMetadata().get(IS_FLIPPED);
        boolean isFlipped = "true".equalsIgnoreCase(isFlippedStr);

        if (!isFlipped && (requiresFlipX || requiresFlipY)) {
            logger.warn("Image {} is not flipped but flip is required in preferences",
                    entry.getImageName());
            return false;
        }

        logger.debug("Image {} validation passed (flipped={}, requiresFlip={})",
                entry.getImageName(), isFlipped, (requiresFlipX || requiresFlipY));
        return true;
    }

    /**
     * Initializes metadata for all images in a project that don't have it.
     * Used for backward compatibility with single-sample projects.
     *
     * @param project The project to initialize
     */
    public static void initializeProjectMetadata(Project<?> project) {
        if (project == null) {
            logger.warn("Cannot initialize metadata for null project");
            return;
        }

        logger.info("Initializing metadata for project with {} images",
                project.getImageList().size());

        boolean anyChanges = false;

        for (ProjectImageEntry<?> entry : project.getImageList()) {
            Map<String, String> metadata = entry.getMetadata();

            if (metadata.get(IMAGE_COLLECTION) == null) {
                metadata.put(IMAGE_COLLECTION, "1");
                anyChanges = true;
                logger.debug("Initialized image_collection=1 for: {}", entry.getImageName());
            }

            // Initialize other fields with defaults if missing
            if (metadata.get(XY_OFFSET_X) == null) {
                metadata.put(XY_OFFSET_X, "0.0");
                anyChanges = true;
            }
            if (metadata.get(XY_OFFSET_Y) == null) {
                metadata.put(XY_OFFSET_Y, "0.0");
                anyChanges = true;
            }
            if (metadata.get(IS_FLIPPED) == null) {
                metadata.put(IS_FLIPPED, "false");
                anyChanges = true;
            }
        }

        if (anyChanges) {
            try {
                project.syncChanges();
                logger.info("Successfully initialized project metadata");
            } catch (IOException e) {
                logger.error("Failed to sync project after metadata initialization", e);
            }
        }
    }

    /**
     * Gets the image collection number for an entry.
     *
     * @param entry The image entry
     * @return The collection number, or -1 if not set or invalid
     */
    public static int getImageCollection(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return -1;
        }

        String collectionStr = entry.getMetadata().get(IMAGE_COLLECTION);
        if (collectionStr == null) {
            return -1;
        }

        try {
            return Integer.parseInt(collectionStr);
        } catch (NumberFormatException e) {
            logger.warn("Invalid image_collection value: {}", collectionStr);
            return -1;
        }
    }

    /**
     * Gets the XY offset for an image entry.
     *
     * @param entry The image entry
     * @return Array of [x, y] offsets in microns, or [0, 0] if not set
     */
    public static double[] getXYOffset(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return new double[]{0, 0};
        }

        Map<String, String> metadata = entry.getMetadata();
        try {
            double x = Double.parseDouble(metadata.get(XY_OFFSET_X));
            double y = Double.parseDouble(metadata.get(XY_OFFSET_Y));
            return new double[]{x, y};
        } catch (Exception e) {
            logger.debug("Could not parse XY offset for {}: {}",
                    entry.getImageName(), e.getMessage());
            return new double[]{0, 0};
        }
    }

    /**
     * Checks if an image entry is marked as flipped.
     *
     * @param entry The image entry to check
     * @return true if the image is marked as flipped, false otherwise
     */
    public static boolean isFlipped(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return false;
        }

        String isFlippedStr = entry.getMetadata().get(IS_FLIPPED);
        return "true".equalsIgnoreCase(isFlippedStr);
    }

    /**
     * Gets the sample name from an image entry.
     *
     * @param entry The image entry
     * @return The sample name, or null if not set
     */
    public static String getSampleName(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return null;
        }

        return entry.getMetadata().get(SAMPLE_NAME);
    }

    /**
     * Gets the original image ID for a flipped duplicate.
     *
     * @param entry The image entry
     * @return The original image ID, or null if not a flipped duplicate
     */
    public static String getOriginalImageId(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return null;
        }

        return entry.getMetadata().get(ORIGINAL_IMAGE_ID);
    }

    /**
     * Checks if an entry belongs to a specific image collection.
     *
     * @param entry The image entry to check
     * @param collectionNumber The collection number to compare against
     * @return true if the entry belongs to the specified collection
     */
    public static boolean isInCollection(ProjectImageEntry<?> entry, int collectionNumber) {
        return getImageCollection(entry) == collectionNumber;
    }

    /**
     * Gets all metadata as an unmodifiable map.
     *
     * @param entry The image entry
     * @return Unmodifiable map of all metadata, or empty map if entry is null
     */
    public static Map<String, String> getAllMetadata(ProjectImageEntry<?> entry) {
        if (entry == null) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(entry.getMetadata());
    }

    /**
     * Updates a single metadata value.
     *
     * @param entry The image entry to update
     * @param key The metadata key
     * @param value The value to set
     * @param syncProject Whether to sync project changes immediately
     */
    public static void updateMetadataValue(ProjectImageEntry<?> entry,
                                           String key, String value,
                                           boolean syncProject) {
        if (entry == null || key == null) {
            logger.warn("Cannot update metadata with null entry or key");
            return;
        }

        entry.getMetadata().put(key, value);
        logger.debug("Updated metadata for {}: {} = {}", entry.getImageName(), key, value);

        if (syncProject) {
            Project<?> project = QP.getProject();
            if (project != null) {
                try {
                    project.syncChanges();
                } catch (IOException e) {
                    logger.error("Failed to sync project after metadata update", e);
                }
            }
        }
    }
}