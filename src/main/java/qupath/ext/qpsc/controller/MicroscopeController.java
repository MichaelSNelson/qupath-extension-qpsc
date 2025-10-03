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

// Remove the isWithinBoundsXY and isWithinBoundsZ methods entirely
// Update the moveStageXY method:

    /**
     * Moves the stage in X,Y only. Z position is not affected.
     *
     * @param x Target X coordinate in microns
     * @param y Target Y coordinate in microns
     */
    public void moveStageXY(double x, double y) {
        // Validate bounds using ConfigManager directly
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        if (!mgr.isWithinStageBounds(x, y)) {
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
        // Validate bounds using ConfigManager directly
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        if (!mgr.isWithinStageBounds(z)) {
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
     * Gets the current camera field of view in microns.
     * This queries the microscope server for the actual FOV dimensions,
     * which accounts for the current objective and camera configuration.
     *
     * @return Array containing [width, height] in microns
     * @throws IOException if communication fails
     */
    public double[] getCameraFOV() throws IOException {
        try {
            double[] fov = socketClient.getCameraFOV();
            logger.info("Camera FOV: {} x {} microns", fov[0], fov[1]);
            return fov;
        } catch (IOException e) {
            logger.error("Failed to get camera FOV: {}", e.getMessage());
            throw new IOException("Failed to get camera FOV via socket", e);
        }
    }


    /**
     * Calculates camera field of view for a specific modality from configuration files.
     * This method reads pixel size and detector dimensions from the config to compute FOV,
     * avoiding the need to query the server which doesn't know the active objective.
     *
     * @param modality The imaging modality (e.g., "bf_10x", "bf_40x", "ppm_20x")
     * @return Array containing [width, height] in microns
     * @throws IOException if configuration is missing or invalid
     */
    public double[] getCameraFOVFromConfig(String modality) throws IOException {
        logger.info("Calculating camera FOV from config for modality: {}", modality);

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        // Find the acquisition profile for this modality to get objective and detector
        List<Object> profiles = mgr.getList("acq_profiles_new", "profiles");
        if (profiles == null || profiles.isEmpty()) {
            throw new IOException("No acquisition profiles defined in configuration");
        }

        String objectiveId = null;
        String detectorId = null;
        String matchedModalityName = null;

        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modality;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        // Search for a matching profile
        for (Object profileObj : profiles) {
            @SuppressWarnings("unchecked")
            Map<String, Object> profile = (Map<String, Object>) profileObj;

            String profileModality = (String) profile.get("modality");

            // Check various matching patterns
            if (profileModality != null &&
                    (profileModality.equals(baseModality) ||
                            profileModality.equals(modality) ||
                            modality.startsWith(profileModality))) {

                objectiveId = (String) profile.get("objective");
                detectorId = (String) profile.get("detector");
                matchedModalityName = profileModality;

                // If we found both, we can break
                if (objectiveId != null && detectorId != null) {
                    logger.debug("Found profile match: modality={}, objective={}, detector={}",
                            profileModality, objectiveId, detectorId);
                    break;
                }
            }
        }

        // If we couldn't find a complete profile, try to infer from modality name
        if (objectiveId == null || detectorId == null) {
            // As a fallback, try to find any profile with this modality type
            String modalityType = baseModality.split("_")[0]; // e.g., "bf" or "ppm"

            for (Object profileObj : profiles) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = (Map<String, Object>) profileObj;

                String profileModality = (String) profile.get("modality");
                if (profileModality != null && profileModality.startsWith(modalityType)) {
                    if (detectorId == null) {
                        detectorId = (String) profile.get("detector");
                    }
                    // Try to match objective by magnification in modality name
                    if (objectiveId == null && baseModality.contains("10x")) {
                        String tempObjective = (String) profile.get("objective");
                        if (tempObjective != null && tempObjective.contains("10X")) {
                            objectiveId = tempObjective;
                            matchedModalityName = profileModality;
                        }
                    } else if (objectiveId == null && baseModality.contains("20x")) {
                        String tempObjective = (String) profile.get("objective");
                        if (tempObjective != null && tempObjective.contains("20X")) {
                            objectiveId = tempObjective;
                            matchedModalityName = profileModality;
                        }
                    } else if (objectiveId == null && baseModality.contains("40x")) {
                        String tempObjective = (String) profile.get("objective");
                        if (tempObjective != null && tempObjective.contains("40X")) {
                            objectiveId = tempObjective;
                            matchedModalityName = profileModality;
                        }
                    }
                }
            }
        }

        if (objectiveId == null || detectorId == null || matchedModalityName == null) {
            throw new IOException(String.format(
                    "Could not find acquisition profile for modality '%s'. Please check configuration.",
                    modality));
        }

        // Get pixel size using the three-parameter method
        double pixelSize = mgr.getModalityPixelSize(matchedModalityName, objectiveId, detectorId);
        if (pixelSize <= 0) {
            throw new IOException(String.format(
                    "Invalid pixel size (%.4f) for modality '%s' with objective '%s' and detector '%s'",
                    pixelSize, matchedModalityName, objectiveId, detectorId));
        }

        // Get detector dimensions
        int[] dimensions = mgr.getDetectorDimensions(detectorId);
        if (dimensions == null) {
            throw new IOException(String.format(
                    "No detector dimensions found for detector '%s'",
                    detectorId));
        }

        double fovWidth = dimensions[0] * pixelSize;
        double fovHeight = dimensions[1] * pixelSize;

        logger.info("CRITICAL FOV CALCULATION:");
        logger.info("  Modality: {}, Objective: {}, Detector: {}", matchedModalityName, objectiveId, detectorId);
        logger.info("  Pixel size from config: {} µm/pixel", pixelSize);
        logger.info("  Detector dimensions: {}x{} pixels", dimensions[0], dimensions[1]);
        logger.info("  Calculated FOV: {} x {} µm", fovWidth, fovHeight);
        logger.info("  FOV calculation: {}px * {}µm/px = {}µm width", dimensions[0], pixelSize, fovWidth);

        return new double[]{fovWidth, fovHeight};
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