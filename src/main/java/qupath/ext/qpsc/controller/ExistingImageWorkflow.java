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
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
            boolean flippedX = QPPreferenceDialog.getFlipMacroXProperty();
            boolean flippedY = QPPreferenceDialog.getFlipMacroYProperty();
            logger.info("Importing image to project: flippedX={}, flippedY={}", flippedX, flippedY);

            // Check if we have an image open first
            var currentImageData = qupathGUI.getImageData();
            if (currentImageData == null) {
                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                        "No image is currently open. Please open an image first.",
                        "No Image"));
                return;
            }

            // Handle project creation/opening synchronously on FX thread
            Platform.runLater(() -> {
                try {
                    Map<String, Object> projectDetails;

                    if (qupathGUI.getProject() == null) {
                        logger.info("Creating new project and importing image");

                        // Extract the current image path before creating project
                        var serverPathFull = currentImageData.getServerPath();
                        logger.info(serverPathFull);
                        String imagePath = MinorFunctions.extractFilePath(serverPathFull);

                        if (imagePath == null) {
                            UIFunctions.notifyUserOfError(
                                    "Cannot extract image path from current image.",
                                    "Import Error");
                            return;
                        }

                        // Create the project first
                        Project<BufferedImage> project = QPProjectFunctions.createProject(
                                sample.projectsFolder().getAbsolutePath(),
                                sample.sampleName()
                        );

                        if (project == null) {
                            UIFunctions.notifyUserOfError(
                                    "Failed to create project.",
                                    "Project Error");
                            return;
                        }

                        // Add the image with flips
                        QPProjectFunctions.addImageToProject(
                                new File(imagePath),
                                project,
                                flippedX,
                                flippedY
                        );

                        // Set the project in QuPath
                        qupathGUI.setProject(project);

                        // Find and open the newly added image
                        var entries = project.getImageList();
                        var imageEntry = entries.stream()
                                .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                .findFirst()
                                .orElse(null);

                        if (imageEntry != null) {
                            qupathGUI.openImageEntry(imageEntry);
                            logger.info("Opened flipped image in project");
                        } else {
                            UIFunctions.notifyUserOfError(
                                    "Failed to find image in project after import.",
                                    "Import Error");
                            return;
                        }

                        // Get project details
                        projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                                sample.projectsFolder().getAbsolutePath(),
                                sample.sampleName(),
                                sample.modality()
                        );

                    } else {
                        logger.info("Project exists, checking if image needs to be added");

                        // Check if current image is in the project
                        var project = qupathGUI.getProject();
                        var projectEntry = project.getEntry(currentImageData);

                        if (projectEntry == null) {
                            // Image not in project - add it with flips
                            logger.info("Adding current image to existing project with flips");
                            String imagePath = MinorFunctions.extractFilePath(currentImageData.getServerPath());
                            if (imagePath != null) {
                                QPProjectFunctions.addImageToProject(
                                        new File(imagePath),
                                        project,
                                        flippedX,
                                        flippedY
                                );

                                // Refresh and reopen the image to apply flips
                                qupathGUI.refreshProject();

                                // Find and open the newly added entry
                                var entries = project.getImageList();
                                var newEntry = entries.stream()
                                        .filter(e -> e.getImageName().equals(new File(imagePath).getName()))
                                        .findFirst()
                                        .orElse(null);

                                if (newEntry != null) {
                                    qupathGUI.openImageEntry(newEntry);
                                    logger.info("Reopened image with flips applied");
                                }
                            }
                        } else {
                            logger.info("Image already in project");
                        }

                        // Get project information for existing project
                        projectDetails = QPProjectFunctions.getCurrentProjectInformation(
                                sample.projectsFolder().getAbsolutePath(),
                                sample.sampleName(),
                                sample.modality()
                        );
                    }

                    // Wait a moment for the image to fully load and display
                    Thread.sleep(500);

                    // Verify we have a properly loaded image before continuing
                    if (qupathGUI.getImageData() == null) {
                        UIFunctions.notifyUserOfError(
                                "No image data available after project setup. Workflow cannot continue.",
                                "Image Error");
                        return;
                    }

                    logger.info("Project setup complete, continuing workflow");
                    boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
                    boolean invertedY = QPPreferenceDialog.getInvertedYProperty();
                    // Continue with the rest of the workflow
                    continueWorkflowAfterProjectSetup(qupathGUI, projectDetails, sample, invertedX, invertedY);

                } catch (Exception e) {
                    logger.error("Failed during project setup", e);
                    UIFunctions.notifyUserOfError(
                            "Failed to create/open project: " + e.getMessage(),
                            "Project Error");
                }
            });
        }).exceptionally(ex -> {
            logger.info("Sample setup failed: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * Continues the workflow after project setup and image import is complete.
     * This method should be called on the JavaFX thread.
     */
    private static void continueWorkflowAfterProjectSetup(
            QuPathGUI qupathGUI,
            Map<String, Object> projectDetails,
            SampleSetupController.SampleSetupResult sample,
            boolean invertedX,
            boolean invertedY) {

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

                        // Refresh annotations to get the current state after user modifications
                        List<PathObject> currentAnnotations = qupathGUI.getViewer().getHierarchy().getAnnotationObjects().stream()
                                .filter(o -> o.getPathClass() != null && "Tissue".equals(o.getPathClass().getName()))
                                .collect(Collectors.toList());

                        if (currentAnnotations.isEmpty()) {
                            logger.warn("No valid annotations found after user confirmation. Aborting workflow.");
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "No valid tissue annotations found. Please create at least one annotation with 'Tissue' classification.",
                                    "No Annotations"));
                            return;
                        }

                        logger.info("Using {} annotation(s) after user confirmation", currentAnnotations.size());

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
                                            o.getPathClass().toString().contains(modalityBase))
                                    .forEach(QP::removeObject);
                            QP.fireHierarchyUpdate();
                            logger.info("Existing tiles should be removed at this point");
                            // Build tiling request with CURRENT annotations
                            TilingRequest request = new TilingRequest.Builder()
                                    .outputFolder(tempTileDirectory)
                                    .modalityName(modeWithIndex)
                                    .frameSize(frameWidthQP, frameHeightQP)
                                    .overlapPercent(overlapPercent)
                                    .annotations(currentAnnotations)  // <- USE CURRENT ANNOTATIONS
                                    .invertAxes(invertedX, invertedY)
                                    .createDetections(true)
                                    .addBuffer(true)
                                    .build();

                            TilingUtilities.createTiles(request);
                            logger.info("Created tiles for {} annotations", currentAnnotations.size());

                        } catch (IOException e) {
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Failed to create tile configurations: " + e.getMessage(),
                                    "Tiling Error"));
                            return;
                        }
