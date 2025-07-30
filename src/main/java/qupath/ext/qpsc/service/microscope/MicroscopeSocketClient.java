package qupath.ext.qpsc.service.microscope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
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
        ACQUIRE("acquire_"),
        /** Get acquisition status */
        STATUS("status__"),
        /** Get acquisition progress */
        PROGRESS("progress"),
        /** Cancel acquisition */
        CANCEL("cancel__");

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
     * Starts an acquisition workflow on the server using a command builder.
     * This single method handles all acquisition types with their specific parameters.
     *
     * @param builder Pre-configured acquisition command builder
     * @throws IOException if communication fails
     */
    public void startAcquisition(AcquisitionCommandBuilder builder) throws IOException {
        String message = builder.buildSocketMessage() + " END_MARKER";
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

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Acquisition command sent successfully");

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Failed to send acquisition command", e));
                throw new IOException("Failed to send acquisition command", e);
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
        byte[] response = executeCommand(Command.STATUS, null, 16);
        String stateStr = new String(response, StandardCharsets.UTF_8);
        AcquisitionState state = AcquisitionState.fromString(stateStr);
        logger.debug("Acquisition status: {}", state);
        return state;
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

                // Get progress if running
                if (currentState == AcquisitionState.RUNNING && progressCallback != null) {
                    try {
                        AcquisitionProgress progress = getAcquisitionProgress();
                        progressCallback.accept(progress);
                    } catch (IOException e) {
                        logger.debug("Failed to get progress: {}", e.getMessage());
                    }
                }

                // Check timeout
                if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                    logger.warn("Acquisition monitoring timed out after {} ms", timeoutMs);
                    break;
                }

                // Log state changes
                if (currentState != lastState) {
                    logger.info("Acquisition state changed: {} -> {}", lastState, currentState);
                    lastState = currentState;
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
