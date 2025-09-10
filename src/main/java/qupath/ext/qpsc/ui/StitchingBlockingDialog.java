package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StitchingBlockingDialog - Modal dialog that blocks QuPath interface during stitching operations
 * 
 * <p>This dialog addresses issues that occur when users interact with the QuPath interface
 * (switching between images, opening dialogs, etc.) while stitching operations are ongoing.
 * It provides a modal barrier that prevents interface interaction while clearly communicating
 * the stitching status to the user.</p>
 * 
 * <p>Key features:
 * <ul>
 *   <li>Modal dialog that blocks QuPath interface interaction</li>
 *   <li>Progress indication for ongoing stitching operations</li>
 *   <li>User can dismiss at their own risk (with warning)</li>
 *   <li>Automatically closes when stitching completes</li>
 *   <li>Thread-safe operation with JavaFX threading</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>
 * StitchingBlockingDialog blockingDialog = StitchingBlockingDialog.show("Sample123");
 * 
 * // Perform stitching operation
 * CompletableFuture.runAsync(() -> {
 *     try {
 *         // Long-running stitching operation
 *         String result = TileProcessingUtilities.stitchImagesAndUpdateProject(...);
 *         Platform.runLater(() -> blockingDialog.close()); // Close dialog when complete
 *     } catch (Exception e) {
 *         Platform.runLater(() -> blockingDialog.closeWithError(e.getMessage()));
 *     }
 * });
 * </pre>
 */
public class StitchingBlockingDialog {
    
    private static final Logger logger = LoggerFactory.getLogger(StitchingBlockingDialog.class);
    
    private final Dialog<Void> dialog;
    private final Label statusLabel;
    private final ProgressIndicator progressIndicator;
    private final AtomicBoolean isComplete = new AtomicBoolean(false);
    
    /**
     * Private constructor - use static show() method to create instances.
     * 
     * @param sampleName The name of the sample being stitched
     */
    private StitchingBlockingDialog(String sampleName) {
        dialog = new Dialog<>();
        
        // Configure dialog properties
        dialog.setTitle("Stitching in Progress");
        dialog.setHeaderText("Processing " + sampleName);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setResizable(false);
        
        // Create content
        VBox content = createDialogContent(sampleName);
        dialog.getDialogPane().setContent(content);
        
        // Add buttons
        ButtonType dismissButton = new ButtonType("Dismiss (At Your Own Risk)", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(dismissButton);
        
        // Configure close button warning
        Button closeButton = (Button) dialog.getDialogPane().lookupButton(dismissButton);
        closeButton.setOnAction(event -> {
            if (!isComplete.get()) {
                boolean confirmed = showDismissWarning();
                if (!confirmed) {
                    event.consume(); // Prevent dialog from closing
                }
            }
        });
        
        // Set default button to minimize accidental dismissal
        closeButton.setDefaultButton(false);
        closeButton.setCancelButton(true);
        
        logger.info("Created stitching blocking dialog for sample: {}", sampleName);
    }
    
    /**
     * Creates the main content for the dialog.
     * 
     * @param sampleName The sample being processed
     * @return VBox containing the dialog content
     */
    private VBox createDialogContent(String sampleName) {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        content.setPrefWidth(400);
        
        // Main message
        Label mainMessage = new Label("Stitching operation in progress...");
        mainMessage.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        
        // Progress indicator
        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(60, 60);
        
        // Status label
        statusLabel = new Label("Please wait while " + sampleName + " is being stitched.");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(350);
        statusLabel.setStyle("-fx-text-alignment: center;");
        
        // Warning message
        Label warningMessage = new Label(
                "⚠️ WARNING: Interacting with QuPath during stitching may cause errors or crashes. " +
                "Please wait for stitching to complete.");
        warningMessage.setWrapText(true);
        warningMessage.setMaxWidth(350);
        warningMessage.setStyle("-fx-text-fill: orange; -fx-font-style: italic; -fx-text-alignment: center;");
        
        // Instructions
        Label instructions = new Label(
                "This dialog will close automatically when stitching is complete. " +
                "You may dismiss it at your own risk if necessary.");
        instructions.setWrapText(true);
        instructions.setMaxWidth(350);
        instructions.setStyle("-fx-text-fill: gray; -fx-font-size: 11px; -fx-text-alignment: center;");
        
        content.getChildren().addAll(
                mainMessage,
                progressIndicator,
                statusLabel,
                new Separator(),
                warningMessage,
                instructions
        );
        
        return content;
    }
    
    /**
     * Shows a warning dialog when user attempts to dismiss the stitching dialog.
     * 
     * @return true if user confirms dismissal, false otherwise
     */
    private boolean showDismissWarning() {
        logger.warn("User attempting to dismiss stitching blocking dialog");
        
        Alert warning = new Alert(Alert.AlertType.WARNING);
        warning.setTitle("Dismiss Stitching Dialog");
        warning.setHeaderText("Are you sure you want to dismiss this dialog?");
        warning.setContentText(
                "Stitching is still in progress. Dismissing this dialog and interacting with QuPath " +
                "while stitching is ongoing may cause:\n\n" +
                "• Application crashes or freezes\n" +
                "• Corrupted stitching results\n" +
                "• Loss of acquisition data\n\n" +
                "It is strongly recommended to wait for stitching to complete.\n\n" +
                "Do you still want to proceed at your own risk?");
        
        warning.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        warning.setResizable(true);
        
        // Make NO the default button
        Button noButton = (Button) warning.getDialogPane().lookupButton(ButtonType.NO);
        noButton.setDefaultButton(true);
        
        Button yesButton = (Button) warning.getDialogPane().lookupButton(ButtonType.YES);
        yesButton.setDefaultButton(false);
        yesButton.setStyle("-fx-base: #ff6b6b;"); // Red color to indicate danger
        
        return warning.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }
    
    /**
     * Shows the stitching blocking dialog for the specified sample.
     * This method should be called on the JavaFX Application Thread.
     * 
     * @param sampleName The name of the sample being stitched
     * @return StitchingBlockingDialog instance for controlling the dialog
     */
    public static StitchingBlockingDialog show(String sampleName) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("StitchingBlockingDialog.show() must be called on JavaFX Application Thread");
        }
        
