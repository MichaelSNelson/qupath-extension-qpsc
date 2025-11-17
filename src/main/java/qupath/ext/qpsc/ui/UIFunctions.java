package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javafx.util.Duration;
import javafx.scene.paint.Color;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import javafx.geometry.Insets;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.scene.control.Separator;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UIFunctions
 *
 * <p>Static UI helpers for common dialogs and notifications:
 *   - Progress bar windows with live updates.
 *   - Error and warning pop-ups.
 *   - Stage alignment GUIs (tile selection, confirmation dialogs).
 */

public class UIFunctions {
    private static final Logger logger = LoggerFactory.getLogger(UIFunctions.class);
    private static Stage progressBarStage;



    public static class ProgressHandle {
        private final Stage stage;
        private final Timeline timeline;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Consumer<Void> cancelCallback;

        public ProgressHandle(Stage stage, Timeline timeline) {
            this.stage = stage;
            this.timeline = timeline;
        }

        public void close() {
            logger.info("ProgressHandle.close() called.");
            Platform.runLater(() -> {
                timeline.stop();
                stage.close();
            });
        }

        public void setCancelCallback(Consumer<Void> callback) {
            this.cancelCallback = callback;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void triggerCancel() {
            cancelled.set(true);
            if (cancelCallback != null) {
                cancelCallback.accept(null);
            }
        }
    }

