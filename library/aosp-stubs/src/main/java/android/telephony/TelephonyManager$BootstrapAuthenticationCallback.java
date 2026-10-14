package android.telephony;

/**
 * Compile-only stub of the framework's {@code @SystemApi} nested class
 * {@code android.telephony.TelephonyManager.BootstrapAuthenticationCallback}.
 *
 * The outer {@code TelephonyManager} ships in the public SDK (so it cannot
 * be stubbed — the SDK bootclasspath wins), but this nested callback is
 * {@code @hide} and absent from {@code android.jar}. Declared here as a
 * top-level class whose binary name matches the framework nested class, so
 * compile-time references link and runtime links against the real framework
 * class. Kotlin callers import it with backticks:
 * {@code import android.telephony.`TelephonyManager$BootstrapAuthenticationCallback`}.
 *
 * Not packaged; the real class is provided by the framework at runtime.
 */
public class TelephonyManager$BootstrapAuthenticationCallback {
    public TelephonyManager$BootstrapAuthenticationCallback() {
        throw new RuntimeException("Stub");
    }

    public void onKeysAvailable(byte[] gbaKey, String transactionId) {
        throw new RuntimeException("Stub");
    }

    public void onAuthenticationFailure(int reason) {
        throw new RuntimeException("Stub");
    }
}
