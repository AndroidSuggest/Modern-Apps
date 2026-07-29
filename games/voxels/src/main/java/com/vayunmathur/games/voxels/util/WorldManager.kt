package com.vayunmathur.games.voxels.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

// On-disk metadata for a single world, stored as `world.json` inside the world's directory.
@Serializable
data class WorldMeta(
    val name: String,
    val seed: Int,
    val created: Long,
    val lastPlayed: Long,
)

// A world plus its resolved id (directory name) and absolute save path. Not persisted directly.
data class WorldInfo(
    val id: String,
    val dir: String,
    val meta: WorldMeta,
)

object WorldManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun worldsRoot(ctx: Context): File = File(ctx.filesDir, "worlds").apply { mkdirs() }

    // All worlds, most-recently-played first.
    fun listWorlds(ctx: Context): List<WorldInfo> {
        val root = worldsRoot(ctx)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, "world.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val meta = json.decodeFromString<WorldMeta>(metaFile.readText())
                WorldInfo(id = dir.name, dir = dir.absolutePath, meta = meta)
            } catch (_: Exception) { null }
        }.sortedByDescending { it.meta.lastPlayed }
    }

    // Resolve a free-text seed field to an Int: a number is used directly; other text is hashed;
    // blank produces a fresh random seed.
    fun resolveSeed(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return Random.nextInt()
        t.toIntOrNull()?.let { return it }
        t.toLongOrNull()?.let { return it.toInt() }
        return t.hashCode()
    }

    fun createWorld(ctx: Context, name: String, seed: Int, now: Long): WorldInfo {
        val root = worldsRoot(ctx)
        // Unique directory id derived from the creation time (worlds can share display names).
        var id = "w_$now"
        var i = 1
        while (File(root, id).exists()) { id = "w_${now}_$i"; i++ }
        val dir = File(root, id).apply { mkdirs() }
        val displayName = name.trim().ifEmpty { "New World" }
        val meta = WorldMeta(name = displayName, seed = seed, created = now, lastPlayed = now)
        File(dir, "world.json").writeText(json.encodeToString(WorldMeta.serializer(), meta))
        return WorldInfo(id = id, dir = dir.absolutePath, meta = meta)
    }

    // Bump lastPlayed so the world floats to the top of the list.
    fun touch(ctx: Context, id: String, now: Long) {
        val dir = File(worldsRoot(ctx), id)
        val metaFile = File(dir, "world.json")
        if (!metaFile.exists()) return
        try {
            val meta = json.decodeFromString<WorldMeta>(metaFile.readText())
            metaFile.writeText(json.encodeToString(WorldMeta.serializer(), meta.copy(lastPlayed = now)))
        } catch (_: Exception) {}
    }

    fun deleteWorld(ctx: Context, id: String) {
        File(worldsRoot(ctx), id).deleteRecursively()
    }
}