    /**
     * Show a progress bar with cancel button that watches an AtomicInteger and updates itself periodically.
     * The progress bar will stay open until either:
     * - All expected files are found (progress reaches 100%)
     * - The timeout is reached with no progress
     * - The user clicks Cancel
     * - It is explicitly closed via the returned handle
     *
     * @param progressCounter Thread-safe counter (incremented externally as work completes).
     * @param totalFiles      The max value of progressCounter.
     * @param timeoutMs       If no progress for this many ms, bar will auto-terminate.
     * @param showCancelButton Whether to show a cancel button
     * @return a ProgressHandle you can .close() when you're done, and set cancel callback on
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            int timeoutMs,
            boolean showCancelButton) {

        final ProgressHandle[] handleHolder = new ProgressHandle[1];

        Platform.runLater(() -> {
            logger.info("Creating progress bar UI on FX thread for {} total files", totalFiles);
            Stage stage = new Stage();
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(300);
            Label timeLabel = new Label("Estimating time…");
            Label progressLabel = new Label("Tiles acquired: 0 of " + totalFiles);
            Label statusLabel = new Label("Acquisition in progress...");

            VBox vbox = new VBox(10, progressBar, progressLabel, timeLabel, statusLabel);
            vbox.setStyle("-fx-padding: 10;");
            vbox.setAlignment(Pos.CENTER);

            // Add cancel button if requested
            final Button cancelButton;
            if (showCancelButton) {
                cancelButton = new Button("Cancel Acquisition");
                cancelButton.setPrefWidth(150);
                vbox.getChildren().addAll(new Separator(), cancelButton);
            } else {
                cancelButton = null;
            }

            stage.initModality(Modality.NONE);
            stage.setTitle("Microscope Acquisition Progress");
            stage.setScene(new Scene(vbox));
            stage.setAlwaysOnTop(true);
            stage.show();

            // Shared timing state
            AtomicLong startTime = new AtomicLong(0);
            AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
            AtomicInteger lastSeenProgress = new AtomicInteger(0);

            // Build the Timeline
            final Timeline timeline = new Timeline();
            KeyFrame keyFrame = new KeyFrame(Duration.millis(500), evt -> {
                int current = progressCounter.get();
                long now = System.currentTimeMillis();

                // Log every 10th check to avoid spam
                if (evt.getSource() instanceof Timeline) {
                    Timeline tl = (Timeline) evt.getSource();
                    if (tl.getCycleCount() % 10 == 0) {
                        logger.debug("Progress bar reading counter (id: {}): value = {}",
                                System.identityHashCode(progressCounter), current);
                    }
                }

                // Log progress updates
                if (current != lastSeenProgress.get()) {
                    logger.info("PROGRESS UPDATE: {} of {} files", current, totalFiles);
                    lastSeenProgress.set(current);
                    lastProgressTime.set(now);
                }

                // Record start time once work begins
                if (current > 0 && startTime.get() == 0) {
                    startTime.set(now);
                }

                // Update UI
                double fraction = totalFiles > 0 ? current / (double) totalFiles : 0.0;
                progressBar.setProgress(fraction);
                progressLabel.setText("Tiles acquired: " + current + " of " + totalFiles);

                // Update status message based on progress
                if (current == 0) {
                    statusLabel.setText("Waiting for acquisition to start...");
                } else if (current < totalFiles) {
                    statusLabel.setText("Acquiring tiles...");
                } else {
                    statusLabel.setText("Acquisition complete!");
                }

                // Calculate time estimate
                if (startTime.get() > 0 && current > 0 && current < totalFiles) {
                    long elapsed = now - startTime.get();
                    long remMs = (long) ((elapsed / (double) current) * (totalFiles - current));

                    // Format time more nicely
                    long remSeconds = remMs / 1000;
                    if (remSeconds < 60) {
                        timeLabel.setText("Time remaining: " + remSeconds + " seconds");
                    } else {
                        long minutes = remSeconds / 60;
                        long seconds = remSeconds % 60;
                        timeLabel.setText(String.format("Time remaining: %d min %d sec", minutes, seconds));
                    }
                } else if (current >= totalFiles) {
                    long totalTime = now - startTime.get();
                    long totalSeconds = totalTime / 1000;
                    if (totalSeconds < 60) {
                        timeLabel.setText("Completed in " + totalSeconds + " seconds");
                    } else {
                        long minutes = totalSeconds / 60;
                        long seconds = totalSeconds % 60;
                        timeLabel.setText(String.format("Completed in %d min %d sec", minutes, seconds));
                    }
                }

                // Check if cancelled
                if (handleHolder[0] != null && handleHolder[0].isCancelled()) {
                    logger.info("Progress bar cancelled by user");
                    statusLabel.setText("Acquisition cancelled");
                    statusLabel.setTextFill(Color.RED);
                    timeline.stop();
                    // Keep window open for 2 seconds to show cancelled status
                    PauseTransition pause = new PauseTransition(Duration.seconds(2));
                    pause.setOnFinished(e -> stage.close());
                    pause.play();
                    return;
                }

                // Check completion conditions
                boolean complete = (current >= totalFiles);
                boolean stalled = false;

                // Only check for stall if we haven't made progress in a while
                // AND we haven't reached completion
                if (!complete && current > 0) {
                    long timeSinceProgress = now - lastProgressTime.get();
                    stalled = timeSinceProgress > timeoutMs;

                    if (stalled) {
                        logger.warn("Progress stalled: no new files for {} ms (current: {}, total: {})",
                                timeSinceProgress, current, totalFiles);
                        statusLabel.setText("Timeout - acquisition may have stalled");
                        statusLabel.setTextFill(Color.RED);
                    }
                }

                // Close only when complete or truly stalled
                if (complete || stalled) {
                    logger.info("Progress bar closing - complete: {}, stalled: {}, files: {}/{}",
                            complete, stalled, current, totalFiles);
                    timeline.stop();

                    // Show final status for a moment before closing
                    if (complete) {
                        PauseTransition pause = new PauseTransition(Duration.seconds(1));
                        pause.setOnFinished(e -> stage.close());
                        pause.play();
                    } else {
                        stage.close();
                    }
                }
            });
            timeline.getKeyFrames().add(keyFrame);
            timeline.setCycleCount(Timeline.INDEFINITE);

            // Create and store the ProgressHandle
            ProgressHandle handle = new ProgressHandle(stage, timeline);
            handleHolder[0] = handle;

            // Set up cancel button action if present
            if (cancelButton != null) {
                cancelButton.setOnAction(e -> {
                    logger.info("Cancel button clicked");
                    cancelButton.setDisable(true);
                    cancelButton.setText("Cancelling...");
                    handle.triggerCancel();
                });
            }

            logger.info("Starting progress Timeline on FX thread");
            timeline.play();
        });

        // Wait for handle to be set before returning
        while (handleHolder[0] == null) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }

        logger.info("Progress bar initialized for {} files with {} ms timeout", totalFiles, timeoutMs);
        return handleHolder[0];
    }

    /**
     * Original method signature for backward compatibility
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            int timeoutMs) {
        return showProgressBarAsync(progressCounter, totalFiles, timeoutMs, false);
    }
    /**
     * Shows an error dialog on the JavaFX thread with proper text wrapping.
     */
    public static void notifyUserOfError(String message, String context) {
        logger.error("Error during {}: {}", context, message);
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Error during " + context);
            alert.setContentText(message);
            alert.initModality(Modality.APPLICATION_MODAL);

            // Enable text wrapping and set reasonable dialog width for long messages
            alert.getDialogPane().setMinWidth(500);
            alert.getDialogPane().setPrefWidth(600);

            // Force content text to wrap
            javafx.scene.control.Label contentLabel = (javafx.scene.control.Label) alert.getDialogPane().lookup(".content");
            if (contentLabel != null) {
                contentLabel.setWrapText(true);
                contentLabel.setMaxWidth(550);
            }

            alert.showAndWait();
        });
    }

    /**
     * Prompts the user to validate annotated regions; calls back with true/false.
     */
    public static void checkValidAnnotationsGUI(List<String> validNames,
                                                Consumer<Boolean> callback) {
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            stage.setTitle("Validate annotation boundaries");
            stage.setAlwaysOnTop(true);

            VBox layout = new VBox(10);
            Label info = new Label("Checking annotations...");
            Button yes = new Button("Collect regions");
            Button no = new Button("Do not collect ANY regions");

            yes.setOnAction(e -> { stage.close(); callback.accept(true); });
            no.setOnAction(e -> { stage.close(); callback.accept(false); });

            ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
            exec.scheduleAtFixedRate(() -> {
                Platform.runLater(() -> {
                    int count = (int) QP.getAnnotationObjects().stream()
                            .filter(o -> o.getPathClass() != null)  // Add null check
                            .filter(o -> validNames.contains(o.getClassification()))
                            .count();
                    info.setText("Total Annotation count in image: " + count +
                            "\nADD, MODIFY or DELETE annotations to select regions to be scanned.");
                    yes.setText("Collect " + count + " regions");
                });
            }, 0, 500, TimeUnit.MILLISECONDS);

            layout.getChildren().addAll(info, yes, no);
            stage.setScene(new Scene(layout, 400, 200));
            stage.show();
        });
    }


