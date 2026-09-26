package android.telephony.gba;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.gba.UaSecurityProtocolIdentifier}. Mirrors the
 * AOSP surface used for GBA bootstrapping (org/protocol/cipher-suite
 * constants + Builder). Not packaged; the real class is provided by the
 * framework at runtime.
 */
public final class UaSecurityProtocolIdentifier {
    public static final int ORG_NONE = 0;
    public static final int ORG_3GPP = 0x01;
    public static final int ORG_3GPP2 = 0x02;
    public static final int ORG_OMA = 0x03;
    public static final int ORG_GSMA = 0x04;
    public static final int ORG_LOCAL = 0xFF;

    public static final int UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION = 2;

    public @interface OrganizationCode {}
    public @interface UaSecurityProtocol3gpp {}

    public static final class Builder {
        public Builder() {
            throw new RuntimeException("Stub");
        }

        public Builder setOrg(@OrganizationCode int orgCode) {
            throw new RuntimeException("Stub");
        }

        public Builder setProtocol(@UaSecurityProtocol3gpp int protocol) {
            throw new RuntimeException("Stub");
        }

        public Builder setTlsCipherSuite(@TlsParams.TlsCipherSuite int cs) {
            throw new RuntimeException("Stub");
        }

        public UaSecurityProtocolIdentifier build() {
            throw new RuntimeException("Stub");
        }
    }
}
