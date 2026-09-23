package android.telephony.ims;

import java.util.Set;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.telephony.ims.DelegateRegistrationState}. Carries only the
 * getters RCS code reads. Not packaged; the real class is provided by the
 * framework at runtime.
 */
public final class DelegateRegistrationState {
    public Set<String> getRegisteringFeatureTags() {
        throw new RuntimeException("Stub");
    }

    public Set<String> getRegisteredFeatureTags() {
        throw new RuntimeException("Stub");
    }

    public Set<FeatureTagState> getDeregisteringFeatureTags() {
        throw new RuntimeException("Stub");
    }

    public Set<FeatureTagState> getDeregisteredFeatureTags() {
        throw new RuntimeException("Stub");
    }
}