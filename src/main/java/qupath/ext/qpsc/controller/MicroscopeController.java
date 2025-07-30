package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Central controller for microscope operations, providing high-level methods
 * for stage control and coordinate transformation via socket communication only.
 *
 * <p>This controller acts as a facade for the microscope hardware, managing:
 * <ul>
 *   <li>Socket-based communication with the Python microscope server</li>
 *   <li>Coordinate transformations between QuPath and stage coordinates</li>
 *   <li>Stage movement and position queries</li>
 *   <li>Error handling and user notifications</li>
 * </ul>
 *
 * <p>The controller uses a singleton pattern to ensure a single connection
 * to the microscope server throughout the application lifecycle.</p>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class MicroscopeController {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeController.class);

    /** Singleton instance */
    private static MicroscopeController instance;

    /** Socket client for server communication */
    private final MicroscopeSocketClient socketClient;

    /** Current affine transform for coordinate conversion */
    private final AtomicReference<AffineTransform> currentTransform = new AtomicReference<>();

    /**
     * Private constructor for singleton pattern.
     * Initializes the socket connection to the microscope server.
     */
    private MicroscopeController() {
        // Get connection parameters from preferences
        String host = QPPreferenceDialog.getMicroscopeServerHost();
        int port = QPPreferenceDialog.getMicroscopeServerPort();
        boolean autoConnect = QPPreferenceDialog.getAutoConnectToServer();

        // Get advanced settings from persistent preferences
        int connectTimeout = PersistentPreferences.getSocketConnectionTimeoutMs();
        int readTimeout = PersistentPreferences.getSocketReadTimeoutMs();
        int maxReconnects = PersistentPreferences.getSocketMaxReconnectAttempts();
        long reconnectDelay = PersistentPreferences.getSocketReconnectDelayMs();
        long healthCheckInterval = PersistentPreferences.getSocketHealthCheckIntervalMs();

        // Initialize socket client with configuration from preferences
        this.socketClient = new MicroscopeSocketClient(
                host,
                port,
                connectTimeout,
                readTimeout,
                maxReconnects,
                reconnectDelay,
                healthCheckInterval
        );

        // Attempt initial connection if auto-connect is enabled
        if (autoConnect) {
            try {
                socketClient.connect();
                logger.info("Successfully connected to microscope server at {}:{}", host, port);
                PersistentPreferences.setSocketLastConnectionStatus("Connected");
                PersistentPreferences.setSocketLastConnectionTime(
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
                );
            } catch (IOException e) {
                logger.warn("Failed to connect to microscope server on startup: {}", e.getMessage());
                logger.info("Will attempt to connect when first command is sent");
                PersistentPreferences.setSocketLastConnectionStatus("Failed: " + e.getMessage());
            }
        }

        // Register shutdown hook to cleanly disconnect
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socketClient.close();
            } catch (Exception e) {
                logger.debug("Error closing socket client during shutdown", e);
            }
        }));
    }

    /**
     * Gets the singleton instance of the MicroscopeController.
     *
     * @return The singleton instance
     */
    public static synchronized MicroscopeController getInstance() {
        if (instance == null) {
            instance = new MicroscopeController();
        }
        return instance;
    }

    /**
     * Queries the microscope for its current X,Y stage position.
     *
     * @return A two-element array [x, y] in microns
     * @throws IOException if communication fails
     */
    public double[] getStagePositionXY() throws IOException {
        try {
            double[] position = socketClient.getStageXY();
            logger.info("Stage XY position: ({}, {})", position[0], position[1]);
            return position;
        } catch (IOException e) {
            logger.error("Failed to get stage XY position: {}", e.getMessage());
            throw new IOException("Failed to get stage XY position via socket", e);
        }
    }

    /**
     * Queries the microscope for its current Z stage position.
     *
     * @return The Z coordinate in microns
     * @throws IOException if communication fails
     */
    public double getStagePositionZ() throws IOException {
        try {
            double z = socketClient.getStageZ();
            logger.info("Stage Z position: {}", z);
            return z;
        } catch (IOException e) {
            logger.error("Failed to get stage Z position: {}", e.getMessage());
            throw new IOException("Failed to get stage Z position via socket", e);
        }
    }

    /**
     * Queries the current rotation angle (in ticks) from the stage.
     *
     * @return The rotation angle in ticks
     * @throws IOException if communication fails
     */
    public double getStagePositionR() throws IOException {
        try {
            double angle = socketClient.getStageR();
            logger.info("Stage rotation angle: {} ticks", angle);
            return angle;
        } catch (IOException e) {
            logger.error("Failed to get stage rotation angle: {}", e.getMessage());
            throw new IOException("Failed to get stage rotation angle via socket", e);
        }
    }

    /**
     * Moves the stage in X,Y only. Z position is not affected.
     *
     * @param x Target X coordinate in microns
     * @param y Target Y coordinate in microns
     */
    public void moveStageXY(double x, double y) {
        // Validate bounds
        if (!isWithinBoundsXY(x, y)) {
            UIFunctions.notifyUserOfError(
                    String.format("Target position (%.2f, %.2f) is outside stage limits", x, y),
                    "Stage Limits Exceeded"
            );
            return;
        }

        try {
            socketClient.moveStageXY(x, y);
            logger.info("Successfully moved stage to XY: ({}, {})", x, y);
        } catch (IOException e) {
            logger.error("Failed to move stage XY: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to move stage XY: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Moves the stage in Z only.
     *
     * @param z Target Z coordinate in microns
     */
    public void moveStageZ(double z) {
        // Validate bounds
        if (!isWithinBoundsZ(z)) {
            UIFunctions.notifyUserOfError(
                    String.format("Target Z position %.2f is outside stage limits", z),
                    "Stage Limits Exceeded"
            );
            return;
        }

        try {
            socketClient.moveStageZ(z);
            logger.info("Successfully moved stage to Z: {}", z);
        } catch (IOException e) {
            logger.error("Failed to move stage Z: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to move stage Z: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Rotates the stage to the given angle.
     *
     * @param angle The target rotation angle in ticks
     */
    public void moveStageR(double angle) {
        try {
            socketClient.moveStageR(angle);
            logger.info("Successfully rotated stage to {} ticks", angle);
        } catch (IOException e) {
            logger.error("Failed to rotate stage: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to rotate stage: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Gets the underlying socket client for advanced operations.
     *
     * @return The socket client instance
     */
    public MicroscopeSocketClient getSocketClient() {
        return socketClient;
    }

    /**
     * Starts an acquisition workflow on the microscope.
     * Uses the builder pattern to support any combination of parameters
     * required by different microscope types and modalities.
     *
     * @param builder Pre-configured acquisition command builder
     * @throws IOException if communication fails
     */
    public void startAcquisition(AcquisitionCommandBuilder builder) throws IOException {
        try {
            socketClient.startAcquisition(builder);
            logger.info("Started acquisition workflow");
        } catch (IOException e) {
            logger.error("Failed to start acquisition: {}", e.getMessage());
            throw new IOException("Failed to start acquisition via socket", e);
        }
    }

    /**
     * Moves the microscope stage to the center of the given tile.
     *
     * @param tile The tile to move to
     */
    public void onMoveButtonClicked(PathObject tile) {
        if (currentTransform.get() == null) {
            UIFunctions.notifyUserOfError(
                    "No transformation set. Please run alignment workflow first.",
                    "No Transform"
            );
            return;
        }

        // Compute stage coordinates from QuPath coordinates
        double[] qpCoords = {tile.getROI().getCentroidX(), tile.getROI().getCentroidY()};
        double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                qpCoords, currentTransform.get()
        );

        logger.info("Moving to tile center - QuPath: ({}, {}) -> Stage: ({}, {})",
                qpCoords[0], qpCoords[1], stageCoords[0], stageCoords[1]);

        moveStageXY(stageCoords[0], stageCoords[1]);
    }

    /**
     * Sets the current affine transform for coordinate conversion.
     *
     * @param transform The transform to use
     */
    public void setCurrentTransform(AffineTransform transform) {
        this.currentTransform.set(transform);
        logger.info("Updated current transform: {}", transform);
    }

    /**
     * Gets the current affine transform.
     *
     * @return The current transform, or null if none set
     */
    public AffineTransform getCurrentTransform() {
        return this.currentTransform.get();
    }

    /**
     * Checks if the given XY coordinates are within stage bounds.
     *
     * @param x X coordinate in microns
     * @param y Y coordinate in microns
     * @return true if within bounds, false otherwise
     */
    public boolean isWithinBoundsXY(double x, double y) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var xlimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "xlimit");
        var ylimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "ylimit");

        if (xlimits == null || ylimits == null) {
            logger.error("Stage limits missing from config");
            return false;
        }

        double xLow = ((Number)xlimits.get("low")).doubleValue();
        double xHigh = ((Number)xlimits.get("high")).doubleValue();
        double yLow = ((Number)ylimits.get("low")).doubleValue();
        double yHigh = ((Number)ylimits.get("high")).doubleValue();

        boolean withinX = (x >= Math.min(xLow, xHigh) && x <= Math.max(xLow, xHigh));
        boolean withinY = (y >= Math.min(yLow, yHigh) && y <= Math.max(yLow, yHigh));

        return withinX && withinY;
    }

    /**
     * Checks if the given Z coordinate is within stage bounds.
     *
     * @param z Z coordinate in microns
     * @return true if within bounds, false otherwise
     */
    public boolean isWithinBoundsZ(double z) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var zlimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "zlimit");

        if (zlimits == null) {
            logger.error("Stage Z limits missing from config");
            return false;
        }

        double zLow = ((Number)zlimits.get("low")).doubleValue();
        double zHigh = ((Number)zlimits.get("high")).doubleValue();

        return (z >= Math.min(zLow, zHigh) && z <= Math.max(zLow, zHigh));
    }

    /**
     * Checks if the socket client is connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return socketClient.isConnected();
    }

    /**
     * Manually connects to the microscope server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        socketClient.connect();
    }

    /**
     * Manually disconnects from the microscope server.
     */
    public void disconnect() {
        socketClient.disconnect();
    }
}