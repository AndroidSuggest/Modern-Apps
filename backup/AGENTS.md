# AGENTS.md — backup/ (':backup')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `backup/` + allowed shared modules. Do not root-scan.

_Install: ./install backup (dev by default)._

- Gradle: :backup / dir: backup/
- Package roots present: ui, data, domain, platform, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:backup-stubs, :library:network, :library:work, :library:biometric, :library:e2ee-p2p
- Metadata: metadata_data/backup.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/backup/MainActivity.kt
- com/vayunmathur/backup/Navigation.kt
- com/vayunmathur/backup/Route.kt
- com/vayunmathur/backup/data/BackupConfig.kt
- com/vayunmathur/backup/data/backend/BackendFactory.kt
- com/vayunmathur/backup/data/backend/BackupBackend.kt
- com/vayunmathur/backup/data/backend/BackupRepository.kt
- com/vayunmathur/backup/data/backend/SafBackend.kt
- com/vayunmathur/backup/data/backend/WebDavBackend.kt
- com/vayunmathur/backup/data/files/FileBackupManager.kt
- com/vayunmathur/backup/data/files/FileMetadata.kt
- com/vayunmathur/backup/domain/crypto/Bip39.kt
- com/vayunmathur/backup/domain/crypto/Crypto.kt
- com/vayunmathur/backup/platform/BackupViewModel.kt
- com/vayunmathur/backup/platform/FileBackupWorker.kt
- com/vayunmathur/backup/platform/crypto/KeyManager.kt
- com/vayunmathur/backup/service/ConfigurableBackupTransport.kt
- com/vayunmathur/backup/service/ConfigurableBackupTransportService.kt
- com/vayunmathur/backup/ui/DashboardScreen.kt
- com/vayunmathur/backup/ui/OnboardingScreen.kt
- com/vayunmathur/backup/ui/components/BackendSection.kt

## Verify (this module only)
```
./gradlew :backup:compileDevKotlin
./gradlew :backup:lint
./gradlew :backup:checkMetadata
```


