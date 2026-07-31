package com.vayunmathur.vpn.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "vpn-db"

@Entity
data class VpnConfigEntity(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: Int = 1280,
    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val peerAllowedIPs: String = "0.0.0.0/0, ::/0",
    val peerEndpoint: String = "",
    val peerKeepalive: Int = 25,
    val lastUsed: Long = 0,
) : DatabaseItem

fun VpnConfigEntity.toModel() = VpnConfig(
    id = id, name = name, privateKey = privateKey, publicKey = publicKey,
    address = address, dns = dns, mtu = mtu,
    peerPublicKey = peerPublicKey, peerPresharedKey = peerPresharedKey,
    peerAllowedIPs = peerAllowedIPs, peerEndpoint = peerEndpoint, peerKeepalive = peerKeepalive,
    lastUsed = lastUsed,
)

fun VpnConfig.toEntity() = VpnConfigEntity(
    id = id, name = name, privateKey = privateKey, publicKey = publicKey,
    address = address, dns = dns, mtu = mtu,
    peerPublicKey = peerPublicKey, peerPresharedKey = peerPresharedKey,
    peerAllowedIPs = peerAllowedIPs, peerEndpoint = peerEndpoint, peerKeepalive = peerKeepalive,
    lastUsed = lastUsed,
)

@Dao
interface VpnConfigDao {
    @Query("SELECT * FROM VpnConfigEntity ORDER BY lastUsed DESC, id DESC")
    fun flowAll(): Flow<List<VpnConfigEntity>>

    @Query("SELECT * FROM VpnConfigEntity WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VpnConfigEntity?

    @Query("SELECT * FROM VpnConfigEntity")
    suspend fun getAll(): List<VpnConfigEntity>

    @Upsert
    suspend fun upsert(entity: VpnConfigEntity): Long

    @Delete
    suspend fun delete(entity: VpnConfigEntity): Int

    @Query("UPDATE VpnConfigEntity SET lastUsed = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)
}

@Database(entities = [VpnConfigEntity::class], version = 1, exportSchema = false)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun vpnConfigDao(): VpnConfigDao

    companion object : DatabaseMigrations {
        override val migrations = emptyList<androidx.room.migration.Migration>()
    }
}
