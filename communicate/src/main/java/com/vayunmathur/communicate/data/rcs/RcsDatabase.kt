package com.vayunmathur.communicate.data.rcs

import android.content.Context
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.DatabaseMigrations

/**
 * Room database for RCS-specific data. Mirrors
 * [com.vayunmathur.communicate.data.signal.SignalDatabase] but for the
 * single-registration line: conversations + cached messages with file-transfer
 * metadata. Version 1, no migrations.
 */
@Database(
    entities = [
        RcsConversation::class,
        RcsCachedMessage::class,
    ],
    version = 1,
    exportSchema = false,
)
@ColumnTypeConverters(RcsTypeConverters::class)
abstract class RcsDatabase : RoomDatabase() {
    abstract fun conversationDao(): RcsConversationDao
    abstract fun cachedMessageDao(): RcsCachedMessageDao

    companion object : DatabaseMigrations {
        override val migrations = emptyList<androidx.room3.migration.Migration>()

        fun getDatabase(context: Context): RcsDatabase =
            RcsRepository.get(context).database()
    }
}

/**
 * Single owner of [RcsDatabase] (communicate_rcs.db).
 *
 * Mirrors [com.vayunmathur.communicate.data.signal.SignalRepository]: the
 * single `buildDatabase` call site lives here (via [RoomRepository.db]).
 */
class RcsRepository private constructor(context: Context) :
    RoomRepository<RcsDatabase>(context, RcsDatabase::class, "communicate_rcs.db") {

    /** Direct database access; prefer suspend wrappers for new code. */
    fun database(): RcsDatabase = db

    companion object {
        @Volatile
        private var instance: RcsRepository? = null

        fun get(context: Context): RcsRepository =
            instance ?: synchronized(this) {
                instance ?: RcsRepository(context).also { instance = it }
            }
    }
}

class RcsTypeConverters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}

// -- Conversation --

@Entity(tableName = "rcs_conversation")
data class RcsConversation(
    /** E.164 for 1:1, conference URI for groups. */
    @PrimaryKey val remoteId: String,
    val isGroup: Boolean = false,
    /** Group subject when known. */
    val name: String? = null,
    /** CSV of participant addresses for groups. */
    val participants: String = "",
    val unreadCount: Int = 0,
    val lastMessageTimestamp: Long = 0L,
)

@Dao
interface RcsConversationDao {
    @Query("SELECT * FROM rcs_conversation WHERE remoteId = :remoteId")
    suspend fun getConversation(remoteId: String): RcsConversation?

    @Query("SELECT * FROM rcs_conversation ORDER BY lastMessageTimestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RcsConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: RcsConversation)

    @Query("DELETE FROM rcs_conversation WHERE remoteId = :remoteId")
    suspend fun delete(remoteId: String)

    @Query("DELETE FROM rcs_conversation")
    suspend fun deleteAll()
}

// -- Cached messages --

@Entity(tableName = "rcs_cached_message")
data class RcsCachedMessage(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    /** Sender address for inbound group messages. */
    val senderId: String = "",
    /** File-transfer metadata (populated by RcsFileTransfer). */
    val ftUrl: String? = null,
    val ftMime: String? = null,
    /** Outgoing delivery status ordinal (MessageStatus ordinal). */
    val status: Int = 0,
)

@Dao
interface RcsCachedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: RcsCachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<RcsCachedMessage>)

    @Query("SELECT * FROM rcs_cached_message WHERE conversationId = :id ORDER BY timestamp ASC")
    suspend fun getForConversation(id: String): List<RcsCachedMessage>

    @Query("SELECT * FROM rcs_cached_message WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): RcsCachedMessage?

    /** Most-recent message per conversation, newest first (for the thread list). */
    @Query(
        "SELECT * FROM rcs_cached_message WHERE timestamp IN " +
            "(SELECT MAX(timestamp) FROM rcs_cached_message GROUP BY conversationId) " +
            "ORDER BY timestamp DESC",
    )
    suspend fun getLatestPerConversation(): List<RcsCachedMessage>

    /** Advance to Delivered (2) only from a lower state. */
    @Query("UPDATE rcs_cached_message SET status = 2 WHERE messageId = :messageId AND status < 2")
    suspend fun markDelivered(messageId: String)

    /** Advance to Read (3) only from a lower state. */
    @Query("UPDATE rcs_cached_message SET status = 3 WHERE messageId = :messageId AND status < 3")
    suspend fun markReadStatus(messageId: String)

    @Query("DELETE FROM rcs_cached_message WHERE conversationId = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM rcs_cached_message")
    suspend fun deleteAll()
}
