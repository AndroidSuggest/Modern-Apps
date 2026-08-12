package android.telephony.euicc;

/**
 * Compile-only stub of the {@code @SystemApi}
 * {@code android.telephony.euicc.EuiccProfileInfo}. Only the members the LPA
 * constructs are declared. Not packaged; the framework provides the real class.
 */
public final class EuiccProfileInfo {
    public static final int PROFILE_STATE_UNSET = -1;
    public static final int PROFILE_STATE_DISABLED = 0;
    public static final int PROFILE_STATE_ENABLED = 1;

    public static final int PROFILE_CLASS_UNSET = -1;
    public static final int PROFILE_CLASS_TESTING = 0;
    public static final int PROFILE_CLASS_PROVISIONING = 1;
    public static final int PROFILE_CLASS_OPERATIONAL = 2;

    public static final class Builder {
        public Builder(String iccid) {}

        public Builder setNickname(String nickname) {
            return this;
        }

        public Builder setServiceProviderName(String serviceProviderName) {
            return this;
        }

        public Builder setProfileName(String profileName) {
            return this;
        }

        public Builder setState(int state) {
            return this;
        }

        public Builder setProfileClass(int profileClass) {
            return this;
        }

        public EuiccProfileInfo build() {
            return null;
        }
    }
}
