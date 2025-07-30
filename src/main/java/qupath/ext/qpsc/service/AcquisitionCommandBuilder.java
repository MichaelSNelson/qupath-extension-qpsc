package qupath.ext.qpsc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.RotationManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized builder for acquisition commands for socket-based communication only.
 *
 * This builder creates properly formatted messages for the microscope server,
 * supporting different imaging modalities (brightfield, PPM, laser scanning, etc.)
 * without requiring changes to workflow code.
 */
public class AcquisitionCommandBuilder {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionCommandBuilder.class);

    // Required parameters
    private String yamlPath;
    private String projectsFolder;
    private String sampleLabel;
    private String scanType;
    private String regionName;

    // Optional parameters for rotation/PPM
    private List<RotationManager.TickExposure> angleExposures;

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

    public AcquisitionCommandBuilder angleExposures(List<RotationManager.TickExposure> angleExposures) {
        this.angleExposures = angleExposures;
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

        // Add required parameters with flags
        args.addAll(Arrays.asList(
                "--yaml", yamlPath,
                "--projects", projectsFolder,
                "--sample", sampleLabel,
                "--scan-type", scanType,
                "--region", regionName
        ));

        // Add optional parameters
        if (angleExposures != null && !angleExposures.isEmpty()) {
            // Format angles as parenthesized comma-separated list: (-5.0,0.0,5.0,90.0)
            String anglesStr = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.ticks))
                    .collect(Collectors.joining(",", "(", ")"));
            args.add("--angles");
            args.add(anglesStr);

            // Format exposures as parenthesized comma-separated list: (500,800,500,10)
            String exposuresStr = angleExposures.stream()
                    .map(ae -> String.valueOf(ae.exposureMs))
                    .collect(Collectors.joining(",", "(", ")"));
            args.add("--exposures");
            args.add(exposuresStr);
        }

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

        if (zStackEnabled) {
            args.add("--z-stack");
            args.addAll(Arrays.asList("--z-start", String.valueOf(zStart)));
            args.addAll(Arrays.asList("--z-end", String.valueOf(zEnd)));
            args.addAll(Arrays.asList("--z-step", String.valueOf(zStep)));
        }

        // Join with spaces, properly quoting arguments that contain spaces or special characters
        String message = args.stream()
                .map(arg -> {
                    // For Windows paths, replace backslashes with forward slashes
                    // Python handles both equally well
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
     * Creates a builder pre-configured for brightfield acquisition
     */
    public static AcquisitionCommandBuilder brightfieldBuilder(
            String yamlPath, String projectsFolder,
            String sampleLabel, String scanType, String regionName) {

        return builder()
                .yamlPath(yamlPath)
                .projectsFolder(projectsFolder)
                .sampleLabel(sampleLabel)
                .scanType(scanType)
                .regionName(regionName);
    }

    /**
     * Creates a builder pre-configured for PPM acquisition
     */
    public static AcquisitionCommandBuilder ppmBuilder(
            String yamlPath, String projectsFolder,
            String sampleLabel, String scanType, String regionName,
            List<RotationManager.TickExposure> angleExposures) {

        return builder()
                .yamlPath(yamlPath)
                .projectsFolder(projectsFolder)
                .sampleLabel(sampleLabel)
                .scanType(scanType)
                .regionName(regionName)
                .angleExposures(angleExposures);
    }

    /**
     * Creates a builder pre-configured for laser scanning acquisition
     */
    public static AcquisitionCommandBuilder laserScanningBuilder(
            String yamlPath, String projectsFolder,
            String sampleLabel, String scanType, String regionName,
            double laserPower, int wavelength, double dwellTime) {

        return builder()
                .yamlPath(yamlPath)
                .projectsFolder(projectsFolder)
                .sampleLabel(sampleLabel)
                .scanType(scanType)
                .regionName(regionName)
                .laserPower(laserPower)
                .laserWavelength(wavelength)
                .dwellTime(dwellTime);
    }
}