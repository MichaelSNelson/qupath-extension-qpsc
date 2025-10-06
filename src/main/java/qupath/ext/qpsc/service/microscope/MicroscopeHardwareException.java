package qupath.ext.qpsc.service.microscope;

import java.io.IOException;

/**
 * Exception thrown when microscope hardware encounters an error.
 * This exception is used to distinguish between network/communication errors
 * and actual hardware problems (e.g., MicroManager not running, stage not configured).
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class MicroscopeHardwareException extends IOException {

    /**
     * Constructs a new microscope hardware exception with the specified detail message.
     *
     * @param message the detail message
     */
    public MicroscopeHardwareException(String message) {
        super(message);
    }

    /**
     * Constructs a new microscope hardware exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public MicroscopeHardwareException(String message, Throwable cause) {
        super(message, cause);
    }
}
