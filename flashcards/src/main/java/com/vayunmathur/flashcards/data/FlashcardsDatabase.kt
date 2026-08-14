package com.vayunmathur.flashcards.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "flashcards-db"

/** Backup config shared by [AppBackupAgent] and the in-app backup buttons. */
fun flashcardsDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Dao
interface DeckDao {
    @Query("SELECT * FROM Deck")
    fun getAllFlow(): Flow<List<Deck>>

    @Query("SELECT * FROM Deck WHERE id = :id")
    suspend fun getById(id: Long): Deck?

    @Upsert
    suspend fun upsert(value: Deck): Long

    @Delete
    suspend fun delete(value: Deck): Int
}

@Dao
interface CardDao {
    @Query("SELECT * FROM Card")
    fun getAllFlow(): Flow<List<Card>>

    @Query("SELECT * FROM Card")
    suspend fun getAll(): List<Card>

    @Query("SELECT * FROM Card WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE deckId = :deckId")
    suspend fun getByDeck(deckId: Long): List<Card>

    @Query("SELECT * FROM Card WHERE deckId = :deckId AND dueDate <= :now")
    fun getDueByDeckFlow(deckId: Long, now: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Card?>

    @Upsert
    suspend fun upsert(value: Card): Long

    @Upsert
    suspend fun upsertAll(values: List<Card>)

    @Delete
    suspend fun delete(value: Card): Int

    @Query("DELETE FROM Card WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)
}

@Dao
interface ReviewLogDao {
    @Query("SELECT * FROM ReviewLog")
    fun getAllFlow(): Flow<List<ReviewLog>>

    @Query("SELECT * FROM ReviewLog WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<ReviewLog>>

    @Insert
    suspend fun insert(value: ReviewLog): Long

    @Query("DELETE FROM ReviewLog WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ReviewLog WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)
}

@Database(
    entities = [Deck::class, Card::class, ReviewLog::class],
    version = 2,
    exportSchema = false,
)
abstract class FlashcardsDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) { db ->
                // FSRS + content columns on Card. DEFAULTs populate legacy rows.
                db.execSQL("ALTER TABLE Card ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Card ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN difficulty REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN state INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN lastReview INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                // Per-deck study config.
                db.execSQL("ALTER TABLE Deck ADD COLUMN newPerDay INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE Deck ADD COLUMN maxReviewsPerDay INTEGER NOT NULL DEFAULT 200")
                db.execSQL("ALTER TABLE Deck ADD COLUMN desiredRetention REAL NOT NULL DEFAULT 0.9")
                // Review history.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ReviewLog (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "cardId INTEGER NOT NULL, " +
                        "deckId INTEGER NOT NULL, " +
                        "reviewedAt INTEGER NOT NULL, " +
                        "grade INTEGER NOT NULL, " +
                        "elapsedDays REAL NOT NULL, " +
                        "scheduledDays REAL NOT NULL, " +
                        "state INTEGER NOT NULL)",
                )
            },
        )
    }
}
