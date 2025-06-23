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



}
