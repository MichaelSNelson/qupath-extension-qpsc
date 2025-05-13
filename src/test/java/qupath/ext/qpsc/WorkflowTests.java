package qupath.ext.qpsc;
//
//Unit tests for pure logic
//
//Coordinate transforms (e.g. TransformationFunctions):
//
//QP → stage, stage → QP, offset, boundary finding.
//
//        Tiling logic (in your UtilityFunctions or MinorFunctions):
//
//Given a bounding box + frame size + overlap, do you generate the right number of tiles?
//
//YAML parsing & validation (MicroscopeConfigManager):
//
//        Missing keys, nested lookups, typed getters (getDouble, getList, etc).
//
//        “Golden file” tests for CLI adapters
//
//Mock out the CLI process (e.g. using a fake ProcessBuilder) and assert that MicroscopeController.moveStageXY(…) builds the correct List<String> of arguments.
//
//Integration style tests (if possible)
//
//Bring up a headless JavaFX runtime and show that your small “Test” dialog doesn’t throw. (QuPath’s test harness supports this.)
//
//Run a “dry run” of your tiling → CLI → stitching pipeline against a dummy directory full of blank TIFFs.
//
//End to end smoke test
//
//A single JUnit test that runs your acquisition workflow against a toy image:
//
//Create a Project in a temp folder.
//
//Add a small blank slide.
//
//        Call runBoundingBoxWorkflow() with a known rectangle.
//
//Verify the CLI was invoked the expected number of times.
//
//Verify that stitching output files exist.
public class WorkflowTests {
}
