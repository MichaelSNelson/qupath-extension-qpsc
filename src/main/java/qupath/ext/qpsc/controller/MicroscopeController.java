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

import static qupath.ext.qpsc.ui.UIFunctions.askForCoordinates;
import static qupath.ext.qpsc.utilities.MinorFunctions.getCurrentOffset;

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


    /**
     * Dummy "Test Entry" workflow.
     * <ul>
     *   <li>Shows a dialog that asks the user for two coordinates (comma-separated).
     *   <li>Duplicates <code>project-structure.txt</code> in the extension folder,
     *       writing <code>project-structure2.txt</code> (and …3, …4 on subsequent runs).
     * </ul>
     *
     * @return the coordinates the user entered as [x, y], or null if the user cancelled or on error
     * @throws IOException if file I/O unexpectedly fails
     */
    public double[] runTestWorkflow() throws IOException {
        // 1) Get a comma separated coordinate string from the user
        String coordPair = askForCoordinates();
        if (coordPair == null)
            return null;   // user cancelled

        logger.info("User entered coordinates: {}", coordPair);

        // 2) Duplicate project structure.txt
        try {
            Path extDir = Path.of("F:/QPScopeExtension/qupath-extension-qpsc");
            Path src    = extDir.resolve("project-structure.txt");

            // Find a free name: project-structure2.txt, 3, 4…
            int n = 2;
            Path dst;
            do {
                dst = extDir.resolve("project-structure" + n + ".txt");
                n++;
            } while (Files.exists(dst));

            String[] cmd = UtilityFunctions.buildCopyCommand(src, dst);
            logger.info("Executing copy command: {}", Arrays.toString(cmd));
            int exit = new ProcessBuilder(cmd)
                    .inheritIO()
                    .start()
                    .waitFor();

            if (exit != 0) {
                logger.warn("Copy command failed (exit {}), falling back to Java copy", exit);
                Files.copy(src, dst);
            }
            logger.info("Successfully created {}", dst.getFileName());
        } catch (Exception e) {
            logger.error("Test workflow failed during file duplication", e);
            // Even if it fails, we still go on to return the coordinates
        }

        // 3) Parse and return the user’s coordinates
        try {
            String[] parts = coordPair.split(",");
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            return new double[]{ x, y };
        } catch (Exception e) {
            logger.warn("Could not parse coordinate pair '{}' into doubles", coordPair, e);
            return null;
        }
    }


    /** Build a platform appropriate copy command */
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
            logger.error("Unexpected output format for XY position: “{}”", out);
            throw new IOException("Unexpected output for XY position: " + out);
        }

        try {
            logger.info("XY coordinates: "+parts[0]+"  "+ parts[1]);
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1])
            };
        } catch (NumberFormatException e) {
            logger.error("Failed to parse stage position numbers from “{}”", out, e);
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
                        "Move‐XY command timed out:\n" + res.stderr(),
                        "Stage Move XY");
            } else if (res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "Move‐XY returned exit code " + res.exitCode() + "\n" + res.stderr(),
                        "Stage Move XY");
            }
        } catch (IOException | InterruptedException e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move‐XY:\n" + e.getMessage(),
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
                        "Move‐Z command timed out:\n" + res.stderr(),
                        "Stage Move Z");
            } else if (res.exitCode() != 0) {
                UIFunctions.notifyUserOfError(
                        "Move‐Z returned exit code " + res.exitCode() + "\n" + res.stderr(),
                        "Stage Move Z");
            }
        } catch (IOException | InterruptedException e) {
            UIFunctions.notifyUserOfError(
                    "Failed to run Move‐Z:\n" + e.getMessage(),
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
                    3, null,
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
//    /**
//     * Move the stage to the given coordinates via the CLI.
//     *TODO MAYBE include a Z coordinate?
//     * @param x Microns in stage-coordinate X
//     * @param y Microns in stage-coordinate Y
//     */
//    public void moveStageTo(double x, double y) {
//        try {
//            // 60-second timeout, no regex for progress updates
//            CliExecutor.ExecResult res = CliExecutor.execComplexCommand(
//                    10,                   // timeout in seconds
//                    null,                 // no progress regex
//                    "moveStageToCoordinates",
//                    Double.toString(x),
//                    Double.toString(y)
//            );
//
//            if (res.timedOut()) {
//                UIFunctions.notifyUserOfError(
//                        "Microscope command timed out.\n" + res.stderr(),
//                        "Stage Move");
//            } else if (res.exitCode() != 0) {
//                UIFunctions.notifyUserOfError(
//                        "Microscope CLI returned exit code " + res.exitCode() + "\n" + res.stderr(),
//                        "Stage Move");
//            }
//        } catch (IOException | InterruptedException e) {
//            UIFunctions.notifyUserOfError(
//                    "Failed to run stage-move command:\n" + e.getMessage(),
//                    "Stage Move");
//        }
//    }


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
            moveStageXY(adjusted[0], adjusted[1]);
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

    public boolean isWithinBoundsXY(double x, double y) {
        var xlimits = MicroscopeConfigManager.getInstance().getSection("stage", "xlimit");
        var ylimits = MicroscopeConfigManager.getInstance().getSection("stage", "ylimit");

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
        var zlimits = MicroscopeConfigManager.getInstance().getSection("stage", "zlimit");

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

