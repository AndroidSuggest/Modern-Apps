@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.notes.platform

import kotlin.uuid.Uuid
import android.content.Context
import android.net.Uri
import com.vayunmathur.library.log.Log
import java.io.File
import java.security.MessageDigest

/**
 * Stores note images as files under `filesDir/note_images`. A [com.vayunmathur.notes.data.NoteBlock.Image]
 * references an image by file name only, so the note database stays small and the
 * images survive app restarts.
 *
 * File names are content-addressed (`sha256.jpg` over the file bytes): identical
 * images dedupe to one file, which is what the relay blob store keys on.
 */
object NoteImageStore {
    private const val TAG = "NoteImageStore"

    private fun dir(context: Context): File =
        File(context.filesDir, "note_images").apply { mkdirs() }

    fun fileFor(context: Context, fileName: String): File = File(dir(context), fileName)

    /** Copies [uri] into the images dir and returns the new file name, or null on failure. */
    // Broad catch is deliberate: any content-provider failure mode means "no file"
    // per this function's contract, and the failure is logged below.
    @Suppress("TooGenericExceptionCaught")
    fun import(context: Context, uri: Uri): String? {
        val tmp = File(dir(context), "img_${Uuid.random()}.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                tmp.delete()
                return null
            }
            val name = contentName(tmp)
            val dest = File(dir(context), name)
            // Content-addressed: an identical image may already exist.
            if (dest.exists()) {
                tmp.delete()
            } else if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            name
        } catch (e: Exception) {
            Log.status(TAG, "import failed for $uri", e)
            tmp.delete()
            null
        }
    }

    fun delete(context: Context, fileName: String) {
        try {
            fileFor(context, fileName).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * One-time migration from legacy `img_<uuid>.jpg` names to content-addressed
     * `sha256.jpg` names. Copies (never moves) each legacy file to its hashed name
     * so a crash mid-migration can't lose data, and returns the old->new rename map
     * for [com.vayunmathur.notes.data.NotesRepository.migrateImageReferences].
     * Already-hashed files are left alone and omitted from the map.
     */
    fun migrateToContentAddressed(context: Context): Map<String, String> {
        val directory = dir(context)
        val files = try {
            directory.listFiles()?.toList().orEmpty()
        } catch (_: Exception) {
            return emptyMap()
        }
        val renames = mutableMapOf<String, String>()
        for (file in files) {
            if (!file.isFile || !file.name.startsWith("img_")) continue
            val hashed = try {
                contentName(file)
            } catch (e: Exception) {
                Log.status(TAG, "hash failed for ${file.name}", e)
                continue
            }
            if (hashed == file.name) continue
            val dest = File(directory, hashed)
            try {
                if (!dest.exists()) file.copyTo(dest)
                renames[file.name] = hashed
            } catch (e: Exception) {
                Log.status(TAG, "copy failed for ${file.name}", e)
            }
        }
        return renames
    }

    /**
     * Deletes legacy `img_<uuid>.jpg` files that were superseded by
     * [migrateToContentAddressed]. Call only after the rename map has been applied
     * to every note (no block references the old names anymore).
     */
    fun pruneMigratedSources(context: Context, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        for (old in renames.keys) {
            if (!old.startsWith("img_")) continue
            try {
                File(dir(context), old).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun contentName(file: File): String = "${sha256Hex(file.readBytes())}.jpg"

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
