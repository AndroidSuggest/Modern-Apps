# AGENTS.md — everysync/ (':everysync')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `everysync/` + allowed shared modules. Do not root-scan.

_Install: ./install everysync (dev by default)._

- Gradle: :everysync / dir: everysync/
- Package roots present: ui, data, domain, platform, network, provider, auth, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, EverySyncApplication.kt
- Deps: :library:network
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
- com/vayunmathur/everysync/auth/OAuthCallbackActivity.kt
- com/vayunmathur/everysync/auth/OAuthConfig.kt
- com/vayunmathur/everysync/auth/OAuthManager.kt
- com/vayunmathur/everysync/auth/TokenStore.kt
- com/vayunmathur/everysync/data/RemoteModels.kt
- com/vayunmathur/everysync/data/Settings.kt
- com/vayunmathur/everysync/data/sink/CalendarSink.kt
- com/vayunmathur/everysync/data/sink/ContactsSink.kt
- com/vayunmathur/everysync/data/sink/HealthSink.kt
- com/vayunmathur/everysync/domain/format/ICalendar.kt
- com/vayunmathur/everysync/domain/format/VCard.kt
- com/vayunmathur/everysync/network/remote/DavClient.kt
- com/vayunmathur/everysync/network/remote/DavSync.kt
- com/vayunmathur/everysync/network/remote/GoogleHealthClient.kt
- com/vayunmathur/everysync/platform/BootReceiver.kt
- com/vayunmathur/everysync/platform/EverySyncUiContract.kt
- com/vayunmathur/everysync/platform/EverySyncViewModel.kt
- com/vayunmathur/everysync/provider/DataType.kt
- com/vayunmathur/everysync/provider/ProviderRegistry.kt
- com/vayunmathur/everysync/provider/SyncProvider.kt
- com/vayunmathur/everysync/provider/SyncState.kt
- com/vayunmathur/everysync/provider/impl/DavProvider.kt
- com/vayunmathur/everysync/provider/impl/DavProviders.kt
- com/vayunmathur/everysync/provider/impl/GoogleProvider.kt
- com/vayunmathur/everysync/provider/impl/HealthOAuthProviders.kt
- com/vayunmathur/everysync/sync/EverySyncAdapter.kt
- com/vayunmathur/everysync/sync/SyncEngine.kt
- com/vayunmathur/everysync/sync/SyncScheduler.kt
- com/vayunmathur/everysync/sync/SyncStatus.kt
- com/vayunmathur/everysync/sync/SyncWorker.kt
- com/vayunmathur/everysync/ui/AccountDetailScreen.kt
- com/vayunmathur/everysync/ui/AccountsScreen.kt
- com/vayunmathur/everysync/ui/AddAccountScreen.kt
- com/vayunmathur/everysync/ui/DavLoginScreen.kt
- com/vayunmathur/everysync/ui/PermissionsRationaleActivity.kt
- com/vayunmathur/everysync/ui/SettingsScreen.kt

## Verify (this module only)
```
./gradlew :everysync:compileDevKotlin
./gradlew :everysync:lint
./gradlew :everysync:checkMetadata
```


