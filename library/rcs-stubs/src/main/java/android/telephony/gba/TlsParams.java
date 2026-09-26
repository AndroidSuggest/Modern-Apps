package android.telephony.gba;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.gba.TlsParams}. Mirrors the AOSP constants used
 * for GBA bootstrapping. Not packaged; the real class is provided by the
 * framework at runtime.
 */
public class TlsParams {
    public static final int TLS_NULL_WITH_NULL_NULL = 0x0000;
    public static final int TLS_RSA_WITH_AES_128_CBC_SHA = 0x002F;
    public static final int TLS_RSA_WITH_AES_128_CBC_SHA256 = 0x003C;

    public @interface TlsCipherSuite {}
}
