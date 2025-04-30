package qupath.ext.qpsc;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.UtilityFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.objects.PathObjects;
import qupath.lib.scripting.QP;
import qupath.lib.gui.scripting.QPEx;

import java.awt.geom.AffineTransform;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateTransformationTest {

    private static final Logger logger = LoggerFactory.getLogger(CoordinateTransformationTest.class);

    private static final String TEST_FOLDER = "F:/QPScopeExtension/data/test";
    private static final double ORIGINAL_PIXEL_SIZE = 1.108;
    private static final double ACQUISITION_PIXEL_SIZE = 0.2201;
    private static final int CAMERA_WIDTH = 1392;
    private static final int CAMERA_HEIGHT = 1040;

    @Test
    void testPerformTilingAndSaveConfiguration() {
        // Step 1: make a simple annotation
        ImagePlane plane = ImagePlane.getDefaultPlane();
        var roi = ROIs.createRectangleROI(200, 200, 600, 600, plane);
        var annotation = PathObjects.createAnnotationObject(roi, QP.getPathClass("Tissue"));
        annotation.setName("Default box");
        List<PathObject> annotations = List.of(annotation);

        // Step 2: compute frame sizes in QP pixels
        double frameWidth  = ACQUISITION_PIXEL_SIZE * CAMERA_WIDTH  / ORIGINAL_PIXEL_SIZE;
        double frameHeight = ACQUISITION_PIXEL_SIZE * CAMERA_HEIGHT / ORIGINAL_PIXEL_SIZE;
        logger.info("frameWidthQPPixels = {}", frameWidth);

        // Step 3: call tile/stitching logic and assert it doesn’t blow up
        assertDoesNotThrow(() ->
                UtilityFunctions.performTilingAndSaveConfiguration(
                        TEST_FOLDER,       // output folder
                        "test_1",          // sample label
                        frameWidth,
                        frameHeight,
                        0,                 // overlap percent
                        null,              // no bounding box coords
                        true,              // create QuPath tile objects
                        annotations,        // our single annotation
                        true,
                        false
                )
        );
    }

    @Test
    void testAffineTransformationAndOffset() {
        // Build the scaling transform based on inverted stage prefs
        AffineTransform scaling = new AffineTransform();
        boolean invertedX = QPPreferenceDialog.getInvertedXProperty();
        boolean invertedY = QPPreferenceDialog.getInvertedYProperty();

        double sx = invertedX ? -ORIGINAL_PIXEL_SIZE : ORIGINAL_PIXEL_SIZE;
        double sy = invertedY ? -ORIGINAL_PIXEL_SIZE : ORIGINAL_PIXEL_SIZE;
        scaling.scale(sx, sy);

        // QuPath → stage coordinates for the first tile
        double [] qpCoords = new double[]{1133.7, 4253.0};
        List<String> stageCoordsStr = List.of("18838", "13730");
        double [] stageCoords = MinorFunctions.convertListToPrimitiveArray(stageCoordsStr);

        // The offset from tile center to stage
        double [] offset = MinorFunctions.getCurrentOffset();

        // Compute the full transform: scale + translation
        AffineTransform fullTransform = TransformationFunctions.addTranslationToScaledAffine(
                scaling, qpCoords, stageCoords, offset
        );

        // When we re apply it to our qpCoords, we should land at stageCoords (±1 µm)
        double[] result = TransformationFunctions.qpToMicroscopeCoordinates(qpCoords, fullTransform);

        // And assert on its elements:
        assertEquals(stageCoords[0], result[0], 1.0, "X coordinate within 1 µm");
        assertEquals(stageCoords[1], result[1], 1.0, "Y coordinate within 1 µm");
    }
}
