package android.telephony.ims;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.RcsClientConfiguration}. Passed to
 * `ProvisioningManager#setRcsClientConfiguration` (hidden; see
 * TestRcsApp's ProvisioningActivity). Not packaged; the real class is
 * provided by the framework at runtime.
 */
public final class RcsClientConfiguration {
    public RcsClientConfiguration(
            String rcsVersion,
            String rcsProfile,
            String clientVendor,
            String clientVersion) {
        throw new RuntimeException("Stub");
    }

    public String getRcsVersion() {
        throw new RuntimeException("Stub");
    }

    public String getRcsProfile() {
        throw new RuntimeException("Stub");
    }

    public String getClientVendor() {
        throw new RuntimeException("Stub");
    }

    public String getClientVersion() {
        throw new RuntimeException("Stub");
    }
}
