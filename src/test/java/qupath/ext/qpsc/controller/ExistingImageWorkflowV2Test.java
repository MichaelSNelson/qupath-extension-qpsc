package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import qupath.ext.qpsc.controller.ExistingImageWorkflowV2.WorkflowState;
import qupath.ext.qpsc.ui.RefinementSelectionController.RefinementChoice;

import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for ExistingImageWorkflowV2 workflow state.
 *
 * <p>Tests focus on WorkflowState initialization and field handling.
 * Integration tests for the full workflow would require JavaFX
 * and mock microscope connections.
 */
class ExistingImageWorkflowV2Test {

    private WorkflowState state;

    @BeforeEach
    void setUp() {
        state = new WorkflowState();
    }

    // ==================== Initial State Tests ====================

    @Test
    @DisplayName("WorkflowState initializes with empty annotations list")
    void testInitialAnnotationsEmpty() {
        assertNotNull(state.annotations);
        assertTrue(state.annotations.isEmpty());
    }

    @Test
    @DisplayName("WorkflowState initializes with empty stitching futures list")
    void testInitialStitchingFuturesEmpty() {
        assertNotNull(state.stitchingFutures);
        assertTrue(state.stitchingFutures.isEmpty());
    }

    @Test
    @DisplayName("WorkflowState defaults refinement to NONE")
    void testDefaultRefinementIsNone() {
        assertEquals(RefinementChoice.NONE, state.refinementChoice);
    }

    @Test
    @DisplayName("WorkflowState initializes slide alignment flags to false")
    void testInitialSlideAlignmentFlags() {
        assertFalse(state.useExistingSlideAlignment);
    }

    // ==================== Field Assignment Tests ====================

    @Test
    @DisplayName("WorkflowState can store transform")
    void testStoreTransform() {
        AffineTransform transform = AffineTransform.getScaleInstance(0.1, 0.1);
        state.transform = transform;

        assertNotNull(state.transform);
        assertEquals(0.1, state.transform.getScaleX(), 0.001);
    }

    @Test
    @DisplayName("WorkflowState can store angle overrides")
    void testStoreAngleOverrides() {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put("minus", 45.0);
        overrides.put("plus", 135.0);

        state.angleOverrides = overrides;

        assertNotNull(state.angleOverrides);
        assertEquals(2, state.angleOverrides.size());
        assertEquals(45.0, state.angleOverrides.get("minus"), 0.001);
    }

    @Test
    @DisplayName("WorkflowState can store pixel size")
    void testStorePixelSize() {
        state.pixelSize = 0.5;
        assertEquals(0.5, state.pixelSize, 0.001);
    }

    // ==================== V2-Specific Field Tests ====================

    @Test
    @DisplayName("WorkflowState can store modality")
    void testStoreModality() {
        state.modality = "ppm";
        assertEquals("ppm", state.modality);
    }

    @Test
    @DisplayName("WorkflowState can store objective")
    void testStoreObjective() {
        state.objective = "20x";
        assertEquals("20x", state.objective);
    }

    @Test
    @DisplayName("WorkflowState can store detector")
    void testStoreDetector() {
        state.detector = "JAI";
        assertEquals("JAI", state.detector);
    }

    @Test
    @DisplayName("WorkflowState can store existing project flag")
    void testStoreExistingProjectFlag() {
        state.isExistingProject = true;
        assertTrue(state.isExistingProject);

        state.isExistingProject = false;
        assertFalse(state.isExistingProject);
    }

    @Test
    @DisplayName("WorkflowState can store alignment confidence")
    void testStoreAlignmentConfidence() {
        state.alignmentConfidence = 0.85;
        assertEquals(0.85, state.alignmentConfidence, 0.001);
    }

    @Test
    @DisplayName("WorkflowState can store alignment source")
    void testStoreAlignmentSource() {
        state.alignmentSource = "Slide-specific (MacroSlide_001)";
        assertEquals("Slide-specific (MacroSlide_001)", state.alignmentSource);
    }

