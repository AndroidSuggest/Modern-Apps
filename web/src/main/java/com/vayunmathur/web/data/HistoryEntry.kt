package com.vayunmathur.web.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

@Entity
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val url: String,
    val title: String = "",
    val visitedAt: Long = System.currentTimeMillis(),
    val faviconUrl: String? = null,
) : DatabaseItem
