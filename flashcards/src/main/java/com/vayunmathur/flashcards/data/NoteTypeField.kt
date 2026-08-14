package com.vayunmathur.flashcards.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One named field of a [NoteType], e.g. "Front"/"Back" (Basic) or "Text" (Cloze).
 * [ord] is the 0-based position of the field within its note type; the pair
 * ([noteTypeId], [ord]) is unique.
 */
@Serializable
@Entity(
    indices = [Index(value = ["noteTypeId", "ord"], unique = true)],
)
data class NoteTypeField(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteTypeId: Long,
    val ord: Int,
    val name: String,
)
