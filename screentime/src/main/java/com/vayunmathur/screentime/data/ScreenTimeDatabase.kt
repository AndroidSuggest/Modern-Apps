package com.vayunmathur.screentime.data

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DB_NAME = "screentime-db"

@Dao
interface AppTimerDao {
    @Query("SELECT * FROM AppTimer ORDER BY packageName")
    fun allFlow(): Flow<List<AppTimer>>

    @Query("SELECT * FROM AppTimer")
    suspend fun all(): List<AppTimer>

    @Query("SELECT * FROM AppTimer WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): AppTimer?

    @Upsert
    suspend fun upsert(timer: AppTimer)

    @Delete
    suspend fun delete(timer: AppTimer)
}

@Dao
interface FocusProfileDao {
    @Query("SELECT * FROM FocusProfile WHERE id = :id")
    fun profileFlow(id: Int = FocusProfile.SINGLETON_ID): Flow<FocusProfile?>

    @Query("SELECT * FROM FocusProfile WHERE id = :id")
    suspend fun profile(id: Int = FocusProfile.SINGLETON_ID): FocusProfile?

    @Upsert
    suspend fun upsert(profile: FocusProfile)
}

@Dao
interface WindDownDao {
    @Query("SELECT * FROM WindDownSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = WindDownSchedule.SINGLETON_ID): Flow<WindDownSchedule?>

    @Query("SELECT * FROM WindDownSchedule WHERE id = :id")
    suspend fun schedule(id: Int = WindDownSchedule.SINGLETON_ID): WindDownSchedule?

    @Upsert
    suspend fun upsert(schedule: WindDownSchedule)
}

@ColumnTypeConverters(DefaultConverters::class)
@Database(
    entities = [AppTimer::class, FocusProfile::class, WindDownSchedule::class],
    version = 1,
    exportSchema = false,
)
abstract class ScreenTimeDatabase : RoomDatabase() {
    abstract fun appTimerDao(): AppTimerDao
    abstract fun focusProfileDao(): FocusProfileDao
    abstract fun windDownDao(): WindDownDao
}

/**
 * The single owner of [ScreenTimeDatabase], shared by the UI, the schedule receiver, the
 * usage observers and the focus service.
 *
 * Mirrors parental controls' `SupervisionRules`: enforcement runs with no Activity alive, so
 * this is a process-wide singleton rather than a ViewModel dependency.
 */
class ScreenTimeRules private constructor(context: Context) :
    RoomRepository<ScreenTimeDatabase>(context, ScreenTimeDatabase::class, DB_NAME) {

    private val timers get() = db.appTimerDao()
    private val focus get() = db.focusProfileDao()
    private val windDown get() = db.windDownDao()

    val allTimers: Flow<List<AppTimer>> = timers.allFlow()

    suspend fun allTimersNow(): List<AppTimer> = timers.all()

    suspend fun timer(packageName: String): AppTimer? = timers.byPackage(packageName)

    suspend fun upsert(timer: AppTimer) = timers.upsert(timer)

    suspend fun delete(timer: AppTimer) = timers.delete(timer)

    /** The focus profile, defaulted rather than nullable. */
    val focusProfile: Flow<FocusProfile> =
        focus.profileFlow().map { it ?: FocusProfile() }

    suspend fun focusNow(): FocusProfile = focus.profile() ?: FocusProfile()

    suspend fun setFocus(profile: FocusProfile) = focus.upsert(profile)

    /** The wind-down schedule, defaulted rather than nullable. */
    val windDownSchedule: Flow<WindDownSchedule> =
        windDown.scheduleFlow().map { it ?: WindDownSchedule() }

    suspend fun windDownNow(): WindDownSchedule = windDown.schedule() ?: WindDownSchedule()

    suspend fun setWindDown(schedule: WindDownSchedule) = windDown.upsert(schedule)

    companion object {
        @Volatile private var instance: ScreenTimeRules? = null

        fun get(context: Context): ScreenTimeRules =
            instance ?: synchronized(this) {
                instance ?: ScreenTimeRules(context).also { instance = it }
            }
    }
}
