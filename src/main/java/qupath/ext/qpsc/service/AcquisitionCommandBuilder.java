package qupath.ext.qpsc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.utilities.ObjectiveUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized builder for acquisition commands for socket-based communication.
 *
 * This builder creates properly formatted messages for the microscope server,
 * supporting different imaging modalities without requiring Python to read
 * configuration files.
 *
 * @author Mike Nelson
 * @version 2.0
 */
public class AcquisitionCommandBuilder {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionCommandBuilder.class);

    // Required parameters
    private String yamlPath;
    private String projectsFolder;
    private String sampleLabel;
    private String scanType;
    private String regionName;

    // Optional parameters for repeated acquisitions (e.g., rotation angles)
    private List<AngleExposure> angleExposures;

    // Background correction parameters
    private boolean backgroundCorrectionEnabled = false;
    private String backgroundCorrectionMethod;
    private String backgroundCorrectionFolder;

    // White balance parameters
    private boolean whiteBalanceEnabled = true; // Default enabled for backward compatibility

    // Autofocus parameters
    private Integer autofocusNTiles;
    private Integer autofocusNSteps;
    private Double autofocusSearchRange;

    // Hardware parameters
    private String objective;
    private String detector;
    private Double pixelSize;

    // Image processing pipeline
    private List<String> processingSteps = new ArrayList<>();
    private boolean debayerEnabled = false;

    // Optional parameters for laser scanning
    private Double laserPower;
    private Integer laserWavelength;
    private Double dwellTime;
    private Integer averaging;

    // Optional parameters for Z-stack
    private boolean zStackEnabled;
    private Double zStart;
    private Double zEnd;
    private Double zStep;

    /**
     * Private constructor - use static builder() method
     */
    private AcquisitionCommandBuilder() {}

    /**
     * Creates a new command builder instance
     */
    public static AcquisitionCommandBuilder builder() {
        return new AcquisitionCommandBuilder();
    }

    // Required parameter setters

    public AcquisitionCommandBuilder yamlPath(String yamlPath) {
        this.yamlPath = yamlPath;
        return this;
    }

    public AcquisitionCommandBuilder projectsFolder(String projectsFolder) {
        this.projectsFolder = projectsFolder;
        return this;
    }

    public AcquisitionCommandBuilder sampleLabel(String sampleLabel) {
        this.sampleLabel = sampleLabel;
        return this;
    }

    public AcquisitionCommandBuilder scanType(String scanType) {
        this.scanType = scanType;
        return this;
    }

    public AcquisitionCommandBuilder regionName(String regionName) {
        this.regionName = regionName;
        return this;
    }

    // Optional parameter setters

    /**
     * Sets the angle-exposure pairs for multi-angle acquisition sequences.
     * 
     * <p>This method configures the rotation angles and their associated decimal exposure times
     * for modalities that require multiple acquisitions at different polarizer or rotation positions.
     * The angles and exposures are formatted into separate comma-separated lists in the socket message.</p>
     * 
     * @param angleExposures list of angle-exposure pairs with decimal precision exposure times
     * @return this builder instance for method chaining
     * @see AngleExposure
     */
    public AcquisitionCommandBuilder angleExposures(List<AngleExposure> angleExposures) {
        this.angleExposures = angleExposures;
        return this;
    }

    /**
     * Configure background correction
     * @param enabled Whether background correction is enabled
     * @param method Correction method ("divide" or "subtract")
     * @param folder Path to background images folder
     */
    public AcquisitionCommandBuilder backgroundCorrection(boolean enabled, String method, String folder) {
        this.backgroundCorrectionEnabled = enabled;
        this.backgroundCorrectionMethod = method;
        this.backgroundCorrectionFolder = folder;
        if (enabled && !processingSteps.contains("background_correction")) {
            processingSteps.add("background_correction");
        }
        return this;
    }

    /**
     * Configure white balance
     * @param enabled Whether white balance is enabled
     */
    public AcquisitionCommandBuilder whiteBalance(boolean enabled) {
        this.whiteBalanceEnabled = enabled;
        if (enabled && !processingSteps.contains("white_balance")) {
            processingSteps.add("white_balance");
        }
        return this;
    }

    /**
     * Configure autofocus parameters
     * @param nTiles Number of tiles for autofocus grid
     * @param nSteps Number of Z steps for focus search
     * @param searchRange Search range in microns
     */
    public AcquisitionCommandBuilder autofocus(int nTiles, int nSteps, double searchRange) {
        this.autofocusNTiles = nTiles;
        this.autofocusNSteps = nSteps;
        this.autofocusSearchRange = searchRange;
        return this;
    }

    /**
     * Configure hardware parameters
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @param detector Detector identifier (e.g., "LOCI_DETECTOR_JAI_001")
     * @param pixelSize Pixel size in microns
     */
    public AcquisitionCommandBuilder hardware(String objective, String detector, double pixelSize) {
        this.objective = objective;
        this.detector = detector;
        this.pixelSize = pixelSize;
        return this;
    }

    /**
     * Enable debayering in the processing pipeline
     */
    public AcquisitionCommandBuilder enableDebayer(boolean enable) {
        this.debayerEnabled = enable;
        if (enable && !processingSteps.contains("debayer")) {
            processingSteps.add(0, "debayer"); // Debayer should be first
        }
        return this;
    }

    /**
     * Set custom processing pipeline order
     * @param steps List of processing steps in order (e.g., ["debayer", "background_correction"])
     */
    public AcquisitionCommandBuilder processingPipeline(List<String> steps) {
        this.processingSteps = new ArrayList<>(steps);
        return this;
    }

    public AcquisitionCommandBuilder laserPower(double laserPower) {
        this.laserPower = laserPower;
        return this;
    }

    public AcquisitionCommandBuilder laserWavelength(int wavelength) {
        this.laserWavelength = wavelength;
        return this;
    }

    public AcquisitionCommandBuilder dwellTime(double dwellTime) {
        this.dwellTime = dwellTime;
        return this;
    }

    public AcquisitionCommandBuilder averaging(int averaging) {
        this.averaging = averaging;
        return this;
    }

    public AcquisitionCommandBuilder enableZStack(double start, double end, double step) {
        this.zStackEnabled = true;
        this.zStart = start;
        this.zEnd = end;
        this.zStep = step;
        return this;
    }

    /**
     * Gets the enhanced scan type that includes magnification from the objective.
     * If no objective is set or magnification cannot be extracted, returns the original scanType.
     * 
     * @return Enhanced scan type with magnification (e.g., "ppm_20x_1")
     */
    public String getEnhancedScanType() {
        if (objective == null) {
            logger.debug("No objective set, using original scan type: {}", scanType);
            return scanType;
        }
        
        return ObjectiveUtils.createEnhancedFolderName(scanType, objective);
    }

    /**
     * Validates that all required parameters are set
     */
    private void validate() {
        List<String> missing = new ArrayList<>();

        if (yamlPath == null || yamlPath.isEmpty()) missing.add("yamlPath");
        if (projectsFolder == null || projectsFolder.isEmpty()) missing.add("projectsFolder");
        if (sampleLabel == null || sampleLabel.isEmpty()) missing.add("sampleLabel");
        if (scanType == null || scanType.isEmpty()) missing.add("scanType");
        if (regionName == null || regionName.isEmpty()) missing.add("regionName");

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required parameters: " + String.join(", ", missing));
        }
    }

    /**
     * Builds the message string for socket communication
     * @return Message string with flag-based format
     */
    public String buildSocketMessage() {
        validate();

        List<String> args = new ArrayList<>();

        // Add required parameters with flags (use enhanced scan type with magnification)
        String enhancedScanType = getEnhancedScanType();
        args.addAll(Arrays.asList(
                "--yaml", yamlPath,
                "--projects", projectsFolder,
                "--sample", sampleLabel,
                "--scan-type", enhancedScanType,
                "--region", regionName
        ));

        // Add hardware parameters (should be before optional params)
        if (objective != null && detector != null && pixelSize != null) {
            args.addAll(Arrays.asList(
                    "--objective", objective,
                    "--detector", detector,
                    "--pixel-size", String.valueOf(pixelSize)
            ));
        }

        // Add angle/exposure parameters
        if (angleExposures != null && !angleExposures.isEmpty()) {
            // Format angles as parenthesized comma-separated list
            String anglesStr = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.ticks()))
                    .collect(Collectors.joining(",", "(", ")"));
            args.add("--angles");
            args.add(anglesStr);

            // Format exposures as parenthesized comma-separated list
            String exposuresStr = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.exposureMs()))
                    .collect(Collectors.joining(",", "(", ")"));
            args.add("--exposures");
            args.add(exposuresStr);
        }

        // Add background correction parameters
        if (backgroundCorrectionEnabled) {
            args.addAll(Arrays.asList("--bg-correction", "true"));
            if (backgroundCorrectionMethod != null) {
                args.addAll(Arrays.asList("--bg-method", backgroundCorrectionMethod));
            }
            if (backgroundCorrectionFolder != null) {
                args.addAll(Arrays.asList("--bg-folder", backgroundCorrectionFolder));
            }
        }

        // Add white balance parameter
        args.addAll(Arrays.asList("--white-balance", String.valueOf(whiteBalanceEnabled)));

        // Add autofocus parameters
        if (autofocusNTiles != null && autofocusNSteps != null && autofocusSearchRange != null) {
            args.addAll(Arrays.asList(
                    "--af-tiles", String.valueOf(autofocusNTiles),
                    "--af-steps", String.valueOf(autofocusNSteps),
                    "--af-range", String.valueOf(autofocusSearchRange)
            ));
        }

        // Add processing pipeline
        if (!processingSteps.isEmpty()) {
            String pipelineStr = processingSteps.stream()
                    .collect(Collectors.joining(",", "(", ")"));
            args.addAll(Arrays.asList("--processing", pipelineStr));
        }

        // Add laser scanning parameters
        if (laserPower != null) {
            args.addAll(Arrays.asList("--laser-power", String.valueOf(laserPower)));
        }

        if (laserWavelength != null) {
            args.addAll(Arrays.asList("--laser-wavelength", String.valueOf(laserWavelength)));
        }

        if (dwellTime != null) {
            args.addAll(Arrays.asList("--dwell-time", String.valueOf(dwellTime)));
        }

        if (averaging != null && averaging > 1) {
            args.addAll(Arrays.asList("--averaging", String.valueOf(averaging)));
        }

        // Add Z-stack parameters
        if (zStackEnabled) {
            args.add("--z-stack");
            args.addAll(Arrays.asList("--z-start", String.valueOf(zStart)));
            args.addAll(Arrays.asList("--z-end", String.valueOf(zEnd)));
            args.addAll(Arrays.asList("--z-step", String.valueOf(zStep)));
        }

        // Join with spaces, properly quoting arguments
        String message = args.stream()
                .map(arg -> {
                    // For Windows paths, replace backslashes with forward slashes
                    if (arg.contains("\\")) {
                        arg = arg.replace("\\", "/");
                    }

                    // Quote arguments that contain spaces or special characters
                    if (arg.contains(" ") || arg.contains("(") || arg.contains(")") || arg.contains(",")) {
                        return "\"" + arg + "\"";
                    }
                    return arg;
                })
                .collect(Collectors.joining(" "));

        logger.info("Built socket message: {}", message);
        return message;
    }

    /**
     * Creates a builder pre-configured for PPM (polarized) acquisition
     */
    public static AcquisitionCommandBuilder ppmBuilder() {
        return builder()
                .enableDebayer(true) // PPM typically needs debayering
                .processingPipeline(Arrays.asList("debayer", "background_correction"));
    }

    /**
     * Creates a builder pre-configured for brightfield acquisition
     */
    public static AcquisitionCommandBuilder brightfieldBuilder() {
        return builder()
                .enableDebayer(true)
                .processingPipeline(Arrays.asList("debayer", "background_correction"));
    }

    /**
     * Creates a builder pre-configured for laser scanning acquisition
     */
    public static AcquisitionCommandBuilder laserScanningBuilder() {
        return builder()
                .enableDebayer(false); // Laser scanning typically doesn't need debayering
    }
}