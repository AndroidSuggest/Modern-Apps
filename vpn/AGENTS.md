# AGENTS.md — vpn/ (':vpn')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `vpn/` + allowed shared modules. Do not root-scan.

_Install: ./install vpn (dev by default)._

- Gradle: :vpn / dir: vpn/
- Package roots present: ui, data, platform, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:network
- Metadata: metadata_data/vpn.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/vpn/MainActivity.kt
- com/vayunmathur/vpn/Navigation.kt
- com/vayunmathur/vpn/Route.kt
- com/vayunmathur/vpn/data/ConnectionLogDao.kt
- com/vayunmathur/vpn/data/ConnectionLogEntity.kt
- com/vayunmathur/vpn/data/VpnConfig.kt
- com/vayunmathur/vpn/data/VpnDatabase.kt
- com/vayunmathur/vpn/data/VpnRepository.kt
- com/vayunmathur/vpn/platform/BypassList.kt
- com/vayunmathur/vpn/platform/VpnViewModel.kt
- com/vayunmathur/vpn/service/AppResolver.kt
- com/vayunmathur/vpn/service/ConnectionTracker.kt
- com/vayunmathur/vpn/service/DnsCache.kt
- com/vayunmathur/vpn/service/PacketInspector.kt
- com/vayunmathur/vpn/service/SniParser.kt
- com/vayunmathur/vpn/service/VpnTunnelService.kt
- com/vayunmathur/vpn/ui/BypassListPage.kt
- com/vayunmathur/vpn/ui/ConfigDetailPage.kt
- com/vayunmathur/vpn/ui/ConfigListContent.kt
- com/vayunmathur/vpn/ui/ConfigListPage.kt
- com/vayunmathur/vpn/ui/LoggingContent.kt
- com/vayunmathur/vpn/ui/LoggingPage.kt
- com/vayunmathur/vpn/ui/SettingsPage.kt
- com/vayunmathur/vpn/ui/VpnTabs.kt
- com/vayunmathur/vpn/ui/components/BypassHelpers.kt
- com/vayunmathur/vpn/util/VpnNative.kt

## Verify (this module only)
```
./gradlew :vpn:compileDevKotlin
./gradlew :vpn:lint
./gradlew :vpn:checkMetadata
```


