package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for creating tile configurations for microscope acquisition.
 * <p>
 * This class provides a unified interface for generating tiling patterns for both
 * bounding box-based and annotation-based acquisition workflows. It handles:
 * <ul>
 *   <li>Grid calculation with configurable overlap</li>
 *   <li>Axis inversion for stage coordinate systems</li>
 *   <li>Serpentine/snake patterns for efficient stage movement</li>
 *   <li>QuPath detection object creation for visualization</li>
 *   <li>TileConfiguration.txt generation for downstream processing</li>
 * </ul>
 *
 * @since 0.2.1
 */
public class TilingUtilities {
    private static final Logger logger = LoggerFactory.getLogger(TilingUtilities.class);

    /**
     * Main entry point for tile generation.
     * <p>
     * Analyzes the provided {@link TilingRequest} and delegates to the appropriate
     * tiling strategy based on whether a bounding box or annotations are provided.
     *
     * @param request the tiling parameters and region specification
     * @throws IllegalArgumentException if neither bounding box nor annotations are provided
     * @throws IOException if unable to create directories or write configuration files
     */
    public static void createTiles(TilingRequest request) throws IOException {
        logger.info("Starting tile creation for modality: {}", request.getModalityName());

        if (request.hasBoundingBox()) {
            logger.info("Creating tiles for bounding box");
            processBoundingBoxTilingRequest(request);
        } else if (request.hasAnnotations()) {
            logger.info("Creating tiles for {} annotations", request.getAnnotations().size());
            processAnnotationTilingRequest(request);
        } else {
            throw new IllegalArgumentException("Must provide either bounding box or annotations");
        }

        logger.info("Tile creation completed");
    }

    /**
     * Creates tiles for a single rectangular bounding box region.
     * <p>
     * The tiles are generated in a grid pattern covering the specified bounding box,
     * with the configuration written to {@code outputFolder/bounds/TileConfiguration.txt}.
     *
     * @param request the tiling parameters including the bounding box
     * @throws IOException if unable to create directories or write configuration
     */
    private static void processBoundingBoxTilingRequest(TilingRequest request) throws IOException {
        BoundingBox bb = request.getBoundingBox();

        // Calculate grid bounds
        double minX = bb.getMinX();
        double maxX = bb.getMaxX();
        double minY = bb.getMinY();
        double maxY = bb.getMaxY();

//        // Apply axis inversions if needed
//        if (request.isInvertX()) {
//            double temp = minX;
//            minX = maxX;
//            maxX = temp;
//        }
//        if (request.isInvertY()) {
//            double temp = minY;
//            minY = maxY;
//            maxY = temp;
//        }

        // Expand bounds by half frame to ensure full coverage
        double startX = minX - request.getFrameWidth() / 2.0;
        double startY = minY - request.getFrameHeight() / 2.0;
        double width = Math.abs(maxX - minX) + request.getFrameWidth();
        double height = Math.abs(maxY - minY) + request.getFrameHeight();

        // Create output directory and configuration path
        Path boundsDir = Paths.get(request.getOutputFolder(), "bounds");
        Files.createDirectories(boundsDir);
        String configPath = boundsDir.resolve("TileConfiguration.txt").toString();

        logger.info("Creating bounding box tiles: start=({}, {}), size={}x{}",
                startX, startY, width, height);

        // Generate the tile grid
        processTileGridRequest(startX, startY, width, height, request, configPath, null, null);
    }

