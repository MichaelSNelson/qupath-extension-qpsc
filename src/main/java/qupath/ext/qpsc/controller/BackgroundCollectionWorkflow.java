package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.BackgroundCollectionController;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
                            executeBackgroundAcquisition(result.modality(), result.angleExposures(), 
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
     * @param angleExposures List of angle-exposure pairs
     * @param outputPath Base output path for background images
     */
    private static void executeBackgroundAcquisition(String modality, List<AngleExposure> angleExposures, 
            String outputPath) {
        
        logger.info("Executing background acquisition for modality '{}' with {} angles", 
                modality, angleExposures.size());
        
        // Create progress counter and show progress bar
        AtomicInteger progressCounter = new AtomicInteger(0);
        UIFunctions.ProgressHandle progressHandle = UIFunctions.showProgressBarAsync(
                progressCounter,
                angleExposures.size(),
                300000, // 5 minute timeout
                false   // No cancel button for background collection
        );
        
        CompletableFuture.runAsync(() -> {
            try (var socketClient = new MicroscopeSocketClient()) {
                
                // Connect to microscope server
                socketClient.connect();
                logger.info("Connected to microscope server for background acquisition");
                
                // Build background acquisition command
                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                var configManager = MicroscopeConfigManager.getInstance(configFileLocation);
                String yamlPath = configFileLocation;
                
                // Format angles and exposures for Python
                var angles = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.ticks()))
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
                        
                var exposures = angleExposures.stream()
                        .map(ae -> String.valueOf(ae.exposureMs()))
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
                
                // Send background acquisition command to Python using BGACQUIRE
                String command = String.format(
                        "--yaml %s --output %s --modality %s --angles %s --exposures %s",
                        yamlPath, outputPath, modality, angles, exposures
                );
                
                logger.info("Sending background acquisition command: {}", command);
                
                // Send BGACQUIRE command followed by the message with END_MARKER
                socketClient.sendExtendedCommand("BGACQUIRE");
                socketClient.sendMessage(command + " END_MARKER");
                
                // Monitor progress
                socketClient.monitorProgress((current, total) -> {
                    progressCounter.set(current);
                    logger.debug("Background collection progress: {}/{}", current, total);
                }, () -> false); // No cancellation for now
                
                logger.info("Background acquisition completed successfully");
                
                Platform.runLater(() -> {
                    progressHandle.close();
                    Dialogs.showInfoNotification("Background Collection Complete", 
                            String.format("Successfully acquired %d background images for %s modality", 
                                    angleExposures.size(), modality));
                });
                
            } catch (Exception e) {
                logger.error("Background acquisition failed", e);
                Platform.runLater(() -> {
                    progressHandle.close();
                    Dialogs.showErrorMessage("Background Acquisition Failed", 
                            "Failed to acquire background images: " + e.getMessage());
                });
            }
        });
    }
    
    /**
     * Data class for background collection parameters.
     */
    public record BackgroundCollectionResult(
            String modality,
            List<AngleExposure> angleExposures,
            String outputPath
    ) {}
}