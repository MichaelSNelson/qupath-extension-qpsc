package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.RotationManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.CliExecutor;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.utilities.*;
import qupath.ext.qpsc.ui.ExistingImageController;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
//TODO need to add image to project so that it gets flipped before running user interface stuff
/**
 * ExistingImageWorkflow
 * <p>
 * Orchestrates the full workflow for region-targeted microscope acquisition based on an existing (macro/low-res) image in QuPath.
 */
public class ExistingImageWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageWorkflow.class);
    private static final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

    /**
     * Single-threaded executor for stitching to ensure only one stitching job runs at a time
     */
    private static final ExecutorService STITCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stitching-queue");
        t.setDaemon(true);
        return t;
    });

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

            // 2. Import current image into a QuPath project
            boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
            boolean invertedY = QPPreferenceDialog.getInvertedYProperty();
            logger.info("Importing image to project: invertedX={}, invertedY={}", invertedX, invertedY);

            Map<String, Object> projectDetails;
            try {
                if (qupathGUI.getProject() == null) {
                    projectDetails = QPProjectFunctions.createAndOpenQuPathProject(
                            qupathGUI,
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality(),
                            invertedX,
                            invertedY
                    );
                } else {
                    // Project is already open
                    projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                            sample.projectsFolder().getAbsolutePath(),
                            sample.sampleName(),
                            sample.modality()
                    );
                }
            } catch (IOException e) {
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Failed to create/open project: " + e.getMessage(),
                        "Project Error"));
                return;
            }

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) projectDetails.get("currentQuPathProject");
            String tempTileDirectory = (String) projectDetails.get("tempTileDirectory");
            String modeWithIndex = (String) projectDetails.get("imagingModeWithIndex");

            // 3. Get macro image pixel size
            getMacroPixelSizeOrPrompt(qupathGUI)
                    .thenAccept(macroPixelSize -> {
                        if (macroPixelSize == null || macroPixelSize <= 0) {
                            logger.warn("Invalid pixel size. Aborting workflow.");
                            return;
                        }
                        logger.info("Final macro pixel size: {} µm", macroPixelSize);

                        // 4. Detect/confirm annotations
                        List<PathObject> annotations = collectOrCreateAnnotations(qupathGUI, macroPixelSize);
                        if (annotations == null || annotations.isEmpty()) {
                            logger.warn("No valid annotations found or created. Aborting workflow.");
                            return;
                        }

                        // 5. Show annotation check dialog
                        CompletableFuture<Boolean> annotationCheckFuture = new CompletableFuture<>();
                        UIFunctions.checkValidAnnotationsGUI(
                                List.of("Tissue"),
                                annotationCheckFuture::complete
                        );

                        annotationCheckFuture.thenAccept(proceed -> {
                            if (!proceed) {
                                logger.info("User cancelled at annotation verification step.");
                                return;
                            }

                            // 6. Get camera parameters from config
                            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(
                                    QPPreferenceDialog.getMicroscopeConfigFileProperty());
                            double pixelSize = mgr.getDouble("imagingMode", sample.modality(), "pixelSize_um");
                            int cameraWidth = mgr.getInteger("imagingMode", sample.modality(), "detector", "width_px");
                            int cameraHeight = mgr.getInteger("imagingMode", sample.modality(), "detector", "height_px");

                            double frameWidthMicrons = pixelSize * cameraWidth;
                            double frameHeightMicrons = pixelSize * cameraHeight;

                            // Convert to QuPath pixels for tiling
                            double frameWidthQP = frameWidthMicrons / macroPixelSize;
                            double frameHeightQP = frameHeightMicrons / macroPixelSize;
                            double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();

                            try {
                                // 7. Remove existing tiles and create new ones
                                String modalityBase = sample.modality().replaceAll("(_\\d+)$", "");
                                QP.getDetectionObjects().stream()
                                        .filter(o -> o.getPathClass() != null &&
                                                o.getPathClass().toString().toLowerCase().contains(modalityBase))
                                        .forEach(o -> QP.removeObject(o, true));

                                // Build tiling request
                                TilingRequest request = new TilingRequest.Builder()
                                        .outputFolder(tempTileDirectory)
                                        .modalityName(modeWithIndex)
                                        .frameSize(frameWidthQP, frameHeightQP)
                                        .overlapPercent(overlapPercent)
                                        .annotations(annotations)
                                        .invertAxes(invertedX, invertedY)
                                        .createDetections(true)
                                        .addBuffer(true)
                                        .build();

                                TilingUtilities.createTiles(request);
                                logger.info("Created tiles for {} annotations", annotations.size());

                            } catch (IOException e) {
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        "Failed to create tile configurations: " + e.getMessage(),
                                        "Tiling Error"));
                                return;
                            }

                            // 8. Affine alignment
                            AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                            macroPixelSize, invertedX, invertedY)
                                    .thenAccept(transform -> {
                                        if (transform == null) {
                                            logger.info("User cancelled affine transform step.");
                                            return;
                                        }

                                        // 9. Get rotation angles based on modality
                                        logger.info("Checking rotation requirements for modality: {}", sample.modality());
                                        RotationManager rotationManager = new RotationManager(sample.modality());
                                        rotationManager.getRotationAngles(sample.modality())
                                                .thenAccept(rotationAngles -> {
                                                    logger.info("Rotation angles returned: {}", rotationAngles);
                                                    if (rotationAngles == null || rotationAngles.isEmpty()) {
                                                        logger.info("No rotation angles selected/required for modality: {}", sample.modality());
                                                        // Process without rotation
                                                        processAnnotations(annotations, transform, sample, project,
                                                                tempTileDirectory, modeWithIndex, pixelSize,
                                                                qupathGUI);
                                                    } else {
                                                        logger.info("Processing with rotation angles: {} for modality: {}",
                                                                rotationAngles, sample.modality());
                                                        // Process each angle as a separate acquisition
                                                        processAnnotations(annotations, transform, sample,
                                                                project, tempTileDirectory, modeWithIndex, pixelSize,
                                                                qupathGUI, rotationAngles, rotationManager);
                                                    }
                                                })
                                                .exceptionally(ex -> {
                                                    logger.error("Rotation angle selection failed", ex);
                                                    return null;
                                                });
                                    });
                        });
                    })
                    .exceptionally(ex -> {
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Workflow error: " + ex.getMessage(), "Workflow Error"));
                        logger.error("Workflow failed", ex);
                        return null;
                    });
        }).exceptionally(ex -> {
            logger.info("Sample setup failed: {}", ex.getMessage());
            return null;
        });
    }
    /**
     * Process all annotations: transform tiles, acquire, and queue stitching.
     * This version handles rotation by processing each angle separately.
     */
    private static void processAnnotations(
            List<PathObject> annotations,
            AffineTransform transform,
            SampleSetupController.SampleSetupResult sample,
            Project<BufferedImage> project,
            String tempTileDirectory,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI qupathGUI,
            List<Double> rotationAngles,
            RotationManager rotationManager) {

        if (rotationAngles == null || rotationAngles.isEmpty()) {
            // No rotation - call simple version
            processAnnotations(annotations, transform, sample, project,
                    tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);
            return;
        }

        // Process each rotation angle
        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();

        for (Double angle : rotationAngles) {
            String angleSuffix = rotationManager.getAngleSuffix(sample.modality(), angle);
            logger.info("Processing rotation angle: {} degrees (suffix: {})", angle, angleSuffix);

            try {
                // Set the rotation angle
                MicroscopeController.getInstance().moveStageR(angle);
                logger.info("Moved rotation stage to {} degrees", angle);

                // Wait a moment for stage to settle
                Thread.sleep(1000);

            } catch (Exception e) {
                logger.error("Failed to set rotation angle", e);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Failed to set rotation to " + angle + " degrees: " + e.getMessage(),
                        "Rotation Error"));
                continue;
            }

            // Transform all tile configurations first (once per angle)
            try {
                TransformationFunctions.transformTileConfiguration(tempTileDirectory, transform);
                logger.info("Transformed tile configurations for angle: {}", angle);
            } catch (IOException e) {
                logger.error("Failed to transform tile configurations", e);
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "Failed to transform tiles: " + e.getMessage(),
                        "Transform Error"));
                continue;
            }

            // Launch acquisition for each annotation at this angle
            List<CompletableFuture<Void>> acquisitionFutures = new ArrayList<>();

            for (PathObject annotation : annotations) {
                String annotationName = annotation.getName();
                String fullAnnotationName = annotationName + angleSuffix;
                logger.info("Starting acquisition for annotation: {} at angle: {}",
                        annotationName, angle);

                CompletableFuture<Void> acquisitionFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Build command with angle-specific annotation name
                        var result = CliExecutor.execComplexCommand(
                                300,
                                "tiles done",
                                res.getString("command.acquisitionWorkflow"),
                                configFileLocation,
                                projectsFolder,
                                sample.sampleName(),
                                modeWithIndex + angleSuffix,  // Include angle in mode
                                annotationName  // Original annotation name for folder
                        );

                        if (result.exitCode() != 0) {
                            throw new RuntimeException("Acquisition failed for " + fullAnnotationName +
                                    ": " + result.stderr());
                        }
                        logger.info("Acquisition completed for annotation: {} at angle: {}",
                                annotationName, angle);
                        return null;
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });

                // Queue stitching after acquisition completes
                acquisitionFuture.thenRunAsync(() -> {
                    try {
                        logger.info("Queuing stitching for annotation: {}", fullAnnotationName);
                        String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                                projectsFolder,
                                sample.sampleName(),
                                modeWithIndex + angleSuffix,
                                annotationName,
                                qupathGUI,
                                project,
                                String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                                pixelSize,
                                1  // downsample
                        );
                        logger.info("Stitching completed for annotation: {}, output: {}",
                                fullAnnotationName, outPath);
                    } catch (Exception e) {
                        logger.error("Stitching failed for annotation: " + fullAnnotationName, e);
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Stitching failed for " + fullAnnotationName + ": " + e.getMessage(),
                                "Stitching Error"));
                    }
                }, STITCH_EXECUTOR);

                acquisitionFutures.add(acquisitionFuture);
            }

            // Wait for all acquisitions at this angle to complete
            CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0]))
                    .join(); // Block here to ensure angles are processed sequentially
        }
    }
    /**
     * Process all annotations: transform tiles, acquire, and queue stitching
     */
    private static void processAnnotations(
            List<PathObject> annotations,
            AffineTransform transform,
            SampleSetupController.SampleSetupResult sample,
            Project<BufferedImage> project,
            String tempTileDirectory,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI qupathGUI) {

        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();

        // Transform all tile configurations first
        try {
            TransformationFunctions.transformTileConfiguration(tempTileDirectory, transform);
            logger.info("Transformed tile configurations for all annotations");
        } catch (IOException e) {
            logger.error("Failed to transform tile configurations", e);
            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                    "Failed to transform tiles: " + e.getMessage(),
                    "Transform Error"));
            return;
        }

        // Launch acquisition for each annotation
        List<CompletableFuture<Void>> acquisitionFutures = new ArrayList<>();

        for (PathObject annotation : annotations) {
            String annotationName = annotation.getName();
            logger.info("Starting acquisition for annotation: {}", annotationName);

            CompletableFuture<Void> acquisitionFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    var result = CliExecutor.execComplexCommand(
                            300,
                            "tiles done",
                            res.getString("command.acquisitionWorkflow"),
                            configFileLocation,
                            projectsFolder,
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName
                    );

                    if (result.exitCode() != 0) {
                        throw new RuntimeException("Acquisition failed for " + annotationName +
                                ": " + result.stderr());
                    }
                    logger.info("Acquisition completed for annotation: {}", annotationName);
                    return null;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            // Queue stitching after acquisition completes
            acquisitionFuture.thenRunAsync(() -> {
                try {
                    logger.info("Queuing stitching for annotation: {}", annotationName);
                    String outPath = UtilityFunctions.stitchImagesAndUpdateProject(
                            projectsFolder,
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName,
                            qupathGUI,
                            project,
                            String.valueOf(QPPreferenceDialog.getCompressionTypeProperty()),
                            pixelSize,
                            1  // downsample
                    );
                    logger.info("Stitching completed for annotation: {}, output: {}",
                            annotationName, outPath);
                } catch (Exception e) {
                    logger.error("Stitching failed for annotation: " + annotationName, e);
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Stitching failed for " + annotationName + ": " + e.getMessage(),
                            "Stitching Error"));
                }
            }, STITCH_EXECUTOR);

            acquisitionFutures.add(acquisitionFuture);
        }

        // When all acquisitions complete, handle cleanup
        CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    logger.info("All acquisitions completed");
                    String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                    if ("Delete".equals(handling)) {
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    } else if ("Zip".equals(handling)) {
                        UtilityFunctions.zipTilesAndMove(tempTileDirectory);
                        UtilityFunctions.deleteTilesAndFolder(tempTileDirectory);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error in acquisition workflow", ex);
                    return null;
                });
    }

    /**
     * Try to get the macro image pixel size from the open image. Prompt the user only if not found or out of range.
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
            ExistingImageController.requestPixelSizeDialog(macroPixelSize)
                    .thenAccept(future::complete)
                    .exceptionally(ex -> {
                        future.complete(null);
                        return null;
                    });
        }
        return future;
    }

    /**
     * Collects annotations from the image, running tissue detection script or prompting the user if none found.
     */
    private static List<PathObject> collectOrCreateAnnotations(QuPathGUI qupathGUI, double macroPixelSize) {
        // Try to get annotations
        List<PathObject> annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                .collect(Collectors.toList());

        if (annotations.isEmpty()) {
            // Run tissue detection script if configured
            String tissueScript = QPPreferenceDialog.getTissueDetectionScriptProperty();
            if (tissueScript != null && !tissueScript.isBlank()) {
                logger.info("No annotations found. Running tissue detection script: {}", tissueScript);
                try {
                    Map<String, String> scriptPaths = MinorFunctions.calculateScriptPaths(tissueScript);
                    String modifiedScript = UtilityFunctions.modifyTissueDetectScript(
                            tissueScript,
                            String.valueOf(macroPixelSize),
                            scriptPaths.get("jsonTissueClassfierPathString")
                    );
                    qupathGUI.runScript(null, modifiedScript);
                } catch (Exception e) {
                    UIFunctions.notifyUserOfError("Tissue detection script failed: " + e.getMessage(),
                            "Script Error");
                    logger.error("Tissue detection script error", e);
                }
                // Refresh annotations after script
                annotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                        .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                        .collect(Collectors.toList());
            }

            // If still empty, prompt the user
            if (annotations.isEmpty()) {
                logger.info("Still no annotations. Prompting user to draw/select tissue regions.");
                ExistingImageController.promptForAnnotations();
                return null;
            }
        }
        logger.info("Found {} tissue annotation(s) in image.", annotations.size());
        return annotations;
    }
}