package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages saved affine transforms for different microscopes and mounting configurations.
 * Transforms are persisted as JSON and can be reused across imaging sessions.
 *
 * @since 0.3.0
 */
public class AffineTransformManager {
    private static final Logger logger = LoggerFactory.getLogger(AffineTransformManager.class);
    private static final String TRANSFORMS_FILE = "saved_transforms.json";

    private final Path transformsPath;
    private final Map<String, TransformPreset> transforms;
    private final Gson gson;

    /**
     * Creates a new transform manager, loading existing transforms from the config directory.
     *
     * @param configDirectory Directory containing the microscope configuration files
     */
    public AffineTransformManager(String configDirectory) {
        this.transformsPath = Paths.get(configDirectory, TRANSFORMS_FILE);
        this.gson = new GsonBuilder()
                .registerTypeAdapter(AffineTransform.class, new AffineTransformAdapter())
                .setPrettyPrinting()
                .create();
        this.transforms = loadTransforms();
        logger.info("Loaded {} transform presets from {}", transforms.size(), transformsPath);
    }

    /**
     * Represents a saved transform preset with metadata.
     */
    public static class TransformPreset {
        private final String name;
        private final String microscope;
        private final String mountingMethod;
        private final AffineTransform transform;
        private final Date createdDate;
        private final String notes;
        private final GreenBoxDetector.DetectionParams greenBoxParams;

        public TransformPreset(String name, String microscope, String mountingMethod,
                               AffineTransform transform, String notes, GreenBoxDetector.DetectionParams greenBoxParams) {
            this.name = name;
            this.microscope = microscope;
            this.mountingMethod = mountingMethod;
            this.transform = new AffineTransform(transform);
            this.createdDate = new Date();
            this.notes = notes;
            this.greenBoxParams = greenBoxParams;
        }
        /**
         * Backward-compatible constructor without green box parameters.
         * Uses default parameters.
         */
        public TransformPreset(String name, String microscope, String mountingMethod,
                               AffineTransform transform, String notes) {
            this(name, microscope, mountingMethod, transform, notes,
                    new GreenBoxDetector.DetectionParams());
        }

        // Getters
        public String getName() { return name; }
        public String getMicroscope() { return microscope; }
        public String getMountingMethod() { return mountingMethod; }
        public AffineTransform getTransform() { return new AffineTransform(transform); }
        public Date getCreatedDate() { return createdDate; }
        public String getNotes() { return notes; }
        public GreenBoxDetector.DetectionParams getGreenBoxParams() {
            return greenBoxParams;
        }
        @Override
        public String toString() {
            return String.format("%s (%s - %s)", name, microscope, mountingMethod);
        }
    }

