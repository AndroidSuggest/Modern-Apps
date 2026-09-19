# AGENTS.md — library/ (':library')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/` + allowed shared modules. Do not root-scan.

- Gradle: :library / dir: library/
- Package roots present: intents
- Entry files: Navigation.kt
- Deps: :sdk:games
- Metadata: metadata_data/library.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/library/intents/calendar/EventData.kt
- com/vayunmathur/library/intents/clock/ClockData.kt
- com/vayunmathur/library/intents/contacts/ContactData.kt
- com/vayunmathur/library/intents/email/EmailData.kt
- com/vayunmathur/library/intents/findfamily/FamilyMemberData.kt
- com/vayunmathur/library/intents/fooddelivery/OrderLookupData.kt
- com/vayunmathur/library/intents/maps/DirectionsData.kt
- com/vayunmathur/library/intents/music/MusicData.kt
- com/vayunmathur/library/intents/notes/InsertNoteData.kt
- com/vayunmathur/library/intents/notes/NoteData.kt
- com/vayunmathur/library/intents/taxi/RideEstimateData.kt
- com/vayunmathur/library/intents/weather/WeatherData.kt
- com/vayunmathur/library/sensor/OrientationManager.kt
- com/vayunmathur/library/util/Achievement.kt
- com/vayunmathur/library/util/AchievementsManager.kt
- com/vayunmathur/library/util/AppMessages.kt
- com/vayunmathur/library/util/AppPreferencesEntry.kt
- com/vayunmathur/library/util/AssistantIntent.kt
- com/vayunmathur/library/util/BackupAgent.kt
- com/vayunmathur/library/util/BackupFormat.kt
- com/vayunmathur/library/util/BackupHelper.kt
- com/vayunmathur/library/util/DailyChallengeStore.kt
- com/vayunmathur/library/util/DailyStreakReporter.kt
- com/vayunmathur/library/util/DatabaseHelper.kt
- com/vayunmathur/library/util/DataStoreUtils.kt
- com/vayunmathur/library/util/FileDropModifier.kt
- com/vayunmathur/library/util/Format.kt
- com/vayunmathur/library/util/GameHubCompose.kt
- com/vayunmathur/library/util/GameHubReporter.kt
- com/vayunmathur/library/util/Hkdf.kt
- com/vayunmathur/library/util/IntentHelper.kt
- com/vayunmathur/library/util/LevelStatsRepository.kt
- com/vayunmathur/library/util/LocalizedDateNames.kt
- com/vayunmathur/library/util/Markdown.kt
- com/vayunmathur/library/util/Navigation.kt
- com/vayunmathur/library/util/NavigationBottomBar.kt
- com/vayunmathur/library/util/NavigationMotion.kt
- com/vayunmathur/library/util/NavigationResults.kt
- com/vayunmathur/library/util/Notifications.kt
- com/vayunmathur/library/util/RoomViewModel.kt
- com/vayunmathur/library/util/SecureResultReceiver.kt
- com/vayunmathur/library/util/SharedEditableText.kt
- com/vayunmathur/library/util/Util.kt

## Verify (this module only)
```
./gradlew :library:compileDevKotlin
./gradlew :library:lint
```


