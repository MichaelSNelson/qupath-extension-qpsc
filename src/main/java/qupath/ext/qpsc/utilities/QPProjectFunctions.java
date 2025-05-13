package qupath.ext.qpsc.utilities;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        ProjectSetup(String imagingModeWithIndex,
                     String tempTileDirectory) {
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
     * @param isSlideFlippedX     flip X on import?
     * @param isSlideFlippedY     flip Y on import?
     * @return a Map containing:
     *         <ul>
     *           <li>"matchingImage" → the {@code ProjectImageEntry} just added (or null)</li>
     *           <li>"imagingModeWithIndex" → the unique folder name (e.g. "4x_bf_1")</li>
     *           <li>"currentQuPathProject" → the loaded {@code Project<BufferedImage>}</li>
     *           <li>"tempTileDirectory" → full path to the imagingMode subfolder</li>
     *         </ul>
     */
    public static Map<String,Object> createAndOpenQuPathProject(
            QuPathGUI qupathGUI,
            String projectsFolderPath,
            String sampleLabel,
            String sampleModality,
            boolean isSlideFlippedX,
            boolean isSlideFlippedY) throws IOException {

        // 1) Prepare folders and preferences
        ProjectSetup setup = prepareProjectFolders(projectsFolderPath, sampleLabel, sampleModality);

        // 2) Create or load the actual QuPath project file
        Project<BufferedImage> project = createOrLoadProject(
                projectsFolderPath, sampleLabel);

        // 3) Import + open the current image, if any
        ProjectImageEntry<BufferedImage> matchingImage =
                importCurrentImage(qupathGUI, project, isSlideFlippedX, isSlideFlippedY);

        // 4) Package results
        Map<String,Object> result = new HashMap<>();
        result.put("matchingImage",        matchingImage);
        result.put("imagingModeWithIndex", setup.imagingModeWithIndex);
        result.put("currentQuPathProject", project);
        result.put("tempTileDirectory",    setup.tempTileDirectory);
        return result;
    }

    /**
     * Build a unique subfolder for this sample + modality, and compute the
     * temp–tiles directory path.
     *
     * @param projectsFolderPath  Root folder under which all samples live.
     * @param sampleLabel         Name of this sample (used as a subfolder).
     * @param modality            Imaging mode name (e.g. "4x_bf") chosen by user.
     * @return A small struct holding the chosen subfolder name and full tile path.
     */
    private static ProjectSetup prepareProjectFolders(
            String projectsFolderPath,
            String sampleLabel,
            String modality) {

        // e.g. "4x_bf" → "4x_bf_1", "4x_bf_2", ...
        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
                Paths.get(projectsFolderPath, sampleLabel, modality)
                        .toString());

        // full path: /…/projectsFolder/sampleLabel/4x_bf_1
        String tempTileDirectory = Paths.get(
                        projectsFolderPath, sampleLabel, imagingModeWithIndex)
                .toString();

        return new ProjectSetup(imagingModeWithIndex, tempTileDirectory);
    }

    /**
     * Create the .qpproj (or load it, if present).
     * Returns the Project&lt;BufferedImage&gt; instance.
     */
    private static Project<BufferedImage> createOrLoadProject(
            String projectsFolderPath,
            String sampleLabel) throws IOException {
        return createProject(projectsFolderPath, sampleLabel);
    }

    /**
     * If QuPath currently has an open image, imports it (with optional flips),
     * then sets & opens it in the GUI.  Returns the matching entry or null.
     */
    private static ProjectImageEntry<BufferedImage> importCurrentImage(
            QuPathGUI qupathGUI,
            Project<BufferedImage> project,
            boolean flipX,
            boolean flipY) throws IOException {

        if (QP.getCurrentImageData() == null) return null;

        String serverPath     = QP.getCurrentImageData().getServerPath();
        String imageFilePath  = MinorFunctions.extractFilePath(serverPath);
        if (imageFilePath == null) return null;

        // Import + flip
        addImageToProject(new File(imageFilePath), project, flipX, flipY);
        qupathGUI.setProject(project);

        // Find the entry we just added
        String baseName = new File(imageFilePath).getName();
        return project.getImageList().stream()
                .filter(e -> new File(e.getImageName()).getName().equals(baseName))
                .findFirst()
                .map(entry -> {
                    qupathGUI.openImageEntry(entry);
                    qupathGUI.refreshProject();
                    return entry;
                }).orElse(null);
    }



