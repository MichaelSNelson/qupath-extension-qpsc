//Minor improvements:
//
//        Consider using only asynchronous chaining for all UI dialog calls to guarantee no UI blocking, especially if running in environments where UI freezes are a problem.
//
//        Make sure per-annotation error handling doesn’t swallow exceptions that should abort the whole workflow if a fatal problem occurs (e.g., check if at least one region succeeds and warn if some fail).

package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.ui.ExistingImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * ExistingImageWorkflow
 * <p>
 * Orchestrates the full workflow for region-targeted microscope acquisition based on an existing (macro/low-res) image in QuPath.
 * <p>
 * This workflow automates and guides the user through each required step, from sample/project setup to tile acquisition, ensuring robust
 * data handling, user feedback, and proper error checking at each stage.
 * <p>
 * <b>Workflow Steps:</b>
 * <ol>
 *   <li><b>Project/sample setup:</b> User provides sample name, project folder, and selects imaging modality via {@link SampleSetupController}.</li>
 *   <li><b>Image import:</b> Image is imported or opened in the QuPath project, applying X/Y flip preferences if required.</li>
 *   <li><b>Pixel size check:</b> The macro image pixel size is read from image metadata; user is only prompted for input if this information is missing or invalid.</li>
 *   <li><b>Annotation/region detection:</b> Tissue annotations are located and confirmed. If not present, a tissue detection script (if configured) is run. User can verify and edit annotations before proceeding.</li>
 *   <li><b>Tiling:</b> Tiles are generated for each annotation/region using camera and modality settings, and {@code TileConfiguration.txt} files are created for each region.</li>
 *   <li><b>Manual stage alignment:</b> User performs interactive affine alignment between the QuPath image and microscope stage, confirming transformation accuracy for each region (annotation).</li>
 *   <li><b>Acquisition and stitching:</b> For each region, tile acquisition is triggered via external CLI (Python) calls; stitching is launched in the background so new acquisitions can begin in parallel.</li>
 *   <li><b>Cleanup:</b> Temporary tiles are deleted or zipped according to user preferences after acquisition/stitching completes.</li>
 * </ol>
 * <p>
 * <b>Error handling:</b> User is notified at each step if critical data is missing or if they choose to cancel. Logging is provided throughout for workflow tracking and debugging.
 */

