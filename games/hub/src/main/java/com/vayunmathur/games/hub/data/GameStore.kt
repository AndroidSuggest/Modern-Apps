package com.vayunmathur.games.hub.data

import com.vayunmathur.games.hub.data.entities.HubGameEntity
import kotlinx.coroutines.flow.Flow

/** Game-catalog reads and writes. Backed by GameDao. */
class GameStore internal constructor(private val repository: GamesHubRepository) {
    private val gameDao get() = repository.gameDaoAccess()

    fun gamesFlow(): Flow<List<HubGameEntity>> = gameDao.flowAll()
    fun gameByIdFlow(gameId: String): Flow<HubGameEntity?> = gameDao.flowById(gameId)
    suspend fun getGame(gameId: String): HubGameEntity? = gameDao.getById(gameId)
    suspend fun getAllGames(): List<HubGameEntity> = gameDao.getAll()
    suspend fun upsertGame(game: HubGameEntity) = gameDao.upsert(game)
    suspend fun markPlayed(gameId: String, timestamp: Long = System.currentTimeMillis()) =
        gameDao.markPlayed(gameId, timestamp)
    suspend fun addPlaytime(gameId: String, increment: Long) = gameDao.addPlaytime(gameId, increment)
    suspend fun clearGames() = gameDao.clearAll()
    fun totalPlaytimeFlow(): Flow<Long> = gameDao.flowTotalPlaytimeMs()
    fun totalSessionsFlow(): Flow<Int> = gameDao.flowTotalSessions()
}
