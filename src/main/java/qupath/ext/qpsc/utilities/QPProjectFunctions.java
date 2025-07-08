package qupath.ext.qpsc.utilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URISyntaxException;

import javafx.application.Platform;
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

                // Important: When we set the project, QuPath might clear the current image
                // So we need to ensure it gets reopened after project is set
            } else {
                // Try to extract file path and add to project
                String imagePath = extractImagePath(currentImageData);

                    if (imagePath != null && new File(imagePath).exists()) {
                        logger.info("Adding new image to project: {}", imagePath);
                        matchingImage = importImageToProject(qupathGUI, project, new File(imagePath),
                                isSlideFlippedX, isSlideFlippedY);
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

        logger.info("Project setup complete. Mode: {}, Tiles dir: {}",
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
     * Import an image file to the project and open it in the GUI.
     */
    private static ProjectImageEntry<BufferedImage> importImageToProject(
            QuPathGUI qupathGUI,
            Project<BufferedImage> project,
            File imageFile,
            boolean flipX,
            boolean flipY) throws IOException {

        // Add image with flips
        addImageToProject(imageFile, project, flipX, flipY);

        // Find the newly added entry
        String baseName = imageFile.getName();
        ProjectImageEntry<BufferedImage> entry = project.getImageList().stream()
                .filter(e -> baseName.equals(e.getImageName()))
                .findFirst()
                .orElse(null);

        if (entry != null) {
            // Open the image
            qupathGUI.openImageEntry(entry);
            qupathGUI.refreshProject();
            logger.info("Opened image in GUI: {}", baseName);
        } else {
            logger.warn("Could not find newly added image in project: {}", baseName);
        }

        return entry;
    }

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
     * Adds an image file to the given project, applying flips if requested.
     * This method preserves associated images (like macro images) when adding to the project.
     */
    public static boolean addImageToProject(
            File imageFile,
            Project<BufferedImage> project,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY) throws IOException {
        if (project == null) {
            logger.warn("Cannot add image: project is null");
            return false;
        }

        logger.info("Adding image to project: {} (flipX={}, flipY={})",
                imageFile.getName(), isSlideFlippedX, isSlideFlippedY);

        String imageUri = imageFile.toURI().toString();
        ImageServer<BufferedImage> server = ImageServers.buildServer(imageUri);

        // Check if we need to apply transforms
        if (!isSlideFlippedX && !isSlideFlippedY) {
            // No transforms needed - add directly to preserve all associated images
            logger.info("No flips needed, adding image directly to preserve associated images");
            ProjectImageEntry<BufferedImage> entry = project.addImage(server.getBuilder());

            ImageData<BufferedImage> imageData = entry.readImageData();
            var regionStore = QPEx.getQuPath().getImageRegionStore();
            var thumb = regionStore.getThumbnail(imageData.getServer(), 0, 0, true);
            var imageType = GuiTools.estimateImageType(imageData.getServer(), thumb);
            imageData.setImageType(imageType);
            entry.setImageName(imageFile.getName());

            project.syncChanges();
            entry.saveImageData(imageData);

            logger.info("Successfully added image to project with all associated images");
            return true;
        }

        // If we need to flip, we have to use the TransformedServerBuilder
        // but this will lose associated images
        logger.warn("Applying flips to image - associated images (macro) will not be preserved in the project");

        AffineTransform transform = new AffineTransform();
        double scaleX = isSlideFlippedX ? -1 : 1;
        double scaleY = isSlideFlippedY ? -1 : 1;
        transform.scale(scaleX, scaleY);
        if (isSlideFlippedX) transform.translate(-server.getWidth(), 0);
        if (isSlideFlippedY) transform.translate(0, -server.getHeight());

        ImageServer<BufferedImage> flipped = new TransformedServerBuilder(server)
                .transform(transform)
                .build();
        ProjectImageEntry<BufferedImage> entry = project.addImage(flipped.getBuilder());

        ImageData<BufferedImage> imageData = entry.readImageData();
        var regionStore = QPEx.getQuPath().getImageRegionStore();
        var thumb = regionStore.getThumbnail(imageData.getServer(), 0, 0, true);
        var imageType = GuiTools.estimateImageType(imageData.getServer(), thumb);
        imageData.setImageType(imageType);
        entry.setImageName(imageFile.getName());

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