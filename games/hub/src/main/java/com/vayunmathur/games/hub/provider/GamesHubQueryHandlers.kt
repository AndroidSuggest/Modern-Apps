package com.vayunmathur.games.hub.provider

import android.database.Cursor
import android.net.Uri
import com.vayunmathur.games.hub.data.GamesHubRepository
import kotlinx.coroutines.flow.first

internal suspend fun GamesHubProvider.queryGame(
    uri: Uri,
    database: com.vayunmathur.games.hub.data.GamesHubDatabase,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    val game = database.gameDao().getById(gameId)
    return cursorFromGames(listOfNotNull(game))
}

internal suspend fun GamesHubProvider.queryAchievementDefs(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    return cursorFromAchievementDefs(repo.achievements.achievementDefsByGameFlow(gameId).first())
}

internal suspend fun GamesHubProvider.queryLegacyAchievements(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    val allDefs = repo.achievements.achievementDefsByGameFlow(gameId).first()
    val progressMap = repo.achievements.progressByGameFlow(gameId).first()
        .associateBy { it.achievementId }
    return cursorFromLegacyAchievements(allDefs, progressMap)
}

internal suspend fun GamesHubProvider.queryProgress(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    return cursorFromProgress(repo.achievements.progressByGameFlow(gameId).first())
}

internal suspend fun GamesHubProvider.queryProgressItem(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    val achId = uri.pathSegments.getOrNull(3) ?: return null
    val prog = repo.achievements.getProgress(gameId, achId)
    return cursorFromProgress(listOfNotNull(prog))
}

internal suspend fun GamesHubProvider.queryLegacyAchievementItem(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    val achId = uri.pathSegments.getOrNull(2) ?: return null
    return cursorFromLegacyAchievementItem(
        repo.achievements.getAchievementDef(gameId, achId),
        repo.achievements.getProgress(gameId, achId),
    )
}

internal suspend fun GamesHubProvider.queryStreak(
    uri: Uri,
    repo: GamesHubRepository,
): Cursor? {
    val gameId = uri.pathSegments.getOrNull(1) ?: return null
    return cursorFromStreaks(listOfNotNull(repo.streaks.getStreak(gameId)))
}
