@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.notes.platform

import kotlin.uuid.Uuid
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Stores note images as files under `filesDir/note_images`. A [com.vayunmathur.notes.data.NoteBlock.Image]
 * references an image by file name only, so the note database stays small and the
 * images survive app restarts.
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
        val fileName = "img_${Uuid.random()}.jpg"
        val dest = File(dir(context), fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            fileName
        } catch (e: Exception) {
            Log.w(TAG, "import failed for $uri", e)
            dest.delete()
            null
        }
    }

    fun delete(context: Context, fileName: String) {
        try {
            fileFor(context, fileName).delete()
        } catch (_: Exception) {
        }
    }
}
