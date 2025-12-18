package qupath.ext.qpsc.ui.stagemap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Data model representing a stage insert configuration with its physical dimensions
 * and slide positions.
 * <p>
 * Stage inserts hold slides in specific positions. Different insert types support
 * different numbers of slides in various orientations (e.g., single slide horizontal,
 * four slides vertical).
 * <p>
 * All dimensions are stored internally in microns (um) for consistency with
 * microscope stage coordinates.
 */
public class StageInsert {

    /** Conversion factor from millimeters to microns */
    private static final double MM_TO_UM = 1000.0;

    private final String id;
    private final String name;
    private final double widthUm;
    private final double heightUm;
    private final double originXUm;
    private final double originYUm;
    private final double slideMarginUm;
    private final List<SlidePosition> slides;

    // Axis inversion flags (detected from calibration points)
    // When true, larger stage values correspond to visually "left" or "top"
    private boolean xAxisInverted = false;
    private boolean yAxisInverted = false;

    /**
     * Creates a new StageInsert with the specified parameters.
     *
     * @param id            Unique identifier (e.g., "single_h", "quad_v")
     * @param name          Display name (e.g., "Single Slide (Horizontal)")
     * @param widthMm       Insert width in millimeters
     * @param heightMm      Insert height in millimeters
     * @param originXUm     X coordinate of insert corner in stage coordinates (um)
     * @param originYUm     Y coordinate of insert corner in stage coordinates (um)
     * @param slideMarginUm Safety margin around slides for legal zone (um)
     * @param slides        List of slide positions within the insert
     */
    public StageInsert(String id, String name, double widthMm, double heightMm,
                       double originXUm, double originYUm, double slideMarginUm,
                       List<SlidePosition> slides) {
        this.id = id;
        this.name = name;
        this.widthUm = widthMm * MM_TO_UM;
        this.heightUm = heightMm * MM_TO_UM;
        this.originXUm = originXUm;
        this.originYUm = originYUm;
        this.slideMarginUm = slideMarginUm;
        this.slides = slides != null ? new ArrayList<>(slides) : new ArrayList<>();
    }

