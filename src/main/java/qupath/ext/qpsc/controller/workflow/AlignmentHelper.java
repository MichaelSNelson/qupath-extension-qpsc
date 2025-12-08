package qupath.ext.qpsc.controller.workflow;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.SampleSetupController;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for alignment-related operations in the workflow.
 *
 * <p>This class provides utilities for:
 * <ul>
 *   <li>Checking for existing slide-specific alignments</li>
 *   <li>Calculating alignment confidence scores</li>
 *   <li>Loading saved alignment transforms</li>
 * </ul>
 *
 * <p>Slide-specific alignments allow users to skip the alignment process if they've
 * already aligned this specific slide in a previous session.
 *
 * <p>Confidence scoring is based on multiple factors:
 * <ul>
 *   <li>Alignment source (slide-specific vs general)</li>
 *   <li>Age of the alignment</li>
 *   <li>Number of control points used</li>
 *   <li>Transform residual error (if available)</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class AlignmentHelper {
    private static final Logger logger = LoggerFactory.getLogger(AlignmentHelper.class);

    // Confidence thresholds
    private static final double BASE_CONFIDENCE_SLIDE_SPECIFIC = 0.85;
    private static final double BASE_CONFIDENCE_GENERAL = 0.65;
    private static final double CONFIDENCE_AGE_PENALTY_PER_DAY = 0.01;
    private static final double MAX_AGE_PENALTY = 0.2;

    /**
     * Result from checking for existing slide alignment.
     */
    public static class SlideAlignmentResult {
        private final AffineTransform transform;
        private final boolean refineRequested;
        private final double confidence;
        private final String source;

        public SlideAlignmentResult(AffineTransform transform, boolean refineRequested) {
            this(transform, refineRequested, 0.7, "Unknown");
        }

        public SlideAlignmentResult(AffineTransform transform, boolean refineRequested,
                                    double confidence, String source) {
            this.transform = transform;
            this.refineRequested = refineRequested;
            this.confidence = confidence;
            this.source = source;
        }

        public AffineTransform getTransform() {
            return transform;
        }

        public boolean isRefineRequested() {
            return refineRequested;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getSource() {
            return source;
        }
    }

    /**
     * Calculates confidence score for a slide-specific alignment.
     *
     * @param isSlideSpecific Whether this is a slide-specific (vs general) alignment
     * @param createdDate The date string when alignment was created (may be null)
     * @return Confidence score between 0.0 and 1.0
     */
    public static double calculateConfidence(boolean isSlideSpecific, String createdDate) {
        // Start with base confidence based on alignment type
        double confidence = isSlideSpecific ? BASE_CONFIDENCE_SLIDE_SPECIFIC : BASE_CONFIDENCE_GENERAL;

        // Apply age penalty if date is available
        if (createdDate != null && !createdDate.isEmpty()) {
            try {
                // Parse date and calculate age in days
                java.time.LocalDate created = java.time.LocalDate.parse(createdDate.substring(0, 10));
                long daysOld = java.time.temporal.ChronoUnit.DAYS.between(created, java.time.LocalDate.now());

                // Apply penalty (capped at MAX_AGE_PENALTY)
                double agePenalty = Math.min(daysOld * CONFIDENCE_AGE_PENALTY_PER_DAY, MAX_AGE_PENALTY);
                confidence -= agePenalty;

                logger.debug("Alignment age: {} days, penalty: {}, final confidence: {}",
                        daysOld, agePenalty, confidence);
            } catch (Exception e) {
                logger.debug("Could not parse alignment date: {}", createdDate);
            }
        }

        // Ensure confidence stays in valid range
        return Math.max(0.1, Math.min(1.0, confidence));
    }

    /**
     * Calculates confidence for a TransformPreset.
     *
     * @param preset The transform preset to evaluate
     * @return Confidence score between 0.0 and 1.0
     */
    public static double calculateConfidence(AffineTransformManager.TransformPreset preset) {
        if (preset == null) {
            return 0.0;
        }

        // General transforms start with lower confidence
        double confidence = BASE_CONFIDENCE_GENERAL;

        // Apply age penalty
        java.util.Date createdDate = preset.getCreatedDate();
        if (createdDate != null) {
            try {
                java.time.LocalDate created = createdDate.toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate();
                long daysOld = java.time.temporal.ChronoUnit.DAYS.between(created, java.time.LocalDate.now());
                double agePenalty = Math.min(daysOld * CONFIDENCE_AGE_PENALTY_PER_DAY, MAX_AGE_PENALTY);
                confidence -= agePenalty;
            } catch (Exception e) {
                logger.debug("Could not parse preset date: {}", createdDate);
            }
        }

        // Boost if green box params are saved (indicates successful prior use)
        if (preset.getGreenBoxParams() != null) {
            confidence += 0.05;
        }

        return Math.max(0.1, Math.min(1.0, confidence));
    }

    /**
     * Checks for existing slide-specific alignment.
     *
     * <p>This method:
     * <ol>
     *   <li>Attempts to load a saved alignment for the specific slide</li>
     *   <li>Calculates confidence score for the alignment</li>
     *   <li>Returns result with transform and confidence (refinement handled separately)</li>
     * </ol>
     *
     * <p>The alignment is slide-specific, meaning it's saved per image name and includes
     * any refinements made during previous acquisitions.
     *
     * <p><b>Note:</b> Refinement options are now handled by {@code RefinementSelectionController}
     * which provides a unified interface with confidence-based recommendations.
     *
     * @param gui QuPath GUI instance
     * @param sample Sample setup information including name and project location
     * @return CompletableFuture containing the SlideAlignmentResult or null if none found
     */
    public static CompletableFuture<SlideAlignmentResult> checkForSlideAlignment(
            QuPathGUI gui, SampleSetupController.SampleSetupResult sample) {

        CompletableFuture<SlideAlignmentResult> future = new CompletableFuture<>();

        // Get the image name (without extension) from the current image
        String imageName = null;
        if (gui.getImageData() != null) {
            String fullImageName = gui.getImageData().getServer().getMetadata().getName();
            imageName = qupath.lib.common.GeneralTools.stripExtension(fullImageName);
        }

        if (imageName == null) {
            logger.warn("No image is currently open, cannot check for slide alignment");
            future.complete(null);
            return future;
        }

        logger.info("Checking for slide-specific alignment for image: {}", imageName);

        // Try to load slide-specific alignment using IMAGE name (not sample name)
        AffineTransform slideTransform = null;
        String createdDate = null;

        // First try from current project
        Project<BufferedImage> project = gui.getProject();
        if (project != null) {
            slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);
            // Try to get created date from alignment metadata
            createdDate = AffineTransformManager.getSlideAlignmentDate(project, imageName);
        } else {
            // Try from project directory if no project is open
            File projectDir = new File(sample.projectsFolder(), sample.sampleName());
            if (projectDir.exists()) {
                slideTransform = AffineTransformManager.loadSlideAlignmentFromDirectory(
                        projectDir, imageName);
                createdDate = AffineTransformManager.getSlideAlignmentDateFromDirectory(
                        projectDir, imageName);
            }
        }

        if (slideTransform != null) {
            // Calculate confidence for this slide-specific alignment
            double confidence = calculateConfidence(true, createdDate);
            String source = "Slide-specific (" + imageName + ")";

            logger.info("Found slide-specific alignment with confidence: {} (source: {})",
                    String.format("%.2f", confidence), source);

            // Return result - refinement choice is handled later by RefinementSelectionController
            future.complete(new SlideAlignmentResult(slideTransform, false, confidence, source));
        } else {
            logger.info("No slide-specific alignment found");
            future.complete(null);
        }

        return future;
    }

    /**
     * Gets a descriptive source string for a general transform.
     *
     * @param preset The transform preset
     * @return Human-readable source description
     */
    public static String getSourceDescription(AffineTransformManager.TransformPreset preset) {
        if (preset == null) {
            return "Unknown";
        }
        return "General (" + preset.getName() + ")";
    }
}