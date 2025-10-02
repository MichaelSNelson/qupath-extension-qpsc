package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    // Singleton instance for managing all concurrent stitching operations
    private static StitchingBlockingDialog instance = null;
    private static final Object instanceLock = new Object();

    private final Dialog<Void> dialog = new Dialog<>();
    private final ListView<String> statusListView = new ListView<>();
    private final Label countLabel = new Label();
    private final ProgressIndicator progressIndicator = new ProgressIndicator();

    // Track active stitching operations by ID
    private final Map<String, String> activeOperations = new ConcurrentHashMap<>();
    private final AtomicBoolean isComplete = new AtomicBoolean(false);
    
    /**
     * Private constructor - use static show() method to access singleton instance.
     */
    private StitchingBlockingDialog() {
        // Configure dialog properties
        dialog.setTitle("Stitching in Progress");
        dialog.setHeaderText("Processing stitching operations...");
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setResizable(false);

        // Set owner to QuPath main window for proper modality
        Stage owner = Window.getWindows().stream()
                .filter(window -> window instanceof Stage && window.isShowing())
                .map(window -> (Stage) window)
                .filter(stage -> stage.getTitle() != null && stage.getTitle().contains("QuPath"))
                .findFirst()
                .orElse(null);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        
        // Create content
        VBox content = createDialogContent();
        dialog.getDialogPane().setContent(content);
        
        // Add buttons
        ButtonType dismissButton = new ButtonType("Dismiss (At Your Own Risk)", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(dismissButton);
        
        // Configure close button warning
        Button closeButton = (Button) dialog.getDialogPane().lookupButton(dismissButton);
        closeButton.setOnAction(event -> {
            logger.info("Dismiss button clicked, isComplete={}", isComplete.get());
            if (!isComplete.get()) {
                // Always consume the event first to prevent immediate closing
                event.consume();
                logger.info("Event consumed, showing dismiss warning");

                // Show warning dialog asynchronously to avoid deadlock
                showDismissWarningAsync(confirmed -> {
                    logger.info("Dismiss warning callback received, confirmed={}", confirmed);
                    if (confirmed) {
                        // User confirmed - close the stitching dialog
                        logger.info("User confirmed dismiss, closing dialog");
                        dialog.close();
                    } else {
                        logger.info("User cancelled dismiss, dialog remains open");
                    }
                });
            } else {
                logger.info("Stitching complete, allowing dialog to close normally");
            }
        });
        
        // Set default button to minimize accidental dismissal
        closeButton.setDefaultButton(false);
        closeButton.setCancelButton(true);

        logger.info("Created stitching blocking dialog singleton");
    }
    
    /**
     * Creates the main content for the dialog showing all active stitching operations.
     *
     * @return VBox containing the dialog content
     */
    private VBox createDialogContent() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        content.setPrefWidth(450);

        // Main message
        Label mainMessage = new Label("Stitching operations in progress...");
        mainMessage.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // Progress indicator
        progressIndicator.setPrefSize(60, 60);

        // Count label
        countLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        updateCountLabel();

        // Status list view showing all active operations
        statusListView.setPrefHeight(150);
        statusListView.setStyle("-fx-font-size: 11px;");

        // Warning message
        Label warningMessage = new Label(
                "⚠️ WARNING: Interacting with QuPath during stitching may cause errors or crashes. " +
                "Please wait for stitching to complete.");
        warningMessage.setWrapText(true);
        warningMessage.setMaxWidth(400);
        warningMessage.setStyle("-fx-text-fill: orange; -fx-font-style: italic; -fx-text-alignment: center;");

        // Instructions
        Label instructions = new Label(
                "This dialog will close automatically when all stitching operations complete. " +
                "You may dismiss it at your own risk if necessary.");
        instructions.setWrapText(true);
        instructions.setMaxWidth(400);
        instructions.setStyle("-fx-text-fill: gray; -fx-font-size: 11px; -fx-text-alignment: center;");

        content.getChildren().addAll(
                mainMessage,
                progressIndicator,
                countLabel,
                statusListView,
                new Separator(),
                warningMessage,
                instructions
        );

        return content;
    }

    /**
     * Updates the count label with the current number of active operations.
     */
    private void updateCountLabel() {
        int count = activeOperations.size();
        if (count == 1) {
            countLabel.setText("1 operation in progress");
        } else {
            countLabel.setText(count + " operations in progress");
        }
    }

    /**
     * Updates the status list view with current operations.
     */
    private void updateStatusList() {
        Platform.runLater(() -> {
            statusListView.getItems().clear();
            activeOperations.values().forEach(status -> statusListView.getItems().add("• " + status));
            updateCountLabel();
        });
    }
    
    /**
     * Shows a warning dialog when user attempts to dismiss the stitching dialog.
     * Uses background thread with CountDownLatch to show modal dialog without blocking JavaFX thread.
     *
     * @param callback Consumer that receives true if user confirms, false otherwise
     */
    private void showDismissWarningAsync(java.util.function.Consumer<Boolean> callback) {
        logger.warn("User attempting to dismiss stitching blocking dialog");

        // Use background thread to avoid blocking JavaFX thread with showAndWait()
        CompletableFuture.runAsync(() -> {
            // Create and configure warning dialog on JavaFX thread
            java.util.concurrent.atomic.AtomicReference<Alert> warningRef = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            Platform.runLater(() -> {
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

                // Make the dialog modal relative to the stitching dialog
                warning.initModality(Modality.APPLICATION_MODAL);
                warning.initOwner(dialog.getOwner());

                // Make NO the default button
                Button noButton = (Button) warning.getDialogPane().lookupButton(ButtonType.NO);
                noButton.setDefaultButton(true);

                Button yesButton = (Button) warning.getDialogPane().lookupButton(ButtonType.YES);
                yesButton.setDefaultButton(false);
                yesButton.setStyle("-fx-base: #ff6b6b;"); // Red color to indicate danger

                // Set always on top
                warning.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene != null && newScene.getWindow() instanceof Stage warningStage) {
                        warningStage.setAlwaysOnTop(true);
                        warningStage.toFront();
                    }
                });

                warningRef.set(warning);
                latch.countDown();
            });

            // Wait for dialog creation
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for warning dialog creation", e);
                Platform.runLater(() -> callback.accept(false));
                return;
            }

            // Show dialog and wait for result on JavaFX thread
            java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.CountDownLatch resultLatch = new java.util.concurrent.CountDownLatch(1);

            Platform.runLater(() -> {
                logger.info("Showing dismiss warning dialog");
                Alert warning = warningRef.get();
                warning.showAndWait().ifPresentOrElse(
                    buttonType -> {
                        boolean confirmed = buttonType == ButtonType.YES;
                        logger.info("Dismiss warning dialog closed, result={}", confirmed ? "YES" : "NO");
                        result.set(confirmed);
                        resultLatch.countDown();
                    },
                    () -> {
                        logger.info("Dismiss warning dialog closed without selection");
                        result.set(false);
                        resultLatch.countDown();
                    }
                );
            });

            // Wait for result
            try {
                resultLatch.await();
            } catch (InterruptedException e) {
                logger.error("Interrupted waiting for warning dialog result", e);
                Platform.runLater(() -> callback.accept(false));
                return;
            }

            // Call callback on JavaFX thread
            Platform.runLater(() -> callback.accept(result.get()));
        });
    }
    
    /**
     * Registers a new stitching operation and shows/updates the singleton dialog.
     * This method should be called on the JavaFX Application Thread.
     *
     * @param operationId Unique identifier for this stitching operation
     * @param displayName Display name for this operation (e.g., "Sample123 - Tissue_1")
     * @return Singleton StitchingBlockingDialog instance for controlling the operation
     */
    public static StitchingBlockingDialog show(String operationId, String displayName) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("StitchingBlockingDialog.show() must be called on JavaFX Application Thread");
        }

        synchronized (instanceLock) {
            // Create singleton instance if it doesn't exist
            if (instance == null) {
                instance = new StitchingBlockingDialog();

                // Handle window close request (X button)
                instance.dialog.setOnCloseRequest(event -> {
                    logger.info("Window close request (X button), isComplete={}", instance.isComplete.get());
                    if (!instance.isComplete.get()) {
                        event.consume();
                        logger.info("Close request consumed, showing warning");
                        instance.showDismissWarningAsync(confirmed -> {
                            if (confirmed) {
                                logger.info("User confirmed close via X button");
                                instance.forceClose();
                            }
                        });
                    }
                });

                // Show dialog non-blocking (modality will block interface, but not this thread)
                instance.dialog.show();

                // Ensure dialog is always on top and stays visible
                Platform.runLater(() -> {
                    if (instance.dialog.getDialogPane().getScene() != null &&
                        instance.dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                        stage.setAlwaysOnTop(true);
                        stage.toFront();
                        stage.requestFocus();

                        // Add a periodic timer to keep dialog on top during stitching
                        Timeline keepOnTop = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
                            if (!instance.isComplete.get() && stage.isShowing()) {
                                stage.toFront();
                                stage.setAlwaysOnTop(true); // Re-apply in case it was lost
                            }
                        }));
                        keepOnTop.setCycleCount(Timeline.INDEFINITE);
                        keepOnTop.play();

                        // Stop the timer when dialog closes
                        stage.setOnHiding(e -> keepOnTop.stop());
                    }
                });

                logger.info("Created and showing stitching blocking dialog");
            }

            // Add this operation to tracking
            instance.activeOperations.put(operationId, displayName);
            instance.updateStatusList();
            logger.info("Registered stitching operation: {} ({})", operationId, displayName);

            return instance;
        }
    }

    /**
     * Convenience method for backward compatibility - uses operationId as displayName.
     */
    public static StitchingBlockingDialog show(String sampleName) {
        return show(sampleName, sampleName);
    }
    
    /**
     * Updates the status message for a specific operation.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the operation to update
     * @param status The new status message
     */
    public void updateStatus(String operationId, String status) {
        if (activeOperations.containsKey(operationId)) {
            activeOperations.put(operationId, status);
            updateStatusList();
            logger.debug("Updated stitching status for {}: {}", operationId, status);
        }
    }

    /**
     * Marks an operation as complete and removes it from tracking.
     * Closes the dialog if no operations remain.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the completed operation
     */
    public void completeOperation(String operationId) {
        Platform.runLater(() -> {
            if (activeOperations.remove(operationId) != null) {
                logger.info("Operation completed: {}", operationId);
                updateStatusList();

                // Close dialog if no operations remain
                if (activeOperations.isEmpty()) {
                    logger.info("All stitching operations complete - closing dialog");
                    if (!isComplete.getAndSet(true) && dialog.isShowing()) {
                        dialog.close();
                        synchronized (instanceLock) {
                            instance = null; // Reset singleton for future use
                        }
                    }
                }
            }
        });
    }

    /**
     * Marks an operation as failed with an error message.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the failed operation
     * @param errorMessage The error message to log and display
     */
    public void failOperation(String operationId, String errorMessage) {
        Platform.runLater(() -> {
            if (activeOperations.remove(operationId) != null) {
                logger.error("Operation failed ({}): {}", operationId, errorMessage);

                // Show error dialog (use show() to avoid blocking)
                Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                errorAlert.setTitle("Stitching Error");
                errorAlert.setHeaderText("Stitching operation failed");
                errorAlert.setContentText("Operation: " + operationId + "\n\nError:\n" + errorMessage);
                errorAlert.show();

                updateStatusList();

                // Close dialog if no operations remain
                if (activeOperations.isEmpty()) {
                    logger.info("No stitching operations remain - closing dialog");
                    if (!isComplete.getAndSet(true) && dialog.isShowing()) {
                        dialog.close();
                        synchronized (instanceLock) {
                            instance = null; // Reset singleton for future use
                        }
                    }
                }
            }
        });
    }

    /**
     * Forces the dialog to close immediately, clearing all operations.
     * Use only when user explicitly dismisses the dialog.
     */
    private void forceClose() {
        Platform.runLater(() -> {
            logger.warn("Force closing stitching dialog with {} operations still active", activeOperations.size());
            activeOperations.clear();
            if (!isComplete.getAndSet(true) && dialog.isShowing()) {
                dialog.close();
                synchronized (instanceLock) {
                    instance = null; // Reset singleton for future use
                }
            }
        });
    }

    /**
     * Legacy method for backward compatibility - completes the operation.
     * @deprecated Use completeOperation(operationId) instead
     */
    @Deprecated
    public void close() {
        // For backward compatibility, complete the first operation or close if empty
        if (!activeOperations.isEmpty()) {
            String firstId = activeOperations.keySet().iterator().next();
            completeOperation(firstId);
        }
    }

    /**
     * Legacy method for backward compatibility - fails the operation with error.
     * @deprecated Use failOperation(operationId, errorMessage) instead
     */
    @Deprecated
    public void closeWithError(String errorMessage) {
        // For backward compatibility, fail the first operation or close if empty
        if (!activeOperations.isEmpty()) {
            String firstId = activeOperations.keySet().iterator().next();
            failOperation(firstId, errorMessage);
        }
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