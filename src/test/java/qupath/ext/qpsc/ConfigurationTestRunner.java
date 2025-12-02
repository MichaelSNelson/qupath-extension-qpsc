package qupath.ext.qpsc.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import static org.junit.jupiter.api.Assertions.*;

import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for MicroscopeConfigManager using template configuration files.
 * This test class creates temporary config files for isolated testing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigurationTestRunner {
    
    private MicroscopeConfigManager configManager;
    private Path tempDir;
    private Path configPath;
    private Path resourcesPath;
    
    @BeforeAll
    public void setUp() throws IOException {
        // Create temporary directory for test files
        tempDir = Files.createTempDirectory("qpsc_test_");
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        
        // Write test configuration files
        configPath = tempDir.resolve("config_Test.yml");
        resourcesPath = resourcesDir.resolve("resources_LOCI.yml");
        
        writeTestConfig(configPath);
        writeTestResources(resourcesPath);
        
        // Initialize config manager
        configManager = MicroscopeConfigManager.getInstance(configPath.toString());
    }
    
    @Test
    public void testBasicConfiguration() {
        assertNotNull(configManager, "Config manager should be initialized");
        
        // Test basic microscope info
        assertEquals("TestMicroscope", configManager.getMicroscopeName());
        assertEquals("TestSystem", configManager.getMicroscopeType());
        
        // Test slide size
        int[] slideSize = configManager.getSlideSize();
        assertNotNull(slideSize);
        assertEquals(25000, slideSize[0]);
        assertEquals(15000, slideSize[1]);
    }
    
    @Test
    public void testModalities() {
        Set<String> modalities = configManager.getAvailableModalities();
        assertEquals(2, modalities.size());
        assertTrue(modalities.contains("test_brightfield"));
        assertTrue(modalities.contains("test_ppm"));
        
        // Test modality validation
        assertTrue(configManager.isValidModality("test_brightfield"));
        assertTrue(configManager.isValidModality("test_ppm"));
        assertFalse(configManager.isValidModality("non_existent"));
        
        // Test PPM detection
        assertTrue(configManager.isPPMModality("test_ppm"));
        assertFalse(configManager.isPPMModality("test_brightfield"));
    }
    
    @Test
    public void testAcquisitionProfiles() {
        // Test profile lookup
        Map<String, Object> profile = configManager.getAcquisitionProfile(
            "test_brightfield", 
            "LOCI_OBJECTIVE_TEST_10X_001", 
            "LOCI_DETECTOR_TEST_001"
        );
        assertNotNull(profile);
        assertEquals("test_brightfield", profile.get("modality"));
        
        // Test pixel size
        double pixelSize = configManager.getModalityPixelSize(
            "test_brightfield",
            "LOCI_OBJECTIVE_TEST_10X_001",
            "LOCI_DETECTOR_TEST_001"
        );
        assertEquals(1.0, pixelSize, 0.001);
        
        // Test exposures
        Map<String, Object> exposures = configManager.getModalityExposures(
            "test_brightfield",
            "LOCI_OBJECTIVE_TEST_10X_001",
            "LOCI_DETECTOR_TEST_001"
        );
        assertNotNull(exposures);
        assertEquals(100, ((Number) exposures.get("single")).intValue());
        
        // Test gains
        Object gains = configManager.getModalityGains(
            "test_brightfield",
            "LOCI_OBJECTIVE_TEST_10X_001",
            "LOCI_DETECTOR_TEST_001"
        );
        assertNotNull(gains);
        assertEquals(1.0, ((Number) gains).doubleValue(), 0.001);
    }
    
    @Test
    public void testAutofocus() {
        Map<String, Object> afParams = configManager.getAutofocusParams("LOCI_OBJECTIVE_TEST_10X_001");
        assertNotNull(afParams);
        
        Integer nSteps = configManager.getAutofocusIntParam("LOCI_OBJECTIVE_TEST_10X_001", "n_steps");
        assertEquals(5, nSteps);
        
        Integer searchRange = configManager.getAutofocusIntParam("LOCI_OBJECTIVE_TEST_10X_001", "search_range_um");
        assertEquals(20, searchRange);
        
        Integer nTiles = configManager.getAutofocusIntParam("LOCI_OBJECTIVE_TEST_10X_001", "n_tiles");
        assertEquals(3, nTiles);
    }
    
    @Test
    public void testStageLimits() {
        // Test individual limits
        assertEquals(-10000.0, configManager.getStageLimit("x", "low"), 0.001);
        assertEquals(10000.0, configManager.getStageLimit("x", "high"), 0.001);
        
        // Test bounds checking
        assertTrue(configManager.isWithinStageBounds(0, 0, 0));
        assertFalse(configManager.isWithinStageBounds(15000, 0, 0));
        assertFalse(configManager.isWithinStageBounds(0, 0, 2000));
        
        // Test all limits
        Map<String, Double> allLimits = configManager.getAllStageLimits();
        assertNotNull(allLimits);
        assertEquals(6, allLimits.size());
    }
    
    @Test
    public void testDetectorAccess() {
        // Test detector dimensions
        int[] dims = configManager.getDetectorDimensions("LOCI_DETECTOR_TEST_001");
        assertNotNull(dims);
        assertEquals(1024, dims[0]);
        assertEquals(768, dims[1]);
        
        // Test FOV calculation
        double[] fov = configManager.getModalityFOV(
            "test_brightfield",
            "LOCI_OBJECTIVE_TEST_10X_001",
            "LOCI_DETECTOR_TEST_001"
        );
        assertNotNull(fov);
        assertEquals(1024.0, fov[0], 0.001); // 1024 pixels * 1.0 µm/pixel
        assertEquals(768.0, fov[1], 0.001);  // 768 pixels * 1.0 µm/pixel
    }
    
    @Test
    public void testBackgroundCorrection() {
        // Test brightfield background correction
        assertTrue(configManager.isBackgroundCorrectionEnabled("test_brightfield"));
        assertEquals("subtract", configManager.getBackgroundCorrectionMethod("test_brightfield"));
        assertEquals("/test/backgrounds", configManager.getBackgroundCorrectionFolder("test_brightfield"));
        
        // Test PPM background correction
        assertFalse(configManager.isBackgroundCorrectionEnabled("test_ppm"));
    }
    
    @Test
    public void testPPMFeatures() {
        List<Map<String, Object>> angles = configManager.getRotationAngles("test_ppm");
        assertNotNull(angles);
        assertEquals(2, angles.size());
        
        // Check first angle
        Map<String, Object> crossed = angles.get(0);
        assertEquals("crossed", crossed.get("name"));
        assertEquals(0, ((Number) crossed.get("tick")).intValue());
    }
    
    @Test
    public void testResourceAccess() {
        // Test detector resource access
        Map<String, Object> detectorSection = configManager.getResourceSection("id_detector");
        assertNotNull(detectorSection);
        assertTrue(detectorSection.containsKey("LOCI_DETECTOR_TEST_001"));
        
        // Test objective resource access
        Map<String, Object> objectiveSection = configManager.getResourceSection("id_objective_lens");
        assertNotNull(objectiveSection);
        assertTrue(objectiveSection.containsKey("LOCI_OBJECTIVE_TEST_10X_001"));
    }
    
    @Test
    public void testConfigurationValidation() {
        List<String> validationErrors = configManager.validateConfiguration();
        assertTrue(validationErrors.isEmpty(), "Should have no validation errors");
    }
    
    @Test
    public void testErrorHandling() {
        // Test non-existent profile
        Map<String, Object> nullProfile = configManager.getAcquisitionProfile(
            "fake", "fake", "fake"
        );
        assertNull(nullProfile);
        
        // Test default pixel size for non-existent profile
        double defaultPixelSize = configManager.getModalityPixelSize(
            "fake", "fake", "fake"
        );
        assertEquals(1.0, defaultPixelSize, 0.001);
        
        // Test invalid stage axis
        double invalidLimit = configManager.getStageLimit("invalid", "low");
        assertEquals(-20000.0, invalidLimit, 0.001); // Should return default
    }
    
    private void writeTestConfig(Path path) throws IOException {
        String config = getTestConfigContent();
        Files.writeString(path, config);
    }
    
    private void writeTestResources(Path path) throws IOException {
        String resources = getTestResourcesContent();
        Files.writeString(path, resources);
    }
    
    private String getTestConfigContent() {
        // Returns the content from config_Test.yml artifact
        return """
# ========== TEST MICROSCOPE CONFIGURATION ==========
# Minimal configuration for unit testing

microscope:
  name: 'TestMicroscope'
  type: 'TestSystem'
  detector_in_use: null
  objective_in_use: null
  modality: null

# ========== MODALITIES ==========
modalities:
  test_brightfield:
    type: 'brightfield'
    background_correction:
      enabled: true
      method: 'subtract'
      base_folder: "/test/backgrounds"
      
  test_ppm:
    type: 'polarized'
    rotation_stage:
      device: 'LOCI_STAGE_TEST_ROT_001'
      type: 'polarizer'
    rotation_angles:
      - name: 'crossed'
        tick: 0   # 1 tick = 2 degrees
      - name: 'uncrossed'
        tick: 45  # 1 tick = 2 degrees
    background_correction:
      enabled: false
      method: 'divide'
      base_folder: "/test/backgrounds"

# ========== ACQUISITION PROFILES ==========
acq_profiles:
  defaults:
    - objective: 'LOCI_OBJECTIVE_TEST_10X_001'
      settings:
        autofocus:
          n_steps: 5
          search_range_um: 20
          n_tiles: 3
        pixel_size_xy_um:
          LOCI_DETECTOR_TEST_001: 1.0
          LOCI_DETECTOR_TEST_002: 0.5
          
    - objective: 'LOCI_OBJECTIVE_TEST_20X_001'
      settings:
        autofocus:
          n_steps: 7
          search_range_um: 10
          n_tiles: 5
        pixel_size_xy_um:
          LOCI_DETECTOR_TEST_001: 0.5
          LOCI_DETECTOR_TEST_002: 0.25

  profiles:
    # Brightfield profiles
    - modality: 'test_brightfield'
      objective: 'LOCI_OBJECTIVE_TEST_10X_001'
      detector: 'LOCI_DETECTOR_TEST_001'

    - modality: 'test_brightfield'
      objective: 'LOCI_OBJECTIVE_TEST_20X_001'
      detector: 'LOCI_DETECTOR_TEST_001'

    # PPM profiles
    - modality: 'test_ppm'
      objective: 'LOCI_OBJECTIVE_TEST_10X_001'
      detector: 'LOCI_DETECTOR_TEST_002'

# ========== STAGE CONFIGURATION ==========
stage:
  stage_id: 'LOCI_STAGE_TEST_XYZ_001'
  limits:
    x_um:
      low: -10000
      high: 10000
    y_um:
      low: -5000
      high: 5000
    z_um:
      low: -1000
      high: 1000

# ========== GENERAL SETTINGS ==========
slide_size_um:
  x: 25000
  y: 15000
""";
    }
    
    private String getTestResourcesContent() {
        // Returns the content from test_resources_LOCI.yml artifact
        return """
# ========== TEST RESOURCES FOR UNIT TESTING ==========

id_stage:
    LOCI_STAGE_TEST_XYZ_001:
        name: 'Test XYZ Stage'
        vendor: 'TestVendor'
        serial: 'TEST123'
        type: 'xyz'
        devices:
            x: 'TestXStage'
            y: 'TestYStage'
            z: 'TestZStage'
            f: null
            
    LOCI_STAGE_TEST_ROT_001:
        name: 'Test Rotation Stage'
        vendor: 'TestVendor'
        model: 'TestRotator'
        serial: 'ROT123'
        type: 'rotational motor'
        device: 'TestRotationStage'
        units: 'ticks'  # 1 tick = 2 degrees
        resolution: 0.5  # degrees per tick

id_detector:
    LOCI_DETECTOR_TEST_001:
        name: 'Test Camera 1'
        manufacturer: 'TestCam'
        width_px: 1024
        height_px: 768
        sensor_pixel_width_um: 5.0
        sensor_pixel_height_um: 5.0
        description: 'Basic test camera'
        
    LOCI_DETECTOR_TEST_002:
        name: 'Test Camera 2'
        manufacturer: 'TestCam'
        width_px: 2048
        height_px: 1536
        sensor_pixel_width_um: 3.5
        sensor_pixel_height_um: 3.5
        description: 'High-res test camera'

id_objective_lens:
    LOCI_OBJECTIVE_TEST_10X_001:
        name: 'Test 10x Objective'
        magnification: 10.0
        na: 0.3
        wd_um: 5000
        description: 'Test 10x objective for unit testing'
        manufacturer_id: 'TEST10X'
        on_scope: 'TestMicroscope'
        
    LOCI_OBJECTIVE_TEST_20X_001:
        name: 'Test 20x Objective'
        magnification: 20.0
        na: 0.5
        wd_um: 2000
        description: 'Test 20x objective for unit testing'
        manufacturer_id: 'TEST20X'
        on_scope: 'TestMicroscope'
""";
    }
}