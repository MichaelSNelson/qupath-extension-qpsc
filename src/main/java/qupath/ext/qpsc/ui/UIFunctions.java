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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.scene.control.Separator;

import java.util.concurrent.CountDownLatch;
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
    }
    /**
     * Show a progress bar that watches an AtomicInteger and updates itself periodically.
     * The progress bar will stay open until either:
     * - All expected files are found (progress reaches 100%)
     * - The timeout is reached with no progress
     * - It is explicitly closed via the returned handle
     *
     * @param progressCounter Thread-safe counter (incremented externally as work completes).
     * @param totalFiles      The max value of progressCounter.
     * @param process         The background Process to watch for liveness (can be null).
     * @param timeoutMs       If no progress for this many ms, bar will auto-terminate.
     * @return a ProgressHandle you can .close() when you're done.
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            Process process,
            int timeoutMs) {

        final ProgressHandle[] handleHolder = new ProgressHandle[1];

        Platform.runLater(() -> {
            logger.info("Creating progress bar UI on FX thread for {} total files", totalFiles);
            Stage stage = new Stage();
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(300);
            Label timeLabel = new Label("Estimating time…");
            Label progressLabel = new Label("Processed 0 of " + totalFiles);
            Label statusLabel = new Label("Monitoring file creation...");

            VBox vbox = new VBox(10, progressBar, progressLabel, timeLabel, statusLabel);
            vbox.setStyle("-fx-padding: 10;");

            stage.initModality(Modality.NONE);
            stage.setTitle("Microscope acquisition progress");
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
                progressLabel.setText("Processed " + current + " of " + totalFiles);

                // Update status based on process state
                if (process != null && !process.isAlive()) {
                    statusLabel.setText("Acquisition complete, waiting for files...");
                }

                // Calculate time estimate
                if (startTime.get() > 0 && current > 0 && current < totalFiles) {
                    long elapsed = now - startTime.get();
                    long remMs = (long) ((elapsed / (double) current) * (totalFiles - current));
                    timeLabel.setText("Remaining: " + (remMs / 1000) + " s");
                } else if (current >= totalFiles) {
                    timeLabel.setText("Complete!");
                    statusLabel.setText("All files detected");
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
                        statusLabel.setText("Timeout - no new files detected");
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

            // Store the ProgressHandle
            handleHolder[0] = new ProgressHandle(stage, timeline);

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
     * Shows an error dialog on the JavaFX thread.
     */
    public static void notifyUserOfError(String message, String context) {
        logger.error("Error during {}: {}", context, message);
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Error during " + context);
            alert.setContentText(message);
            alert.initModality(Modality.APPLICATION_MODAL);
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

        AtomicBoolean result = new AtomicBoolean(false);

        Button confirmButton = new Button("Current Position is Accurate");
        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> {
            result.set(true);
            stage.close();
        });

        Button cancelButton = new Button("Cancel acquisition");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            result.set(false);
            stage.close();
        });

        buttonBox.getChildren().addAll(confirmButton, cancelButton);
        layout.getChildren().addAll(headerLabel, instructionLabel, new Separator(), buttonBox);

        Scene scene = new Scene(layout, 350, 150);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> result.set(false));

        stage.centerOnScreen();
        stage.showAndWait();

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
}
