package com.vayunmathur.flashcards.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "flashcards-db"

/** Backup config shared by [AppBackupAgent] and the in-app backup buttons. */
fun flashcardsDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Dao
interface DeckDao {
    @Query("SELECT * FROM Deck")
    fun getAllFlow(): Flow<List<Deck>>

    @Upsert
    suspend fun upsert(value: Deck): Long

    @Delete
    suspend fun delete(value: Deck): Int
}

@Dao
interface CardDao {
    @Query("SELECT * FROM Card")
    fun getAllFlow(): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE deckId = :deckId AND dueDate <= :now")
    fun getDueByDeckFlow(deckId: Long, now: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Card?>

    @Upsert
    suspend fun upsert(value: Card): Long

    @Delete
    suspend fun delete(value: Card): Int

    @Query("DELETE FROM Card WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)
}

@Database(entities = [Deck::class, Card::class], version = 1, exportSchema = false)
abstract class FlashcardsDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao
    abstract fun cardDao(): CardDao

    companion object : DatabaseMigrations {
        override val migrations = emptyList<Migration>()
    }
}