    /**
     * Creates a StageInsert from a YAML configuration map using reference point calibration.
     * <p>
     * The configuration uses 4 calibration reference points:
     * <ul>
     *   <li>aperture_left_x_um: Stage X when left aperture edge is centered in FOV</li>
     *   <li>aperture_right_x_um: Stage X when right aperture edge is centered in FOV</li>
     *   <li>slide_top_y_um: Stage Y when top edge of slide is centered in FOV</li>
     *   <li>slide_bottom_y_um: Stage Y when bottom edge of slide is centered in FOV</li>
     * </ul>
     * <p>
     * The aperture width is calculated from the X coordinates.
     * The slide is assumed to be centered vertically within the aperture.
     * <p>
     * Note: Due to optical inversion, "left" may have a larger X value than "right",
     * and "top" may have a larger Y value than "bottom". The code handles both orientations.
     *
     * @param id            Insert identifier
     * @param configMap     Configuration map from YAML
     * @param slideMarginUm Safety margin around slides (from parent config)
     * @return A new StageInsert instance
     */
    @SuppressWarnings("unchecked")
    public static StageInsert fromConfigMap(String id, Map<String, Object> configMap,
                                            double slideMarginUm) {
        String name = (String) configMap.getOrDefault("name", id);

        // Read calibration reference points for aperture X bounds
        double apertureLeftX = getDoubleValue(configMap, "aperture_left_x_um", 0.0);
        double apertureRightX = getDoubleValue(configMap, "aperture_right_x_um", 55000.0);

        // Read calibration reference points for aperture Y bounds (if available)
        // Fall back to slide positions if aperture Y not specified
        Double apertureTopY = getDoubleValueOrNull(configMap, "aperture_top_y_um");
        Double apertureBottomY = getDoubleValueOrNull(configMap, "aperture_bottom_y_um");

        // Read slide edge positions
        double slideTopY = getDoubleValue(configMap, "slide_top_y_um", 0.0);
        double slideBottomY = getDoubleValue(configMap, "slide_bottom_y_um", 25000.0);

        // Detect axis orientation (optics may invert the coordinate system)
        // "Left" visually may correspond to larger X values if optics flip the image
        boolean xInverted = apertureLeftX > apertureRightX;
        boolean yInverted = slideTopY > slideBottomY;

        // Calculate aperture dimensions from reference points (always positive)
        double apertureWidthUm = Math.abs(apertureRightX - apertureLeftX);

        // Calculate aperture height - use actual Y coordinates if available
        double apertureHeightUm;
        double originYUm;
        if (apertureTopY != null && apertureBottomY != null) {
            // Use actual aperture Y coordinates
            apertureHeightUm = Math.abs(apertureBottomY - apertureTopY);
            // Origin is at visual top (larger Y if inverted, smaller if not)
            originYUm = yInverted ? Math.max(apertureTopY, apertureBottomY)
                                  : Math.min(apertureTopY, apertureBottomY);
        } else {
            // Fall back to estimated height from config
            double apertureHeightMm = getDoubleValue(configMap, "aperture_height_mm", 60.0);
            apertureHeightUm = apertureHeightMm * MM_TO_UM;
            // Estimate origin based on slide position (old behavior)
            double slideHeightUm = Math.abs(slideBottomY - slideTopY);
            double slideYOffsetUm = (apertureHeightUm - slideHeightUm) / 2.0;
            double slideTopStageY = yInverted ? Math.max(slideTopY, slideBottomY)
                                              : Math.min(slideTopY, slideBottomY);
            originYUm = slideTopStageY - (yInverted ? -slideYOffsetUm : slideYOffsetUm);
        }

        // Read slide dimensions
        double slideWidthMm = getDoubleValue(configMap, "slide_width_mm", 75.0);
        double slideHeightMm = getDoubleValue(configMap, "slide_height_mm", 25.0);
        double slideWidthUm = slideWidthMm * MM_TO_UM;
        double slideHeightUm = Math.abs(slideBottomY - slideTopY);  // Use measured slide height

        // Origin X is the top-left corner in VISUAL space
        // For inverted X: visual left = larger stage X, so origin = max X
        double originXUm = xInverted ? Math.max(apertureLeftX, apertureRightX)
                                     : Math.min(apertureLeftX, apertureRightX);

        // Calculate slide's position within aperture
        // Slide Y offset: distance from aperture top to slide top
        double slideTopStageY = yInverted ? Math.max(slideTopY, slideBottomY)
                                          : Math.min(slideTopY, slideBottomY);
        double slideYOffsetUm = Math.abs(slideTopStageY - originYUm);

        // Slide X offset: centered horizontally (can be negative if slide wider than aperture)
        double slideXOffsetUm = (apertureWidthUm - slideWidthUm) / 2.0;

        // Build slide positions
        List<SlidePosition> slides = new ArrayList<>();

        // Check for multi-slide configuration (quad_v style)
        int numSlides = ((Number) configMap.getOrDefault("num_slides", 1)).intValue();
        double slideSpacingMm = getDoubleValue(configMap, "slide_spacing_mm", 0.0);

        if (numSlides > 1 && slideSpacingMm > 0) {
            // Multi-slide configuration
            double slideSpacingUm = slideSpacingMm * MM_TO_UM;
            double totalWidth = (numSlides - 1) * slideSpacingUm + slideWidthUm;
            double startX = (apertureWidthUm - totalWidth) / 2.0;  // Center the group

            for (int i = 0; i < numSlides; i++) {
                double xOffset = startX + i * slideSpacingUm;
                slides.add(new SlidePosition(
                        "Slide " + (i + 1),
                        xOffset / MM_TO_UM,
                        slideYOffsetUm / MM_TO_UM,
                        slideWidthMm,
                        slideHeightUm / MM_TO_UM,
                        slideHeightMm > slideWidthMm ? 90 : 0));
            }
        } else {
            // Single slide configuration
            slides.add(new SlidePosition(
                    "Slide 1",
                    slideXOffsetUm / MM_TO_UM,
                    slideYOffsetUm / MM_TO_UM,
                    slideWidthMm,
                    slideHeightUm / MM_TO_UM,
                    0));
        }

        // Convert aperture dimensions back to mm for constructor
        double apertureWidthMm = apertureWidthUm / MM_TO_UM;
        double apertureHeightMm = apertureHeightUm / MM_TO_UM;

        // Store axis inversion flags in the insert for rendering
        StageInsert insert = new StageInsert(id, name, apertureWidthMm, apertureHeightMm,
                               originXUm, originYUm, slideMarginUm, slides);
        insert.xAxisInverted = xInverted;
        insert.yAxisInverted = yInverted;
        return insert;
    }

    private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private static Double getDoubleValueOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    // ========== Getters ==========

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Returns insert width in microns */
    public double getWidthUm() {
        return widthUm;
    }

    /** Returns insert height in microns */
    public double getHeightUm() {
        return heightUm;
    }

