package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.objects.PathObject;


/**
 * MicroscopeController
 *
 * <p>Thin adapter to your microscope’s CLI:
 *   - Wraps command line calls (e.g. "smartpath getStagePositionForQuPath ").
 *   - Exposes typed methods like moveStageXY, getStagePositionZ, moveStageP.
 *   - Handles timeouts and error pop-ups so higher layers don’t worry about CLI details.
 */


public class MicroscopeController {

    private static final Logger logger =
            LoggerFactory.getLogger(QPScopeController.class);
    private static MicroscopeController instance;

    // Store whatever you need here: e.g. the last used transformation, Python script path, etc.
    private AffineTransform currentTransform;
    private String pythonEnv;
    private String pythonScript;
    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    // now load from strings.properties
    private static final String CMD_GET_STAGE_XY  = BUNDLE.getString("command.getStagePositionXY");
    private static final String CMD_GET_STAGE_Z   = BUNDLE.getString("command.getStagePositionZ");
    private static final String CMD_GET_STAGE_P   = BUNDLE.getString("command.getStagePositionP");

    private static final String CMD_MOVE_STAGE_XY = BUNDLE.getString("command.moveStageXY");
    private static final String CMD_MOVE_STAGE_Z  = BUNDLE.getString("command.moveStageZ");
    private static final String CMD_MOVE_STAGE_P  = BUNDLE.getString("command.moveStageP");


    public static synchronized MicroscopeController getInstance() {
        if (instance == null) {
            instance = new MicroscopeController();
        }
        return instance;
    }




//    /** Build a platform appropriate copy command */
//    private static String[] buildCopyCommand(Path src, Path dst) {
//        if (System.getProperty("os.name").toLowerCase().contains("win")) {
//            // Windows: use cmd /c copy "src" "dst"
//            return new String[]{"cmd", "/c", "copy", "/Y",
//                    src.toAbsolutePath().toString(),
//                    dst.toAbsolutePath().toString()};
//        }
//        // *nix & macOS: use /bin/sh -c cp
//        return new String[]{"/bin/sh", "-c",
//                "cp \"" + src.toAbsolutePath() + "\" \"" + dst.toAbsolutePath() + "\""};
//    }


