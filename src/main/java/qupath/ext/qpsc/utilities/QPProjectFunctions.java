package qupath.ext.qpsc.utilities;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.collections.ObservableList;
import org.controlsfx.control.PropertySheet;
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
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 n * Collection of project-level helper functions: create/open project, add images, etc.
 n */
public class QPProjectFunctions {
    private static final Logger logger = LoggerFactory.getLogger(QPProjectFunctions.class);

    /**
     * Creates (or loads) a QuPath project, adds the current image if open, and returns key info.
     */
    public static Map<String,Object> createAndOpenQuPathProject(
            QuPathGUI qupathGUI,
            String projectsFolderPath,
            String sampleLabel,
            ObservableList<PropertySheet.Item> preferences,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY) throws IOException {

        // 1) Read the "First Scan Type" preference:
        String firstImagingMode = preferences.stream()
                .filter(item -> "First Scan Type".equals(item.getName()))
                .findFirst()
                .map(item -> item.getValue().toString())
                .orElse("");

        // 2) Create or load the project folder
        Project<BufferedImage> project = createProjectFolder(projectsFolderPath, sampleLabel);
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                projectsFolderPath + File.separator + sampleLabel + File.separator + firstImagingMode);
        String tempTileDirectory = projectsFolderPath + File.separator + sampleLabel + File.separator + imagingModeWithIndex;

        // 3) Add current image if open, find matching entry
        ProjectImageEntry<BufferedImage> matchingImage = null;
        if (QP.getCurrentImageData() != null) {
            String serverPath = QP.getCurrentImageData().getServerPath();
            String macroImagePath = MinorFunctions.extractFilePath(serverPath);
            if (macroImagePath != null) {
                addImageToProject(new File(macroImagePath), project, isSlideFlippedX, isSlideFlippedY);
                qupathGUI.setProject(project);
                assert project != null;
                List<ProjectImageEntry<BufferedImage>> images = project.getImageList();
                matchingImage = images.stream()
                        .filter(entry -> {
                            String name = new File(entry.getImageName()).getName();
                            return name.equals(new File(macroImagePath).getName());
                        })
                        .findFirst()
                        .orElse(null);

                if (matchingImage != null)
                    qupathGUI.openImageEntry(matchingImage);

                qupathGUI.refreshProject();
            }
        }

        // 4) Build and return the result map
        Map<String,Object> result = new HashMap<>();
        result.put("matchingImage",        matchingImage);
        result.put("imagingModeWithIndex", imagingModeWithIndex);
        result.put("currentQuPathProject", project);
        result.put("tempTileDirectory",    tempTileDirectory);
        return result;
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
        String imageUri = imageFile.toURI().toString();
        ImageServer<BufferedImage> original = ImageServers.buildServer(imageUri);
        AffineTransform transform = new AffineTransform();
        double scaleX = isSlideFlippedX ? -1 : 1;
        double scaleY = isSlideFlippedY ? -1 : 1;
        transform.scale(scaleX, scaleY);
        if (isSlideFlippedX) transform.translate(-original.getWidth(), 0);
        if (isSlideFlippedY) transform.translate(0, -original.getHeight());

        ImageServer<BufferedImage> flipped = new TransformedServerBuilder(original)
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
        return true;
    }

    /**
     * Creates (or loads) a QuPath project in the folder:
     *   {projectsFolderPath}/{sampleLabel}
     * and ensures a "SlideImages" subfolder exists.
     * <p>
     * - If no .qpproj file is found, a new Project<BufferedImage> is created.
     * - If one or more .qpproj files are present, the first is loaded (with a warning if >1).
     * - Any missing directories (root, sample, SlideImages) are created,
     *   and any failure to do so pops up an error dialog and returns null.
     *
     * @param projectsFolderPath  Base directory under which all samples live.
     * @param sampleLabel         Name of the sample (and hence of the subdirectory).
     * @return                    A Project<BufferedImage> instance, or null if directory creation
     *                            or project load/create fails.
     */
    public static Project<BufferedImage> createProjectFolder(String projectsFolderPath,
                                                             String sampleLabel) {
        // Resolve the three directories we need:
        //  1) rootPath
        //  2) sampleDir (rootPath/sampleLabel)
        //  3) slideImagesDir (sampleDir/SlideImages)
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
                project = Projects.createProject(sampleDir.toFile(), BufferedImage.class);
            } else {
                if (qpprojFiles.length > 1) {
                    // Warn if multiple projects found; we’ll load the first
                    Dialogs.showErrorNotification(
                            "Warning: Multiple project files",
                            "Found " + qpprojFiles.length + " .qpproj files in:\n  " +
                                    sampleDir + "\nLoading the first: " + qpprojFiles[0].getName()
                    );
                }
                // Load the first existing project
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

    /** Saves the current ImageData into its ProjectImageEntry. */
    public static void saveCurrentImageData() throws IOException {
        ProjectImageEntry<BufferedImage> entry = QP.getProjectEntry();
        entry.saveImageData(QP.getCurrentImageData());
    }
}
