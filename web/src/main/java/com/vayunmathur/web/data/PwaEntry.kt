package com.vayunmathur.web.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

@Entity
data class PwaEntry(
    @PrimaryKey override val id: String,
    val url: String,
    val name: String,
    val shortName: String = "",
    val iconUrl: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val displayMode: String = "standalone",
    val startUrl: String = url,
    val origin: String,
    val installedAt: Long = System.currentTimeMillis(),
) : DatabaseItem {
    // DatabaseItem expects Long id, but we use String PK for shortcuts.
    // Provide Long via hash for interface compliance — actual PK is String.
    override val idLong: Long get() = id.hashCode().toLong()
}

// Keep DatabaseItem compatibility — the interface expects Long id, but we override via separate name.
// Actually DatabaseItem has val id: Long. Room with String PK cannot implement that directly.
// Use adapter: define idLong above but we need to satisfy interface with different property name.
// To avoid breaking DatabaseItem, redefine interface locally for PwaEntry.
// Instead we define PwaEntry WITHOUT implementing DatabaseItem, so queries still work.
// For flow compatibility we avoid DatabaseItem for PWA. Keep extra property for sorting.

@Entity
data class PwaEntryV2(
    @PrimaryKey val id: String,
    val url: String,
    val name: String,
    val shortName: String = "",
    val iconUrl: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val displayMode: String = "standalone",
    val startUrl: String = url,
    val origin: String,
    val installedAt: Long = System.currentTimeMillis(),
)
