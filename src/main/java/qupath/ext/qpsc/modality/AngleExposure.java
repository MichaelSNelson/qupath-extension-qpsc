package qupath.ext.qpsc.modality;

/**
 * Simple container for a rotation angle (in ticks) and its associated exposure time.
 */
public record AngleExposure(double ticks, int exposureMs) {
    @Override
    public String toString() {
        return ticks + "," + exposureMs;
    }
}
