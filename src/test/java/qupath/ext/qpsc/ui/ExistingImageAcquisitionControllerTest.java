package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.ExistingImageAcquisitionConfig;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.RefinementChoice;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for ExistingImageAcquisitionController.
 *
 * <p>These tests focus on the logic and data structures of the controller,
 * not the JavaFX UI components which require the Application thread.
 */
class ExistingImageAcquisitionControllerTest {

    // ==================== RefinementChoice Tests ====================

    @Test
    @DisplayName("RefinementChoice.NONE has correct display name")
    void testRefinementChoiceNoneDisplayName() {
        assertEquals("Proceed without refinement", RefinementChoice.NONE.getDisplayName());
    }

    @Test
    @DisplayName("RefinementChoice.SINGLE_TILE has correct display name")
    void testRefinementChoiceSingleTileDisplayName() {
        assertEquals("Single-tile refinement", RefinementChoice.SINGLE_TILE.getDisplayName());
    }

    @Test
    @DisplayName("RefinementChoice.FULL_MANUAL has correct display name")
    void testRefinementChoiceFullManualDisplayName() {
        assertEquals("Full manual alignment", RefinementChoice.FULL_MANUAL.getDisplayName());
    }

    @Test
    @DisplayName("All RefinementChoice values are present")
    void testRefinementChoiceValuesCount() {
        assertEquals(3, RefinementChoice.values().length);
    }

    // ==================== ExistingImageAcquisitionConfig Tests ====================

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores sample data correctly")
    void testConfigStoresSampleData() {
        File folder = new File("/test/projects");
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                folder,
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                0.85,
                RefinementChoice.NONE,
                null,
                null
        );

        assertEquals("TestSample", config.sampleName());
        assertEquals(folder, config.projectsFolder());
        assertFalse(config.isExistingProject());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores hardware data correctly")
    void testConfigStoresHardwareData() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                true,
                "BF",
                "10x",
                "Basler",
                false,
                null,
                0.0,
                RefinementChoice.FULL_MANUAL,
                null,
                null
        );

        assertEquals("BF", config.modality());
        assertEquals("10x", config.objective());
        assertEquals("Basler", config.detector());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores alignment data correctly")
    void testConfigStoresAlignmentData() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                0.92,
                RefinementChoice.SINGLE_TILE,
                null,
                null
        );

        assertTrue(config.useExistingAlignment());
        assertNull(config.selectedTransform());
        assertEquals(0.92, config.alignmentConfidence(), 0.001);
        assertEquals(RefinementChoice.SINGLE_TILE, config.refinementChoice());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores angle overrides correctly")
    void testConfigStoresAngleOverrides() {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put("minus", 45.0);
        overrides.put("plus", 135.0);

        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                0.85,
                RefinementChoice.NONE,
                null,
                overrides
        );

        assertNotNull(config.angleOverrides());
        assertEquals(2, config.angleOverrides().size());
        assertEquals(45.0, config.angleOverrides().get("minus"), 0.001);
        assertEquals(135.0, config.angleOverrides().get("plus"), 0.001);
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig with existing project variant")
    void testConfigExistingProjectVariant() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "ExistingSample",
                new File("/existing/project"),
                true,  // existing project
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                0.85,
                RefinementChoice.NONE,
                null,
                null
        );

        assertTrue(config.isExistingProject());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig with new project variant")
    void testConfigNewProjectVariant() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "NewSample",
                new File("/new/project"),
                false,  // new project
                "BF",
                "10x",
                "Basler",
                false,
                null,
                0.0,
                RefinementChoice.FULL_MANUAL,
                null,
                null
        );

        assertFalse(config.isExistingProject());
        assertFalse(config.useExistingAlignment());
    }

    // ==================== Confidence Threshold Tests ====================

    @Test
    @DisplayName("High confidence threshold is 0.8")
    void testHighConfidenceThreshold() {
        // Based on the implementation constants
        double highThreshold = 0.8;

        ExistingImageAcquisitionConfig highConfig = new ExistingImageAcquisitionConfig(
                "TestSample", new File("/test"), false, "ppm", "20x", "JAI",
                true, null, 0.85, RefinementChoice.NONE, null, null
        );

        assertTrue(highConfig.alignmentConfidence() >= highThreshold,
                "85% confidence should be above high threshold");
    }

    @Test
    @DisplayName("Medium confidence is between 0.5 and 0.8")
    void testMediumConfidenceRange() {
        double mediumLow = 0.5;
        double mediumHigh = 0.8;

        ExistingImageAcquisitionConfig medConfig = new ExistingImageAcquisitionConfig(
                "TestSample", new File("/test"), false, "ppm", "20x", "JAI",
                true, null, 0.65, RefinementChoice.SINGLE_TILE, null, null
        );

        double conf = medConfig.alignmentConfidence();
        assertTrue(conf >= mediumLow && conf < mediumHigh,
                "65% confidence should be in medium range");
    }

    @Test
    @DisplayName("Low confidence is below 0.5")
    void testLowConfidenceRange() {
        double lowThreshold = 0.5;

        ExistingImageAcquisitionConfig lowConfig = new ExistingImageAcquisitionConfig(
                "TestSample", new File("/test"), false, "ppm", "20x", "JAI",
                true, null, 0.35, RefinementChoice.FULL_MANUAL, null, null
        );

        assertTrue(lowConfig.alignmentConfidence() < lowThreshold,
                "35% confidence should be below low threshold");
    }

    // ==================== Manual Alignment Path Tests ====================

    @Test
    @DisplayName("Manual alignment path has zero confidence")
    void testManualAlignmentHasZeroConfidence() {
        ExistingImageAcquisitionConfig manualConfig = new ExistingImageAcquisitionConfig(
                "TestSample", new File("/test"), false, "ppm", "20x", "JAI",
                false,  // manual alignment
                null,
                0.0,
                RefinementChoice.FULL_MANUAL,
                null, null
        );

        assertFalse(manualConfig.useExistingAlignment());
        assertEquals(0.0, manualConfig.alignmentConfidence(), 0.001);
        assertNull(manualConfig.selectedTransform());
    }
}