        StitchingBlockingDialog blockingDialog = new StitchingBlockingDialog(sampleName);
        
        // Show dialog non-blocking (modality will block interface, but not this thread)
        blockingDialog.dialog.show();
        
        logger.info("Showing stitching blocking dialog for sample: {}", sampleName);
        return blockingDialog;
    }
    
    /**
     * Updates the status message displayed in the dialog.
     * This method is thread-safe and can be called from any thread.
     * 
     * @param status The new status message
     */
    public void updateStatus(String status) {
        Platform.runLater(() -> {
            if (statusLabel != null && !isComplete.get()) {
                statusLabel.setText(status);
                logger.debug("Updated stitching dialog status: {}", status);
            }
        });
    }
    
    /**
     * Closes the dialog indicating successful completion.
     * This method is thread-safe and can be called from any thread.
     */
    public void close() {
        Platform.runLater(() -> {
            if (!isComplete.getAndSet(true)) {
                logger.info("Closing stitching blocking dialog - operation completed successfully");
                if (dialog.isShowing()) {
                    dialog.close();
                }
            }
        });
    }
    
    /**
     * Closes the dialog with an error message.
     * This method is thread-safe and can be called from any thread.
     * 
     * @param errorMessage The error message to log
     */
    public void closeWithError(String errorMessage) {
        Platform.runLater(() -> {
            if (!isComplete.getAndSet(true)) {
                logger.error("Closing stitching blocking dialog due to error: {}", errorMessage);
                if (dialog.isShowing()) {
                    dialog.close();
                }
                
                // Show error dialog
                Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                errorAlert.setTitle("Stitching Error");
                errorAlert.setHeaderText("Stitching operation failed");
                errorAlert.setContentText("An error occurred during stitching:\n\n" + errorMessage);
                errorAlert.showAndWait();
            }
        });
    }
    
    /**
     * Checks if the dialog is currently showing.
     * 
     * @return true if dialog is showing, false otherwise
     */
    public boolean isShowing() {
        return dialog.isShowing() && !isComplete.get();
    }
    
    /**
     * Sets the dialog as a child of the specified window.
     * This ensures proper window hierarchy and modal behavior.
     * 
     * @param owner The owner window
     */
    public void setOwner(Window owner) {
        if (dialog.getDialogPane().getScene() != null && 
            dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
            stage.initOwner(owner);
        }
    }
}