# AGENTS.md — passwords/ (':passwords')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `passwords/` + allowed shared modules. Do not root-scan.

_Install: ./install passwords (dev by default)._

- Gradle: :passwords / dir: passwords/
- Package roots present: ui, data, domain, platform, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:biometric, :library:room, :library:network
- Metadata: metadata_data/passwords.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/passwords/MainActivity.kt
- com/vayunmathur/passwords/Navigation.kt
- com/vayunmathur/passwords/Route.kt
- com/vayunmathur/passwords/data/Converters.kt
- com/vayunmathur/passwords/data/Passkey.kt
- com/vayunmathur/passwords/data/PasskeyStore.kt
- com/vayunmathur/passwords/data/Password.kt
- com/vayunmathur/passwords/data/PasswordDatabase.kt
- com/vayunmathur/passwords/data/PasswordRepository.kt
- com/vayunmathur/passwords/data/SyncSnapshot.kt
- com/vayunmathur/passwords/data/SyncSnapshotDao.kt
- com/vayunmathur/passwords/domain/Cbor.kt
- com/vayunmathur/passwords/domain/CredentialMerge.kt
- com/vayunmathur/passwords/domain/DomainMatch.kt
- com/vayunmathur/passwords/domain/ImportSource.kt
- com/vayunmathur/passwords/domain/PasskeyLink.kt
- com/vayunmathur/passwords/domain/TOTP.kt
- com/vayunmathur/passwords/platform/AppBackupAgent.kt
- com/vayunmathur/passwords/platform/KdbxBackupFormat.kt
- com/vayunmathur/passwords/platform/PasskeyCredentialService.kt
- com/vayunmathur/passwords/platform/PasskeyEntries.kt
- com/vayunmathur/passwords/platform/PasskeyUtils.kt
- com/vayunmathur/passwords/platform/PasswordAutofillService.kt
- com/vayunmathur/passwords/platform/PasswordsUiContract.kt
- com/vayunmathur/passwords/platform/PasswordsViewModel.kt
- com/vayunmathur/passwords/platform/cable/CableActivity.kt
- com/vayunmathur/passwords/platform/cable/CableAdvertiser.kt
- com/vayunmathur/passwords/platform/cable/CableEid.kt
- com/vayunmathur/passwords/platform/cable/CableKeys.kt
- com/vayunmathur/passwords/platform/cable/CableQr.kt
- com/vayunmathur/passwords/platform/cable/CableService.kt
- com/vayunmathur/passwords/platform/cable/CableSession.kt
- com/vayunmathur/passwords/platform/cable/CableTunnel.kt
- com/vayunmathur/passwords/platform/cable/CborReader.kt
- com/vayunmathur/passwords/platform/cable/Crypter.kt
- com/vayunmathur/passwords/platform/cable/Ctap.kt
- com/vayunmathur/passwords/platform/cable/CtapProcessor.kt
- com/vayunmathur/passwords/platform/cable/Noise.kt
- com/vayunmathur/passwords/platform/cable/P256.kt
- com/vayunmathur/passwords/platform/cable/TunnelDomains.kt
- com/vayunmathur/passwords/platform/cable/WebAuthnAuthenticator.kt
- com/vayunmathur/passwords/sync/EntryMapper.kt
- com/vayunmathur/passwords/sync/KdbxPasswordHelper.kt
- com/vayunmathur/passwords/sync/KdbxSyncEngine.kt
- com/vayunmathur/passwords/sync/KdbxSyncScheduler.kt
- com/vayunmathur/passwords/sync/KdbxSyncSettings.kt
- com/vayunmathur/passwords/sync/KdbxSyncWorker.kt
- com/vayunmathur/passwords/ui/AutofillSettingsActivity.kt
- com/vayunmathur/passwords/ui/MenuPage.kt
- com/vayunmathur/passwords/ui/MenuScreen.kt
- com/vayunmathur/passwords/ui/PasskeyAuthActivity.kt
- com/vayunmathur/passwords/ui/PasskeyLinkDialog.kt
- com/vayunmathur/passwords/ui/PasskeyPage.kt
- com/vayunmathur/passwords/ui/PasswordEditPage.kt
- com/vayunmathur/passwords/ui/PasswordEditScreen.kt
- com/vayunmathur/passwords/ui/PasswordPage.kt
- com/vayunmathur/passwords/ui/PasswordScreen.kt
- com/vayunmathur/passwords/ui/PasswordScreenSection.kt
- com/vayunmathur/passwords/ui/SettingsPage.kt
- com/vayunmathur/passwords/util/KdbxNative.kt

## Verify (this module only)
```
./gradlew :passwords:compileDevKotlin
./gradlew :passwords:lint
./gradlew :passwords:checkMetadata
```


