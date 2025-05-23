package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * TestWorkFlowController
 *
 * Pops up a simple dialog, starts a heartbeat server, and launches a Python process
 * that checks in with the server. Heartbeat and Python process terminate cleanly when
 * the dialog is closed or cancelled.
 */
public class TestWorkFlowController {

    private static final Logger logger = LoggerFactory.getLogger(TestWorkFlowController.class);
    private static final int HEARTBEAT_PORT = 53717; // Any open port

    /**
     * Shows a dialog for testing stage movement and maintains a heartbeat server
     * while the dialog is open. Returns a CompletableFuture that completes when
     * the dialog is closed.
     */
    public static CompletableFuture<Void> showDialog() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<Void> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle(res.getString("testDialog.title"));
            dlg.setHeaderText(res.getString("testDialog.header"));

            // -- Basic label (minimal for test) --
            Label testLabel = new Label(res.getString("testDialog.label.heartbeat"));
            GridPane grid = new GridPane();
            grid.setPadding(new Insets(20));
            grid.add(testLabel, 0, 0);

            dlg.getDialogPane().setContent(grid);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

            // Only start heartbeat server and Python after dialog is shown (not on failure)
            dlg.setOnShown(ev -> {
                // Start heartbeat and Python
                startHeartbeatServer(future);
                launchPythonHeartbeatClient();
            });

            dlg.setOnCloseRequest(ev -> {
                // Mark workflow as done
                if (!future.isDone())
                    future.complete(null);
            });

            dlg.show();
        });

        return future;
    }

    /**
     * Starts a server socket that listens for heartbeats from the Python process.
     * If heartbeats stop, the server socket is closed.
     */
    private static void startHeartbeatServer(CompletableFuture<Void> workflowFuture) {
        Thread t = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(HEARTBEAT_PORT)) {
                logger.info("Heartbeat server listening on port {}", HEARTBEAT_PORT);
                try (Socket client = serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                    String line;
                    boolean running = true;
                    while (running && !workflowFuture.isDone()) {
                        // Set a socket read timeout so we don't block forever
                        client.setSoTimeout(5000);
                        try {
                            line = in.readLine();
                            if (line == null) {
                                logger.info("Heartbeat client disconnected.");
                                break;
                            }
                            logger.info("Heartbeat received: {}", line);
                        } catch (IOException e) {
                            // Socket read timeout or connection reset
                            if (workflowFuture.isDone()) {
                                logger.info("Heartbeat server closed after workflow end.");
                            } else {
                                logger.info("Heartbeat server error or timeout (expected if closed): {}", e.getMessage());
                            }
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Heartbeat server could not start: {}", e.getMessage());
            }
        }, "HeartbeatServer");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Launches a Python process that connects to the heartbeat server and
     * sends a heartbeat every 2 seconds, terminating if the connection is lost.
     */
    private static void launchPythonHeartbeatClient() {
        String pythonExe = "F:\\miniconda\\envs\\qubalab\\python.exe"; // Update with actual path!
        String script = "F:\\QPScopeExtension\\heartbeat_client.py"; // Update with actual script path!

        ProcessBuilder pb = new ProcessBuilder(pythonExe, script, String.valueOf(HEARTBEAT_PORT));
        pb.inheritIO(); // Pipe Python stdout/stderr to console for debugging
        try {
            Process p = pb.start();
            logger.info("Started Python heartbeat client (pid: {})", p.pid());
        } catch (IOException e) {
            logger.error("Failed to launch Python heartbeat client: {}", e.getMessage());
        }
    }
}
