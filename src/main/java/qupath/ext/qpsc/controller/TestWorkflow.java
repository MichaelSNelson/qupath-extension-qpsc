package qupath.ext.qpsc.controller;

import java.io.IOException;
import java.io.UncheckedIOException;

import java.util.*;

import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.BoundingBoxController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.projects.Project;

public class TestWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(TestWorkflow.class);
    /** Entry point called by your “Test” menu item. */
    public static void runTestWorkflow() {
        var res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

        SampleSetupController.showDialog()
                .thenCompose(sample ->
                        BoundingBoxController.showDialog()
                                .thenApply(bb -> Map.entry(sample, bb))
                )
                .thenAccept(pair -> {
                    var sample = pair.getKey();
                    var bb     = pair.getValue();

                    // pull prefs still needed
                    String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
                    double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                    boolean invertX = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertY = QPPreferenceDialog.getInvertedYProperty();

                    // create/open the QuPath project and import any open image
                    QuPathGUI qupathGUI = QPEx.getQuPath();
                    Map<String,Object> pd;
                    try {
                        pd = QPProjectFunctions.createAndOpenQuPathProject(
                                qupathGUI,
                                projectsFolder,
                                sample.sampleName(),
                                sample.modality(),   // pass the modality from the user
                                invertX,
                                invertY
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }

                    String tempTileDirectory    = (String) pd.get("tempTileDirectory");
                    String imagingModeWithIndex = (String) pd.get("imagingModeWithIndex");
                    Project<?> currentProject   = (Project<?>) pd.get("currentQuPathProject");

                    // compute frame size
                    double pixelSize = MicroscopeConfigManager
                            .getInstance(
                                    QPPreferenceDialog.getMicroscopeConfigFileProperty())
                            .getDouble("imagingMode", sample.modality(), "pixelSize_um");
                    int cameraW = MicroscopeConfigManager
                            .getInstance(
                                    QPPreferenceDialog.getMicroscopeConfigFileProperty())
                            .getInteger("parts","camera","width_px");
                    int cameraH = MicroscopeConfigManager
                            .getInstance(
                                    QPPreferenceDialog.getMicroscopeConfigFileProperty())
                            .getInteger("parts","camera","height_px");
                    double frameWidth  = pixelSize * cameraW;
                    double frameHeight = pixelSize * cameraH;

                    // write tile config
                    UtilityFunctions.performTilingAndSaveConfiguration(
                            tempTileDirectory,
                            imagingModeWithIndex,
                            frameWidth, frameHeight,
                            overlapPercent,
                            List.of(bb.x1(), bb.y1(), bb.x2(), bb.y2()),
                            /*createTiles=*/ true,
                            /*annotations=*/ Collections.emptyList(),
                            invertY, invertX);

                    // now build and fire your acquisition CLI
                    List<String> args = List.of(
                            sample.sampleName(),
                            sample.projectsFolder().getAbsolutePath(),
                            sample.modality(),
                            String.valueOf(bb.x1()),
                            String.valueOf(bb.y1()),
                            String.valueOf(bb.x2()),
                            String.valueOf(bb.y2()),
                            Boolean.toString(bb.inFocus())
                    );
                    try {
                        CliExecutor.execCommandExitCode(
                                res.getString("command.acquisitionWorkflow"),
                                Arrays.toString(args.toArray(new String[0])));
                    } catch (Exception e) {
                        new Alert(Alert.AlertType.ERROR, e.getMessage())
                                .showAndWait();
                    }

                    // finally clean up
                    String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                    if ("Delete".equals(handling)) {
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    } else if ("Zip".equals(handling)) {
                        UtilityFunctions.zipTilesAndMove(tempTileDirectory);
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("Workflow aborted: " + ex);
                    return null;
                });
    }


}
