package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Central controller for microscope operations, providing high-level methods
 * for stage control and coordinate transformation.
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
 * @since 1.0
 */
public class MicroscopeController {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeController.class);

    /** Singleton instance */
    private static MicroscopeController instance;

    /** Socket client for server communication */
    private final MicroscopeSocketClient socketClient;

    /** Current affine transform for coordinate conversion */
    private final AtomicReference<AffineTransform> currentTransform = new AtomicReference<>();

    /** Flag to track if we should use socket or fall back to CLI */
    private volatile boolean useSocketConnection = true;

    /** Resource bundle for command strings */
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

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

        // Check if socket connection is enabled
        this.useSocketConnection = QPPreferenceDialog.getUseSocketConnection();

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
        if (autoConnect && useSocketConnection) {
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
     * @throws InterruptedException if the operation is interrupted
     */
    public double[] getStagePositionXY() throws IOException, InterruptedException {
        if (!useSocketConnection) {
            // Fall back to CLI if socket is disabled
            return getStagePositionXYCLI();
        }

        try {
            double[] position = socketClient.getStageXY();
            logger.info("Stage XY position: ({}, {})", position[0], position[1]);
            return position;
        } catch (IOException e) {
            handleSocketError(e, "Failed to get stage XY position");
            // Retry with CLI fallback
            return getStagePositionXYCLI();
        }
    }

    /**
     * Queries the microscope for its current Z stage position.
     *
     * @return The Z coordinate in microns
     * @throws IOException if communication fails
     * @throws InterruptedException if the operation is interrupted
     */
    public double getStagePositionZ() throws IOException, InterruptedException {
        if (!useSocketConnection) {
            return getStagePositionZCLI();
        }

        try {
            double z = socketClient.getStageZ();
            logger.info("Stage Z position: {}", z);
            return z;
        } catch (IOException e) {
            handleSocketError(e, "Failed to get stage Z position");
            return getStagePositionZCLI();
        }
    }

    /**
     * Queries the current rotation angle (in ticks) from the stage.
     *
     * @return The rotation angle in ticks
     * @throws IOException if communication fails
     * @throws InterruptedException if the operation is interrupted
     */
    public double getStagePositionR() throws IOException, InterruptedException {
        if (!useSocketConnection) {
            return getStagePositionRCLI();
        }

        try {
            double angle = socketClient.getStageR();
            logger.info("Stage rotation angle: {} ticks", angle);
            return angle;
        } catch (IOException e) {
            handleSocketError(e, "Failed to get stage rotation angle");
            return getStagePositionRCLI();
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

        if (!useSocketConnection) {
            moveStageXYCLI(x, y);
            return;
        }

        try {
            socketClient.moveStageXY(x, y);
            logger.info("Successfully moved stage to XY: ({}, {})", x, y);
        } catch (IOException e) {
            handleSocketError(e, "Failed to move stage XY");
            moveStageXYCLI(x, y);
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

        if (!useSocketConnection) {
            moveStageZCLI(z);
            return;
        }

        try {
            socketClient.moveStageZ(z);
            logger.info("Successfully moved stage to Z: {}", z);
        } catch (IOException e) {
            handleSocketError(e, "Failed to move stage Z");
            moveStageZCLI(z);
        }
    }

    /**
     * Rotates the stage to the given angle.
     *
     * @param angle The target rotation angle in ticks
     */
    public void moveStageR(double angle) {
        if (!useSocketConnection) {
            moveStageRCLI(angle);
            return;
        }

        try {
            socketClient.moveStageR(angle);
            logger.info("Successfully rotated stage to {} ticks", angle);
        } catch (IOException e) {
            handleSocketError(e, "Failed to rotate stage");
            moveStageRCLI(angle);
        }
    }

    /**
     * Starts an acquisition workflow on the microscope.
     *
     * @param yamlPath Path to YAML configuration
     * @param projectsFolder Projects folder path
     * @param sampleLabel Sample label
     * @param scanType Scan type
     * @param regionName Region name
     * @param angles Rotation angles for acquisition
     * @throws IOException if communication fails
     */
    public void startAcquisition(String yamlPath, String projectsFolder, String sampleLabel,
                                 String scanType, String regionName, double[] angles) throws IOException {
        if (!useSocketConnection) {
            throw new IOException("Acquisition requires socket connection");
        }

        try {
            socketClient.startAcquisition(yamlPath, projectsFolder, sampleLabel,
                    scanType, regionName, angles);
            logger.info("Started acquisition workflow");
        } catch (IOException e) {
            handleSocketError(e, "Failed to start acquisition");
            throw e;
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

    /**
     * Enables or disables socket connection mode.
     * When disabled, falls back to CLI commands.
     *
     * @param useSocket true to use socket, false for CLI
     */
    public void setUseSocketConnection(boolean useSocket) {
        this.useSocketConnection = useSocket;
        logger.info("Socket connection mode: {}", useSocket ? "ENABLED" : "DISABLED (using CLI)");
    }

    /**
     * Handles socket errors and optionally falls back to CLI.
     *
     * @param e The exception that occurred
     * @param operation Description of the failed operation
     */
    private void handleSocketError(IOException e, String operation) {
        logger.error("{}: {}", operation, e.getMessage());

        // Update connection status
        PersistentPreferences.setSocketLastConnectionStatus("Error: " + e.getMessage());

        if (PersistentPreferences.getSocketAutoFallbackToCLI()) {
            logger.info("Falling back to CLI for this operation");
        } else {
            UIFunctions.notifyUserOfError(
                    operation + "\n" + e.getMessage() +
                            "\n\nConsider enabling CLI fallback in preferences.",
                    "Communication Error"
            );
        }
    }

    // ========== CLI Fallback Methods ==========
    // These methods are kept for backwards compatibility and fallback

    private double[] getStagePositionXYCLI() throws IOException, InterruptedException {
        String cmd = BUNDLE.getString("command.getStagePositionXY");
        String out = qupath.ext.qpsc.service.CliExecutor.execCommandAndGetOutput(10, cmd);
        logger.info("CLI getStagePositionXY output: {}", out);

        String cleaned = out.replaceAll("[(),]", "").trim();
        String[] parts = cleaned.split("\\s+");
        if (parts.length < 2) {
            throw new IOException("Unexpected output for XY position: " + out);
        }

        return new double[]{
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1])
        };
    }

    private double getStagePositionZCLI() throws IOException, InterruptedException {
        String cmd = BUNDLE.getString("command.getStagePositionZ");
        String out = qupath.ext.qpsc.service.CliExecutor.execCommandAndGetOutput(10, cmd);
        return Double.parseDouble(out.trim());
    }

    private double getStagePositionRCLI() throws IOException, InterruptedException {
        String cmd = BUNDLE.getString("command.getStagePositionP");
        String out = qupath.ext.qpsc.service.CliExecutor.execCommandAndGetOutput(10, cmd);
        String cleaned = out.replaceAll("[(),]", "").trim();
        return Double.parseDouble(cleaned);
    }

    private void moveStageXYCLI(double x, double y) {
        try {
            String cmd = BUNDLE.getString("command.moveStageXY");
            var res = qupath.ext.qpsc.service.CliExecutor.execComplexCommand(
                    10, null, cmd, "-x", Double.toString(x), "-y", Double.toString(y)
            );

            if (res.timedOut() || res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "CLI Move XY failed: " + res.stderr(),
                        "Stage Move Error"
                );
            }
        } catch (Exception e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move XY: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    private void moveStageZCLI(double z) {
        try {
            String cmd = BUNDLE.getString("command.moveStageZ");
            var res = qupath.ext.qpsc.service.CliExecutor.execComplexCommand(
                    10, null, cmd, "-z", Double.toString(z)
            );

            if (res.timedOut() || res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "CLI Move Z failed: " + res.stderr(),
                        "Stage Move Error"
                );
            }
        } catch (Exception e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move Z: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    private void moveStageRCLI(double angle) {
        try {
            String cmd = BUNDLE.getString("command.moveStageP");
            var res = qupath.ext.qpsc.service.CliExecutor.execComplexCommand(
                    10, null, cmd, "-angle", Double.toString(angle)
            );

            if (res.timedOut() || res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "CLI Move R failed: " + res.stderr(),
                        "Stage Move Error"
                );
            }
        } catch (Exception e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move R: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }
}