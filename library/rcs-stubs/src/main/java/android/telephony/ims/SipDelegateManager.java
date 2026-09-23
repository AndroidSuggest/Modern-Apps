package android.telephony.ims;

import android.telephony.ims.stub.DelegateConnectionMessageCallback;
import android.telephony.ims.stub.DelegateConnectionStateCallback;
import java.util.concurrent.Executor;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.SipDelegateManager}. Mirrors the AOSP method
 * surface RCS code uses. Not packaged; the real class is provided by the
 * framework at runtime.
 */
public class SipDelegateManager {
    public static final int MESSAGE_FAILURE_REASON_UNKNOWN = 0;
    public static final int MESSAGE_FAILURE_REASON_DELEGATE_DEAD = 1;
    public static final int MESSAGE_FAILURE_REASON_DELEGATE_CLOSED = 2;
    public static final int MESSAGE_FAILURE_REASON_INVALID_START_LINE = 3;
    public static final int MESSAGE_FAILURE_REASON_INVALID_HEADER_FIELDS = 4;
    public static final int MESSAGE_FAILURE_REASON_INVALID_BODY_CONTENT = 5;
    public static final int MESSAGE_FAILURE_REASON_INVALID_FEATURE_TAG = 6;
    public static final int MESSAGE_FAILURE_REASON_TAG_NOT_ENABLED_FOR_DELEGATE = 7;
    public static final int MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE = 8;
    public static final int MESSAGE_FAILURE_REASON_NOT_REGISTERED = 9;
    public static final int MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION = 10;
    public static final int MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION = 11;

    public static final int DENIED_REASON_UNKNOWN = 0;
    public static final int DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE = 1;
    public static final int DENIED_REASON_NOT_ALLOWED = 2;
    public static final int DENIED_REASON_SINGLE_REGISTRATION_NOT_ALLOWED = 3;
    public static final int DENIED_REASON_INVALID = 4;

    public static final int SIP_DELEGATE_DESTROY_REASON_UNKNOWN = 0;
    public static final int SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD = 1;
    public static final int SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP = 2;
    public static final int SIP_DELEGATE_DESTROY_REASON_USER_DISABLED_RCS = 3;
    public static final int SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN = 4;

    public @interface MessageFailureReason {}
    public @interface DeniedReason {}
    public @interface SipDelegateDestroyReason {}

    public boolean isSupported() throws ImsException {
        throw new RuntimeException("Stub");
    }

    public void createSipDelegate(
            DelegateRequest request,
            Executor executor,
            DelegateConnectionStateCallback dc,
            DelegateConnectionMessageCallback mc)
            throws ImsException {
        throw new RuntimeException("Stub");
    }

    public void destroySipDelegate(
            SipDelegateConnection delegateConnection,
            int reason)
            throws ImsException {
        throw new RuntimeException("Stub");
    }

    public void triggerFullNetworkRegistration(
            SipDelegateConnection connection,
            int sipCode,
            String sipReason)
            throws ImsException {
        throw new RuntimeException("Stub");
    }
}