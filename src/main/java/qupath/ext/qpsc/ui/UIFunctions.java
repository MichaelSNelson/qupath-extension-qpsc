package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Utility class providing various UI functions for the QP Scope extension.
 * Includes methods to display dialogs, progress bars, and prompt the user for input.
 */
public class UIFunctions {
    private static final Logger logger = LoggerFactory.getLogger(UIFunctions.class);
    private static Stage progressBarStage;



//TODO DELETE, for testing only
    /**
     * Ask the user for a pair of stage coordinates (comma-separated).
     *
     * @return the string the user typed, or {@code null} if they cancelled.
     */
    public static String askForCoordinates() {
        TextInputDialog dlg = new TextInputDialog("0.0, 0.0");
        dlg.setTitle("Enter Coordinates");
        dlg.setHeaderText("Provide stage coordinates (X , Y)");
        dlg.setContentText("Coordinates (µm):");

        Optional<String> txt = dlg.showAndWait();
        return txt.orElse(null);
    }

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

    /**
     * Displays and updates a progress bar based on a Python process's stdout progress.
     * @param progressCounter AtomicInteger tracking completed file count.
     * @param totalFiles total number of files expected.
     * @param pythonProcess external Process to monitor.
     * @param timeout milliseconds of stalled output before abort.
     */
    public static void showProgressBar(AtomicInteger progressCounter, int totalFiles,
                                       Process pythonProcess, int timeout) {
        Platform.runLater(() -> {
            AtomicLong startTime = new AtomicLong();
            AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
            AtomicInteger lastProgress = new AtomicInteger(0);

            progressBarStage = new Stage();
            progressBarStage.initModality(Modality.NONE);
            progressBarStage.setTitle("Microscope acquisition progress");

            VBox vbox = new VBox(10);
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(300);
            Label timeLabel = new Label("Estimating time...");
            Label progressLabel = new Label("Processing files...");
            vbox.getChildren().addAll(progressBar, timeLabel, progressLabel);

            progressBarStage.setScene(new Scene(vbox));
            progressBarStage.setAlwaysOnTop(true);
            progressBarStage.show();

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> {
                int current = progressCounter.get();
                long now = System.currentTimeMillis();
                if (current > 0) {
                    if (startTime.get() == 0) {
                        startTime.set(now);
                    }
                    double fraction = current / (double) totalFiles;
                    long elapsed = now - startTime.get();
                    long sinceLast = now - lastUpdateTime.get();
                    double perUnit = elapsed / (double) current;
                    int estTotal = (int) (perUnit * totalFiles);
                    int remainingSec = (estTotal - (int) elapsed) / 1000;

                    Platform.runLater(() -> {
                        progressBar.setProgress(fraction);
                        timeLabel.setText("Rough estimate of remaining time: " + remainingSec + " seconds");
                        progressLabel.setText(String.format("Processed %d out of %d files...", current, totalFiles));
                    });

                    if (!pythonProcess.isAlive() || sinceLast > timeout) {
                        executor.shutdownNow();
                        Platform.runLater(() -> {
                            progressLabel.setText("Process stalled and was terminated.");
                            notifyUserOfError(
                                    "Timeout reached when waiting for images from microscope. Acquisition halted.",
                                    "Acquisition process");
                            new Thread(() -> {
                                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                                Platform.runLater(progressBarStage::close);
                            }).start();
                        });
                    } else if (fraction >= 1.0) {
                        executor.shutdownNow();
                        Platform.runLater(progressBarStage::close);
                    } else if (current > lastProgress.get()) {
                        lastProgress.set(current);
                        lastUpdateTime.set(now);
                    }
                }
            }, 200, 200, TimeUnit.MILLISECONDS);
        });
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

        // --- bind disable: only enable when exactly 1 tile is selected ---
        ObservableList<PathObject> selection = (ObservableList<PathObject>) QP.getSelectedObjects();
        moveBtn.disableProperty().bind(Bindings.createBooleanBinding(() ->
                        // count how many of the selected objects are actual tiles
                        selection.stream().filter(PathObject::isTile).count() != 1,
                selection
        ));

        // --- on "Move to tile" click, actually send the command ---
        moveBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume();
            PathObject tile = selection.stream()
                    .filter(PathObject::isTile)
                    .findFirst()
                    .get();   // safe: we know count == 1
            MicroscopeController.getInstance().moveStageToSelectedTile(tile);
        });

        // --- OK / Cancel logic ---
        dlg.setResultConverter(btn -> {
            if (btn == okType) {
                long tileCount = selection.stream().filter(PathObject::isTile).count();
                if (tileCount != 1) {
                    UIFunctions.showAlertDialog("Please select exactly one tile before continuing.");
                    return false;       // stays open
                }
                return true;            // close and return true
            }
            return false;               // Cancel or other => false
        });

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
            String virtualEnvPath, String pythonScriptPath,
            AffineTransform transform, double[] offset) throws IOException, InterruptedException {

        QP.selectObjects(tile);
        double x = tile.getROI().getCentroidX();
        double y = tile.getROI().getCentroidY();
        double [] qpCoords = new double[]{x, y};

        // Apply scaling
        double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(qpCoords, transform);
        // THEN apply offset
        stageCoords = TransformationFunctions.applyOffset(stageCoords, offset, true);

        // Build the Python arguments by indexing into the array
        List<String> pythonArgs = List.of(
                Double.toString(stageCoords[0]),
                Double.toString(stageCoords[1])
        );

        UtilityFunctions.execCommand(
                pythonArgs,
                "moveStageToCoordinates"
        );

        gui.getViewer().setCenterPixelLocation(x, y);

        boolean ok = stageToQuPathAlignmentGUI2();
        if (ok) {
            int resp = UtilityFunctions.execCommand( null,
                    "getStageCoordinates");

            double[] currentStage = MinorFunctions.convertListToPrimitiveArray(resp);
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
}
