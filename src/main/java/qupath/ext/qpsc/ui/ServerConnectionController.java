package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.ResourceBundle;

/**
 * UI controller for managing microscope server connections.
 * Provides a user-friendly interface for configuring socket connection parameters,
 * testing connections, and monitoring connection status.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Connection configuration (host, port, timeouts)</li>
 *   <li>Live connection testing with detailed feedback</li>
 *   <li>Connection status monitoring</li>
 *   <li>Advanced settings for reconnection and health checks</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class ServerConnectionController {
    private static final Logger logger = LoggerFactory.getLogger(ServerConnectionController.class);

    // Resource bundle for localized strings
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    // UI Components
    private Dialog<ButtonType> dialog;
    private TextField hostField;
    private Spinner<Integer> portSpinner;
    private CheckBox autoConnectCheckBox;

    // Advanced settings
    private Spinner<Integer> connectTimeoutSpinner;
    private Spinner<Integer> readTimeoutSpinner;
    private Spinner<Integer> maxReconnectSpinner;
    private Spinner<Integer> reconnectDelaySpinner;
    private Spinner<Integer> healthCheckIntervalSpinner;

    // Status components
    private Label statusLabel;
    private Label lastConnectionLabel;
    private ProgressIndicator progressIndicator;
    private TextArea logArea;

    // Connection test client
    private MicroscopeSocketClient testClient;

    /**
     * Shows the server connection configuration dialog.
     *
     * @return CompletableFuture that completes when dialog is closed
     */
    public static CompletableFuture<Void> showDialog() {
        ServerConnectionController controller = new ServerConnectionController();
        return controller.show();
    }

    /**
     * Creates and shows the dialog.
     */
    private CompletableFuture<Void> show() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            createDialog();
            dialog.showAndWait();
            cleanup();
            future.complete(null);
        });

        return future;
    }

    /**
     * Creates the dialog and all UI components.
     */
    private void createDialog() {
        dialog = new Dialog<>();
        dialog.setTitle(res.getString("server.dialog.title"));
        dialog.setHeaderText(res.getString("server.dialog.header"));
        dialog.setResizable(true);

        // Create main layout
        TabPane tabPane = new TabPane();
        tabPane.getTabs().addAll(
                createConnectionTab(),
                createAdvancedTab(),
                createStatusTab()
        );

        // Add control buttons
        dialog.getDialogPane().getButtonTypes().addAll(
                ButtonType.OK,
                ButtonType.CANCEL
        );

        // Set content
        dialog.getDialogPane().setContent(tabPane);
        dialog.getDialogPane().setPrefSize(600, 500);

        // Handle OK button
        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                saveSettings();
            }
            return buttonType;
        });

        // Load current settings
        loadSettings();
    }

    /**
     * Creates the main connection configuration tab.
     */
    private Tab createConnectionTab() {
        Tab tab = new Tab(res.getString("server.tab.connection"));
        tab.setClosable(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        int row = 0;

        // Server settings
        Label serverLabel = new Label("Server Settings");
        serverLabel.setFont(Font.font(null, FontWeight.BOLD, 14));
        grid.add(serverLabel, 0, row++, 2, 1);

        // Host
        grid.add(new Label(res.getString("server.connection.host")), 0, row);
        hostField = new TextField();
        hostField.setPromptText("127.0.0.1 or hostname");
        hostField.setPrefWidth(200);
        grid.add(hostField, 1, row++);

        // Port
        grid.add(new Label(res.getString("server.connection.port")), 0, row);
        portSpinner = new Spinner<>(1, 65535, 5000);
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(100);
        grid.add(portSpinner, 1, row++);

        // Auto-connect
        autoConnectCheckBox = new CheckBox(res.getString("server.connection.autoConnect"));
        autoConnectCheckBox.setTooltip(new Tooltip(
                "Automatically connect to the server when QuPath starts"
        ));
        grid.add(autoConnectCheckBox, 0, row++, 2, 1);

        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);

        // Test connection button
        Button testButton = new Button(res.getString("server.connection.testButton"));
        testButton.setOnAction(e -> testConnection());

        Button connectButton = new Button(res.getString("server.connection.connectButton"));
        connectButton.setOnAction(e -> connectNow());

        HBox buttonBox = new HBox(10, testButton, connectButton);
        grid.add(buttonBox, 0, row++, 2, 1);

        // Connection status
        statusLabel = new Label(res.getString("server.connection.status") + " Not tested");
        statusLabel.setWrapText(true);
        grid.add(statusLabel, 0, row++, 2, 1);

        progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        progressIndicator.setPrefSize(30, 30);
        grid.add(progressIndicator, 2, row - 1);

        tab.setContent(grid);
        return tab;
    }

    /**
     * Creates the advanced settings tab.
     */
    private Tab createAdvancedTab() {
        Tab tab = new Tab(res.getString("server.tab.advanced"));
        tab.setClosable(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        int row = 0;

        // Timeout settings
        Label timeoutLabel = new Label(res.getString("server.advanced.timeouts"));
        timeoutLabel.setFont(Font.font(null, FontWeight.BOLD, 14));
        grid.add(timeoutLabel, 0, row++, 2, 1);

        // Connection timeout
        grid.add(new Label(res.getString("server.advanced.connectionTimeout")), 0, row);
        connectTimeoutSpinner = new Spinner<>(100, 30000, 3000, 500);
        connectTimeoutSpinner.setEditable(true);
        connectTimeoutSpinner.setPrefWidth(120);
        connectTimeoutSpinner.setTooltip(new Tooltip("Time to wait for initial connection"));
        grid.add(connectTimeoutSpinner, 1, row++);

        // Read timeout
        grid.add(new Label(res.getString("server.advanced.readTimeout")), 0, row);
        readTimeoutSpinner = new Spinner<>(100, 30000, 5000, 500);
        readTimeoutSpinner.setEditable(true);
        readTimeoutSpinner.setPrefWidth(120);
        readTimeoutSpinner.setTooltip(new Tooltip("Time to wait for response from server"));
        grid.add(readTimeoutSpinner, 1, row++);

        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);

        // Reconnection settings
        Label reconnectLabel = new Label(res.getString("server.advanced.reconnection"));
        reconnectLabel.setFont(Font.font(null, FontWeight.BOLD, 14));
        grid.add(reconnectLabel, 0, row++, 2, 1);

        // Max reconnect attempts
        grid.add(new Label(res.getString("server.advanced.maxAttempts")), 0, row);
        maxReconnectSpinner = new Spinner<>(0, 10, 3);
        maxReconnectSpinner.setEditable(true);
        maxReconnectSpinner.setPrefWidth(120);
        maxReconnectSpinner.setTooltip(new Tooltip("Number of automatic reconnection attempts"));
        grid.add(maxReconnectSpinner, 1, row++);

        // Reconnect delay
        grid.add(new Label(res.getString("server.advanced.reconnectDelay")), 0, row);
        reconnectDelaySpinner = new Spinner<>(1000, 60000, 5000, 1000);
        reconnectDelaySpinner.setEditable(true);
        reconnectDelaySpinner.setPrefWidth(120);
        reconnectDelaySpinner.setTooltip(new Tooltip("Delay between reconnection attempts"));
        grid.add(reconnectDelaySpinner, 1, row++);

        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);

        // Health check settings
        Label healthLabel = new Label(res.getString("server.advanced.healthCheck"));
        healthLabel.setFont(Font.font(null, FontWeight.BOLD, 14));
        grid.add(healthLabel, 0, row++, 2, 1);

        // Health check interval
        grid.add(new Label(res.getString("server.advanced.healthInterval")), 0, row);
        healthCheckIntervalSpinner = new Spinner<>(5000, 300000, 30000, 5000);
        healthCheckIntervalSpinner.setEditable(true);
        healthCheckIntervalSpinner.setPrefWidth(120);
        healthCheckIntervalSpinner.setTooltip(new Tooltip("Interval between connection health checks"));
        grid.add(healthCheckIntervalSpinner, 1, row++);

        // Reset to defaults button
        Button resetButton = new Button(res.getString("server.advanced.resetDefaults"));
        resetButton.setOnAction(e -> resetToDefaults());
        grid.add(resetButton, 0, row++, 2, 1);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        tab.setContent(scrollPane);
        return tab;
    }

    /**
     * Creates the connection status tab.
     */
    private Tab createStatusTab() {
        Tab tab = new Tab(res.getString("server.tab.status"));
        tab.setClosable(false);

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(20));

        // Current status
        Label statusTitle = new Label(res.getString("server.status.title"));
        statusTitle.setFont(Font.font(null, FontWeight.BOLD, 14));
        vbox.getChildren().add(statusTitle);

        // Last connection info
        lastConnectionLabel = new Label("Loading...");
        lastConnectionLabel.setWrapText(true);
        vbox.getChildren().add(lastConnectionLabel);

        // Connection log
        Label logTitle = new Label(res.getString("server.status.log"));
        logTitle.setFont(Font.font(null, FontWeight.BOLD, 14));
        vbox.getChildren().add(logTitle);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(15);
        logArea.setStyle("-fx-font-family: monospace");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        vbox.getChildren().add(logArea);

        // Clear log button
        Button clearButton = new Button(res.getString("server.status.clearLog"));
        clearButton.setOnAction(e -> logArea.clear());
        vbox.getChildren().add(clearButton);

        tab.setContent(vbox);
        return tab;
    }

    /**
     * Loads current settings from preferences.
     */
    private void loadSettings() {
        // Main settings
        hostField.setText(QPPreferenceDialog.getMicroscopeServerHost());
        portSpinner.getValueFactory().setValue(QPPreferenceDialog.getMicroscopeServerPort());
        autoConnectCheckBox.setSelected(QPPreferenceDialog.getAutoConnectToServer());

        // Advanced settings
        connectTimeoutSpinner.getValueFactory().setValue(PersistentPreferences.getSocketConnectionTimeoutMs());
        readTimeoutSpinner.getValueFactory().setValue(PersistentPreferences.getSocketReadTimeoutMs());
        maxReconnectSpinner.getValueFactory().setValue(PersistentPreferences.getSocketMaxReconnectAttempts());
        reconnectDelaySpinner.getValueFactory().setValue((int) PersistentPreferences.getSocketReconnectDelayMs());
        healthCheckIntervalSpinner.getValueFactory().setValue((int) PersistentPreferences.getSocketHealthCheckIntervalMs());

        // Status
        updateStatusDisplay();
    }

    /**
     * Saves settings to preferences.
     */
    private void saveSettings() {
        // Main settings
        QPPreferenceDialog.setMicroscopeServerHost(hostField.getText());
        QPPreferenceDialog.setMicroscopeServerPort(portSpinner.getValue());
        QPPreferenceDialog.setAutoConnectToServer(autoConnectCheckBox.isSelected());

        // Advanced settings
        PersistentPreferences.setSocketConnectionTimeoutMs(connectTimeoutSpinner.getValue());
        PersistentPreferences.setSocketReadTimeoutMs(readTimeoutSpinner.getValue());
        PersistentPreferences.setSocketMaxReconnectAttempts(maxReconnectSpinner.getValue());
        PersistentPreferences.setSocketReconnectDelayMs(reconnectDelaySpinner.getValue());
        PersistentPreferences.setSocketHealthCheckIntervalMs(healthCheckIntervalSpinner.getValue());

        logger.info("Server connection settings saved");
        logMessage("Settings saved successfully");
    }

    /**
     * Resets advanced settings to defaults.
     */
    private void resetToDefaults() {
        connectTimeoutSpinner.getValueFactory().setValue(3000);
        readTimeoutSpinner.getValueFactory().setValue(5000);
        maxReconnectSpinner.getValueFactory().setValue(3);
        reconnectDelaySpinner.getValueFactory().setValue(5000);
        healthCheckIntervalSpinner.getValueFactory().setValue(30000);

        logMessage("Advanced settings reset to defaults");
    }

    /**
     * Tests the connection with current settings.
     */
    private void testConnection() {
        // Show progress
        progressIndicator.setVisible(true);
        statusLabel.setText(res.getString("server.status.testing"));
        statusLabel.setTextFill(Color.BLUE);

        // Disable controls during test
        setControlsEnabled(false);

        // Run test in background
        CompletableFuture.runAsync(() -> {
            try {
                // Create test client
                testClient = new MicroscopeSocketClient(
                        hostField.getText(),
                        portSpinner.getValue(),
                        connectTimeoutSpinner.getValue(),
                        readTimeoutSpinner.getValue(),
                        1, // Only try once for test
                        1000,
                        30000
                );

                logMessage("Testing connection to " + hostField.getText() + ":" + portSpinner.getValue());

                // Try to connect
                testClient.connect();
                logMessage("Connected successfully!");

                // Test getting position
                double[] pos = testClient.getStageXY();
                logMessage(String.format("Stage position: (%.2f, %.2f)", pos[0], pos[1]));

                // Test Z position
                double z = testClient.getStageZ();
                logMessage(String.format("Stage Z position: %.2f", z));

                // Success
                Platform.runLater(() -> {
                    statusLabel.setText(String.format(
                            res.getString("server.status.success"),
                            pos[0], pos[1], z
                    ));
                    statusLabel.setTextFill(Color.GREEN);
                });

            } catch (Exception e) {
                // Failed
                String error = e.getMessage();
                logMessage("Connection failed: " + error);

                Platform.runLater(() -> {
                    statusLabel.setText(String.format(
                            res.getString("server.status.failed"),
                            error
                    ));
                    statusLabel.setTextFill(Color.RED);
                });

            } finally {
                // Clean up
                if (testClient != null) {
                    testClient.close();
                    testClient = null;
                }

                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    setControlsEnabled(true);
                });
            }
        });
    }

    /**
     * Connects to the server immediately and updates the main controller.
     */
    private void connectNow() {
        progressIndicator.setVisible(true);
        statusLabel.setText(res.getString("server.status.connecting"));
        statusLabel.setTextFill(Color.BLUE);
        setControlsEnabled(false);

        CompletableFuture.runAsync(() -> {
            try {
                // Save settings first
                Platform.runLater(this::saveSettings);
                Thread.sleep(100); // Let settings save

                // Get controller instance and connect
                MicroscopeController controller = MicroscopeController.getInstance();
                controller.disconnect(); // Disconnect first if connected

                controller.connect();

                logMessage("Connected to server via MicroscopeController");

                Platform.runLater(() -> {
                    statusLabel.setText(res.getString("server.status.connected"));
                    statusLabel.setTextFill(Color.GREEN);
                    updateStatusDisplay();
                });

            } catch (Exception e) {
                String error = e.getMessage();
                logMessage("Failed to connect via controller: " + error);

                Platform.runLater(() -> {
                    statusLabel.setText(String.format(
                            res.getString("server.status.failed"),
                            error
                    ));
                    statusLabel.setTextFill(Color.RED);
                });

            } finally {
                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    setControlsEnabled(true);
                });
            }
        });
    }

    /**
     * Updates the status display with last connection info.
     */
    private void updateStatusDisplay() {
        String lastStatus = PersistentPreferences.getSocketLastConnectionStatus();
        String lastTime = PersistentPreferences.getSocketLastConnectionTime();

        StringBuilder sb = new StringBuilder();

        // Current controller status
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            boolean connected = controller.isConnected();
            sb.append(res.getString(connected ?
                    "server.status.controllerConnected" :
                    "server.status.controllerDisconnected"));
            sb.append("\n\n");
        } catch (Exception e) {
            sb.append(res.getString("server.status.controllerNotInit")).append("\n\n");
        }

        // Last connection attempt
        if (!lastStatus.isEmpty()) {
            sb.append("Last Connection: ").append(lastStatus).append("\n");
        }
        if (!lastTime.isEmpty()) {
            sb.append("Time: ").append(lastTime).append("\n");
        }

        lastConnectionLabel.setText(sb.toString());
    }

    /**
     * Logs a message to the log area.
     */
    private void logMessage(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String logEntry = String.format("[%s] %s%n", timestamp, message);

        Platform.runLater(() -> {
            logArea.appendText(logEntry);
            // Auto-scroll to bottom
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * Enables or disables controls.
     */
    private void setControlsEnabled(boolean enabled) {
        hostField.setDisable(!enabled);
        portSpinner.setDisable(!enabled);
        // Don't disable checkboxes - user should be able to change these anytime
    }

    /**
     * Cleanup when dialog closes.
     */
    private void cleanup() {
        if (testClient != null) {
            testClient.close();
            testClient = null;
        }
    }
}