//    /**
//     * Create a new QuPath project (or open an existing one) under
//     *   &lt;projectsFolderPath&gt;/&lt;sampleLabel&gt;,
//     * then—for the image currently open in QuPath—add it (with optional flips)
//     * and open it in the GUI.
//     *
//     * <p><strong>Return map keys:</strong>
//     * <ul>
//     *   <li><strong>"matchingImage"</strong> → the {@code ProjectImageEntry} for the image you just added (or null)</li>
//     *   <li><strong>"imagingModeWithIndex"</strong> → folder name (e.g. "4x_bf_1") for your next-stage tiles</li>
//     *   <li><strong>"currentQuPathProject"</strong> → the loaded {@code Project<BufferedImage>}</li>
//     *   <li><strong>"tempTileDirectory"</strong> → full path to &lt;sampleLabel&gt;/&lt;imagingModeWithIndex&gt;</li>
//     * </ul>
//     *
//     * @param qupathGUI            the QuPath GUI instance (used to set/open the project)
//     * @param projectsFolderPath   absolute path to your root "projects " folder
//     * @param sampleLabel          name of the subfolder/project to create or open
//     * @param preferences          the QuPath PreferenceSheet items (to read "First Scan Type ")
//     * @param isSlideFlippedX      whether to horizontally flip the slide image on import
//     * @param isSlideFlippedY      whether to vertically flip the slide image on import
//     * @return a Map<String,Object> containing the keys described above
//     * @throws IOException if anything goes wrong creating/loading the project folder
//     */
//    public static Map<String,Object> createAndOpenQuPathProject(
//            QuPathGUI qupathGUI,
//            String projectsFolderPath,
//            String sampleLabel,
//            ObservableList<PropertySheet.Item> preferences,
//            boolean isSlideFlippedX,
//            boolean isSlideFlippedY) throws IOException {
//
//        // 1) Read "First Scan Type " from the preferences list
//        String firstImagingMode = preferences.stream()
//                .filter(item -> "First Scan Type".equals(item.getName()))
//                .findFirst()
//                .map(item -> item.getValue().toString())
//                .orElseThrow(() -> new IllegalArgumentException(
//                        "Preference "First Scan Type " not found"));
//
//        // 2) Create (or load) the QuPath project under projectsFolderPath/sampleLabel
//        Project<BufferedImage> project = QPProjectFunctions.createProjectFolder(
//                projectsFolderPath, sampleLabel);
//
//        // 2a) Make a unique subfolder name for this imaging mode (e.g. "4x_bf_1 ")
//        String imagingModeWithIndex = MinorFunctions.getUniqueFolderName(
//                projectsFolderPath + File.separator + sampleLabel + File.separator + firstImagingMode);
//
//        // 2b) Build the temp tiles folder path
//        String tempTileDirectory = projectsFolderPath
//                + File.separator + sampleLabel
//                + File.separator + imagingModeWithIndex;
//
//        // 3) If there’s a currently open image in QuPath, import it (with flips) and open its entry
//        ProjectImageEntry<BufferedImage> matchingImage = null;
//        if (QP.getCurrentImageData() != null) {
//            String serverPath      = QP.getCurrentImageData().getServerPath();
//            String macroImagePath  = MinorFunctions.extractFilePath(serverPath);
//            if (macroImagePath != null) {
//                // import into project
//                QPProjectFunctions.addImageToProject(
//                        new File(macroImagePath),
//                        project, isSlideFlippedX, isSlideFlippedY);
//
//                // set & open in the GUI
//                qupathGUI.setProject(project);
//                List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
//                File imageFile = new File(macroImagePath);
//                matchingImage = entries.stream()
//                        .filter(e -> new File(e.getImageName()).getName()
//                                .equals(imageFile.getName()))
//                        .findFirst().orElse(null);
//
//                if (matchingImage != null) {
//                    qupathGUI.openImageEntry(matchingImage);
//                }
//                qupathGUI.refreshProject();
//            }
//        }
//
//        // 4) Package everything up
//        Map<String,Object> result = new HashMap<>();
//        result.put("matchingImage",        matchingImage);
//        result.put("imagingModeWithIndex", imagingModeWithIndex);
//        result.put("currentQuPathProject", project);
//        result.put("tempTileDirectory",    tempTileDirectory);
//        return result;
//    }

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
    public static Project<BufferedImage> createProject(String projectsFolderPath,
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
