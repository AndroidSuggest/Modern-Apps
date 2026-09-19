# AGENTS.md — parentalcontrols/ (':parentalcontrols')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `parentalcontrols/` + allowed shared modules. Do not root-scan.

_Install: ./install parentalcontrols (dev by default)._

- Gradle: :parentalcontrols / dir: parentalcontrols/
- Package roots present: ui, data, domain, platform, service, notifications, auth
- Entry files: 
- Deps: :library:room, :library:supervision-stubs
- Metadata: metadata_data/parentalcontrols.md (present)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/parentalcontrols/auth/ParentPin.kt
- com/vayunmathur/parentalcontrols/data/Migrations.kt
- com/vayunmathur/parentalcontrols/data/ParentalControlsDatabase.kt
- com/vayunmathur/parentalcontrols/data/Rules.kt
- com/vayunmathur/parentalcontrols/data/SupervisionRules.kt
- com/vayunmathur/parentalcontrols/domain/TimeWindow.kt
- com/vayunmathur/parentalcontrols/notifications/LockNotifier.kt
- com/vayunmathur/parentalcontrols/platform/AppLimits.kt
- com/vayunmathur/parentalcontrols/platform/BedtimeScheduler.kt
- com/vayunmathur/parentalcontrols/platform/Enforcer.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionApis.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionCaller.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionMaster.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionPolicies.kt
- com/vayunmathur/parentalcontrols/platform/SupervisionViewModel.kt
- com/vayunmathur/parentalcontrols/platform/UsageAccess.kt
- com/vayunmathur/parentalcontrols/platform/WebContentFilters.kt
- com/vayunmathur/parentalcontrols/receiver/Receivers.kt
- com/vayunmathur/parentalcontrols/service/ParentalControlsAppService.kt
- com/vayunmathur/parentalcontrols/service/SupervisionMessengerService.kt
- com/vayunmathur/parentalcontrols/ui/AppLimitsActivity.kt
- com/vayunmathur/parentalcontrols/ui/AppLimitsScreen.kt
- com/vayunmathur/parentalcontrols/ui/BedtimeActivity.kt
- com/vayunmathur/parentalcontrols/ui/BedtimeScreen.kt
- com/vayunmathur/parentalcontrols/ui/DailyLimitScreen.kt
- com/vayunmathur/parentalcontrols/ui/DashboardActivity.kt
- com/vayunmathur/parentalcontrols/ui/DashboardScreen.kt
- com/vayunmathur/parentalcontrols/ui/LockActivities.kt
- com/vayunmathur/parentalcontrols/ui/LockScreen.kt
- com/vayunmathur/parentalcontrols/ui/PinActivities.kt
- com/vayunmathur/parentalcontrols/ui/PinGatedActivity.kt
- com/vayunmathur/parentalcontrols/ui/PinGateScreen.kt
- com/vayunmathur/parentalcontrols/ui/PinSetupScreen.kt
- com/vayunmathur/parentalcontrols/ui/SetupActivity.kt
- com/vayunmathur/parentalcontrols/ui/WebContentFiltersActivity.kt
- com/vayunmathur/parentalcontrols/ui/WindowActivities.kt
- com/vayunmathur/parentalcontrols/ui/WindowScheduleScreen.kt

## Verify (this module only)
```
./gradlew :parentalcontrols:compileDevKotlin
./gradlew :parentalcontrols:lint
./gradlew :parentalcontrols:checkMetadata
```


