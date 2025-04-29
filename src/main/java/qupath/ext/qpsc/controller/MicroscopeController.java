package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.ext.qpsc.ui.UIFunctions;

import static qupath.ext.qpsc.ui.UIFunctions.askForCoordinates;
import static qupath.ext.qpsc.utilities.MinorFunctions.getCurrentOffset;

public class MicroscopeController {

    private final String smartpathCmd = "smartpath";  // or read from prefs
    private static final Logger logger =
            LoggerFactory.getLogger(QPScopeController.class);
    private static MicroscopeController instance;

    // Store whatever you need here: e.g. the last used transformation, Python script path, etc.
    private AffineTransform currentTransform;
    private String pythonEnv;
    private String pythonScript;


    public static synchronized MicroscopeController getInstance() {
        if (instance == null) {
            instance = new MicroscopeController();
        }
        return instance;
    }


    /**
     * Dummy “Test Entry” workflow.
     * <ul>
     *   <li>Shows a dialog that asks the user for two coordinates (comma-separated).
     *   <li>Duplicates <code>project-structure.txt</code> in the extension folder,
     *       writing <code>project-structure2.txt</code> (and …3, …4 on subsequent runs).
     * </ul>
     *
     * @return
     */
    public double[] runTestWorkflow() throws IOException {

        /* 1) Ask the user for coordinates (value not yet used) */
        String coordPair = askForCoordinates();   // we are on the FX thread
        if (coordPair == null)
            return null;   // user cancelled

        logger.info("User entered coordinates: {}", coordPair);

        /* duplicate project-structure.txt */
        try {
            Path extDir = Path.of(System.getProperty("user.dir"));
            Path src = extDir.resolve("project-structure.txt");

            int n = 2;
            Path dst;
            do dst = extDir.resolve("project-structure" + n++ + ".txt");
            while (Files.exists(dst));

            String[] cmd = UtilityFunctions.buildCopyCommand(src, dst);
            logger.info("Executing: {}", Arrays.toString(cmd));

            int exit = new ProcessBuilder(cmd).inheritIO().start().waitFor();
            if (exit != 0)                          // fall back to Java copy
                Files.copy(src, dst);

            logger.info("Created {}", dst.getFileName());
        } catch (Exception e) {
            logger.error("Test workflow failed", e);
        }
    }
    /** Build a platform-appropriate copy command */
    private static String[] buildCopyCommand(Path src, Path dst) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Windows: use cmd /c copy "src" "dst"
            return new String[]{"cmd", "/c", "copy", "/Y",
                    src.toAbsolutePath().toString(),
                    dst.toAbsolutePath().toString()};
        }
        // *nix & macOS: use /bin/sh -c cp
        return new String[]{"/bin/sh", "-c",
                "cp \"" + src.toAbsolutePath() + "\" \"" + dst.toAbsolutePath() + "\""};
    }


    public double[] getStagePosition() throws IOException, InterruptedException {
        String smartpathCmd = QPPreferenceDialog.getRunCommandProperty();
        ProcessBuilder pb = new ProcessBuilder(smartpathCmd, "getStagePositionForQuPath");
        Process p = pb.start();

        // Read its stdout (e.g. "1234.5 678.9")
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = r.readLine();
            p.waitFor();
            if (line == null || line.isEmpty())
                throw new IOException("No output from smartpath");
            String[] parts = line.trim().split("\\s+");
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1])
            };
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        }

    /**
     * Move the stage to the given coordinates.
     * @param x Microns in QuPath stage-coordinates
     * @param y Microns in QuPath stage-coordinates
     */
    public void moveStageTo(double x, double y) throws IOException, InterruptedException {
        // Launch: smartpath moveToCoordinates 24.75 -512.89
        ProcessBuilder pb = new ProcessBuilder(
                smartpathCmd,
                "moveToCoordinates",
                Double.toString(x),
                Double.toString(y)
        );
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0)
            throw new IOException("smartpath returned exit code " + code);
    }

    // Then in your JavaFX button-handler:
    public void onMoveButtonClicked(PathObject tile) {
        // 1) compute stageCoords from QuPath coords
        double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(
                new double[]{tile.getROI().getCentroidX(), tile.getROI().getCentroidY()},
                currentTransform
        );

        // 2) apply any offset

        double[] currentOffset = getCurrentOffset();
        double[] adjusted = TransformationFunctions.applyOffset(stageCoords, currentOffset, true);

        // 3) actually move hardware
        try {
            moveStageTo(adjusted[0], adjusted[1]);
            // maybe pop a confirmation dialog here
        } catch (Exception e) {
            UIFunctions.notifyUserOfError(
                    "Failed to move stage:\n" + e.getMessage(),
                    "Stage Move"
            );
        }
    }
    /**
     * Move the microscope stage to the center of the given tile.
     * TODO automatically generated function, does not even remotely work
     */
    public void moveStageToSelectedTile(PathObject tile) throws IOException, InterruptedException {
        // 1) extract QuPath coords
        double x = tile.getROI().getCentroidX();
        double y = tile.getROI().getCentroidY();
        double [] qpCoords = new double []{x, y};

        logger.info("Moving to QuPath coords: " + qpCoords);

        // 2) transform into stage coords
        double [] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(qpCoords, currentTransform);

        // 3) account for any offset (if you store one)
        double[] offset = getCurrentOffset();
        double[] adjusted = TransformationFunctions.applyOffset(stageCoords, offset, true);

        logger.info("Transformed to stage coords: " + List.of(adjusted[0], adjusted[1]));

        // 4) send to Python
        UtilityFunctions.execCommand(
                String.valueOf(List.of(String.valueOf(adjusted[0]), String.valueOf(adjusted[1]))),
                "moveStageToCoordinates");

        // 5) optionally update the GUI
        QuPathGUI.getInstance().getViewer()
                .setCenterPixelLocation(x, y);
    }

    /** If you ever want to update the scaling transform after alignment: */
    public void setCurrentTransform(AffineTransform tx) {
        this.currentTransform = tx;
    }

    /** Dummy bounding-box workflow – replace with your real steps later. */
    public void startBoundingBoxWorkflow() {
        logger.info("▶ Bounding-box workflow started (stub)");
        javafx.application.Platform.runLater(() ->
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "QP Scope • Bounding-box",
                        "Here is where the Bounding-box workflow will run."));
    }

    /** Dummy existing-image workflow – replace with your real steps later. */
    public void startExistingImageWorkflow() {
        logger.info("▶ Existing-image workflow started (stub)");
        javafx.application.Platform.runLater(() ->
                qupath.fx.dialogs.Dialogs.showInfoNotification(
                        "QP Scope • Existing image",
                        "Here is where the Existing-image workflow will run."));
    }


}

