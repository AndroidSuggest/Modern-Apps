package android.telephony.ims.stub;

import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateManager;
import java.util.Set;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.stub.DelegateConnectionStateCallback}. Not
 * packaged; the real class is provided by the framework at runtime.
 */
public interface DelegateConnectionStateCallback {
    void onCreated(SipDelegateConnection c);

    void onFeatureTagStatusChanged(
            DelegateRegistrationState registrationState,
            Set<FeatureTagState> deniedFeatureTags);

    void onConfigurationChanged(SipDelegateConfiguration registeredSipConfig);

    void onDestroyed(@SipDelegateManager.SipDelegateDestroyReason int reason);
}