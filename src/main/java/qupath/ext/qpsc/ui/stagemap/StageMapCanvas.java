package qupath.ext.qpsc.ui.stagemap;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * Custom Canvas for rendering the stage map visualization.
 * <p>
 * Displays:
 * <ul>
 *   <li>Stage insert outline (dark gray rectangle)</li>
 *   <li>Slide positions (light blue rectangles)</li>
 *   <li>Legal/illegal zone overlay (green/red tint)</li>
 *   <li>Current objective position (red crosshair)</li>
 *   <li>Camera field of view (orange rectangle)</li>
 *   <li>Target position on hover (dashed crosshair)</li>
 * </ul>
 * <p>
 * Coordinate system:
 * <ul>
 *   <li>Stage coordinates: microns (um), from microscope</li>
 *   <li>Insert coordinates: relative to insert origin</li>
 *   <li>Screen coordinates: pixels, for JavaFX rendering</li>
 * </ul>
 */
public class StageMapCanvas extends Canvas {

    private static final Logger logger = LoggerFactory.getLogger(StageMapCanvas.class);

    // ========== Colors ==========
    private static final Color INSERT_BACKGROUND = Color.rgb(60, 60, 60);
    private static final Color INSERT_BORDER = Color.rgb(100, 100, 100);
    private static final Color SLIDE_FILL = Color.rgb(200, 220, 255, 0.8);
    private static final Color SLIDE_BORDER = Color.rgb(100, 140, 200);
    private static final Color SLIDE_LABEL = Color.rgb(60, 80, 120);
    private static final Color LEGAL_ZONE = Color.rgb(100, 200, 100, 0.15);
    private static final Color ILLEGAL_ZONE = Color.rgb(200, 100, 100, 0.15);
    private static final Color CROSSHAIR_COLOR = Color.RED;
    private static final Color FOV_COLOR = Color.ORANGE;
    private static final Color TARGET_COLOR = Color.rgb(0, 150, 255, 0.7);
    private static final Color OUT_OF_BOUNDS_COLOR = Color.rgb(255, 100, 100, 0.5);

    // ========== Rendering Constants ==========
    private static final double CROSSHAIR_SIZE = 15;  // pixels
    private static final double CROSSHAIR_GAP = 5;    // pixels (gap in center)
    private static final double INSERT_PADDING = 20;  // pixels padding around insert
    private static final double SLIDE_CORNER_RADIUS = 3;  // pixels

    // ========== State ==========
    private StageInsert currentInsert;
    private double currentStageX = Double.NaN;
    private double currentStageY = Double.NaN;
    private double targetStageX = Double.NaN;
    private double targetStageY = Double.NaN;
    private double fovWidthUm = 0;
    private double fovHeightUm = 0;
    private double scale = 1.0;  // pixels per micron
    private double offsetX = 0;  // canvas offset for centering
    private double offsetY = 0;
    private boolean showLegalZones = true;
    private boolean showTarget = false;

    // ========== Callback ==========
    private BiConsumer<Double, Double> clickHandler;

    public StageMapCanvas() {
        this(400, 300);
    }

    public StageMapCanvas(double width, double height) {
        super(width, height);

        // Handle mouse movement for target preview
        setOnMouseMoved(e -> {
            if (currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    targetStageX = stageCoords[0];
                    targetStageY = stageCoords[1];
                    showTarget = true;
                    render();
                }
            }
        });

        setOnMouseExited(e -> {
            showTarget = false;
            render();
        });

