package com.vayunmathur.games.hub.provider

import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.sdk.games.GameHubContract

internal const val DEFAULT_XP_REWARD = 25

/** Contract column first, legacy snake_case fallback second. */
internal fun ContentValues.gameString(contractKey: String, legacyKey: String): String? =
    getAsString(contractKey) ?: getAsString(legacyKey)

internal fun ContentValues.gameLong(contractKey: String, legacyKey: String): Long? =
    getAsLong(contractKey) ?: getAsLong(legacyKey)

internal fun ContentValues.gameInt(contractKey: String, legacyKey: String): Int? =
    getAsInteger(contractKey) ?: getAsInteger(legacyKey)

internal fun valuesToGame(v: ContentValues, uri: Uri): HubGameEntity {
    val segGameId = uri.pathSegments.getOrNull(1)
    val gameId = v.gameString(GameHubContract.Games.GAME_ID, "game_id")
        ?: segGameId ?: error("gameId missing")
    return HubGameEntity(
        gameId = gameId,
        packageName = v.gameString(GameHubContract.Games.PACKAGE_NAME, "package_name")
            ?: "unknown",
        displayName = v.gameString(GameHubContract.Games.DISPLAY_NAME, "display_name")
            ?: gameId,
        description = v.gameString(GameHubContract.Games.DESCRIPTION, "description"),
        versionName = v.gameString(GameHubContract.Games.VERSION_NAME, "version_name"),
        versionCode = v.gameLong(GameHubContract.Games.VERSION_CODE, "version_code"),
        registeredAt = v.gameLong(GameHubContract.Games.REGISTERED_AT, "registered_at")
            ?: System.currentTimeMillis(),
        lastSeenAt = v.gameLong(GameHubContract.Games.LAST_SEEN_AT, "last_seen_at")
            ?: System.currentTimeMillis()
    )
}

internal suspend fun valuesToGameMerged(
    v: ContentValues,
    uri: Uri,
    repo: GamesHubRepository,
): HubGameEntity {
    val gameId = v.gameString(GameHubContract.Games.GAME_ID, "game_id")
        ?: uri.pathSegments.getOrNull(1) ?: error("gameId missing")
    val existing = repo.games.getGame(gameId)
    if (existing == null) return valuesToGame(v, uri)
    return existing.copy(
        packageName = v.gameString(GameHubContract.Games.PACKAGE_NAME, "package_name")
            ?: existing.packageName,
        displayName = v.gameString(GameHubContract.Games.DISPLAY_NAME, "display_name")
            ?: existing.displayName,
        description = v.gameString(GameHubContract.Games.DESCRIPTION, "description")
            ?: existing.description,
        versionName = v.gameString(GameHubContract.Games.VERSION_NAME, "version_name")
            ?: existing.versionName,
        versionCode = v.gameLong(GameHubContract.Games.VERSION_CODE, "version_code")
            ?: existing.versionCode,
        lastSeenAt = v.gameLong(GameHubContract.Games.LAST_SEEN_AT, "last_seen_at")
            ?: System.currentTimeMillis()
    )
}

internal suspend fun ensureGameExists(gameId: String, repo: GamesHubRepository) {
    if (repo.games.getGame(gameId) == null) {
        repo.games.upsertGame(
            HubGameEntity(
                gameId = gameId,
                packageName = "unknown",
                displayName = gameId.replaceFirstChar { it.uppercase() },
                lastSeenAt = System.currentTimeMillis()
            )
        )
    }
}

