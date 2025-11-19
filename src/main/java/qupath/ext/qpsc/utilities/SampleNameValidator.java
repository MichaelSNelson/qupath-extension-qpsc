package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * SampleNameValidator
 *
 * <p>Validates and sanitizes sample names to ensure they are safe for use in filenames
 * across all operating systems (Windows, Linux, macOS).
 *
 * <p>Illegal characters that are blocked:
 * <ul>
 *   <li>Forward slash: /
 *   <li>Backslash: \
 *   <li>Colon: :
 *   <li>Asterisk: *
 *   <li>Question mark: ?
 *   <li>Quote: "
 *   <li>Less than: &lt;
 *   <li>Greater than: &gt;
 *   <li>Pipe: |
 * </ul>
 *
 * <p>Additional constraints:
 * <ul>
 *   <li>Cannot be empty or only whitespace
 *   <li>Cannot start or end with spaces or periods
 *   <li>Cannot be reserved Windows names (CON, PRN, AUX, NUL, COM1-9, LPT1-9)
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class SampleNameValidator {
    private static final Logger logger = LoggerFactory.getLogger(SampleNameValidator.class);

    // Pattern for illegal filename characters (cross-platform)
    // Includes newlines and carriage returns per GeneralTools.stripInvalidFilenameChars()
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[/\\\\:*?\"<>|\\n\\r]");

    // Reserved Windows filenames (case-insensitive)
    private static final String[] RESERVED_NAMES = {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    /**
     * Validates if a sample name is safe for use in filenames across all platforms.
     *
     * @param sampleName The sample name to validate
     * @return true if the name is valid, false otherwise
     */
    public static boolean isValid(String sampleName) {
        if (sampleName == null || sampleName.trim().isEmpty()) {
            return false;
        }

        String trimmed = sampleName.trim();

        // Check for illegal characters
        if (ILLEGAL_CHARS.matcher(trimmed).find()) {
            return false;
        }

        // Check for leading or trailing spaces/periods
        if (!trimmed.equals(sampleName) || trimmed.startsWith(".") || trimmed.endsWith(".")) {
            return false;
        }

        // Check for reserved Windows names
        String upperName = trimmed.toUpperCase();
        for (String reserved : RESERVED_NAMES) {
            if (upperName.equals(reserved) || upperName.startsWith(reserved + ".")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Gets a detailed validation error message for an invalid sample name.
     *
     * @param sampleName The sample name to validate
     * @return Error message describing why the name is invalid, or null if valid
     */
    public static String getValidationError(String sampleName) {
        if (sampleName == null) {
            return "Sample name cannot be null";
        }

        if (sampleName.trim().isEmpty()) {
            return "Sample name cannot be empty";
        }

        String trimmed = sampleName.trim();

        // Check for illegal characters
        if (ILLEGAL_CHARS.matcher(trimmed).find()) {
            return "Sample name contains illegal characters: / \\ : * ? \" < > |";
        }

        // Check for leading or trailing spaces
        if (!trimmed.equals(sampleName)) {
            return "Sample name cannot start or end with spaces";
        }

        // Check for leading or trailing periods
        if (trimmed.startsWith(".")) {
            return "Sample name cannot start with a period";
        }
        if (trimmed.endsWith(".")) {
            return "Sample name cannot end with a period";
        }

        // Check for reserved Windows names
        String upperName = trimmed.toUpperCase();
        for (String reserved : RESERVED_NAMES) {
            if (upperName.equals(reserved) || upperName.startsWith(reserved + ".")) {
                return "Sample name '" + reserved + "' is reserved by Windows";
            }
        }

        return null;  // Valid
    }

    /**
     * Sanitizes a sample name by replacing illegal characters with underscores.
     * This is a best-effort approach - always validate after sanitization.
     *
     * @param sampleName The sample name to sanitize
     * @return A sanitized version of the name, or "Sample" if input is invalid
     */
    public static String sanitize(String sampleName) {
        if (sampleName == null || sampleName.trim().isEmpty()) {
            return "Sample";
        }

        // Replace illegal characters with underscores
        String sanitized = ILLEGAL_CHARS.matcher(sampleName).replaceAll("_");

        // Trim and remove leading/trailing periods
        sanitized = sanitized.trim();
        while (sanitized.startsWith(".")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }

        // If empty after sanitization, use default
        if (sanitized.isEmpty()) {
            sanitized = "Sample";
        }

        // Check for reserved names and append underscore if needed
        String upperName = sanitized.toUpperCase();
        for (String reserved : RESERVED_NAMES) {
            if (upperName.equals(reserved) || upperName.startsWith(reserved + ".")) {
                sanitized = sanitized + "_sample";
                break;
            }
        }

        logger.debug("Sanitized '{}' to '{}'", sampleName, sanitized);
        return sanitized;
    }

    // Note: extractBaseName() has been removed - use GeneralTools.stripExtension() instead
    // GeneralTools handles multi-part extensions (.ome.tif, .ome.zarr, etc.) automatically

    /**
     * Validates and sanitizes a sample name, returning a valid name.
     * If the input is invalid, returns a sanitized version.
     *
     * @param sampleName The sample name to process
     * @return A valid sample name (either the original if valid, or sanitized version)
     */
    public static String ensureValid(String sampleName) {
        if (isValid(sampleName)) {
            return sampleName;
        }
        return sanitize(sampleName);
    }
}
