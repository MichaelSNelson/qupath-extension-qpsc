
// File: qupath/ext/qpsc/controller/workflow/AnnotationHelper.java
package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Helper class for annotation management.
 */
public class AnnotationHelper {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationHelper.class);
    private static final String[] VALID_ANNOTATION_CLASSES = {"Tissue", "Scanned Area", "Bounding Box"};

    /**
     * Ensures annotations exist - either collects existing ones or creates new via tissue detection.
     */
    public static List<PathObject> ensureAnnotationsExist(QuPathGUI gui, double macroPixelSize) {
        logger.info("Ensuring annotations exist for acquisition");

        // Get existing annotations
        List<PathObject> annotations = getCurrentValidAnnotations();

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

                double pixelSize = gui.getImageData().getServer()
                        .getPixelCalibration().getAveragedPixelSizeMicrons();

                Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                        tissueScript,
                        String.valueOf(pixelSize),
                        scriptPaths.get("jsonTissueClassfierPathString")
                );

                gui.runScript(null, modifiedScript);
                logger.info("Tissue detection completed");

                // Re-collect annotations
                annotations = getCurrentValidAnnotations();
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
                                    String.join(", ", VALID_ANNOTATION_CLASSES),
                            "No Annotations"
                    )
            );
        } else {
            ensureAnnotationNames(annotations);
        }

        return annotations;
    }

    /**
     * Gets current valid annotations from the image hierarchy.
     */
    public static List<PathObject> getCurrentValidAnnotations() {
        var annotations = QP.getAnnotationObjects().stream()
                .filter(ann -> ann.getROI() != null && !ann.getROI().isEmpty())
                .filter(ann -> ann.getPathClass() != null &&
                        Arrays.asList(VALID_ANNOTATION_CLASSES).contains(ann.getPathClass().getName()))
                .collect(Collectors.toList());

        logger.debug("Found {} valid annotations from {} total",
                annotations.size(), QP.getAnnotationObjects().size());

        return annotations;
    }

    /**
     * Ensures all annotations have names.
     */
    private static void ensureAnnotationNames(List<PathObject> annotations) {
        int unnamedCount = 0;
        for (PathObject ann : annotations) {
            if (ann.getName() == null || ann.getName().trim().isEmpty()) {
                String className = ann.getPathClass() != null ?
                        ann.getPathClass().getName() : "Annotation";

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

    /**
     * Prompts user to select a tissue detection script.
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