        // Handle double-click for movement
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && clickHandler != null && currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    clickHandler.accept(stageCoords[0], stageCoords[1]);
                }
            }
        });
    }

    /**
     * Sets the insert configuration to display.
     */
    public void setInsert(StageInsert insert) {
        this.currentInsert = insert;
        calculateScale();
        render();
    }

    /**
     * Updates the current stage position (crosshair location).
     */
    public void updatePosition(double stageX, double stageY) {
        this.currentStageX = stageX;
        this.currentStageY = stageY;
        render();
    }

    /**
     * Updates the camera field of view dimensions.
     */
    public void updateFOV(double widthUm, double heightUm) {
        this.fovWidthUm = widthUm;
        this.fovHeightUm = heightUm;
        render();
    }

    /**
     * Sets the callback for double-click events.
     * The callback receives stage coordinates (x, y) in microns.
     */
    public void setClickHandler(BiConsumer<Double, Double> handler) {
        this.clickHandler = handler;
    }

    /**
     * Sets whether to show legal/illegal zone overlay.
     */
    public void setShowLegalZones(boolean show) {
        this.showLegalZones = show;
        render();
    }

    /**
     * Returns the current target position (under mouse cursor).
     */
    public double[] getTargetPosition() {
        if (showTarget && !Double.isNaN(targetStageX)) {
            return new double[]{targetStageX, targetStageY};
        }
        return null;
    }

    /**
     * Converts screen coordinates to stage coordinates.
     */
    public double[] screenToStage(double screenX, double screenY) {
        if (currentInsert == null || scale == 0) {
            return null;
        }

        double insertX = (screenX - offsetX) / scale;
        double insertY = (screenY - offsetY) / scale;

        double stageX = currentInsert.getOriginXUm() + insertX;
        double stageY = currentInsert.getOriginYUm() + insertY;

        return new double[]{stageX, stageY};
    }

    /**
     * Converts stage coordinates to screen coordinates.
     */
    public double[] stageToScreen(double stageX, double stageY) {
        if (currentInsert == null) {
            return null;
        }

        double insertX = stageX - currentInsert.getOriginXUm();
        double insertY = stageY - currentInsert.getOriginYUm();

        double screenX = offsetX + insertX * scale;
        double screenY = offsetY + insertY * scale;

        return new double[]{screenX, screenY};
    }

    /**
     * Calculates the scale factor to fit the insert in the canvas with padding.
     */
    private void calculateScale() {
        if (currentInsert == null) {
            scale = 1.0;
            offsetX = offsetY = 0;
            return;
        }

        double availableWidth = getWidth() - 2 * INSERT_PADDING;
        double availableHeight = getHeight() - 2 * INSERT_PADDING;

        double scaleX = availableWidth / currentInsert.getWidthUm();
        double scaleY = availableHeight / currentInsert.getHeightUm();

        scale = Math.min(scaleX, scaleY);

        // Center the insert
        double renderedWidth = currentInsert.getWidthUm() * scale;
        double renderedHeight = currentInsert.getHeightUm() * scale;

        offsetX = (getWidth() - renderedWidth) / 2.0;
        offsetY = (getHeight() - renderedHeight) / 2.0;
    }

    /**
     * Renders the complete stage map visualization.
     */
    public void render() {
        GraphicsContext gc = getGraphicsContext2D();

        // Clear canvas
        gc.setFill(Color.rgb(40, 40, 40));
        gc.fillRect(0, 0, getWidth(), getHeight());

        if (currentInsert == null) {
            gc.setFill(Color.GRAY);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No insert configuration", getWidth() / 2, getHeight() / 2);
            return;
        }

        // Render layers from back to front
        renderInsertBackground(gc);
        if (showLegalZones) {
            renderLegalZones(gc);
        }
        renderSlides(gc);
        renderInsertBorder(gc);
        renderFOV(gc);
        renderCrosshair(gc);
        if (showTarget) {
            renderTarget(gc);
        }
    }

    private void renderInsertBackground(GraphicsContext gc) {
        gc.setFill(INSERT_BACKGROUND);
        gc.fillRect(offsetX, offsetY,
                currentInsert.getWidthUm() * scale,
                currentInsert.getHeightUm() * scale);
    }

    private void renderInsertBorder(GraphicsContext gc) {
        gc.setStroke(INSERT_BORDER);
        gc.setLineWidth(2);
        gc.strokeRect(offsetX, offsetY,
                currentInsert.getWidthUm() * scale,
                currentInsert.getHeightUm() * scale);
    }

    private void renderSlides(GraphicsContext gc) {
        gc.setFont(Font.font(10));

        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            double x = offsetX + slide.getXOffsetUm() * scale;
            double y = offsetY + slide.getYOffsetUm() * scale;
            double w = slide.getWidthUm() * scale;
            double h = slide.getHeightUm() * scale;

            // Fill
            gc.setFill(SLIDE_FILL);
            gc.fillRoundRect(x, y, w, h, SLIDE_CORNER_RADIUS, SLIDE_CORNER_RADIUS);

            // Border
            gc.setStroke(SLIDE_BORDER);
            gc.setLineWidth(1.5);
            gc.strokeRoundRect(x, y, w, h, SLIDE_CORNER_RADIUS, SLIDE_CORNER_RADIUS);

            // Label
            gc.setFill(SLIDE_LABEL);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(slide.getName(), x + w / 2, y + h / 2 + 4);
        }
    }

    private void renderLegalZones(GraphicsContext gc) {
        // First, fill the entire insert with "illegal" zone color
        gc.setFill(ILLEGAL_ZONE);
        gc.fillRect(offsetX, offsetY,
                currentInsert.getWidthUm() * scale,
                currentInsert.getHeightUm() * scale);

        // Then overlay "legal" zones around each slide
        gc.setFill(LEGAL_ZONE);
        double marginPx = currentInsert.getSlideMarginUm() * scale;

        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            double x = offsetX + slide.getXOffsetUm() * scale - marginPx;
            double y = offsetY + slide.getYOffsetUm() * scale - marginPx;
            double w = slide.getWidthUm() * scale + 2 * marginPx;
            double h = slide.getHeightUm() * scale + 2 * marginPx;

            gc.fillRect(x, y, w, h);
        }
    }

    private void renderCrosshair(GraphicsContext gc) {
        if (Double.isNaN(currentStageX) || Double.isNaN(currentStageY)) {
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        // Check if position is within insert bounds
        boolean inInsert = currentInsert.isPositionInInsert(currentStageX, currentStageY);
        Color color = inInsert ? CROSSHAIR_COLOR : OUT_OF_BOUNDS_COLOR;

        gc.setStroke(color);
        gc.setLineWidth(2);

        // Horizontal line with gap
        gc.strokeLine(sx - CROSSHAIR_SIZE, sy, sx - CROSSHAIR_GAP, sy);
        gc.strokeLine(sx + CROSSHAIR_GAP, sy, sx + CROSSHAIR_SIZE, sy);

        // Vertical line with gap
        gc.strokeLine(sx, sy - CROSSHAIR_SIZE, sx, sy - CROSSHAIR_GAP);
        gc.strokeLine(sx, sy + CROSSHAIR_GAP, sx, sy + CROSSHAIR_SIZE);
    }

    private void renderFOV(GraphicsContext gc) {
        if (Double.isNaN(currentStageX) || Double.isNaN(currentStageY) ||
            fovWidthUm <= 0 || fovHeightUm <= 0) {
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];
        double fovW = fovWidthUm * scale;
        double fovH = fovHeightUm * scale;

        // Draw FOV rectangle centered on crosshair
        gc.setStroke(FOV_COLOR);
        gc.setLineWidth(1.5);
        gc.strokeRect(sx - fovW / 2, sy - fovH / 2, fovW, fovH);
    }

    private void renderTarget(GraphicsContext gc) {
        if (Double.isNaN(targetStageX) || Double.isNaN(targetStageY)) {
            return;
        }

        double[] screenPos = stageToScreen(targetStageX, targetStageY);
        if (screenPos == null) {
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        // Check if target is legal
        boolean isLegal = currentInsert.isPositionLegal(targetStageX, targetStageY);
        Color color = isLegal ? TARGET_COLOR : OUT_OF_BOUNDS_COLOR;

        gc.setStroke(color);
        gc.setLineWidth(1);
        gc.setLineDashes(4, 4);

        // Dashed crosshair for target
        gc.strokeLine(sx - CROSSHAIR_SIZE, sy, sx + CROSSHAIR_SIZE, sy);
        gc.strokeLine(sx, sy - CROSSHAIR_SIZE, sx, sy + CROSSHAIR_SIZE);

        gc.setLineDashes(null);  // Reset to solid
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return getWidth();
    }

    @Override
    public double prefHeight(double width) {
        return getHeight();
    }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        calculateScale();
        render();
    }
}
