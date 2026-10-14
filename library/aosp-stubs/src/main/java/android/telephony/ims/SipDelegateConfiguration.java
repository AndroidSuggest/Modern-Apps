package android.telephony.ims;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.SipDelegateConfiguration}. Carries the getters
 * RCS code reads (mirrors the AOSP surface; see TestRcsApp's
 * RegistrationControllerImpl for the consumer side). Not packaged; the real
 * class is provided by the framework at runtime.
 */
public final class SipDelegateConfiguration {
    public static final int SIP_TRANSPORT_UDP = 0;
    public static final int SIP_TRANSPORT_TCP = 1;
    public static final int SIP_TRANSPORT_TLS = 2;

    public long getVersion() {
        throw new RuntimeException("Stub");
    }

    public int getTransportType() {
        throw new RuntimeException("Stub");
    }

    public String getPublicUserIdentifier() {
        throw new RuntimeException("Stub");
    }

    public String getPrivateUserIdentifier() {
        throw new RuntimeException("Stub");
    }

    public String getHomeDomain() {
        throw new RuntimeException("Stub");
    }

    public String getImei() {
        throw new RuntimeException("Stub");
    }

    public String getPublicGruuUri() {
        throw new RuntimeException("Stub");
    }

    public String getSipAuthenticationHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipAuthenticationNonce() {
        throw new RuntimeException("Stub");
    }

    public String getSipServiceRouteHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipPathHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipUserAgentHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipContactUserParameter() {
        throw new RuntimeException("Stub");
    }

    public String getSipPaniHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipPlaniHeader() {
        throw new RuntimeException("Stub");
    }

    public String getSipAssociatedUriHeader() {
        throw new RuntimeException("Stub");
    }

    public int getMaxUdpPayloadSizeBytes() {
        throw new RuntimeException("Stub");
    }

    public boolean isSipCompactFormEnabled() {
        throw new RuntimeException("Stub");
    }

    public boolean isSipKeepaliveEnabled() {
        throw new RuntimeException("Stub");
    }
}
