# AGENTS.md — games/hub/ (':games:hub')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/hub/` + allowed shared modules. Do not root-scan.

_Install: ./install games/hub (dev by default)._

- Gradle: :games:hub / dir: games/hub/
- Package roots present: ui, data, provider
- Entry files: MainActivity.kt
- Deps: :library, :library:ui, :library:room, :sdk:games
- Metadata: metadata_data/games-hub.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/games/hub/MainActivity.kt
- com/vayunmathur/games/hub/data/GamesHubDatabase.kt
- com/vayunmathur/games/hub/data/GamesHubRepository.kt
- com/vayunmathur/games/hub/data/dao/AchievementDao.kt
- com/vayunmathur/games/hub/data/dao/ActivityDao.kt
- com/vayunmathur/games/hub/data/dao/GameDao.kt
- com/vayunmathur/games/hub/data/dao/ProfileDao.kt
- com/vayunmathur/games/hub/data/dao/SessionDao.kt
- com/vayunmathur/games/hub/data/dao/StreakDao.kt
- com/vayunmathur/games/hub/data/entities/AchievementDefEntity.kt
- com/vayunmathur/games/hub/data/entities/AchievementProgressEntity.kt
- com/vayunmathur/games/hub/data/entities/ActivityEventEntity.kt
- com/vayunmathur/games/hub/data/entities/DailyStreakEntity.kt
- com/vayunmathur/games/hub/data/entities/HubGameEntity.kt
- com/vayunmathur/games/hub/data/entities/PlayerProfileEntity.kt
- com/vayunmathur/games/hub/data/entities/PlaySessionEntity.kt
- com/vayunmathur/games/hub/provider/GamesHubProvider.kt
- com/vayunmathur/games/hub/ui/components/AchievementRow.kt
- com/vayunmathur/games/hub/ui/components/ActivityItem.kt
- com/vayunmathur/games/hub/ui/components/AvatarBadge.kt
- com/vayunmathur/games/hub/ui/components/AvatarIcon.kt
- com/vayunmathur/games/hub/ui/components/GameCard.kt
- com/vayunmathur/games/hub/ui/components/LevelBadge.kt
- com/vayunmathur/games/hub/ui/components/StatCard.kt
- com/vayunmathur/games/hub/ui/components/StreakIndicator.kt
- com/vayunmathur/games/hub/ui/components/XpProgressBar.kt
- com/vayunmathur/games/hub/ui/screens/AchievementsScreen.kt
- com/vayunmathur/games/hub/ui/screens/ActivityFeedScreen.kt
- com/vayunmathur/games/hub/ui/screens/DashboardPage.kt
- com/vayunmathur/games/hub/ui/screens/DashboardScreen.kt
- com/vayunmathur/games/hub/ui/screens/GameDetailScreen.kt
- com/vayunmathur/games/hub/ui/screens/GamesListPage.kt
- com/vayunmathur/games/hub/ui/screens/GamesListScreen.kt
- com/vayunmathur/games/hub/ui/screens/ProfilePage.kt
- com/vayunmathur/games/hub/ui/screens/ProfileScreen.kt
- com/vayunmathur/games/hub/ui/screens/SettingsScreen.kt
- com/vayunmathur/games/hub/util/AppBackupAgent.kt
- com/vayunmathur/games/hub/util/FormatUtils.kt
- com/vayunmathur/games/hub/util/GameIconResolver.kt
- com/vayunmathur/games/hub/util/HubUiContract.kt
- com/vayunmathur/games/hub/util/StreakCalculator.kt
- com/vayunmathur/games/hub/util/XpLevelCalculator.kt
- com/vayunmathur/games/hub/viewmodel/GameHubViewModel.kt

## Verify (this module only)
```
./gradlew :games:hub:compileDevKotlin
./gradlew :games:hub:lint
./gradlew :games:hub:checkMetadata
```


