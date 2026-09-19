# AGENTS.md — files/ (':files')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `files/` + allowed shared modules. Do not root-scan.

_Install: ./install files (dev by default)._

- Gradle: :files / dir: files/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, FilesApplication.kt
- Deps: :library:image
- Metadata: metadata_data/files.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/files/FilesApplication.kt
- com/vayunmathur/files/MainActivity.kt
- com/vayunmathur/files/Navigation.kt
- com/vayunmathur/files/Route.kt
- com/vayunmathur/files/data/saf/DocumentPickerActivity.kt
- com/vayunmathur/files/data/saf/StorageUris.kt
- com/vayunmathur/files/platform/ArchiveLimits.kt
- com/vayunmathur/files/platform/FilesClipboard.kt
- com/vayunmathur/files/platform/FilesListing.kt
- com/vayunmathur/files/platform/FilesUiContract.kt
- com/vayunmathur/files/platform/FilesViewModel.kt
- com/vayunmathur/files/platform/ProgressNotificationWorker.kt
- com/vayunmathur/files/platform/TextEditorViewModel.kt
- com/vayunmathur/files/platform/UnzipWorker.kt
- com/vayunmathur/files/platform/ZipWorker.kt
- com/vayunmathur/files/ui/DirectoryBreadcrumbs.kt
- com/vayunmathur/files/ui/DirectoryDialogs.kt
- com/vayunmathur/files/ui/DirectoryLeading.kt
- com/vayunmathur/files/ui/DirectoryListing.kt
- com/vayunmathur/files/ui/DirectoryMenus.kt
- com/vayunmathur/files/ui/DirectoryPage.kt
- com/vayunmathur/files/ui/DirectoryParentPane.kt
- com/vayunmathur/files/ui/DirectoryScreen.kt
- com/vayunmathur/files/ui/FilesNavActions.kt
- com/vayunmathur/files/ui/FilesShell.kt
- com/vayunmathur/files/ui/HomeScreen.kt
- com/vayunmathur/files/ui/HomeScreenBinder.kt
- com/vayunmathur/files/ui/TextEditorActivity.kt
- com/vayunmathur/files/ui/components/DirectoryItem.kt
- com/vayunmathur/files/ui/components/GridItem.kt
- com/vayunmathur/files/ui/dialogs/PropertiesDialog.kt

## Verify (this module only)
```
./gradlew :files:compileDevKotlin
./gradlew :files:lint
./gradlew :files:checkMetadata
```


