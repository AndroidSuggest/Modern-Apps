# AGENTS.md — emergency/ (':emergency')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `emergency/` + allowed shared modules. Do not root-scan.

_Install: ./install emergency (dev by default)._

- Gradle: :emergency / dir: emergency/
- Package roots present: ui, data, domain, platform, service, notifications
- Entry files: 
- Deps: 
- Metadata: metadata_data/emergency.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/emergency/data/EmergencyInfo.kt
- com/vayunmathur/emergency/data/EmergencyRepository.kt
- com/vayunmathur/emergency/data/ImportedMedical.kt
- com/vayunmathur/emergency/domain/FhirMedicalParse.kt
- com/vayunmathur/emergency/domain/OwnerPostalFormat.kt
- com/vayunmathur/emergency/notifications/SosNotifications.kt
- com/vayunmathur/emergency/platform/ContactReader.kt
- com/vayunmathur/emergency/platform/EmergencyNumberLookup.kt
- com/vayunmathur/emergency/platform/EmergencySearchIndexablesProvider.kt
- com/vayunmathur/emergency/platform/EmergencyUiContract.kt
- com/vayunmathur/emergency/platform/EmergencyViewModel.kt
- com/vayunmathur/emergency/platform/GestureProvider.kt
- com/vayunmathur/emergency/platform/HealthConnectMedical.kt
- com/vayunmathur/emergency/platform/OwnerIdentityReader.kt
- com/vayunmathur/emergency/platform/SosCoordinator.kt
- com/vayunmathur/emergency/platform/SosSound.kt
- com/vayunmathur/emergency/receiver/SosActionReceiver.kt
- com/vayunmathur/emergency/service/SosCountdownService.kt
- com/vayunmathur/emergency/ui/EditInfoScreen.kt
- com/vayunmathur/emergency/ui/OwnerAddressDialog.kt
- com/vayunmathur/emergency/ui/PermissionsRationaleActivity.kt
- com/vayunmathur/emergency/ui/SosScreen.kt
- com/vayunmathur/emergency/ui/ViewInfoScreen.kt

## Verify (this module only)
```
./gradlew :emergency:compileDevKotlin
./gradlew :emergency:lint
./gradlew :emergency:checkMetadata
```


