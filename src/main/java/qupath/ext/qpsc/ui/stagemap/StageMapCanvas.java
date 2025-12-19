package qupath.ext.qpsc.ui.stagemap;

import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Stage map visualization using WritableImage + Shape nodes.
 * <p>
 * This implementation avoids the hardware texture corruption issues that affect
 * Canvas-based rendering when MicroManager's Live Mode is toggled. It uses:
 * <ul>
 *   <li>WritableImage for static background elements (painted via PixelWriter)</li>
 *   <li>JavaFX Shape nodes for dynamic overlays (crosshair, FOV, target)</li>
 * </ul>
 * <p>
 * This design also supports future "live preview scan" functionality where
 * RGB color data can be progressively painted into the WritableImage.
 * <p>
 * Displays:
 * <ul>
 *   <li>Stage insert outline (dark gray rectangle)</li>
 *   <li>Slide positions (light blue rectangles)</li>
 *   <li>Legal/illegal zone overlay (green/red tint)</li>
 *   <li>Current objective position (green crosshair)</li>
 *   <li>Camera field of view (orange rectangle)</li>
 *   <li>Target position on hover (blue dashed crosshair)</li>
 * </ul>
 */
public class StageMapCanvas extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(StageMapCanvas.class);

    // ========== Colors ==========
    private static final Color BACKGROUND_COLOR = Color.rgb(40, 40, 40);
    private static final Color INSERT_BACKGROUND = Color.rgb(60, 60, 60);
    private static final Color INSERT_BORDER = Color.rgb(100, 100, 100);
    private static final Color SLIDE_FILL = Color.rgb(200, 220, 255);
    private static final Color SLIDE_BORDER = Color.rgb(100, 140, 200);
    private static final Color SLIDE_LABEL = Color.rgb(60, 80, 120);
    private static final Color LEGAL_ZONE = Color.rgb(100, 200, 100, 0.15);
    private static final Color ILLEGAL_ZONE = Color.rgb(200, 100, 100, 0.15);
    private static final Color CROSSHAIR_COLOR = Color.LIME;
    private static final Color FOV_COLOR = Color.ORANGE;
    private static final Color TARGET_COLOR = Color.rgb(0, 150, 255, 0.7);
    private static final Color OUT_OF_BOUNDS_COLOR = Color.rgb(255, 100, 100, 0.8);

    // ========== Rendering Constants ==========
    private static final double CROSSHAIR_RADIUS = 6;  // pixels
    private static final double CROSSHAIR_LINE_LENGTH = 20;  // pixels
    private static final double CROSSHAIR_GAP = 2;  // pixels gap between circle and lines
    private static final double INSERT_PADDING = 20;  // pixels padding around insert

    // ========== Image Layer ==========
    private WritableImage backgroundImage;
    private ImageView backgroundView;
    private PixelWriter pixelWriter;

    // ========== Shape Overlay Layer ==========
    private Pane overlayPane;
    private Circle crosshairCircle;
    private Line crosshairLineH1, crosshairLineH2, crosshairLineV1, crosshairLineV2;
    private Rectangle fovRect;
    private Line targetLineH, targetLineV;
    private List<Rectangle> slideRects = new ArrayList<>();
    private List<Text> slideLabels = new ArrayList<>();
    private Rectangle insertBorderRect;

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

    // Track size for recalculation
    private double lastWidth = 0;
    private double lastHeight = 0;
    private boolean isRecalculating = false;

    // Rendering control
    private volatile boolean renderingEnabled = true;

    // ========== Callback ==========
    private BiConsumer<Double, Double> clickHandler;

    // Initial size preferences
    private double initialWidth = 400;
    private double initialHeight = 300;

    public StageMapCanvas() {
        this(400, 300);
    }

    public StageMapCanvas(double width, double height) {
        this.initialWidth = width;
        this.initialHeight = height;
        setPrefSize(width, height);

        // Create background image layer
        backgroundImage = new WritableImage((int) width, (int) height);
        pixelWriter = backgroundImage.getPixelWriter();
        backgroundView = new ImageView(backgroundImage);
        backgroundView.setPreserveRatio(false);

        // Create overlay pane for shapes
        overlayPane = new Pane();
        overlayPane.setMouseTransparent(false);

        // Create crosshair shapes
        crosshairCircle = new Circle(CROSSHAIR_RADIUS);
        crosshairCircle.setFill(CROSSHAIR_COLOR);
        crosshairCircle.setVisible(false);

        crosshairLineH1 = createCrosshairLine();
        crosshairLineH2 = createCrosshairLine();
        crosshairLineV1 = createCrosshairLine();
        crosshairLineV2 = createCrosshairLine();

        // Create FOV rectangle
        fovRect = new Rectangle();
        fovRect.setFill(Color.TRANSPARENT);
        fovRect.setStroke(FOV_COLOR);
        fovRect.setStrokeWidth(1.5);
        fovRect.setVisible(false);

        // Create target crosshair (dashed)
        targetLineH = createTargetLine();
        targetLineV = createTargetLine();

        // Create insert border rectangle
        insertBorderRect = new Rectangle();
        insertBorderRect.setFill(Color.TRANSPARENT);
        insertBorderRect.setStroke(INSERT_BORDER);
        insertBorderRect.setStrokeWidth(2);
        insertBorderRect.setVisible(false);

        // Add all shapes to overlay
        overlayPane.getChildren().addAll(
                insertBorderRect,
                crosshairCircle, crosshairLineH1, crosshairLineH2, crosshairLineV1, crosshairLineV2,
                fovRect,
                targetLineH, targetLineV
        );

        // Stack layers
        getChildren().addAll(backgroundView, overlayPane);

        // Handle mouse events on the overlay pane
        overlayPane.setOnMouseMoved(e -> {
            if (currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    targetStageX = stageCoords[0];
                    targetStageY = stageCoords[1];
                    showTarget = true;
                    updateTargetOverlay();
                }
            }
        });

        overlayPane.setOnMouseExited(e -> {
            showTarget = false;
            updateTargetOverlay();
        });

        overlayPane.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && clickHandler != null && currentInsert != null) {
                double[] stageCoords = screenToStage(e.getX(), e.getY());
                if (stageCoords != null) {
                    clickHandler.accept(stageCoords[0], stageCoords[1]);
                }
            }
        });

        // Handle size changes
        widthProperty().addListener((obs, oldVal, newVal) -> onSizeChanged());
        heightProperty().addListener((obs, oldVal, newVal) -> onSizeChanged());

        // Initial render
        renderBackground();
    }

    private Line createCrosshairLine() {
        Line line = new Line();
        line.setStroke(CROSSHAIR_COLOR);
        line.setStrokeWidth(2);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        line.setVisible(false);
        return line;
    }

    private Line createTargetLine() {
        Line line = new Line();
        line.setStroke(TARGET_COLOR);
        line.setStrokeWidth(1);
        line.getStrokeDashArray().addAll(4.0, 4.0);
        line.setVisible(false);
        return line;
    }

    // ========== Public API ==========

    /**
     * Sets the insert configuration to display.
     */
    public void setInsert(StageInsert insert) {
        this.currentInsert = insert;
        calculateScale();
        renderBackground();
        updateOverlays();
    }

    /**
     * Updates the current stage position (crosshair location).
     */
    public void updatePosition(double stageX, double stageY) {
        this.currentStageX = stageX;
        this.currentStageY = stageY;
        updateCrosshairOverlay();
        updateFOVOverlay();
    }

    /**
     * Updates the camera field of view dimensions.
     */
    public void updateFOV(double widthUm, double heightUm) {
        this.fovWidthUm = widthUm;
        this.fovHeightUm = heightUm;
        updateFOVOverlay();
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
        renderBackground();
    }

    /**
     * Enables or disables rendering.
     */
    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
        if (!enabled) {
            logger.debug("Rendering disabled");
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
     * Paints a color block at the specified stage position.
     * Used for live preview scan feature.
     *
     * @param stageX   Stage X coordinate in microns
     * @param stageY   Stage Y coordinate in microns
     * @param r        Red component (0-255)
     * @param g        Green component (0-255)
     * @param b        Blue component (0-255)
     * @param blockSize Size of the color block in pixels
     */
    public void paintColorBlock(double stageX, double stageY, int r, int g, int b, int blockSize) {
        if (!renderingEnabled || currentInsert == null) {
            return;
        }

        double[] screenPos = stageToScreen(stageX, stageY);
        if (screenPos == null) {
            return;
        }

        int sx = (int) screenPos[0];
        int sy = (int) screenPos[1];
        Color color = Color.rgb(r, g, b);

        // Paint a small block centered on the position
        int halfSize = blockSize / 2;
        int imgWidth = (int) backgroundImage.getWidth();
        int imgHeight = (int) backgroundImage.getHeight();

        for (int dx = -halfSize; dx <= halfSize; dx++) {
            for (int dy = -halfSize; dy <= halfSize; dy++) {
                int px = sx + dx;
                int py = sy + dy;
                if (px >= 0 && px < imgWidth && py >= 0 && py < imgHeight) {
                    pixelWriter.setColor(px, py, color);
                }
            }
        }
    }

    // ========== Coordinate Conversion ==========

    /**
     * Converts screen coordinates to stage coordinates.
     */
    public double[] screenToStage(double screenX, double screenY) {
        if (currentInsert == null || scale == 0) {
            return null;
        }

        double insertX = (screenX - offsetX) / scale;
        double insertY = (screenY - offsetY) / scale;

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
     */
    public double[] stageToScreen(double stageX, double stageY) {
        if (currentInsert == null) {
            return null;
        }

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

    // ========== Internal Rendering ==========

    private void calculateScale() {
        if (currentInsert == null) {
            scale = 1.0;
            offsetX = offsetY = 0;
            return;
        }

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        double availableWidth = w - 2 * INSERT_PADDING;
        double availableHeight = h - 2 * INSERT_PADDING;

        double scaleX = availableWidth / currentInsert.getWidthUm();
        double scaleY = availableHeight / currentInsert.getHeightUm();

        scale = Math.min(scaleX, scaleY);

        double renderedWidth = currentInsert.getWidthUm() * scale;
        double renderedHeight = currentInsert.getHeightUm() * scale;

        offsetX = (w - renderedWidth) / 2.0;
        offsetY = (h - renderedHeight) / 2.0;
    }

    /**
     * Renders the static background elements to the WritableImage.
     */
    private void renderBackground() {
        if (!renderingEnabled) {
            return;
        }

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::renderBackground);
            return;
        }

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Recreate image if size changed
        int iw = (int) w;
        int ih = (int) h;
        if (backgroundImage.getWidth() != iw || backgroundImage.getHeight() != ih) {
            backgroundImage = new WritableImage(iw, ih);
            pixelWriter = backgroundImage.getPixelWriter();
            backgroundView.setImage(backgroundImage);
        }

        // Fill background
        fillRect(0, 0, iw, ih, BACKGROUND_COLOR);

        if (currentInsert == null) {
            // Show message when no insert configured
            return;
        }

        // Draw insert background
        int insertX = (int) offsetX;
        int insertY = (int) offsetY;
        int insertW = (int) (currentInsert.getWidthUm() * scale);
        int insertH = (int) (currentInsert.getHeightUm() * scale);
        fillRect(insertX, insertY, insertW, insertH, INSERT_BACKGROUND);

        // Draw legal/illegal zones
        if (showLegalZones) {
            // First fill with illegal zone color
            fillRectBlend(insertX, insertY, insertW, insertH, ILLEGAL_ZONE);

            // Then overlay legal zones around slides
            double marginPx = currentInsert.getSlideMarginUm() * scale;
            for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
                int zx = (int) (offsetX + slide.getXOffsetUm() * scale - marginPx);
                int zy = (int) (offsetY + slide.getYOffsetUm() * scale - marginPx);
                int zw = (int) (slide.getWidthUm() * scale + 2 * marginPx);
                int zh = (int) (slide.getHeightUm() * scale + 2 * marginPx);
                fillRectBlend(zx, zy, zw, zh, LEGAL_ZONE);
            }
        }

        // Draw slides
        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            int sx = (int) (offsetX + slide.getXOffsetUm() * scale);
            int sy = (int) (offsetY + slide.getYOffsetUm() * scale);
            int sw = (int) (slide.getWidthUm() * scale);
            int sh = (int) (slide.getHeightUm() * scale);

            fillRect(sx, sy, sw, sh, SLIDE_FILL);
            drawRectBorder(sx, sy, sw, sh, SLIDE_BORDER, 2);
        }

        // Update insert border shape
        insertBorderRect.setX(offsetX);
        insertBorderRect.setY(offsetY);
        insertBorderRect.setWidth(currentInsert.getWidthUm() * scale);
        insertBorderRect.setHeight(currentInsert.getHeightUm() * scale);
        insertBorderRect.setVisible(true);

        // Update slide labels (using Text shapes)
        updateSlideLabels();
    }

    private void updateSlideLabels() {
        // Remove old labels
        overlayPane.getChildren().removeAll(slideLabels);
        slideLabels.clear();

        if (currentInsert == null) {
            return;
        }

        for (StageInsert.SlidePosition slide : currentInsert.getSlides()) {
            double sx = offsetX + slide.getXOffsetUm() * scale;
            double sy = offsetY + slide.getYOffsetUm() * scale;
            double sw = slide.getWidthUm() * scale;
            double sh = slide.getHeightUm() * scale;

            Text label = new Text(slide.getName());
            label.setFont(Font.font(10));
            label.setFill(SLIDE_LABEL);
            label.setTextAlignment(TextAlignment.CENTER);

            // Center the label
            double textWidth = label.getLayoutBounds().getWidth();
            double textHeight = label.getLayoutBounds().getHeight();
            label.setX(sx + (sw - textWidth) / 2);
            label.setY(sy + (sh + textHeight) / 2 - 2);

            slideLabels.add(label);
        }

        overlayPane.getChildren().addAll(slideLabels);
    }

    private void fillRect(int x, int y, int w, int h, Color color) {
        int imgW = (int) backgroundImage.getWidth();
        int imgH = (int) backgroundImage.getHeight();

        for (int px = Math.max(0, x); px < Math.min(imgW, x + w); px++) {
            for (int py = Math.max(0, y); py < Math.min(imgH, y + h); py++) {
                pixelWriter.setColor(px, py, color);
            }
        }
    }

    private void fillRectBlend(int x, int y, int w, int h, Color color) {
        int imgW = (int) backgroundImage.getWidth();
        int imgH = (int) backgroundImage.getHeight();

        for (int px = Math.max(0, x); px < Math.min(imgW, x + w); px++) {
            for (int py = Math.max(0, y); py < Math.min(imgH, y + h); py++) {
                Color existing = backgroundImage.getPixelReader().getColor(px, py);
                Color blended = blendColors(existing, color);
                pixelWriter.setColor(px, py, blended);
            }
        }
    }

    private Color blendColors(Color base, Color overlay) {
        double alpha = overlay.getOpacity();
        double r = base.getRed() * (1 - alpha) + overlay.getRed() * alpha;
        double g = base.getGreen() * (1 - alpha) + overlay.getGreen() * alpha;
        double b = base.getBlue() * (1 - alpha) + overlay.getBlue() * alpha;
        return Color.color(r, g, b);
    }

    private void drawRectBorder(int x, int y, int w, int h, Color color, int thickness) {
        // Top and bottom
        fillRect(x, y, w, thickness, color);
        fillRect(x, y + h - thickness, w, thickness, color);
        // Left and right
        fillRect(x, y, thickness, h, color);
        fillRect(x + w - thickness, y, thickness, h, color);
    }

    // ========== Overlay Updates ==========

    private void updateOverlays() {
        updateCrosshairOverlay();
        updateFOVOverlay();
        updateTargetOverlay();
    }

    private void updateCrosshairOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateCrosshairOverlay);
            return;
        }

        if (Double.isNaN(currentStageX) || Double.isNaN(currentStageY) || currentInsert == null) {
            crosshairCircle.setVisible(false);
            crosshairLineH1.setVisible(false);
            crosshairLineH2.setVisible(false);
            crosshairLineV1.setVisible(false);
            crosshairLineV2.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        // Check if in bounds
        boolean inInsert = currentInsert.isPositionInInsert(currentStageX, currentStageY);
        Color color = inInsert ? CROSSHAIR_COLOR : OUT_OF_BOUNDS_COLOR;

        // Update circle
        crosshairCircle.setCenterX(sx);
        crosshairCircle.setCenterY(sy);
        crosshairCircle.setFill(color);
        crosshairCircle.setVisible(true);

        // Update lines
        double lineStart = CROSSHAIR_RADIUS + CROSSHAIR_GAP;
        double lineEnd = lineStart + CROSSHAIR_LINE_LENGTH;

        crosshairLineH1.setStartX(sx - lineEnd);
        crosshairLineH1.setStartY(sy);
        crosshairLineH1.setEndX(sx - lineStart);
        crosshairLineH1.setEndY(sy);
        crosshairLineH1.setStroke(color);
        crosshairLineH1.setVisible(true);

        crosshairLineH2.setStartX(sx + lineStart);
        crosshairLineH2.setStartY(sy);
        crosshairLineH2.setEndX(sx + lineEnd);
        crosshairLineH2.setEndY(sy);
        crosshairLineH2.setStroke(color);
        crosshairLineH2.setVisible(true);

        crosshairLineV1.setStartX(sx);
        crosshairLineV1.setStartY(sy - lineEnd);
        crosshairLineV1.setEndX(sx);
        crosshairLineV1.setEndY(sy - lineStart);
        crosshairLineV1.setStroke(color);
        crosshairLineV1.setVisible(true);

        crosshairLineV2.setStartX(sx);
        crosshairLineV2.setStartY(sy + lineStart);
        crosshairLineV2.setEndX(sx);
        crosshairLineV2.setEndY(sy + lineEnd);
        crosshairLineV2.setStroke(color);
        crosshairLineV2.setVisible(true);
    }

    private void updateFOVOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateFOVOverlay);
            return;
        }

        if (Double.isNaN(currentStageX) || Double.isNaN(currentStageY) ||
            fovWidthUm <= 0 || fovHeightUm <= 0 || currentInsert == null) {
            fovRect.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(currentStageX, currentStageY);
        if (screenPos == null) {
            fovRect.setVisible(false);
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];
        double fovW = fovWidthUm * scale;
        double fovH = fovHeightUm * scale;

        fovRect.setX(sx - fovW / 2);
        fovRect.setY(sy - fovH / 2);
        fovRect.setWidth(fovW);
        fovRect.setHeight(fovH);
        fovRect.setVisible(true);
    }

    private void updateTargetOverlay() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateTargetOverlay);
            return;
        }

        if (!showTarget || Double.isNaN(targetStageX) || Double.isNaN(targetStageY) || currentInsert == null) {
            targetLineH.setVisible(false);
            targetLineV.setVisible(false);
            return;
        }

        double[] screenPos = stageToScreen(targetStageX, targetStageY);
        if (screenPos == null) {
            targetLineH.setVisible(false);
            targetLineV.setVisible(false);
            return;
        }

        double sx = screenPos[0];
        double sy = screenPos[1];

        boolean isLegal = currentInsert.isPositionLegal(targetStageX, targetStageY);
        Color color = isLegal ? TARGET_COLOR : OUT_OF_BOUNDS_COLOR;

        targetLineH.setStartX(sx - CROSSHAIR_LINE_LENGTH);
        targetLineH.setStartY(sy);
        targetLineH.setEndX(sx + CROSSHAIR_LINE_LENGTH);
        targetLineH.setEndY(sy);
        targetLineH.setStroke(color);
        targetLineH.setVisible(true);

        targetLineV.setStartX(sx);
        targetLineV.setStartY(sy - CROSSHAIR_LINE_LENGTH);
        targetLineV.setEndX(sx);
        targetLineV.setEndY(sy + CROSSHAIR_LINE_LENGTH);
        targetLineV.setStroke(color);
        targetLineV.setVisible(true);
    }

    // ========== Size Handling ==========

    public void onSizeChanged() {
        if (isRecalculating) {
            return;
        }

        double currentWidth = getWidth();
        double currentHeight = getHeight();
        if (Math.abs(currentWidth - lastWidth) < 2 && Math.abs(currentHeight - lastHeight) < 2) {
            return;
        }

        isRecalculating = true;
        try {
            lastWidth = currentWidth;
            lastHeight = currentHeight;
            backgroundView.setFitWidth(currentWidth);
            backgroundView.setFitHeight(currentHeight);
            calculateScale();
            renderBackground();
            updateOverlays();
        } finally {
            isRecalculating = false;
        }
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    protected double computePrefWidth(double height) {
        return initialWidth;
    }

    @Override
    protected double computePrefHeight(double width) {
        return initialHeight;
    }

    /**
     * Convenience method for API compatibility with legacy canvas.
     * Triggers a full re-render.
     */
    public void render() {
        renderBackground();
        updateOverlays();
    }

    /**
     * Returns false - this implementation does not have texture corruption issues.
     */
    public boolean isTextureCorrupted() {
        return false;
    }
}
