package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Dual progress dialog that shows both total workflow progress and current annotation progress.
 * 
 * This dialog provides:
 * - Overall progress across all annotations in the acquisition workflow
 * - Current annotation progress (tiles acquired)
 * - Total time estimation for the complete workflow
 * - Cancel support that applies to the entire workflow
 * 
 * @author Mike Nelson
 */
public class DualProgressDialog {
    private static final Logger logger = LoggerFactory.getLogger(DualProgressDialog.class);
    
    private final Stage stage;
    private final ProgressBar totalProgressBar;
    private final ProgressBar currentProgressBar;
    private final Label totalProgressLabel;
    private final Label currentProgressLabel;
    private final Label timeLabel;
    private final Label statusLabel;
    private final Button cancelButton;
    private final Timeline timeline;
    
    // Overall workflow tracking
    private final int totalAnnotations;
    private final AtomicInteger completedAnnotations = new AtomicInteger(0);
    private final AtomicLong workflowStartTime = new AtomicLong(0);
    private final AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
    
    // Current annotation tracking
    private volatile String currentAnnotationName = "";
    private volatile int currentAnnotationExpectedFiles = 0;
    private final AtomicInteger currentAnnotationProgress = new AtomicInteger(0);
    private final AtomicLong currentAnnotationStartTime = new AtomicLong(0);
    
    // Control
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Consumer<Void> cancelCallback;
    
    /**
     * Creates a new dual progress dialog.
     * 
     * @param totalAnnotations Total number of annotations to be processed
     * @param showCancelButton Whether to show a cancel button
     */
    public DualProgressDialog(int totalAnnotations, boolean showCancelButton) {
        this.totalAnnotations = totalAnnotations;
        
        // Create UI components
        stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Acquisition Workflow Progress");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        
        // Total progress components
        totalProgressBar = new ProgressBar(0);
        totalProgressBar.setPrefWidth(350);
        totalProgressLabel = new Label("Overall Progress: 0 of " + totalAnnotations + " annotations complete");
        
        // Current annotation progress components  
        currentProgressBar = new ProgressBar(0);
        currentProgressBar.setPrefWidth(350);
        currentProgressLabel = new Label("Current Annotation: Waiting to start...");
        
        // Time and status labels
        timeLabel = new Label("Estimating total time...");
        statusLabel = new Label("Initializing workflow...");
        
        // Layout
        VBox vbox = new VBox(8);
        vbox.setStyle("-fx-padding: 15;");
        vbox.setAlignment(Pos.CENTER);
        
        // Add section headers and progress bars
        Label totalHeader = new Label("Total Workflow Progress");
        totalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        
        Label currentHeader = new Label("Current Annotation Progress");
        currentHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        
        vbox.getChildren().addAll(
            totalHeader,
            totalProgressBar,
            totalProgressLabel,
            new Separator(),
            currentHeader, 
            currentProgressBar,
            currentProgressLabel,
            new Separator(),
            timeLabel,
            statusLabel
        );
        
        // Add cancel button if requested
        if (showCancelButton) {
            cancelButton = new Button("Cancel Workflow");
            cancelButton.setPrefWidth(150);
            cancelButton.setOnAction(e -> {
                logger.info("Workflow cancel button clicked");
                cancelButton.setDisable(true);
                cancelButton.setText("Cancelling...");
                triggerCancel();
            });
            vbox.getChildren().addAll(new Separator(), cancelButton);
        } else {
            cancelButton = null;
        }
        
        stage.setScene(new Scene(vbox));
        
        // Create update timeline
        timeline = new Timeline();
        KeyFrame keyFrame = new KeyFrame(Duration.millis(500), e -> updateDisplay());
        timeline.getKeyFrames().add(keyFrame);
        timeline.setCycleCount(Timeline.INDEFINITE);
        
        // Set up window close handling
        stage.setOnCloseRequest(e -> {
            if (!isWorkflowComplete()) {
                e.consume(); // Prevent closing during active workflow
            }
        });
        
        logger.info("Created dual progress dialog for {} annotations", totalAnnotations);
    }
    
    /**
     * Shows the dialog and starts the update timeline.
     */
    public void show() {
        Platform.runLater(() -> {
            workflowStartTime.set(System.currentTimeMillis());
            lastProgressTime.set(System.currentTimeMillis());
            stage.show();
            timeline.play();
            logger.info("Dual progress dialog shown and timeline started");
        });
    }
    
    /**
     * Starts tracking a new annotation acquisition.
     * 
     * @param annotationName Name of the annotation being acquired
     * @param expectedFiles Number of files expected for this annotation
     */
    public void startAnnotation(String annotationName, int expectedFiles) {
        this.currentAnnotationName = annotationName;
        this.currentAnnotationExpectedFiles = expectedFiles;
        this.currentAnnotationProgress.set(0);
        this.currentAnnotationStartTime.set(System.currentTimeMillis());
        
        logger.info("Started tracking annotation '{}' with {} expected files", 
                   annotationName, expectedFiles);
    }
    
    /**
     * Updates the current annotation's progress.
     * 
     * @param filesCompleted Number of files completed for current annotation
     */
    public void updateCurrentAnnotationProgress(int filesCompleted) {
        currentAnnotationProgress.set(filesCompleted);
        lastProgressTime.set(System.currentTimeMillis());
        
        if (filesCompleted > 0 && filesCompleted % 10 == 0) {
            logger.debug("Current annotation progress: {}/{} files", 
                        filesCompleted, currentAnnotationExpectedFiles);
        }
    }
    
