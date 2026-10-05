package com.vayunmathur.games.hub.provider

import android.content.ContentValues
import android.net.Uri
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity

internal suspend fun GamesHubProvider.insertGame(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
): Uri? {
    val entity = valuesToGameMerged(v, uri, repo)
    repo.games.upsertGame(entity)
    repo.activity.upsertActivity(
        ActivityEventEntity(
            type = ActivityEventEntity.TYPE_GAME_REGISTERED,
            gameId = entity.gameId,
            title = "${entity.displayName} registered",
            description = entity.description
        )
    )
    return uri
}

internal suspend fun GamesHubProvider.insertAchievementDef(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
): Uri? {
    val def = valuesToAchievementDef(v, uri)
    repo.achievements.upsertDef(def)
    return uri
}

internal suspend fun GamesHubProvider.insertProgress(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
): Uri? {
    val prog = valuesToProgress(v, uri)
    val wasUnlocked = repo.achievements.getProgress(prog.gameId, prog.achievementId)?.isUnlocked
        ?: false
    val def = repo.achievements.getAchievementDef(prog.gameId, prog.achievementId)
    repo.achievements.upsertProgress(prog)
    if (prog.isUnlocked && !wasUnlocked && def != null) {
        repo.activity.upsertActivity(
            ActivityEventEntity(
                type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                gameId = prog.gameId,
                title = def.name,
                description = def.description
            )
        )
    }
    return uri
}

internal suspend fun GamesHubProvider.insertProgressItem(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
    ensureDef: Boolean,
): Uri? {
    val prog = valuesToProgress(v, uri)
    val def = repo.achievements.getAchievementDef(prog.gameId, prog.achievementId)
    val existing = repo.achievements.getProgress(prog.gameId, prog.achievementId)
    val merged = mergeProgress(existing, prog)
    repo.achievements.upsertProgress(merged)
    val wasUnlockedBefore = existing?.isUnlocked ?: false
    if (merged.isUnlocked && !wasUnlockedBefore && def != null) {
        repo.activity.upsertActivity(
            ActivityEventEntity(
                type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                gameId = merged.gameId,
                title = def.name,
                description = def.description
            )
        )
    }
    if (ensureDef) {
        ensureAchievementDef(prog, repo)
    }
    return uri
}

internal fun mergeProgress(
    existing: AchievementProgressEntity?,
    prog: AchievementProgressEntity,
): AchievementProgressEntity {
    if (existing == null) return prog
    val newUnlocked = existing.isUnlocked || prog.isUnlocked
    val newUnlockedAt = when {
        newUnlocked && existing.unlockedAt != null -> existing.unlockedAt
        newUnlocked -> prog.unlockedAt ?: System.currentTimeMillis()
        else -> existing.unlockedAt
    }
    return existing.copy(
        progress = maxOf(existing.progress, prog.progress),
        isUnlocked = newUnlocked,
        unlockedAt = newUnlockedAt,
        lastUpdated = System.currentTimeMillis()
    )
}

internal suspend fun ensureAchievementDef(prog: AchievementProgressEntity, repo: GamesHubRepository) {
    if (repo.achievements.getAchievementDef(prog.gameId, prog.achievementId) == null) {
        repo.achievements.upsertDef(
            AchievementDefEntity(
                gameId = prog.gameId,
                achievementId = prog.achievementId,
                name = prog.achievementId,
                description = "",
                xpReward = DEFAULT_XP_REWARD
            )
        )
    }
}

internal suspend fun GamesHubProvider.insertSession(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
    database: com.vayunmathur.games.hub.data.GamesHubDatabase,
): Uri? {
    val session = valuesToSession(v, uri)
    ensureGameExists(session.gameId, repo)
    database.gameDao().markPlayed(session.gameId, session.startTime)
    repo.sessions.upsertSession(session)
    return uri
}

internal suspend fun GamesHubProvider.insertSessionItem(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
    database: com.vayunmathur.games.hub.data.GamesHubDatabase,
): Uri? {
    val session = valuesToSession(v, uri)
    ensureGameExists(session.gameId, repo)
    if (repo.sessions.getSessionById(session.sessionId) == null) {
        database.gameDao().markPlayed(session.gameId, session.startTime)
    }
    repo.sessions.upsertSession(session)
    return uri
}

internal suspend fun GamesHubProvider.insertStreak(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
): Uri? {
    val reported = valuesToStreak(v, uri)
    ensureGameExists(reported.gameId, repo)
    val existing = repo.streaks.getStreak(reported.gameId)
    // Never lower a personal best: a reinstall starts the game's store from zero.
    val merged = reported.copy(
        longestStreak = maxOf(reported.longestStreak, existing?.longestStreak ?: 0)
    )
    repo.streaks.upsertStreak(merged)
    return uri
}
