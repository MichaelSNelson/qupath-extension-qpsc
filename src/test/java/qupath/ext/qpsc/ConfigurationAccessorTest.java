package qupath.ext.qpsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test class to verify configuration accessor methods work correctly.
 * Run this after adding the accessor methods to ensure everything is working.
 */
public class ConfigurationAccessorTest {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationAccessorTest.class);

    public static void main(String[] args) {
        // Initialize configuration manager
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        logger.info("========== Configuration Accessor Test ==========");
        logger.info("Config path: {}", configPath);

        // Test 1: Validate configuration
        testConfigurationValidation(mgr);

        // Test 2: Test modality access
        testModalityAccess(mgr);

        // Test 3: Test stage limits
        testStageLimits(mgr);

        // Test 4: Test acquisition profiles
        testAcquisitionProfiles(mgr);

        // Test 5: Test detector and FOV
        testDetectorAndFOV(mgr);

        // Test 6: Test PPM specific features
        testPPMFeatures(mgr);

        // Test 7: Test autofocus
        testAutofocus(mgr);

        // Test 8: Test background correction
        testBackgroundCorrection(mgr);

        // Test 9: Test error handling
        testErrorHandling(mgr);

        logger.info("========== Test Complete ==========");
    }

    private static void testConfigurationValidation(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Configuration Validation ---");

        List<String> missing = mgr.validateConfiguration();
        if (missing.isEmpty()) {
            logger.info("✓ Configuration is valid");
        } else {
            logger.error("✗ Configuration has missing sections: {}", missing);
        }
    }

    private static void testModalityAccess(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Modality Access ---");

        Set<String> modalities = mgr.getAvailableModalities();
        logger.info("Found {} modalities: {}", modalities.size(), modalities);

        for (String modality : modalities) {
            boolean valid = mgr.isValidModality(modality);
            boolean isPPM = mgr.isPPMModality(modality);

            logger.info("  {} - Valid: {}, PPM: {}", modality, valid, isPPM);

            // Test modality-specific settings
            Map<String, Object> modalityConfig = mgr.getSection("modalities", modality);
            if (modalityConfig != null) {
                logger.info("    Type: {}", modalityConfig.get("type"));
            }
        }
    }

    private static void testStageLimits(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Stage Limits ---");

        Map<String, Double> limits = mgr.getAllStageLimits();
        if (limits != null) {
            logger.info("Stage limits: {}", limits);

            // Test bounds checking
            double[] testPoints = {0, 0, 0};
            boolean inBounds = mgr.isWithinStageBounds(testPoints[0], testPoints[1], testPoints[2]);
            logger.info("Position ({}, {}, {}) is {} bounds",
                    testPoints[0], testPoints[1], testPoints[2],
                    inBounds ? "within" : "outside");

            // Test extreme position
            double xMax = mgr.getStageLimit("x", "high");
            boolean outOfBounds = mgr.isWithinStageBounds(xMax + 1000, 0, 0);
            logger.info("Position ({}, 0, 0) is {} bounds (should be outside)",
                    xMax + 1000, outOfBounds ? "within" : "outside");
        } else {
            logger.error("Could not retrieve stage limits");
        }
    }

    private static void testAcquisitionProfiles(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Acquisition Profiles ---");

        // Get all profiles
        List<Object> profiles = mgr.getList("acq_profiles_new", "profiles");
        if (profiles != null) {
            logger.info("Found {} acquisition profiles", profiles.size());

            // Test a few profiles
            for (Object profileObj : profiles.subList(0, Math.min(3, profiles.size()))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = (Map<String, Object>) profileObj;

                String modality = (String) profile.get("modality");
                String objective = (String) profile.get("objective");
                String detector = (String) profile.get("detector");

                logger.info("\n  Profile: {}/{}/{}", modality, objective, detector);

                // Test pixel size
                double pixelSize = mgr.getModalityPixelSize(modality, objective, detector);
                logger.info("    Pixel size: {} µm", pixelSize);

                // Test exposures
                Map<String, Object> exposures = mgr.getModalityExposures(modality, objective, detector);
                if (exposures != null) {
                    logger.info("    Exposures: {}", exposures.keySet());
                }

                // Test gains
                Object gains = mgr.getModalityGains(modality, objective, detector);
                logger.info("    Gains type: {}", gains != null ? gains.getClass().getSimpleName() : "null");
            }
        } else {
            logger.error("No acquisition profiles found");
        }
    }

    private static void testDetectorAndFOV(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Detector and FOV ---");

        String defaultDetector = mgr.getDefaultDetector();
        logger.info("Default detector: {}",
                defaultDetector.isEmpty() ? "Not configured" : defaultDetector);

        // Test with specific detectors from profiles
        List<Object> profiles = mgr.getList("acq_profiles_new", "profiles");
        if (profiles != null && !profiles.isEmpty()) {
            // Get first profile for testing
            @SuppressWarnings("unchecked")
            Map<String, Object> firstProfile = (Map<String, Object>) profiles.get(0);

            String modality = (String) firstProfile.get("modality");
            String objective = (String) firstProfile.get("objective");
            String detector = (String) firstProfile.get("detector");

            logger.info("\nTesting detector: {}", detector);

            int[] dims = mgr.getDetectorDimensions(detector);
            if (dims != null) {
                logger.info("  Dimensions: {}x{} pixels", dims[0], dims[1]);

                double[] fov = mgr.getModalityFOV(modality, objective, detector);
                if (fov != null) {
                    logger.info("  FOV for {}/{}: {:.1f} x {:.1f} µm",
                            modality, objective, fov[0], fov[1]);
                }
            } else {
                logger.warn("  Detector dimensions not found");
            }
        }
    }

    private static void testPPMFeatures(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing PPM Features ---");

        // Find PPM modalities
        Set<String> modalities = mgr.getAvailableModalities();
        for (String modality : modalities) {
            if (mgr.isPPMModality(modality)) {
                logger.info("Testing PPM modality: {}", modality);

                List<Map<String, Object>> angles = mgr.getRotationAngles(modality);
                logger.info("  Found {} rotation angles:", angles.size());

                for (Map<String, Object> angle : angles) {
                    logger.info("    {} - Tick: {} (1 tick = 2 degrees)",
                            angle.get("name"),
                            angle.get("tick"));
                }

                // Test rotation stage
                String rotationDevice = mgr.getString("modalities", modality, "rotation_stage", "device");
                logger.info("  Rotation stage device: {}", rotationDevice);
            }
        }
    }

    private static void testAutofocus(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Autofocus Parameters ---");

        // Get objectives from defaults
        List<Object> defaults = mgr.getList("acq_profiles_new", "defaults");
        if (defaults != null && !defaults.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstDefault = (Map<String, Object>) defaults.get(0);
            String objective = (String) firstDefault.get("objective");

            logger.info("Testing autofocus for objective: {}", objective);

            Map<String, Object> afParams = mgr.getAutofocusParams(objective);
            if (afParams != null) {
                logger.info("  Full params: {}", afParams);

                Integer nSteps = mgr.getAutofocusIntParam(objective, "n_steps");
                Integer searchRange = mgr.getAutofocusIntParam(objective, "search_range_um");
                Integer nTiles = mgr.getAutofocusIntParam(objective, "n_tiles");

                logger.info("  n_steps: {}, search_range_um: {}, n_tiles: {}",
                        nSteps, searchRange, nTiles);
            } else {
                logger.warn("  No autofocus parameters found");
            }
        }
    }

    private static void testBackgroundCorrection(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Background Correction ---");

        // Test for each modality
        for (String modality : mgr.getAvailableModalities()) {
            boolean enabled = mgr.isBackgroundCorrectionEnabled(modality);
            String method = mgr.getBackgroundCorrectionMethod(modality);
            String folder = mgr.getBackgroundCorrectionFolder(modality);

            logger.info("  {} - Enabled: {}, Method: {}, Folder: {}",
                    modality, enabled, method, folder);
        }
    }

    /**
     * Test error handling by trying to access non-existent values.
     */
    private static void testErrorHandling(MicroscopeConfigManager mgr) {
        logger.info("\n--- Testing Error Handling ---");

        // Try to get pixel size for non-existent combination
        double pixelSize = mgr.getModalityPixelSize("non_existent", "fake_objective", "fake_detector");
        logger.info("Non-existent modality pixel size (should be 1.0): {}", pixelSize);

        // Try to get stage limit for invalid axis
        double limit = mgr.getStageLimit("w", "low");
        logger.info("Invalid axis limit (should be default): {}", limit);

        // Try to validate non-existent modality
        boolean valid = mgr.isValidModality("fake_modality");
        logger.info("Non-existent modality valid (should be false): {}", valid);

        // Try to get non-existent profile
        Map<String, Object> fakeProfile = mgr.getAcquisitionProfile("fake", "fake", "fake");
        logger.info("Non-existent profile (should be null): {}", fakeProfile);
    }
}