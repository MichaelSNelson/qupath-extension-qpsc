package qupath.ext.qpsc.utilities;

/**
 * Represents a rectangular bounding box defined by two corner points.
 * 
 * <p>This immutable data class is central to QPSC's coordinate-based acquisition workflows,
 * providing a simple container for rectangular regions that need to be imaged. The class
 * handles coordinate normalization internally, ensuring that geometric calculations work
 * correctly regardless of the order in which corner points are specified.</p>
 * 
 * <h3>Coordinate System Assumptions</h3>
 * <p>BoundingBox coordinates are assumed to be in the same coordinate system as the context
 * in which they are used:</p>
 * <ul>
 *   <li><strong>Bounding Box Workflow:</strong> Coordinates in physical micrometers for stage positioning</li>
 *   <li><strong>Tiling Operations:</strong> Coordinates in the coordinate system of the target image</li>
 *   <li><strong>UI Input:</strong> Raw coordinates from user input, validated by calling workflows</li>
 * </ul>
 * 
 * <h3>Usage Examples</h3>
 * 
 * <p><strong>Basic Construction and Access:</strong></p>
 * <pre>{@code
 * // Create bounding box from corner coordinates (order doesn't matter)
 * BoundingBox bbox = new BoundingBox(100.0, 200.0, 500.0, 800.0);
 * 
 * // Access normalized bounds
 * double left = bbox.getMinX();    // 100.0 - leftmost coordinate
 * double right = bbox.getMaxX();   // 500.0 - rightmost coordinate  
 * double top = bbox.getMinY();     // 200.0 - topmost coordinate
 * double bottom = bbox.getMaxY();  // 800.0 - bottommost coordinate
 * 
 * // Get dimensions
 * double width = bbox.getWidth();   // 400.0 - absolute width
 * double height = bbox.getHeight(); // 600.0 - absolute height
 * }</pre>
 * 
 * <p><strong>Integration with Tiling Workflow:</strong></p>
 * <pre>{@code
 * // User specifies region in micrometers via UI
 * BoundingBox acquisitionRegion = new BoundingBox(0, 0, 2000, 1500);
 * 
 * // Create tiling request for microscope acquisition
 * TilingRequest request = new TilingRequest.Builder()
 *     .outputFolder("/path/to/acquisition")
 *     .modalityName("BF_10x_1")
 *     .frameSize(512.0, 512.0)  // Field of view in microns
 *     .overlapPercent(10.0)
 *     .boundingBox(acquisitionRegion.getMinX(), acquisitionRegion.getMinY(), 
 *                  acquisitionRegion.getMaxX(), acquisitionRegion.getMaxY())
 *     .build();
 * 
 * // Generate tile grid covering the bounding box
 * TilingUtilities.createTiles(request);
 * }</pre>
 * 
 * <p><strong>Coordinate System Integration:</strong></p>
 * <pre>{@code
 * // BoundingBox works with coordinate transformations
 * BoundingBox pixelBounds = new BoundingBox(1000, 2000, 3000, 4000);
 * 
 * // Transform corners to stage coordinates for microscope control
 * AffineTransform pixelToStage = getPixelToStageTransform();
 * double[] topLeft = {pixelBounds.getMinX(), pixelBounds.getMinY()};
 * double[] bottomRight = {pixelBounds.getMaxX(), pixelBounds.getMaxY()};
 * 
 * double[] stageTopLeft = TransformationFunctions.transformQuPathFullResToStage(
 *     topLeft, pixelToStage);
 * double[] stageBottomRight = TransformationFunctions.transformQuPathFullResToStage(
 *     bottomRight, pixelToStage);
 * 
 * // Create stage-coordinate bounding box for acquisition
 * BoundingBox stageBounds = new BoundingBox(
 *     stageTopLeft[0], stageTopLeft[1],
 *     stageBottomRight[0], stageBottomRight[1]);
 * }</pre>
 * 
 * <p><strong>Validation and Edge Cases:</strong></p>
 * <pre>{@code
 * // BoundingBox handles inverted coordinates automatically
 * BoundingBox reversed = new BoundingBox(100, 100, 50, 50);
 * assert reversed.getMinX() == 50.0;  // Correctly normalized
 * assert reversed.getMaxX() == 100.0;
 * 
 * // Zero-area bounding boxes are valid (single point or line)
 * BoundingBox point = new BoundingBox(100, 100, 100, 100);
 * assert point.getWidth() == 0.0;
 * assert point.getHeight() == 0.0;
 * 
 * // Validate reasonable bounds before expensive operations
 * if (bbox.getWidth() < 10.0 || bbox.getHeight() < 10.0) {
 *     logger.warn("Bounding box very small: {}x{} - check coordinate system", 
 *                 bbox.getWidth(), bbox.getHeight());
 * }
 * }</pre>
 * 
 * <p><strong>Integration with TilingUtilities:</strong></p>
 * <pre>{@code
 * // TilingUtilities automatically expands bounding box for full coverage
 * BoundingBox userRegion = new BoundingBox(0, 0, 1000, 1000);
 * double frameWidth = 200.0;
 * double frameHeight = 200.0;
 * 
 * // Internal expansion for tile positioning (done automatically)
 * double startX = userRegion.getMinX() - frameWidth / 2.0;   // -100.0
 * double endX = userRegion.getMaxX() + frameWidth / 2.0;     // 1100.0
 * double startY = userRegion.getMinY() - frameHeight / 2.0;  // -100.0  
 * double endY = userRegion.getMaxY() + frameHeight / 2.0;    // 1100.0
 * 
 * // Results in 6x6 tile grid with 10% overlap covering expanded region
 * }</pre>
 * 
 * <h3>Coordinate System Constraints</h3>
 * <ul>
 *   <li><strong>No validation:</strong> BoundingBox does not validate coordinate ranges or units</li>
 *   <li><strong>Coordinate system agnostic:</strong> Works with pixels, micrometers, or any units</li>
 *   <li><strong>Immutable:</strong> All coordinate values are final and cannot be modified</li>
 *   <li><strong>Order independent:</strong> Corner points can be specified in any order</li>
 * </ul>
 * 
 * @since 0.2.1
 */