    /** Returns the X coordinate of the insert's top-left corner in stage coordinates (um) */
    public double getOriginXUm() {
        return originXUm;
    }

    /** Returns the Y coordinate of the insert's top-left corner in stage coordinates (um) */
    public double getOriginYUm() {
        return originYUm;
    }

    /** Returns the safety margin around slides in microns */
    public double getSlideMarginUm() {
        return slideMarginUm;
    }

    /** Returns an unmodifiable list of slide positions */
    public List<SlidePosition> getSlides() {
        return Collections.unmodifiableList(slides);
    }

    /** Returns true if X axis is inverted (larger X = visual left) */
    public boolean isXAxisInverted() {
        return xAxisInverted;
    }

    /** Returns true if Y axis is inverted (larger Y = visual top) */
    public boolean isYAxisInverted() {
        return yAxisInverted;
    }

    // ========== Coordinate Bounds ==========

    /**
     * Returns the minimum X coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMinStageX() {
        if (xAxisInverted) {
            return originXUm - widthUm;
        }
        return originXUm;
    }

    /**
     * Returns the maximum X coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMaxStageX() {
        if (xAxisInverted) {
            return originXUm;
        }
        return originXUm + widthUm;
    }

    /**
     * Returns the minimum Y coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMinStageY() {
        if (yAxisInverted) {
            return originYUm - heightUm;
        }
        return originYUm;
    }

    /**
     * Returns the maximum Y coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMaxStageY() {
        if (yAxisInverted) {
            return originYUm;
        }
        return originYUm + heightUm;
    }

    // ========== Position Validation ==========

    /**
     * Checks if a stage position is within the insert boundaries.
     * Accounts for axis inversion.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is within the insert
     */
    public boolean isPositionInInsert(double stageX, double stageY) {
        return stageX >= getMinStageX() && stageX <= getMaxStageX() &&
               stageY >= getMinStageY() && stageY <= getMaxStageY();
    }

