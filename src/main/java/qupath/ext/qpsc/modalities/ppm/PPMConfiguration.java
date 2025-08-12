package qupath.ext.qpsc.modalities.ppm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.RotationManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.util.Map;

/**
 * Central configuration and utility class for PPM (Polarized Light Microscopy).
 * Handles tick-to-angle conversions and PPM-specific configurations.
 *
 * @author Mike Nelson
 * @since 4.0
 */
public class PPMConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(PPMConfiguration.class);

    /**
     * Conversion factor between ticks and angles.
     * In PPM systems, ticks = 2 * angle in degrees.
     */
    public static final double TICK_TO_ANGLE_FACTOR = 2.0;

    /**
     * Converts ticks to angle in degrees.
     *
     * @param ticks The tick value
     * @return The angle in degrees
     */
    public static double ticksToAngle(double ticks) {
        return ticks / TICK_TO_ANGLE_FACTOR;
    }

    /**
     * Converts angle in degrees to ticks.
     *
     * @param angle The angle in degrees
     * @return The tick value
     */
    public static double angleToTicks(double angle) {
        return angle * TICK_TO_ANGLE_FACTOR;
    }

    /**
     * Checks if a modality name indicates PPM.
     *
     * @param modalityName The modality name
     * @return true if PPM modality
     */
    public static boolean isPPMModality(String modalityName) {
        return modalityName != null && modalityName.startsWith("PPM_");
    }

    /**
     * Container for PPM angle configuration.
     */
    public static class PPMAngles {
        public final double minusTicks;
        public final double zeroTicks;
        public final double plusTicks;
        public final double brightfieldTicks;

        public PPMAngles(double minusTicks, double zeroTicks, double plusTicks, double brightfieldTicks) {
            this.minusTicks = minusTicks;
            this.zeroTicks = zeroTicks;
            this.plusTicks = plusTicks;
            this.brightfieldTicks = brightfieldTicks;
        }

        /**
         * Creates PPM angles with standard 90° brightfield.
         */
        public PPMAngles(double minusTicks, double zeroTicks, double plusTicks) {
            this(minusTicks, zeroTicks, plusTicks, 90.0);
        }
    }

    /**
     * Reads PPM angles from microscope configuration.
     *
     * @param configManager The microscope configuration manager
     * @return PPMAngles object with configured angles
     */
    public static PPMAngles readPPMAngles(MicroscopeConfigManager configManager) {
        Map<String, Object> ppmConfig = configManager.getPPMConfig();

        Double plusTick = null;
        Double minusTick = null;
        Double zeroTick = null;

        // Read from global PPM section
        Map<String, Object> ppmPlus = (Map<String, Object>) ppmConfig.get("ppm_plus");
        Map<String, Object> ppmMinus = (Map<String, Object>) ppmConfig.get("ppm_minus");
        Map<String, Object> ppmZero = (Map<String, Object>) ppmConfig.get("ppm_zero");

        if (ppmPlus != null && ppmPlus.containsKey("tick")) {
            plusTick = ((Number) ppmPlus.get("tick")).doubleValue();
        }

        if (ppmMinus != null && ppmMinus.containsKey("tick")) {
            minusTick = ((Number) ppmMinus.get("tick")).doubleValue();
        }

        if (ppmZero != null && ppmZero.containsKey("tick")) {
            zeroTick = ((Number) ppmZero.get("tick")).doubleValue();
        }

        // Use defaults if not configured
        if (plusTick == null || minusTick == null) {
            logger.warn("PPM ticks not fully configured in YAML. Using defaults (+5, -5).");
            plusTick = plusTick != null ? plusTick : 5.0;
            minusTick = minusTick != null ? minusTick : -5.0;
        }
        if (zeroTick == null) {
            zeroTick = 0.0;
        }

        logger.info("PPM angles configured - minus: {} ticks, zero: {} ticks, plus: {} ticks",
                minusTick, zeroTick, plusTick);

        return new PPMAngles(minusTick, zeroTick, plusTick);
    }

    /**
     * Creates a PPM rotation strategy from configuration.
     *
     * @param configManager The microscope configuration manager
     * @return Configured PPM rotation strategy
     */
    public static PPMRotationStrategy createRotationStrategy(MicroscopeConfigManager configManager) {
        PPMAngles angles = readPPMAngles(configManager);

        // Get exposure times from preferences
        int plusExposure = PPMPreferences.getPPMPlusExposureMs();
        int minusExposure = PPMPreferences.getPPMMinusExposureMs();
        int zeroExposure = PPMPreferences.getPPMZeroExposureMs();

        return new PPMRotationStrategy(
                new RotationManager.TickExposure(angles.plusTicks, plusExposure),
                new RotationManager.TickExposure(angles.minusTicks, minusExposure),
                new RotationManager.TickExposure(angles.zeroTicks, zeroExposure)
        );
    }

    /**
     * Formats a tick value for display (shows both ticks and degrees).
     *
     * @param ticks The tick value
     * @return Formatted string like "5.0 ticks (2.5°)"
     */
    public static String formatTicksForDisplay(double ticks) {
        double degrees = ticksToAngle(ticks);
        return String.format("%.1f ticks (%.1f°)", ticks, degrees);
    }
}