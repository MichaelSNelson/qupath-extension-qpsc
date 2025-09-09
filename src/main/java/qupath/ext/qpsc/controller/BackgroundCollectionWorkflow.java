package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.BackgroundCollectionController;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BackgroundCollectionWorkflow - Simplified workflow for acquiring flat field correction backgrounds
 *
 * <p>This workflow provides an easy way to acquire background images for flat field correction:
 * <ol>
 *   <li>User selects modality and adjusts exposure times</li>
 *   <li>User positions microscope at clean, blank area</li>
 *   <li>Backgrounds are acquired for all angles with no processing</li>
 *   <li>Images are saved in the correct folder structure with proper names</li>
 * </ol>
 *
 * <p>Key features:
 * <ul>
 *   <li>No project creation or sample acquisition tracking</li>
 *   <li>No image processing (debayering, white balance, etc.)</li>
 *   <li>Direct save to background folder structure</li>
 *   <li>Exposure time persistence to YAML config</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class BackgroundCollectionWorkflow {
    
    private static final Logger logger = LoggerFactory.getLogger(BackgroundCollectionWorkflow.class);
    
    /**
     * Main entry point for background collection workflow.
     * Shows UI for modality selection and exposure adjustment, then acquires backgrounds.
     */
    public static void run() {
        logger.info("Starting background collection workflow");
        
        Platform.runLater(() -> {
            try {
                // Show background collection dialog
                BackgroundCollectionController.showDialog()
                    .thenAccept(result -> {
                        if (result != null) {
                            logger.info("Background collection parameters received: modality={}, angles={}",
                                    result.modality(), result.angleExposures().size());
                            
                            // Execute background acquisition
                            executeBackgroundAcquisition(result.modality(), result.objective(), result.angleExposures(), 
                                    result.outputPath());
                        } else {
                            logger.info("Background collection cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in background collection dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Background Collection Error", 
                                "Failed to show background collection dialog: " + ex.getMessage()));
                        return null;
                    });
                    
            } catch (Exception e) {
                logger.error("Failed to start background collection workflow", e);
                Dialogs.showErrorMessage("Background Collection Error", 
                        "Failed to start background collection: " + e.getMessage());
            }
        });
    }
    
    /**
     * Executes the background acquisition process via socket communication.
     * 
     * @param modality The modality (e.g., "ppm")
     * @param objective The selected objective
     * @param angleExposures List of angle-exposure pairs
     * @param outputPath Base output path for background images
     */
    private static void executeBackgroundAcquisition(String modality, String objective, List<AngleExposure> angleExposures, 
            String outputPath) {
        
        logger.info("Executing background acquisition for modality '{}' with {} angles", 
                modality, angleExposures.size());
        
        // Create progress counter and show progress bar
        AtomicInteger progressCounter = new AtomicInteger(0);
        // Temporarily disable progress bar to test if it's blocking execution
        // UIFunctions.ProgressHandle progressHandle = UIFunctions.showProgressBarAsync(
        //         progressCounter,
        //         angleExposures.size(),
        //         300000, // 5 minute timeout
        //         false   // No cancel button for background collection
        // );
        UIFunctions.ProgressHandle progressHandle = null; // Temporary fix
        
        logger.info("Creating background acquisition CompletableFuture");
        // Use explicit executor to avoid common pool issues
        var executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> acquisitionFuture = CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting background acquisition async task");
                
                // Get socket client from MicroscopeController 
                MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();
                logger.info("Retrieved socket client from MicroscopeController");
                
                // Ensure connection to microscope server
                if (!MicroscopeController.getInstance().isConnected()) {
                    logger.info("Connecting to microscope server for background acquisition");
                    MicroscopeController.getInstance().connect();
                    logger.info("Connection attempt completed");
                } else {
                    logger.info("Already connected to microscope server");
                }
                logger.info("Using microscope server connection for background acquisition");
                
                // Build background acquisition command using AcquisitionCommandBuilder
                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                logger.info("Using config file: {}", configFileLocation);
                var configManager = MicroscopeConfigManager.getInstance(configFileLocation);
                logger.info("Retrieved config manager");
                
                // Use the user-selected objective
                logger.info("Using user-selected objective: {}", objective);
                
                String detector = null;
                if (objective != null) {
                    Set<String> availableDetectors = configManager.getAvailableDetectorsForModalityObjective(modality, objective);
                    logger.info("Available detectors for {}/{}: {}", modality, objective, availableDetectors);
                    detector = availableDetectors.isEmpty() ? null : availableDetectors.iterator().next();
                }
                logger.info("Selected hardware: objective={}, detector={}", objective, detector);
                
                // Create proper background folder structure: base_folder/detector/modality/magnification
                String finalOutputPath = outputPath;
                if (objective != null && detector != null) {
                    // Extract magnification from objective (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x")
                    String magnification = extractMagnificationFromObjective(objective);
                    finalOutputPath = java.nio.file.Paths.get(outputPath, detector, modality, magnification).toString();
                    logger.info("Created background folder structure: {}", finalOutputPath);
                } else {
                    logger.warn("Could not determine objective/detector for {}, using base output path", modality);
                }
                
                // Create the output directory if it doesn't exist
                java.io.File outputDir = new java.io.File(finalOutputPath);
                logger.info("Creating output directory: {}", finalOutputPath);
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    throw new IOException("Failed to create output directory: " + finalOutputPath);
                }
                logger.info("Output directory ready: {}", finalOutputPath);
                
                // Build BGACQUIRE-specific command parameters (different from regular acquisition)
                logger.info("Building BGACQUIRE command with {} angles", angleExposures.size());
                
                // Format angles and exposures for BGACQUIRE command (server expects parentheses format)
                String angles = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.ticks()))
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
                String exposures = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.exposureMs()))
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
                
                logger.info("Formatted for BGACQUIRE - angles: {}, exposures: {}", angles, exposures);
                
                logger.info("Starting background acquisition for modality '{}' with {} angles", modality, angleExposures.size());
                
                // Send BGACQUIRE command with correct parameters
                logger.info("Sending BGACQUIRE command to server");
                socketClient.startBackgroundAcquisition(configFileLocation, finalOutputPath, modality, angles, exposures);
                logger.info("Background acquisition command sent, monitoring progress");
                
                // Monitor acquisition with progress updates
                MicroscopeSocketClient.AcquisitionState finalState =
                        socketClient.monitorAcquisition(
                                // Progress callback - update the progress counter
                                progress -> {
                                    logger.debug("Background collection progress: {}", progress);
                                    progressCounter.set(progress.current);
                                },
                                500,    // Poll every 500ms
                                300000  // 5 minute timeout
                        );
                
                // Check final state
                if (finalState == MicroscopeSocketClient.AcquisitionState.COMPLETED) {
                    logger.info("Background acquisition completed successfully");
                    Platform.runLater(() -> {
                        if (progressHandle != null) progressHandle.close();
                        Dialogs.showInfoNotification("Background Collection Complete", 
                                String.format("Successfully acquired %d background images for %s modality", 
                                        angleExposures.size(), modality));
                    });
                } else if (finalState == MicroscopeSocketClient.AcquisitionState.CANCELLED) {
                    logger.warn("Background acquisition was cancelled");
                    Platform.runLater(() -> {
                        if (progressHandle != null) progressHandle.close();
                        Dialogs.showInfoNotification("Background Collection Cancelled",
                                "Background acquisition was cancelled by user request");
                    });
                } else if (finalState == MicroscopeSocketClient.AcquisitionState.FAILED) {
                    throw new RuntimeException("Background acquisition failed on server");
                } else {
                    logger.warn("Background acquisition ended in unexpected state: {}", finalState);
                }
                
            } catch (Exception e) {
                logger.error("Background acquisition failed", e);
                Platform.runLater(() -> {
                    if (progressHandle != null) progressHandle.close();
                    Dialogs.showErrorMessage("Background Acquisition Failed", 
                            "Failed to acquire background images: " + e.getMessage());
                });
            }
        });
        
        // Handle any exceptions from the CompletableFuture itself
        acquisitionFuture.exceptionally(ex -> {
            logger.error("CompletableFuture execution failed", ex);
            Platform.runLater(() -> {
                if (progressHandle != null) progressHandle.close();
                Dialogs.showErrorMessage("Background Acquisition Error", 
                        "Failed to start background acquisition: " + ex.getMessage());
            });
            return null;
        });
    }
    
    /**
     * Data class for background collection parameters.
     */
    public record BackgroundCollectionResult(
            String modality,
            String objective,
            List<AngleExposure> angleExposures,
            String outputPath
    ) {}
    
    /**
     * Extract magnification from objective identifier.
     * Examples:
     * - "LOCI_OBJECTIVE_OLYMPUS_10X_001" -> "10x"
     * - "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001" -> "20x"
     * - "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001" -> "40x"
     */
    private static String extractMagnificationFromObjective(String objective) {
        if (objective == null) return "unknown";
        
        // Look for pattern like "10X", "20X", "40X"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)X", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(objective);
        
        if (matcher.find()) {
            return matcher.group(1).toLowerCase() + "x";  // "20X" -> "20x"
        }
        
        // Fallback: return "unknown" if pattern not found
        return "unknown";
    }
}