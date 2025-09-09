package qupath.ext.qpsc.utilities;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ObjectiveUtils functionality.
 */
class ObjectiveUtilsTest {

    @Test
    void testExtractMagnification_ValidLOCIObjectives() {
        assertEquals("20x", ObjectiveUtils.extractMagnification("LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        assertEquals("10x", ObjectiveUtils.extractMagnification("LOCI_OBJECTIVE_OLYMPUS_10X_001"));
        assertEquals("4x", ObjectiveUtils.extractMagnification("LOCI_OBJECTIVE_NIKON_4X_001"));
        assertEquals("40x", ObjectiveUtils.extractMagnification("LOCI_OBJECTIVE_OLYMPUS_40X_POL_001"));
    }

    @Test
    void testExtractMagnification_DifferentFormats() {
        assertEquals("20x", ObjectiveUtils.extractMagnification("OBJECTIVE_20X_POLARIZED"));
        assertEquals("10x", ObjectiveUtils.extractMagnification("Something_10x_Whatever"));
        assertEquals("100x", ObjectiveUtils.extractMagnification("NIKON_100X"));
    }

    @Test
    void testExtractMagnification_InvalidInputs() {
        assertNull(ObjectiveUtils.extractMagnification(null));
        assertNull(ObjectiveUtils.extractMagnification(""));
        assertNull(ObjectiveUtils.extractMagnification("NO_MAGNIFICATION_HERE"));
        assertNull(ObjectiveUtils.extractMagnification("LOCI_OBJECTIVE_WEIRD_FORMAT"));
    }

    @Test
    void testCreateEnhancedFolderName_StandardCase() {
        assertEquals("ppm_20x_1", 
            ObjectiveUtils.createEnhancedFolderName("ppm_1", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        
        assertEquals("brightfield_10x_2", 
            ObjectiveUtils.createEnhancedFolderName("brightfield_2", "LOCI_OBJECTIVE_OLYMPUS_10X_001"));
        
        assertEquals("multiphoton_40x_1", 
            ObjectiveUtils.createEnhancedFolderName("multiphoton_1", "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001"));
    }

    @Test
    void testCreateEnhancedFolderName_NoMagnification() {
        // Should return original when no magnification can be extracted
        assertEquals("ppm_1", 
            ObjectiveUtils.createEnhancedFolderName("ppm_1", "INVALID_OBJECTIVE_NAME"));
        
        assertEquals("ppm_1", 
            ObjectiveUtils.createEnhancedFolderName("ppm_1", null));
    }

    @Test
    void testCreateEnhancedFolderName_EdgeCases() {
        // Single part scan type - should append magnification
        assertEquals("ppm_20x", 
            ObjectiveUtils.createEnhancedFolderName("ppm", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        
        // Multiple parts - should insert magnification before last part
        assertEquals("complex_modality_20x_1", 
            ObjectiveUtils.createEnhancedFolderName("complex_modality_1", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        
        // Null or empty base scan type
        assertEquals("", ObjectiveUtils.createEnhancedFolderName("", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        assertNull(ObjectiveUtils.createEnhancedFolderName(null, "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
    }

    @Test
    void testCreateEnhancedFolderName_RealWorldExamples() {
        // Test with actual config data patterns
        assertEquals("ppm_10x_1", 
            ObjectiveUtils.createEnhancedFolderName("ppm_1", "LOCI_OBJECTIVE_OLYMPUS_10X_001"));
        
        assertEquals("ppm_20x_1", 
            ObjectiveUtils.createEnhancedFolderName("ppm_1", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001"));
        
        assertEquals("ppm_4x_2", 
            ObjectiveUtils.createEnhancedFolderName("ppm_2", "LOCI_OBJECTIVE_NIKON_4X_002"));
    }
}