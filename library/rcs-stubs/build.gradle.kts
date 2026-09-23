plugins {
    id("common-conventions-library")
}

// Compile-only stubs for the framework's @SystemApi single-registration RCS classes
// (`android.telephony.ims.SipDelegateManager`, `DelegateRequest`, `SipMessage`, ...). These
// classes exist in the base framework at runtime on every device but are hidden
// from the public SDK, so this module is depended on with `compileOnly` and its
// classes must NOT be packaged into any APK.
//
// Signatures mirror AOSP (verified against frameworks/base telephony/java on main).
// Same-FQN stubs for the two otherwise-public classes (`ImsManager`, `RcsUceAdapter`)
// carry only the hidden members used plus the public members RCS code touches, so
// resolution is self-contained within the stub at compile time; the framework class
// wins at runtime.
