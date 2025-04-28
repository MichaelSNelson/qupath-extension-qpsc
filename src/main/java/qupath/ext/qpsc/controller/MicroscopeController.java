package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.logging.Logger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import qupath.ext.qpsc.QPPreferenceDialog;
import qupath.lib.objects.PathObject;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.ext.qpsc.ui.UIFunctions;

import static qupath.ext.qpsc.utilities.MinorFunctions.getCurrentOffset;

public class MicroscopeController {

    private String smartpathCmd = "smartpath";  // or read from prefs
    private static final Logger logger = Logger.getLogger(MicroscopeController.class.getName());
    private static MicroscopeController instance;

    // Store whatever you need here: e.g. the last‐used transformation, Python script path, etc.
    private AffineTransform currentTransform;
    private String pythonEnv;
    private String pythonScript;


    public static synchronized MicroscopeController getInstance() {
        if (instance == null) {
            instance = new MicroscopeController();
        }
        return instance;
    }
    public double[] getStagePosition() throws IOException, InterruptedException {
        // Launch: smartpath getStagePositionForQuPath
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
    public void moveStageToSelectedTile(PathObject tile) {
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
        UtilityFunctions.runPythonCommand(pythonEnv, pythonScript,
                List.of(String.valueOf(adjusted[0]), String.valueOf(adjusted[1])),
                "moveStageToCoordinates.py");

        // 5) optionally update the GUI
        QuPathGUI.getInstance().getViewer()
                .setCenterPixelLocation(x, y);
    }

    /** If you ever want to update the scaling transform after alignment: */
    public void setCurrentTransform(AffineTransform tx) {
        this.currentTransform = tx;
    }
}

