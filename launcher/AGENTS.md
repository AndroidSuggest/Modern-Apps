# AGENTS.md — launcher/ (':launcher')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `launcher/` + allowed shared modules. Do not root-scan.

_Install: ./install launcher (dev by default)._

- Gradle: :launcher / dir: launcher/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room
- Metadata: metadata_data/launcher.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/launcher/MainActivity.kt
- com/vayunmathur/launcher/Navigation.kt
- com/vayunmathur/launcher/Route.kt
- com/vayunmathur/launcher/data/LauncherDatabase.kt
- com/vayunmathur/launcher/data/LauncherItemEntity.kt
- com/vayunmathur/launcher/data/LauncherRepository.kt
- com/vayunmathur/launcher/domain/AutoPlacer.kt
- com/vayunmathur/launcher/domain/CellOccupancy.kt
- com/vayunmathur/launcher/domain/CellRect.kt
- com/vayunmathur/launcher/domain/ContainerRef.kt
- com/vayunmathur/launcher/domain/DropBarTargets.kt
- com/vayunmathur/launcher/domain/FastScroll.kt
- com/vayunmathur/launcher/domain/FolderMerge.kt
- com/vayunmathur/launcher/domain/FolderRules.kt
- com/vayunmathur/launcher/domain/GridPlacer.kt
- com/vayunmathur/launcher/domain/GridPreview.kt
- com/vayunmathur/launcher/domain/GridReorder.kt
- com/vayunmathur/launcher/domain/HotseatArrange.kt
- com/vayunmathur/launcher/domain/LauncherItemType.kt
- com/vayunmathur/launcher/domain/LauncherTuning.kt
- com/vayunmathur/launcher/domain/PageCount.kt
- com/vayunmathur/launcher/domain/ReconcileUseCase.kt
- com/vayunmathur/launcher/domain/ReorderDwell.kt
- com/vayunmathur/launcher/domain/WidgetResize.kt
- com/vayunmathur/launcher/platform/ActivityBridge.kt
- com/vayunmathur/launcher/platform/IconCache.kt
- com/vayunmathur/launcher/platform/LauncherAppsMonitor.kt
- com/vayunmathur/launcher/platform/LauncherPredictions.kt
- com/vayunmathur/launcher/platform/LauncherPrivilege.kt
- com/vayunmathur/launcher/platform/LauncherUiContract.kt
- com/vayunmathur/launcher/platform/LauncherViewModel.kt
- com/vayunmathur/launcher/platform/LauncherWidgetHost.kt
- com/vayunmathur/launcher/platform/LauncherWidgets.kt
- com/vayunmathur/launcher/ui/DrawerCells.kt
- com/vayunmathur/launcher/ui/DrawerContent.kt
- com/vayunmathur/launcher/ui/DrawerFastScroll.kt
- com/vayunmathur/launcher/ui/FolderContent.kt
- com/vayunmathur/launcher/ui/HomeConstants.kt
- com/vayunmathur/launcher/ui/HomeContent.kt
- com/vayunmathur/launcher/ui/HomeDrawerSwipe.kt
- com/vayunmathur/launcher/ui/HomeDropLogic.kt
- com/vayunmathur/launcher/ui/HomeEffects.kt
- com/vayunmathur/launcher/ui/HomeGestureRoot.kt
- com/vayunmathur/launcher/ui/HomeLaunch.kt
- com/vayunmathur/launcher/ui/HomeOverlays.kt
- com/vayunmathur/launcher/ui/HomePage.kt
- com/vayunmathur/launcher/ui/HomePopups.kt
- com/vayunmathur/launcher/ui/HomeWorkspace.kt
- com/vayunmathur/launcher/ui/HotseatBar.kt
- com/vayunmathur/launcher/ui/ItemMenuContent.kt
- com/vayunmathur/launcher/ui/SettingsContent.kt
- com/vayunmathur/launcher/ui/SettingsPage.kt
- com/vayunmathur/launcher/ui/WidgetPickerContent.kt
- com/vayunmathur/launcher/ui/WorkspaceGrid.kt
- com/vayunmathur/launcher/ui/WorkspaceOptionsContent.kt
- com/vayunmathur/launcher/ui/WorkspacePage.kt
- com/vayunmathur/launcher/ui/components/AppWindowBounds.kt
- com/vayunmathur/launcher/ui/components/CellLayout.kt
- com/vayunmathur/launcher/ui/components/CellPlacement.kt
- com/vayunmathur/launcher/ui/components/DragFeedback.kt
- com/vayunmathur/launcher/ui/components/DragLayer.kt
- com/vayunmathur/launcher/ui/components/DragSourceModifier.kt
- com/vayunmathur/launcher/ui/components/DropBar.kt
- com/vayunmathur/launcher/ui/components/DropTargetModifier.kt
- com/vayunmathur/launcher/ui/components/FastScrollStrip.kt
- com/vayunmathur/launcher/ui/components/HostedWidget.kt
- com/vayunmathur/launcher/ui/components/LauncherAppIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherDragController.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInput.kt
- com/vayunmathur/launcher/ui/components/LauncherDragInputSection.kt
- com/vayunmathur/launcher/ui/components/LauncherItemIcon.kt
- com/vayunmathur/launcher/ui/components/LauncherPopup.kt
- com/vayunmathur/launcher/ui/components/LauncherPopupSurface.kt
- com/vayunmathur/launcher/ui/components/MergeRing.kt
- com/vayunmathur/launcher/ui/components/MissingWidget.kt
- com/vayunmathur/launcher/ui/components/PageEdgeDwell.kt
- com/vayunmathur/launcher/ui/components/PageIndicator.kt
- com/vayunmathur/launcher/ui/components/ReorderPreview.kt
- com/vayunmathur/launcher/ui/components/WidgetResizeFrame.kt

## Verify (this module only)
```
./gradlew :launcher:compileDevKotlin
./gradlew :launcher:lint
./gradlew :launcher:checkMetadata
```


