package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles Path B: Manual alignment creation.
 *
 * <p>This path is used when:
 * <ul>
 *   <li>No existing alignment is available or suitable</li>
 *   <li>The user wants to create a new alignment from scratch</li>
 *   <li>No macro image is available for automatic detection</li>
 * </ul>
 *
 * <p>The workflow:
 * <ol>
 *   <li>Loads pixel size configuration</li>
 *   <li>Sets up the project</li>
 *   <li>Creates tiles for alignment</li>
 *   <li>Shows manual alignment UI</li>
 *   <li>Saves the created transform</li>
 * </ol>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class ManualAlignmentPath {
    private static final Logger logger = LoggerFactory.getLogger(ManualAlignmentPath.class);

    private final QuPathGUI gui;
    private final WorkflowState state;

    /**
     * Creates a new manual alignment path handler.
     *
     * @param gui QuPath GUI instance
     * @param state Current workflow state
     */
    public ManualAlignmentPath(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the manual alignment path workflow.
     *
     * <p>This method orchestrates the complete Path B workflow including
     * project setup and manual transform creation.
     *
     * @return CompletableFuture containing the updated workflow state
     */
    public CompletableFuture<WorkflowState> execute() {
        logger.info("Path B: Manual alignment creation");

        return loadPixelSize()
                .thenCompose(pixelSize -> {
                    state.pixelSize = pixelSize;
                    return ProjectHelper.setupProject(gui, state.sample);
                })
                .thenCompose(projectInfo -> {
                    if (projectInfo == null) {
                        throw new RuntimeException("Project setup failed");
                    }
                    state.projectInfo = projectInfo;
                    return validateAndFlipImage();
                })
                .thenCompose(validated -> {
                    if (!validated) {
                        throw new RuntimeException("Image validation and flip preparation failed");
                    }
                    return createManualAlignment();
                });
    }

    /**
     * Validates and flips the full-resolution image if needed.
     *
     * <p>This step ensures the image has the correct orientation BEFORE
     * any operations that depend on it (annotations, tile creation, manual alignment UI).
     *
     * @return CompletableFuture with true if successful, false if failed
     */
    private CompletableFuture<Boolean> validateAndFlipImage() {
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

        return ImageFlipHelper.validateAndFlipIfNeeded(gui, project, state.sample)
                .thenApply(validated -> {
                    if (validated) {
                        logger.info("Image flip validation complete - ready for manual alignment");
                    }
                    return validated;
                });
    }

    /**
     * Loads pixel size for manual alignment.
     *
     * <p>Attempts to load from:
     * <ol>
     *   <li>Saved scanner configuration if available</li>
     *   <li>MacroImageUtility fallback configuration</li>
     * </ol>
     *
     * @return CompletableFuture containing the pixel size in micrometers
     */
    private CompletableFuture<Double> loadPixelSize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Try saved scanner first
                String savedScanner = PersistentPreferences.getSelectedScanner();
                if (savedScanner != null && !savedScanner.isEmpty()) {
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    File configDir = new File(configPath).getParentFile();
                    File scannerConfigFile = new File(configDir, "config_" + savedScanner + ".yml");

                    if (scannerConfigFile.exists()) {
                        Map<String, Object> scannerConfig = MinorFunctions.loadYamlFile(
                                scannerConfigFile.getAbsolutePath());
                        Double pixelSize = MinorFunctions.getYamlDouble(
                                scannerConfig, "macro", "pixel_size_um");

                        if (pixelSize != null && pixelSize > 0) {
                            logger.info("Loaded pixel size {} from scanner config", pixelSize);
                            return pixelSize;
                        }
                    }
                }

                // Try to get from MacroImageUtility - will throw if not configured
                return MacroImageUtility.getMacroPixelSize();
            } catch (Exception e) {
                logger.error("Failed to load pixel size", e);
                throw new RuntimeException(
                        "Cannot determine macro image pixel size.\n" + e.getMessage()
                );
            }
        });
    }

    /**
     * Creates manual alignment through user interaction.
     *
     * <p>This method:
     * <ol>
     *   <li>Gets or creates annotations</li>
     *   <li>Creates tiles for visual reference</li>
     *   <li>Shows the manual alignment UI</li>
     *   <li>Saves the created transform</li>
     * </ol>
     *
     * @return CompletableFuture containing the updated workflow state
     */
    private CompletableFuture<WorkflowState> createManualAlignment() {
        // Get or create annotations
        state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize, state.selectedAnnotationClasses);

        if (state.annotations.isEmpty()) {
            throw new RuntimeException("No annotations available for manual alignment");
        }

        // Create tiles for alignment
        TileHelper.createTilesForAnnotations(
                state.annotations,
                state.sample,
                state.projectInfo.getTempTileDirectory(),
                state.projectInfo.getImagingModeWithIndex(),
                state.pixelSize
        );

        // Get axis inversion settings
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        // Show manual alignment UI
        return AffineTransformationController.setupAffineTransformationAndValidationGUI(
                state.pixelSize, invertedX, invertedY
        ).thenApply(transform -> {
            if (transform == null) {
                throw new RuntimeException("Manual alignment cancelled");
            }

            logger.info("Manual transform created successfully");

            state.transform = transform;
            MicroscopeController.getInstance().setCurrentTransform(transform);

            // Save slide-specific transform
            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();

            AffineTransformManager.saveSlideAlignment(
                    project,
                    state.sample.sampleName(),
                    state.sample.modality(),
                    transform,
                    null
            );

            logger.info("Saved slide-specific transform");
            return state;
        });
    }
}
