plugins {
    id("common-conventions-library")
}
// Compile-only stubs for framework @SystemApi / hidden classes absent from the public SDK.
// Depend on this with `compileOnly`; it must NOT be packaged into any APK.
//
// One module holds every stub family so consumers take a single dependency:
//   android.app.backup.*            — @SystemApi app-data backup transport (BackupTransport,
//                                      RestoreDescription, RestoreSet). Mirrors AOSP so
//                                      ConfigurableBackupTransport overrides exactly. Live use
//                                      needs a platform-signed priv-app whitelisted for
//                                      android.permission.BACKUP.
//   android.app.supervision.*       — @SystemApi parental-controls classes. `SupervisionAppService.onBind`
//                                      is FINAL upstream and final here; `Policy.Builder<P, B>` generics
//                                      are mirrored exactly so javac emits the same invokevirtual +
//                                      checkcast as against the real SDK. Inlined `static final`
//                                      constant values matter (TYPE_ALLOWED/BLOCKED/TIME_LIMIT are 0/1/2).
//   android.nearby.NearbyManager    — @SystemApi, ships in the Connectivity mainline module
//                                      (com.android.tethering); only the powered-off finding surface.
//   android.os.UpdateEngine{,Callback} — @SystemApi A/B slot writer; only the surface :updater calls.
//   android.service.euicc.*         — @SystemApi eSIM LPA classes; signatures mirror AOSP so
//                                      EuiccManagerService overrides the abstract methods exactly.
//   android.telephony.ims.* (+ gba) — @SystemApi single-registration RCS (SipDelegateManager,
//                                      DelegateRequest, SipMessage, ...). Same-FQN stubs for the two
//                                      otherwise-public classes (ImsManager, RcsUceAdapter) carry only the
//                                      hidden members used plus the public members RCS code touches, so
//                                      resolution is self-contained; the framework class wins at runtime.
//   com.android.media.remotedisplay.* — NOT hidden framework classes but a separate Java shared library
//                                      (com.android.media.remotedisplay); the app must also carry a
//                                      `<uses-library>` tag. Only the members MaRemoteDisplayProvider touches.
//
// A stub is a compile-time promise about the runtime class: every member here is one more thing that
// has to stay true. Signatures copied verbatim from AOSP/MAOS; if they drift, the failure is a runtime
// NoSuchMethodError, not a build error. Javac inlines `static final` constants, so their VALUES are
// silently baked into the APK — never add an unused constant.
