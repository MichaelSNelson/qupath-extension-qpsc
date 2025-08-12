package qupath.ext.qpsc.modalities.common;

import javafx.scene.Node;
import qupath.ext.qpsc.utilities.RotationManager;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for handling different imaging modalities.
 * Each modality (PPM, brightfield, laser, etc.) implements this interface
 * to provide modality-specific behavior.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public interface ModalityHandler {

    /**
     * Determines if this handler can handle the given modality name.
     *
     * @param modalityName The imaging modality (e.g., "BF_10x", "PPM_20x", "LASER_40x")
     * @return true if this handler should handle the modality
     */
    boolean handles(String modalityName);

    /**
     * Gets the modality prefix this handler manages (e.g., "PPM", "BF", "LASER").
     *
     * @return The modality prefix
     */
    String getModalityPrefix();

    /**
     * Determines if this modality requires rotation/angle control.
     *
     * @return true if rotation is needed (e.g., PPM), false otherwise
     */
    boolean requiresRotation();

    /**
     * Gets rotation angles with exposure times if this modality uses rotation.
     * Returns empty list for non-rotating modalities.
     *
     * @param modalityName The specific modality variant
     * @param configManager The microscope configuration
     * @return CompletableFuture with list of rotation angles and exposures
     */
    CompletableFuture<List<RotationManager.TickExposure>> getRotationAngles(
            String modalityName,
            MicroscopeConfigManager configManager);

    /**
     * Creates a configuration UI panel for this modality.
     * Used in dialogs like BoundingBoxController.
     *
     * @param configManager The microscope configuration
     * @return A JavaFX Node containing the configuration UI, or null if no UI needed
     */
    Node createConfigurationUI(MicroscopeConfigManager configManager);

    /**
     * Validates that the microscope configuration has all required settings
     * for this modality.
     *
     * @param modalityName The specific modality variant
     * @param configManager The microscope configuration
     * @return true if configuration is valid, false otherwise
     */
    boolean validateConfiguration(String modalityName, MicroscopeConfigManager configManager);

    /**
     * Gets default parameters for this modality.
     * These might include exposure times, laser power, etc.
     *
     * @return Map of parameter names to default values
     */
    Map<String, Object> getDefaultParameters();

    /**
     * Enhances an acquisition command builder with modality-specific parameters.
     *
     * @param builder The command builder to enhance
     * @param modalityName The specific modality variant
     * @param configManager The microscope configuration
     * @param userParameters Optional user-specified parameters (may override defaults)
     * @return The enhanced builder
     */
    AcquisitionCommandBuilder enhanceAcquisitionCommand(
            AcquisitionCommandBuilder builder,
            String modalityName,
            MicroscopeConfigManager configManager,
            Map<String, Object> userParameters);

    /**
     * Gets a descriptive name for this modality type.
     *
     * @return Human-readable modality name (e.g., "Polarized Light Microscopy")
     */
    String getDisplayName();

    /**
     * Gets the file naming suffix for images acquired with specific parameters.
     * For example, PPM might return "_p5" for +5 degree angle.
     *
     * @param parameters The acquisition parameters
     * @return Suffix string or empty string if no suffix needed
     */
    default String getFileSuffix(Map<String, Object> parameters) {
        return "";
    }
}