// 8. Check for saved transform first
                        AffineTransform savedTransform = AffineTransformManager.loadSavedTransformFromPreferences();
                        if (savedTransform != null) {
                            logger.info("Using saved microscope alignment, skipping manual alignment");

                            // Transform tile configurations
                            try {
                                List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                                        tempTileDirectory, savedTransform);
                                logger.info("Transformed tile configurations for directories: {}", modifiedDirs);
                            } catch (IOException e) {
                                logger.error("Failed to transform tile configurations", e);
                                Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                        "Failed to transform tile coordinates: " + e.getMessage(),
                                        "Transform Error"));
                                return;
                            }

                            // Continue with rotation and acquisition
                            proceedWithRotationAndAcquisition(currentAnnotations, savedTransform, sample,
                                    project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);

                        } else {
                            // No saved transform - do manual alignment
                            logger.info("No saved transform found, proceeding with manual alignment");

                            AffineTransformationController.setupAffineTransformationAndValidationGUI(
                                            macroPixelSize, invertedX, invertedY)
                                    .thenAccept(transform -> {
                                        if (transform == null) {
                                            logger.info("User cancelled affine transform step.");
                                            return;
                                        }

                                        // Transform tile configurations
                                        try {
                                            List<String> modifiedDirs = TransformationFunctions.transformTileConfiguration(
                                                    tempTileDirectory, transform);
                                            logger.info("Transformed tile configurations for directories: {}", modifiedDirs);
                                        } catch (IOException e) {
                                            logger.error("Failed to transform tile configurations", e);
                                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                                    "Failed to transform tile coordinates: " + e.getMessage(),
                                                    "Transform Error"));
                                            return;
                                        }

                                        // Continue with rotation and acquisition
                                        proceedWithRotationAndAcquisition(currentAnnotations, transform, sample,
                                                project, tempTileDirectory, modeWithIndex, pixelSize, qupathGUI);
                                    });
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> UIFunctions.notifyUserOfError(
                            "Workflow error: " + ex.getMessage(), "Workflow Error"));
                    logger.error("Workflow failed", ex);
                    return null;
                });
    }

    private static void proceedWithRotationAndAcquisition(
            List<PathObject> annotations,
            AffineTransform transform,
            SampleSetupController.SampleSetupResult sample,
            Project<BufferedImage> project,
            String tempTileDirectory,
            String modeWithIndex,
            double pixelSize,
            QuPathGUI qupathGUI) {

        // 9. Get rotation angles based on modality
        logger.info("Checking rotation requirements for modality: {}", sample.modality());
        RotationManager rotationManager = new RotationManager(sample.modality());

        rotationManager.getRotationAngles(sample.modality())
                .thenAccept(rotationAngles -> {
                    logger.info("Rotation angles returned: {}", rotationAngles);

                    // Process annotations with already-transformed coordinates
                    processAnnotations(annotations, transform, sample, project,
                            tempTileDirectory, modeWithIndex, pixelSize,
                            qupathGUI, rotationAngles, rotationManager);
                })
                .exceptionally(ex -> {
                    logger.error("Rotation angle selection failed", ex);
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

        String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String projectsFolder = sample.projectsFolder().getAbsolutePath();

        // Launch acquisition for each annotation (with all angles handled internally)
        List<CompletableFuture<Void>> acquisitionFutures = new ArrayList<>();

        for (PathObject annotation : annotations) {
            String annotationName = annotation.getName();
            logger.info("Starting acquisition for annotation: {} with angles: {}",
                    annotationName, rotationAngles);

            CompletableFuture<Void> acquisitionFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<String> cliArgs = new ArrayList<>(List.of(
                            res.getString("command.acquisitionWorkflow"),
                            configFileLocation,
                            projectsFolder,
                            sample.sampleName(),
                            modeWithIndex,
                            annotationName
                    ));

                    // Add angles parameter if rotation is needed
                    if (rotationAngles != null && !rotationAngles.isEmpty()) {
                        String anglesStr = rotationAngles.stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ", "(", ")"));
                        cliArgs.add(anglesStr);
                    }

                    var result = CliExecutor.execComplexCommand(
                            300 * (rotationAngles != null ? rotationAngles.size() : 1), // Adjust timeout
                            "tiles done",
                            cliArgs.toArray(new String[0])
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
                if (rotationAngles != null && !rotationAngles.isEmpty()) {
                    // Stitch each angle separately
                    for (Double angle : rotationAngles) {
                        String angleSuffix = rotationManager.getAngleSuffix(sample.modality(), angle);
                        try {
                            logger.info("Queuing stitching for annotation: {} at angle: {}",
                                    annotationName, angle);
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
                            logger.info("Stitching completed for annotation: {} at angle: {}, output: {}",
                                    annotationName, angle, outPath);
                        } catch (Exception e) {
                            logger.error("Stitching failed for annotation: {} at angle: {}",
                                    annotationName, angle, e);
                            Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                    "Stitching failed for " + annotationName + " at angle " + angle +
                                            ": " + e.getMessage(),
                                    "Stitching Error"));
                        }
                    }
                } else {
                    // No rotation - single stitch
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
                                1
                        );
                        logger.info("Stitching completed for annotation: {}, output: {}",
                                annotationName, outPath);
                    } catch (Exception e) {
                        logger.error("Stitching failed for annotation: " + annotationName, e);
                        Platform.runLater(() -> UIFunctions.notifyUserOfError(
                                "Stitching failed for " + annotationName + ": " + e.getMessage(),
                                "Stitching Error"));
                    }
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
                // Return empty list instead of null to continue workflow
                return new ArrayList<>();
            }
        }

        logger.info("Found {} tissue annotation(s) in image.", annotations.size());
        return annotations;
    }
}