    /**
     * Marks the current annotation as complete and advances overall progress.
     */
    public void completeCurrentAnnotation() {
        int completed = completedAnnotations.incrementAndGet();
        currentAnnotationProgress.set(currentAnnotationExpectedFiles);
        
        logger.info("Completed annotation '{}' - {}/{} annotations done", 
                   currentAnnotationName, completed, totalAnnotations);
        
        if (completed >= totalAnnotations) {
            logger.info("All annotations completed - workflow finished");
            Platform.runLater(this::showCompletionAndClose);
        }
    }
    
    /**
     * Updates the display with current progress and time estimates.
     */
    private void updateDisplay() {
        if (cancelled.get()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int completed = completedAnnotations.get();
        int currentFiles = currentAnnotationProgress.get();
        
        // Update total progress
        double totalFraction = totalAnnotations > 0 ? completed / (double) totalAnnotations : 0.0;
        totalProgressBar.setProgress(totalFraction);
        totalProgressLabel.setText("Overall Progress: " + completed + " of " + totalAnnotations + " annotations complete");
        
        // Update current annotation progress
        double currentFraction = currentAnnotationExpectedFiles > 0 ? 
            currentFiles / (double) currentAnnotationExpectedFiles : 0.0;
        currentProgressBar.setProgress(currentFraction);
        
        if (currentAnnotationName.isEmpty()) {
            currentProgressLabel.setText("Current Annotation: Waiting to start...");
        } else {
            currentProgressLabel.setText("Current Annotation: " + currentAnnotationName + 
                " (" + currentFiles + "/" + currentAnnotationExpectedFiles + " tiles)");
        }
        
        // Update status
        if (completed == 0 && currentFiles == 0) {
            statusLabel.setText("Initializing workflow...");
        } else if (completed < totalAnnotations) {
            statusLabel.setText("Acquiring data...");
        } else {
            statusLabel.setText("Workflow complete!");
        }
        
        // Calculate and display time estimates
        updateTimeEstimate(now, completed);
    }
    
    /**
     * Updates time estimation display for the complete workflow.
     */
    private void updateTimeEstimate(long now, int completed) {
        if (workflowStartTime.get() == 0 || completed == 0) {
            timeLabel.setText("Estimating total time...");
            return;
        }
        
        if (completed >= totalAnnotations) {
            // Workflow complete - show total time
            long totalTime = now - workflowStartTime.get();
            long totalSeconds = totalTime / 1000;
            timeLabel.setText("Total time: " + formatTime(totalSeconds));
            return;
        }
        
        // Calculate remaining time for complete workflow
        long elapsed = now - workflowStartTime.get();
        
        // Estimate based on completed annotations plus current annotation progress
        double effectiveCompleted = completed;
        if (currentAnnotationExpectedFiles > 0) {
            double currentProgress = currentAnnotationProgress.get() / (double) currentAnnotationExpectedFiles;
            effectiveCompleted += currentProgress;
        }
        
        if (effectiveCompleted > 0) {
            long remainingMs = (long) ((elapsed / effectiveCompleted) * (totalAnnotations - effectiveCompleted));
            long remainingSeconds = remainingMs / 1000;
            timeLabel.setText("Complete workflow time remaining: " + formatTime(remainingSeconds));
        } else {
            timeLabel.setText("Estimating total time...");
        }
    }
    
    /**
     * Formats time in seconds to a human-readable string.
     */
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return String.format("%d min %d sec", minutes, secs);
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return String.format("%d hr %d min", hours, minutes);
        }
    }
    
    /**
     * Shows completion message and closes dialog after a delay.
     */
    private void showCompletionAndClose() {
        statusLabel.setText("All acquisitions completed successfully!");
        statusLabel.setTextFill(Color.GREEN);
        
        if (cancelButton != null) {
            cancelButton.setVisible(false);
        }
        
        PauseTransition pause = new PauseTransition(Duration.seconds(3));
        pause.setOnFinished(e -> close());
        pause.play();
    }
    
    /**
     * Closes the dialog and stops the timeline.
     */
    public void close() {
        Platform.runLater(() -> {
            timeline.stop();
            stage.close();
            logger.info("Dual progress dialog closed");
        });
    }
    
    /**
     * Sets a callback to be called when cancel is requested.
     */
    public void setCancelCallback(Consumer<Void> callback) {
        this.cancelCallback = callback;
    }
    
    /**
     * Returns true if the user has cancelled the workflow.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
    
    /**
     * Returns true if the workflow is complete.
     */
    public boolean isWorkflowComplete() {
        return completedAnnotations.get() >= totalAnnotations;
    }
    
    /**
     * Triggers cancellation and calls the cancel callback.
     */
    private void triggerCancel() {
        cancelled.set(true);
        statusLabel.setText("Cancelling workflow...");
        statusLabel.setTextFill(Color.RED);
        
        if (cancelCallback != null) {
            cancelCallback.accept(null);
        }
        
        // Close after showing cancel status
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(e -> close());
        pause.play();
    }
    
    /**
     * Shows an error state and allows closing.
     */
    public void showError(String message) {
        Platform.runLater(() -> {
            statusLabel.setText("Error: " + message);
            statusLabel.setTextFill(Color.RED);
            
            if (cancelButton != null) {
                cancelButton.setText("Close");
                cancelButton.setDisable(false);
                cancelButton.setOnAction(e -> close());
            }
            
            // Allow window to be closed
            stage.setOnCloseRequest(e -> close());
        });
    }
}