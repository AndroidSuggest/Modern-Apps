plugins {
    id("common-conventions-library")
}

// Compile-only stubs for the framework's unbundled location/geocode provider API
// (`com.android.location.provider.*`). These classes exist at runtime on the device (provided
// via `<uses-library android:name="com.android.location.provider">`), so this module is depended
// on with `compileOnly` and its classes must NOT be packaged into any APK.
//
// Signatures are copied verbatim from the GrapheneOS/AOSP source in the platform tree
// (frameworks/base/location/lib/.../com/android/location/provider) so our provider subclasses
// override the real abstract methods exactly.
