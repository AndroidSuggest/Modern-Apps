package android.telephony.ims;


/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.SipMessage}. Not packaged; the real class is
 * provided by the framework at runtime.
 */
public final class SipMessage {
    public SipMessage(
            String startLine,
            String headerSection,
            byte[] content) {
        throw new RuntimeException("Stub");
    }

    public String getStartLine() {
        throw new RuntimeException("Stub");
    }

    public String getHeaderSection() {
        throw new RuntimeException("Stub");
    }

    public byte[] getContent() {
        throw new RuntimeException("Stub");
    }

    public String getViaBranchParameter() {
        throw new RuntimeException("Stub");
    }

    public String getCallIdParameter() {
        throw new RuntimeException("Stub");
    }

    public byte[] toEncodedMessage() {
        throw new RuntimeException("Stub");
    }
}