    /**
     * Creates tiles for multiple annotation regions.
     * <p>
     * Each annotation gets its own subdirectory with a separate TileConfiguration.txt.
     * Annotations are automatically named based on their centroid coordinates and locked
     * to prevent accidental modification during acquisition.
     *
     * @param request the tiling parameters including the annotation list
     * @throws IOException if unable to create directories or write configurations
     */
    private static void processAnnotationTilingRequest(TilingRequest request) throws IOException {
        // First, name and lock all annotations
        for (PathObject annotation : request.getAnnotations()) {
            String name = String.format("%d_%d",
                    (int) annotation.getROI().getCentroidX(),
                    (int) annotation.getROI().getCentroidY()
            );
            annotation.setName(name);
            annotation.setLocked(true);
        }

        // Fire hierarchy update to reflect annotation changes
        QP.fireHierarchyUpdate();

        // Create tiles for each annotation
        for (PathObject annotation : request.getAnnotations()) {
            ROI roi = annotation.getROI();
            String annotationName = annotation.getName();

            logger.info("Processing annotation: {} at bounds ({}, {}, {}, {})",
                    annotationName, roi.getBoundsX(), roi.getBoundsY(),
                    roi.getBoundsWidth(), roi.getBoundsHeight());

            // Calculate bounds with optional buffer
            double x = roi.getBoundsX();
            double y = roi.getBoundsY();
            double w = roi.getBoundsWidth();
            double h = roi.getBoundsHeight();

            if (request.isAddBuffer()) {
                x -= request.getFrameWidth() / 2.0;
                y -= request.getFrameHeight() / 2.0;
                w += request.getFrameWidth();
                h += request.getFrameHeight();
                logger.debug("Added buffer - new bounds: ({}, {}, {}, {})", x, y, w, h);
            }

            // IMPORTANT: Don't apply axis inversion here - it's handled in createTileGrid
            // The inversion affects the order of tile generation, not the bounds

            // Create annotation-specific directory
            Path annotationDir = Paths.get(request.getOutputFolder(), annotationName);
            Files.createDirectories(annotationDir);

            String configPath = annotationDir.resolve("TileConfiguration.txt").toString();

            // Generate tiles

            processTileGridRequest(x, y, w, h, request, configPath, roi, annotationName);
        }
    }

    /**
     * Core tiling algorithm that generates a grid of tiles and writes the configuration.
     * <p>
     * This method implements the actual grid generation logic including:
     * <ul>
     *   <li>Step size calculation based on overlap</li>
     *   <li>Serpentine/snake pattern for efficient stage movement</li>
     *   <li>Optional filtering by ROI intersection</li>
     *   <li>QuPath detection object creation</li>
     *   <li>TileConfiguration.txt generation in ImageJ/Fiji format</li>
     * </ul>
     *
     * @param startX         the left edge of the grid area
     * @param startY         the top edge of the grid area
     * @param width          the total width to cover
     * @param height         the total height to cover
     * @param request        the tiling parameters
     * @param configPath     the output path for TileConfiguration.txt
     * @param filterROI      optional ROI to filter tiles (only tiles intersecting this ROI are kept)
     * @param annotationName
     * @throws IOException if unable to write the configuration file
     */