internal fun valuesToAchievementDef(v: ContentValues, uri: Uri): AchievementDefEntity {
    val segGameId = uri.pathSegments.getOrNull(1)
    val gameId = v.gameString(GameHubContract.AchievementDefs.GAME_ID, "game_id")
        ?: segGameId ?: error("gameId missing in def")
    val achId = v.gameString(GameHubContract.AchievementDefs.ACHIEVEMENT_ID, "achievement_id")
        ?: error("achievement_id missing")
    val cols = GameHubContract.AchievementDefs
    return AchievementDefEntity(
        gameId = gameId,
        achievementId = achId,
        name = v.gameString(cols.NAME, "name") ?: achId,
        description = v.gameString(cols.DESCRIPTION, "description") ?: "",
        xpReward = v.gameInt(cols.XP_REWARD, "xp_reward") ?: DEFAULT_XP_REWARD,
        targetProgress = v.gameInt(cols.TARGET_PROGRESS, "target_progress") ?: 1,
        isSecret = (v.gameInt(cols.IS_SECRET, "is_secret") ?: 0) == 1,
        tier = v.gameString(cols.TIER, "tier") ?: "BRONZE",
        iconResName = v.gameString(cols.ICON_RES_NAME, "icon_res_name")
    )
}

internal fun valuesToProgress(v: ContentValues, uri: Uri): AchievementProgressEntity {
    val (segGameId, segAchId) = progressUriIds(uri)
    val cols = GameHubContract.AchievementProgressCols
    val gameId = v.gameString(cols.GAME_ID, "game_id")
        ?: segGameId ?: error("gameId missing in progress")
    val achId = v.gameString(cols.ACHIEVEMENT_ID, "achievement_id")
        ?: segAchId ?: error("achievementId missing in progress")
    val isUnlocked = progressUnlocked(v)
    return AchievementProgressEntity(
        gameId = gameId,
        achievementId = achId,
        progress = v.gameInt(cols.PROGRESS, "progress") ?: if (isUnlocked) 1 else 0,
        isUnlocked = isUnlocked,
        unlockedAt = progressUnlockedAt(v, isUnlocked),
        lastUpdated = v.gameLong(cols.LAST_UPDATED, "last_updated")
            ?: System.currentTimeMillis()
    )
}

internal fun progressUriIds(uri: Uri): Pair<String?, String?> {
    val segs = uri.pathSegments
    val segAchId = when {
        segs.size >= 4 && segs[2] == "progress" -> segs[3]
        segs.size == 3 && segs[1] != "defs" && segs[1] != "progress" -> segs[2]
        else -> null
    }
    return segs.getOrNull(1) to segAchId
}

internal fun progressUnlocked(v: ContentValues): Boolean {
    val legacyUnlocked = v.getAsInteger("unlocked")
    if (legacyUnlocked != null) return legacyUnlocked == 1
    if (v.containsKey(GameHubContract.AchievementProgressCols.IS_UNLOCKED)) {
        return (v.getAsInteger(GameHubContract.AchievementProgressCols.IS_UNLOCKED) ?: 0) == 1
    }
    return false
}

internal fun progressUnlockedAt(v: ContentValues, isUnlocked: Boolean): Long? {
    val cols = GameHubContract.AchievementProgressCols
    return v.gameLong(cols.UNLOCKED_AT, "unlocked_at")
        ?: if (isUnlocked) System.currentTimeMillis() else null
}

internal fun valuesToSession(v: ContentValues, uri: Uri): PlaySessionEntity {
    val segGameId = uri.pathSegments.getOrNull(1)
    val segSessionId = uri.pathSegments.getOrNull(2)
    val cols = GameHubContract.Sessions
    return PlaySessionEntity(
        gameId = v.gameString(cols.GAME_ID, "game_id")
            ?: segGameId ?: error("gameId missing in session"),
        sessionId = v.gameString(cols.SESSION_ID, "session_id")
            ?: segSessionId ?: error("sessionId missing"),
        startTime = v.gameLong(cols.START_TIME, "start_time")
            ?: System.currentTimeMillis(),
        endTime = v.gameLong(cols.END_TIME, "end_time"),
        durationMs = v.gameLong(cols.DURATION_MS, "duration_ms")
    )
}

internal fun valuesToStreak(v: ContentValues, uri: Uri): DailyStreakEntity {
    val segGameId = uri.pathSegments.getOrNull(1)
    val cols = GameHubContract.Streaks
    return DailyStreakEntity(
        gameId = v.gameString(cols.GAME_ID, "game_id")
            ?: segGameId ?: error("gameId missing in streak"),
        currentStreak = v.gameInt(cols.CURRENT_STREAK, "current_streak") ?: 0,
        longestStreak = v.gameInt(cols.LONGEST_STREAK, "longest_streak") ?: 0,
        lastCompletedDay = v.gameLong(cols.LAST_COMPLETED_DAY, "last_completed_day") ?: 0L,
        lastUpdated = v.gameLong(cols.LAST_UPDATED, "last_updated")
            ?: System.currentTimeMillis()
    )
}

