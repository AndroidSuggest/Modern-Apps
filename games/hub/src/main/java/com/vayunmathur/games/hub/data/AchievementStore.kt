package com.vayunmathur.games.hub.data

import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import kotlinx.coroutines.flow.Flow

/** Achievement definitions and player progress. Backed by AchievementDao. */
class AchievementStore internal constructor(private val repository: GamesHubRepository) {
    private val achievementDao get() = repository.achievementDaoAccess()

    suspend fun upsertDef(def: AchievementDefEntity) = achievementDao.upsertDef(def)
    fun achievementDefsByGameFlow(gameId: String): Flow<List<AchievementDefEntity>> =
        achievementDao.flowDefsByGame(gameId)
    suspend fun getAchievementDef(gameId: String, achievementId: String): AchievementDefEntity? =
        achievementDao.getDef(gameId, achievementId)
    fun totalDefsCountFlow(): Flow<Int> = achievementDao.flowTotalDefsCount()
    suspend fun upsertProgress(progress: AchievementProgressEntity) =
        achievementDao.upsertProgress(progress)
    suspend fun getProgress(gameId: String, achievementId: String): AchievementProgressEntity? =
        achievementDao.getProgress(gameId, achievementId)
    fun progressByGameFlow(gameId: String): Flow<List<AchievementProgressEntity>> =
        achievementDao.flowProgressByGame(gameId)
    fun allWithProgressFlow(): Flow<List<AchievementWithProgress>> =
        achievementDao.flowAllWithProgress()
    fun byGameWithProgressFlow(gameId: String): Flow<List<AchievementWithProgress>> =
        achievementDao.flowByGameWithProgress(gameId)
    fun unlockedWithProgressFlow(): Flow<List<AchievementWithProgress>> =
        achievementDao.flowUnlockedWithProgress()
    fun totalXpFlow(): Flow<Int> = achievementDao.flowTotalXp()
    fun unlockedProgressFlow(): Flow<List<AchievementProgressEntity>> =
        achievementDao.flowUnlockedProgress()
    suspend fun clearDefs() = achievementDao.clearDefs()
    suspend fun clearProgress() = achievementDao.clearProgress()
}
