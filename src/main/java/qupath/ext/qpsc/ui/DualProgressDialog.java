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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // Tile timing tracking for better estimation (uses recent actual timing, no hardcoded values)
    // Dynamic timing window size based on autofocus settings (5x n_steps for that objective)
    private final AtomicInteger timingWindowSize = new AtomicInteger(10); // Default to 10, updated from acquisition metadata
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> recentTileTimes = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicLong lastTileCompletionTime = new AtomicLong(0);
    private final AtomicInteger totalTilesCompleted = new AtomicInteger(0);

    // Enhanced timing tracking for accurate autofocus-aware estimation
    // Separates tile-only time from autofocus time using statistical detection
    private final AtomicInteger afNTiles = new AtomicInteger(5); // Number of AF positions per annotation
    private final AtomicInteger totalTilesPerAnnotation = new AtomicInteger(0); // Total tiles in current annotation
    private volatile long detectedFullAfTime = 0; // Time for full autofocus (detected from first spike)
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> allTileTimes = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicBoolean firstTileProcessed = new AtomicBoolean(false);

    // Per-annotation tracking for tile counts in future annotations
    private final List<Integer> futureTileCounts = Collections.synchronizedList(new ArrayList<>());

    // Control
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Consumer<Void> cancelCallback;

    // Stitching status tracking - shows concurrent stitching operations in this dialog
    // This allows users to see stitching progress alongside acquisition progress
    private final Map<String, String> activeStitchingOperations = new ConcurrentHashMap<>();
    private final VBox stitchingSection;
    private final Label stitchingHeader;
    private final ListView<String> stitchingListView;
    private final Label stitchingCountLabel;

    /**
     * Creates a new dual progress dialog with default timing window size.
     *
     * @param totalAnnotations Total number of annotations to be processed
     * @param showCancelButton Whether to show a cancel button
     */
    public DualProgressDialog(int totalAnnotations, boolean showCancelButton) {
        this(totalAnnotations, showCancelButton, 10); // Default timing window of 10
    }

    /**
     * Creates a new dual progress dialog with specified timing window size.
     *
     * @param totalAnnotations Total number of annotations to be processed
     * @param showCancelButton Whether to show a cancel button
     * @param timingWindowSize Number of tiles to use for rolling average time estimation
     */
    public DualProgressDialog(int totalAnnotations, boolean showCancelButton, int timingWindowSize) {
        this.totalAnnotations = totalAnnotations;
        this.timingWindowSize.set(timingWindowSize);
        
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
        timeLabel.setWrapText(true);
        statusLabel = new Label("Initializing workflow...");

        // Stitching status section - hidden until stitching starts
        stitchingHeader = new Label("Stitching Operations");
        stitchingHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        stitchingListView = new ListView<>();
        stitchingListView.setPrefHeight(80);
        stitchingListView.setStyle("-fx-font-size: 10px;");
        stitchingCountLabel = new Label("0 operations in progress");
        stitchingCountLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        stitchingSection = new VBox(5, stitchingHeader, stitchingListView, stitchingCountLabel);
        stitchingSection.setVisible(false);
        stitchingSection.setManaged(false); // Don't take space when hidden

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
            stitchingSection,  // Stitching status - hidden until active
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
        long now = System.currentTimeMillis();
        this.currentAnnotationStartTime.set(now);
        this.lastTileCompletionTime.set(now); // Initialize for tile timing tracking

        // Reset first tile flag for this annotation to detect full autofocus time
        this.firstTileProcessed.set(false);

        logger.info("Started tracking annotation '{}' with {} expected files",
                   annotationName, expectedFiles);
    }
    
    /**
     * Updates the current annotation's progress.
     *
     * @param filesCompleted Number of files completed for current annotation
     */
    public void updateCurrentAnnotationProgress(int filesCompleted) {
        int previousProgress = currentAnnotationProgress.getAndSet(filesCompleted);
        long now = System.currentTimeMillis();
        lastProgressTime.set(now);

        // Track tile completion timing for better estimation
        if (filesCompleted > previousProgress) {
            // One or more tiles completed
            long lastCompletion = lastTileCompletionTime.get();
            if (lastCompletion > 0) {
                // Calculate time since last tile and add to rolling window
                long tileTime = now - lastCompletion;
                recentTileTimes.addLast(tileTime);

                // Keep only the most recent N tiles (based on dynamic timing window)
                int windowSize = timingWindowSize.get();
                while (recentTileTimes.size() > windowSize) {
                    recentTileTimes.removeFirst();
                }

                // Also track in allTileTimes for statistical analysis
                allTileTimes.addLast(tileTime);
                // Keep a larger window for statistical detection (3x timing window)
                int maxAllTimes = windowSize * 3;
                while (allTileTimes.size() > maxAllTimes) {
                    allTileTimes.removeFirst();
                }

                // Detect full autofocus time from first tile spike
                // The first tile with tissue will have a much longer time due to full AF
                if (!firstTileProcessed.get() && filesCompleted == 1) {
                    // First tile - likely includes full autofocus
                    detectedFullAfTime = tileTime;
                    firstTileProcessed.set(true);
                    logger.info("First tile time (likely includes full AF): {} ms", tileTime);
                }
            }
            lastTileCompletionTime.set(now);
            totalTilesCompleted.addAndGet(filesCompleted - previousProgress);
        }

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
     * Updates the timing window size used for rolling average time estimation.
     * This should be set based on autofocus settings (typically 5x n_steps for the objective).
     *
     * @param newSize New timing window size (must be > 0)
     */
    public void setTimingWindowSize(int newSize) {
        if (newSize > 0) {
            int oldSize = timingWindowSize.getAndSet(newSize);
            if (oldSize != newSize) {
                logger.info("Updated timing window size from {} to {} tiles", oldSize, newSize);
            }
        } else {
            logger.warn("Attempted to set invalid timing window size: {}", newSize);
        }
    }

    /**
     * Sets the number of autofocus positions per annotation from acquisition metadata.
     * This is used to calculate expected remaining autofocus operations.
     *
     * @param afTiles Number of autofocus tile positions per annotation
     */
    public void setAfNTiles(int afTiles) {
        if (afTiles > 0) {
            int oldVal = afNTiles.getAndSet(afTiles);
            if (oldVal != afTiles) {
                logger.info("Updated AF positions per annotation from {} to {}", oldVal, afTiles);
            }
        }
    }

    /**
     * Sets the total number of tiles for the current annotation.
     * This helps calculate remaining autofocus operations accurately.
     *
     * @param totalTiles Total tile positions in current annotation
     */
    public void setTotalTilesForAnnotation(int totalTiles) {
        if (totalTiles > 0) {
            int oldVal = totalTilesPerAnnotation.getAndSet(totalTiles);
            if (oldVal != totalTiles) {
                logger.info("Updated total tiles for annotation from {} to {}", oldVal, totalTiles);
            }
        }
    }

    /**
     * Sets the expected tile counts for future annotations.
     * This enables accurate time estimation for the complete workflow.
     *
     * @param tileCounts List of tile counts for remaining annotations (excluding current)
     */
    public void setFutureTileCounts(List<Integer> tileCounts) {
        futureTileCounts.clear();
        if (tileCounts != null) {
            futureTileCounts.addAll(tileCounts);
            logger.info("Set future tile counts for {} annotations: {}",
                    tileCounts.size(), tileCounts);
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
     * Uses component-based estimation that separates tile time from autofocus time.
     */
    private void updateTimeEstimate(long now, int completed) {
        // Enable time estimation once we have any progress (even during first annotation)
        if (workflowStartTime.get() == 0 || (completed == 0 && currentAnnotationProgress.get() == 0)) {
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

        int windowSize = timingWindowSize.get();
        int tilesCollected = allTileTimes.size();

        // Need minimum tiles to perform statistical analysis
        int minTilesForEstimate = Math.min(windowSize, 5);
        if (tilesCollected < minTilesForEstimate) {
            int tilesNeeded = minTilesForEstimate - tilesCollected;
            timeLabel.setText(String.format("Collecting timing data... %d tiles remaining", tilesNeeded));
            return;
        }

        // Calculate time components using statistical separation
        TimingComponents timing = calculateTimingComponents();

        // Calculate remaining work
        int currentProgress = currentAnnotationProgress.get();
        int tilesRemainingCurrentAnnotation = Math.max(0, currentAnnotationExpectedFiles - currentProgress);

        // Calculate tiles in future annotations
        int tilesRemainingFutureAnnotations;
        if (!futureTileCounts.isEmpty()) {
            // Use actual tile counts if available
            tilesRemainingFutureAnnotations = futureTileCounts.stream().mapToInt(Integer::intValue).sum();
        } else {
            // Estimate based on current annotation
            int avgTilesPerAnnotation = totalTilesPerAnnotation.get() > 0
                    ? totalTilesPerAnnotation.get()
                    : (currentAnnotationExpectedFiles > 0 ? currentAnnotationExpectedFiles : 100);
            int remainingAnnotations = totalAnnotations - completed - 1;
            tilesRemainingFutureAnnotations = remainingAnnotations * avgTilesPerAnnotation;
        }

        int totalTilesRemaining = tilesRemainingCurrentAnnotation + tilesRemainingFutureAnnotations;

        // Calculate remaining autofocus operations
        int afPositionsPerAnnotation = afNTiles.get();

        // Remaining adaptive AF in current annotation (proportional to remaining tiles)
        int totalTilesCurrentAnnotation = totalTilesPerAnnotation.get() > 0
                ? totalTilesPerAnnotation.get()
                : currentAnnotationExpectedFiles;
        int remainingAdaptiveAfCurrent = 0;
        if (totalTilesCurrentAnnotation > 0 && afPositionsPerAnnotation > 0) {
            // Calculate how many AF positions remain based on progress
            double progressFraction = currentProgress / (double) totalTilesCurrentAnnotation;
            int completedAfPositions = (int) (progressFraction * afPositionsPerAnnotation);
            remainingAdaptiveAfCurrent = Math.max(0, afPositionsPerAnnotation - completedAfPositions - 1);
            // Subtract 1 because first AF is full autofocus, handled separately
        }

        // Remaining adaptive AF in future annotations
        int remainingAnnotations = totalAnnotations - completed - 1;
        // Each future annotation has (afPositionsPerAnnotation - 1) adaptive AF positions
        // because the first is a full autofocus
        int remainingAdaptiveAfFuture = remainingAnnotations * Math.max(0, afPositionsPerAnnotation - 1);

        int totalRemainingAdaptiveAf = remainingAdaptiveAfCurrent + remainingAdaptiveAfFuture;

        // Remaining full autofocus operations (one per remaining annotation)
        int remainingFullAf = remainingAnnotations;

        // Calculate estimated remaining time
        // Time = (remaining tiles * base tile time) + (remaining adaptive AF * adaptive AF added time)
        //      + (remaining full AF * full AF added time)
        long tileTimeMs = timing.baseTileTimeMs * totalTilesRemaining;
        long adaptiveAfTimeMs = timing.adaptiveAfAddedTimeMs * totalRemainingAdaptiveAf;
        long fullAfTimeMs = timing.fullAfAddedTimeMs * remainingFullAf;

        long totalRemainingMs = tileTimeMs + adaptiveAfTimeMs + fullAfTimeMs;
        long remainingSeconds = totalRemainingMs / 1000;

        // Build informative display
        String estimate = formatTime(remainingSeconds);
        if (logger.isDebugEnabled()) {
            logger.debug("Time estimate breakdown: {} tiles @ {}ms = {}ms, {} adaptive AF @ {}ms = {}ms, " +
                            "{} full AF @ {}ms = {}ms, total = {}s",
                    totalTilesRemaining, timing.baseTileTimeMs, tileTimeMs,
                    totalRemainingAdaptiveAf, timing.adaptiveAfAddedTimeMs, adaptiveAfTimeMs,
                    remainingFullAf, timing.fullAfAddedTimeMs, fullAfTimeMs,
                    remainingSeconds);
        }

        timeLabel.setText(String.format("Time remaining: %s (%d tiles, %d AF ops)",
                estimate, totalTilesRemaining, totalRemainingAdaptiveAf + remainingFullAf));
    }

    /**
     * Calculates timing components by statistically separating tile-only times from autofocus times.
     *
     * The approach:
     * 1. Use the lower quartile (25th percentile) of tile times as base tile time
     *    (most tiles don't have autofocus, so lower quartile captures typical tile-only time)
     * 2. Identify tiles with autofocus as those significantly above the median
     * 3. Calculate adaptive AF added time from the difference between AF tiles and base time
     * 4. Use detected first tile time for full AF estimate
     */
    private TimingComponents calculateTimingComponents() {
        // Copy times to a list for sorting
        List<Long> sortedTimes = new ArrayList<>(allTileTimes);
        Collections.sort(sortedTimes);

        int n = sortedTimes.size();

        // Base tile time: use 25th percentile (lower quartile)
        // This captures tiles without autofocus
        int q1Index = Math.max(0, n / 4);
        long baseTileTime = sortedTimes.get(q1Index);

        // Median for threshold detection
        long median = sortedTimes.get(n / 2);

        // Threshold for detecting autofocus tiles: 2x the base time or median + 50%, whichever is larger
        long afThreshold = Math.max(baseTileTime * 2, median + median / 2);

        // Calculate adaptive AF added time from tiles above threshold (excluding the max which may be full AF)
        long adaptiveAfAddedTime = 0;
        int adaptiveAfCount = 0;

        for (int i = 0; i < n - 1; i++) { // Exclude max (likely full AF)
            long time = sortedTimes.get(i);
            if (time > afThreshold) {
                adaptiveAfAddedTime += (time - baseTileTime);
                adaptiveAfCount++;
            }
        }

        if (adaptiveAfCount > 0) {
            adaptiveAfAddedTime /= adaptiveAfCount; // Average
        } else {
            // No adaptive AF detected yet - estimate based on difference between median and base
            adaptiveAfAddedTime = Math.max(0, median - baseTileTime) * 2;
        }

        // Full AF time: use detected first tile time or max observed time
        long fullAfAddedTime;
        if (detectedFullAfTime > 0) {
            fullAfAddedTime = Math.max(0, detectedFullAfTime - baseTileTime);
        } else {
            // Fallback: use max time minus base
            long maxTime = sortedTimes.get(n - 1);
            fullAfAddedTime = Math.max(0, maxTime - baseTileTime);
        }

        // Ensure reasonable minimums
        baseTileTime = Math.max(baseTileTime, 500); // At least 0.5s per tile
        adaptiveAfAddedTime = Math.max(adaptiveAfAddedTime, 1000); // At least 1s added for adaptive AF
        fullAfAddedTime = Math.max(fullAfAddedTime, adaptiveAfAddedTime * 2); // Full AF at least 2x adaptive

        return new TimingComponents(baseTileTime, adaptiveAfAddedTime, fullAfAddedTime);
    }

    /**
     * Holds the separated timing components for estimation.
     */
    private static class TimingComponents {
        final long baseTileTimeMs;        // Time for tile acquisition only (no autofocus)
        final long adaptiveAfAddedTimeMs; // Additional time when adaptive autofocus runs
        final long fullAfAddedTimeMs;     // Additional time for full autofocus (first tile per annotation)

        TimingComponents(long baseTileTimeMs, long adaptiveAfAddedTimeMs, long fullAfAddedTimeMs) {
            this.baseTileTimeMs = baseTileTimeMs;
            this.adaptiveAfAddedTimeMs = adaptiveAfAddedTimeMs;
            this.fullAfAddedTimeMs = fullAfAddedTimeMs;
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

    // ==================== Stitching Status Methods ====================

    /**
     * Returns the underlying Stage for this dialog.
     * This can be used for window ownership and z-order management.
     *
     * @return The JavaFX Stage
     */
    public Stage getStage() {
        return stage;
    }

    /**
     * Registers a new stitching operation and shows it in the stitching section.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId Unique identifier for this stitching operation
     * @param displayName Display name for this operation (e.g., "Sample - Tissue_1")
     */
    public void registerStitchingOperation(String operationId, String displayName) {
        activeStitchingOperations.put(operationId, displayName + ": Initializing...");
        updateStitchingDisplay();
        logger.info("Registered stitching operation in progress dialog: {}", operationId);
    }

    /**
     * Updates the status message for a specific stitching operation.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the operation to update
     * @param status The new status message (e.g., "Processing angle 45...")
     */
    public void updateStitchingStatus(String operationId, String status) {
        if (activeStitchingOperations.containsKey(operationId)) {
            // Extract display name (part before the colon) and append new status
            String existing = activeStitchingOperations.get(operationId);
            String displayName = existing.contains(":") ? existing.substring(0, existing.indexOf(":")) : operationId;
            activeStitchingOperations.put(operationId, displayName + ": " + status);
            updateStitchingDisplay();
        }
    }

    /**
     * Marks a stitching operation as complete and removes it from the display.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the completed operation
     */
    public void completeStitchingOperation(String operationId) {
        if (activeStitchingOperations.remove(operationId) != null) {
            updateStitchingDisplay();
            logger.info("Completed stitching operation in progress dialog: {}", operationId);
        }
    }

    /**
     * Marks a stitching operation as failed.
     * This method is thread-safe and can be called from any thread.
     *
     * @param operationId The ID of the failed operation
     * @param errorMessage Brief error description
     */
    public void failStitchingOperation(String operationId, String errorMessage) {
        if (activeStitchingOperations.containsKey(operationId)) {
            String existing = activeStitchingOperations.get(operationId);
            String displayName = existing.contains(":") ? existing.substring(0, existing.indexOf(":")) : operationId;
            activeStitchingOperations.put(operationId, displayName + ": FAILED - " + errorMessage);
            updateStitchingDisplay();
            logger.error("Stitching operation failed: {} - {}", operationId, errorMessage);

            // Remove after 5 seconds so the error is visible but doesn't persist forever
            Platform.runLater(() -> {
                PauseTransition pause = new PauseTransition(Duration.seconds(5));
                pause.setOnFinished(e -> {
                    activeStitchingOperations.remove(operationId);
                    updateStitchingDisplay();
                });
                pause.play();
            });
        }
    }

    /**
     * Returns true if there are any active stitching operations.
     *
     * @return true if stitching is in progress
     */
    public boolean hasActiveStitchingOperations() {
        return !activeStitchingOperations.isEmpty();
    }

    /**
     * Updates the stitching section display with current operations.
     * Called internally when operations change.
     */
    private void updateStitchingDisplay() {
        Platform.runLater(() -> {
            int count = activeStitchingOperations.size();
            boolean hasOps = count > 0;

            // Show/hide section based on whether there are active operations
            stitchingSection.setVisible(hasOps);
            stitchingSection.setManaged(hasOps);

            if (hasOps) {
                // Update list view
                stitchingListView.getItems().clear();
                activeStitchingOperations.values().forEach(status ->
                    stitchingListView.getItems().add("* " + status));

                // Update count label
                if (count == 1) {
                    stitchingCountLabel.setText("1 operation in progress");
                } else {
                    stitchingCountLabel.setText(count + " operations in progress");
                }

                // Resize window to accommodate stitching section
                stage.sizeToScene();
            }
        });
    }
}