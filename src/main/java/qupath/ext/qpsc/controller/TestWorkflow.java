package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Test workflow to verify affine transform coordinate systems
 */
public class TestWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(TestWorkflow.class);

    public static void run() {
        logger.info("Starting Transform Test Workflow...");

        QuPathGUI gui = QuPathGUI.getInstance();

        if (gui.getImageData() == null) {
            Platform.runLater(() -> showError("No image open"));
            return;
        }

        Platform.runLater(() -> runTests(gui));
    }

    private static void runTests(QuPathGUI gui) {
        StringBuilder results = new StringBuilder();
        results.append("TRANSFORM COORDINATE SYSTEM TEST\n");
        results.append("================================\n\n");

        try {
            // 1. Get image properties
            var imageData = gui.getImageData();
            var server = imageData.getServer();
            double imagePixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons();
            int imageWidth = server.getWidth();
            int imageHeight = server.getHeight();

            results.append("IMAGE PROPERTIES:\n");
            results.append(String.format("  Full resolution size: %d x %d pixels\n", imageWidth, imageHeight));
            results.append(String.format("  Pixel size: %.6f µm\n", imagePixelSize));
            results.append(String.format("  Physical size: %.2f x %.2f µm\n",
                    imageWidth * imagePixelSize, imageHeight * imagePixelSize));

            // 2. Check for macro image
            BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
            double macroPixelSize = 80.0; // default assumption

            if (macroImage != null) {
                results.append("\nMACRO IMAGE PROPERTIES:\n");
                results.append(String.format("  Macro size: %d x %d pixels\n",
                        macroImage.getWidth(), macroImage.getHeight()));
                results.append(String.format("  Assumed pixel size: %.1f µm\n", macroPixelSize));
                results.append(String.format("  Physical size: %.2f x %.2f µm\n",
                        macroImage.getWidth() * macroPixelSize,
                        macroImage.getHeight() * macroPixelSize));

                // Check for green box
                var greenBoxResult = GreenBoxDetector.detectGreenBox(
                        macroImage, new GreenBoxDetector.DetectionParams());

                if (greenBoxResult != null && greenBoxResult.getDetectedBox() != null) {
                    ROI greenBox = greenBoxResult.getDetectedBox();
                    results.append("\nGREEN BOX DETECTED:\n");
                    results.append(String.format("  Position in macro: (%.1f, %.1f)\n",
                            greenBox.getBoundsX(), greenBox.getBoundsY()));
                    results.append(String.format("  Size in macro: %.1f x %.1f pixels\n",
                            greenBox.getBoundsWidth(), greenBox.getBoundsHeight()));
                    results.append(String.format("  Physical position: (%.2f, %.2f) µm\n",
                            greenBox.getBoundsX() * macroPixelSize,
                            greenBox.getBoundsY() * macroPixelSize));
                }
            } else {
                results.append("\nNO MACRO IMAGE FOUND\n");
            }

            // 3. Check flip settings
            results.append("\nFLIP SETTINGS:\n");
            results.append(String.format("  Flip Macro X: %s\n", QPPreferenceDialog.getFlipMacroXProperty()));
            results.append(String.format("  Flip Macro Y: %s\n", QPPreferenceDialog.getFlipMacroYProperty()));
            results.append(String.format("  Inverted X: %s\n", QPPreferenceDialog.getInvertedXProperty()));
            results.append(String.format("  Inverted Y: %s\n", QPPreferenceDialog.getInvertedYProperty()));

            // 4. Test coordinate conversions
            results.append("\nCOORDINATE CONVERSION TESTS:\n");
            results.append("Testing center point of image:\n");

            double[] testPointFullRes = {imageWidth / 2.0, imageHeight / 2.0};
            results.append(String.format("  Full-res center: (%.1f, %.1f) pixels\n",
                    testPointFullRes[0], testPointFullRes[1]));

            // Convert to macro coordinates
            double scaleFactor = imagePixelSize / macroPixelSize;
            double[] testPointMacro = {
                    testPointFullRes[0] * scaleFactor,
                    testPointFullRes[1] * scaleFactor
            };
            results.append(String.format("  → Macro coords: (%.1f, %.1f) pixels (scale: %.6f)\n",
                    testPointMacro[0], testPointMacro[1], scaleFactor));

            // 5. Load and test transforms
            results.append("\nTRANSFORM TESTS:\n");

            // Check for general saved transform
            AffineTransform generalTransform = AffineTransformManager.loadSavedTransformFromPreferences();
            if (generalTransform != null) {
                results.append("General transform loaded from preferences\n");
                results.append(formatTransform("  General (macro→stage)", generalTransform));

                // Test the transform with macro coordinates
                double[] stageFromMacro = TransformationFunctions.transformQuPathFullResToStage(
                        testPointMacro, generalTransform);
                results.append(String.format("  Macro center → Stage: (%.2f, %.2f) µm\n",
                        stageFromMacro[0], stageFromMacro[1]));
            }

            // Check for slide-specific transform
            Project<BufferedImage> project = gui.getProject();
            if (project != null) {
                String sampleName = project.getName();
                if (sampleName != null && sampleName.contains("-")) {
                    sampleName = sampleName.substring(0, sampleName.indexOf("-"));
                }

                AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(
                        project, sampleName);

                if (slideTransform != null) {
                    results.append("\nSlide-specific transform loaded\n");
                    results.append(formatTransform("  Slide (?→stage)", slideTransform));

                    // Test with full-res coordinates
                    double[] stageFromFullRes = TransformationFunctions.transformQuPathFullResToStage(
                            testPointFullRes, slideTransform);
                    results.append(String.format("  Full-res center → Stage: (%.2f, %.2f) µm\n",
                            stageFromFullRes[0], stageFromFullRes[1]));

                    // Test with macro coordinates (to see if it's expecting macro)
                    double[] stageFromMacroViaSlide = TransformationFunctions.transformQuPathFullResToStage(
                            testPointMacro, slideTransform);
                    results.append(String.format("  Macro center → Stage (via slide): (%.2f, %.2f) µm\n",
                            stageFromMacroViaSlide[0], stageFromMacroViaSlide[1]));
                }
            }

            // 6. Test flip effects
            results.append("\nFLIP EFFECT TEST:\n");
            if (macroImage != null) {
                double macroWidth = macroImage.getWidth();
                double macroHeight = macroImage.getHeight();

                // Test a point in macro coordinates
                double[] macroTestPoint = {macroWidth * 0.75, macroHeight * 0.25}; // upper right
                results.append(String.format("  Test point in macro: (%.1f, %.1f)\n",
                        macroTestPoint[0], macroTestPoint[1]));

                // Apply flips if needed
                double[] flippedMacroPoint = {macroTestPoint[0], macroTestPoint[1]};
                if (QPPreferenceDialog.getFlipMacroXProperty()) {
                    flippedMacroPoint[0] = macroWidth - macroTestPoint[0];
                    results.append(String.format("  After X flip: (%.1f, %.1f)\n",
                            flippedMacroPoint[0], flippedMacroPoint[1]));
                }
                if (QPPreferenceDialog.getFlipMacroYProperty()) {
                    flippedMacroPoint[1] = macroHeight - macroTestPoint[1];
                    results.append(String.format("  After Y flip: (%.1f, %.1f)\n",
                            flippedMacroPoint[0], flippedMacroPoint[1]));
                }
            }

            // 7. Test problem coordinates
            results.append("\nPROBLEM COORDINATE TEST:\n");
            double[] problemCoords = {119360.0, 41664.0};
            results.append(String.format("  Annotation coords: (%.1f, %.1f) pixels\n",
                    problemCoords[0], problemCoords[1]));

            // These should be full-res coordinates based on the error
            double[] problemMacro = {
                    problemCoords[0] * scaleFactor,
                    problemCoords[1] * scaleFactor
            };
            results.append(String.format("  → As macro coords: (%.1f, %.1f) pixels\n",
                    problemMacro[0], problemMacro[1]));

            // Check if flips would help
            if (macroImage != null) {
                double[] flippedProblemMacro = {problemMacro[0], problemMacro[1]};
                double macroWidth = macroImage.getWidth();
                double macroHeight = macroImage.getHeight();

                if (QPPreferenceDialog.getFlipMacroXProperty()) {
                    flippedProblemMacro[0] = macroWidth - problemMacro[0];
                }
                if (QPPreferenceDialog.getFlipMacroYProperty()) {
                    flippedProblemMacro[1] = macroHeight - problemMacro[1];
                }

                if (flippedProblemMacro[0] != problemMacro[0] || flippedProblemMacro[1] != problemMacro[1]) {
                    results.append(String.format("  → After flips: (%.1f, %.1f) pixels\n",
                            flippedProblemMacro[0], flippedProblemMacro[1]));
                }
            }

            // If we have transforms, test them
            if (generalTransform != null) {
                double[] stageFromProblem = TransformationFunctions.transformQuPathFullResToStage(
                        problemMacro, generalTransform);
                results.append(String.format("  → Stage (via general): (%.2f, %.2f) µm\n",
                        stageFromProblem[0], stageFromProblem[1]));
            }

            // Get current transform from MicroscopeController
            MicroscopeController controller = MicroscopeController.getInstance();
            AffineTransform currentTransform = controller.getCurrentTransform();
            if (currentTransform != null) {
                results.append("\nCURRENT MICROSCOPE TRANSFORM:\n");
                results.append(formatTransform("  Current", currentTransform));

                // Test with problem coordinates as if they're what the transform expects
                double[] directTest = TransformationFunctions.transformQuPathFullResToStage(
                        problemCoords, currentTransform);
                results.append(String.format("  Problem coords → Stage (direct): (%.2f, %.2f) µm\n",
                        directTest[0], directTest[1]));
            }

            // 7. Stage limits
            results.append("\nSTAGE LIMITS (from error):\n");
            results.append("  X: -21000.0 to 33000.0 µm\n");
            results.append("  Y: -9000.0 to 11000.0 µm\n");

        } catch (Exception e) {
            results.append("\nERROR: ").append(e.getMessage()).append("\n");
            logger.error("Test failed", e);
        }

        // Show results
        showResults(results.toString());
    }

    private static String formatTransform(String label, AffineTransform transform) {
        double[] matrix = new double[6];
        transform.getMatrix(matrix);
        return String.format("%s: [%.6f, %.6f, %.6f, %.6f, %.2f, %.2f]\n",
                label, matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);
    }

    private static void showResults(String results) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Transform Test Results");
        alert.setHeaderText("Coordinate System Analysis");

        TextArea textArea = new TextArea(results);
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setPrefWidth(800);
        textArea.setPrefHeight(600);

        alert.getDialogPane().setContent(textArea);
        alert.getDialogPane().setPrefWidth(850);
        alert.showAndWait();

        // Also log the results
        logger.info("\n" + results);
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Test Error");
        alert.setContentText(message);
        alert.showAndWait();
    }
}