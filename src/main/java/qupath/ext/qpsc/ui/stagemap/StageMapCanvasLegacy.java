package qupath.ext.qpsc.ui.stagemap;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * Legacy Canvas-based implementation for rendering the stage map visualization.
 * <p>
 * <b>DEPRECATED:</b> This Canvas-based implementation has texture corruption issues
 * when MicroManager's Live Mode is toggled off. Use {@link StageMapCanvas} instead,
 * which uses WritableImage + Shapes to avoid hardware texture issues.
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
 *
 * @deprecated Use {@link StageMapCanvas} instead for better stability with MicroManager.
 */
@Deprecated
public class StageMapCanvasLegacy extends Canvas {

    private static final Logger logger = LoggerFactory.getLogger(StageMapCanvasLegacy.class);

    // ========== Colors ==========
    private static final Color INSERT_BACKGROUND = Color.rgb(60, 60, 60);
    private static final Color INSERT_BORDER = Color.rgb(100, 100, 100);
    private static final Color SLIDE_FILL = Color.rgb(200, 220, 255, 0.8);
    private static final Color SLIDE_BORDER = Color.rgb(100, 140, 200);
    private static final Color SLIDE_LABEL = Color.rgb(60, 80, 120);
    private static final Color LEGAL_ZONE = Color.rgb(100, 200, 100, 0.15);
    private static final Color ILLEGAL_ZONE = Color.rgb(200, 100, 100, 0.15);
    private static final Color CROSSHAIR_COLOR = Color.CYAN;
    private static final Color FOV_COLOR = Color.ORANGE;
    private static final Color TARGET_COLOR = Color.rgb(0, 150, 255, 0.7);
    private static final Color OUT_OF_BOUNDS_COLOR = Color.rgb(255, 100, 100, 0.8);

    // ========== Rendering Constants ==========
    private static final double CROSSHAIR_SIZE = 12;  // pixels (radius of filled circle)
    private static final double CROSSHAIR_LINE_LENGTH = 20;  // pixels (line extending from circle)
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

    // Track previous size to avoid unnecessary recalculations
    private double lastCalculatedWidth = 0;
    private double lastCalculatedHeight = 0;
    private boolean isRecalculating = false;

    // Error suppression to prevent log spam when canvas is in bad state
    private boolean renderErrorLogged = false;
    private int renderErrorCount = 0;

    // Flag to completely disable rendering (used during dispose)
    private volatile boolean renderingEnabled = true;

    // Track if canvas texture appears corrupted (continuous render failures)
    private volatile boolean textureCorrupted = false;

    // ========== Callback ==========
    private BiConsumer<Double, Double> clickHandler;

    public StageMapCanvasLegacy() {
        this(400, 300);
    }

