# AGENTS.md — everysync/ (':everysync')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `everysync/` + allowed shared modules. Do not root-scan.

_Install: ./install everysync (dev by default)._

- Gradle: :everysync / dir: everysync/
- Package roots present: ui, data, domain, platform, network, provider, auth, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, EverySyncApplication.kt
- Deps: :library:network, :library:e2ee-p2p
- Metadata: metadata_data/everysync.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/everysync/EverySyncApplication.kt
- com/vayunmathur/everysync/MainActivity.kt
- com/vayunmathur/everysync/Navigation.kt
- com/vayunmathur/everysync/Route.kt
- com/vayunmathur/everysync/auth/AccountConfig.kt
- com/vayunmathur/everysync/auth/AccountStore.kt
- com/vayunmathur/everysync/auth/Authenticator.kt
- com/vayunmathur/everysync/auth/TokenStore.kt
- com/vayunmathur/everysync/data/RemoteModels.kt
- com/vayunmathur/everysync/data/Settings.kt
- com/vayunmathur/everysync/data/VaultStore.kt
- com/vayunmathur/everysync/data/sink/CalendarSink.kt
- com/vayunmathur/everysync/data/sink/ContactsSink.kt
- com/vayunmathur/everysync/data/sink/NoteSink.kt
- com/vayunmathur/everysync/data/sink/PasswordSink.kt
- com/vayunmathur/everysync/data/sink/TodoIpcSink.kt
- com/vayunmathur/everysync/data/sink/TodoSink.kt
- com/vayunmathur/everysync/domain/format/ICalendar.kt
- com/vayunmathur/everysync/domain/format/VCard.kt
- com/vayunmathur/everysync/network/remote/RelayCursorStore.kt
- com/vayunmathur/everysync/network/remote/RelaySync.kt
- com/vayunmathur/everysync/platform/BootReceiver.kt
- com/vayunmathur/everysync/platform/EverySyncUiContract.kt
- com/vayunmathur/everysync/platform/EverySyncVaults.kt
- com/vayunmathur/everysync/platform/EverySyncViewModel.kt
- com/vayunmathur/everysync/provider/DataType.kt
- com/vayunmathur/everysync/provider/ProviderRegistry.kt
- com/vayunmathur/everysync/provider/SyncProvider.kt
- com/vayunmathur/everysync/provider/SyncState.kt
- com/vayunmathur/everysync/provider/impl/E2eeProvider.kt
- com/vayunmathur/everysync/sync/OwnerTransfer.kt
- com/vayunmathur/everysync/sync/RelayBlobStore.kt
- com/vayunmathur/everysync/sync/RelayMerge.kt
- com/vayunmathur/everysync/sync/SyncEngine.kt
- com/vayunmathur/everysync/sync/SyncScheduler.kt
- com/vayunmathur/everysync/sync/SyncStatus.kt
- com/vayunmathur/everysync/sync/SyncWorker.kt
- com/vayunmathur/everysync/sync/VaultKeys.kt
- com/vayunmathur/everysync/sync/VaultRoster.kt
- com/vayunmathur/everysync/sync/adapters/CalendarKind.kt
- com/vayunmathur/everysync/sync/adapters/ContactKind.kt
- com/vayunmathur/everysync/sync/adapters/NoteKind.kt
- com/vayunmathur/everysync/sync/adapters/PasswordKind.kt
- com/vayunmathur/everysync/sync/adapters/TodoKind.kt
- com/vayunmathur/everysync/ui/AccountDetailScreen.kt
- com/vayunmathur/everysync/ui/AccountsScreen.kt
- com/vayunmathur/everysync/ui/AddAccountScreen.kt
- com/vayunmathur/everysync/ui/SettingsScreen.kt
- com/vayunmathur/everysync/ui/VaultSharingSection.kt

## Verify (this module only)
```
./gradlew :everysync:compileDevKotlin
./gradlew :everysync:lint
./gradlew :everysync:checkMetadata
```