//    /**
//     * Confirmation dialog for current stage position accuracy.
//     */
//    public static boolean stageToQuPathAlignmentGUI2() {
//        Dialog<Boolean> dlg = new Dialog<>();
//        dlg.initModality(Modality.NONE);
//        dlg.setTitle("Position Confirmation");
//        dlg.setHeaderText(
//                "Is the current position accurate?\nCompare with the uManager live view.");
//        ButtonType ok = new ButtonType("Current Position is Accurate", ButtonBar.ButtonData.OK_DONE);
//        ButtonType cancel = new ButtonType("Cancel acquisition", ButtonBar.ButtonData.CANCEL_CLOSE);
//        dlg.getDialogPane().getButtonTypes().addAll(ok, cancel);
//
//        dlg.setResultConverter(btn -> btn == ok);
//        return dlg.showAndWait().orElse(false);
//    }
    /**
     * Confirmation dialog for current stage position accuracy.
     * Uses a custom Stage with alwaysOnTop to ensure visibility while remaining non-modal.
     * Must be called from the JavaFX Application Thread.
     *
     * Location: UIFunctions.java - stageToQuPathAlignmentGUI2() method
     *
     * @return true if user confirms position is accurate, false otherwise
     * @throws IllegalStateException if not called from JavaFX thread
     */
    public static boolean stageToQuPathAlignmentGUI2() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("stageToQuPathAlignmentGUI2 must be called from JavaFX thread");
        }

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Position Confirmation");
        stage.setAlwaysOnTop(true);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));
        layout.setAlignment(Pos.CENTER);

        Label headerLabel = new Label("Is the current position accurate?");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label instructionLabel = new Label("Compare with the uManager live view.");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        Button confirmButton = new Button("Current Position is Accurate");
        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> {
            result.set(true);
            stage.close();
            latch.countDown();
        });

        Button cancelButton = new Button("Cancel acquisition");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            result.set(false);
            stage.close();
            latch.countDown();
        });

        buttonBox.getChildren().addAll(confirmButton, cancelButton);
        layout.getChildren().addAll(headerLabel, instructionLabel, new Separator(), buttonBox);

        Scene scene = new Scene(layout, 350, 150);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            result.set(false);
            latch.countDown();
        });

        stage.centerOnScreen();
        stage.show();

        // Wait for user response without blocking the UI thread
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return result.get();
    }


    /** Pops up a modal warning dialog. */
    public static void showAlertDialog(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning!");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.showAndWait();
    }

    /**
     * Shows a blocking dialog requesting manual focus from the user.
     * Used when autofocus fails and manual intervention is required.
     * This method blocks until the user presses OK.
     */
    public static void showManualFocusDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Manual Focus Required");
        alert.setHeaderText("Autofocus Failed");
        alert.setContentText(
                "Autofocus was unable to find a reliable focus position.\n\n" +
                "Please manually focus the microscope on the tissue and press OK to continue.\n\n" +
                "The system will retry autofocus after you press OK."
        );
        alert.initModality(Modality.APPLICATION_MODAL);

        // Make dialog always on top so it's visible above progress dialog
        alert.initOwner(null);
        if (alert.getDialogPane() != null && alert.getDialogPane().getScene() != null) {
            javafx.stage.Window window = alert.getDialogPane().getScene().getWindow();
            if (window instanceof javafx.stage.Stage) {
                ((javafx.stage.Stage) window).setAlwaysOnTop(true);
            }
        }

        alert.showAndWait();
    }


    /**
     * Prompts the user to select exactly one tile (detection object) in QuPath.
     * Shows a non-modal dialog that allows the user to interact with QuPath while open.
     *
     * @param message The instruction message to display to the user
     * @return CompletableFuture that completes with the selected PathObject or null if cancelled
     */
    public static CompletableFuture<PathObject> promptTileSelectionDialogAsync(String message) {
        CompletableFuture<PathObject> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // Create a non-modal stage instead of a modal dialog
            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            stage.setTitle("Select Tile");
            stage.setAlwaysOnTop(true);

            VBox layout = new VBox(10);
            layout.setPadding(new Insets(20));

            Label instructionLabel = new Label(message);
            instructionLabel.setWrapText(true);
            instructionLabel.setPrefWidth(400);

            Label statusLabel = new Label("No tile selected");
            statusLabel.setStyle("-fx-font-weight: bold");

            Button confirmButton = new Button("Confirm Selection");
            confirmButton.setDisable(true);

            Button cancelButton = new Button("Cancel");

            HBox buttonBox = new HBox(10, confirmButton, cancelButton);
            buttonBox.setAlignment(javafx.geometry.Pos.CENTER);

            layout.getChildren().addAll(instructionLabel, statusLabel, buttonBox);

            // Check selection periodically
            Timeline selectionChecker = new Timeline(new KeyFrame(
                    Duration.millis(500),
                    e -> {
                        Collection<PathObject> selected = QP.getSelectedObjects();

                        // Filter for detection objects that have a "TileNumber" measurement
                        // This identifies objects created by our tiling system
                        List<PathObject> tiles = selected.stream()
                                .filter(PathObject::isDetection)
                                .filter(obj -> obj.getMeasurements().containsKey("TileNumber"))
                                .collect(Collectors.toList());

                        if (tiles.size() == 1) {
                            PathObject tile = tiles.get(0);
                            String tileName = tile.getName() != null ? tile.getName() :
                                    "Tile " + (int)tile.getMeasurements().get("TileNumber").doubleValue();
                            statusLabel.setText("Selected Tile Name: " + tileName);
                            statusLabel.setTextFill(javafx.scene.paint.Color.GREEN);
                            confirmButton.setDisable(false);
                        } else if (tiles.isEmpty()) {
                            statusLabel.setText("No tile selected");
                            statusLabel.setTextFill(javafx.scene.paint.Color.BLACK);
                            confirmButton.setDisable(true);
                        } else {
                            statusLabel.setText("Multiple tiles selected - please select only one");
                            statusLabel.setTextFill(javafx.scene.paint.Color.RED);
                            confirmButton.setDisable(true);
                        }
                    }
            ));
            selectionChecker.setCycleCount(Timeline.INDEFINITE);
            selectionChecker.play();

            // Button actions
            confirmButton.setOnAction(event -> {
                Collection<PathObject> selected = QP.getSelectedObjects();
                List<PathObject> tiles = selected.stream()
                        .filter(PathObject::isDetection)
                        .filter(obj -> obj.getMeasurements().containsKey("TileNumber"))
                        .collect(Collectors.toList());

                if (tiles.size() == 1) {
                    selectionChecker.stop();
                    stage.close();
                    future.complete(tiles.get(0));
                }
            });

            cancelButton.setOnAction(event -> {
                selectionChecker.stop();
                stage.close();
                future.complete(null);
            });

            stage.setOnCloseRequest(event -> {
                selectionChecker.stop();
                future.complete(null);
            });

            stage.setScene(new Scene(layout));
            stage.show();
        });

        return future;
    }
    /**
     * Shows a Yes/No dialog to the user.
     * Returns true if "Yes"/"OK" is pressed, false otherwise.
     */
    public static boolean promptYesNoDialog(String title, String message) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(title);
        dialog.setHeaderText(message);
        dialog.getButtonTypes().setAll(
                new ButtonType("Yes", ButtonBar.ButtonData.YES),
                new ButtonType("No", ButtonBar.ButtonData.NO)
        );
        var result = dialog.showAndWait();
        return result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.YES;
    }

    /**
     * Executes a long-running task with a progress dialog.
     * Enhanced version with proper cleanup and timeout handling.
     *
     * @param title Dialog title
     * @param message Progress message to display
     * @param task The task to execute
     * @return The result of the task
     */
    public static <T> T executeWithProgress(String title, String message, Callable<T> task) {
        logger.debug("executeWithProgress called with title: {}", title);

        if (Platform.isFxApplicationThread()) {
            // If on FX thread, we need to handle this carefully to avoid blocking
            logger.warn("executeWithProgress called on FX thread - using non-blocking approach");

            // Create a simple progress stage instead of Alert
            Stage progressStage = new Stage();
            progressStage.setTitle(title);
            progressStage.initModality(Modality.APPLICATION_MODAL);
            progressStage.setResizable(false);
            progressStage.setOnCloseRequest(e -> e.consume()); // Prevent closing during operation

            VBox vbox = new VBox(15);
            vbox.setPadding(new Insets(20));
            vbox.setAlignment(Pos.CENTER);

            Label headerLabel = new Label(message);
            headerLabel.setWrapText(true);
            headerLabel.setPrefWidth(350);

            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressBar.setPrefWidth(300);

            Label statusLabel = new Label("Please wait...");

            Button cancelButton = new Button("Cancel");
            cancelButton.setDisable(true); // Initially disabled

            vbox.getChildren().addAll(headerLabel, progressBar, statusLabel, cancelButton);

            Scene scene = new Scene(vbox);
            progressStage.setScene(scene);

            // Use a CompletableFuture to run the task asynchronously
            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                try {
                    logger.debug("Starting task execution");
                    return task.call();
                } catch (Exception e) {
                    logger.error("Task failed with exception", e);
                    throw new RuntimeException(e);
                }
            });

            // Handle completion
            future.whenComplete((result, error) -> {
                Platform.runLater(() -> {
                    logger.debug("Task completed, closing progress dialog");
                    progressStage.close();
                });
            });

            // Show the progress stage
            progressStage.show();

            // Process events while waiting (this is the key part)
            try {
                while (!future.isDone()) {
                    Thread.sleep(50);
                    // This allows the UI to remain responsive
                    Platform.runLater(() -> {});
                }

                return future.get();
            } catch (Exception e) {
                logger.error("Error waiting for task completion", e);
                progressStage.close();
                throw new RuntimeException("Task execution failed", e);
            }

        } else {
            // Not on FX thread - safer approach
            logger.debug("executeWithProgress called from background thread");

            CompletableFuture<T> future = new CompletableFuture<>();
            CountDownLatch dialogShownLatch = new CountDownLatch(1);

            // Create reference holder for the stage
            final Stage[] stageHolder = new Stage[1];

            Platform.runLater(() -> {
                try {
                    Stage progressStage = new Stage();
                    stageHolder[0] = progressStage;

                    progressStage.setTitle(title);
                    progressStage.initModality(Modality.APPLICATION_MODAL);
                    progressStage.setResizable(false);

                    VBox vbox = new VBox(15);
                    vbox.setPadding(new Insets(20));
                    vbox.setAlignment(Pos.CENTER);

                    Label headerLabel = new Label(message);
                    headerLabel.setWrapText(true);
                    headerLabel.setPrefWidth(350);

                    ProgressBar progressBar = new ProgressBar();
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    progressBar.setPrefWidth(300);

                    Label statusLabel = new Label("Please wait...");

                    vbox.getChildren().addAll(headerLabel, progressBar, statusLabel);

                    Scene scene = new Scene(vbox);
                    progressStage.setScene(scene);

                    // Prevent closing while task is running
                    progressStage.setOnCloseRequest(e -> {
                        if (!future.isDone()) {
                            e.consume();
                            statusLabel.setText("Processing... Please wait for completion.");
                        }
                    });

                    progressStage.show();
                    dialogShownLatch.countDown();

                } catch (Exception e) {
                    logger.error("Failed to create progress dialog", e);
                    dialogShownLatch.countDown();
                    future.completeExceptionally(e);
                }
            });

            // Execute task in background
            CompletableFuture.runAsync(() -> {
                try {
                    // Wait for dialog to be shown
                    dialogShownLatch.await(5, TimeUnit.SECONDS);

                    logger.debug("Executing task in background");
                    T result = task.call();
                    logger.debug("Task completed successfully");

                    Platform.runLater(() -> {
                        if (stageHolder[0] != null) {
                            stageHolder[0].close();
                        }
                        future.complete(result);
                    });

                } catch (Exception e) {
                    logger.error("Task execution failed", e);
                    Platform.runLater(() -> {
                        if (stageHolder[0] != null) {
                            stageHolder[0].close();
                        }
                        future.completeExceptionally(e);
                    });
                }
            });

            // Add timeout to prevent infinite waiting
            ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
            timeoutExecutor.schedule(() -> {
                if (!future.isDone()) {
                    logger.warn("Task timed out after 5 minutes");
                    Platform.runLater(() -> {
                        if (stageHolder[0] != null) {
                            stageHolder[0].close();
                        }
                    });
                    future.completeExceptionally(new TimeoutException("Task timed out after 5 minutes"));
                }
            }, 5, TimeUnit.MINUTES);

            try {
                T result = future.get();
                timeoutExecutor.shutdown();
                return result;
            } catch (InterruptedException e) {
                logger.error("Task interrupted", e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Task was interrupted", e);
            } catch (ExecutionException e) {
                logger.error("Task execution failed", e);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException("Task execution failed", cause);
            } finally {
                timeoutExecutor.shutdownNow();
                // Ensure dialog is closed
                Platform.runLater(() -> {
                    if (stageHolder[0] != null && stageHolder[0].isShowing()) {
                        stageHolder[0].close();
                    }
                });
            }
        }
    }
    /**
     * Shows a simple, non-blocking progress notification that auto-closes.
     * Use this for quick operations where you just want to inform the user.
     *
     * @param title The title of the notification
     * @param message The message to display
     * @param durationSeconds How long to show the notification (0 = until manually closed)
     * @return A Runnable that can be called to close the notification early
     */
    public static Runnable showProgressNotification(String title, String message, int durationSeconds) {
        logger.debug("Showing progress notification: {}", title);

        final Stage[] stageHolder = new Stage[1];

        Platform.runLater(() -> {
            Stage notificationStage = new Stage();
            stageHolder[0] = notificationStage;

            notificationStage.setTitle(title);
            notificationStage.initModality(Modality.NONE); // Non-blocking
            notificationStage.setAlwaysOnTop(true);
            notificationStage.setResizable(false);

            VBox vbox = new VBox(10);
            vbox.setPadding(new Insets(15));
            vbox.setAlignment(Pos.CENTER);
            vbox.setMinWidth(300);

            Label messageLabel = new Label(message);
            messageLabel.setWrapText(true);

            ProgressBar progressBar = new ProgressBar();
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressBar.setPrefWidth(250);

            vbox.getChildren().addAll(messageLabel, progressBar);

            Scene scene = new Scene(vbox);
            notificationStage.setScene(scene);

            // Position in corner
            notificationStage.setX(javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX() - 320);
            notificationStage.setY(javafx.stage.Screen.getPrimary().getVisualBounds().getMaxY() - 120);

            notificationStage.show();

            // Auto-close after duration if specified
            if (durationSeconds > 0) {
                PauseTransition pause = new PauseTransition(Duration.seconds(durationSeconds));
                pause.setOnFinished(e -> notificationStage.close());
                pause.play();
            }
        });

        // Return a runnable that can close the notification
        return () -> {
            Platform.runLater(() -> {
                if (stageHolder[0] != null && stageHolder[0].isShowing()) {
                    stageHolder[0].close();
                }
            });
        };
    }

}
