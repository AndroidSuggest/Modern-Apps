package android.service.euicc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.service.euicc.EuiccService}. Mirrors the AOSP abstract surface
 * the LPA must implement. Not packaged; the real class is provided by the
 * framework at runtime.
 */
public abstract class EuiccService extends Service {
    public static final int RESULT_OK = 0;
    public static final int RESULT_MUST_DEACTIVATE_SIM = 1;
    public static final int RESULT_RESOLVABLE_ERRORS = 2;
    public static final int RESULT_FIRST_USER = 10;

    public static final int OTA_STATUS_NOT_UPDATED = 1;

    public interface OtaStatusChangedCallback {
        void onOtaStatusChanged(int status);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public abstract String onGetEid(int slotId);

    public abstract int onGetOtaStatus(int slotId);

    public abstract void onStartOtaIfNecessary(int slotId, OtaStatusChangedCallback statusChangedCallback);

    public abstract GetDownloadableSubscriptionMetadataResult onGetDownloadableSubscriptionMetadata(
            int slotId, DownloadableSubscription subscription, boolean forceDeactivateSim);

    public abstract GetDefaultDownloadableSubscriptionListResult onGetDefaultDownloadableSubscriptionList(
            int slotId, boolean forceDeactivateSim);

    public abstract GetEuiccProfileInfoListResult onGetEuiccProfileInfoList(int slotId);

    public abstract EuiccInfo onGetEuiccInfo(int slotId);

    public abstract int onDeleteSubscription(int slotId, String iccid);

    public abstract int onSwitchToSubscription(int slotId, String iccid, boolean forceDeactivateSim);

    public abstract int onUpdateSubscriptionNickname(int slotId, String iccid, String nickname);

    public abstract int onEraseSubscriptions(int slotId);

    public abstract int onRetainSubscriptionsForFactoryReset(int slotId);

    public abstract int onDownloadSubscription(
            int slotId, DownloadableSubscription subscription, boolean switchAfterDownload,
            boolean forceDeactivateSim);
}
