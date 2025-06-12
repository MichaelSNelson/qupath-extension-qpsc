package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
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
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import javafx.geometry.Insets;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
     * @param progressCounter Thread-safe counter (incremented externally as work completes).
     * @param totalFiles      The max value of progressCounter.
     * @param process         The background Process to watch for liveness.
     * @param timeoutMs       If no progress for this many ms, bar will auto-terminate.
     * @return a ProgressHandle you can .close() when you’re done (or ignore if you let it timeout).
     */

    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            Process process,
            int timeoutMs) {

        final ProgressHandle[] handleHolder = new ProgressHandle[1];

        Platform.runLater(() -> {
            logger.info("Creating progress bar UI on FX thread.");
            Stage stage = new Stage();
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(300);
            Label timeLabel = new Label("Estimating time…");
            Label progressLabel = new Label("Processed 0 of " + totalFiles);
            VBox vbox = new VBox(10, progressBar, timeLabel, progressLabel);
            vbox.setStyle("-fx-padding: 10;");

            stage.initModality(Modality.NONE);
            stage.setTitle("Microscope acquisition progress");
            stage.setScene(new Scene(vbox));
            stage.setAlwaysOnTop(true);
            stage.show();

            // Shared timing state
            AtomicLong startTime = new AtomicLong(0);
            AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());

            // Build the Timeline
            final Timeline timeline = new Timeline();
            KeyFrame keyFrame = new KeyFrame(Duration.millis(200), evt -> {
                int current = progressCounter.get();
                long now = System.currentTimeMillis();

                // Record start time once work begins
                if (current > 0 && startTime.get() == 0) {
                    startTime.set(now);
                }

                // Update fraction & labels
                double fraction = totalFiles > 0 ? current / (double) totalFiles : 0.0;
                progressBar.setProgress(fraction);
                progressLabel.setText("Processed " + current + " of " + totalFiles);

                if (startTime.get() > 0 && current > 0) {
                    long elapsed = now - startTime.get();
                    long remMs = (long) ((elapsed / (double) current) * (totalFiles - current));
                    timeLabel.setText("Remaining: " + (remMs / 1000) + " s");
                }

                // Check for stall or completion
                boolean stalled = (!process.isAlive())
                        || (current == lastUpdateTime.get() && now - lastUpdateTime.get() > timeoutMs);

                if (fraction >= 1.0 || stalled) {
                    logger.info("Progress bar closing (fraction={}, stalled={}, current={}, total={})", fraction, stalled, current, totalFiles);
                    timeline.stop();
                    stage.close();
                } else {
                    lastUpdateTime.set(now);
                }
            });
            timeline.getKeyFrames().add(keyFrame);
            timeline.setCycleCount(Timeline.INDEFINITE);

            // Store the ProgressHandle so we can return it below
            handleHolder[0] = new ProgressHandle(stage, timeline);

            logger.info("Starting progress Timeline on FX thread.");
            timeline.play();
        });

        // Wait for handle to be set before returning (avoid NPE)
        while (handleHolder[0] == null) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        logger.info("ProgressHandle returned to caller.");
        return handleHolder[0];
    }




    /**
     * Shows an error dialog on the JavaFX thread.
     */
    public static void notifyUserOfError(String message, String context) {
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
                            .filter(o -> validNames.contains(o.getPathClass().toString()))
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

    /**
     * Dialog for selecting a single tile and matching live view for stage alignment.
     */
    public static boolean stageToQuPathAlignmentGUI1() {
        QuPathGUI gui = QuPathGUI.getInstance();
        Dialog<Boolean> dlg = new Dialog<>();
        dlg.initModality(Modality.NONE);
        dlg.setTitle("Identify Location");
        dlg.setHeaderText(
                "Select exactly one tile and match the Live View in uManager.\n" +
                        "This will calibrate QuPath to stage coordinates.");

        // --- set up buttons ---
        ButtonType okType    = ButtonType.OK;
        ButtonType cancelType= ButtonType.CANCEL;
        ButtonType moveType  = new ButtonType("Move to tile", ButtonBar.ButtonData.APPLY);
        dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType, moveType);

        // Grab the JavaFX Button node for "Move to tile"
        Button moveBtn = (Button) dlg.getDialogPane().lookupButton(moveType);

        moveBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume();  // prevent dialog from closing

            // Grab the current selection
            Collection<PathObject> sel = QP.getSelectedObjects();
            long tileCount = sel.stream().filter(PathObject::isTile).count();

            if (tileCount != 1) {
                // Warn and stay open
                UIFunctions.notifyUserOfError(
                        "Please select exactly one tile before moving the stage.",
                        "Stage Move");
            } else {
                // Good: perform the move then close
                PathObject tile = sel.stream()
                        .filter(PathObject::isTile)
                        .findFirst().get();
                try {
                    MicroscopeController.getInstance().moveStageToSelectedTile(tile);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
                dlg.setResult(Boolean.TRUE);
                dlg.close();
            }
        });

        // 4) Convert the OK button to simply close & return true
        dlg.setResultConverter(btn -> btn == okType);

        // 5) Show and return whether OK was hit (or move was successful)
        return dlg.showAndWait().orElse(false);
    }

    /**
     * Confirmation dialog for current stage position accuracy.
     */
    public static boolean stageToQuPathAlignmentGUI2() {
        Dialog<Boolean> dlg = new Dialog<>();
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Position Confirmation");
        dlg.setHeaderText(
                "Is the current position accurate?\nCompare with the uManager live view.");
        ButtonType ok = new ButtonType("Current Position is Accurate", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel acquisition", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(ok, cancel);

        dlg.setResultConverter(btn -> btn == ok);
        return dlg.showAndWait().orElse(false);
    }

    /**
     * Core handler: transforms the selected tile coords, applies offset, runs Python move,
     * recenters the viewer, then optionally recalibrates the transform.
     */
    public static Map<String,Object> handleStageAlignment(
            PathObject tile, QuPathGUI gui,
            AffineTransform transform, double[] offset) throws IOException, InterruptedException {

        QP.selectObjects(tile);
        double x = tile.getROI().getCentroidX();
        double y = tile.getROI().getCentroidY();
        double [] qpCoords = new double[]{x, y};

        // Apply scaling
        double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(qpCoords, transform);
        // THEN apply offset
        stageCoords = TransformationFunctions.applyOffset(stageCoords, offset, true);



        UtilityFunctions.execCommand(
                Arrays.toString(stageCoords),
                "moveStageToCoordinates"
        );

        gui.getViewer().setCenterPixelLocation(x, y);

        boolean ok = stageToQuPathAlignmentGUI2();
        if (ok) {
            List<String> out = UtilityFunctions.execCommandAndCapture("getStageCoordinates");
            double[] currentStage =
                    MinorFunctions.convertListToPrimitiveArray(out);   // << expects List<String>

            currentStage = TransformationFunctions.applyOffset(currentStage, offset, false);
            transform = TransformationFunctions.addTranslationToScaledAffine(transform, qpCoords, currentStage, offset);
        }

        Map<String,Object> result = new HashMap<>();
        result.put("updatePosition", ok);
        result.put("transformation", transform);
        return result;
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
     * Blocks until a selection is made or cancelled.
     * Returns the selected PathObject or null if cancelled.
     */
    public static PathObject promptTileSelectionDialog(String message) {
        PathObject selectedTile = null;

        while (true) {
            // Prompt user to make the selection in QuPath (not in dialog)
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Select Tile");
            info.setHeaderText(message);
            info.setContentText("Select exactly ONE tile (Detection) in QuPath, then press OK.\n" +
                    "Press Cancel to abort.");
            ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            info.getButtonTypes().setAll(okType, cancelType);
            var result = info.showAndWait();

            if (result.isEmpty() || result.get() == cancelType) {
                return null;
            }

            // Check selection
            Collection<PathObject> selected =  QP.getSelectedObjects();
            var tile = selected.stream().filter(PathObject::isTile).toList();

            if (tile.size() != 1) {
                Alert warn = new Alert(Alert.AlertType.WARNING, "Please select exactly one tile!", ButtonType.OK);
                warn.showAndWait();
                continue; // Loop and prompt again
            }
            selectedTile = tile.get(0);
            break;
        }
        return selectedTile;
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