    public StageMapCanvasLegacy(double width, double height) {
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
     * Enables or disables canvas rendering.
     * When disabled, all render() calls are ignored. Used during window disposal
     * to prevent texture corruption from stale render requests.
     */
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
        if (!enabled) {
            logger.debug("Canvas rendering disabled");
        }
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
     * Handles axis inversion when optics flip the coordinate system.
     */
    public double[] screenToStage(double screenX, double screenY) {
        if (currentInsert == null || scale == 0) {
            return null;
        }

        double insertX = (screenX - offsetX) / scale;
        double insertY = (screenY - offsetY) / scale;

        // Handle axis inversion: when inverted, screen right = decreasing stage value
        double stageX, stageY;
        if (currentInsert.isXAxisInverted()) {
            stageX = currentInsert.getOriginXUm() - insertX;
        } else {
            stageX = currentInsert.getOriginXUm() + insertX;
        }

        if (currentInsert.isYAxisInverted()) {
            stageY = currentInsert.getOriginYUm() - insertY;
        } else {
            stageY = currentInsert.getOriginYUm() + insertY;
        }

        return new double[]{stageX, stageY};
    }

    /**
     * Converts stage coordinates to screen coordinates.
     * Handles axis inversion when optics flip the coordinate system.
     */
    public double[] stageToScreen(double stageX, double stageY) {
        if (currentInsert == null) {
            return null;
        }

        // Handle axis inversion: when inverted, decreasing stage value = screen right
        double insertX, insertY;
        if (currentInsert.isXAxisInverted()) {
            insertX = currentInsert.getOriginXUm() - stageX;
        } else {
            insertX = stageX - currentInsert.getOriginXUm();
        }

        if (currentInsert.isYAxisInverted()) {
            insertY = currentInsert.getOriginYUm() - stageY;
        } else {
            insertY = stageY - currentInsert.getOriginYUm();
        }

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
        // Skip if rendering has been disabled (during dispose)
        if (!renderingEnabled) {
            return;
        }

        // Ensure we're on the FX Application Thread
        if (!Platform.isFxApplicationThread()) {
            // Check again before queueing to avoid stale requests
            if (!renderingEnabled) {
                return;
            }
            Platform.runLater(this::render);
            return;
        }

        // Guard against rendering with invalid dimensions (causes NPE in JavaFX)
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0 || !Double.isFinite(w) || !Double.isFinite(h)) {
            return;
        }

        try {
            GraphicsContext gc = getGraphicsContext2D();

            // Clear canvas
            gc.setFill(Color.rgb(40, 40, 40));
            gc.fillRect(0, 0, w, h);

            if (currentInsert == null) {
                gc.setFill(Color.GRAY);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("No insert configuration", w / 2, h / 2);
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

            // Reset error state on successful render
            if (renderErrorLogged) {
                logger.info("Canvas rendering recovered after {} errors", renderErrorCount);
                renderErrorLogged = false;
                renderErrorCount = 0;
            }
        } catch (Exception e) {
            // Suppress repeated error logging - only log first occurrence
            renderErrorCount++;
            if (!renderErrorLogged) {
                logger.warn("Canvas render error (suppressing further): {}", e.getMessage());
                renderErrorLogged = true;
            }

            // If we hit many render errors quickly, the texture is likely corrupted
            // Hide ourselves to stop JavaFX's internal render loop from spamming
            if (renderErrorCount >= 5 && !textureCorrupted) {
                textureCorrupted = true;
                logger.warn("Canvas texture appears corrupted - hiding canvas to stop render errors");
                setVisible(false);
            }
        }
    }

    /**
     * Returns true if the canvas texture appears to be corrupted.
     * This happens when MicroManager's Live Mode is toggled off, corrupting
     * shared graphics resources.
     */
    public boolean isTextureCorrupted() {
        return textureCorrupted;
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

        // Draw filled circle at center
        gc.setFill(color);
        gc.fillOval(sx - CROSSHAIR_SIZE / 2, sy - CROSSHAIR_SIZE / 2,
                    CROSSHAIR_SIZE, CROSSHAIR_SIZE);

        // Draw crosshair lines extending from circle
        gc.setStroke(color);
        gc.setLineWidth(2);

        double lineStart = CROSSHAIR_SIZE / 2 + 2;  // Start just outside the circle
        double lineEnd = lineStart + CROSSHAIR_LINE_LENGTH;

        // Horizontal lines
        gc.strokeLine(sx - lineEnd, sy, sx - lineStart, sy);
        gc.strokeLine(sx + lineStart, sy, sx + lineEnd, sy);

        // Vertical lines
        gc.strokeLine(sx, sy - lineEnd, sx, sy - lineStart);
        gc.strokeLine(sx, sy + lineStart, sx, sy + lineEnd);
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

    /**
     * Called when the canvas size changes (via property binding).
     * Recalculates scale and re-renders.
     * Includes guards against feedback loops and unnecessary recalculations.
     */
    public void onSizeChanged() {
        // Prevent re-entry during recalculation
        if (isRecalculating) {
            return;
        }

        // Only recalculate if size actually changed meaningfully
        double currentWidth = getWidth();
        double currentHeight = getHeight();
        if (Math.abs(currentWidth - lastCalculatedWidth) < 2 &&
            Math.abs(currentHeight - lastCalculatedHeight) < 2) {
            return;
        }

        isRecalculating = true;
        try {
            lastCalculatedWidth = currentWidth;
            lastCalculatedHeight = currentHeight;
            calculateScale();
            render();
        } finally {
            isRecalculating = false;
        }
    }
}