    /**
     * Custom Gson adapter for AffineTransform serialization.
     */
    private static class AffineTransformAdapter
            extends com.google.gson.TypeAdapter<AffineTransform> {

        @Override
        public void write(com.google.gson.stream.JsonWriter out, AffineTransform transform)
                throws IOException {
            if (transform == null) {
                out.nullValue();
                return;
            }

            double[] matrix = new double[6];
            transform.getMatrix(matrix);

            out.beginObject();
            out.name("m00").value(matrix[0]);
            out.name("m10").value(matrix[1]);
            out.name("m01").value(matrix[2]);
            out.name("m11").value(matrix[3]);
            out.name("m02").value(matrix[4]);
            out.name("m12").value(matrix[5]);
            out.endObject();
        }

        @Override
        public AffineTransform read(com.google.gson.stream.JsonReader in)
                throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            double m00 = 0, m10 = 0, m01 = 0, m11 = 0, m02 = 0, m12 = 0;

            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "m00" -> m00 = in.nextDouble();
                    case "m10" -> m10 = in.nextDouble();
                    case "m01" -> m01 = in.nextDouble();
                    case "m11" -> m11 = in.nextDouble();
                    case "m02" -> m02 = in.nextDouble();
                    case "m12" -> m12 = in.nextDouble();
                    default -> in.skipValue();
                }
            }
            in.endObject();

            return new AffineTransform(m00, m10, m01, m11, m02, m12);
        }
    }

    /**
     * Loads transforms from the JSON file.
     */
    private Map<String, TransformPreset> loadTransforms() {
        if (!Files.exists(transformsPath)) {
            logger.info("No saved transforms file found at {}", transformsPath);
            return new HashMap<>();
        }

        try {
            String json = Files.readString(transformsPath);
            var type = new TypeToken<Map<String, TransformPreset>>(){}.getType();
            Map<String, TransformPreset> loaded = gson.fromJson(json, type);

            // Handle backward compatibility - add default green box params if missing
            if (loaded != null) {
                loaded.forEach((key, preset) -> {
                    if (preset.getGreenBoxParams() == null) {
                        logger.info("Adding default green box params to legacy transform: {}", key);
                        // This would require making the field non-final or recreating the preset
                        // For now, the Gson deserialization will handle it with defaults
                    }
                });
            }

            return loaded != null ? loaded : new HashMap<>();
        } catch (IOException e) {
            logger.error("Failed to load transforms from {}", transformsPath, e);
            return new HashMap<>();
        }
    }

    /**
     * Saves all transforms to the JSON file.
     */
    private void saveTransforms() {
        try {
            String json = gson.toJson(transforms);
            Files.writeString(transformsPath, json);
            logger.info("Saved {} transforms to {}", transforms.size(), transformsPath);
        } catch (IOException e) {
            logger.error("Failed to save transforms to {}", transformsPath, e);
        }
    }

    /**
     * Saves a transform preset to persistent storage.
     * The preset will be immediately written to the JSON file.
     *
     * @param preset The transform preset to save
     */
    public void savePreset(TransformPreset preset) {
        transforms.put(preset.getName(), preset);
        persistTransforms(); // renamed from saveTransforms
        logger.info("Saved transform preset: {}", preset.getName());
    }
    /**
     * Writes all transforms to the JSON file.
     * This is called automatically when presets are added or removed.
     */
    private void persistTransforms() {  // renamed from saveTransforms
        try {
            String json = gson.toJson(transforms);
            Files.writeString(transformsPath, json);
            logger.debug("Persisted {} transforms to {}", transforms.size(), transformsPath);
        } catch (IOException e) {
            logger.error("Failed to persist transforms to {}", transformsPath, e);
        }
    }
    /**
     * Gets a transform preset by name.
     *
     * @param name The preset name
     * @return The transform preset, or null if not found
     */
    public TransformPreset getTransform(String name) {
        return transforms.get(name);
    }

    /**
     * Gets all transform presets for a specific microscope.
     *
     * @param microscope The microscope identifier
     * @return List of matching presets
     */
    public List<TransformPreset> getTransformsForMicroscope(String microscope) {
        return transforms.values().stream()
                .filter(t -> t.getMicroscope().equals(microscope))
                .sorted(Comparator.comparing(TransformPreset::getName))
                .toList();
    }

    /**
     * Gets all available transform presets.
     *
     * @return Unmodifiable collection of all presets
     */
    public Collection<TransformPreset> getAllTransforms() {
        return Collections.unmodifiableCollection(transforms.values());
    }

    /**
     * Deletes a transform preset.
     *
     * @param name The preset name to delete
     * @return true if deleted, false if not found
     */
    public boolean deleteTransform(String name) {
        if (transforms.remove(name) != null) {
            saveTransforms();
            logger.info("Deleted transform preset: {}", name);
            return true;
        }
        return false;
    }

    /**
     * Validates a transform by checking if it produces reasonable stage coordinates
     * for a given QuPath coordinate range.
     *
     * @param transform The transform to validate
     * @param imageWidth Width of the image in pixels
     * @param imageHeight Height of the image in pixels
     * @param stageXMin Minimum expected stage X coordinate
     * @param stageXMax Maximum expected stage X coordinate
     * @param stageYMin Minimum expected stage Y coordinate
     * @param stageYMax Maximum expected stage Y coordinate
     * @return true if the transform produces coordinates within expected bounds
     */
    public static boolean validateTransform(AffineTransform transform,
                                            double imageWidth, double imageHeight,
                                            double stageXMin, double stageXMax,
                                            double stageYMin, double stageYMax) {
        // Test corners of the image
        double[][] testPoints = {
                {0, 0},
                {imageWidth, 0},
                {0, imageHeight},
                {imageWidth, imageHeight},
                {imageWidth/2, imageHeight/2}
        };

        for (double[] point : testPoints) {
            double[] transformed = TransformationFunctions.transformQuPathFullResToStage(
                    point, transform);

            if (transformed[0] < stageXMin || transformed[0] > stageXMax ||
                    transformed[1] < stageYMin || transformed[1] > stageYMax) {
                logger.warn("Transform validation failed: point ({}, {}) -> ({}, {}) " +
                                "is outside stage bounds",
                        point[0], point[1], transformed[0], transformed[1]);
                return false;
            }
        }

        logger.info("Transform validation passed for all test points");
        return true;
    }

    /**
     * Loads and applies a saved transform from preferences if available.
     * Retrieves the transform name from preferences, loads it from the transform manager,
     * and applies it to the microscope controller for immediate use.
     *
     * @return The loaded AffineTransform if successful, or null if no saved transform exists
     *         or cannot be loaded
     */
    public static AffineTransform loadSavedTransformFromPreferences() {
        String savedTransformName = PersistentPreferences.getSavedTransformName();
        if (savedTransformName == null || savedTransformName.isEmpty()) {
            logger.debug("No saved transform name in preferences");
            return null;
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            AffineTransformManager manager = new AffineTransformManager(
                    new File(configPath).getParent());

            TransformPreset savedPreset = manager.getTransform(savedTransformName);

            if (savedPreset != null) {
                logger.info("Loaded saved microscope alignment: {}", savedTransformName);
                AffineTransform transform = savedPreset.getTransform();

                // Apply it to the microscope controller
                MicroscopeController.getInstance().setCurrentTransform(transform);

                return transform;
            } else {
                logger.warn("Saved transform '{}' not found in transform manager", savedTransformName);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error loading saved transform", e);
            return null;
        }
    }

    /**
     * Checks if a valid saved transform exists in preferences.
     * Verifies both that a transform name is saved and that it can be loaded.
     *
     * @return true if a transform is saved and can be successfully loaded, false otherwise
     */
    public static boolean hasSavedTransform() {
        String savedName = PersistentPreferences.getSavedTransformName();
        if (savedName == null || savedName.isEmpty()) {
            return false;
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            AffineTransformManager manager = new AffineTransformManager(
                    new File(configPath).getParent());
            return manager.getTransform(savedName) != null;
        } catch (Exception e) {
            return false;
        }
    }
    /**
     * Saves a slide-specific alignment transform and its associated processed macro image to the project folder.
     *
     * <p>This method creates a persistent record of the alignment between macro coordinates and stage coordinates
     * for a specific slide. The alignment data is saved as a JSON file containing the transform matrix,
     * metadata, and timestamp. Additionally, the processed (flipped and cropped) macro image used for
     * the alignment is saved as a PNG file.</p>
     *
     * <p>Both files are saved in an "alignmentFiles" subdirectory within the project folder, using the
     * sample name as the base filename:</p>
     * <ul>
     *   <li>{sampleName}_alignment.json - Contains the transform and metadata</li>
     *   <li>{sampleName}_alignment.png - Contains the processed macro image</li>
     * </ul>
     *
     * <p>The JSON file structure includes:</p>
     * <pre>
     * {
     *   "sampleName": "sample identifier",
     *   "modality": "imaging modality",
     *   "timestamp": "creation timestamp",
     *   "transform": [scaleX, shearY, shearX, scaleY, translateX, translateY]
     * }
     * </pre>
     *
     * <p>The transform array represents the affine transformation matrix in the standard Java
     * AffineTransform format, mapping macro image coordinates to stage micrometers.</p>
     *
     * @param project The QuPath project containing the slide. Must not be null and must have a valid path.
     * @param sampleName The unique identifier for the sample/slide. Used as the base filename for saved files.
     *                   Must not be null or empty.
     * @param modality The imaging modality used (e.g., "Brightfield", "Polarized"). Stored as metadata
     *                 in the JSON file. May be null.
     * @param transform The affine transform mapping macro coordinates to stage coordinates. Must not be null.
     *                  The transform components are extracted and saved as a 6-element array.
     * @param processedMacroImage The flipped and cropped macro image used for alignment. If not null,
     *                            this image is saved as a PNG file alongside the alignment data.
     *                            The image should already be processed (flipped/cropped) according to
     *                            the scanner configuration.
     *
     * @throws NullPointerException if project, sampleName, or transform is null
     * @throws IllegalStateException if the project path cannot be determined
     * @throws IOException if there is an error creating directories or writing files (caught internally
     *                     and logged, does not propagate)
     *
     * @see #loadSlideAlignment(Project, String)
     * @see AffineTransform
     * @see javax.imageio.ImageIO#write(RenderedImage, String, File)
     *
     * @since 0.4.0
     */
    public static void saveSlideAlignment(
            Project<BufferedImage> project,
            String sampleName,
            String modality,
            AffineTransform transform,
            BufferedImage processedMacroImage) {  // Add this parameter

        try {
            // Get project folder
            File projectDir = project.getPath().toFile().getParentFile();

            // Create alignmentFiles directory
            File alignmentDir = new File(projectDir, "alignmentFiles");
            if (!alignmentDir.exists()) {
                alignmentDir.mkdirs();
            }

            // Create filename based on sample name
            String filename = sampleName + "_alignment.json";
            File alignmentFile = new File(alignmentDir, filename);

            // Save the transform data as JSON (existing code)
            Map<String, Object> alignmentData = new HashMap<>();
            alignmentData.put("sampleName", sampleName);
            alignmentData.put("modality", modality);
            alignmentData.put("timestamp", new Date().toString());
            alignmentData.put("transform", new double[] {
                    transform.getScaleX(),
                    transform.getShearY(),
                    transform.getShearX(),
                    transform.getScaleY(),
                    transform.getTranslateX(),
                    transform.getTranslateY()
            });

            // Convert to JSON and save
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(alignmentData);
            Files.write(alignmentFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

            logger.info("Saved slide-specific alignment to: {}", alignmentFile.getAbsolutePath());

            // Save the processed macro image if provided
            if (processedMacroImage != null) {
                String imageFilename = sampleName + "_alignment.png";
                File imageFile = new File(alignmentDir, imageFilename);
                ImageIO.write(processedMacroImage, "png", imageFile);
                logger.info("Saved processed macro image to: {}", imageFile.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.error("Failed to save slide alignment", e);
        }
    }
    public static AffineTransform loadSlideAlignment(
            Project<BufferedImage> project,
            String sampleName) {

        try {
            // Get project folder
            File projectDir = project.getPath().toFile().getParentFile();

            // Check for alignmentFiles directory
            File alignmentDir = new File(projectDir, "alignmentFiles");
            if (!alignmentDir.exists()) {
                return null;
            }

            // Look for slide-specific alignment file
            String filename = sampleName + "_alignment.json";
            File alignmentFile = new File(alignmentDir, filename);

            if (!alignmentFile.exists()) {
                logger.info("No slide-specific alignment found at: {}", alignmentFile.getAbsolutePath());
                return null;
            }

            // Read and parse the file
            String json = new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> alignmentData = new Gson().fromJson(json, mapType);

            // Extract transform values
            @SuppressWarnings("unchecked")
            List<Double> transformValues = (List<Double>) alignmentData.get("transform");

            if (transformValues != null && transformValues.size() == 6) {
                AffineTransform transform = new AffineTransform(
                        transformValues.get(0),  // m00 (scaleX)
                        transformValues.get(1),  // m10 (shearY)
                        transformValues.get(2),  // m01 (shearX)
                        transformValues.get(3),  // m11 (scaleY)
                        transformValues.get(4),  // m02 (translateX)
                        transformValues.get(5)   // m12 (translateY)
                );

                logger.info("Loaded slide-specific alignment from: {}", alignmentFile.getAbsolutePath());
                logger.info("Alignment created on: {}", alignmentData.get("timestamp"));
                if (transform != null) {
                    logger.info("CRITICAL: Loaded slide alignment with scale: X={}, Y={}",
                            transform.getScaleX(), transform.getScaleY());
                }
                return transform;
            }

        } catch (Exception e) {
            logger.error("Failed to load slide alignment", e);
        }

        return null;
    }

    /**
     * Load slide-specific alignment from a project directory without requiring an open project.
     * Useful for checking if alignment exists before project is loaded.
     *
     * @param projectDir The project directory
     * @param sampleName The sample/slide name
     * @return The slide-specific transform, or null if not found
     */
    public static AffineTransform loadSlideAlignmentFromDirectory(File projectDir, String sampleName) {
        if (projectDir == null || !projectDir.exists() || sampleName == null) {
            return null;
        }

        File alignmentDir = new File(projectDir, "alignmentFiles");
        if (!alignmentDir.exists()) {
            return null;
        }

        File alignmentFile = new File(alignmentDir, sampleName + "_alignment.json");
        if (!alignmentFile.exists()) {
            logger.debug("No slide-specific alignment found at: {}", alignmentFile.getAbsolutePath());
            return null;
        }

        try (Reader reader = new FileReader(alignmentFile)) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(AffineTransform.class, new AffineTransformAdapter())
                    .create();

            AffineTransform transform = gson.fromJson(reader, AffineTransform.class);

            if (transform != null) {
                logger.info("Loaded slide-specific alignment from: {}", alignmentFile.getAbsolutePath());
                return transform;
            }

        } catch (IOException e) {
            logger.error("Error loading slide alignment from: {}", alignmentFile, e);
        }

        return null;
    }

    /**
     * Loads the saved macro image for a specific slide alignment.
     *
     * @param project The QuPath project
     * @param sampleName The sample name
     * @return The saved macro image, or null if not found
     */
    public static BufferedImage loadSavedMacroImage(Project<BufferedImage> project, String sampleName) {
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            File alignmentDir = new File(projectDir, "alignmentFiles");

            if (!alignmentDir.exists()) {
                return null;
            }

            String imageFilename = sampleName + "_alignment.png";
            File imageFile = new File(alignmentDir, imageFilename);

            if (!imageFile.exists()) {
                logger.debug("No saved macro image found at: {}", imageFile.getAbsolutePath());
                return null;
            }

            BufferedImage macroImage = ImageIO.read(imageFile);
            logger.info("Loaded saved macro image from: {}", imageFile.getAbsolutePath());
            return macroImage;

        } catch (Exception e) {
            logger.error("Failed to load saved macro image", e);
            return null;
        }
    }

}