# AGENTS.md — appstore/ (':appstore')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `appstore/` + allowed shared modules. Do not root-scan.

_Install: ./install appstore (dev by default)._

- Gradle: :appstore / dir: appstore/
- Package roots present: ui, data, domain
- Entry files: MainActivity.kt
- Deps: :library:room, :library:network, :library:work, :library:image
- Metadata: metadata_data/appstore.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/appstore/MainActivity.kt
- com/vayunmathur/appstore/data/AppDatabase.kt
- com/vayunmathur/appstore/data/AppModels.kt
- com/vayunmathur/appstore/data/AppProvider.kt
- com/vayunmathur/appstore/data/AppStoreDatabaseRepository.kt
- com/vayunmathur/appstore/data/CatalogRepository.kt
- com/vayunmathur/appstore/data/FDroidAppProvider.kt
- com/vayunmathur/appstore/data/FDroidRepository.kt
- com/vayunmathur/appstore/data/FDroidVerification.kt
- com/vayunmathur/appstore/data/InstalledAppsRepository.kt
- com/vayunmathur/appstore/data/PlayStoreLinks.kt
- com/vayunmathur/appstore/data/RestrictedPackages.kt
- com/vayunmathur/appstore/data/SandboxedGooglePlay.kt
- com/vayunmathur/appstore/data/SettingsRepository.kt
- com/vayunmathur/appstore/data/UpdateCheckWorker.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentApi.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepo.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepoData.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentRepository.kt
- com/vayunmathur/appstore/data/accrescent/AccrescentTrustStore.kt
- com/vayunmathur/appstore/data/accrescent/DeviceAttributesProvider.kt
- com/vayunmathur/appstore/data/accrescent/SignifyNative.kt
- com/vayunmathur/appstore/data/grapheneos/GrapheneOSIndex.kt
- com/vayunmathur/appstore/data/grapheneos/GrapheneOSRepo.kt
- com/vayunmathur/appstore/data/grapheneos/GrapheneOSRepository.kt
- com/vayunmathur/appstore/data/installer/InstallCoordinator.kt
- com/vayunmathur/appstore/data/installer/InstallStatusReceiver.kt
- com/vayunmathur/appstore/data/installer/PlayDownloader.kt
- com/vayunmathur/appstore/data/installer/SessionInstaller.kt
- com/vayunmathur/appstore/data/play/AnonymousAuthRepository.kt
- com/vayunmathur/appstore/data/play/CertUtil.kt
- com/vayunmathur/appstore/data/play/DeviceInfoProvider.kt
- com/vayunmathur/appstore/data/play/EglExtensionProvider.kt
- com/vayunmathur/appstore/data/play/GsfVersionProvider.kt
- com/vayunmathur/appstore/data/play/PlayHttpClient.kt
- com/vayunmathur/appstore/data/play/PlayRepository.kt
- com/vayunmathur/appstore/data/play/PlayStoreApi.kt
- com/vayunmathur/appstore/data/security/ApkCertificates.kt
- com/vayunmathur/appstore/data/security/InstallVerifier.kt
- com/vayunmathur/appstore/data/security/SignedJarIndex.kt
- com/vayunmathur/appstore/data/security/Signify.kt
- com/vayunmathur/appstore/data/security/SourceStamp.kt
- com/vayunmathur/appstore/data/security/TrustProfile.kt
- com/vayunmathur/appstore/domain/SearchRanking.kt
- com/vayunmathur/appstore/ui/AppComponents.kt
- com/vayunmathur/appstore/ui/AppDetailHeader.kt
- com/vayunmathur/appstore/ui/AppDetailPage.kt
- com/vayunmathur/appstore/ui/AppDetailSections.kt
- com/vayunmathur/appstore/ui/AppMeta.kt
- com/vayunmathur/appstore/ui/AppTile.kt
- com/vayunmathur/appstore/ui/HomePage.kt
- com/vayunmathur/appstore/ui/LibraryPage.kt
- com/vayunmathur/appstore/ui/SearchPage.kt
- com/vayunmathur/appstore/ui/SourcesPage.kt
- com/vayunmathur/appstore/ui/TrustPage.kt
- com/vayunmathur/appstore/ui/UpdatesPage.kt
- com/vayunmathur/appstore/util/AppStoreActionOps.kt
- com/vayunmathur/appstore/util/AppStoreDetailOps.kt
- com/vayunmathur/appstore/util/AppStoreHomeOps.kt
- com/vayunmathur/appstore/util/AppStoreSearchOps.kt
- com/vayunmathur/appstore/util/AppStoreUiContract.kt
- com/vayunmathur/appstore/util/AppStoreUpdateOps.kt
- com/vayunmathur/appstore/util/AppStoreViewModel.kt

## Verify (this module only)
```
./gradlew :appstore:compileDevKotlin
./gradlew :appstore:lint
./gradlew :appstore:checkMetadata
```