    @Test
    @DisplayName("WorkflowState can store green box params")
    void testStoreGreenBoxParams() {
        // Just test that we can assign it (detailed tests would need GreenBoxDetector)
        state.greenBoxParams = null;
        assertNull(state.greenBoxParams);
    }

    // ==================== Refinement Choice Tests ====================

    @Test
    @DisplayName("WorkflowState can set refinement to NONE")
    void testSetRefinementNone() {
        state.refinementChoice = RefinementChoice.NONE;
        assertEquals(RefinementChoice.NONE, state.refinementChoice);
    }

    @Test
    @DisplayName("WorkflowState can set refinement to SINGLE_TILE")
    void testSetRefinementSingleTile() {
        state.refinementChoice = RefinementChoice.SINGLE_TILE;
        assertEquals(RefinementChoice.SINGLE_TILE, state.refinementChoice);
    }

    @Test
    @DisplayName("WorkflowState can set refinement to FULL_MANUAL")
    void testSetRefinementFullManual() {
        state.refinementChoice = RefinementChoice.FULL_MANUAL;
        assertEquals(RefinementChoice.FULL_MANUAL, state.refinementChoice);
    }

    // ==================== Complete State Configuration Tests ====================

    @Test
    @DisplayName("WorkflowState can be fully configured for existing alignment path")
    void testFullConfigurationExistingPath() {
        // Configure for existing alignment
        state.modality = "ppm";
        state.objective = "20x";
        state.detector = "JAI";
        state.isExistingProject = true;
        state.useExistingSlideAlignment = false;
        state.alignmentConfidence = 0.85;
        state.alignmentSource = "General (Standard_Alignment)";
        state.refinementChoice = RefinementChoice.SINGLE_TILE;
        state.transform = AffineTransform.getScaleInstance(0.1, 0.1);
        state.pixelSize = 0.5;

        // Verify all fields
        assertEquals("ppm", state.modality);
        assertEquals("20x", state.objective);
        assertEquals("JAI", state.detector);
        assertTrue(state.isExistingProject);
        assertFalse(state.useExistingSlideAlignment);
        assertEquals(0.85, state.alignmentConfidence, 0.001);
        assertEquals("General (Standard_Alignment)", state.alignmentSource);
        assertEquals(RefinementChoice.SINGLE_TILE, state.refinementChoice);
        assertNotNull(state.transform);
        assertEquals(0.5, state.pixelSize, 0.001);
    }

    @Test
    @DisplayName("WorkflowState can be fully configured for manual alignment path")
    void testFullConfigurationManualPath() {
        // Configure for manual alignment
        state.modality = "BF";
        state.objective = "10x";
        state.detector = "Basler";
        state.isExistingProject = false;
        state.useExistingSlideAlignment = false;
        state.alignmentConfidence = 0.0;  // No alignment yet
        state.alignmentSource = "Unknown";
        state.refinementChoice = RefinementChoice.FULL_MANUAL;
        state.transform = null;
        state.pixelSize = 1.0;

        // Verify manual path configuration
        assertEquals("BF", state.modality);
        assertFalse(state.isExistingProject);
        assertEquals(0.0, state.alignmentConfidence, 0.001);
        assertEquals(RefinementChoice.FULL_MANUAL, state.refinementChoice);
        assertNull(state.transform);
    }

    @Test
    @DisplayName("WorkflowState can be configured for slide-specific alignment")
    void testFullConfigurationSlideSpecific() {
        // Configure for slide-specific alignment (highest confidence)
        state.modality = "ppm";
        state.objective = "20x";
        state.detector = "JAI";
        state.isExistingProject = true;
        state.useExistingSlideAlignment = true;
        state.alignmentConfidence = 0.92;
        state.alignmentSource = "Slide-specific (MacroSlide_001)";
        state.refinementChoice = RefinementChoice.NONE;  // High confidence - no refinement needed
        state.transform = AffineTransform.getScaleInstance(0.1, 0.1);
        state.pixelSize = 0.5;

        // Verify slide-specific configuration
        assertTrue(state.useExistingSlideAlignment);
        assertTrue(state.alignmentConfidence >= 0.8);
        assertEquals(RefinementChoice.NONE, state.refinementChoice);
    }
}
