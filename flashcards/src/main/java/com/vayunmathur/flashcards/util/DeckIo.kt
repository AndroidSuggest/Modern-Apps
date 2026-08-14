package com.vayunmathur.flashcards.util

import android.content.Context
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.Deck
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A deck plus its cards, the unit of per-deck JSON export/import. */
@Serializable
data class DeckExport(val deck: Deck, val cards: List<Card>)

/** Per-deck JSON export (for share) and CSV import (for bulk card creation). */
object DeckIo {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Writes [deck] + [cards] as JSON to a cache file and returns it. */
    fun writeExport(context: Context, deck: Deck, cards: List<Card>): File {
        val dir = File(context.cacheDir, "shared_decks").apply { mkdirs() }
        val safeName = deck.name.ifBlank { "deck" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "$safeName.json")
        file.writeText(json.encodeToString(DeckExport(deck, cards)))
        return file
    }

    /**
     * Parses CSV text into (front, back) pairs. Accepts two columns per row; a
     * standard-quoted first line of `front,back` is skipped as a header. Handles
     * double-quoted fields with embedded commas and escaped quotes.
     */
    fun parseCsv(text: String): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val fields = splitCsvLine(raw)
            if (fields.size < 2) return@forEachIndexed
            val front = fields[0].trim()
            val back = fields[1].trim()
            if (index == 0 && front.equals("front", true) && back.equals("back", true)) {
                return@forEachIndexed
            }
            if (front.isNotEmpty() || back.isNotEmpty()) rows.add(front to back)
        }
        return rows
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
