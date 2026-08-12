plugins {
    id("common-conventions-library")
}

// Compile-only stubs for the framework's @SystemApi eSIM LPA classes
// (`android.service.euicc.*`, `android.telephony.euicc.EuiccProfileInfo`). These
// classes exist in the base framework at runtime on every device but are hidden
// from the public SDK, so this module is depended on with `compileOnly` and its
// classes must NOT be packaged into any APK.
//
// Signatures mirror AOSP so `EuiccManagerService` overrides the real abstract
// methods exactly. Live behavior requires a platform-signed priv-app install.
