package qupath.ext.qpsc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.objects.PathObject;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for ordering annotations by spatial proximity.
 *
 * <p>Orders annotations using a greedy nearest-neighbor algorithm to minimize
 * stage travel distance and facilitate building spatial models (e.g., Z-focus
 * prediction planes) that work best with locally clustered measurements.</p>
 *
 * <p>The first annotation in the list is preserved (typically the one prioritized
 * from user refinement), and remaining annotations are ordered by proximity
 * starting from the first.</p>
 *
 * @author Generated for QPSC project
 * @since 1.0
 */
public class AnnotationOrderingService {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationOrderingService.class);

    /**
     * Sorts annotations by proximity using greedy nearest-neighbor.
     *
     * <p>The first annotation is preserved in place (typically the user-focused
     * annotation from refinement). Remaining annotations are ordered by selecting
     * the nearest unvisited annotation from the current position.</p>
     *
     * @param annotations List of annotations to sort
     * @param transform   Affine transform for converting QuPath to stage coordinates
     * @return New list with first element preserved, rest ordered by proximity
     */
    public static List<PathObject> sortByProximity(
            List<PathObject> annotations,
            AffineTransform transform) {

        if (annotations == null || annotations.size() <= 1) {
            return annotations != null ? new ArrayList<>(annotations) : new ArrayList<>();
        }

        if (transform == null) {
            logger.warn("No transform provided - cannot sort by proximity, returning original order");
            return new ArrayList<>(annotations);
        }

        // Pre-calculate all stage coordinates
        List<AnnotationWithCoords> withCoords = annotations.stream()
                .map(ann -> new AnnotationWithCoords(ann, getStageCoordinates(ann, transform)))
                .collect(Collectors.toList());

        // Start with the first annotation (refinement-prioritized)
        List<PathObject> ordered = new ArrayList<>();
        ordered.add(withCoords.get(0).annotation);
        double[] currentPos = withCoords.get(0).stageCoords;

        // Track which annotations we've added
        List<AnnotationWithCoords> remaining = new ArrayList<>(withCoords.subList(1, withCoords.size()));

        // Log starting position
        logger.info("Starting annotation ordering from: {} at ({:.1f}, {:.1f})",
                withCoords.get(0).annotation.getName(),
                currentPos[0], currentPos[1]);

        // Greedy nearest-neighbor
        while (!remaining.isEmpty()) {
            // Find nearest remaining annotation
            int nearestIdx = 0;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                double dist = euclideanDistance(currentPos, remaining.get(i).stageCoords);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestIdx = i;
                }
            }

            // Add nearest and update current position
            AnnotationWithCoords nearest = remaining.remove(nearestIdx);
            ordered.add(nearest.annotation);
            currentPos = nearest.stageCoords;

            logger.debug("  Next: {} at ({:.1f}, {:.1f}), distance: {:.1f} um",
                    nearest.annotation.getName(),
                    currentPos[0], currentPos[1],
                    nearestDist);
        }

        // Log final ordering
        String orderStr = ordered.stream()
                .map(PathObject::getName)
                .collect(Collectors.joining(" -> "));
        logger.info("Annotation order: {}", orderStr);

        // Calculate and log total travel distance
        double totalDistance = calculateTotalDistance(ordered, transform);
        logger.info("Total estimated travel distance: {:.1f} um ({:.2f} mm)",
                totalDistance, totalDistance / 1000.0);

        return ordered;
    }

    /**
     * Gets stage coordinates for an annotation's centroid.
     *
     * @param annotation Annotation to get coordinates for
     * @param transform  Transform from QuPath to stage coordinates
     * @return [stageX, stageY] in micrometers
     */
    public static double[] getStageCoordinates(PathObject annotation, AffineTransform transform) {
        double centroidX = annotation.getROI().getCentroidX();
        double centroidY = annotation.getROI().getCentroidY();
        return TransformationFunctions.transformQuPathFullResToStage(
                new double[]{centroidX, centroidY},
                transform
        );
    }

    /**
     * Calculates Euclidean distance between two points.
     *
     * @param a First point [x, y]
     * @param b Second point [x, y]
     * @return Distance in micrometers
     */
    public static double euclideanDistance(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Calculates total travel distance for an ordered list of annotations.
     *
     * @param annotations Ordered list of annotations
     * @param transform   Transform for coordinate conversion
     * @return Total distance in micrometers
     */
    public static double calculateTotalDistance(List<PathObject> annotations, AffineTransform transform) {
        if (annotations == null || annotations.size() <= 1) {
            return 0.0;
        }

        double total = 0.0;
        double[] prevCoords = getStageCoordinates(annotations.get(0), transform);

        for (int i = 1; i < annotations.size(); i++) {
            double[] coords = getStageCoordinates(annotations.get(i), transform);
            total += euclideanDistance(prevCoords, coords);
            prevCoords = coords;
        }

        return total;
    }

    /**
     * Helper class to associate annotations with their stage coordinates.
     */
    private static class AnnotationWithCoords {
        final PathObject annotation;
        final double[] stageCoords;

        AnnotationWithCoords(PathObject annotation, double[] stageCoords) {
            this.annotation = annotation;
            this.stageCoords = stageCoords;
        }
    }
}
