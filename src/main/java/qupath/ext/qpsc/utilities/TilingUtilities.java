package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            createBoundingBoxTiles(request);
        } else if (request.hasAnnotations()) {
            logger.info("Creating tiles for {} annotations", request.getAnnotations().size());
            createAnnotationTiles(request);
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
    private static void createBoundingBoxTiles(TilingRequest request) throws IOException {
        BoundingBox bb = request.getBoundingBox();

        // Calculate grid bounds
        double minX = bb.getMinX();
        double maxX = bb.getMaxX();
        double minY = bb.getMinY();
        double maxY = bb.getMaxY();

        // Apply axis inversions if needed
        if (request.isInvertX()) {
            double temp = minX;
            minX = maxX;
            maxX = temp;
        }
        if (request.isInvertY()) {
            double temp = minY;
            minY = maxY;
            maxY = temp;
        }

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
        createTileGrid(startX, startY, width, height, request, configPath, null);
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
    private static void createAnnotationTiles(TilingRequest request) throws IOException {
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

            createTileGrid(x, y, w, h, request, configPath, roi);
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
     * @param startX the left edge of the grid area
     * @param startY the top edge of the grid area
     * @param width the total width to cover
     * @param height the total height to cover
     * @param request the tiling parameters
     * @param configPath the output path for TileConfiguration.txt
     * @param filterROI optional ROI to filter tiles (only tiles intersecting this ROI are kept)
     * @throws IOException if unable to write the configuration file
     */

    private static void createTileGrid(
            double startX, double startY,
            double width, double height,
            TilingRequest request,
            String configPath,
            ROI filterROI) throws IOException {

        // Calculate step sizes based on overlap
        double overlapFraction = request.getOverlapPercent() / 100.0;
        double xStep = request.getFrameWidth() * (1 - overlapFraction);
        double yStep = request.getFrameHeight() * (1 - overlapFraction);

        // Calculate number of tiles needed - ensure we cover the entire area
        int nCols = (int) Math.ceil(width / xStep);
        int nRows = (int) Math.ceil(height / yStep);

        // If the division is exact, we still need one more tile to cover the far edge
        if (width % xStep == 0) nCols++;
        if (height % yStep == 0) nRows++;

        logger.info("Tile grid configuration:");
        logger.info("  Area: ({}, {}) to ({}, {})", startX, startY, startX + width, startY + height);
        logger.info("  Frame size: {} x {}", request.getFrameWidth(), request.getFrameHeight());
        logger.info("  Step size: {} x {} ({}% overlap)", xStep, yStep, request.getOverlapPercent());
        logger.info("  Grid: {} columns x {} rows", nCols, nRows);

        // Prepare output structures
        List<String> configLines = new ArrayList<>();
        configLines.add("dim = 2");
        List<PathObject> detectionTiles = new ArrayList<>();
        int tileIndex = 0;
        int skippedTiles = 0;

        // Generate tiles in a simple raster pattern
        // The axis inversion (if any) will be handled by the transformation later
        for (int row = 0; row < nRows; row++) {
            double y = startY + row * yStep;

            // Serpentine pattern for efficient stage movement
            boolean reverseDirection = (row % 2 == 1);

            for (int col = 0; col < nCols; col++) {
                // Apply serpentine pattern
                int actualCol = reverseDirection ? (nCols - 1 - col) : col;
                double x = startX + actualCol * xStep;

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
                    // Check if tile center is within the filter ROI or if they intersect
                    includeTile = filterROI.contains(tileROI.getCentroidX(), tileROI.getCentroidY()) ||
                            filterROI.getGeometry().intersects(tileROI.getGeometry());
                }

                if (!includeTile) {
                    skippedTiles++;
                    continue;
                }

                // Add to configuration file
                configLines.add(String.format("%d.tif; ; (%.3f, %.3f)",
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
                    tile.setName(String.valueOf(tileIndex));
                    tile.getMeasurements().put("TileNumber", tileIndex);
                    tile.getMeasurements().put("Row", row);
                    tile.getMeasurements().put("Column", actualCol);
                    detectionTiles.add(tile);
                }

                tileIndex++;
            }
        }

        logger.info("Generated {} tiles, skipped {} tiles outside ROI", tileIndex, skippedTiles);

        // Write configuration file
        Path configFilePath = Paths.get(configPath);
        Files.createDirectories(configFilePath.getParent());
        Files.write(configFilePath, configLines, StandardCharsets.UTF_8);
        logger.info("Wrote tile configuration to: {}", configPath);

        // Add detection objects to QuPath hierarchy
        if (!detectionTiles.isEmpty()) {
            QP.getCurrentHierarchy().addObjects(detectionTiles);
            QP.fireHierarchyUpdate();
            logger.info("Added {} detection tiles to QuPath", detectionTiles.size());
        }
    }
}