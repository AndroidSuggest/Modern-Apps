package android.telephony.ims;

import android.net.Uri;
import java.util.List;
import java.util.Set;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.RcsContactUceCapability}. Not packaged; the
 * real class is provided by the framework at runtime.
 */
public final class RcsContactUceCapability {
    public static final int CAPABILITY_MECHANISM_PRESENCE = 1;
    public static final int CAPABILITY_MECHANISM_OPTIONS = 2;
    public static final int SOURCE_TYPE_NETWORK = 0;
    public static final int SOURCE_TYPE_CACHED = 1;
    public static final int REQUEST_RESULT_UNKNOWN = 0;
    public static final int REQUEST_RESULT_NOT_ONLINE = 1;
    public static final int REQUEST_RESULT_NOT_FOUND = 2;
    public static final int REQUEST_RESULT_FOUND = 3;

    public @interface CapabilityMechanism {}
    public @interface SourceType {}
    public @interface RequestResult {}

    public Uri getContactUri() {
        throw new RuntimeException("Stub");
    }

    public Set<String> getFeatureTags() {
        throw new RuntimeException("Stub");
    }

    public @CapabilityMechanism int getCapabilityMechanism() {
        throw new RuntimeException("Stub");
    }

    public @SourceType int getSourceType() {
        throw new RuntimeException("Stub");
    }

    public @RequestResult int getRequestResult() {
        throw new RuntimeException("Stub");
    }

    public Uri getEntityUri() {
        throw new RuntimeException("Stub");
    }

    public List<String> getCapabilityTuples() {
        throw new RuntimeException("Stub");
    }
}