public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);

    /**
     * Entrypoint: launches the full workflow with all necessary user dialogs and background steps.
     */
    public static void run() {
        logger.info("Starting Existing Image Workflow...");

        QuPathGUI qupathGUI = QuPathGUI.getInstance();

        // 1. Prompt for sample/project info
        SampleSetupController.showDialog().thenAccept(sample -> {
            if (sample == null) {
                logger.info("Sample setup cancelled.");
                return;
            }
            logger.info("Sample info received: {}", sample);

            // 2. Import current image into a QuPath project, handle flipX/Y from preferences
            boolean flipX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean flipY = QPPreferenceDialog.getFlipMacroYProperty();
            logger.info("Importing image to project: flipX={}, flipY={}", flipX, flipY);

            Map<String, Object> projectDetails;
            if (qupathGUI.getProject() == null) {
                try {
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            qupathGUI,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality(),
                            flipX,
                            flipY
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // Project is already open; get current project info
                projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                        sample.projectsFolder().getAbsolutePath(),
                        sample.sampleName(),
                        sample.modality()
            );
        }

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            // 3. Get macro image pixel size from metadata (prompt if missing or >10um)
            double macroPixelSize = getMacroPixelSizeOrPrompt(qupathGUI)
                    .exceptionally(ex -> {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Pixel size entry cancelled or invalid. Aborting workflow.", "Pixel Size Error"));
                        logger.warn("Pixel size dialog cancelled or invalid", ex);
                        return null;
                    }).join();
            if (Double.isNaN(macroPixelSize)) return; // User cancelled

            logger.info("Final macro pixel size: {} µm", macroPixelSize);

            // 4. Detect/confirm annotations
            List<PathObject> annotations = collectOrCreateAnnotations(qupathGUI, macroPixelSize);
            if (annotations == null || annotations.isEmpty()) {
                logger.warn("No valid annotations found or created. Aborting workflow.");
                return;
            }
        // Show annotation check dialog before proceeding
            CompletableFuture<Boolean> annotationCheckFuture = new CompletableFuture<>();
            UIFunctions.checkValidAnnotationsGUI(
                    List.of("Tissue"), // or whatever PathClass names are valid
                    annotationCheckFuture::complete
            );
            try {
                if (!annotationCheckFuture.get()) {
                    logger.info("User cancelled at annotation verification step.");
                    return;
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
            // 5. Perform tiling and generate TileConfiguration.txt for each annotation
            performTilingForAnnotations(tempTileDirectory, modeWithIndex, macroPixelSize, sample.modality(), annotations);

            // 6. Affine alignment GUI and acquisition (each annotation/region in sequence or parallel)
            CompletableFuture<AffineTransform> transformFuture =
                    AffineTransformationController.setupAffineTransformationAndValidationGUI(
                            macroPixelSize,
                            QPPreferenceDialog.getFlipMacroXProperty(),
                            QPPreferenceDialog.getFlipMacroYProperty()
                    );
            // Wait for completion or handle cancellation
            AffineTransform transform = null;
            try {
                transform = transformFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
            if (transform == null) {
                logger.info("User cancelled affine transform step.");
                return;
            }

            // For each annotation: perform alignment, then acquisition, then stitching (asynchronous)
            annotations.forEach(annotation -> {
                CompletableFuture.runAsync(() -> {
                    try {
                        // 6. Launch tile acquisition via CLI (you'll need to add args as needed)
                        var result = qupath.ext.qpsc.service.CliExecutor.execComplexCommand(
                                300, "tiles done", "acquireTiles", tempTileDirectory);
                        if (result.exitCode() != 0 || result.timedOut()) {
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Acquisition error: " + result.stderr(), "Acquisition Error"));
                            logger.error("Acquisition error: {}", result.stderr());
                            return;
                        }
                        logger.info("Acquisition finished for annotation: {}", annotation.getName());

                        // 6c. Stitch tiles and update project
                        String stitchedImagePath = UtilityFunctions.stitchImagesAndUpdateProject(
                                sample.projectsFolder().getAbsolutePath(),
                                sample.sampleName(),
                                modeWithIndex,
                                annotation.getName(),
                                qupathGUI,
                                project,
                                String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                macroPixelSize,
                                1
                        );
                        logger.info("Stitching complete for annotation {} at {}", annotation.getName(), stitchedImagePath);

                        // 6d. Post-processing: record tile boundaries
                        // ... see previous examples ...

                    } catch (Exception e) {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(e.getMessage(), "Workflow Error"));
                        logger.error("Workflow error for annotation {}", annotation.getName(), e);
                    }
                });
            });
        });
    }

    /**
     * Try to get the macro image pixel size from the open image. Prompt the user only if not found or out of range.
     *
     * @param qupathGUI QuPath GUI instance
     * @return CompletableFuture<Double> for the pixel size (may be cancelled)
     */
    private static CompletableFuture<Double> getMacroPixelSizeOrPrompt(QuPathGUI qupathGUI) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        double macroPixelSize = Double.NaN;
        var imageData = qupathGUI.getImageData();
        if (imageData != null && imageData.getServer() != null) {
            try {
                macroPixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                logger.info("Pixel size found in image metadata: {}", macroPixelSize);
            } catch (Exception e) {
                macroPixelSize = Double.NaN;
            }
        }
        if (!Double.isNaN(macroPixelSize) && macroPixelSize > 0 && macroPixelSize <= 10) {
            future.complete(macroPixelSize);
        } else {
            logger.info("No valid macro pixel size in metadata. Prompting user...");
            ExistingImageController.requestPixelSizeDialog(macroPixelSize).thenAccept(future::complete);
        }
        return future;
    }

    /**
     * Collects annotations from the image, running tissue detection script or prompting the user if none found.
     * May run a Groovy script, or prompt for manual annotation verification.
     */
    private static List<PathObject> collectOrCreateAnnotations(QuPathGUI qupathGUI, double macroPixelSize) {
        // Try to get annotations
        List<PathObject> annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(o -> "Tissue".equals(o.getPathClass().getName()))
                .collect(Collectors.toList());

        if (annotations.isEmpty()) {
            // Run tissue detection script if configured
            String tissueScript = qupath.ext.qpsc.preferences.PersistentPreferences.getAnalysisScriptForAutomation();
            if (tissueScript != null && !tissueScript.isBlank()) {
                logger.info("No annotations found. Running tissue detection script: {}", tissueScript);
                // Optionally patch the script for pixel size, etc., here
                try {
                    String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                            tissueScript,
                            String.valueOf(macroPixelSize),
                            "" // (optional path to classifier or config, if needed)
                    );
                    qupathGUI.runScript(null, modifiedScript);
                } catch (Exception e) {
                    UIFunctions.notifyUserOfError("Tissue detection script failed: " + e.getMessage(), "Script Error");
                    logger.error("Tissue detection script error", e);
                }
                // Refresh annotations after script
                annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                        .filter(o -> "Tissue".equals(o.getPathClass().getName()))
                        .collect(Collectors.toList());
            }

            // If still empty, prompt the user to create/verify them manually
            if (annotations.isEmpty()) {
                logger.info("Still no annotations. Prompting user to draw/select tissue regions.");
                UIFunctions.notifyUserOfError(
                        "No 'Tissue' annotations found. Please add them manually before continuing.",
                        "Missing Annotations"
                );
                // (Optionally, block/wait for user to add and press a "Continue" button...)
                return null;
            }
        }
        logger.info("Found {} tissue annotation(s) in image.", annotations.size());
        return annotations;
    }

    /**
     * For each annotation, create a tile grid and TileConfiguration.txt.
     * Each annotation should get its own subfolder within the temp tile directory, named after the annotation.
     */
    private static void performTilingForAnnotations(
            String tempTileDirectory,
            String modeWithIndex,
            double macroPixelSize,
            String modality,
            List<PathObject> annotations
    ) {
        for (PathObject annotation : annotations) {
            String annotationName = annotation.getName();
            Path annotationTileDir = Path.of(tempTileDirectory, annotationName);

            try {
                Files.createDirectories(annotationTileDir);
                logger.info("Created annotation tile directory: {}", annotationTileDir);

                // Fetch modality, pixel size, and camera dimensions from config
                MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());


                double pixelSize = configMgr.getDouble("imagingMode", modality, "pixelSize_um");
                int cameraWidth  = configMgr.getInteger("imagingMode", modality, "detector", "width_px");
                int cameraHeight = configMgr.getInteger("imagingMode", modality, "detector", "height_px");
                double overlap   = QPPreferenceDialog.getTileOverlapPercentProperty();

                double frameWidth  = pixelSize * cameraWidth;
                double frameHeight = pixelSize * cameraHeight;

                logger.info("Preparing tiling for annotation '{}': frame size = {}x{} µm, overlap = {}%", annotationName, frameWidth, frameHeight, overlap);

                // Only this annotation is tiled, so pass a singleton list for annotations
                UtilityFunctions.performTilingAndSaveConfiguration(
                        annotationTileDir.toString(),   // Directory for this annotation's tiles/config
                        modality,                       // Imaging modality (without index, if that's desired)
                        frameWidth,
                        frameHeight,
                        overlap,
                        null,                           // No bounding box; use annotations directly
                        true,                           // Create detection objects in QuPath
                        List.of(annotation),            // Tiling just this annotation
                        QPPreferenceDialog.getInvertedYProperty(),
                        QPPreferenceDialog.getInvertedXProperty()
                );

                logger.info("Finished tiling and config for annotation '{}'", annotationName);

            } catch (IOException ioex) {
                logger.error("Failed to prepare tiling for annotation '{}': {}", annotationName, ioex.getMessage());
                UIFunctions.notifyUserOfError(
                        "Could not create directory or perform tiling for annotation: " + annotationName, "Directory or Tiling Error"
                );
            }
        }


    }
}
