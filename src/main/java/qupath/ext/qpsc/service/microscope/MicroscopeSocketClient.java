package qupath.ext.qpsc.service.microscope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Socket-based client for communicating with the Python microscope control server.
 * This class manages a persistent connection to the microscope server and provides
 * thread-safe command execution with automatic reconnection capabilities.
 *
 * <p>The client uses a binary protocol with fixed-length commands (8 bytes) and
 * network byte order (big-endian) for numeric data transmission.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Connection pooling with automatic reconnection</li>
 *   <li>Thread-safe command execution</li>
 *   <li>Configurable timeouts and retry policies</li>
 *   <li>Health monitoring with heartbeat checks</li>
 *   <li>Graceful shutdown and resource cleanup</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class MicroscopeSocketClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeSocketClient.class);

    // Protocol constants
    /** End marker expected by Python server to indicate message completion */
    private static final String END_MARKER = "ENDOFSTR";

    // Connection parameters
    private final String host;
    private final int port;
    private final int connectTimeout;
    private final int readTimeout;

    // Socket and streams
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private final Object socketLock = new Object();

    // Connection state
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    // Progress monitoring state - tracks last time progress was made or significant event occurred
    private final AtomicLong lastProgressUpdateTime = new AtomicLong(System.currentTimeMillis());

    // Acquisition error tracking
    private volatile String lastFailureMessage = null;

    // Final Z position from completed acquisition (for tilt correction model)
    private volatile Double lastAcquisitionFinalZ = null;

    // Reconnection handling
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "MicroscopeReconnect");
                t.setDaemon(true);
                return t;
            }
    );

    // Health monitoring
    private final ScheduledExecutorService healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "MicroscopeHealthCheck");
                t.setDaemon(true);
                return t;
            }
    );

    // Configuration
    private final int maxReconnectAttempts;
    private final long reconnectDelayMs;
    private final long healthCheckIntervalMs;

    /**
     * Protocol command enumeration matching the Python server commands.
     * Each command is exactly 8 bytes with trailing underscores for padding.
     */
    public enum Command {
        /** Get XY stage position */
        GETXY("getxy___"),
        /** Get Z stage position */
        GETZ("getz____"),
        /** Move Z stage */
        MOVEZ("move_z__"),
        /** Move XY stage */
        MOVE("move____"),
        /** Get rotation angle in ticks */
        GETR("getr____"),
        /** Move rotation stage */
        MOVER("move_r__"),
        /** Shutdown server */
        SHUTDOWN("shutdown"),
        /** Disconnect client */
        DISCONNECT("quitclnt"),
        /** Start acquisition */
        ACQUIRE("acquire_"),
        /** Background acquisition */
        BGACQUIRE("bgacquir"),
        /** Test standard autofocus at current position */
        TESTAF("testaf__"),
        /** Test adaptive autofocus at current position */
        TESTADAF("testadaf"),
        /** Get acquisition status */
        STATUS("status__"),
        /** Get acquisition progress */
        PROGRESS("progress"),
        /** Cancel acquisition */
        CANCEL("cancel__"),
        GETFOV("getfov__"),
        /** Check if manual focus is requested */
        REQMANF("reqmanf_"),
        /** Acknowledge manual focus - retry autofocus */
        ACKMF("ackmf___"),
        /** Skip autofocus retry - use current focus */
        SKIPAF("skipaf__"),
        /** Run autofocus parameter benchmark */
        AFBENCH("afbench_"),
        /** PPM birefringence maximization test */
        PPMBIREF("ppmbiref");

        private final byte[] value;

        Command(String value) {
            if (value.length() != 8) {
                throw new IllegalArgumentException("Command must be exactly 8 bytes");
            }
            this.value = value.getBytes();
        }

        public byte[] getValue() {
            return value.clone();
        }
    }

    /**
     * Acquisition state enumeration matching the Python server states.
     */
    public enum AcquisitionState {
        IDLE,
        RUNNING,
        CANCELLING,
        CANCELLED,
        COMPLETED,
        FAILED;

        /**
         * Parse state from server response string.
         * @param stateStr 16-byte padded state string from server
         * @return Parsed acquisition state
         */
        public static AcquisitionState fromString(String stateStr) {
            String trimmed = stateStr.trim().toUpperCase();
            try {
                return AcquisitionState.valueOf(trimmed);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown acquisition state: {}", trimmed);
                return IDLE;
            }
        }
    }
    /**
     * Represents acquisition progress information.
     */
    public static class AcquisitionProgress {
        public final int current;
        public final int total;

        public AcquisitionProgress(int current, int total) {
            this.current = current;
            this.total = total;
        }

        public double getPercentage() {
            return total > 0 ? (100.0 * current / total) : 0.0;
        }

        @Override
        public String toString() {
            return String.format("%d/%d (%.1f%%)", current, total, getPercentage());
        }
    }

    /**
     * Creates a new microscope socket client with default configuration.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     */
    public MicroscopeSocketClient(String host, int port) {
        this(host, port, 3000, 5000, 3, 5000, 30000);
    }

    /**
     * Creates a new microscope socket client with custom configuration.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     * @param connectTimeout Connection timeout in milliseconds
     * @param readTimeout Read timeout in milliseconds
     * @param maxReconnectAttempts Maximum number of reconnection attempts
     * @param reconnectDelayMs Delay between reconnection attempts in milliseconds
     * @param healthCheckIntervalMs Interval between health checks in milliseconds
     */
    public MicroscopeSocketClient(String host, int port, int connectTimeout, int readTimeout,
                                  int maxReconnectAttempts, long reconnectDelayMs, long healthCheckIntervalMs) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelayMs = reconnectDelayMs;
        this.healthCheckIntervalMs = healthCheckIntervalMs;

        // Start health monitoring
        startHealthMonitoring();
    }

    /**
     * Establishes connection to the microscope server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        synchronized (socketLock) {
            if (connected.get()) {
                logger.debug("Already connected to {}:{}", host, port);
                return;
            }

            logger.info("Connecting to microscope server at {}:{}", host, port);

            try {
                socket = new Socket();
                socket.setSoTimeout(readTimeout);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm for low latency

                socket.connect(new InetSocketAddress(host, port), connectTimeout);

                input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                connected.set(true);
                lastActivityTime.set(System.currentTimeMillis());

                logger.info("Successfully connected to microscope server");

            } catch (IOException e) {
                cleanup();
                throw new IOException("Failed to connect to microscope server: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Disconnects from the microscope server gracefully.
     */
    public void disconnect() {
        synchronized (socketLock) {
            if (!connected.get()) {
                return;
            }

            try {
                // Send disconnect command
                sendCommand(Command.DISCONNECT);
            } catch (Exception e) {
                logger.debug("Error sending disconnect command", e);
            }

            cleanup();
            logger.info("Disconnected from microscope server");
        }
    }
    /**
     * Gets the current camera field of view in microns.
     * This returns the actual FOV dimensions accounting for the current objective
     * and camera settings, eliminating the need for manual calculations.
     *
     * @return Array containing [width, height] in microns
     * @throws IOException if communication fails
     */
    public double[] getCameraFOV() throws IOException {
        byte[] response = executeCommand(Command.GETFOV, null, 8);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float fovX = buffer.getFloat();
        float fovY = buffer.getFloat();

        logger.info("Camera FOV: {} x {} microns", fovX, fovY);
        return new double[] { fovX, fovY };
    }



    /**
     * Gets the current XY position of the microscope stage.
     *
     * @return Array containing [x, y] coordinates in microns
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double[] getStageXY() throws IOException {
        byte[] response = executeCommand(Command.GETXY, null, 8);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HW_ERROR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting XY position. Check that MicroManager is running and the XY stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float x = buffer.getFloat();
        float y = buffer.getFloat();

        logger.trace("Stage XY position: ({}, {})", x, y);
        return new double[] { x, y };
    }

    /**
     * Gets the current Z position of the microscope stage.
     *
     * @return Z coordinate in microns
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double getStageZ() throws IOException {
        byte[] response = executeCommand(Command.GETZ, null, 4);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HWERR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting Z position. Check that MicroManager is running and the Z stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float z = buffer.getFloat();
        logger.trace("Stage Z position: {}", z);
        return z;
    }

    /**
     * Gets the current rotation angle (in ticks) of the stage.
     *
     * @return Rotation angle in ticks (double the angle)
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double getStageR() throws IOException {
        byte[] response = executeCommand(Command.GETR, null, 4);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HWERR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting rotation angle. Check that MicroManager is running and the rotation stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float angle = buffer.getFloat();
        logger.debug("Stage rotation ticks: {}", angle);
        return angle;
    }

    /**
     * Moves the stage to the specified XY position.
     *
     * @param x Target X coordinate in microns
     * @param y Target Y coordinate in microns
     * @throws IOException if communication fails
     */
    public void moveStageXY(double x, double y) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) x);
        buffer.putFloat((float) y);

        executeCommand(Command.MOVE, buffer.array(), 0);
        logger.info("Moved stage to XY position: ({}, {})", x, y);
    }

    /**
     * Moves the stage to the specified Z position.
     *
     * @param z Target Z coordinate in microns
     * @throws IOException if communication fails
     */
    public void moveStageZ(double z) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) z);

        executeCommand(Command.MOVEZ, buffer.array(), 0);
        logger.info("Moved stage to Z position: {}", z);
    }

    /**
     * Rotates the stage to the specified angle.
     *
     * @param angle Target rotation angle in degrees
     * @throws IOException if communication fails
     */
    public void moveStageR(double angle) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) angle);

        executeCommand(Command.MOVER, buffer.array(), 0);
        logger.info("Rotated stage to angle: {}", angle);
    }

    /**
     * Starts an acquisition workflow on the server using a command builder.
     * This single method handles all acquisition types with their specific parameters.
     *
     * @param builder Pre-configured acquisition command builder
     * @throws IOException if communication fails
     */
    public void startAcquisition(AcquisitionCommandBuilder builder) throws IOException {
        String message = builder.buildSocketMessage() + " " + END_MARKER;
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending acquisition command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command (8 bytes)
                output.write(Command.ACQUIRE.getValue());
                output.flush();
                logger.debug("Sent ACQUIRE command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent acquisition message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                // Temporarily increase timeout for acknowledgment read
                // Server needs time to parse message and start acquisition thread
                int originalTimeout = socket.getSoTimeout();
                socket.setSoTimeout(30000); // 30 seconds for acknowledgment

                try {
                    // Wait for acknowledgment from server (16 bytes like STATUS response)
                    byte[] ackResponse = new byte[16];
                    input.readFully(ackResponse);
                    String ackStr = new String(ackResponse, StandardCharsets.UTF_8).trim();

                    if (!ackStr.startsWith("STARTED")) {
                        throw new IOException("Unexpected acquisition acknowledgment: " + ackStr);
                    }

                    logger.info("Acquisition acknowledged by server: {}", ackStr);
                } finally {
                    socket.setSoTimeout(originalTimeout);
                }

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Acquisition command sent successfully");

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Failed to send acquisition command", e));
                throw new IOException("Failed to send acquisition command", e);
            }
        }
    }

    /**
     * Starts a background acquisition workflow on the server for flat field correction.
     * This method uses the BGACQUIRE command with custom parameters that match the
     * server's expected format (--yaml, --output, --modality, --angles, --exposures).
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for background images
     * @param modality Modality name (e.g., "ppm")
     * @param angles Angle values in parentheses format (e.g., "(-5.0,0.0,5.0,90.0)")
     * @param exposures Exposure values in parentheses format (e.g., "(120.0,250.0,60.0,1.2)")
     *                  NOTE: These are ignored by server with adaptive exposure enabled
     * @return Map of angle (degrees) to final exposure time (ms) used by Python server
     * @throws IOException if communication fails
     */
    public Map<Double, Double> startBackgroundAcquisition(String yamlPath, String outputPath, String modality,
                                           String angles, String exposures) throws IOException {

        // Build BGACQUIRE-specific command message
        String message = String.format("--yaml %s --output %s --modality %s --angles %s --exposures %s %s",
                yamlPath, outputPath, modality, angles, exposures, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending background acquisition command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for background acquisition
            // Adaptive exposure requires multiple iterations per angle (typically 2-5, max 10)
            // With 4 angles, this can take 60-120 seconds. Allow 3 minutes to be safe.
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(180000); // 3 minutes for background acquisition with adaptive exposure
                    logger.debug("Increased socket timeout to 180s for background acquisition");
                }

                // Send BGACQUIRE command (8 bytes)
                output.write(Command.BGACQUIRE.getValue());
                output.flush();
                logger.debug("Sent BGACQUIRE command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent background acquisition message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Background acquisition command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected background acquisition: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for background acquisition to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Background acquisition failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse final exposures from SUCCESS response
                    // Format: SUCCESS:/path|angle1:exposure1,angle2:exposure2,...
                    Map<Double, Double> finalExposures = new HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        // Check if exposures are included (parts[1])
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String exposuresStr = parts[1].trim();
                            logger.info("Parsing final exposures from response: {}", exposuresStr);

                            String[] exposurePairs = exposuresStr.split(",");
                            for (String pair : exposurePairs) {
                                String[] angleExposure = pair.split(":");
                                if (angleExposure.length == 2) {
                                    try {
                                        double angle = Double.parseDouble(angleExposure[0].trim());
                                        double exposure = Double.parseDouble(angleExposure[1].trim());
                                        finalExposures.put(angle, exposure);
                                        logger.debug("  Angle {}° -> {}ms", angle, exposure);
                                    } catch (NumberFormatException e) {
                                        logger.warn("Failed to parse angle:exposure pair: {}", pair);
                                    }
                                }
                            }
                            logger.info("Parsed {} final exposure values from server", finalExposures.size());
                        } else {
                            logger.warn("No exposure data in response (old server version?)");
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return finalExposures;
                    }
                } else {
                    throw new IOException("No final response received from background acquisition");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Background acquisition error", e));
                throw new IOException("Background acquisition error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Test autofocus at current microscope position with diagnostic output.
     *
     * This command performs autofocus using settings from the autofocus_{microscope}.yml
     * configuration file and generates a detailed diagnostic plot showing:
     * - Focus curve with raw scores and interpolated curve
     * - Z positions tested and scores achieved
     * - Comparison of raw best vs interpolated best focus position
     * - Summary statistics and parameter settings
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for diagnostic plots
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Map with keys: "plot_path", "initial_z", "final_z", "z_shift"
     * @throws IOException if communication fails or autofocus test fails
     */
    public Map<String, String> testAutofocus(String yamlPath, String outputPath, String objective)
            throws IOException {

        // Build TESTAF-specific command message
        String message = String.format("--yaml %s --output %s --objective %s %s",
                yamlPath, outputPath, objective, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending autofocus test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for autofocus test
            // Autofocus scan can take 30-60 seconds depending on n_steps
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(120000); // 2 minutes for autofocus test
                    logger.debug("Increased socket timeout to 120s for autofocus test");
                }

                // Send TESTAF command (8 bytes)
                output.write(Command.TESTAF.getValue());
                output.flush();
                logger.debug("Sent TESTAF command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent autofocus test message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Autofocus test command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected autofocus test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for autofocus test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Autofocus test failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse result from SUCCESS response
                    // Format: SUCCESS:plot_path|initial_z:final_z:z_shift
                    Map<String, String> result = new java.util.HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        if (parts.length > 0) {
                            result.put("plot_path", parts[0].trim());
                        }

                        // Parse Z position data if available
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String zData = parts[1].trim();
                            logger.info("Parsing Z position data from response: {}", zData);

                            String[] zValues = zData.split(":");
                            if (zValues.length == 3) {
                                result.put("initial_z", zValues[0].trim());
                                result.put("final_z", zValues[1].trim());
                                result.put("z_shift", zValues[2].trim());

                                logger.info("  Initial Z: {} um", zValues[0]);
                                logger.info("  Final Z: {} um", zValues[1]);
                                logger.info("  Z shift: {} um", zValues[2]);
                            }
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return result;
                    }
                } else {
                    throw new IOException("No final response received from autofocus test");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new java.util.HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Autofocus test error", e));
                throw new IOException("Autofocus test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Test adaptive autofocus at current microscope position with diagnostic output.
     *
     * This command performs ADAPTIVE autofocus which:
     * - Starts at current Z position
     * - Searches bidirectionally (above and below)
     * - Adapts step size based on results
     * - Stops when focus is "good enough" or max steps reached
     * - Minimizes number of acquisitions needed
     *
     * This is the autofocus algorithm used during actual acquisitions.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for diagnostic data
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Map with keys: "message", "initial_z", "final_z", "z_shift"
     * @throws IOException if communication fails or autofocus test fails
     */
    public Map<String, String> testAdaptiveAutofocus(String yamlPath, String outputPath, String objective)
            throws IOException {

        // Build TESTADAF-specific command message
        String message = String.format("--yaml %s --output %s --objective %s %s",
                yamlPath, outputPath, objective, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending adaptive autofocus test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for adaptive autofocus test
            // Adaptive can take varying time depending on how quickly it converges
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(120000); // 2 minutes for adaptive autofocus test
                    logger.debug("Increased socket timeout to 120s for adaptive autofocus test");
                }

                // Send TESTADAF command (8 bytes)
                output.write(Command.TESTADAF.getValue());
                output.flush();
                logger.debug("Sent TESTADAF command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent adaptive autofocus test message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Adaptive autofocus test command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected adaptive autofocus test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for adaptive autofocus test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Adaptive autofocus test failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse result from SUCCESS response
                    // Format: SUCCESS:message|initial_z:final_z:z_shift
                    Map<String, String> result = new java.util.HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        if (parts.length > 0) {
                            result.put("message", parts[0].trim());
                        }

                        // Parse Z position data if available
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String zData = parts[1].trim();
                            logger.info("Parsing Z position data from response: {}", zData);

                            String[] zValues = zData.split(":");
                            if (zValues.length == 3) {
                                result.put("initial_z", zValues[0].trim());
                                result.put("final_z", zValues[1].trim());
                                result.put("z_shift", zValues[2].trim());

                                logger.info("  Initial Z: {} um", zValues[0]);
                                logger.info("  Final Z: {} um", zValues[1]);
                                logger.info("  Z shift: {} um", zValues[2]);
                            }
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return result;
                    }
                } else {
                    throw new IOException("No final response received from adaptive autofocus test");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new java.util.HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Adaptive autofocus test error", e));
                throw new IOException("Adaptive autofocus test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Runs PPM rotation sensitivity test on the server.
     * This method systematically tests PPM rotation sensitivity by acquiring images
     * at precise angles and analyzing the impact of angular deviations.
     *
     * @param params Parameters for the sensitivity test
     * @return Path to the output directory containing test results
     * @throws IOException if communication fails
     */
    public String runPPMSensitivityTest(PPMSensitivityParams params) throws IOException {
        // Build PPMSENS-specific command message
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("--yaml ").append(params.configYaml());
        messageBuilder.append(" --output ").append(params.outputDir());
        messageBuilder.append(" --test-type ").append(params.testType());
        messageBuilder.append(" --base-angle ").append(params.baseAngle());
        messageBuilder.append(" --repeats ").append(params.nRepeats());
        messageBuilder.append(" ").append(END_MARKER);

        String message = messageBuilder.toString();
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending PPM sensitivity test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for sensitivity test (can take 10-30 minutes depending on test type)
                socket.setSoTimeout(1800000); // 30 minutes
                logger.debug("Increased socket timeout to 30 minutes for PPM sensitivity test");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command (8 bytes: "ppmsens_")
                output.write("ppmsens_".getBytes(StandardCharsets.UTF_8));
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected PPM sensitivity test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for PPM sensitivity test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("PPM sensitivity test failed: " + finalResponse.substring(7));
                    } else if (finalResponse.startsWith("SUCCESS:")) {
                        // Extract output directory path from SUCCESS response
                        String outputDir = finalResponse.substring(8).trim();
                        logger.info("PPM sensitivity test successful. Output: {}", outputDir);
                        return outputDir;
                    } else {
                        throw new IOException("Unexpected final response: " + finalResponse);
                    }
                }

                throw new IOException("No response received from server");

            } catch (IOException e) {
                logger.error("Error during PPM sensitivity test", e);
                handleIOException(new IOException("PPM sensitivity test error", e));
                throw new IOException("PPM sensitivity test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Parameters for PPM rotation sensitivity test.
     *
     * @param configYaml Path to microscope configuration YAML file
     * @param outputDir Output directory for test results (required)
     * @param testType Type of test: comprehensive, standard, deviation, repeatability, calibration
     * @param baseAngle Base angle for deviation/repeatability tests (degrees)
     * @param nRepeats Number of repetitions for repeatability test
     */
    public record PPMSensitivityParams(
            String configYaml,
            String outputDir,
            String testType,
            double baseAngle,
            int nRepeats
    ) {}

    /**
     * Run autofocus parameter benchmark to find optimal settings.
     *
     * This command performs systematic testing of autofocus parameters to determine
     * the best configuration for speed and accuracy. The benchmark tests:
     * - Multiple n_steps values (number of Z positions sampled)
     * - Multiple search ranges (how far from current Z to search)
     * - Different interpolation methods (linear, quadratic, cubic)
     * - Different focus score metrics (laplacian, sobel, brenner)
     * - Both standard and adaptive autofocus algorithms
     *
     * Results are saved as CSV and JSON files for analysis.
     *
     * @param referenceZ Known good focus Z position (must be manually verified in focus)
     * @param outputPath Output directory for benchmark results
     * @param testDistances List of distances (um) from focus to test, or null for defaults
     * @param quickMode If true, run reduced parameter space for faster results
     * @param objective Objective identifier for safety limits (e.g., "20X")
     * @return Map with summary results including success_rate, timing_stats, and fastest configs
     * @throws IOException if communication fails or benchmark encounters errors
     */
    public Map<String, Object> runAutofocusBenchmark(
            double referenceZ,
            String outputPath,
            List<Double> testDistances,
            boolean quickMode,
            String objective) throws IOException {

        // Build AFBENCH command message
        StringBuilder message = new StringBuilder();
        message.append("--reference_z ").append(referenceZ);
        message.append(" --output ").append(outputPath);

        if (testDistances != null && !testDistances.isEmpty()) {
            message.append(" --distances ");
            for (int i = 0; i < testDistances.size(); i++) {
                if (i > 0) message.append(",");
                message.append(testDistances.get(i));
            }
        }

        if (quickMode) {
            message.append(" --quick true");
        }

        if (objective != null && !objective.isEmpty()) {
            message.append(" --objective ").append(objective);
        }

        message.append(" ").append(END_MARKER);

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        logger.info("Sending autofocus benchmark command:");
        logger.info("  Reference Z: {} um", referenceZ);
        logger.info("  Output path: {}", outputPath);
        logger.info("  Test distances: {}", testDistances);
        logger.info("  Quick mode: {}", quickMode);
        logger.info("  Objective: {}", objective);

        synchronized (socketLock) {
            ensureConnected();

            // Benchmark can take a long time - set generous timeout
            // With full parameter grid, could take 30-60 minutes
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(3600000); // 60 minutes for full benchmark
                    logger.debug("Increased socket timeout to 60 minutes for benchmark");
                }

                // Send AFBENCH command (8 bytes)
                output.write(Command.AFBENCH.getValue());
                output.flush();
                logger.debug("Sent AFBENCH command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent benchmark message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Autofocus benchmark command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected autofocus benchmark: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for autofocus benchmark to complete...");
                logger.info("This may take 10-60 minutes depending on parameter space...");

                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    // Check for safety violation
                    if (finalResponse.startsWith("FAILED:SAFETY:")) {
                        String safetyMsg = finalResponse.substring("FAILED:SAFETY:".length());
                        throw new IOException("SAFETY VIOLATION: " + safetyMsg);
                    } else if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Benchmark failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse JSON results from SUCCESS response
                    // Format: SUCCESS:{"success_rate": 0.95, "total_trials": 100, ...}
                    Map<String, Object> results = new HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String jsonData = finalResponse.substring(8).trim();
                        logger.info("Parsing benchmark results JSON");

                        try {
                            // Simple JSON parsing for common types
                            // Note: This is a basic parser - production code should use a JSON library
                            results = parseSimpleJson(jsonData);
                            logger.info("Parsed {} result keys", results.size());
                        } catch (Exception e) {
                            logger.warn("Could not parse JSON results: {}", e.getMessage());
                            results.put("raw_response", jsonData);
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return results;
                    }
                } else {
                    throw new IOException("No final response received from benchmark");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Autofocus benchmark error", e));
                throw new IOException("Autofocus benchmark error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Simple JSON parser for benchmark results.
     * Handles basic types: strings, numbers, booleans, and nested objects.
     *
     * Note: This is a minimal parser for the specific benchmark response format.
     * For general JSON parsing, use a proper JSON library.
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();

        // Remove outer braces
        String content = json.trim();
        if (content.startsWith("{")) content = content.substring(1);
        if (content.endsWith("}")) content = content.substring(0, content.length() - 1);

        // Split by commas (simple approach - doesn't handle nested objects properly)
        // For benchmark results, we just need top-level keys
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim();

                // Parse value type
                if (value.equals("true") || value.equals("false")) {
                    result.put(key, Boolean.parseBoolean(value));
                } else if (value.equals("null")) {
                    result.put(key, null);
                } else if (value.startsWith("\"") && value.endsWith("\"")) {
                    result.put(key, value.substring(1, value.length() - 1));
                } else {
                    try {
                        if (value.contains(".")) {
                            result.put(key, Double.parseDouble(value));
                        } else {
                            result.put(key, Integer.parseInt(value));
                        }
                    } catch (NumberFormatException e) {
                        result.put(key, value); // Store as string if parsing fails
                    }
                }
            }
        }

        return result;
    }

    /**
     * Starts a polarizer calibration workflow on the server for PPM rotation stage.
     * This method uses the POLCAL command with parameters for angle sweep configuration.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for calibration report
     * @param startAngle Starting angle for sweep (degrees)
     * @param endAngle Ending angle for sweep (degrees)
     * @param stepSize Step size for angle sweep (degrees)
     * @param exposureMs Exposure time for images (milliseconds)
     * @return Path to the generated calibration report file
     * @throws IOException if communication fails
     */
    public String startPolarizerCalibration(String yamlPath, String outputPath,
                                           double startAngle, double endAngle,
                                           double stepSize, double exposureMs) throws IOException {

        // Build POLCAL-specific command message
        String message = String.format("--yaml %s --output %s --start %.1f --end %.1f --step %.1f --exposure %.1f %s",
                yamlPath, outputPath, startAngle, endAngle, stepSize, exposureMs, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending polarizer calibration command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for calibration (can take several minutes)
                // With stability check (3 runs), each run takes ~2-3 minutes, so allow 15 minutes total
                socket.setSoTimeout(900000); // 15 minutes
                logger.debug("Increased socket timeout to 15 minutes for calibration");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command
                output.write("polcal__".getBytes(StandardCharsets.UTF_8));
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected polarizer calibration: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for polarizer calibration to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Polarizer calibration failed: " + finalResponse.substring(7));
                    } else if (finalResponse.startsWith("SUCCESS:")) {
                        // Extract report path from SUCCESS response
                        String reportPath = finalResponse.substring(8).trim();
                        logger.info("Polarizer calibration successful. Report: {}", reportPath);
                        return reportPath;
                    } else {
                        throw new IOException("Unexpected final response: " + finalResponse);
                    }
                }

                throw new IOException("No response received from server");

            } catch (IOException e) {
                logger.error("Error during polarizer calibration", e);
                handleIOException(new IOException("Polarizer calibration error", e));
                throw new IOException("Polarizer calibration error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Runs PPM birefringence optimization test to find optimal polarizer angle.
     * This method uses the PPMBIREF command to systematically test angles and
     * identify the optimal angle for maximum birefringence signal contrast.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for test results and plots
     * @param minAngle Minimum angle to test (degrees)
     * @param maxAngle Maximum angle to test (degrees)
     * @param angleStep Step size for angle sweep (degrees)
     * @param exposureMode Exposure mode: "interpolate", "calibrate", or "fixed"
     * @param fixedExposureMs Fixed exposure in ms (required if mode="fixed", null otherwise)
     * @param targetIntensity Target intensity for calibrate mode (0-255)
     * @return Path to the results directory
     * @throws IOException if communication fails
     */
    public String runBirefringenceOptimization(String yamlPath, String outputPath,
                                               double minAngle, double maxAngle, double angleStep,
                                               String exposureMode, Double fixedExposureMs,
                                               int targetIntensity) throws IOException {

        // Build PPMBIREF-specific command message
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("--yaml ").append(yamlPath)
                     .append(" --output ").append(outputPath)
                     .append(" --mode ").append(exposureMode)
                     .append(" --min-angle ").append(minAngle)
                     .append(" --max-angle ").append(maxAngle)
                     .append(" --step ").append(angleStep);

        if ("fixed".equals(exposureMode) && fixedExposureMs != null) {
            messageBuilder.append(" --exposure ").append(fixedExposureMs);
        }

        messageBuilder.append(" --target-intensity ").append(targetIntensity)
                     .append(" ").append(END_MARKER);

        String message = messageBuilder.toString();
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending PPM birefringence optimization command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for birefringence test (can take many minutes)
                // With 201 angle pairs (-10 to +10, step 0.1), ~400 images total
                // At ~1-2s per image = 400-800s = 6-13 minutes. Allow 30 minutes to be safe.
                socket.setSoTimeout(1800000); // 30 minutes
                logger.debug("Increased socket timeout to 30 minutes for birefringence optimization");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command
                output.write(Command.PPMBIREF.getValue());
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected birefringence optimization: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for birefringence optimization to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Birefringence optimization failed: " + finalResponse.substring(7));
                    } else if (finalResponse.startsWith("SUCCESS:")) {
                        // Extract results directory path from SUCCESS response
                        String resultPath = finalResponse.substring(8).trim();
                        logger.info("Birefringence optimization successful. Results: {}", resultPath);
                        return resultPath;
                    } else {
                        throw new IOException("Unexpected final response: " + finalResponse);
                    }
                }

                throw new IOException("No response received from server");

            } catch (IOException e) {
                logger.error("Error during birefringence optimization", e);
                handleIOException(new IOException("Birefringence optimization error", e));
                throw new IOException("Birefringence optimization error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Executes a command and optionally waits for response.
     *
     * @param command Command to execute
     * @param data Optional data to send with command
     * @param expectedResponseBytes Number of bytes to read in response (0 for no response)
     * @return Response bytes or empty array if no response expected
     * @throws IOException if communication fails
     */
    private byte[] executeCommand(Command command, byte[] data, int expectedResponseBytes) throws IOException {
        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command
                output.write(command.getValue());

                // Send data if provided
                if (data != null && data.length > 0) {
                    output.write(data);
                }

                output.flush();
                lastActivityTime.set(System.currentTimeMillis());

                // Read response if expected
                if (expectedResponseBytes > 0) {
                    byte[] response = new byte[expectedResponseBytes];
                    input.readFully(response);
                    lastActivityTime.set(System.currentTimeMillis());
                    return response;
                }

                return new byte[0];

            } catch (IOException e) {
                handleIOException(e);
                throw e;
            }
        }
    }

    /**
     * Sends a command without expecting response.
     *
     * @param command Command to send
     * @throws IOException if communication fails
     */
    private void sendCommand(Command command) throws IOException {
        executeCommand(command, null, 0);
    }

    /**
     * Ensures the client is connected, attempting reconnection if necessary.
     *
     * @throws IOException if connection cannot be established
     */
    private void ensureConnected() throws IOException {
        if (!connected.get()) {
            connect();
        }
    }

    /**
     * Handles IO exceptions by triggering reconnection if appropriate.
     *
     * @param e The exception to handle
     */
    private void handleIOException(IOException e) {
        logger.error("Communication error with microscope server", e);

        // Mark as disconnected
        connected.set(false);

        // Schedule reconnection attempt
        if (!shuttingDown.get()) {
            scheduleReconnection();
        }
    }

    /**
     * Schedules automatic reconnection attempts.
     */
    private void scheduleReconnection() {
        reconnectExecutor.submit(() -> {
            int attempts = 0;

            while (attempts < maxReconnectAttempts && !connected.get() && !shuttingDown.get()) {
                attempts++;
                logger.info("Reconnection attempt {} of {}", attempts, maxReconnectAttempts);

                try {
                    Thread.sleep(reconnectDelayMs);
                    connect();
                    logger.info("Successfully reconnected to microscope server");
                    break;
                } catch (Exception e) {
                    logger.warn("Reconnection attempt {} failed: {}", attempts, e.getMessage());
                }
            }

            if (!connected.get() && !shuttingDown.get()) {
                logger.error("Failed to reconnect after {} attempts", maxReconnectAttempts);
            }
        });
    }

    /**
     * Starts health monitoring thread.
     */
    private void startHealthMonitoring() {
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (connected.get() && !shuttingDown.get()) {
                long idleTime = System.currentTimeMillis() - lastActivityTime.get();

                // Perform health check if idle for too long
                if (idleTime > healthCheckIntervalMs) {
                    try {
                        // Simple health check - get stage position
                        getStageXY();
                        logger.debug("Health check passed");
                    } catch (Exception e) {
                        logger.warn("Health check failed: {}", e.getMessage());
                        connected.set(false);
                        scheduleReconnection();
                    }
                }
            }
        }, healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cleans up socket resources.
     */
    private void cleanup() {
        try {
            if (input != null) {
                input.close();
                input = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing input stream", e);
        }

        try {
            if (output != null) {
                output.close();
                output = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing output stream", e);
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing socket", e);
        }

        connected.set(false);
    }

    /**
     * Checks if the client is currently connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Shuts down the microscope server.
     * This will terminate the server process.
     *
     * @throws IOException if communication fails
     */
    public void shutdownServer() throws IOException {
        logger.warn("Sending shutdown command to microscope server");
        sendCommand(Command.SHUTDOWN);
        disconnect();
    }

    /**
     * Closes the client and releases all resources.
     */
    @Override
    public void close() {
        shuttingDown.set(true);

        // Stop executors
        reconnectExecutor.shutdown();
        healthCheckExecutor.shutdown();

        try {
            reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS);
            healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while shutting down executors", e);
        }

        // Disconnect
        disconnect();

        logger.info("Microscope socket client closed");
    }
    /**
     * Gets the current acquisition status.
     *
     * @return Current acquisition state
     * @throws IOException if communication fails
     */
    public AcquisitionState getAcquisitionStatus() throws IOException {
        // First, read initial response to check state
        byte[] initialResponse = executeCommand(Command.STATUS, null, 16);
        String stateStr = new String(initialResponse, StandardCharsets.UTF_8);

        // Check if this is a FAILED message with additional details
        if (stateStr.startsWith("FAILED:")) {
            // Read additional bytes for the full error message (up to 512 bytes total)
            synchronized (socketLock) {
                try {
                    byte[] additionalBytes = new byte[496]; // 512 - 16 already read
                    int bytesRead = input.read(additionalBytes);

                    if (bytesRead > 0) {
                        // Combine initial response with additional bytes
                        String additionalStr = new String(additionalBytes, 0, bytesRead, StandardCharsets.UTF_8);
                        stateStr = stateStr + additionalStr;
                    }
                } catch (IOException e) {
                    logger.warn("Could not read additional failure message bytes", e);
                }
            }

            String failureDetails = stateStr.substring("FAILED:".length()).trim();
            lastFailureMessage = failureDetails.isEmpty() ? "Unknown server error" : failureDetails;
            logger.error("Received FAILED message during status check: {} - Details: {}",
                    stateStr.trim(), lastFailureMessage);
            return AcquisitionState.FAILED;
        } else if (stateStr.startsWith("SUCCESS:")) {
            logger.info("Received SUCCESS message during status check: {}", stateStr.trim());
            lastFailureMessage = null; // Clear any previous failure message
            return AcquisitionState.COMPLETED;
        } else if (stateStr.startsWith("COMPLETED")) {
            // Check for extended format with final_z: "COMPLETED|final_z:1234.56"
            // Read additional bytes if needed
            synchronized (socketLock) {
                try {
                    byte[] additionalBytes = new byte[64]; // Enough for "COMPLETED|final_z:1234.56"
                    int bytesRead = input.read(additionalBytes);

                    if (bytesRead > 0) {
                        String additionalStr = new String(additionalBytes, 0, bytesRead, StandardCharsets.UTF_8);
                        stateStr = stateStr + additionalStr;
                    }
                } catch (IOException e) {
                    // May timeout if no additional data - that's fine
                    logger.debug("No additional bytes for COMPLETED status");
                }
            }

            // Parse final_z if present: "COMPLETED|final_z:1234.56"
            lastFailureMessage = null;
            lastAcquisitionFinalZ = null;

            if (stateStr.contains("|final_z:")) {
                try {
                    int startIdx = stateStr.indexOf("|final_z:") + "|final_z:".length();
                    String zStr = stateStr.substring(startIdx).trim();
                    // Handle potential trailing characters
                    int endIdx = 0;
                    while (endIdx < zStr.length() &&
                           (Character.isDigit(zStr.charAt(endIdx)) || zStr.charAt(endIdx) == '.' || zStr.charAt(endIdx) == '-')) {
                        endIdx++;
                    }
                    if (endIdx > 0) {
                        lastAcquisitionFinalZ = Double.parseDouble(zStr.substring(0, endIdx));
                        logger.info("Parsed final Z from COMPLETED status: {} um", lastAcquisitionFinalZ);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Could not parse final_z from COMPLETED response: {}", stateStr);
                }
            }

            logger.info("Received COMPLETED status{}",
                lastAcquisitionFinalZ != null ? " with final_z: " + lastAcquisitionFinalZ + " um" : "");
            return AcquisitionState.COMPLETED;
        }

        AcquisitionState state = AcquisitionState.fromString(stateStr);
        logger.debug("Acquisition status: {}", state);
        return state;
    }

    /**
     * Gets the last failure message received from the server.
     * This provides detailed information about why an acquisition failed.
     * 
     * @return The last failure message, or null if no failure occurred or message is unavailable
     */
    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    /**
     * Gets the final Z-focus position from the last completed acquisition.
     *
     * <p>This value is used by the tilt correction model to build a plane
     * that predicts optimal Z positions across the slide.</p>
     *
     * @return The final Z position in micrometers, or null if not available
     */
    public Double getLastAcquisitionFinalZ() {
        return lastAcquisitionFinalZ;
    }

    /**
     * Clears the stored final Z value.
     * Should be called before starting a new acquisition.
     */
    public void clearLastAcquisitionFinalZ() {
        lastAcquisitionFinalZ = null;
    }

    /**
     * Gets the current acquisition progress.
     *
     * @return Acquisition progress (current/total images)
     * @throws IOException if communication fails
     */
    public AcquisitionProgress getAcquisitionProgress() throws IOException {
        byte[] response = executeCommand(Command.PROGRESS, null, 8);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int current = buffer.getInt();
        int total = buffer.getInt();

        AcquisitionProgress progress = new AcquisitionProgress(current, total);
        logger.debug("Acquisition progress: {}", progress);
        return progress;
    }

    /**
     * Cancels the currently running acquisition.
     *
     * @return true if cancellation was acknowledged
     * @throws IOException if communication fails
     */
    public boolean cancelAcquisition() throws IOException {
        byte[] response = executeCommand(Command.CANCEL, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8);
        boolean cancelled = "ACK".equals(ack);
        logger.info("Acquisition cancellation {}", cancelled ? "acknowledged" : "failed");
        return cancelled;
    }

    /**
     * Checks if manual focus is requested by the server and returns retry count.
     * This should be called periodically during acquisition to detect autofocus failures.
     *
     * @return number of retries remaining (0+) if manual focus is requested, or -1 if not needed
     * @throws IOException if communication fails
     */
    public int isManualFocusRequested() throws IOException {
        byte[] response = executeCommand(Command.REQMANF, null, 8);
        String status = new String(response, StandardCharsets.UTF_8).trim();

        if (status.equals("IDLE____")) {
            return -1;  // No manual focus needed
        } else if (status.startsWith("NEEDED")) {
            // Format: "NEEDEDnn" where nn is 00-99
            try {
                String retriesStr = status.substring(6);  // Get last 2 characters
                return Integer.parseInt(retriesStr);
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                logger.warn("Failed to parse retries from manual focus response: {}", status);
                return 0;  // Default to 0 retries if parsing fails
            }
        } else {
            logger.warn("Unknown manual focus status: {}", status);
            return -1;
        }
    }

    /**
     * Acknowledges manual focus completion - retry autofocus.
     * Call this after the user has manually focused and wants to retry autofocus.
     *
     * @return true if acknowledgment was successful
     * @throws IOException if communication fails
     */
    public boolean acknowledgeManualFocus() throws IOException {
        byte[] response = executeCommand(Command.ACKMF, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8).trim();
        boolean acknowledged = "ACK".equals(ack);
        logger.info("Manual focus retry autofocus {}", acknowledged ? "acknowledged" : "failed");
        // Reset progress timeout since user has responded and acquisition will resume
        if (acknowledged) {
            resetProgressTimeout();
        }
        return acknowledged;
    }

    /**
     * Skip autofocus retry - use current focus position.
     * Call this when user has manually focused and wants to use current position.
     *
     * @return true if acknowledgment was successful
     * @throws IOException if communication fails
     */
    public boolean skipAutofocusRetry() throws IOException {
        byte[] response = executeCommand(Command.SKIPAF, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8).trim();
        boolean acknowledged = "ACK".equals(ack);
        logger.info("Manual focus skip retry {}", acknowledged ? "acknowledged" : "failed");
        // Reset progress timeout since user has responded and acquisition will resume
        if (acknowledged) {
            resetProgressTimeout();
        }
        return acknowledged;
    }

    /**
     * Resets the progress timeout timer.
     * Call this when a significant event occurs that should reset the timeout
     * (e.g., manual focus acknowledgment, user interaction).
     */
    public void resetProgressTimeout() {
        lastProgressUpdateTime.set(System.currentTimeMillis());
        logger.debug("Progress timeout reset");
    }

    /**
     * Monitors acquisition progress until completion or timeout.
     * Calls the progress callback periodically.
     *
     * @param progressCallback Callback for progress updates (can be null)
     * @param pollIntervalMs Interval between progress checks in milliseconds
     * @param timeoutMs Maximum time to wait in milliseconds (0 for no timeout)
     * @return Final acquisition state
     * @throws IOException if communication fails
     * @throws InterruptedException if thread is interrupted
     */
    public AcquisitionState monitorAcquisition(
            Consumer<AcquisitionProgress> progressCallback,
            long pollIntervalMs,
            long timeoutMs) throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();
        // Use instance field instead of local variable so it can be reset externally
        lastProgressUpdateTime.set(startTime);
        int lastProgressCount = -1;  // Initialize to -1 to detect first progress
        AcquisitionState lastState = AcquisitionState.IDLE;
        int retryCount = 0;
        final int maxInitialRetries = 3;

        while (true) {
            try {
                // Check status
                AcquisitionState currentState = getAcquisitionStatus();

                // Reset retry count on successful read
                retryCount = 0;

                // Check if terminal state reached
                if (currentState == AcquisitionState.COMPLETED ||
                        currentState == AcquisitionState.FAILED ||
                        currentState == AcquisitionState.CANCELLED) {
                    logger.info("Acquisition reached terminal state: {}", currentState);
                    return currentState;
                }

                // Check for manual focus request - if active, reset timeout
                // This prevents timeout while waiting for user to respond to manual focus dialog
                try {
                    int manualFocusRetries = isManualFocusRequested();
                    if (manualFocusRetries >= 0) {
                        // Manual focus is requested - reset timeout since we're waiting for user input
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                        logger.debug("Manual focus requested (retries: {}) - resetting progress timeout", manualFocusRetries);
                    }
                } catch (IOException e) {
                    logger.debug("Failed to check manual focus status: {}", e.getMessage());
                    // Not critical - just continue monitoring
                }

                // Get progress if running
                if (currentState == AcquisitionState.RUNNING && progressCallback != null) {
                    try {
                        AcquisitionProgress progress = getAcquisitionProgress();

                        // For background acquisition, progress might start at -1/-1, which is normal
                        // Only report valid progress values
                        if (progress != null && progress.current >= 0 && progress.total >= 0) {
                            progressCallback.accept(progress);

                            // Check if progress was actually made
                            if (progress.current > lastProgressCount) {
                                lastProgressUpdateTime.set(System.currentTimeMillis());
                                lastProgressCount = progress.current;
                                logger.debug("Progress updated: {}/{} files, resetting timeout", progress.current, progress.total);
                            }
                        } else {
                            // Invalid progress (-1/-1), but still reset timeout if we got a response
                            lastProgressUpdateTime.set(System.currentTimeMillis());
                            logger.debug("Received progress response (server still working): {}/{}",
                                    progress != null ? progress.current : "null",
                                    progress != null ? progress.total : "null");
                        }
                    } catch (IOException e) {
                        logger.debug("Failed to get progress (expected during background acquisition): {}", e.getMessage());
                        // For background acquisition, progress queries might fail, so don't treat as error
                        // Just reset timeout to show server is still responsive
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                    }
                }

                // Check timeout based on last progress, not total time
                if (timeoutMs > 0) {
                    long timeSinceProgress = System.currentTimeMillis() - lastProgressUpdateTime.get();
                    if (timeSinceProgress > timeoutMs) {
                        logger.warn("No progress for {} ms (last progress: {} files), timing out",
                                timeSinceProgress, lastProgressCount);
                        break;
                    }
                }

                // Log state changes
                if (currentState != lastState) {
                    logger.info("Acquisition state changed: {} -> {}", lastState, currentState);
                    lastState = currentState;

                    // Reset progress timer on state change to RUNNING
                    if (currentState == AcquisitionState.RUNNING) {
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                    }
                }

                Thread.sleep(pollIntervalMs);

            } catch (IOException e) {
                // Handle initial connection issues gracefully
                if (retryCount < maxInitialRetries &&
                        System.currentTimeMillis() - startTime < 10000) {
                    retryCount++;
                    logger.debug("Initial status check failed (attempt {}/{}), retrying: {}",
                            retryCount, maxInitialRetries, e.getMessage());
                    Thread.sleep(1000); // Wait a bit longer before retry
                    continue;
                }
                throw e;
            }
        }

        return lastState;
    }

}
