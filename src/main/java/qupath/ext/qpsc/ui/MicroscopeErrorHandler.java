package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.microscope.MicroscopeHardwareException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;

/**
 * Centralized error handler for microscope-related UI errors.
 * Provides user-friendly error messages and actionable guidance based on the exception type.
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class MicroscopeErrorHandler {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeErrorHandler.class);

    /**
     * Handles an exception from a microscope operation and displays an appropriate error dialog.
     *
     * @param e The exception that occurred
     * @param context Description of what was being attempted (e.g., "get stage position", "start acquisition")
     */
    public static void handleException(Exception e, String context) {
        logger.error("Error during {}: {}", context, e.getMessage(), e);

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Microscope Error");
            alert.setHeaderText("Failed to " + context);

            String message = buildUserMessage(e);
            String guidance = buildGuidance(e);

            alert.setContentText(message + "\n\n" + guidance);

            // Add expandable exception details
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String exceptionText = sw.toString();

            Label label = new Label("Technical Details:");
            TextArea textArea = new TextArea(exceptionText);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            GridPane.setVgrow(textArea, Priority.ALWAYS);
            GridPane.setHgrow(textArea, Priority.ALWAYS);

            GridPane expContent = new GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(label, 0, 0);
            expContent.add(textArea, 0, 1);

            alert.getDialogPane().setExpandableContent(expContent);
            alert.showAndWait();
        });
    }

    /**
     * Handles an exception silently (logs only, no dialog).
     * Useful for non-critical operations where UI interruption is not desired.
     *
     * @param e The exception that occurred
     * @param context Description of what was being attempted
     */
    public static void handleSilently(Exception e, String context) {
        logger.warn("Non-critical error during {}: {}", context, e.getMessage());
    }

    /**
     * Builds a user-friendly error message based on the exception type.
     *
     * @param e The exception
     * @return User-friendly error message
     */
    private static String buildUserMessage(Exception e) {
        if (e instanceof MicroscopeHardwareException) {
            return "Microscope hardware error: " + e.getMessage();
        } else if (e instanceof SocketTimeoutException) {
            return "The microscope server is not responding. The server may be busy, or the hardware may not be responding.";
        } else if (e instanceof IOException && e.getMessage().contains("Connection refused")) {
            return "Cannot connect to the microscope server. The Python server is not running.";
        } else if (e instanceof IOException) {
            return "Communication error: " + e.getMessage();
        } else {
            return "Unexpected error: " + e.getMessage();
        }
    }

    /**
     * Builds actionable guidance for the user based on the exception type.
     *
     * @param e The exception
     * @return Guidance text
     */
    private static String buildGuidance(Exception e) {
        if (e instanceof MicroscopeHardwareException) {
            return "Please check:\n" +
                   "1. MicroManager is running\n" +
                   "2. Hardware devices are loaded in MicroManager\n" +
                   "3. Stage devices are properly configured";
        } else if (e instanceof SocketTimeoutException) {
            return "Please check:\n" +
                   "1. MicroManager is running and responding\n" +
                   "2. Hardware devices are not in an error state\n" +
                   "3. Python server logs for errors\n" +
                   "4. Try restarting MicroManager";
        } else if (e instanceof IOException && e.getMessage().contains("Connection refused")) {
            return "Please:\n" +
                   "1. Start the Python microscope server\n" +
                   "2. Verify the server host/port in Settings\n" +
                   "3. Check for firewall issues";
        } else if (e instanceof IOException) {
            return "Please check:\n" +
                   "1. Network connection to microscope server\n" +
                   "2. Server logs for errors\n" +
                   "3. Try reconnecting via Settings > Server Connection";
        } else {
            return "See technical details below for more information.";
        }
    }

    /**
     * Shows a confirmation dialog asking if the user wants to continue despite an error.
     *
     * @param e The exception that occurred
     * @param context Description of what was being attempted
     * @return true if user wants to continue, false otherwise
     */
    public static boolean confirmContinue(Exception e, String context) {
        logger.warn("Error during {}: {}", context, e.getMessage());

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Microscope Warning");
        alert.setHeaderText("Error during " + context);

        String message = buildUserMessage(e);
        alert.setContentText(message + "\n\nDo you want to continue anyway?");

        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }
}