public class BoundingBox {
    private final double x1;
    private final double y1;
    private final double x2;
    private final double y2;

    /**
     * Creates a new bounding box from two corner points.
     * 
     * <p>The corner points can be specified in any order (e.g., top-left and bottom-right,
     * or bottom-left and top-right). The class automatically normalizes coordinates using
     * the {@link #getMinX()}, {@link #getMaxX()}, {@link #getMinY()}, and {@link #getMaxY()}
     * methods to ensure consistent geometric operations.</p>
     * 
     * <p><strong>Coordinate System Note:</strong> This constructor does not validate coordinates
     * or perform unit conversions. Ensure that all coordinates are in the same coordinate system
     * and units appropriate for your workflow context.</p>
     *
     * @param x1 X-coordinate of first corner (any numeric value)
     * @param y1 Y-coordinate of first corner (any numeric value)  
     * @param x2 X-coordinate of second corner (any numeric value)
     * @param y2 Y-coordinate of second corner (any numeric value)
     */
    public BoundingBox(double x1, double y1, double x2, double y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    /**
     * Gets the X-coordinate of the first corner point as specified in the constructor.
     * 
     * <p><strong>Note:</strong> This may not be the minimum X coordinate. Use {@link #getMinX()}
     * for the leftmost coordinate of the bounding box.</p>
     *
     * @return the X-coordinate of the first corner point
     */
    public double getX1() { return x1; }

    /**
     * Gets the Y-coordinate of the first corner point as specified in the constructor.
     * 
     * <p><strong>Note:</strong> This may not be the minimum Y coordinate. Use {@link #getMinY()}
     * for the topmost coordinate of the bounding box.</p>
     *
     * @return the Y-coordinate of the first corner point
     */
    public double getY1() { return y1; }

    /**
     * Gets the X-coordinate of the second corner point as specified in the constructor.
     * 
     * <p><strong>Note:</strong> This may not be the maximum X coordinate. Use {@link #getMaxX()}
     * for the rightmost coordinate of the bounding box.</p>
     *
     * @return the X-coordinate of the second corner point
     */
    public double getX2() { return x2; }

    /**
     * Gets the Y-coordinate of the second corner point as specified in the constructor.
     * 
     * <p><strong>Note:</strong> This may not be the maximum Y coordinate. Use {@link #getMaxY()}
     * for the bottommost coordinate of the bounding box.</p>
     *
     * @return the Y-coordinate of the second corner point
     */
    public double getY2() { return y2; }

    /**
     * Gets the minimum X coordinate of the bounding box.
     * 
     * <p>This method returns the leftmost coordinate regardless of the order in which
     * corner points were specified to the constructor. Essential for defining tile grids
     * and coordinate transformations in QPSC workflows.</p>
     *
     * @return the leftmost X coordinate (minimum of x1 and x2)
     */
    public double getMinX() { return Math.min(x1, x2); }

    /**
     * Gets the maximum X coordinate of the bounding box.
     * 
     * <p>This method returns the rightmost coordinate regardless of the order in which
     * corner points were specified to the constructor. Used with {@link #getMinX()}
     * to define the horizontal extent of acquisition regions.</p>
     *
     * @return the rightmost X coordinate (maximum of x1 and x2)
     */
    public double getMaxX() { return Math.max(x1, x2); }

    /**
     * Gets the minimum Y coordinate of the bounding box.
     * 
     * <p>This method returns the topmost coordinate regardless of the order in which
     * corner points were specified to the constructor. Critical for coordinate system
     * transformations where Y-axis orientation may differ between systems.</p>
     *
     * @return the topmost Y coordinate (minimum of y1 and y2)
     */
    public double getMinY() { return Math.min(y1, y2); }

    /**
     * Gets the maximum Y coordinate of the bounding box.
     * 
     * <p>This method returns the bottommost coordinate regardless of the order in which
     * corner points were specified to the constructor. Used with {@link #getMinY()}
     * to define the vertical extent of acquisition regions.</p>
     *
     * @return the bottommost Y coordinate (maximum of y1 and y2)
     */
    public double getMaxY() { return Math.max(y1, y2); }

    /**
     * Gets the width of the bounding box.
     * 
     * <p>Calculates the absolute horizontal extent of the bounding box. Essential for
     * tile grid calculations and validation of acquisition regions. Returns 0.0 for
     * zero-width regions (vertical lines or single points).</p>
     *
     * @return the absolute width (|x2 - x1|)
     */
    public double getWidth() { return Math.abs(x2 - x1); }

    /**
     * Gets the height of the bounding box.
     * 
     * <p>Calculates the absolute vertical extent of the bounding box. Used alongside
     * {@link #getWidth()} for area calculations and tile grid planning. Returns 0.0 for
     * zero-height regions (horizontal lines or single points).</p>
     *
     * @return the absolute height (|y2 - y1|)
     */
    public double getHeight() { return Math.abs(y2 - y1); }
}