package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Tracks Z-focus values for acquired annotations and predicts focus positions
 * using least-squares plane fitting.
 *
 * <p>Mathematical model: z = ax + by + c, where (x, y) are stage coordinates
 * in micrometers and z is the focus position.</p>
 *
 * <p>The model requires a minimum number of data points before making predictions.
 * By default, 4 points are required, but if the distance to the next annotation
 * exceeds 1mm, prediction can be enabled after only 3 points.</p>
 *
 * <p>This addresses sample tilt across the microscope stage, which causes a
 * predictable gradient in optimal focus position.</p>
 *
 * @author Generated for QPSC project
 * @since 1.0
 */
public class ZFocusPredictionModel {
    private static final Logger logger = LoggerFactory.getLogger(ZFocusPredictionModel.class);

    /** Collected data points: each array is [stageX, stageY, focusZ] in micrometers */
    private final List<double[]> dataPoints = new ArrayList<>();

    /** Plane coefficients for z = a*x + b*y + c */
    private double a = 0.0;  // X coefficient
    private double b = 0.0;  // Y coefficient
    private double c = 0.0;  // Intercept

    /** Whether the model has been fitted and is ready for predictions */
    private boolean modelFitted = false;

    /** Last acquired point coordinates for distance calculations */
    private double[] lastAcquiredPoint = null;

    /** Minimum points required for standard prediction */
    private static final int MIN_POINTS_STANDARD = 4;

    /** Minimum points required for early prediction (when distance threshold exceeded) */
    private static final int MIN_POINTS_EARLY = 3;

    /** Distance threshold (in micrometers) for enabling early prediction with 3 points */
    private static final double EARLY_PREDICTION_DISTANCE_UM = 1000.0;  // 1mm

    /**
     * Adds a measured Z-focus data point after successful acquisition.
     *
     * @param stageX Stage X coordinate in micrometers
     * @param stageY Stage Y coordinate in micrometers
     * @param focusZ Final focus Z position in micrometers
     */
    public void addDataPoint(double stageX, double stageY, double focusZ) {
        dataPoints.add(new double[]{stageX, stageY, focusZ});
        lastAcquiredPoint = new double[]{stageX, stageY};

        logger.info("Added Z focus point #{}: ({:.1f}, {:.1f}) -> Z={:.2f} um",
                dataPoints.size(), stageX, stageY, focusZ);

        // Refit the plane model if we have enough points
        if (dataPoints.size() >= MIN_POINTS_EARLY) {
            fitPlane();
        }
    }

    /**
     * Checks if the model can make predictions for an annotation at the given distance.
     *
     * <p>Prediction is enabled when:</p>
     * <ul>
     *   <li>4+ points collected (standard case), OR</li>
     *   <li>3 points collected AND distance to next annotation > 1mm</li>
     * </ul>
     *
     * @param distanceToNextAnnotation Distance from last acquired annotation to
     *                                  the next one (in micrometers)
     * @return true if prediction can be made
     */
    public boolean canPredict(double distanceToNextAnnotation) {
        int numPoints = dataPoints.size();

        if (numPoints >= MIN_POINTS_STANDARD) {
            return modelFitted;
        }

        if (numPoints >= MIN_POINTS_EARLY && distanceToNextAnnotation > EARLY_PREDICTION_DISTANCE_UM) {
            logger.info("Early prediction enabled: {} points, distance {:.1f}um > {:.1f}um threshold",
                    numPoints, distanceToNextAnnotation, EARLY_PREDICTION_DISTANCE_UM);
            return modelFitted;
        }

        return false;
    }

    /**
     * Predicts the Z-focus position for the given stage coordinates.
     *
     * @param stageX Stage X coordinate in micrometers
     * @param stageY Stage Y coordinate in micrometers
     * @return Predicted Z position, or empty if model not ready
     */
    public OptionalDouble predictZ(double stageX, double stageY) {
        if (!modelFitted) {
            return OptionalDouble.empty();
        }

        double predictedZ = a * stageX + b * stageY + c;

        logger.debug("Predicted Z for ({:.1f}, {:.1f}): {:.2f} um (plane: z = {:.6f}x + {:.6f}y + {:.2f})",
                stageX, stageY, predictedZ, a, b, c);

        return OptionalDouble.of(predictedZ);
    }

