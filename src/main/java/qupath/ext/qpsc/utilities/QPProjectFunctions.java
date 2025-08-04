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
     * @param sampleModality      imaging modality
     * @param isSlideFlippedX     flip X on import?
     * @param isSlideFlippedY     flip Y on import?
     * @return a Map containing project details
     */
    public static Map<String,Object> createAndOpenQuPathProject(
            QuPathGUI qupathGUI,
            String projectsFolderPath,
            String sampleLabel,
            String sampleModality,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY) throws IOException {

        logger.info("Creating/opening project: {} in {}", sampleLabel, projectsFolderPath);

        // 1) Prepare folders and preferences
        ProjectSetup setup = prepareProjectFolders(projectsFolderPath, sampleLabel, sampleModality);

        // 2) Create or load the actual QuPath project file
        Project<BufferedImage> project = createOrLoadProject(projectsFolderPath, sampleLabel);

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

                    // Add image with flips
                    if (addImageToProject(imageFile, project, isSlideFlippedX, isSlideFlippedY)) {
                        // Find the newly added entry
                        String baseName = imageFile.getName();
                        matchingImage = project.getImageList().stream()
                                .filter(e -> baseName.equals(e.getImageName()))
                                .findFirst()
                                .orElse(null);

                        if (matchingImage != null) {
                            // Open the image later, after project is set
                            logger.info("Image added successfully: {}", baseName);
                        } else {
                            logger.warn("Could not find newly added image in project: {}", baseName);
                        }
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
            String modality) {

        // e.g. "4x_bf" → "4x_bf_1", "4x_bf_2", ...
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                Paths.get(projectsFolderPath, sampleLabel, modality).toString());

        // full path: /…/projectsFolder/sampleLabel/4x_bf_1
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
     */
    public static Map<String,Object> getCurrentProjectInformation(
            String projectsFolderPath,
            String sampleLabel,
            String imagingModality) {
        Project<?> project = QP.getProject();
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                projectsFolderPath + File.separator + sampleLabel + File.separator + imagingModality);
        String tempTileDirectory = projectsFolderPath + File.separator + sampleLabel + File.separator + imagingModeWithIndex;
        ProjectImageEntry<?> matchingImage = QP.getProjectEntry();

        Map<String,Object> result = new HashMap<>();
        result.put("matchingImage", matchingImage);
        result.put("imagingModeWithIndex", imagingModeWithIndex);
        result.put("currentQuPathProject", project);
        result.put("tempTileDirectory", tempTileDirectory);
        return result;
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
            boolean isSlideFlippedY) throws IOException {

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

            // Generate a thumbnail for image type estimation
            var regionStore = QPEx.getQuPath().getImageRegionStore();
            var thumb = regionStore.getThumbnail(imageData.getServer(), 0, 0, true);

            // Estimate and set the image type (e.g., BRIGHTFIELD_H_E, FLUORESCENCE, etc.)
            var imageType = GuiTools.estimateImageType(imageData.getServer(), thumb);
            imageData.setImageType(imageType);

            // Set a user-friendly name for the image in the project
            entry.setImageName(imageFile.getName());

            // Sync changes and save the image data
            project.syncChanges();
            entry.saveImageData(imageData);

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

        // Set up the image data (same process as no-flip path)
        ImageData<BufferedImage> imageData = entry.readImageData();
        // Determine image type based on filename patterns
        var imageType = ImageData.ImageType.UNSET;
        String fileName = imageFile.getName().toLowerCase();

        // Check if this is a PPM or BF modality based on the filename
        // PPM files typically contain "ppm" in the name
        // BF (brightfield) files typically contain "bf" or "90" (90 degree angle)
        if (fileName.contains("ppm") || fileName.contains("_90") || fileName.contains("90.") ||
                fileName.contains("_bf") || fileName.contains("brightfield")) {
            // Force brightfield H&E for PPM and BF modalities
            imageType = ImageData.ImageType.BRIGHTFIELD_H_E;
            logger.info("Setting image type to BRIGHTFIELD_H_E for PPM/BF image: {}", fileName);
        } else {
            // For other modalities, use the standard estimation
            var regionStore = QPEx.getQuPath().getImageRegionStore();
            var thumb = regionStore.getThumbnail(imageData.getServer(), 0, 0, true);
            imageType = GuiTools.estimateImageType(imageData.getServer(), thumb);
            logger.info("Auto-detected image type as {} for: {}", imageType, fileName);
        }

// Set the determined image type
        imageData.setImageType(imageType);
        entry.setImageName(imageFile.getName());

        // Save the changes
        project.syncChanges();
        entry.saveImageData(imageData);

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