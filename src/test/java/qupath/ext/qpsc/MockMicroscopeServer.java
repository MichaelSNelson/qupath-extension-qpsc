package qupath.ext.qpsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock microscope server for testing the socket client.
 * This server simulates the Python microscope control server's protocol
 * and behavior for unit and integration testing.
 *
 * <p>The mock server maintains simulated stage positions and responds to
 * commands according to the same binary protocol as the real server.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Simulates all microscope commands (move, get position, etc.)</li>
 *   <li>Maintains internal state for stage positions</li>
 *   <li>Configurable delays to simulate real hardware</li>
 *   <li>Error injection for testing error handling</li>
 *   <li>Thread-safe for concurrent client connections</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class MockMicroscopeServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MockMicroscopeServer.class);

    // Server configuration
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Simulated stage state (thread-safe)
    private final AtomicReference<StagePosition> currentPosition = new AtomicReference<>(
            new StagePosition(0.0, 0.0, 0.0, 0.0)
    );

    // Stage limits for validation
    private final double xMin = -21000;
    private final double xMax = 33000;
    private final double yMin = -9000;
    private final double yMax = 11000;
    private final double zMin = -1000;
    private final double zMax = 1000;

    // Configuration
    private volatile long moveDelayMs = 100; // Simulate movement time
    private volatile boolean injectErrors = false;
    private volatile double errorProbability = 0.1;

    // Client tracking
    private final ConcurrentHashMap<String, ClientHandler> activeClients = new ConcurrentHashMap<>();

    /**
     * Stage position record.
     */
    private record StagePosition(double x, double y, double z, double r) {}

    /**
     * Creates a mock server on the specified port.
     *
     * @param port Port to listen on
     */
    public MockMicroscopeServer(int port) {
        this.port = port;
    }

    /**
     * Creates a mock server on a random available port.
     *
     * @return A new mock server instance
     * @throws IOException if no port is available
     */
    public static MockMicroscopeServer createOnRandomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            return new MockMicroscopeServer(port);
        }
    }

    /**
     * Starts the mock server.
     *
     * @throws IOException if server cannot start
     */
    public void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Server already running");
        }

        serverSocket = new ServerSocket(port);
        running.set(true);

        // Start accept thread
        executor.submit(this::acceptLoop);

        logger.info("Mock microscope server started on port {}", getPort());
    }

    /**
     * Main accept loop for incoming connections.
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientId = clientSocket.getRemoteSocketAddress().toString();

                logger.info("Client connected: {}", clientId);

                ClientHandler handler = new ClientHandler(clientSocket, clientId);
                activeClients.put(clientId, handler);
                executor.submit(handler);

            } catch (IOException e) {
                if (running.get()) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
    }

    /**
     * Handles a single client connection.
     */
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientId;
        private DataInputStream input;
        private DataOutputStream output;

        ClientHandler(Socket socket, String clientId) {
            this.socket = socket;
            this.clientId = clientId;
        }

        @Override
        public void run() {
            try {
                input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                while (!socket.isClosed() && running.get()) {
                    // Read command (8 bytes)
                    byte[] commandBytes = new byte[8];
                    input.readFully(commandBytes);
                    String command = new String(commandBytes).trim();

                    logger.debug("Received command '{}' from {}", command, clientId);

                    // Handle command
                    switch (command) {
                        case "getxy" -> handleGetXY();
                        case "getz" -> handleGetZ();
                        case "getr" -> handleGetR();
                        case "move" -> handleMoveXY();
                        case "movez" -> handleMoveZ();
                        case "mover" -> handleMoveR();
                        case "quitclnt" -> {
                            logger.info("Client {} disconnecting", clientId);
                            return;
                        }
                        case "shutdown" -> {
                            logger.info("Shutdown requested by {}", clientId);
                            stop();
                            return;
                        }
                        case "acquire" -> handleAcquire();
                        default -> logger.warn("Unknown command '{}' from {}", command, clientId);
                    }
                }

            } catch (EOFException e) {
                logger.info("Client {} disconnected", clientId);
            } catch (IOException e) {
                logger.error("Error handling client {}", clientId, e);
            } finally {
                cleanup();
            }
        }

        private void handleGetXY() throws IOException {
            maybeInjectError("getXY");

            StagePosition pos = currentPosition.get();
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putFloat((float) pos.x);
            buffer.putFloat((float) pos.y);

            output.write(buffer.array());
            output.flush();

            logger.debug("Sent XY position ({}, {}) to {}", pos.x, pos.y, clientId);
        }

        private void handleGetZ() throws IOException {
            maybeInjectError("getZ");

            StagePosition pos = currentPosition.get();
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putFloat((float) pos.z);

            output.write(buffer.array());
            output.flush();

            logger.debug("Sent Z position {} to {}", pos.z, clientId);
        }

        private void handleGetR() throws IOException {
            maybeInjectError("getR");

            StagePosition pos = currentPosition.get();
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putFloat((float) pos.r);

            output.write(buffer.array());
            output.flush();

            logger.debug("Sent R position {} to {}", pos.r, clientId);
        }

        private void handleMoveXY() throws IOException {
            byte[] data = new byte[8];
            input.readFully(data);

            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);
            float x = buffer.getFloat();
            float y = buffer.getFloat();

            maybeInjectError("moveXY");

            // Validate bounds
            if (x < xMin || x > xMax || y < yMin || y > yMax) {
                logger.warn("Move XY out of bounds: ({}, {})", x, y);
                // In real server, might send error response
            }

            // Simulate movement delay
            simulateMovement();

            // Update position
            currentPosition.updateAndGet(pos ->
                    new StagePosition(x, y, pos.z, pos.r)
            );

            logger.info("Moved to XY ({}, {}) for client {}", x, y, clientId);
        }

        private void handleMoveZ() throws IOException {
            byte[] data = new byte[4];
            input.readFully(data);

            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);
            float z = buffer.getFloat();

            maybeInjectError("moveZ");

            // Validate bounds
            if (z < zMin || z > zMax) {
                logger.warn("Move Z out of bounds: {}", z);
            }

            // Simulate movement delay
            simulateMovement();

            // Update position
            currentPosition.updateAndGet(pos ->
                    new StagePosition(pos.x, pos.y, z, pos.r)
            );

            logger.info("Moved to Z {} for client {}", z, clientId);
        }

        private void handleMoveR() throws IOException {
            byte[] data = new byte[4];
            input.readFully(data);

            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);
            float r = buffer.getFloat();

            maybeInjectError("moveR");

            // Simulate movement delay
            simulateMovement();

            // Update position
            currentPosition.updateAndGet(pos ->
                    new StagePosition(pos.x, pos.y, pos.z, r)
            );

            logger.info("Rotated to tick {} for client {}", r, clientId);
        }

        private void handleAcquire() throws IOException {
            // Read the acquisition message until END_MARKER
            StringBuilder message = new StringBuilder();
            byte[] buffer = new byte[1024];

            while (true) {
                int bytesRead = input.read(buffer);
                if (bytesRead == -1) break;

                String chunk = new String(buffer, 0, bytesRead);
                message.append(chunk);

                if (message.toString().contains("END_MARKER")) {
                    break;
                }
            }

            String acquisitionParams = message.toString().replace("END_MARKER", "");
            logger.info("Acquisition requested: {}", acquisitionParams);

            // Simulate acquisition (would normally trigger hardware)
            try {
                Thread.sleep(1000); // Simulate acquisition time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void simulateMovement() {
            if (moveDelayMs > 0) {
                try {
                    Thread.sleep(moveDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void maybeInjectError(String operation) throws IOException {
            if (injectErrors && Math.random() < errorProbability) {
                logger.warn("Injecting error for operation: {}", operation);
                throw new IOException("Simulated error in " + operation);
            }
        }

        private void cleanup() {
            activeClients.remove(clientId);

            try {
                if (input != null) input.close();
            } catch (IOException ignored) {}

            try {
                if (output != null) output.close();
            } catch (IOException ignored) {}

            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Stops the mock server.
     */
    public void stop() {
        if (!running.get()) {
            return;
        }

        running.set(false);

        // Close all client connections
        activeClients.values().forEach(handler -> {
            try {
                handler.socket.close();
            } catch (IOException ignored) {}
        });
        activeClients.clear();

        // Close server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket", e);
        }

        // Shutdown executor
        executor.shutdown();

        logger.info("Mock microscope server stopped");
    }

    /**
     * Gets the actual port the server is listening on.
     *
     * @return The port number
     */
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    /**
     * Sets the current stage position (for testing).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param r Rotation angle
     */
    public void setPosition(double x, double y, double z, double r) {
        currentPosition.set(new StagePosition(x, y, z, r));
        logger.debug("Set position to ({}, {}, {}, {})", x, y, z, r);
    }

    /**
     * Gets the current stage position.
     *
     * @return Current position array [x, y, z, r]
     */
    public double[] getPosition() {
        StagePosition pos = currentPosition.get();
        return new double[] { pos.x, pos.y, pos.z, pos.r };
    }

    /**
     * Sets the movement delay to simulate hardware.
     *
     * @param delayMs Delay in milliseconds
     */
    public void setMoveDelay(long delayMs) {
        this.moveDelayMs = delayMs;
    }

    /**
     * Enables or disables error injection.
     *
     * @param inject true to inject errors
     * @param probability Probability of error (0.0 to 1.0)
     */
    public void setErrorInjection(boolean inject, double probability) {
        this.injectErrors = inject;
        this.errorProbability = Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * Gets the number of active client connections.
     *
     * @return Number of connected clients
     */
    public int getActiveClientCount() {
        return activeClients.size();
    }

    @Override
    public void close() {
        stop();
    }

}