# AGENTS.md — screentime/ (':screentime')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `screentime/` + allowed shared modules. Do not root-scan.

_Install: ./install screentime (dev by default)._

- Gradle: :screentime / dir: screentime/
- Package roots present: ui, data, domain, platform, service, widget, notifications
- Entry files: 
- Deps: :library:room, :library:widgets
- Metadata: metadata_data/screentime.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/screentime/data/Migrations.kt
- com/vayunmathur/screentime/data/Rules.kt
- com/vayunmathur/screentime/data/ScreenTimeDatabase.kt
- com/vayunmathur/screentime/domain/HourBuckets.kt
- com/vayunmathur/screentime/platform/AppTimers.kt
- com/vayunmathur/screentime/platform/Coordinator.kt
- com/vayunmathur/screentime/platform/DndState.kt
- com/vayunmathur/screentime/platform/ScreenTimeViewModel.kt
- com/vayunmathur/screentime/platform/Suspender.kt
- com/vayunmathur/screentime/platform/UsageAccess.kt
- com/vayunmathur/screentime/platform/UsageHistory.kt
- com/vayunmathur/screentime/platform/WindDownApplier.kt
- com/vayunmathur/screentime/platform/WindowScheduler.kt
- com/vayunmathur/screentime/receiver/Receivers.kt
- com/vayunmathur/screentime/service/Services.kt
- com/vayunmathur/screentime/ui/AppDetailsScreen.kt
- com/vayunmathur/screentime/ui/AppIconImage.kt
- com/vayunmathur/screentime/ui/DashboardActivity.kt
- com/vayunmathur/screentime/ui/DashboardScreen.kt
- com/vayunmathur/screentime/ui/FocusScreen.kt
- com/vayunmathur/screentime/ui/FrictionScreen.kt
- com/vayunmathur/screentime/ui/UsageBarChart.kt
- com/vayunmathur/screentime/ui/UsageFormat.kt
- com/vayunmathur/screentime/ui/WindDownScreen.kt
- com/vayunmathur/screentime/widget/ScreenTimeGlanceWidget.kt
- com/vayunmathur/screentime/widget/ScreenTimeWidgetReceiver.kt

## Verify (this module only)
```
./gradlew :screentime:compileDevKotlin
./gradlew :screentime:lint
./gradlew :screentime:checkMetadata
```


