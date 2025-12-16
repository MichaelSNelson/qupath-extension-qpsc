package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Helper class for annotation management in the workflow.
 *
 * <p>This class provides utilities for:
 * <ul>
 *   <li>Ensuring valid annotations exist before acquisition</li>
 *   <li>Running automatic tissue detection if configured</li>
 *   <li>Validating annotation classes and properties</li>
 *   <li>Auto-naming unnamed annotations</li>
 * </ul>
 *
 * <p>Valid annotation classes are "Tissue", "Scanned Area", and "Bounding Box".
 * These represent different types of regions that can be acquired.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AnnotationHelper {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationHelper.class);


    /**
     * Ensures annotations exist for acquisition.
     *
     * <p>This method follows this logic:
     * <ol>
     *   <li>Check for existing valid annotations based on selected classes</li>
     *   <li>If none found, check for configured tissue detection script</li>
     *   <li>If no script configured, prompt user to select one</li>
     *   <li>Run tissue detection to create annotations</li>
     *   <li>Ensure all annotations have names</li>
     * </ol>
     *
     * @param gui QuPath GUI instance
     * @param macroPixelSize Pixel size of macro image in micrometers
     * @param validClasses List of class names to consider valid
     * @return List of valid annotations (may be empty if none created)
     */
    public static List<PathObject> ensureAnnotationsExist(QuPathGUI gui, double macroPixelSize, List<String> validClasses) {
        logger.info("Ensuring annotations exist for acquisition with classes: {}", validClasses);

        // Get existing annotations using GUI hierarchy (not QP static context)
        // This is critical after image flip operations to get the correct annotations
        List<PathObject> annotations = getCurrentValidAnnotations(gui, validClasses);

        if (!annotations.isEmpty()) {
            logger.info("Found {} existing valid annotations", annotations.size());
            ensureAnnotationNames(annotations);
            return annotations;
        }

        logger.info("No existing annotations found, checking tissue detection script");

        // Run tissue detection if configured
        String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();

        if (tissueScript == null || tissueScript.isBlank()) {
            logger.info("No tissue detection script configured, prompting user");
            tissueScript = promptForTissueDetectionScript();
        }

        if (tissueScript != null && !tissueScript.isBlank()) {
            try {
                logger.info("Running tissue detection script: {}", tissueScript);

                // Get current image pixel size
                double pixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                // Calculate script paths and modify script with parameters
                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = TileProcessingUtilities.modifyTissueDetectScript(
                        tissueScript,
                        String.valueOf(pixelSize),
                        scriptPaths.get("jsonTissueClassfierPathString")
                );

                // Run the script
                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Re-collect annotations after tissue detection using GUI hierarchy
                annotations = getCurrentValidAnnotations(gui, validClasses);
                logger.info("Found {} annotations after tissue detection", annotations.size());

            } catch (Exception e) {
                logger.error("Error running tissue detection", e);
            }
        }

        if (annotations.isEmpty()) {
            logger.warn("Still no valid annotations after tissue detection");
            Platform.runLater(() ->
                    UIFunctions.notifyUserOfError(
                            "No valid annotations found. Please create annotations with one of these classes:\n" +
                                    String.join(", ", validClasses),
                            "No Annotations"
                    )
            );
        } else {
            ensureAnnotationNames(annotations);
        }

        return annotations;
    }

    /**
     * Ensures annotations exist using the default selected classes from preferences.
     *
     * @param gui QuPath GUI instance
     * @param macroPixelSize Pixel size of macro image in micrometers
     * @return List of valid annotations (may be empty if none created)
     */
    public static List<PathObject> ensureAnnotationsExist(QuPathGUI gui, double macroPixelSize) {
        List<String> selectedClasses = PersistentPreferences.getSelectedAnnotationClasses();
        return ensureAnnotationsExist(gui, macroPixelSize, selectedClasses);
    }


    /**
     * Gets current valid annotations from the image hierarchy using custom class list.
     *
     * <p>IMPORTANT: This method uses the GUI's current image hierarchy directly,
     * ensuring we get annotations from the currently displayed image (including
     * after flip operations). This is more reliable than using QP.getAnnotationObjects()
     * which relies on the static scripting context that may not be synchronized.
     *
     * @param gui QuPath GUI instance
     * @param validClasses List of class names to consider valid
     * @return List of valid annotations
     */
    public static List<PathObject> getCurrentValidAnnotations(QuPathGUI gui, List<String> validClasses) {
        if (gui.getImageData() == null) {
            logger.warn("No image data available - cannot get annotations");
            return Collections.emptyList();
        }

        // Enhanced logging to help diagnose tile positioning issues
        String imageName = gui.getImageData().getServer().getPath();
        logger.info("Retrieving annotations from image: {}", imageName);

        var hierarchy = gui.getImageData().getHierarchy();
        var allAnnotations = hierarchy.getAnnotationObjects();

        var annotations = allAnnotations.stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        validClasses.contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        // Log annotation positions to help diagnose coordinate issues
        if (!annotations.isEmpty()) {
            PathObject firstAnn = annotations.get(0);
            logger.info("Found {} valid annotations. First annotation '{}' at position: ({}, {}) size: {}x{}",
                    annotations.size(),
                    firstAnn.getName() != null ? firstAnn.getName() : "unnamed",
                    firstAnn.getROI().getBoundsX(),
                    firstAnn.getROI().getBoundsY(),
                    firstAnn.getROI().getBoundsWidth(),
                    firstAnn.getROI().getBoundsHeight());
        } else {
            logger.debug("Found {} valid annotations from {} total with classes: {} (using GUI hierarchy)",
                    annotations.size(), allAnnotations.size(), validClasses);
        }

        return annotations;
    }

    /**
     * Gets current valid annotations using selected classes from preferences.
     *
     * @param gui QuPath GUI instance
     * @return List of valid annotations
     */
    public static List<PathObject> getCurrentValidAnnotations(QuPathGUI gui) {
        List<String> selectedClasses = PersistentPreferences.getSelectedAnnotationClasses();
        return getCurrentValidAnnotations(gui, selectedClasses);
    }

    /**
     * Gets current valid annotations from the image hierarchy using custom class list.
     *
     * @param validClasses List of class names to consider valid
     * @return List of valid annotations
     * @deprecated Use {@link #getCurrentValidAnnotations(QuPathGUI, List)} instead for reliable
     *             annotation retrieval after image flip operations.
     */
    @Deprecated
    public static List<PathObject> getCurrentValidAnnotations(List<String> validClasses) {
        var annotations = QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        validClasses.contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        logger.debug("Found {} valid annotations from {} total with classes: {}",
                annotations.size(), QP.getAnnotationObjects().size(), validClasses);

        return annotations;
    }

    /**
     * Gets current valid annotations using selected classes from preferences.
     *
     * @return List of valid annotations
     * @deprecated Use {@link #getCurrentValidAnnotations(QuPathGUI)} instead for reliable
     *             annotation retrieval after image flip operations.
     */
    @Deprecated
    public static List<PathObject> getCurrentValidAnnotations() {
        List<String> selectedClasses = PersistentPreferences.getSelectedAnnotationClasses();
        return getCurrentValidAnnotations(selectedClasses);
    }

    /**
     * Ensures all annotations have names.
     *
     * <p>Unnamed annotations are given auto-generated names based on their
     * class and centroid position. This ensures unique identification during
     * acquisition and file organization.
     *
     * @param annotations List of annotations to check and name
     */
    private static void ensureAnnotationNames(List<PathObject> annotations) {
        int unnamedCount = 0;
        for (PathObject ann : annotations) {
            if (ann.getName() == null || ann.getName().trim().isEmpty()) {
                String className = ann.getPathClass() != null ?
                        ann.getPathClass().getName() : "Annotation";

                // Create name based on class and position
                String name = String.format("%s_%d_%d",
                        className,
                        Math.round(ann.getROI().getCentroidX()),
                        Math.round(ann.getROI().getCentroidY()));

                ann.setName(name);
                logger.info("Auto-named annotation: {}", name);
                unnamedCount++;
            }
        }

        if (unnamedCount > 0) {
            logger.info("Auto-named {} annotations", unnamedCount);
        }
    }

    //TODO this should probably be a part of another dialog.

    /**
     * Prompts user to select a tissue detection script.
     *
     * <p>Shows a dialog asking if the user wants to run automatic tissue detection,
     * and if so, allows them to select a Groovy script file.
     *
     * @return Path to selected script or null if cancelled
     */
    private static String promptForTissueDetectionScript() {
        CompletableFuture<String> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            var useDetection = Dialogs.showYesNoDialog(
                    "Tissue Detection",
                    "Would you like to run automatic tissue detection?\n\n" +
                            "This will create annotations for tissue regions."
            );

            if (!useDetection) {
                future.complete(null);
                return;
            }

            // Show file chooser for script selection
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Select Tissue Detection Script");
            fileChooser.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("Groovy Scripts", "*.groovy"),
                    new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
            );

            File selectedFile = fileChooser.showOpenDialog(null);
            future.complete(selectedFile != null ? selectedFile.getAbsolutePath() : null);
        });

        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error selecting tissue detection script", e);
            return null;
        }
    }
}
