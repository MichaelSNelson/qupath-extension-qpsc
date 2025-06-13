package qupath.ext.qpsc.utilities;

/**
 * Represents a rectangular bounding box defined by two corner points.
 * Used for defining regions of interest in tile generation workflows.
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
     * The points do not need to be in any particular order.
     *
     * @param x1 X-coordinate of first corner
     * @param y1 Y-coordinate of first corner
     * @param x2 X-coordinate of second corner
     * @param y2 Y-coordinate of second corner
     */
    public BoundingBox(double x1, double y1, double x2, double y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public double getX1() { return x1; }
    public double getY1() { return y1; }
    public double getX2() { return x2; }
    public double getY2() { return y2; }

    /**
     * Gets the minimum X coordinate of the bounding box.
     *
     * @return the leftmost X coordinate
     */
    public double getMinX() { return Math.min(x1, x2); }

    /**
     * Gets the maximum X coordinate of the bounding box.
     *
     * @return the rightmost X coordinate
     */
    public double getMaxX() { return Math.max(x1, x2); }

    /**
     * Gets the minimum Y coordinate of the bounding box.
     *
     * @return the topmost Y coordinate
     */
    public double getMinY() { return Math.min(y1, y2); }

    /**
     * Gets the maximum Y coordinate of the bounding box.
     *
     * @return the bottommost Y coordinate
     */
    public double getMaxY() { return Math.max(y1, y2); }

    /**
     * Gets the width of the bounding box.
     *
     * @return the absolute width
     */
    public double getWidth() { return Math.abs(x2 - x1); }

    /**
     * Gets the height of the bounding box.
     *
     * @return the absolute height
     */
    public double getHeight() { return Math.abs(y2 - y1); }
}