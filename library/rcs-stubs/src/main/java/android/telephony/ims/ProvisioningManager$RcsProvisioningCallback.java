package android.telephony.ims;

/**
 * Compile-only stub of the framework's {@code @SystemApi} nested class
 * {@code android.telephony.ims.ProvisioningManager.RcsProvisioningCallback}.
 *
 * The outer {@code ProvisioningManager} is hidden (bridged reflectively via
 * {@code RcsHiddenApi.provisioningManager}), and this nested callback is a
 * concrete class, not an interface — so {@code java.lang.reflect.Proxy}
 * cannot implement it (verified on-device: "not an interface"). Declared
 * here as a top-level class whose binary name matches the framework nested
 * class, so compile-time references link and runtime links against the real
 * framework class. Same mechanism as the
 * {@code TelephonyManager$BootstrapAuthenticationCallback} stub used for
 * live GBA. Kotlin callers subclass it directly with overrides.
 *
 * Not packaged; the real class is provided by the framework at runtime.
 */
public class ProvisioningManager$RcsProvisioningCallback {
    public ProvisioningManager$RcsProvisioningCallback() {
        throw new RuntimeException("Stub");
    }

    public void onConfigurationChanged(byte[] configXml) {
        throw new RuntimeException("Stub");
    }

    public void onAutoConfigurationErrorReceived(int errorCode, String errorString) {
        throw new RuntimeException("Stub");
    }

    public void onConfigurationReset() {
        throw new RuntimeException("Stub");
    }

    public void onRemoved() {
        throw new RuntimeException("Stub");
    }
}
