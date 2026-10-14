package android.telephony.ims.stub;

import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.stub.DelegateConnectionMessageCallback}. Not
 * packaged; the real class is provided by the framework at runtime.
 */
public interface DelegateConnectionMessageCallback {
    void onMessageReceived(SipMessage message);

    void onMessageSent(String viaTransactionId);

    void onMessageSendFailure(
            String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}