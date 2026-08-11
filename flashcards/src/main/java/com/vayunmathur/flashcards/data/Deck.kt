package com.vayunmathur.flashcards.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Deck(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Deck> {
    override fun withPosition(position: Double) = copy(position = position)
}
