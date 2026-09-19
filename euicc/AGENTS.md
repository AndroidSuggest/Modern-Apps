# AGENTS.md — euicc/ (':euicc')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `euicc/` + allowed shared modules. Do not root-scan.

_Install: ./install euicc (dev by default)._

- Gradle: :euicc / dir: euicc/
- Package roots present: ui, data, platform, service, telephony
- Entry files: Route.kt, Navigation.kt
- Deps: :library:euicc-stubs, :library:network
- Metadata: metadata_data/euicc.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/euicc/EuiccNative.kt
- com/vayunmathur/euicc/Navigation.kt
- com/vayunmathur/euicc/Route.kt
- com/vayunmathur/euicc/data/EuiccInfo.kt
- com/vayunmathur/euicc/data/Notification.kt
- com/vayunmathur/euicc/data/Profile.kt
- com/vayunmathur/euicc/platform/DownloadState.kt
- com/vayunmathur/euicc/platform/EuiccViewModel.kt
- com/vayunmathur/euicc/platform/LuiActivity.kt
- com/vayunmathur/euicc/service/EuiccManagerService.kt
- com/vayunmathur/euicc/telephony/EuiccChannelManager.kt
- com/vayunmathur/euicc/ui/ActivationCodeScreen.kt
- com/vayunmathur/euicc/ui/AddSimScreen.kt
- com/vayunmathur/euicc/ui/DeviceInfoScreen.kt
- com/vayunmathur/euicc/ui/DownloadScreen.kt
- com/vayunmathur/euicc/ui/EuiccHomeScreen.kt
- com/vayunmathur/euicc/ui/ProfileDetailScreen.kt
- com/vayunmathur/euicc/ui/QrScanner.kt
- com/vayunmathur/euicc/ui/dialogs/EidDialog.kt
- com/vayunmathur/euicc/ui/dialogs/RenameDialog.kt
- com/vayunmathur/euicc/ui/download/CompleteContent.kt
- com/vayunmathur/euicc/ui/download/ConfirmationCodeContent.kt
- com/vayunmathur/euicc/ui/download/ConfirmCarrierContent.kt
- com/vayunmathur/euicc/ui/download/FailedContent.kt
- com/vayunmathur/euicc/ui/download/InstallingContent.kt

## Verify (this module only)
```
./gradlew :euicc:compileDevKotlin
./gradlew :euicc:lint
./gradlew :euicc:checkMetadata
```


