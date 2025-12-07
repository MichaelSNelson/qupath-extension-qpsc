package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import qupath.ext.qpsc.ui.RefinementSelectionController.RefinementChoice;
import qupath.ext.qpsc.ui.RefinementSelectionController.RefinementResult;
import qupath.ext.qpsc.ui.RefinementSelectionController.AlignmentInfo;

/**
 * Unit tests for RefinementSelectionController.
 *
 * <p>These tests focus on the logic components that don't require JavaFX:
 * <ul>
 *   <li>RefinementChoice enum</li>
 *   <li>RefinementResult record</li>
 *   <li>AlignmentInfo record and confidence calculations</li>
 * </ul>
 */
class RefinementSelectionControllerTest {

    // Confidence thresholds matching the controller
    private static final double HIGH_CONFIDENCE = 0.8;
    private static final double MEDIUM_CONFIDENCE = 0.5;

    // ==================== RefinementChoice Tests ====================

    @Test
    @DisplayName("RefinementChoice enum has all expected values")
    void testRefinementChoiceValues() {
        RefinementChoice[] values = RefinementChoice.values();
        assertEquals(3, values.length);
        assertNotNull(RefinementChoice.valueOf("NONE"));
        assertNotNull(RefinementChoice.valueOf("SINGLE_TILE"));
        assertNotNull(RefinementChoice.valueOf("FULL_MANUAL"));
    }

    @ParameterizedTest
    @CsvSource({
            "NONE, Proceed without refinement",
            "SINGLE_TILE, Single-tile refinement",
            "FULL_MANUAL, Full manual alignment"
    })
    @DisplayName("RefinementChoice display names are correct")
    void testRefinementChoiceDisplayNames(String choice, String expectedName) {
        assertEquals(expectedName, RefinementChoice.valueOf(choice).getDisplayName());
    }

    // ==================== RefinementResult Tests ====================

    @Test
    @DisplayName("RefinementResult stores choice correctly")
    void testRefinementResultStoresChoice() {
        RefinementResult result = new RefinementResult(RefinementChoice.SINGLE_TILE, false);
        assertEquals(RefinementChoice.SINGLE_TILE, result.choice());
    }

    @Test
    @DisplayName("RefinementResult tracks auto-selection state")
    void testRefinementResultAutoSelected() {
        RefinementResult autoResult = new RefinementResult(RefinementChoice.NONE, true);
        assertTrue(autoResult.wasAutoSelected());

        RefinementResult userResult = new RefinementResult(RefinementChoice.NONE, false);
        assertFalse(userResult.wasAutoSelected());
    }

    @Test
    @DisplayName("RefinementResult with each choice type")
    void testRefinementResultAllChoices() {
        for (RefinementChoice choice : RefinementChoice.values()) {
            RefinementResult result = new RefinementResult(choice, true);
            assertEquals(choice, result.choice());
            assertTrue(result.wasAutoSelected());
        }
    }

    // ==================== AlignmentInfo Tests ====================

    @Test
    @DisplayName("AlignmentInfo stores all fields correctly")
    void testAlignmentInfoStoresFields() {
        AlignmentInfo info = new AlignmentInfo(0.85, "Slide-specific", "MacroSlide_001");

        assertEquals(0.85, info.confidence(), 0.001);
        assertEquals("Slide-specific", info.source());
        assertEquals("MacroSlide_001", info.transformName());
    }

    @Test
    @DisplayName("AlignmentInfo.withDefaults creates info with default confidence")
    void testAlignmentInfoWithDefaults() {
        AlignmentInfo info = AlignmentInfo.withDefaults("General", "Default_Transform");

        assertEquals(0.7, info.confidence(), 0.001);
        assertEquals("General", info.source());
        assertEquals("Default_Transform", info.transformName());
    }

    // ==================== Confidence Level Tests ====================

