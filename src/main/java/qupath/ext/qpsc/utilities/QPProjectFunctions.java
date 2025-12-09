package qupath.ext.qpsc.utilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URISyntaxException;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * QPProjectFunctions
 *
 * <p>Project level helpers for QuPath projects:
 *   - Create or load a .qpproj in a folder.
 *   - Add images (with optional flipping/transforms) to a project.
 *   - Save or synchronize ImageData.
 *   - Multi-sample metadata support through ImageMetadataManager
 */
public class QPProjectFunctions {
    private static final Logger logger = LoggerFactory.getLogger(QPProjectFunctions.class);

    /**
     * Result container for creating/loading a project.
     */
    private static class ProjectSetup {
        final String imagingModeWithIndex;
        final String tempTileDirectory;

        ProjectSetup(String imagingModeWithIndex, String tempTileDirectory) {
            this.imagingModeWithIndex = imagingModeWithIndex;
            this.tempTileDirectory = tempTileDirectory;
        }
    }

    /**
     * Top level: create (or open) the QuPath project, add current image (if any) and return key info map.
     *
     * @param qupathGUI           the QuPath GUI instance
     * @param projectsFolderPath  root folder for all projects
     * @param sampleLabel         subfolder / project name
     * @param enhancedModality      enhanced imaging modality (includes magnification)
     * @param isSlideFlippedX     flip X on import?
     * @param isSlideFlippedY     flip Y on import?
     * @return a Map containing project details
     */
    public static Map<String,Object> createAndOpenQuPathProject(
            QuPathGUI qupathGUI,
            String projectsFolderPath,
            String sampleLabel,
            String enhancedModality,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY) throws IOException {

        logger.info("Creating/opening project: {} in {}", sampleLabel, projectsFolderPath);

        // 1) Prepare folders and preferences
        ProjectSetup setup = prepareProjectFolders(projectsFolderPath, sampleLabel, enhancedModality);

        // 2) Create or load the actual QuPath project file
        Project<BufferedImage> project = createOrLoadProject(projectsFolderPath, sampleLabel);

        // Initialize project metadata
        ImageMetadataManager.initializeProjectMetadata(project);

        // 3) Import + open the current image, if any
        ProjectImageEntry<BufferedImage> matchingImage = null;

        ImageData<BufferedImage> currentImageData = qupathGUI.getImageData();
        if (currentImageData != null) {
            logger.info("Current image found, checking if it needs to be added to project");

            // Check if this image is already in the project
            ProjectImageEntry<BufferedImage> existingEntry = findImageInProject(project, currentImageData);

            if (existingEntry != null) {
                logger.info("Image already exists in project: {}", existingEntry.getImageName());
                matchingImage = existingEntry;
            } else {
                // Try to extract file path and add to project
                String imagePath = extractImagePath(currentImageData);

                if (imagePath != null && new File(imagePath).exists()) {
                    File imageFile = new File(imagePath);
                    logger.info("Adding new image to project: {}", imagePath);

                    // Use the metadata-aware method
                    matchingImage = addImageToProjectWithMetadata(
                            project,
                            imageFile,
                            null, // no parent
                            0, 0, // TODO: Calculate actual offsets from slide corner
                            isSlideFlippedX,
                            isSlideFlippedY,
                            sampleLabel,
                            null  // no modality handler available in this context
                    );

                    if (matchingImage != null) {
                        // Open the image later, after project is set
                        logger.info("Image added successfully: {}", matchingImage.getImageName());
                    } else {
                        logger.error("Failed to add image to project: {}", imagePath);
                    }
                } else {
                    logger.warn("Could not extract valid file path from current image");
                }
            }
        } else {
            logger.info("No current image open in QuPath");
        }

        // Set the project as active
        qupathGUI.setProject(project);

        // Setting the project can clear the current image, so ensure we reopen it
        if (matchingImage != null) {
            logger.info("Reopening image after project set: {}", matchingImage.getImageName());
            qupathGUI.openImageEntry(matchingImage);
            // Wait briefly for image to load
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.debug("Interrupted while waiting for image load");
            }

            // Verify image loaded successfully
            if (qupathGUI.getImageData() == null) {
                logger.error("Failed to reopen image after setting project");
            }
        }

