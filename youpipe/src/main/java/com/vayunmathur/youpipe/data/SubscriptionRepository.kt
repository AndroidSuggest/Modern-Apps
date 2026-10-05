package com.vayunmathur.youpipe.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all YouPipe persisted data.
 *
 * Owns the one [SubscriptionDatabase] instance (via [RoomRepository]) and is the
 * only place the 12 DAOs are touched. The [com.vayunmathur.youpipe.util.YouPipeViewModel],
 * [com.vayunmathur.youpipe.util.DownloadWorker], and
 * [com.vayunmathur.youpipe.util.SubscriptionFetchTask] all read/write through here
 * so they share one live, invalidation-backed view of the data.
 *
 * Read access is exposed as cold [Flow]s (consumers apply `stateIn`/`map` as before).
 * Writes are `suspend` wrappers over the existing atomic DAO queries, so no caller
 * ever holds a DAO directly.
 *
 * Uses the default `passwords-db` file name (same as all three previous
 * `buildDatabase<SubscriptionDatabase>()` call sites — MainActivity, DownloadWorker,
 * SubscriptionFetchTask — so the same DB file continues to be opened).
 */
class SubscriptionRepository private constructor(context: Context) :
    RoomRepository<SubscriptionDatabase>(context, SubscriptionDatabase::class) {

    private val subscriptionDao: SubscriptionDao get() = db.subscriptionDao()
    private val subscriptionCategoryDao: SubscriptionCategoryDao get() = db.subscriptionCategoryDao()
    private val subscriptionVideoDao: SubscriptionVideoDao get() = db.subscriptionVideoDao()
    private val historyVideoDao: HistoryVideoDao get() = db.historyVideoDao()
    private val downloadedVideoDao: DownloadedVideoDao get() = db.downloadedVideoDao()
    private val cachedRelatedVideoDao: CachedRelatedVideoDao get() = db.cachedRelatedVideoDao()
    private val recommendationImpressionDao: RecommendationImpressionDao get() = db.recommendationImpressionDao()
    private val recommendationPreferencesDao: RecommendationPreferencesDao get() = db.recommendationPreferencesDao()
    private val channelPreferenceDao: ChannelPreferenceDao get() = db.channelPreferenceDao()
    private val keywordPreferenceDao: KeywordPreferenceDao get() = db.keywordPreferenceDao()
    private val playlistDao: PlaylistDao get() = db.playlistDao()
    private val playlistItemDao: PlaylistItemDao get() = db.playlistItemDao()

    /** Category writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    internal val categoryStore by lazy { SubscriptionCategoryStore(subscriptionCategoryDao) }

    /** Playlist writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    internal val playlistStore by lazy { PlaylistStore(playlistDao, playlistItemDao) }

    /**
     * Recommendation reads/writes (split out: TooManyFunctions cap).
     * Same module; behavior identical.
     */
    internal val recommendationStore by lazy {
        RecommendationStore(
            cachedRelatedVideoDao,
            recommendationImpressionDao,
            recommendationPreferencesDao,
            channelPreferenceDao,
            keywordPreferenceDao,
        )
    }

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val subscriptions: Flow<List<Subscription>> get() = subscriptionDao.getAllFlow()
    val subscriptionCategories: Flow<List<SubscriptionCategory>> get() = subscriptionCategoryDao.getAllFlow()
    val subscriptionVideos: Flow<List<SubscriptionVideo>> get() = subscriptionVideoDao.getAllFlow()
    val historyVideos: Flow<List<HistoryVideo>> get() = historyVideoDao.getAllFlow()
    val downloadedVideos: Flow<List<DownloadedVideo>> get() = downloadedVideoDao.getAllFlow()
    val playlists: Flow<List<Playlist>> get() = playlistDao.getAllFlow()
    val playlistItems: Flow<List<PlaylistItem>> get() = playlistItemDao.getAllFlow()
    val cachedRelatedVideos: Flow<List<CachedRelatedVideo>> get() = cachedRelatedVideoDao.getAllFlow()
    val recommendationImpressions: Flow<List<RecommendationImpression>> get() = recommendationImpressionDao.getAllFlow()
    val recommendationPreferencesFlow: Flow<RecommendationPreferences?> get() = recommendationPreferencesDao.getFlow()
    val channelPreferences: Flow<List<ChannelPreference>> get() = channelPreferenceDao.getAllFlow()
    val keywordPreferences: Flow<List<KeywordPreference>> get() = keywordPreferenceDao.getAllFlow()

    // By-id flows
    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)
    fun playlistById(id: Long): Flow<Playlist?> = playlistDao.getByIdFlow(id)
    fun subscriptionById(id: Long): Flow<Subscription?> = subscriptionDao.getByIdFlow(id)
    fun subscriptionVideoById(id: Long): Flow<SubscriptionVideo?> = subscriptionVideoDao.getByIdFlow(id)
    fun subscriptionCategoryById(id: Long): Flow<SubscriptionCategory?> = subscriptionCategoryDao.getByIdFlow(id)
    fun playlistItemsFor(playlistId: Long): Flow<List<PlaylistItem>> = playlistItemDao.getForPlaylistFlow(playlistId)

    // ------------------------------------------------------------------
    // Subscription
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptions(): List<Subscription> = subscriptionDao.getAll()
    suspend fun upsertSubscription(value: Subscription): Long = subscriptionDao.upsert(value)
    suspend fun upsertSubscriptions(values: List<Subscription>) = subscriptionDao.upsertAll(values)
    suspend fun deleteSubscription(value: Subscription): Int = subscriptionDao.delete(value)
    suspend fun clearAllSubscriptions() = subscriptionDao.clearAll()

    // Category writes live on [categoryStore]; recommendation reads/writes on
    // [recommendationStore]; playlist writes on [playlistStore] (TooManyFunctions cap).
    // Call sites use `repository.<store>.<fn>` directly; behavior is identical.

    // ------------------------------------------------------------------
    // SubscriptionVideo
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptionVideos(): List<SubscriptionVideo> = subscriptionVideoDao.getAll()
    suspend fun upsertSubscriptionVideos(values: List<SubscriptionVideo>) = subscriptionVideoDao.upsertAll(values)

    // ------------------------------------------------------------------
    // HistoryVideo
    // ------------------------------------------------------------------

    suspend fun getAllHistoryVideos(): List<HistoryVideo> = historyVideoDao.getAll()
    suspend fun upsertHistoryVideo(value: HistoryVideo): Long = historyVideoDao.upsert(value)
    suspend fun upsertHistoryVideos(values: List<HistoryVideo>) = historyVideoDao.upsertAll(values)
    suspend fun deleteHistoryVideosByIds(ids: List<Long>) = historyVideoDao.deleteByIds(ids)
    suspend fun deleteHistoryVideo(value: HistoryVideo) = historyVideoDao.delete(value)
    suspend fun clearAllHistory() = historyVideoDao.clearAll()

    // ------------------------------------------------------------------
    // DownloadedVideo
    // ------------------------------------------------------------------

    suspend fun upsertDownloadedVideo(value: DownloadedVideo): Long = downloadedVideoDao.upsert(value)
    suspend fun deleteDownloadedVideo(value: DownloadedVideo): Int = downloadedVideoDao.delete(value)

    // (CachedRelatedVideo / RecommendationImpression / RecommendationPreferences /
    // ChannelPreference / KeywordPreference / Playlist / PlaylistItem live on the stores above.)

    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun get(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context).also { instance = it }
            }
    }
}
