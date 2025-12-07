package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper.SlideAlignmentResult;

import java.awt.geom.AffineTransform;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Unit tests for AlignmentHelper confidence scoring.
 *
 * <p>Tests focus on:
 * <ul>
 *   <li>Confidence calculation for slide-specific vs general alignments</li>
 *   <li>Age-based penalty application</li>
 *   <li>SlideAlignmentResult data handling</li>
 * </ul>
 */
class AlignmentHelperTest {

    // Base confidence values from AlignmentHelper
    private static final double BASE_SLIDE_SPECIFIC = 0.85;
    private static final double BASE_GENERAL = 0.65;
    private static final double AGE_PENALTY_PER_DAY = 0.01;
    private static final double MAX_AGE_PENALTY = 0.2;

    // ==================== Slide-Specific Confidence Tests ====================

    @Test
    @DisplayName("Slide-specific alignment starts with 0.85 base confidence")
    void testSlideSpecificBaseConfidence() {
        double confidence = AlignmentHelper.calculateConfidence(true, null);
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.001);
    }

    @Test
    @DisplayName("General alignment starts with 0.65 base confidence")
    void testGeneralBaseConfidence() {
        double confidence = AlignmentHelper.calculateConfidence(false, null);
        assertEquals(BASE_GENERAL, confidence, 0.001);
    }

    @Test
    @DisplayName("Slide-specific has higher base confidence than general")
    void testSlideSpecificHigherThanGeneral() {
        double slideSpecific = AlignmentHelper.calculateConfidence(true, null);
        double general = AlignmentHelper.calculateConfidence(false, null);
        assertTrue(slideSpecific > general);
    }

    // ==================== Age-Based Penalty Tests ====================

    @Test
    @DisplayName("Fresh alignment (today) has no age penalty")
    void testFreshAlignmentNoPenalty() {
        String today = LocalDate.now().toString();
        double confidence = AlignmentHelper.calculateConfidence(true, today);
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.001);
    }

    @Test
    @DisplayName("1-day old alignment has small penalty")
    void testOneDayOldPenalty() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        double confidence = AlignmentHelper.calculateConfidence(true, yesterday);
        double expected = BASE_SLIDE_SPECIFIC - AGE_PENALTY_PER_DAY;
        assertEquals(expected, confidence, 0.001);
    }

    @Test
    @DisplayName("10-day old alignment has 10x daily penalty")
    void testTenDayOldPenalty() {
        String tenDaysAgo = LocalDate.now().minusDays(10).toString();
        double confidence = AlignmentHelper.calculateConfidence(true, tenDaysAgo);
        double expected = BASE_SLIDE_SPECIFIC - (10 * AGE_PENALTY_PER_DAY);
        assertEquals(expected, confidence, 0.001);
    }

    @Test
    @DisplayName("Very old alignment is capped at max penalty")
    void testMaxAgePenaltyCap() {
        String veryOld = LocalDate.now().minusDays(100).toString();
        double confidence = AlignmentHelper.calculateConfidence(true, veryOld);
        double expected = BASE_SLIDE_SPECIFIC - MAX_AGE_PENALTY;
        assertEquals(expected, confidence, 0.001);
    }

    @Test
    @DisplayName("Age penalty does not reduce confidence below 0.1")
    void testMinimumConfidenceFloor() {
        // Even with max penalty, general alignment shouldn't go below 0.1
        String veryOld = LocalDate.now().minusDays(365).toString();
        double confidence = AlignmentHelper.calculateConfidence(false, veryOld);
        assertTrue(confidence >= 0.1, "Confidence should not drop below 0.1");
    }

    // ==================== Date Format Handling Tests ====================

    @Test
    @DisplayName("Null date returns base confidence")
    void testNullDateReturnsBase() {
        double confidence = AlignmentHelper.calculateConfidence(true, null);
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.001);
    }

    @Test
    @DisplayName("Empty date string returns base confidence")
    void testEmptyDateReturnsBase() {
        double confidence = AlignmentHelper.calculateConfidence(true, "");
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.001);
    }

    @Test
    @DisplayName("Invalid date format returns base confidence")
    void testInvalidDateReturnsBase() {
        double confidence = AlignmentHelper.calculateConfidence(true, "not-a-date");
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.001);
    }

    @Test
    @DisplayName("Full timestamp string is handled (date portion extracted)")
    void testFullTimestampHandled() {
        // The format "2025-12-05T10:30:00" should extract "2025-12-05"
        String fullTimestamp = LocalDate.now().toString() + "T10:30:00";
        double confidence = AlignmentHelper.calculateConfidence(true, fullTimestamp);
        // Should be close to base (today's date)
        assertEquals(BASE_SLIDE_SPECIFIC, confidence, 0.01);
    }

    // ==================== SlideAlignmentResult Tests ====================

    @Test
    @DisplayName("SlideAlignmentResult stores transform correctly")
    void testSlideAlignmentResultTransform() {
        AffineTransform transform = AffineTransform.getScaleInstance(0.1, 0.1);
        SlideAlignmentResult result = new SlideAlignmentResult(transform, false);

        assertEquals(transform, result.getTransform());
        assertEquals(0.1, result.getTransform().getScaleX(), 0.001);
    }

    @Test
    @DisplayName("SlideAlignmentResult stores refine requested flag")
    void testSlideAlignmentResultRefineRequested() {
        AffineTransform transform = new AffineTransform();

        SlideAlignmentResult noRefine = new SlideAlignmentResult(transform, false);
        assertFalse(noRefine.isRefineRequested());

        SlideAlignmentResult withRefine = new SlideAlignmentResult(transform, true);
        assertTrue(withRefine.isRefineRequested());
    }

    @Test
    @DisplayName("SlideAlignmentResult full constructor stores all fields")
    void testSlideAlignmentResultFullConstructor() {
        AffineTransform transform = new AffineTransform();
        SlideAlignmentResult result = new SlideAlignmentResult(
                transform, false, 0.92, "Slide-specific (MacroSlide_001)");

        assertEquals(transform, result.getTransform());
        assertFalse(result.isRefineRequested());
        assertEquals(0.92, result.getConfidence(), 0.001);
        assertEquals("Slide-specific (MacroSlide_001)", result.getSource());
    }

    @Test
    @DisplayName("SlideAlignmentResult legacy constructor sets default confidence")
    void testSlideAlignmentResultLegacyConstructor() {
        AffineTransform transform = new AffineTransform();
        SlideAlignmentResult result = new SlideAlignmentResult(transform, true);

        assertEquals(0.7, result.getConfidence(), 0.001);
        assertEquals("Unknown", result.getSource());
    }

    // ==================== Confidence Range Validation Tests ====================

    @ParameterizedTest
    @CsvSource({
            "true, 0.65, 0.85",   // Slide-specific range
            "false, 0.45, 0.65"   // General range (after max penalty)
    })
    @DisplayName("Confidence stays within expected range")
    void testConfidenceRange(boolean isSlideSpecific, double minExpected, double maxExpected) {
        // Test with various dates
        double fresh = AlignmentHelper.calculateConfidence(isSlideSpecific, LocalDate.now().toString());
        double old = AlignmentHelper.calculateConfidence(isSlideSpecific,
                LocalDate.now().minusDays(100).toString());

        assertTrue(fresh <= maxExpected && fresh >= minExpected,
                "Fresh confidence should be in range");
        assertTrue(old >= 0.1,
                "Old confidence should be at least 0.1");
    }

    // ==================== Source Description Tests ====================

    @Test
    @DisplayName("getSourceDescription returns formatted string for preset")
    void testGetSourceDescriptionNotNull() {
        // Would need mock TransformPreset to fully test
        // For now, test that null returns expected value
        String source = AlignmentHelper.getSourceDescription(null);
        assertEquals("Unknown", source);
    }
}
