plugins {
    id("common-conventions-library")
}

// Compile-only stubs for the framework's @SystemApi app-data backup transport API
// (`android.app.backup.BackupTransport` + `RestoreDescription` / `RestoreSet`). These
// classes exist in the base framework at runtime on every device but are hidden from
// the public SDK, so this module is depended on with `compileOnly` and its classes
// must NOT be packaged into any APK.
//
// Signatures and constant values mirror AOSP so `ConfigurableBackupTransport`
// overrides the real methods exactly. Live behavior requires a platform-signed
// priv-app install whitelisted for android.permission.BACKUP.
