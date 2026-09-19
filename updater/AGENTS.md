# AGENTS.md — updater/ (':updater')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `updater/` + allowed shared modules. Do not root-scan.

_Install: ./install updater (dev by default)._

- Gradle: :updater / dir: updater/
- Package roots present: ui, domain, platform, service, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:work, :library:updateengine-stubs
- Metadata: metadata_data/updater.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/updater/MainActivity.kt
- com/vayunmathur/updater/Navigation.kt
- com/vayunmathur/updater/Route.kt
- com/vayunmathur/updater/domain/AutoInstallPolicy.kt
- com/vayunmathur/updater/domain/OtaArtifacts.kt
- com/vayunmathur/updater/domain/OtaDownloadPlan.kt
- com/vayunmathur/updater/domain/OtaPackageMetadata.kt
- com/vayunmathur/updater/domain/OtaPackageValidation.kt
- com/vayunmathur/updater/domain/PayloadProperties.kt
- com/vayunmathur/updater/domain/UpdateComparison.kt
- com/vayunmathur/updater/domain/UpdateEngineOutcome.kt
- com/vayunmathur/updater/domain/UpdateMetadata.kt
- com/vayunmathur/updater/notifications/UpdateNotifications.kt
- com/vayunmathur/updater/platform/IdleReboot.kt
- com/vayunmathur/updater/platform/OtaDownloader.kt
- com/vayunmathur/updater/platform/OtaInstaller.kt
- com/vayunmathur/updater/platform/RebootReceiver.kt
- com/vayunmathur/updater/platform/SystemBuild.kt
- com/vayunmathur/updater/platform/UpdateBootReceiver.kt
- com/vayunmathur/updater/platform/UpdateChecker.kt
- com/vayunmathur/updater/platform/UpdateCheckWorker.kt
- com/vayunmathur/updater/platform/UpdaterPreferences.kt
- com/vayunmathur/updater/platform/UpdaterViewModel.kt
- com/vayunmathur/updater/service/UpdateInstallService.kt
- com/vayunmathur/updater/ui/UpdaterScreen.kt

## Verify (this module only)
```
./gradlew :updater:compileDevKotlin
./gradlew :updater:lint
./gradlew :updater:checkMetadata
```


