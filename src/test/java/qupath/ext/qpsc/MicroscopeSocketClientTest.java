package qupath.ext.qpsc;

import org.junit.jupiter.api.*;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the MicroscopeSocketClient using a mock server.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Basic connection and disconnection</li>
 *   <li>All stage movement and query commands</li>
 *   <li>Error handling and reconnection</li>
 *   <li>Thread safety and concurrent operations</li>
 *   <li>Timeout behavior</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
class MicroscopeSocketClientTest {

    private MockMicroscopeServer mockServer;
    private MicroscopeSocketClient client;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        // Create and start mock server
        mockServer = MockMicroscopeServer.createOnRandomPort();
        mockServer.start();
        serverPort = mockServer.getPort();

        // Create client (not connected yet)
        client = new MicroscopeSocketClient("localhost", serverPort);
    }

    @AfterEach
    void tearDown() {
        // Clean up
        if (client != null) {
            client.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    @DisplayName("Test basic connection and disconnection")
    void testConnectionLifecycle() throws IOException {
        // Initially not connected
        assertFalse(client.isConnected());

        // Connect
        client.connect();
        assertTrue(client.isConnected());

        // Connect again (should be no-op)
        client.connect();
        assertTrue(client.isConnected());

        // Disconnect
        client.disconnect();
        assertFalse(client.isConnected());

        // Disconnect again (should be no-op)
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("Test stage XY position query")
    void testGetStageXY() throws IOException {
        // Set mock position
        mockServer.setPosition(100.5, 200.7, 50.0, 0.0);

        // Connect and query
        client.connect();
        double[] position = client.getStageXY();

        assertEquals(2, position.length);
        assertEquals(100.5, position[0], 0.01);
        assertEquals(200.7, position[1], 0.01);
    }

    @Test
    @DisplayName("Test stage Z position query")
    void testGetStageZ() throws IOException {
        // Set mock position
        mockServer.setPosition(0.0, 0.0, 123.45, 0.0);

        // Connect and query
        client.connect();
        double z = client.getStageZ();

        assertEquals(123.45, z, 0.01);
    }

    @Test
    @DisplayName("Test stage rotation query")
    void testGetStageR() throws IOException {
        // Set mock position
        mockServer.setPosition(0.0, 0.0, 0.0, 45.5);

        // Connect and query
        client.connect();
        double r = client.getStageR();

        assertEquals(45.5, r, 0.01);
    }

    @Test
    @DisplayName("Test stage XY movement")
    void testMoveStageXY() throws IOException, InterruptedException {
        // Reduce movement delay for faster test
        mockServer.setMoveDelay(10);

        client.connect();
        client.moveStageXY(500.0, 750.0);

        // Give mock server time to update
        Thread.sleep(50);

        // Verify position changed
        double[] newPos = mockServer.getPosition();
        assertEquals(500.0, newPos[0], 0.01);
        assertEquals(750.0, newPos[1], 0.01);
    }

    @Test
    @DisplayName("Test stage Z movement")
    void testMoveStageZ() throws IOException, InterruptedException {
        mockServer.setMoveDelay(10);

        client.connect();
        client.moveStageZ(-100.0);

        Thread.sleep(50);

        double[] newPos = mockServer.getPosition();
        assertEquals(-100.0, newPos[2], 0.01);
    }

    @Test
    @DisplayName("Test stage rotation")
    void testMoveStageR() throws IOException, InterruptedException {
        mockServer.setMoveDelay(10);

        client.connect();
        client.moveStageR(90.0);

        Thread.sleep(50);

        double[] newPos = mockServer.getPosition();
        assertEquals(90.0, newPos[3], 0.01);
    }

    @Test
    @DisplayName("Test acquisition command")
    void testAcquisition() throws IOException {
        client.connect();

        // Should not throw
        assertDoesNotThrow(() -> {
            client.startAcquisition(
                    "/path/to/config.yaml",
                    "/projects",
                    "sample001",
                    "BF_10x",
                    "region1",
                    new double[]{0.0, 45.0, 90.0}
            );
        });
    }

    @Test
    @DisplayName("Test automatic reconnection")
    void testReconnection() throws IOException, InterruptedException {
        // Create client with fast reconnection for testing
        client = new MicroscopeSocketClient("localhost", serverPort, 1000, 1000, 3, 500, 30000);
        client.connect();
        assertTrue(client.isConnected());

        // Simulate server restart
        mockServer.stop();
        Thread.sleep(100);

        // Client should detect disconnection
        try {
            client.getStageXY();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected
        }

        // Restart server
        mockServer = new MockMicroscopeServer(serverPort);
        mockServer.start();

        // Wait for automatic reconnection
        Thread.sleep(1500);

        // Should work again
        assertDoesNotThrow(() -> client.getStageXY());
    }

    @Test
    @DisplayName("Test concurrent operations")
    void testConcurrency() throws Exception {
        client.connect();

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicBoolean hasErrors = new AtomicBoolean(false);

        // Create threads that will all query position simultaneously
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start

                    // Perform multiple operations
                    for (int j = 0; j < 5; j++) {
                        client.getStageXY();
                        client.getStageZ();
                        client.moveStageXY(j * 10, j * 20);
                    }

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    hasErrors.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // Verify success
        assertFalse(hasErrors.get());
        assertEquals(threadCount, successCount.get());
    }

    @Test
    @DisplayName("Test connection timeout")
    void testConnectionTimeout() {
        // Create client pointing to non-existent server
        MicroscopeSocketClient badClient = new MicroscopeSocketClient(
                "localhost",
                12345, // Invalid port
                500,   // Short timeout
                1000,
                1,
                1000,
                30000
        );

        // Should timeout quickly
        assertThrows(IOException.class, badClient::connect);

        badClient.close();
    }

    @Test
    @DisplayName("Test error injection and recovery")
    void testErrorHandling() throws IOException {
        // Enable error injection
        mockServer.setErrorInjection(true, 0.5); // 50% error rate

        client.connect();

        // Try multiple operations - some should fail
        int successCount = 0;
        int errorCount = 0;

        for (int i = 0; i < 10; i++) {
            try {
                client.getStageXY();
                successCount++;
            } catch (IOException e) {
                errorCount++;
            }
        }

        // Should have some successes and some errors
        assertTrue(successCount > 0);
        assertTrue(errorCount > 0);
    }

    @Test
    @DisplayName("Test health check mechanism")
    void testHealthCheck() throws Exception {
        // Create client with very short health check interval
        client = new MicroscopeSocketClient("localhost", serverPort, 1000, 1000, 3, 500, 500);
        client.connect();

        // Let health checks run
        Thread.sleep(1500);

        // Should still be connected
        assertTrue(client.isConnected());
    }

    @Test
    @DisplayName("Test server shutdown command")
    void testServerShutdown() throws IOException, InterruptedException {
        client.connect();

        // Verify server is running
        assertEquals(1, mockServer.getActiveClientCount());

        // Send shutdown command
        client.shutdownServer();

        // Give server time to shut down
        Thread.sleep(500);

        // Server should be stopped
        assertEquals(0, mockServer.getActiveClientCount());
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("Test large acquisition parameters")
    void testLargeAcquisition() throws IOException {
        client.connect();

        // Create large angle array
        double[] angles = new double[100];
        for (int i = 0; i < angles.length; i++) {
            angles[i] = i * 3.6; // 0 to 360 degrees
        }

        // Should handle large message
        assertDoesNotThrow(() -> {
            client.startAcquisition(
                    "/very/long/path/to/configuration/file.yaml",
                    "/very/long/path/to/projects/folder",
                    "very_long_sample_label_with_lots_of_characters",
                    "complex_scan_type_with_parameters",
                    "detailed_region_name",
                    angles
            );
        });
    }

    @Test
    @DisplayName("Test edge case coordinates")
    void testEdgeCaseCoordinates() throws IOException {
        client.connect();

        // Test extreme values
        double[][] testCases = {
                {0.0, 0.0},
                {-21000.0, -9000.0}, // Min bounds
                {33000.0, 11000.0},  // Max bounds
                {0.000001, 0.000001}, // Very small
                {-0.000001, -0.000001}
        };

        for (double[] coords : testCases) {
            assertDoesNotThrow(() -> client.moveStageXY(coords[0], coords[1]));
        }
    }
}