    @ParameterizedTest
    @ValueSource(doubles = {0.8, 0.85, 0.9, 0.95, 1.0})
    @DisplayName("High confidence level for values >= 0.8")
    void testHighConfidenceLevel(double confidence) {
        AlignmentInfo info = new AlignmentInfo(confidence, "Test", "Transform");
        assertEquals("HIGH", info.getConfidenceLevel());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.79})
    @DisplayName("Medium confidence level for values 0.5-0.79")
    void testMediumConfidenceLevel(double confidence) {
        AlignmentInfo info = new AlignmentInfo(confidence, "Test", "Transform");
        assertEquals("MEDIUM", info.getConfidenceLevel());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.1, 0.2, 0.3, 0.4, 0.49})
    @DisplayName("Low confidence level for values < 0.5")
    void testLowConfidenceLevel(double confidence) {
        AlignmentInfo info = new AlignmentInfo(confidence, "Test", "Transform");
        assertEquals("LOW", info.getConfidenceLevel());
    }

    // ==================== Confidence-Based Recommendation Tests ====================

    @Test
    @DisplayName("High confidence recommends no refinement")
    void testHighConfidenceRecommendsNoRefinement() {
        // High confidence (>= 0.8) should recommend NONE
        AlignmentInfo highInfo = new AlignmentInfo(0.85, "Slide-specific", "Test");
        assertEquals("HIGH", highInfo.getConfidenceLevel());
        // In the controller, this would auto-select RefinementChoice.NONE
    }

    @Test
    @DisplayName("Medium confidence recommends single-tile refinement")
    void testMediumConfidenceRecommendsSingleTile() {
        // Medium confidence (0.5-0.79) should recommend SINGLE_TILE
        AlignmentInfo mediumInfo = new AlignmentInfo(0.65, "General", "Test");
        assertEquals("MEDIUM", mediumInfo.getConfidenceLevel());
        // In the controller, this would auto-select RefinementChoice.SINGLE_TILE
    }

    @Test
    @DisplayName("Low confidence recommends full manual alignment")
    void testLowConfidenceRecommendsFullManual() {
        // Low confidence (< 0.5) should recommend FULL_MANUAL
        AlignmentInfo lowInfo = new AlignmentInfo(0.35, "Unknown", "Test");
        assertEquals("LOW", lowInfo.getConfidenceLevel());
        // In the controller, this would auto-select RefinementChoice.FULL_MANUAL
    }

    // ==================== Edge Case Tests ====================

    @Test
    @DisplayName("Boundary confidence at exactly 0.8")
    void testBoundaryHighConfidence() {
        AlignmentInfo info = new AlignmentInfo(0.8, "Test", "Transform");
        assertEquals("HIGH", info.getConfidenceLevel());
    }

    @Test
    @DisplayName("Boundary confidence at exactly 0.5")
    void testBoundaryMediumConfidence() {
        AlignmentInfo info = new AlignmentInfo(0.5, "Test", "Transform");
        assertEquals("MEDIUM", info.getConfidenceLevel());
    }

    @Test
    @DisplayName("Zero confidence is LOW")
    void testZeroConfidence() {
        AlignmentInfo info = new AlignmentInfo(0.0, "Test", "Transform");
        assertEquals("LOW", info.getConfidenceLevel());
    }

    @Test
    @DisplayName("100% confidence is HIGH")
    void testFullConfidence() {
        AlignmentInfo info = new AlignmentInfo(1.0, "Test", "Transform");
        assertEquals("HIGH", info.getConfidenceLevel());
    }

    // ==================== Source Description Tests ====================

    @Test
    @DisplayName("AlignmentInfo preserves source description")
    void testAlignmentInfoSourceDescriptions() {
        String[] sources = {
                "Slide-specific (MacroSlide_001)",
                "General (Standard_Alignment)",
                "Unknown"
        };

        for (String source : sources) {
            AlignmentInfo info = new AlignmentInfo(0.7, source, "Transform");
            assertEquals(source, info.source());
        }
    }

    // ==================== Transform Name Tests ====================

    @Test
    @DisplayName("AlignmentInfo preserves transform name")
    void testAlignmentInfoTransformNames() {
        String[] names = {
                "Standard_Alignment_20231215",
                "MacroSlide_001_refined",
                "Manual_3point",
                "Current alignment"
        };

        for (String name : names) {
            AlignmentInfo info = new AlignmentInfo(0.7, "Test", name);
            assertEquals(name, info.transformName());
        }
    }
}
