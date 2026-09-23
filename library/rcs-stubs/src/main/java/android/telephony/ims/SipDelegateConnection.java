package android.telephony.ims;


/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.SipDelegateConnection}. Not packaged; the real
 * class is provided by the framework at runtime.
 */
public interface SipDelegateConnection {
    void sendMessage(SipMessage sipMessage, long configVersion);

    void notifyMessageReceived(String viaTransactionId);

    void cleanupSession(String callId);

    void notifyMessageReceiveError(
            String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}