    /**
     * Query the microscope for its current X,Y stage position.
     *
     * @return a two element array [x, y] in microns
     * @throws IOException if the CLI returns non zero or unparsable output
     * @throws InterruptedException if the process is interrupted
     */
    public double[] getStagePositionXY() throws IOException, InterruptedException {
        String out = CliExecutor.execCommandAndGetOutput(10, CMD_GET_STAGE_XY);
        logger.info("getStagePositionXY raw output: {}", out);

        // Strip parentheses and commas, then split on whitespace
        String cleaned = out.replaceAll("[(),]", "").trim();
        String[] parts = cleaned.split("\\s+");
        if (parts.length < 2) {
            logger.error("Unexpected output format for XY position: {}", out);
            throw new IOException("Unexpected output for XY position: " + out);
        }

        try {
            logger.info("XY coordinates: "+parts[0]+"  "+ parts[1]);
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1])
            };
        } catch (NumberFormatException e) {
            logger.error("Failed to parse stage position numbers from {}", out, e);
            throw new IOException("Cannot parse stage position: " + out, e);
        }
    }

    /**
     * Query the microscope for its current Z stage position.
     *
     * @return the Z coordinate in microns
     * @throws IOException if the CLI returns non zero or unparsable output
     * @throws InterruptedException if the process is interrupted
     */
    public double getStagePositionZ() throws IOException, InterruptedException {
        String out = CliExecutor.execCommandAndGetOutput(10, CMD_GET_STAGE_Z);
        try {
            String z = out.trim();
             logger.info("Z stage position um:" + z);
            return Double.parseDouble(z);
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected output for Z position: " + out, e);
        }
    }
    /**
     * Query the current polarizer (P) position from the stage.
     * @return The P coordinate (degrees or whatever units your CLI returns).
     */
    public double getStagePositionR() throws IOException, InterruptedException {
        String out = CliExecutor.execCommandAndGetOutput(10, CMD_GET_STAGE_P);
        try {
            // Strip parentheses and commas, then split on whitespace
            String cleaned = out.replaceAll("[(),]", "").trim();
            logger.info("Rotational stage angle" + cleaned);
             return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected output for P position: " + out, e);
        }
    }
    /**
     * Move the stage in X,Y only.  Z will not be touched.
     *
     * @param x target X in microns
     * @param y target Y in microns
     */
    public void moveStageXY(double x, double y) {
        try {
            CliExecutor.ExecResult res = CliExecutor.execComplexCommand(
                    10,       // timeout in seconds
                    null,     // no progress regex
                    CMD_MOVE_STAGE_XY,
                    "-x",
                    Double.toString(x),
                    "-y",
                    Double.toString(y)
            );
            if (res.timedOut()) {
                UIFunctions.notifyUserOfError(
                        "Move XY command timed out:\n" + res.stderr(),
                        "Stage Move XY");
            } else if (res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "Move XY returned exit code " + res.exitCode() + "\n" + res.stderr(),
                        "Stage Move XY");
            }
        } catch (IOException | InterruptedException e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move XY:\n" + e.getMessage(),
                    "Stage Move XY");
        }
    }

    /**
     * Move the stage in Z only.
     *
     * @param z target Z in microns
     */
    public void moveStageZ(double z) {
        try {
            CliExecutor.ExecResult res = CliExecutor.execComplexCommand(
                    10,       // timeout in seconds
                    null,     // no progress regex
                    CMD_MOVE_STAGE_Z,
                    "-z",
                    Double.toString(z)
            );
            if (res.timedOut()) {
                UIFunctions.notifyUserOfError(
                        "Move Z command timed out:\n" + res.stderr(),
                        "Stage Move Z");
            } else if (res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "Move Z returned exit code " + res.exitCode() + "\n" + res.stderr(),
                        "Stage Move Z");
            }
        } catch (IOException | InterruptedException e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move Z:\n" + e.getMessage(),
                    "Stage Move Z");
        }
    }

    /**
     * Rotate the polarizer to the given angle p.
     * @param r The target polarizer coordinate.
     */
    public void moveStageR(double r) {
        try {
            var res = CliExecutor.execComplexCommand(
                    10, null,
                    CMD_MOVE_STAGE_P,
                    "-angle",
                    Double.toString(r)
            );
            if (res.timedOut()) {
                UIFunctions.notifyUserOfError(
                        "Move-P command timed out:\n" + res.stderr(),
                        "Polarizer Move");
            } else if (res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "Move-P returned exit code " + res.exitCode() + "\n" + res.stderr(),
                        "Polarizer Move");
            }
        } catch (Exception e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move-P:\n" + e.getMessage(),
                    "Polarizer Move");
        }
    }



    // TODO implement browser for QuPath image that moves microscope to locations
    // These two functions form a base for that.
    public void onMoveButtonClicked(PathObject tile) {
        // 1) compute stageCoords from QuPath coords
        double[] stageCoords = TransformationFunctions.qpToMicroscopeCoordinates(
                new double[]{tile.getROI().getCentroidX(), tile.getROI().getCentroidY()},
                currentTransform
        );

        // 2) apply any offset TODO remove
//
//        double[] currentOffset = getCurrentOffset();
//        double[] adjusted = TransformationFunctions.applyOffset(stageCoords, currentOffset, true);

        // 3) actually move hardware
        try {
            moveStageXY(stageCoords[0], stageCoords[1]);
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

//        // 3) account for any offset (if you store one) TODO remove
//        double[] offset = getCurrentOffset();
//        double[] adjusted = TransformationFunctions.applyOffset(stageCoords, offset, true);

        logger.info("Transformed to stage coords: " + List.of(stageCoords[0], stageCoords[1]));

        // 4) send to Python
        UtilityFunctions.execCommand(
                String.valueOf(List.of(String.valueOf(stageCoords[0]), String.valueOf(stageCoords[1]))),
                "moveStageToCoordinates");

        // 5) optionally update the GUI
        QuPathGUI.getInstance().getViewer()
                .setCenterPixelLocation(x, y);
    }

    /** If you ever want to update the scaling transform after alignment: */
    public void setCurrentTransform(AffineTransform tx) {
        this.currentTransform = tx;
    }

    public boolean isWithinBoundsXY(double x, double y) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var xlimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "xlimit");
        var ylimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "ylimit");

        if (xlimits == null || ylimits == null) {
            logger.error("Stage limits missing from config");
            return false;
        }

        double xLow = ((Number)xlimits.get("low")).doubleValue();
        double xHigh = ((Number)xlimits.get("high")).doubleValue();
        double yLow = ((Number)ylimits.get("low")).doubleValue();
        double yHigh = ((Number)ylimits.get("high")).doubleValue();

        boolean withinX = (x >= Math.min(xLow, xHigh) && x <= Math.max(xLow, xHigh));
        boolean withinY = (y >= Math.min(yLow, yHigh) && y <= Math.max(yLow, yHigh));

        return withinX && withinY;
    }
    public boolean isWithinBoundsZ(double z) {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        var zlimits = MicroscopeConfigManager.getInstance(configPath).getSection("stage", "zlimit");

        if (zlimits == null ) {
            logger.error("Stage limits missing from config");
            return false;
        }

        double zLow = ((Number)zlimits.get("low")).doubleValue();
        double zHigh = ((Number)zlimits.get("high")).doubleValue();


        boolean withinZ = (z >= Math.min(zLow, zHigh) && z <= Math.max(zLow, zHigh));


        return withinZ ;
    }

}