    /**
     * Calculates the distance from the last acquired point to a new position.
     *
     * @param stageX Target stage X coordinate
     * @param stageY Target stage Y coordinate
     * @return Distance in micrometers, or 0 if no points acquired yet
     */
    public double distanceFromLastPoint(double stageX, double stageY) {
        if (lastAcquiredPoint == null) {
            return 0.0;
        }
        double dx = stageX - lastAcquiredPoint[0];
        double dy = stageY - lastAcquiredPoint[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Fits a plane to the collected data points using least squares.
     *
     * <p>Solves the normal equations for z = ax + by + c:</p>
     * <pre>
     * | sum(x^2)  sum(xy)   sum(x) |   | a |   | sum(xz) |
     * | sum(xy)   sum(y^2)  sum(y) | * | b | = | sum(yz) |
     * | sum(x)    sum(y)    N      |   | c |   | sum(z)  |
     * </pre>
     */
    private void fitPlane() {
        int n = dataPoints.size();
        if (n < MIN_POINTS_EARLY) {
            modelFitted = false;
            return;
        }

        // Calculate sums for normal equations
        double sumX = 0, sumY = 0, sumZ = 0;
        double sumX2 = 0, sumY2 = 0;
        double sumXY = 0, sumXZ = 0, sumYZ = 0;

        for (double[] point : dataPoints) {
            double x = point[0];
            double y = point[1];
            double z = point[2];

            sumX += x;
            sumY += y;
            sumZ += z;
            sumX2 += x * x;
            sumY2 += y * y;
            sumXY += x * y;
            sumXZ += x * z;
            sumYZ += y * z;
        }

        // Build coefficient matrix A and right-hand side b
        // A * [a, b, c]^T = b
        // Using Cramer's rule to solve 3x3 system

        double[][] A = {
                {sumX2, sumXY, sumX},
                {sumXY, sumY2, sumY},
                {sumX, sumY, n}
        };

        double[] rhs = {sumXZ, sumYZ, sumZ};

        // Calculate determinant of A
        double detA = determinant3x3(A);

        if (Math.abs(detA) < 1e-10) {
            logger.warn("Plane fitting failed: singular matrix (collinear or coincident points)");
            modelFitted = false;
            return;
        }

        // Solve using Cramer's rule
        double[][] Aa = substituteColumn(A, rhs, 0);
        double[][] Ab = substituteColumn(A, rhs, 1);
        double[][] Ac = substituteColumn(A, rhs, 2);

        a = determinant3x3(Aa) / detA;
        b = determinant3x3(Ab) / detA;
        c = determinant3x3(Ac) / detA;

        modelFitted = true;

        // Calculate and log residual error
        double residualError = calculateResidualError();
        logger.info("Plane fitted with {} points: z = {:.6f}x + {:.6f}y + {:.2f}, residual error: {:.2f} um",
                n, a, b, c, residualError);
    }

    /**
     * Calculates the root mean square residual error of the fit.
     *
     * @return RMS error in micrometers
     */
    public double calculateResidualError() {
        if (!modelFitted || dataPoints.isEmpty()) {
            return Double.NaN;
        }

        double sumSqError = 0;
        for (double[] point : dataPoints) {
            double predicted = a * point[0] + b * point[1] + c;
            double error = point[2] - predicted;
            sumSqError += error * error;
        }

        return Math.sqrt(sumSqError / dataPoints.size());
    }

    /**
     * Calculates the determinant of a 3x3 matrix.
     */
    private static double determinant3x3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /**
     * Creates a copy of matrix with one column replaced by a vector.
     */
    private static double[][] substituteColumn(double[][] matrix, double[] vector, int col) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = (j == col) ? vector[i] : matrix[i][j];
            }
        }
        return result;
    }

    /**
     * Returns the number of data points collected.
     *
     * @return Number of Z-focus measurements
     */
    public int getPointCount() {
        return dataPoints.size();
    }

    /**
     * Returns whether the plane model has been fitted.
     *
     * @return true if model is ready for predictions
     */
    public boolean isModelFitted() {
        return modelFitted;
    }

    /**
     * Gets the plane coefficients.
     *
     * @return Array of [a, b, c] for z = ax + by + c, or null if not fitted
     */
    public double[] getPlaneCoefficients() {
        if (!modelFitted) {
            return null;
        }
        return new double[]{a, b, c};
    }

    /**
     * Resets the model, clearing all data points.
     */
    public void reset() {
        dataPoints.clear();
        a = b = c = 0.0;
        modelFitted = false;
        lastAcquiredPoint = null;
        logger.info("Z focus prediction model reset");
    }

    /**
     * Returns a copy of the collected data points for debugging/analysis.
     *
     * @return List of [stageX, stageY, focusZ] arrays
     */
    public List<double[]> getDataPoints() {
        List<double[]> copy = new ArrayList<>();
        for (double[] point : dataPoints) {
            copy.add(point.clone());
        }
        return copy;
    }
}