internal fun cursorFromStreaks(streaks: List<DailyStreakEntity>): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "game_id",
            "current_streak",
            "longest_streak",
            "last_completed_day",
            "last_updated",
        )
    )
    for (s in streaks) {
        c.addRow(
            arrayOf<Any?>(
                s.gameId,
                s.currentStreak,
                s.longestStreak,
                s.lastCompletedDay,
                s.lastUpdated,
            )
        )
    }
    return c
}

internal fun cursorFromGames(games: List<HubGameEntity>): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "game_id",
            "package_name",
            "display_name",
            "description",
            "version_name",
            "version_code",
            "registered_at",
            "last_seen_at",
            "last_played_at",
            "total_playtime_ms",
            "total_sessions",
        )
    )
    for (g in games) {
        c.addRow(
            arrayOf<Any?>(
                g.gameId,
                g.packageName,
                g.displayName,
                g.description,
                g.versionName,
                g.versionCode,
                g.registeredAt,
                g.lastSeenAt,
                g.lastPlayedAt,
                g.totalPlaytimeMs,
                g.totalSessions,
            )
        )
    }
    return c
}

internal fun cursorFromAchievementDefs(
    defs: List<AchievementDefEntity>,
): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "game_id",
            "achievement_id",
            "name",
            "description",
            "xp_reward",
            "target_progress",
            "is_secret",
            "tier",
            "icon_res_name",
        )
    )
    for (d in defs) {
        c.addRow(
            arrayOf<Any?>(
                d.gameId,
                d.achievementId,
                d.name,
                d.description,
                d.xpReward,
                d.targetProgress,
                if (d.isSecret) 1 else 0,
                d.tier,
                d.iconResName,
            )
        )
    }
    return c
}

internal fun cursorFromProgress(progresses: List<AchievementProgressEntity>): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "game_id",
            "achievement_id",
            "progress",
            "is_unlocked",
            "unlocked_at",
            "last_updated",
        )
    )
    for (p in progresses) {
        c.addRow(
            arrayOf<Any?>(
                p.gameId,
                p.achievementId,
                p.progress,
                if (p.isUnlocked) 1 else 0,
                p.unlockedAt,
                p.lastUpdated,
            )
        )
    }
    return c
}

internal fun cursorFromLegacyAchievements(
    defs: List<AchievementDefEntity>,
    progressMap: Map<String, AchievementProgressEntity>,
): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "achievement_id",
            "game_id",
            "name",
            "description",
            "icon_res_name",
            "unlocked",
            "unlocked_at",
        )
    )
    for (d in defs) {
        val prog = progressMap[d.achievementId]
        c.addRow(
            arrayOf<Any?>(
                d.achievementId,
                d.gameId,
                d.name,
                d.description,
                d.iconResName,
                if (prog?.isUnlocked == true) 1 else 0,
                prog?.unlockedAt,
            )
        )
    }
    return c
}

internal fun cursorFromLegacyAchievementItem(
    def: AchievementDefEntity?,
    prog: AchievementProgressEntity?,
): MatrixCursor {
    val c = MatrixCursor(
        arrayOf(
            "achievement_id",
            "game_id",
            "name",
            "description",
            "icon_res_name",
            "unlocked",
            "unlocked_at",
        )
    )
    val unlocked = prog?.isUnlocked == true
    c.addRow(
        arrayOf<Any?>(
            def?.achievementId ?: prog?.achievementId ?: "",
            def?.gameId ?: prog?.gameId ?: "",
            def?.name ?: "",
            def?.description ?: "",
            def?.iconResName,
            if (unlocked) 1 else 0,
            prog?.unlockedAt,
        )
    )
    return c
}
