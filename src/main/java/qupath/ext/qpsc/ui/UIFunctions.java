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



    /**
     * Helper to add a label-control pair to a GridPane row.
     */
    public static void addToGrid(GridPane pane, Node label, Node control, int rowIndex) {
        pane.add(label, 0, rowIndex);
        pane.add(control, 1, rowIndex);
    }

    /**
     * Helper to add a single node spanning two columns in a GridPane.
     */
    public static void addToGrid(GridPane pane, Node node, int rowIndex) {
        pane.add(node, 0, rowIndex, 2, 1);
    }


    public static class ProgressHandle {
        private final Stage stage;
        private final Timeline timeline;

        private ProgressHandle(Stage stage, Timeline timeline) {
            this.stage   = stage;
            this.timeline = timeline;
        }

        /** Stops updates and closes the progress bar window. */
        public void close() {
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
    /**
     * Shows a progress bar window that watches an AtomicInteger and updates every 200 ms.
     *
     * @param progressCounter thread safe counter (increment yourself as work completes)
     * @param totalFiles      max value of progressCounter (for a fraction)
     * @param process         the external Process we can watch for isAlive()
     * @param timeoutMs       if no progress for this many ms, the bar auto closes
     * @return a ProgressHandle; just call handle.close() when you’re done (or ignore it if it auto closes)
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            Process process,
            int timeoutMs) {

        // 1) Prepare the Stage + controls (on FX thread)
        Stage stage = new Stage();
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        Label timeLabel     = new Label("Estimating time…");
        Label progressLabel = new Label("Processed 0 of " + totalFiles);
        VBox vbox = new VBox(10, progressBar, timeLabel, progressLabel);
        vbox.setStyle("-fx-padding: 10;");

        Platform.runLater(() -> {
            stage.initModality(Modality.NONE);
            stage.setTitle("Microscope acquisition progress");
            stage.setScene(new Scene(vbox));
            stage.setAlwaysOnTop(true);
            stage.show();
        });

        // 2) Shared timing state
        AtomicLong startTime      = new AtomicLong(0);
        AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());

        // 3) Build the Timeline in two steps so we can reference it in the lambda
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
                long remMs   = (long) ((elapsed / (double) current) * (totalFiles - current));
                timeLabel.setText("Remaining: " + (remMs / 1000) + " s");
            }

            // Check for stall or completion
            boolean stalled = (!process.isAlive())
                    || (current == lastUpdateTime.get() && now - lastUpdateTime.get() > timeoutMs);

            if (fraction >= 1.0 || stalled) {
                timeline.stop();
                stage.close();
            } else {
                lastUpdateTime.set(now);
            }
        });
        timeline.getKeyFrames().add(keyFrame);
        timeline.setCycleCount(Timeline.INDEFINITE);

        // 4) Start it on the FX thread
        Platform.runLater(timeline::play);

        // 5) Return the handle so caller can close early if desired
        return new ProgressHandle(stage, timeline);
    }

//    /**
//     * Displays and updates a progress bar based on a Python process's stdout progress.
//     * @param progressCounter AtomicInteger tracking completed file count.
//     * @param totalFiles total number of files expected.
//     * @param pythonProcess external Process to monitor.
//     * @param timeout milliseconds of stalled output before abort.
//     */
//    public static void showProgressBar(AtomicInteger progressCounter, int totalFiles,
//                                       Process pythonProcess, int timeout) {
//        Platform.runLater(() -> {
//            AtomicLong startTime = new AtomicLong();
//            AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
//            AtomicInteger lastProgress = new AtomicInteger(0);
//
//            progressBarStage = new Stage();
//            progressBarStage.initModality(Modality.NONE);
//            progressBarStage.setTitle("Microscope acquisition progress");
//
//            VBox vbox = new VBox(10);
//            ProgressBar progressBar = new ProgressBar(0);
//            progressBar.setPrefWidth(300);
//            Label timeLabel = new Label("Estimating time...");
//            Label progressLabel = new Label("Processing files...");
//            vbox.getChildren().addAll(progressBar, timeLabel, progressLabel);
//
//            progressBarStage.setScene(new Scene(vbox));
//            progressBarStage.setAlwaysOnTop(true);
//            progressBarStage.show();
//
//            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
//            executor.scheduleAtFixedRate(() -> {
//                int current = progressCounter.get();
//                long now = System.currentTimeMillis();
//                if (current > 0) {
//                    if (startTime.get() == 0) {
//                        startTime.set(now);
//                    }
//                    double fraction = current / (double) totalFiles;
//                    long elapsed = now - startTime.get();
//                    long sinceLast = now - lastUpdateTime.get();
//                    double perUnit = elapsed / (double) current;
//                    int estTotal = (int) (perUnit * totalFiles);
//                    int remainingSec = (estTotal - (int) elapsed) / 1000;
//
//                    Platform.runLater(() -> {
//                        progressBar.setProgress(fraction);
//                        timeLabel.setText("Rough estimate of remaining time: " + remainingSec + " seconds");
//                        progressLabel.setText(String.format("Processed %d out of %d files...", current, totalFiles));
//                    });
//
//                    if (!pythonProcess.isAlive() || sinceLast > timeout) {
//                        executor.shutdownNow();
//                        Platform.runLater(() -> {
//                            progressLabel.setText("Process stalled and was terminated.");
//                            notifyUserOfError(
//                                    "Timeout reached when waiting for images from microscope. Acquisition halted.",
//                                    "Acquisition process");
//                            new Thread(() -> {
//                                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
//                                Platform.runLater(progressBarStage::close);
//                            }).start();
//                        });
//                    } else if (fraction >= 1.0) {
//                        executor.shutdownNow();
//                        Platform.runLater(progressBarStage::close);
//                    } else if (current > lastProgress.get()) {
//                        lastProgress.set(current);
//                        lastUpdateTime.set(now);
//                    }
//                }
//            }, 200, 200, TimeUnit.MILLISECONDS);
//        });
//    }

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
     * Holds the user’s choices from the “sample setup” dialog.
     */
    public record SampleSetupResult(String sampleName, File projectsFolder, String modality) { }

    /**
     * Show a dialog to collect:
     *  - Sample name (text)
     *  - Projects folder (directory chooser, default from prefs)
     *  - Modality (combo box, keys from microscope YAML imagingMode section)
     *
     * @return a CompletableFuture that completes with the user’s entries,
     *         or is cancelled if the user hits “Cancel.”
     */
    public static CompletableFuture<SampleSetupResult> showSampleSetupDialog() {
        CompletableFuture<SampleSetupResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            // 1) Build the dialog
            Dialog<SampleSetupResult> dlg = new Dialog<>();
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.setTitle("New Sample Setup");
            dlg.setHeaderText("Enter new sample details before acquisition");

            // Buttons
            ButtonType okType     = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dlg.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            // 2) Build fields
            TextField sampleNameField = new TextField();
            sampleNameField.setPromptText("e.g. MySample01");

            // Projects folder field + browse
            TextField folderField = new TextField();
            folderField.setPrefColumnCount(30);
            folderField.setText(QPPreferenceDialog.getProjectsFolderProperty());
            Button browseBtn = new Button("Browse…");
            browseBtn.setOnAction(e -> {
                Window win = dlg.getDialogPane().getScene().getWindow();
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("Select Projects Folder");
                chooser.setInitialDirectory(new File(folderField.getText()));
                File chosen = chooser.showDialog(win);
                if (chosen != null) folderField.setText(chosen.getAbsolutePath());
            });
            HBox folderBox = new HBox(5, folderField, browseBtn);

            // Modality combo
            // Load keys from YAML: imagingMode section
            Set<String> modalities = MicroscopeConfigManager
                    .getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())    // ensure you’ve already called getInstance(path)
                    .getSection("imagingMode")
                    .keySet();
            ComboBox<String> modalityBox = new ComboBox<>(
                    FXCollections.observableArrayList(modalities)
            );
            modalityBox.setValue(modalities.iterator().next());

            // 3) Layout in a grid
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));
            grid.add(new Label("Sample name:"),          0, 0);
            grid.add(sampleNameField,                     1, 0);
            grid.add(new Label("Projects folder:"),       0, 1);
            grid.add(folderBox,                           1, 1);
            grid.add(new Label("Modality:"),              0, 2);
            grid.add(modalityBox,                         1, 2);

            dlg.getDialogPane().setContent(grid);

            // 4) Convert result on OK
            dlg.setResultConverter(button -> {
                if (button == okType) {
                    String name = sampleNameField.getText().trim();
                    File  folder = new File(folderField.getText().trim());
                    String mod  = modalityBox.getValue();
                    if (name.isEmpty() || !folder.isDirectory() || mod == null) {
                        new Alert(Alert.AlertType.ERROR,
                                "Please enter a name, valid folder, and select a modality.")
                                .showAndWait();
                        return null; // keep dialog open
                    }
                    return new SampleSetupResult(name, folder, mod);
                }
                return null; // on Cancel or close
            });

            // 5) Show and handle
            Optional<SampleSetupResult> res = dlg.showAndWait();
            if (res.isPresent()) {
                future.complete(res.get());
            } else {
                future.cancel(true);
            }
        });

        return future;
    }
}