    /**
     * Checks if a stage position is on any slide (within the slide boundaries).
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is on a slide
     */
    public boolean isPositionOnSlide(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePosition(stageX, stageY, originXUm, originYUm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a stage position is in a "legal" zone (on or near a slide).
     * The legal zone includes the slide area plus the configured safety margin.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is in a legal movement zone
     */
    public boolean isPositionLegal(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePositionWithMargin(stageX, stageY, originXUm, originYUm,
                    slideMarginUm, xAxisInverted, yAxisInverted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the slide at the specified stage position, or null if not on a slide.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return The SlidePosition at this location, or null
     */
    public SlidePosition getSlideAtPosition(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePosition(stageX, stageY, originXUm, originYUm)) {
                return slide;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }

    // ========== Inner Class: SlidePosition ==========

    /**
     * Represents a single slide position within a stage insert.
     * <p>
     * Coordinates are relative to the insert's top-left corner, not stage coordinates.
     * Standard microscope slides are 25mm x 75mm (1" x 3").
     */
    public static class SlidePosition {

        private final String name;
        private final double xOffsetUm;
        private final double yOffsetUm;
        private final double widthUm;
        private final double heightUm;
        private final double rotationDeg;

        /**
         * Creates a new slide position.
         *
         * @param name        Display name (e.g., "Slide 1")
         * @param xOffsetMm   X offset from insert origin in millimeters
         * @param yOffsetMm   Y offset from insert origin in millimeters
         * @param widthMm     Slide width in millimeters
         * @param heightMm    Slide height in millimeters
         * @param rotationDeg Rotation angle in degrees (0 = horizontal, 90 = vertical)
         */
        public SlidePosition(String name, double xOffsetMm, double yOffsetMm,
                             double widthMm, double heightMm, double rotationDeg) {
            this.name = name;
            this.xOffsetUm = xOffsetMm * MM_TO_UM;
            this.yOffsetUm = yOffsetMm * MM_TO_UM;
            this.widthUm = widthMm * MM_TO_UM;
            this.heightUm = heightMm * MM_TO_UM;
            this.rotationDeg = rotationDeg;
        }

        /**
         * Creates a SlidePosition from a YAML configuration map.
         *
         * @param configMap Configuration map from YAML
         * @return A new SlidePosition instance
         */
        public static SlidePosition fromConfigMap(Map<String, Object> configMap) {
            String name = (String) configMap.getOrDefault("name", "Slide");
            double xOffsetMm = getDoubleValue(configMap, "x_offset_mm", 0.0);
            double yOffsetMm = getDoubleValue(configMap, "y_offset_mm", 0.0);
            double widthMm = getDoubleValue(configMap, "width_mm", 75.0);
            double heightMm = getDoubleValue(configMap, "height_mm", 25.0);
            double rotationDeg = getDoubleValue(configMap, "rotation_deg", 0.0);

            return new SlidePosition(name, xOffsetMm, yOffsetMm, widthMm, heightMm, rotationDeg);
        }

        private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return defaultValue;
        }

        // ========== Getters ==========

        public String getName() {
            return name;
        }

        /** Returns X offset from insert origin in microns */
        public double getXOffsetUm() {
            return xOffsetUm;
        }

        /** Returns Y offset from insert origin in microns */
        public double getYOffsetUm() {
            return yOffsetUm;
        }

        /** Returns slide width in microns */
        public double getWidthUm() {
            return widthUm;
        }

        /** Returns slide height in microns */
        public double getHeightUm() {
            return heightUm;
        }

        /** Returns rotation in degrees (0 = horizontal, 90 = vertical) */
        public double getRotationDeg() {
            return rotationDeg;
        }

        // ========== Position Checking ==========

        /**
         * Checks if a stage position is within this slide's boundaries.
         *
         * @param stageX        Stage X coordinate (um)
         * @param stageY        Stage Y coordinate (um)
         * @param insertOriginX Insert origin X in stage coordinates (um)
         * @param insertOriginY Insert origin Y in stage coordinates (um)
         * @return true if the position is on this slide
         */
        public boolean containsStagePosition(double stageX, double stageY,
                                             double insertOriginX, double insertOriginY) {
            // Default to non-inverted for backward compatibility
            return containsStagePositionWithMargin(stageX, stageY, insertOriginX, insertOriginY,
                    0, false, false);
        }

        /**
         * Checks if a stage position is within this slide's boundaries plus a margin.
         * Handles axis inversion when optics flip the coordinate system.
         *
         * @param stageX        Stage X coordinate (um)
         * @param stageY        Stage Y coordinate (um)
         * @param insertOriginX Insert origin X in stage coordinates (um)
         * @param insertOriginY Insert origin Y in stage coordinates (um)
         * @param marginUm      Additional margin around the slide (um)
         * @param xInverted     True if X axis is inverted (origin is max X)
         * @param yInverted     True if Y axis is inverted (origin is max Y)
         * @return true if the position is within the slide plus margin
         */
        public boolean containsStagePositionWithMargin(double stageX, double stageY,
                                                       double insertOriginX, double insertOriginY,
                                                       double marginUm,
                                                       boolean xInverted, boolean yInverted) {
            double slideMinX, slideMaxX, slideMinY, slideMaxY;

            if (xInverted) {
                // Origin is at max X, slide extends in negative direction
                slideMaxX = insertOriginX - xOffsetUm + marginUm;
                slideMinX = insertOriginX - xOffsetUm - widthUm - marginUm;
            } else {
                // Origin is at min X, slide extends in positive direction
                slideMinX = insertOriginX + xOffsetUm - marginUm;
                slideMaxX = insertOriginX + xOffsetUm + widthUm + marginUm;
            }

            if (yInverted) {
                // Origin is at max Y, slide extends in negative direction
                slideMaxY = insertOriginY - yOffsetUm + marginUm;
                slideMinY = insertOriginY - yOffsetUm - heightUm - marginUm;
            } else {
                // Origin is at min Y, slide extends in positive direction
                slideMinY = insertOriginY + yOffsetUm - marginUm;
                slideMaxY = insertOriginY + yOffsetUm + heightUm + marginUm;
            }

            return stageX >= slideMinX && stageX <= slideMaxX &&
                   stageY >= slideMinY && stageY <= slideMaxY;
        }

        /**
         * Returns the stage X coordinate of this slide's left edge.
         */
        public double getMinStageX(double insertOriginX) {
            return insertOriginX + xOffsetUm;
        }

        /**
         * Returns the stage X coordinate of this slide's right edge.
         */
        public double getMaxStageX(double insertOriginX) {
            return insertOriginX + xOffsetUm + widthUm;
        }

        /**
         * Returns the stage Y coordinate of this slide's top edge.
         */
        public double getMinStageY(double insertOriginY) {
            return insertOriginY + yOffsetUm;
        }

        /**
         * Returns the stage Y coordinate of this slide's bottom edge.
         */
        public double getMaxStageY(double insertOriginY) {
            return insertOriginY + yOffsetUm + heightUm;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
