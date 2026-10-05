package com.vayunmathur.games.hub.data

import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import kotlinx.coroutines.flow.Flow

/** Play sessions. Backed by SessionDao. */
class SessionStore internal constructor(private val repository: GamesHubRepository) {
    private val sessionDao get() = repository.sessionDaoAccess()

    suspend fun upsertSession(session: PlaySessionEntity) = sessionDao.upsert(session)
    suspend fun getSessionById(sessionId: String): PlaySessionEntity? =
        sessionDao.getBySessionId(sessionId)
    fun sessionsByGameFlow(gameId: String): Flow<List<PlaySessionEntity>> =
        sessionDao.flowByGame(gameId)
    fun allSessionsFlow(): Flow<List<PlaySessionEntity>> = sessionDao.flowAll()
    suspend fun endSession(sessionId: String, endTime: Long, durationMs: Long) =
        sessionDao.endSession(sessionId, endTime, durationMs)
    fun totalPlaytimeSessionsFlow(): Flow<Long> = sessionDao.flowTotalPlaytimeMs()
    suspend fun clearSessions() = sessionDao.clearAll()
}

/** Daily streaks. Backed by StreakDao. */
class StreakStore internal constructor(private val repository: GamesHubRepository) {
    private val streakDao get() = repository.streakDaoAccess()

    suspend fun upsertStreak(streak: DailyStreakEntity) = streakDao.upsert(streak)
    suspend fun getStreak(gameId: String): DailyStreakEntity? = streakDao.getByGame(gameId)
    fun streakByGameFlow(gameId: String): Flow<DailyStreakEntity?> = streakDao.flowByGame(gameId)
    fun allStreaksFlow(): Flow<List<DailyStreakEntity>> = streakDao.flowAll()
    suspend fun clearStreaks() = streakDao.clearAll()
}

/** Player profile. Backed by ProfileDao. */
class ProfileStore internal constructor(private val repository: GamesHubRepository) {
    private val profileDao get() = repository.profileDaoAccess()

    fun profileFlow(): Flow<PlayerProfileEntity?> = profileDao.flowProfile()
    suspend fun getProfile(): PlayerProfileEntity? = profileDao.getProfile()
    suspend fun upsertProfile(profile: PlayerProfileEntity) = profileDao.upsert(profile)
}

/** Activity feed. Backed by ActivityDao. */
class ActivityStore internal constructor(private val repository: GamesHubRepository) {
    private val activityDao get() = repository.activityDaoAccess()

    suspend fun upsertActivity(event: ActivityEventEntity) = activityDao.upsert(event)
    fun recentActivityFlow(limit: Int = 50): Flow<List<ActivityEventEntity>> =
        activityDao.flowRecent(limit)
    fun allActivityFlow(): Flow<List<ActivityEventEntity>> = activityDao.flowAll()
    fun activityByGameFlow(gameId: String, limit: Int = 20): Flow<List<ActivityEventEntity>> =
        activityDao.flowByGame(gameId, limit)
    suspend fun clearActivities() = activityDao.clearAll()
}
