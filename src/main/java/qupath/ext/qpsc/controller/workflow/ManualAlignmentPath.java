package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ExistingImageWorkflow.WorkflowState;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.AffineTransformationController;
import qupath.ext.qpsc.utilities.AffineTransformManager;
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
 */
public class ManualAlignmentPath {
    private static final Logger logger = LoggerFactory.getLogger(ManualAlignmentPath.class);

    private final QuPathGUI gui;
    private final WorkflowState state;

    public ManualAlignmentPath(QuPathGUI gui, WorkflowState state) {
        this.gui = gui;
        this.state = state;
    }

    /**
     * Executes the manual alignment path workflow.
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
                    return createManualAlignment();
                });
    }

    /**
     * Loads pixel size for manual alignment.
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
                                scannerConfig, "macro", "pixelSize_um");

                        if (pixelSize != null && pixelSize > 0) {
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
     */
    private CompletableFuture<WorkflowState> createManualAlignment() {
        // Get or create annotations
        state.annotations = AnnotationHelper.ensureAnnotationsExist(gui, state.pixelSize);

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

        // Setup manual transform
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

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
            Project<BufferedImage> project = (Project<BufferedImage>) state.projectInfo.getCurrentProject();
            AffineTransformManager.saveSlideAlignment(
                    project,
                    state.sample.sampleName(),
                    state.sample.modality(),
                    transform
            );

            logger.info("Saved slide-specific transform");
            return state;
        });
    }
}