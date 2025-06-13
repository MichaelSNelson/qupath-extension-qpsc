package qupath.ext.qpsc.utilities;

import qupath.ext.qpsc.utilities.BoundingBox;
import qupath.lib.objects.PathObject;
import java.util.List;

/**
 * Request object encapsulating all parameters needed for tile generation.
 * This class follows the builder pattern to avoid method parameter explosion
 * and provides a clear API for different tiling workflows.
 *
 * <p>Either {@link #boundingBox} or {@link #annotations} must be set, but not both.
 * The presence of these fields determines the tiling strategy used.</p>
 *
 * @since 0.2.1
 */
public class TilingRequest {
    /** Output directory where tile configurations will be written */
    private String outputFolder;

    /** Name of the imaging modality (e.g., "BF_10x_1") */
    private String modalityName;

    /** Width of a single camera frame in microns */
    private double frameWidth;

    /** Height of a single camera frame in microns */
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

    // Builder pattern implementation
    public static class Builder {
        private final TilingRequest request = new TilingRequest();

        public Builder outputFolder(String folder) {
            request.outputFolder = folder;
            return this;
        }

        public Builder modalityName(String name) {
            request.modalityName = name;
            return this;
        }

        public Builder frameSize(double width, double height) {
            request.frameWidth = width;
            request.frameHeight = height;
            return this;
        }

        public Builder overlapPercent(double percent) {
            request.overlapPercent = percent;
            return this;
        }

        public Builder invertAxes(boolean x, boolean y) {
            request.invertX = x;
            request.invertY = y;
            return this;
        }

        public Builder createDetections(boolean create) {
            request.createDetections = create;
            return this;
        }

        public Builder addBuffer(boolean buffer) {
            request.addBuffer = buffer;
            return this;
        }

        public Builder boundingBox(double x1, double y1, double x2, double y2) {
            request.boundingBox = new BoundingBox(x1, y1, x2, y2);
            return this;
        }

        public Builder annotations(List<PathObject> annotations) {
            request.annotations = annotations;
            return this;
        }

        /**
         * Builds the TilingRequest, validating that all required fields are set.
         *
         * @return the completed TilingRequest
         * @throws IllegalStateException if required fields are missing or invalid
         */
        public TilingRequest build() {
            if (request.outputFolder == null || request.modalityName == null) {
                throw new IllegalStateException("Output folder and modality name are required");
            }
            if (request.frameWidth <= 0 || request.frameHeight <= 0) {
                throw new IllegalStateException("Frame dimensions must be positive");
            }
            if (request.hasBoundingBox() && request.hasAnnotations()) {
                throw new IllegalStateException("Cannot specify both bounding box and annotations");
            }
            if (!request.hasBoundingBox() && !request.hasAnnotations()) {
                throw new IllegalStateException("Must specify either bounding box or annotations");
            }
            return request;
        }
    }

    // Private constructor - use Builder
    private TilingRequest() {}

    // Getters only (immutable after building)
    public String getOutputFolder() { return outputFolder; }
    public String getModalityName() { return modalityName; }
    public double getFrameWidth() { return frameWidth; }
    public double getFrameHeight() { return frameHeight; }
    public double getOverlapPercent() { return overlapPercent; }
    public boolean isInvertX() { return invertX; }
    public boolean isInvertY() { return invertY; }
    public boolean isCreateDetections() { return createDetections; }
    public boolean isAddBuffer() { return addBuffer; }
    public BoundingBox getBoundingBox() { return boundingBox; }
    public List<PathObject> getAnnotations() { return annotations; }

    /**
     * Checks if this request is for bounding box-based tiling.
     *
     * @return true if a bounding box is specified
     */
    public boolean hasBoundingBox() {
        return boundingBox != null;
    }

    /**
     * Checks if this request is for annotation-based tiling.
     *
     * @return true if annotations are specified and non-empty
     */
    public boolean hasAnnotations() {
        return annotations != null && !annotations.isEmpty();
    }
}