        // 4) Package results
        Map<String,Object> result = new HashMap<>();
        result.put("matchingImage", matchingImage);
        result.put("imagingModeWithIndex", setup.imagingModeWithIndex);
        result.put("currentQuPathProject", project);
        result.put("tempTileDirectory", setup.tempTileDirectory);

        logger.info("Project setup complete. Mode: {}, Tile acquisition parent dir: {}",
                setup.imagingModeWithIndex, setup.tempTileDirectory);

        return result;
    }

    /**
     * Find an image in the project that matches the current ImageData.
     * This checks multiple ways to match images.
     */
    private static ProjectImageEntry<BufferedImage> findImageInProject(
            Project<BufferedImage> project,
            ImageData<BufferedImage> imageData) {

        if (project == null || imageData == null) {
            return null;
        }

        // First, check if we can get the entry directly
        try {
            ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
            if (entry != null) {
                logger.debug("Found image via direct project.getEntry()");
                return entry;
            }
        } catch (Exception e) {
            logger.debug("Could not find image via getEntry: {}", e.getMessage());
        }

        // Try to match by server path or URI
        String serverPath = imageData.getServerPath();
        if (serverPath != null) {
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                try {
                    // Check if the URIs match
                    if (entry.getURIs() != null && !entry.getURIs().isEmpty()) {
                        for (URI uri : entry.getURIs()) {
                            if (uri.toString().equals(serverPath) ||
                                    serverPath.contains(uri.toString())) {
                                logger.debug("Found image via URI match: {}", uri);
                                return entry;
                            }
                        }
                    }

                    // Check by image name
                    String imageName = new File(extractImagePath(imageData)).getName();
                    if (imageName.equals(entry.getImageName())) {
                        logger.debug("Found image via name match: {}", imageName);
                        return entry;
                    }
                } catch (Exception e) {
                    logger.debug("Error checking entry: {}", e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * Extract a file path from ImageData, handling various server path formats.
     */
    private static String extractImagePath(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return null;
        }

        String serverPath = imageData.getServerPath();
        if (serverPath == null) {
            return null;
        }

        logger.debug("Extracting path from server path: {}", serverPath);

        // Try multiple extraction methods

        // 1. First try the existing MinorFunctions method
        String path = MinorFunctions.extractFilePath(serverPath);
        if (path != null && new File(path).exists()) {
            logger.debug("Extracted via MinorFunctions: {}", path);
            return path;
        }

        // 2. Try direct URI parsing
        try {
            URI uri = new URI(serverPath);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri);
                if (file.exists()) {
                    logger.debug("Extracted via URI: {}", file.getAbsolutePath());
                    return file.getAbsolutePath();
                }
            }
        } catch (URISyntaxException e) {
            logger.debug("Not a valid URI: {}", serverPath);
        }

        // 3. Check if it's already a file path
        File directFile = new File(serverPath);
        if (directFile.exists()) {
            logger.debug("Server path is already a file path: {}", serverPath);
            return serverPath;
        }

        // 4. Try to get from the first URI in the image server
        try {
            ImageServer<BufferedImage> server = imageData.getServer();
            if (server != null && server.getURIs() != null && !server.getURIs().isEmpty()) {
                URI firstUri = server.getURIs().iterator().next();
                if ("file".equals(firstUri.getScheme())) {
                    File file = new File(firstUri);
                    if (file.exists()) {
                        logger.debug("Extracted from server URI: {}", file.getAbsolutePath());
                        return file.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract from server URIs: {}", e.getMessage());
        }

        logger.warn("Could not extract valid file path from: {}", serverPath);
        return null;
    }

    /**
     * Gets the actual image file name (without extension) from an ImageData.
     *
     * <p>This method extracts the real file name from the image server's URIs,
     * rather than using the metadata name which may be different (e.g., renamed
     * in a project or set to the project name).</p>
     *
     * <p>The extraction order is:
     * <ol>
     *   <li>Server URIs (most reliable for actual file name)</li>
     *   <li>Server path extraction</li>
     *   <li>Metadata name as fallback</li>
     * </ol>
     *
     * @param imageData The ImageData to extract the file name from
     * @return The file name without extension, or null if unavailable
     */
    public static String getActualImageFileName(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return null;
        }

        ImageServer<BufferedImage> server = imageData.getServer();
        if (server == null) {
            return null;
        }

        String fileName = null;

        // 1. Try to get from server URIs (most reliable for actual file name)
        try {
            if (server.getURIs() != null && !server.getURIs().isEmpty()) {
                URI firstUri = server.getURIs().iterator().next();
                String path = firstUri.getPath();
                if (path != null && !path.isEmpty()) {
                    // Get just the file name from the path
                    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                    fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                    logger.debug("Extracted file name from URI: {}", fileName);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract file name from URIs: {}", e.getMessage());
        }

        // 2. Try server path if URI didn't work
        if (fileName == null || fileName.isEmpty()) {
            String serverPath = imageData.getServerPath();
            if (serverPath != null) {
                String extractedPath = extractImagePath(imageData);
                if (extractedPath != null) {
                    File file = new File(extractedPath);
                    fileName = file.getName();
                    logger.debug("Extracted file name from server path: {}", fileName);
                }
            }
        }

        // 3. Fall back to metadata name (may be different from actual file name)
        if (fileName == null || fileName.isEmpty()) {
            fileName = server.getMetadata().getName();
            logger.debug("Using metadata name as fallback: {}", fileName);
        }

        // Strip extension if we got a name
        if (fileName != null && !fileName.isEmpty()) {
            return qupath.lib.common.GeneralTools.stripExtension(fileName);
        }

        return null;
    }

//    /**
//     * Import an image file to the project and open it in the GUI.
//     */
//    private static ProjectImageEntry<BufferedImage> importCurrentImageToNewProject(
//            QuPathGUI qupathGUI,
//            Project<BufferedImage> project,
//            File imageFile,
//            boolean flipX,
//            boolean flipY) throws IOException {
//
//        // Add image with flips
//        addImageToProject(imageFile, project, flipX, flipY);
//
//        // Find the newly added entry
//        String baseName = imageFile.getName();
//        ProjectImageEntry<BufferedImage> entry = project.getImageList().stream()
//                .filter(e -> baseName.equals(e.getImageName()))
//                .findFirst()
//                .orElse(null);
//
//        if (entry != null) {
//            // Open the image
//            qupathGUI.openImageEntry(entry);
//            qupathGUI.refreshProject();
//            logger.info("Opened image in GUI: {}", baseName);
//        } else {
//            logger.warn("Could not find newly added image in project: {}", baseName);
//        }
//
//        return entry;
//    }

    /**
     * Build a unique subfolder for this sample + modality, and compute the
     * temp–tiles directory path.
     */
    private static ProjectSetup prepareProjectFolders(
            String projectsFolderPath,
            String sampleLabel,
            String enhancedModality) {

        // Enhanced modality already includes magnification (e.g. "ppm_10x")
        // Apply unique folder naming to get indexed version (e.g. "ppm_10x_1", "ppm_10x_2", ...)
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                Paths.get(projectsFolderPath, sampleLabel, enhancedModality).toString());

        // full path: /…/projectsFolder/sampleLabel/ppm_10x_1
        String tempTileDirectory = Paths.get(
                        projectsFolderPath, sampleLabel, imagingModeWithIndex)
                .toString();

        return new ProjectSetup(imagingModeWithIndex, tempTileDirectory);
    }

    /**
     * Create the .qpproj (or load it, if present).
     * Returns the Project<BufferedImage> instance.
     */
    private static Project<BufferedImage> createOrLoadProject(
            String projectsFolderPath,
            String sampleLabel) throws IOException {
        return createProject(projectsFolderPath, sampleLabel);
    }

    /**
     * Returns project info for an already open project.
     * 
     * @param projectsFolderPath the root folder for all projects
     * @param sampleLabel the sample label/name
     * @param enhancedModality the enhanced imaging mode/modality name (includes magnification)
     * @return map containing project info including "tempTileDirectory" and "imagingModeWithIndex"
     */
    public static Map<String,Object> getCurrentProjectInformation(
            String projectsFolderPath,
            String sampleLabel,
            String enhancedModality) {
        Project<?> project = QP.getProject();
        // Enhanced modality already includes magnification (e.g. "ppm_10x")
        // Apply unique folder naming to get indexed version (e.g. "ppm_10x_1", "ppm_10x_2", ...)
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                projectsFolderPath + File.separator + sampleLabel + File.separator + enhancedModality);
        String tempTileDirectory = projectsFolderPath + File.separator + sampleLabel + File.separator + imagingModeWithIndex;
        ProjectImageEntry<?> matchingImage = QP.getProjectEntry();

        Map<String,Object> result = new HashMap<>();
        result.put("matchingImage", matchingImage);
        result.put("imagingModeWithIndex", imagingModeWithIndex);
        result.put("currentQuPathProject", project);
        result.put("tempTileDirectory", tempTileDirectory);

        // Add metadata info if available
        if (matchingImage != null) {
            String collection = matchingImage.getMetadata().get(ImageMetadataManager.IMAGE_COLLECTION);
            if (collection != null) {
                result.put("imageCollection", collection);
            }
        }

        return result;
    }

    /**
     * Enhanced version of addImageToProject with metadata support.
     *
     * @param project The project to add the image to
     * @param imageFile The image file to add
     * @param parentEntry Optional parent entry for metadata inheritance
     * @param xOffset X offset from slide corner in microns
     * @param yOffset Y offset from slide corner in microns
     * @param isFlippedX Whether the image has been flipped horizontally
     * @param isFlippedY Whether the image has been flipped vertically
     * @param sampleName The sample name
     * @param modalityHandler Optional modality handler for determining image type
     * @return The newly created ProjectImageEntry, or null on failure
     */
    /**
     * Adds an image to a project with comprehensive metadata including identification fields.
     */
    public static ProjectImageEntry<BufferedImage> addImageToProjectWithMetadata(
            Project<BufferedImage> project,
            File imageFile,
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean isFlippedX,
            boolean isFlippedY,
            String sampleName,
            String modality,
            String objective,
            String angle,
            String annotationName,
            Integer imageIndex,
            qupath.ext.qpsc.modality.ModalityHandler modalityHandler) throws IOException {

        if (project == null) {
            logger.error("Cannot add image: project is null");
            return null;
        }

        logger.info("Adding image with metadata: {} (parent={}, offset=({},{}), flipped={}, sample={}, modality={}, objective={}, angle={}, annotation={}, index={})",
                imageFile.getName(),
                parentEntry != null ? parentEntry.getImageName() : "none",
                xOffset, yOffset, isFlippedX || isFlippedY, sampleName,
                modality, objective, angle, annotationName, imageIndex);

        // First add the image using existing logic (preserves original method)
        boolean success = addImageToProject(imageFile, project, isFlippedX, isFlippedY, modalityHandler);
        if (!success) {
            return null;
        }

        // Find the newly added entry
        String imageName = imageFile.getName();
        ProjectImageEntry<BufferedImage> newEntry = project.getImageList().stream()
                .filter(e -> imageName.equals(e.getImageName()))
                .findFirst()
                .orElse(null);

        if (newEntry != null) {
            // Apply comprehensive metadata with all identification fields
            ImageMetadataManager.applyImageMetadata(
                    newEntry, parentEntry, xOffset, yOffset, isFlippedX || isFlippedY, sampleName,
                    modality, objective, angle, annotationName, imageIndex
            );

            // Save project
            project.syncChanges();
            logger.info("Successfully added image with comprehensive metadata to project");
        } else {
            logger.error("Could not find newly added image in project: {}", imageName);
        }

        return newEntry;
    }

    /**
     * Adds an image to a project with basic metadata.
     * This is a convenience method that calls the full version with null for optional fields.
     */
    public static ProjectImageEntry<BufferedImage> addImageToProjectWithMetadata(
            Project<BufferedImage> project,
            File imageFile,
            ProjectImageEntry<BufferedImage> parentEntry,
            double xOffset,
            double yOffset,
            boolean isFlippedX,
            boolean isFlippedY,
            String sampleName,
            qupath.ext.qpsc.modality.ModalityHandler modalityHandler) throws IOException {

        return addImageToProjectWithMetadata(project, imageFile, parentEntry,
                xOffset, yOffset, isFlippedX, isFlippedY, sampleName,
                null, null, null, null, null, modalityHandler);
    }

    /**
     * Creates a flipped duplicate of an image, preserving the hierarchy.
     *
     * IMPORTANT: This does NOT create a separate flipped file. Instead, it adds the SAME
     * underlying image file to the project again but with flips applied via TransformedServerBuilder.
     * The hierarchy objects are transformed to match the flipped coordinate system.
     *
     * NOTE: TransformedServerBuilder creates a virtual transformation - the actual pixel data
     * comes from the original file, but QuPath applies the transformation when rendering.
     *
     * @param project The project
     * @param originalEntry The original image entry to duplicate
     * @param flipX Whether to flip horizontally
     * @param flipY Whether to flip vertically
     * @param sampleName The sample name
     * @return The new flipped entry, or null on failure
     */
    public static ProjectImageEntry<BufferedImage> createFlippedDuplicate(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> originalEntry,
            boolean flipX,
            boolean flipY,
            String sampleName) throws IOException {

        if (project == null || originalEntry == null) {
            logger.error("Cannot create flipped duplicate: null project or entry");
            return null;
        }

        logger.info("Creating flipped duplicate of {} (flipX={}, flipY={})",
                originalEntry.getImageName(), flipX, flipY);

        // Load the original image data to get hierarchy and server
        ImageData<BufferedImage> originalData = originalEntry.readImageData();
        ImageServer<BufferedImage> originalServer = originalData.getServer();

        // Get image dimensions for transform calculations
        int imageWidth = originalServer.getWidth();
        int imageHeight = originalServer.getHeight();

        // Get the original image type
        ImageData.ImageType imageType = originalData.getImageType();

        // Build the transformed server using the correct transform order for TransformedServerBuilder
        // Based on QuPath forum example: https://forum.image.sc/t/flipping-an-image-in-qupaths-gui/85110
        // Order: scale first, then translate
        TransformedServerBuilder builder = new TransformedServerBuilder(originalServer);

        if (flipX && flipY) {
            // Both flips
            AffineTransform transform = new AffineTransform();
            transform.scale(-1.0, -1.0);
            transform.translate(-imageWidth, -imageHeight);
            builder = builder.transform(transform);
        } else if (flipX) {
            // Horizontal flip only
            AffineTransform transform = new AffineTransform();
            transform.scale(-1.0, 1.0);
            transform.translate(-imageWidth, 0);
            builder = builder.transform(transform);
        } else if (flipY) {
            // Vertical flip only
            AffineTransform transform = new AffineTransform();
            transform.scale(1.0, -1.0);
            transform.translate(0, -imageHeight);
            builder = builder.transform(transform);
        }

        // Build the transformed server
        ImageServer<BufferedImage> flippedServer = builder.build();

        // Add the flipped server to the project
        ProjectImageEntry<BufferedImage> flippedEntry = project.addImage(flippedServer.getBuilder());

        // Set name to indicate it's flipped
        String baseName = originalEntry.getImageName();
        String flippedName;
        if (flipX && flipY) {
            flippedName = baseName + " (flipped XY)";
        } else if (flipX) {
            flippedName = baseName + " (flipped X)";
        } else if (flipY) {
            flippedName = baseName + " (flipped Y)";
        } else {
            flippedName = baseName + " (duplicate)";
        }
        flippedEntry.setImageName(flippedName);

        // Read the flipped image data
        ImageData<BufferedImage> flippedData = flippedEntry.readImageData();

        // Set the image type to match the original
        flippedData.setImageType(imageType);

        // CRITICAL: Verify and log pixel calibration
        // TransformedServerBuilder should preserve calibration, but let's verify
        double originalPixelSize = originalServer.getPixelCalibration().getAveragedPixelSizeMicrons();
        double flippedPixelSize = flippedServer.getPixelCalibration().getAveragedPixelSizeMicrons();

        logger.info("Pixel calibration check:");
        logger.info("  Original: {} µm/pixel", originalPixelSize);
        logger.info("  Flipped:  {} µm/pixel", flippedPixelSize);

        if (Math.abs(originalPixelSize - flippedPixelSize) > 0.001) {
            logger.warn("CRITICAL: Pixel calibration mismatch detected!");
            logger.warn("  This will cause incorrect tile sizes during acquisition");
            logger.warn("  Original={} µm/px, Flipped={} µm/px", originalPixelSize, flippedPixelSize);
        }

        // Get hierarchies for transformation
        PathObjectHierarchy originalHierarchy = originalData.getHierarchy();
        PathObjectHierarchy flippedHierarchy = flippedData.getHierarchy();

        // Transform hierarchy to account for flips
        // Use the ORIGINAL (unflipped) server dimensions for transformation
        TransformationFunctions.transformHierarchy(
                originalHierarchy,
                flippedHierarchy,
                flipX,
                flipY,
                imageWidth,
                imageHeight
        );

        // Ensure original entry has base_image set before we inherit from it
        // This ensures both original and flipped entries share the same base_image
        Map<String, String> originalMetadata = originalEntry.getMetadata();
        if (originalMetadata.get(ImageMetadataManager.BASE_IMAGE) == null) {
            String baseImage = qupath.lib.common.GeneralTools.stripExtension(originalEntry.getImageName());
            originalMetadata.put(ImageMetadataManager.BASE_IMAGE, baseImage);
            logger.info("Set base_image='{}' on original entry: {}", baseImage, originalEntry.getImageName());
        }

        // Apply metadata - get offsets from original
        double[] offsets = ImageMetadataManager.getXYOffset(originalEntry);
        ImageMetadataManager.applyImageMetadata(
                flippedEntry,
                originalEntry, // Use original as parent to inherit collection and base_image
                offsets[0], offsets[1],
                true, // Mark as flipped
                sampleName
        );

        // Save the flipped image data to persist the hierarchy and image type
        flippedEntry.saveImageData(flippedData);
        logger.info("Saved flipped image data with transformed hierarchy and image type");

        // Save project changes
        project.syncChanges();
        logger.info("Synced project changes for flipped duplicate");

        logger.info("Successfully created flipped duplicate: {}", flippedName);
        return flippedEntry;
    }

    /**
     * Determines the appropriate image type for a given image file.
     *
     * <p>This method first checks for specific filename patterns that indicate
     * PPM (Polarized Light Microscopy) or Brightfield images, which should always
     * be set as BRIGHTFIELD_H_E type. If no specific patterns are found, it falls
     * back to QuPath's automatic image type estimation.</p>
     *
     * <p>Filename patterns that force BRIGHTFIELD_H_E type:</p>
     * <ul>
     *   <li>Contains "ppm" (case-insensitive) - Polarized light microscopy</li>
     *   <li>Contains "_90" or "90." - 90-degree rotation brightfield</li>
     *   <li>Contains "_bf" - Brightfield designation</li>
     *   <li>Contains "brightfield" - Explicit brightfield naming</li>
     * </ul>
     *
     * <p>Filename patterns that force OTHER type:</p>
     * <ul>
     *   <li>Contains "biref" - Birefringence images (16-bit single-channel)</li>
     * </ul>
     *
     * @param imageFile The image file to check
     * @param server The image server for automatic type estimation
     * @param imageData The image data for automatic type estimation
     * @return The determined ImageType
     */
    private static ImageData.ImageType determineImageType(
            File imageFile,
            ImageServer<BufferedImage> server,
            ImageData<BufferedImage> imageData,
            qupath.ext.qpsc.modality.ModalityHandler modalityHandler) {

        String fileName = imageFile.getName().toLowerCase();
        String filePath = imageFile.getAbsolutePath().toLowerCase();

        // Check for birefringence images first (16-bit single-channel)
        // These need to be OTHER type, not BRIGHTFIELD_H_E
        if (fileName.contains("biref") || filePath.contains(".biref")) {
            logger.info("Setting image type to OTHER for birefringence image: {}", fileName);
            return ImageData.ImageType.OTHER;
        }

        // First check if modality handler specifies a preferred image type
        if (modalityHandler != null) {
            java.util.Optional<ImageData.ImageType> modalityType = modalityHandler.getImageType();
            if (modalityType.isPresent()) {
                logger.info("Using modality-specified image type {} for: {} (modality={})",
                        modalityType.get(), fileName, modalityHandler.getClass().getSimpleName());
                return modalityType.get();
            }
        }

        // Fallback: Check if this is a PPM or BF modality based on the filename
        // PPM files typically contain "ppm" in the name
        // BF (brightfield) files typically contain "bf" or "90" (90 degree angle)
        if (fileName.contains("ppm") ||
                fileName.contains("_90") ||
                fileName.contains("90.") ||
                fileName.contains("_bf") ||
                fileName.contains("brightfield")) {

            // Force brightfield H&E for PPM and BF modalities
            logger.info("Setting image type to BRIGHTFIELD_H_E based on filename pattern: {}", fileName);
            return ImageData.ImageType.BRIGHTFIELD_H_E;
        }

        // For other modalities, use the standard estimation
        logger.debug("Using automatic image type estimation for: {}", fileName);
        var regionStore = QPEx.getQuPath().getImageRegionStore();
        var thumb = regionStore.getThumbnail(server, 0, 0, true);
        var imageType = GuiTools.estimateImageType(server, thumb);
        logger.info("Auto-detected image type as {} for: {}", imageType, fileName);

        return imageType;
    }

    /**
     * Adds an image file to the specified QuPath project, with optional horizontal and vertical flipping.
     *
     * <p>This method handles two scenarios:</p>
     * <ol>
     *   <li><b>No flipping required:</b> The image is added directly to the project using its original
     *       ImageServerBuilder. This preserves all associated images (e.g., macro/label images) that
     *       may be embedded in the file.</li>
     *   <li><b>Flipping required:</b> A TransformedServerBuilder is used to apply the necessary
     *       affine transformations. However, this approach cannot preserve associated images due to
     *       limitations in how QuPath handles transformed servers.</li>
     * </ol>
     *
     * <p><b>Important Note on Associated Images:</b> When flipping is applied, any associated images
     * (such as macro overview images commonly found in whole slide images) will be lost. This is a
     * known limitation of using TransformedServerBuilder in QuPath.</p>
     *
     * <p><b>Coordinate System:</b> The flipping transformations assume a standard image coordinate
     * system where (0,0) is at the top-left corner, X increases to the right, and Y increases
     * downward.</p>
     *
     * @param imageFile The image file to add to the project. Must be a valid image file that
     *                  QuPath can read (e.g., TIFF, OME-TIFF, SVS, etc.).
     * @param project The QuPath project to which the image will be added. Must not be null.
     * @param isSlideFlippedX If true, the image will be flipped horizontally (mirrored around the Y-axis).
     * @param isSlideFlippedY If true, the image will be flipped vertically (mirrored around the X-axis).
     * @return true if the image was successfully added to the project, false if the project was null.
     * @throws IOException If there's an error reading the image file or adding it to the project.
     *
     * @see qupath.lib.images.servers.TransformedServerBuilder
     * @see java.awt.geom.AffineTransform
     */
    public static boolean addImageToProject(
            File imageFile,
            Project<BufferedImage> project,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY,
            qupath.ext.qpsc.modality.ModalityHandler modalityHandler) throws IOException {

        // Validate project parameter
        if (project == null) {
            logger.warn("Cannot add image: project is null");
            return false;
        }

        logger.info("Adding image to project: {} (flipX={}, flipY={})",
                imageFile.getName(), isSlideFlippedX, isSlideFlippedY);

        // Build an ImageServer for the image file
        String imageUri = imageFile.toURI().toString();
        ImageServer<BufferedImage> server = ImageServers.buildServer(imageUri);

        // Check if we need to apply any transformations
        if (!isSlideFlippedX && !isSlideFlippedY) {
            // === PATH 1: No transformations needed ===
            // This is the preferred path as it preserves all image metadata and associated images
            logger.info("No flips needed, adding image directly to preserve associated images");

            // Add the image using its original builder
            ProjectImageEntry<BufferedImage> entry = project.addImage(server.getBuilder());

            // Read the image data and set up basic properties
            ImageData<BufferedImage> imageData = entry.readImageData();

            // Determine and set the image type using our unified method
            var imageType = determineImageType(imageFile, server, imageData, modalityHandler);
            imageData.setImageType(imageType);

            // Set a user-friendly name for the image in the project
            entry.setImageName(imageFile.getName());

            // Save the image data to persist the image type setting
            entry.saveImageData(imageData);

            // Sync changes
            project.syncChanges();

            logger.info("Successfully added image to project with all associated images");
            return true;
        }

        // === PATH 2: Transformations needed ===
        // We need to flip the image, which requires creating a transformed server
        logger.warn("Applying flips to image - associated images (macro) will not be preserved in the project");

        // Create an affine transformation for the flipping
        AffineTransform transform = new AffineTransform();

        // Calculate scale factors:
        // - For flipping horizontally (around Y-axis): scale X by -1
        // - For flipping vertically (around X-axis): scale Y by -1
        // - No flipping: scale by 1 (identity)
        double scaleX = isSlideFlippedX ? -1 : 1;
        double scaleY = isSlideFlippedY ? -1 : 1;

        // Apply the scaling transformation
        // This creates a flip around the origin (0,0)
        transform.scale(scaleX, scaleY);

        // CRITICAL: After flipping, we need to translate the image back into view
        // When we flip horizontally (scaleX = -1), the image moves to negative X coordinates
        // We must translate by the full width to bring it back to positive coordinates
        if (isSlideFlippedX) {
            transform.translate(-server.getWidth(), 0);
        }

        // Similarly for vertical flipping, translate by the full height
        if (isSlideFlippedY) {
            transform.translate(0, -server.getHeight());
        }

        // Create a transformed server that applies our affine transformation
        ImageServer<BufferedImage> flipped = new TransformedServerBuilder(server)
                .transform(transform)
                .build();

        // Add the transformed server to the project
        ProjectImageEntry<BufferedImage> entry = project.addImage(flipped.getBuilder());

        // Set up the image data
        ImageData<BufferedImage> imageData = entry.readImageData();

        // Determine and set the image type using our unified method
        var imageType = determineImageType(imageFile, flipped, imageData, modalityHandler);
        imageData.setImageType(imageType);

        entry.setImageName(imageFile.getName());

        // Save the image data to persist the image type setting
        entry.saveImageData(imageData);

        // Save the changes
        project.syncChanges();

        logger.info("Successfully added flipped image to project (associated images not preserved)");
        return true;
    }

    /**
     * Creates (or loads) a QuPath project in the folder:
     *   {projectsFolderPath}/{sampleLabel}
     * and ensures a "SlideImages" subfolder exists.
     */
    public static Project<BufferedImage> createProject(String projectsFolderPath,
                                                       String sampleLabel) {
        // Resolve the three directories we need
        Path rootPath        = Paths.get(projectsFolderPath);
        Path sampleDir       = rootPath.resolve(sampleLabel);
        Path slideImagesDir  = sampleDir.resolve("SlideImages");

        // 1) Ensure all directories exist (creates parents if needed)
        try {
            Files.createDirectories(slideImagesDir);
        } catch (IOException e) {
            Dialogs.showErrorNotification(
                    "Error creating directories",
                    "Could not create project folders under:\n  " + projectsFolderPath +
                            "\nCause: " + e.getMessage()
            );
            return null;
        }

        // 2) Look for existing QuPath project files (.qpproj) in sampleDir
        File[] qpprojFiles = sampleDir.toFile().listFiles((dir, name) -> name.endsWith(".qpproj"));
        Project<BufferedImage> project;

        try {
            if (qpprojFiles == null || qpprojFiles.length == 0) {
                // No project exists yet → create a new one
                logger.info("Creating new project in: {}", sampleDir);
                project = Projects.createProject(sampleDir.toFile(), BufferedImage.class);
            } else {
                if (qpprojFiles.length > 1) {
                    // Warn if multiple projects found; we'll load the first
                    Dialogs.showErrorNotification(
                            "Warning: Multiple project files",
                            "Found " + qpprojFiles.length + " .qpproj files in:\n  " +
                                    sampleDir + "\nLoading the first: " + qpprojFiles[0].getName()
                    );
                }
                // Load the first existing project
                logger.info("Loading existing project: {}", qpprojFiles[0].getName());
                project = ProjectIO.loadProject(qpprojFiles[0], BufferedImage.class);
            }
        } catch (IOException e) {
            Dialogs.showErrorNotification(
                    "Error opening project",
                    "Failed to create or load project in:\n  " + sampleDir +
                            "\nCause: " + e.getMessage()
            );
            return null;
        }

        return project;
    }

    public static void onImageLoadedInViewer(QuPathGUI qupathGUI, String expectedImagePath, Runnable onLoaded) {
        ChangeListener<ImageData<?>> listener = new ChangeListener<ImageData<?>>() {
            @Override
            public void changed(ObservableValue<? extends ImageData<?>> obs, ImageData<?> oldImage, ImageData<?> newImage) {
                if (newImage == null) return;
                String serverPath = newImage.getServer().getPath();
                if (serverPath != null && serverPath.contains(expectedImagePath)) {
                    qupathGUI.getViewer().imageDataProperty().removeListener(this);
                    onLoaded.run();
                }
            }
        };
        qupathGUI.getViewer().imageDataProperty().addListener(listener);
    }

    /** Saves the current ImageData into its ProjectImageEntry. */
    public static void saveCurrentImageData() throws IOException {
        ProjectImageEntry<BufferedImage> entry = QP.getProjectEntry();
        if (entry != null && QP.getCurrentImageData() != null) {
            entry.saveImageData(QP.getCurrentImageData());
            logger.info("Saved current image data to project entry");
        }
    }

}