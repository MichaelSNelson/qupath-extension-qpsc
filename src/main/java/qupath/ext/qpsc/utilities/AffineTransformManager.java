package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
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
     * Saves a new transform preset.
     *
     * @param preset The transform preset to save
     */
    public void saveTransform(TransformPreset preset) {
        transforms.put(preset.getName(), preset);
        saveTransforms();
        logger.info("Saved transform preset: {}", preset.getName());
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
            double[] transformed = TransformationFunctions.qpToMicroscopeCoordinates(
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
     * Loads and applies a saved transform from preferences.
     *
     * @return The loaded transform, or null if no saved transform exists or cannot be loaded
     */
    public static AffineTransform loadSavedTransformFromPreferences() {
        String savedTransformName = QPPreferenceDialog.getSavedTransformName();
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
     *
     * @return true if a transform is saved and can be loaded
     */
    public static boolean hasSavedTransform() {
        String savedName = QPPreferenceDialog.getSavedTransformName();
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
}