    private static void processTileGridRequest(
            double startX, double startY,
            double width, double height,
            TilingRequest request,
            String configPath,
            ROI filterROI, String annotationName) throws IOException {

        // Calculate step sizes based on overlap
        double overlapFraction = request.getOverlapPercent() / 100.0;
        double xStep = request.getFrameWidth() * (1 - overlapFraction);
        double yStep = request.getFrameHeight() * (1 - overlapFraction);

        // Calculate number of tiles needed
        int nCols = (int) Math.ceil(width / xStep);
        int nRows = (int) Math.ceil(height / yStep);

        // If the division is exact, we still need one more tile to cover the far edge
        if (width % xStep == 0) nCols++;
        if (height % yStep == 0) nRows++;

        logger.info("Tile grid configuration:");
        logger.info("  Area: ({}, {}) to ({}, {})", startX, startY, startX + width, startY + height);
        logger.info("  Frame size: {} x {} (in input units)", request.getFrameWidth(), request.getFrameHeight());
        logger.info("  Step size: {} x {} ({}% overlap)", xStep, yStep, request.getOverlapPercent());
        logger.info("  Grid: {} columns x {} rows", nCols, nRows);
        logger.info("  Pixel size for coordinate conversion: {} µm/px", request.getPixelSizeMicrons());
        logger.info("  X-axis inverted: {}, Y-axis inverted: {}", request.isInvertX(), request.isInvertY());

        // Prepare output structures
        // NOTE: Both files start with pixel coordinates. TileConfiguration.txt will be transformed
        // to stage coordinates later by TransformationFunctions.transformTileConfiguration()
        List<String> configLinesForTransform = new ArrayList<>();  // For transformation to stage
        List<String> configLinesPixels = new ArrayList<>();   // For QuPath (pixel coordinates)
        configLinesForTransform.add("dim = 2");
        configLinesPixels.add("dim = 2");
        List<PathObject> detectionTiles = new ArrayList<>();
        int tileIndex = 0;
        int skippedTiles = 0;

        // Generate tiles
        for (int row = 0; row < nRows; row++) {
            // When Y is inverted, we need to process rows in reverse order
            int gridRow = request.isInvertY() ? (nRows - 1 - row) : row;
            double y = startY + gridRow * yStep;

            // Serpentine pattern based on the logical row (not grid row)
            boolean reverseDirection = (row % 2 == 1);

            for (int col = 0; col < nCols; col++) {
                // Apply serpentine pattern
                int serpentineCol = reverseDirection ? (nCols - 1 - col) : col;

                // When X is inverted, we need to process columns in reverse order
                int gridCol = request.isInvertX() ? (nCols - 1 - serpentineCol) : serpentineCol;
                double x = startX + gridCol * xStep;

                // Create tile ROI
                ROI tileROI = ROIs.createRectangleROI(
                        x, y,
                        request.getFrameWidth(),
                        request.getFrameHeight(),
                        ImagePlane.getDefaultPlane()
                );

                // Check if we should include this tile
                boolean includeTile = true;
                if (filterROI != null) {
                    includeTile = filterROI.contains(tileROI.getCentroidX(), tileROI.getCentroidY()) ||
                            filterROI.getGeometry().intersects(tileROI.getGeometry());
                }

                if (!includeTile) {
                    skippedTiles++;
                    continue;
                }

                // Add to configuration files
                // 1. TileConfiguration.txt - QuPath pixel coordinates (will be transformed to stage later)
                // NOTE: These are in PIXELS, not microns! The transformation to stage coordinates
                // happens later in TransformationFunctions.transformTileConfiguration()
                configLinesForTransform.add(String.format("%d.tif; ; (%.3f, %.3f)",
                        tileIndex,
                        tileROI.getCentroidX(),
                        tileROI.getCentroidY()
                ));

                // 2. TileConfiguration_QP.txt - QuPath pixel coordinates (for stitching back into QuPath)
                configLinesPixels.add(String.format("%d.tif; ; (%.3f, %.3f)",
                        tileIndex,
                        tileROI.getCentroidX(),
                        tileROI.getCentroidY()
                ));

                // Create QuPath detection object if requested
                if (request.isCreateDetections()) {
                    PathObject tile = PathObjects.createDetectionObject(
                            tileROI,
                            QP.getPathClass(request.getModalityName())
                    );
                    // Set name to include both tile number and annotation name
                    if (annotationName != null) {
                        tile.setName(String.format("%d_%s", tileIndex, annotationName));
                    } else {
                        tile.setName(String.valueOf(tileIndex));
                    }

                    tile.getMeasurements().put("TileNumber", tileIndex);
                    tile.getMeasurements().put("Row", gridRow);
                    tile.getMeasurements().put("Column", gridCol);
                    detectionTiles.add(tile);
                }

                tileIndex++;
            }
        }

        logger.info("Generated {} tiles, skipped {} tiles outside ROI", tileIndex, skippedTiles);

        // Write both configuration files
        Path configFilePath = Paths.get(configPath);
        Files.createDirectories(configFilePath.getParent());

        // Write TileConfiguration.txt - QuPath pixel coordinates (will be transformed to stage later)
        Files.write(configFilePath, configLinesForTransform, StandardCharsets.UTF_8);
        logger.info("Wrote tile configuration (pixels, pre-transform) to: {}", configPath);

        // Write TileConfiguration_QP.txt - QuPath pixel coordinates (for stitching)
        Path configFilePathQP = Paths.get(configFilePath.getParent().toString(), "TileConfiguration_QP.txt");
        Files.write(configFilePathQP, configLinesPixels, StandardCharsets.UTF_8);
        logger.info("Wrote QuPath tile configuration to: {}", configFilePathQP);

        // Add detection objects to QuPath hierarchy
        // IMPORTANT: Use GUI's hierarchy, not QP static context, to ensure tiles
        // are added to the correct image (especially after image flip operations)
        if (!detectionTiles.isEmpty()) {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getImageData() != null) {
                gui.getImageData().getHierarchy().addObjects(detectionTiles);
                gui.getImageData().getHierarchy().fireHierarchyChangedEvent(gui.getImageData().getHierarchy().getRootObject());
            } else {
                // Fallback to QP static context if GUI not available
                QP.getCurrentHierarchy().addObjects(detectionTiles);
                QP.fireHierarchyUpdate();
            }
            logger.info("Added {} detection tiles to QuPath", detectionTiles.size());
        }
    }

    /**
     * Creates tiles for annotations using camera FOV from the microscope server.
     *
     * <p>This method:
     * <ol>
     *   <li>Removes existing tiles for the modality</li>
     *   <li>Gets camera FOV from the microscope server</li>
     *   <li>Converts FOV to QuPath pixels based on image calibration</li>
     *   <li>Creates detection tiles for each annotation</li>
     * </ol>
     *
     * @param annotations List of annotations to tile
     * @param sampleSetup Sample setup information containing modality
     * @param tempTileDirectory Directory for tile configuration files
     * @param modeWithIndex Imaging mode identifier with index (e.g., "bf_10x_1")
     * @throws IOException if communication with server fails or tile creation fails
     * @throws IllegalArgumentException if annotations are empty or invalid
     * @since 0.3.0
     */
    public static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex) throws IOException {

        if (annotations == null || annotations.isEmpty()) {
            throw new IllegalArgumentException("No annotations provided for tiling");
        }

        logger.info("Creating tiles for {} annotations in modality {}",
                annotations.size(), modeWithIndex);

        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui.getImageData() == null) {
            throw new IllegalStateException("No image is open in QuPath");
        }

        // Remove existing tiles for this modality
        String modalityBase = sampleSetup.modality().replaceAll("(_\\d+)$", "");
        removeExistingModalityTiles(gui, modalityBase);

        // Get FOV using the explicit hardware configuration from sample setup
        // This ensures we use the correct detector (e.g., JAI vs TELEDYNE) that was selected by the user
        double[] fovMicrons = MicroscopeController.getInstance().getCameraFOVFromConfig(
                sampleSetup.modality(),
                sampleSetup.objective(),
                sampleSetup.detector());
        double frameWidthMicrons = fovMicrons[0];
        double frameHeightMicrons = fovMicrons[1];

        logger.info("Camera FOV from server: {} x {} microns",
                frameWidthMicrons, frameHeightMicrons);

        // Validate FOV is reasonable (between 0.1mm and 50mm)
        if (frameWidthMicrons < 100 || frameWidthMicrons > 50000 ||
                frameHeightMicrons < 100 || frameHeightMicrons > 50000) {
            throw new IOException(String.format(
                    "Camera FOV seems unreasonable: %.1f x %.1f µm. Expected between 100-50000 µm",
                    frameWidthMicrons, frameHeightMicrons));
        }

        // Get the actual image pixel size from QuPath
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        // Convert to image pixels for QuPath visualization and tile calculation
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        // Get tiling parameters from preferences
        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        logger.info("Frame size in QuPath pixels: {} x {} ({}% overlap)",
                frameWidthPixels, frameHeightPixels, overlapPercent);
        logger.info("Pixel size: {} microns/pixel", imagePixelSize);

        // Validate tile counts before creation
        validateAnnotationTileCounts(annotations, frameWidthPixels, frameHeightPixels, overlapPercent);

        // Create new tiles - frameSize is in PIXELS for tile visualization,
        // pixelSizeMicrons is used to convert coordinates to microns for TileConfiguration.txt
        TilingRequest request = new TilingRequest.Builder()
                .outputFolder(tempTileDirectory)
                .modalityName(modeWithIndex)
                .frameSize(frameWidthPixels, frameHeightPixels)  // In pixels for tile ROI creation
                .overlapPercent(overlapPercent)
                .annotations(annotations)
                .invertAxes(invertedX, invertedY)
                .createDetections(true)
                .addBuffer(true)
                .pixelSizeMicrons(imagePixelSize)  // For converting tile coordinates to microns
                .build();

        createTiles(request);
        logger.info("Created tiles for {} annotations", annotations.size());
    }

    /**
     * Creates tiles for the given annotations with explicit flip parameters.
     *
     * <p>This overload is used when the flip status should come from image metadata
     * rather than global preferences, such as when working with existing flipped images.</p>
     *
     * @param annotations List of annotations to tile
     * @param sampleSetup Sample setup information containing modality
     * @param tempTileDirectory Directory for tile configuration files
     * @param modeWithIndex Imaging mode identifier with index (e.g., "bf_10x_1")
     * @param invertedX Whether to invert X axis (from image metadata)
     * @param invertedY Whether to invert Y axis (from image metadata)
     * @throws IOException if communication with server fails or tile creation fails
     * @throws IllegalArgumentException if annotations are empty or invalid
     * @since 0.3.0
     */
    public static void createTilesForAnnotations(
            List<PathObject> annotations,
            SampleSetupController.SampleSetupResult sampleSetup,
            String tempTileDirectory,
            String modeWithIndex,
            boolean invertedX,
            boolean invertedY) throws IOException {

        if (annotations == null || annotations.isEmpty()) {
            throw new IllegalArgumentException("No annotations provided for tiling");
        }

        logger.info("Creating tiles for {} annotations in modality {} (invertX={}, invertY={})",
                annotations.size(), modeWithIndex, invertedX, invertedY);

        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui.getImageData() == null) {
            throw new IllegalStateException("No image is open in QuPath");
        }

        // Remove existing tiles for this modality
        String modalityBase = sampleSetup.modality().replaceAll("(_\\d+)$", "");
        removeExistingModalityTiles(gui, modalityBase);

        // Get FOV using the explicit hardware configuration from sample setup
        double[] fovMicrons = MicroscopeController.getInstance().getCameraFOVFromConfig(
                sampleSetup.modality(),
                sampleSetup.objective(),
                sampleSetup.detector());
        double frameWidthMicrons = fovMicrons[0];
        double frameHeightMicrons = fovMicrons[1];

        logger.info("Camera FOV from server: {} x {} microns",
                frameWidthMicrons, frameHeightMicrons);

        // Validate FOV is reasonable (between 0.1mm and 50mm)
        if (frameWidthMicrons < 100 || frameWidthMicrons > 50000 ||
                frameHeightMicrons < 100 || frameHeightMicrons > 50000) {
            throw new IOException(String.format(
                    "Camera FOV seems unreasonable: %.1f x %.1f um. Expected between 100-50000 um",
                    frameWidthMicrons, frameHeightMicrons));
        }

        // Get the actual image pixel size from QuPath
        double imagePixelSize = gui.getImageData().getServer()
                .getPixelCalibration().getAveragedPixelSizeMicrons();

        // Convert to image pixels for QuPath visualization and tile calculation
        double frameWidthPixels = frameWidthMicrons / imagePixelSize;
        double frameHeightPixels = frameHeightMicrons / imagePixelSize;

        // Get overlap from preferences (flip params are explicit)
        double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

        logger.info("Frame size in QuPath pixels: {} x {} ({}% overlap)",
                frameWidthPixels, frameHeightPixels, overlapPercent);
        logger.info("Pixel size: {} microns/pixel", imagePixelSize);

        // Validate tile counts before creation
        validateAnnotationTileCounts(annotations, frameWidthPixels, frameHeightPixels, overlapPercent);

        // Create new tiles using explicit flip parameters
        TilingRequest request = new TilingRequest.Builder()
                .outputFolder(tempTileDirectory)
                .modalityName(modeWithIndex)
                .frameSize(frameWidthPixels, frameHeightPixels)
                .overlapPercent(overlapPercent)
                .annotations(annotations)
                .invertAxes(invertedX, invertedY)  // Use explicit parameters
                .createDetections(true)
                .addBuffer(true)
                .pixelSizeMicrons(imagePixelSize)
                .build();

        createTiles(request);
        logger.info("Created tiles for {} annotations with explicit flip settings", annotations.size());
    }

    /**
     * Removes all detection tiles for a given modality.
     *
     * @param gui QuPath GUI instance
     * @param modalityBase Base modality name without index suffix
     */
    private static void removeExistingModalityTiles(QuPathGUI gui, String modalityBase) {
        var hierarchy = gui.getViewer().getHierarchy();
        List<PathObject> tilesToRemove = hierarchy.getDetectionObjects().stream()
                .filter(o -> o.getPathClass() != null &&
                        o.getPathClass().toString().contains(modalityBase))
                .toList();

        if (!tilesToRemove.isEmpty()) {
            logger.info("Removing {} existing tiles for modality: {}",
                    tilesToRemove.size(), modalityBase);
            hierarchy.removeObjects(tilesToRemove, true);
            hierarchy.fireHierarchyChangedEvent(gui.getViewer());
        }
    }

    /**
     * Validates that tile counts are reasonable for all annotations.
     *
     * @param annotations Annotations to validate
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @param overlapPercent Overlap percentage
     * @throws IllegalArgumentException if any annotation would create too many tiles
     */
    private static void validateAnnotationTileCounts(List<PathObject> annotations,
                                                     double frameWidth, double frameHeight, double overlapPercent) {

        final int MAX_TILES_PER_ANNOTATION = 10000;

        for (PathObject ann : annotations) {
            if (ann.getROI() != null) {
                double annWidth = ann.getROI().getBoundsWidth();
                double annHeight = ann.getROI().getBoundsHeight();

                // Calculate effective frame size considering overlap
                double effectiveFrameWidth = frameWidth * (1 - overlapPercent/100.0);
                double effectiveFrameHeight = frameHeight * (1 - overlapPercent/100.0);

                double tilesX = Math.ceil(annWidth / effectiveFrameWidth);
                double tilesY = Math.ceil(annHeight / effectiveFrameHeight);
                double totalTiles = tilesX * tilesY;

                if (totalTiles > MAX_TILES_PER_ANNOTATION) {
                    throw new IllegalArgumentException(String.format(
                            "Annotation '%s' would require %.0f tiles (%.0fx%.0f). Maximum allowed is %d.\n" +
                                    "This usually indicates incorrect pixel size settings.\n" +
                                    "Annotation size: %.0fx%.0f pixels, Frame size: %.0fx%.0f pixels",
                            ann.getName(), totalTiles, tilesX, tilesY, MAX_TILES_PER_ANNOTATION,
                            annWidth, annHeight, frameWidth, frameHeight));
                }

                logger.debug("Annotation '{}' will create {} tiles ({}x{})",
                        ann.getName(), totalTiles, tilesX, tilesY);
            }
        }
    }
}