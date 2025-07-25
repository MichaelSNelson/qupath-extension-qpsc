package qupath.ext.qpsc.service.microscope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        MOVEZ("movez___"),
        /** Move XY stage */
        MOVE("move____"),
        /** Get rotation angle in ticks */
        GETR("getr____"),
        /** Move rotation stage */
        MOVER("mover___"),
        /** Shutdown server */
        SHUTDOWN("shutdown"),
        /** Disconnect client */
        DISCONNECT("quitclnt"),
        /** Start acquisition */
        ACQUIRE("acquire_");

        private final byte[] value;

        Command(String value) {
            if (value.length() != 8) {
                throw new IllegalArgumentException("Command must be exactly 8 bytes");
            }
            this.value = value.getBytes();
        }

        public byte[] getValue() {
            return value.clone(); // Defensive copy
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
     * Gets the current XY position of the microscope stage.
     *
     * @return Array containing [x, y] coordinates in microns
     * @throws IOException if communication fails
     */
    public double[] getStageXY() throws IOException {
        byte[] response = executeCommand(Command.GETXY, null, 8);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float x = buffer.getFloat();
        float y = buffer.getFloat();

        logger.debug("Stage XY position: ({}, {})", x, y);
        return new double[] { x, y };
    }

    /**
     * Gets the current Z position of the microscope stage.
     *
     * @return Z coordinate in microns
     * @throws IOException if communication fails
     */
    public double getStageZ() throws IOException {
        byte[] response = executeCommand(Command.GETZ, null, 4);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float z = buffer.getFloat();
        logger.debug("Stage Z position: {}", z);
        return z;
    }

    /**
     * Gets the current rotation angle (in ticks) of the stage.
     *
     * @return Rotation angle in ticks (double the angle)
     * @throws IOException if communication fails
     */
    public double getStageR() throws IOException {
        byte[] response = executeCommand(Command.GETR, null, 4);

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
     * Starts an acquisition workflow on the server.
     *
     * @param yamlPath Path to YAML configuration file
     * @param projectsFolder Projects folder path
     * @param sampleLabel Sample label
     * @param scanType Scan type identifier
     * @param regionName Region name
     * @param angles Array of rotation angles
     * @throws IOException if communication fails
     */
    public void startAcquisition(String yamlPath, String projectsFolder, String sampleLabel,
                                 String scanType, String regionName, double[] angles) throws IOException {
        // Format angles as space-separated string in parentheses
        StringBuilder anglesStr = new StringBuilder("(");
        for (int i = 0; i < angles.length; i++) {
            if (i > 0) anglesStr.append(" ");
            anglesStr.append(angles[i]);
        }
        anglesStr.append(")");

        // Build comma-separated message with end marker
        String message = String.join(",",
                yamlPath,
                projectsFolder,
                sampleLabel,
                scanType,
                regionName,
                anglesStr.toString()
        ) + ",END_MARKER";

        byte[] messageBytes = message.getBytes();

        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command
                output.write(Command.ACQUIRE.getValue());
                // Send message
                output.write(messageBytes);
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Started acquisition workflow: {}", scanType);

            } catch (IOException e) {
                handleIOException(e);
                throw e;
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
}