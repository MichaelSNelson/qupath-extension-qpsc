package qupath.ext.qpsc.utilities;

import qupath.ext.qpsc.utilities.BoundingBox;
import qupath.lib.objects.PathObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * Request object encapsulating all parameters needed for tile generation.
 * This class follows the builder pattern to avoid method parameter explosion
 * and provides a clear API for different tiling workflows.
 *
 * <p>Either {@link #boundingBox} or {@link #annotations} must be set, but not both.
 * The presence of these fields determines the tiling strategy used:</p>
 * 
 * <ul>
 *   <li><strong>Bounding Box Workflow:</strong> Tiles a rectangular region defined by coordinates</li>
 *   <li><strong>Annotation Workflow:</strong> Tiles regions defined by QuPath annotation objects</li>
 * </ul>
 *
 * <h3>Usage Examples:</h3>
 * 
 * <p><strong>Bounding Box Tiling:</strong></p>
 * <pre>{@code
 * TilingRequest request = new TilingRequest.Builder()
 *     .outputFolder("/path/to/output")
 *     .modalityName("BF_10x_1")
 *     .frameSize(512.0, 512.0)  // microns
 *     .overlapPercent(10.0)
 *     .boundingBox(0, 0, 1000, 1000)  // microns
 *     .createDetections(true)
 *     .build();
 * }</pre>
 * 
 * <p><strong>Annotation-based Tiling:</strong></p>
 * <pre>{@code
 * List<PathObject> selectedAnnotations = qupath.getSelectedObjects()
 *     .stream()
 *     .filter(PathObject::isAnnotation)
 *     .collect(Collectors.toList());
 *     
 * TilingRequest request = new TilingRequest.Builder()
 *     .outputFolder("/path/to/output")
 *     .modalityName("FL_20x_DAPI")
 *     .frameSize(256.0, 256.0)
 *     .overlapPercent(15.0)
 *     .annotations(selectedAnnotations)
 *     .addBuffer(true)
 *     .invertAxes(false, true)  // coordinate system alignment
 *     .build();
 * }</pre>
 *
 * <p><strong>Builder Pattern Validation:</strong></p>
 * <p>The builder enforces the following constraints:</p>
 * <ul>
 *   <li>Output folder and modality name are required</li>
 *   <li>Frame dimensions must be positive values</li>
 *   <li>Overlap percentage should be between 0-100 (validated with warnings)</li>
 *   <li>Either bounding box OR annotations must be specified (mutually exclusive)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 0.2.1
 */
public class TilingRequest {
    private static final Logger logger = LoggerFactory.getLogger(TilingRequest.class);
    /** Output directory where tile configurations will be written */
    private String outputFolder;

    /** Name of the imaging modality (e.g., "BF_10x_1") */
    private String modalityName;

    /** Width of a single camera frame (units depend on context: pixels for QuPath, microns for bounding box) */
    private double frameWidth;

    /** Height of a single camera frame (units depend on context: pixels for QuPath, microns for bounding box) */
    private double frameHeight;

    /** Overlap between adjacent tiles as a percentage (0-100) */
    private double overlapPercent;

    /** Whether to invert the X-axis during tiling */
    private boolean invertX;

    /** Whether to invert the Y-axis during tiling */
    private boolean invertY;

    /** Whether to create QuPath detection objects for visualization */
    private boolean createDetections;

    /** Whether to add a buffer zone around annotation boundaries */
    private boolean addBuffer;

    /** Bounding box for rectangular region tiling (mutually exclusive with annotations) */
    private BoundingBox boundingBox;

    /** Annotation objects for region-based tiling (mutually exclusive with boundingBox) */
    private List<PathObject> annotations;

    /** Pixel size in microns for coordinate conversion (required for annotation workflows, use 1.0 for bounding box) */
    private double pixelSizeMicrons = -1.0;  // -1.0 indicates not set

    // Builder pattern implementation
    /**
     * Builder class for constructing TilingRequest instances.
     * Provides fluent API with comprehensive validation and logging.
     */
    public static class Builder {
        private static final Logger logger = LoggerFactory.getLogger(Builder.class);
        private final TilingRequest request = new TilingRequest();

        /**
         * Sets the output directory where tile configurations will be written.
         * 
         * @param folder the output directory path, must not be null or empty
         * @return this builder instance for method chaining
         */
        public Builder outputFolder(String folder) {
            logger.debug("Setting output folder: {}", folder);
            if (folder == null || folder.trim().isEmpty()) {
                logger.warn("Output folder is null or empty - this will cause build validation to fail");
            }
            request.outputFolder = folder;
            return this;
        }

        /**
         * Sets the imaging modality name (e.g., "BF_10x_1", "FL_20x_DAPI").
         * This name is used to look up acquisition parameters from the microscope configuration.
         * 
         * @param name the modality name, must not be null or empty
         * @return this builder instance for method chaining
         */
        public Builder modalityName(String name) {
            logger.debug("Setting modality name: {}", name);
            if (name == null || name.trim().isEmpty()) {
                logger.warn("Modality name is null or empty - this will cause build validation to fail");
            }
            request.modalityName = name;
            return this;
        }

        /**
         * Sets the camera frame dimensions.
         * Units depend on workflow: pixels for annotation-based tiling (QuPath coordinates),
         * microns for bounding box-based tiling (physical stage coordinates).
         * Use pixelSizeMicrons() to specify conversion factor for annotation workflows.
         *
         * @param width the frame width, must be positive
         * @param height the frame height, must be positive
         * @return this builder instance for method chaining
         */
        public Builder frameSize(double width, double height) {
            logger.debug("Setting frame size: {}x{}", width, height);
            if (width <= 0 || height <= 0) {
                logger.warn("Frame dimensions must be positive: width={}, height={} - this will cause build validation to fail", width, height);
            }
            request.frameWidth = width;
            request.frameHeight = height;
            return this;
        }

        /**
         * Sets the overlap between adjacent tiles as a percentage.
         * Typical values are 10-20% to ensure proper stitching.
         * 
         * @param percent the overlap percentage, should be between 0-100
         * @return this builder instance for method chaining
         */
        public Builder overlapPercent(double percent) {
            logger.debug("Setting overlap percent: {}%", percent);
            if (percent < 0 || percent > 100) {
                logger.warn("Overlap percentage should be between 0-100, got: {}%", percent);
            }
            request.overlapPercent = percent;
            return this;
        }

        /**
         * Sets axis inversion flags for coordinate system alignment.
         * Used to handle differences between QuPath and microscope coordinate systems.
         * 
         * @param x whether to invert the X-axis
         * @param y whether to invert the Y-axis
         * @return this builder instance for method chaining
         */
        public Builder invertAxes(boolean x, boolean y) {
            logger.debug("Setting axis inversion: X={}, Y={}", x, y);
            request.invertX = x;
            request.invertY = y;
            return this;
        }

        /**
         * Sets whether to create QuPath detection objects for tile visualization.
         * When enabled, rectangular detection objects are created in QuPath
         * to show the planned tile positions.
         * 
         * @param create true to create detection objects, false otherwise
         * @return this builder instance for method chaining
         */
        public Builder createDetections(boolean create) {
            logger.debug("Setting create detections: {}", create);
            request.createDetections = create;
            return this;
        }

        /**
         * Sets whether to add a buffer zone around annotation boundaries.
         * When enabled, additional tiles are generated around annotation edges
         * to ensure complete coverage of the region of interest.
         * 
         * @param buffer true to add buffer zone, false otherwise
         * @return this builder instance for method chaining
         */
        public Builder addBuffer(boolean buffer) {
            logger.debug("Setting add buffer: {}", buffer);
            request.addBuffer = buffer;
            return this;
        }

        /**
         * Sets a bounding box for rectangular region tiling.
         * Mutually exclusive with annotations - only one tiling mode can be used.
         * 
         * @param x1 left coordinate in microns
         * @param y1 top coordinate in microns
         * @param x2 right coordinate in microns
         * @param y2 bottom coordinate in microns
         * @return this builder instance for method chaining
         */
        public Builder boundingBox(double x1, double y1, double x2, double y2) {
            logger.debug("Setting bounding box: ({}, {}) to ({}, {})", x1, y1, x2, y2);
            if (request.annotations != null) {
                logger.warn("Setting bounding box when annotations are already set - this will cause build validation to fail");
            }
            request.boundingBox = new BoundingBox(x1, y1, x2, y2);
            return this;
        }

        /**
         * Sets annotation objects for region-based tiling.
         * Mutually exclusive with bounding box - only one tiling mode can be used.
         *
         * @param annotations list of QuPath annotation objects, must not be null or empty
         * @return this builder instance for method chaining
         */
        public Builder annotations(List<PathObject> annotations) {
            logger.debug("Setting annotations: {} objects", annotations != null ? annotations.size() : "null");
            if (request.boundingBox != null) {
                logger.warn("Setting annotations when bounding box is already set - this will cause build validation to fail");
            }
            if (annotations == null || annotations.isEmpty()) {
                logger.warn("Annotations list is null or empty - this will cause build validation to fail");
            }
            request.annotations = annotations;
            return this;
        }

        /**
         * Sets the pixel size for coordinate conversion between pixels and microns.
         * This is used to convert QuPath pixel coordinates to physical stage coordinates.
         *
         * @param pixelSizeMicrons the pixel size in microns, must be positive
         * @return this builder instance for method chaining
         */
        public Builder pixelSizeMicrons(double pixelSizeMicrons) {
            logger.debug("Setting pixel size: {} microns", pixelSizeMicrons);
            if (pixelSizeMicrons <= 0) {
                logger.warn("Pixel size must be positive: {} - this will cause incorrect coordinate conversion", pixelSizeMicrons);
            }
            request.pixelSizeMicrons = pixelSizeMicrons;
            return this;
        }

        /**
         * Builds the TilingRequest, validating that all required fields are set.
         * Performs comprehensive validation of all parameters and constraints.
         *
         * @return the completed TilingRequest
         * @throws IllegalStateException if required fields are missing or invalid
         */
        public TilingRequest build() {
            logger.debug("Building TilingRequest with validation");
            
            // Validate required fields
            if (request.outputFolder == null || request.modalityName == null) {
                String error = "Output folder and modality name are required";
                logger.error("Build validation failed: {}", error);
                throw new IllegalStateException(error);
            }
            
            // Validate frame dimensions
            if (request.frameWidth <= 0 || request.frameHeight <= 0) {
                String error = String.format("Frame dimensions must be positive: width=%f, height=%f", 
                    request.frameWidth, request.frameHeight);
                logger.error("Build validation failed: {}", error);
                throw new IllegalStateException(error);
            }
            
            // Validate mutually exclusive tiling modes
            if (request.hasBoundingBox() && request.hasAnnotations()) {
                String error = "Cannot specify both bounding box and annotations";
                logger.error("Build validation failed: {}", error);
                throw new IllegalStateException(error);
            }
            
            // Validate at least one tiling mode is specified
            if (!request.hasBoundingBox() && !request.hasAnnotations()) {
                String error = "Must specify either bounding box or annotations";
                logger.error("Build validation failed: {}", error);
                throw new IllegalStateException(error);
            }

            // Validate pixel size is set for annotation workflows
            if (request.hasAnnotations() && request.pixelSizeMicrons <= 0) {
                String error = "Pixel size must be set for annotation-based workflows. " +
                        "Check that the image metadata contains valid pixel calibration, " +
                        "or verify the microscope configuration file has correct pixel size settings.";
                logger.error("Build validation failed: {}", error);
                throw new IllegalStateException(error);
            }

            // For bounding box workflows, default to 1.0 if not explicitly set
            if (request.hasBoundingBox() && request.pixelSizeMicrons < 0) {
                request.pixelSizeMicrons = 1.0;
                logger.debug("Bounding box workflow: using pixelSizeMicrons=1.0 (no conversion)");
            }

            // Log successful build with summary
            String tilingMode = request.hasBoundingBox() ? "bounding box" : "annotations";
            logger.debug("Successfully built TilingRequest: modality={}, mode={}, frameSize={}x{}, overlap={}%, pixelSize={}",
                request.modalityName, tilingMode, request.frameWidth, request.frameHeight,
                request.overlapPercent, request.pixelSizeMicrons);

            return request;
        }
    }

    // Private constructor - use Builder
    private TilingRequest() {}

    // Getters only (immutable after building)
    
    /**
     * Gets the output directory where tile configurations will be written.
     * 
     * @return the output folder path
     */
    public String getOutputFolder() { return outputFolder; }
    
    /**
     * Gets the imaging modality name used for acquisition parameter lookup.
     * 
     * @return the modality name (e.g., "BF_10x_1", "FL_20x_DAPI")
     */
    public String getModalityName() { return modalityName; }
    
    /**
     * Gets the camera frame width in microns.
     * 
     * @return the frame width in microns
     */
    public double getFrameWidth() { return frameWidth; }
    
    /**
     * Gets the camera frame height in microns.
     * 
     * @return the frame height in microns
     */
    public double getFrameHeight() { return frameHeight; }
    
    /**
     * Gets the overlap percentage between adjacent tiles.
     * 
     * @return the overlap percentage (0-100)
     */
    public double getOverlapPercent() { return overlapPercent; }
    
    /**
     * Gets whether the X-axis should be inverted during tiling.
     * 
     * @return true if X-axis should be inverted, false otherwise
     */
    public boolean isInvertX() { return invertX; }
    
    /**
     * Gets whether the Y-axis should be inverted during tiling.
     * 
     * @return true if Y-axis should be inverted, false otherwise
     */
    public boolean isInvertY() { return invertY; }
    
    /**
     * Gets whether QuPath detection objects should be created for visualization.
     * 
     * @return true if detection objects should be created, false otherwise
     */
    public boolean isCreateDetections() { return createDetections; }
    
    /**
     * Gets whether a buffer zone should be added around annotation boundaries.
     * 
     * @return true if buffer should be added, false otherwise
     */
    public boolean isAddBuffer() { return addBuffer; }
    
    /**
     * Gets the bounding box for rectangular region tiling.
     * 
     * @return the bounding box, or null if annotation-based tiling is used
     */
    public BoundingBox getBoundingBox() { return boundingBox; }
    
    /**
     * Gets the annotation objects for region-based tiling.
     *
     * @return the list of annotations, or null if bounding box tiling is used
     */
    public List<PathObject> getAnnotations() { return annotations; }

    /**
     * Gets the pixel size in microns for coordinate conversion.
     *
     * @return the pixel size in microns
     */
    public double getPixelSizeMicrons() { return pixelSizeMicrons; }

    /**
     * Checks if this request is for bounding box-based tiling.
     * This method determines the tiling strategy that should be used.
     *
     * @return true if a bounding box is specified, false otherwise
     */
    public boolean hasBoundingBox() {
        return boundingBox != null;
    }

    /**
     * Checks if this request is for annotation-based tiling.
     * This method determines the tiling strategy that should be used.
     *
     * @return true if annotations are specified and non-empty, false otherwise
     */
    public boolean hasAnnotations() {
        return annotations != null && !annotations.isEmpty();
    }
}