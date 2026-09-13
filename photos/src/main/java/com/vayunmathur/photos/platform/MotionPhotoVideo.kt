package com.vayunmathur.photos.platform

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.vayunmathur.photos.domain.MotionPhotoXmp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Extracts the embedded MicroVideo clip from a Google motion photo into the
 * app cache, so the viewer can play it with the shared video player.
 *
 * The still JPEG and the MP4 live in one file back to back; [MotionPhotoXmp]
 * reads the XMP-declared byte offset where the video starts, and this copies
 * everything from there to end-of-file. Results are cached per photo id under
 * `cacheDir/motion/` (skipped when already extracted); nothing here touches
 * the database or the sync pipeline. Returns null on any failure — callers
 * stay on the still and report it.
 */
object MotionPhotoVideo {

    /**
     * True when the photo's XMP declares an embedded MicroVideo clip. Opens
     * only the original bytes' EXIF header (mirrors SyncWorker.setExifData);
     * any failure reads as not-motion so the viewer just shows the still.
     */
    suspend fun isMotionPhoto(context: Context, photoUri: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(
                    MediaStore.setRequireOriginal(photoUri.toUri())
                )?.use { stream ->
                    MotionPhotoXmp.parseMicroVideoOffset(
                        ExifInterface(stream).getAttribute(ExifInterface.TAG_XMP)
                    ) != null
                } ?: false
            }.getOrDefault(false)
        }

    suspend fun motionVideoFile(context: Context, photoUri: String, photoId: Long): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = photoUri.toUri()
                val original = MediaStore.setRequireOriginal(uri)
                val offset = context.contentResolver.openInputStream(original)?.use { stream ->
                    MotionPhotoXmp.parseMicroVideoOffset(
                        ExifInterface(stream).getAttribute(ExifInterface.TAG_XMP)
                    )
                } ?: return@runCatching null

                val dir = File(context.cacheDir, "motion").apply { mkdirs() }
                val out = File(dir, "$photoId.mp4")
                if (out.exists() && out.length() > 0) return@runCatching out

                // Sanity-check the offset against the file length when known;
                // an unknown length (-1) just means the check is skipped.
                val total = contentLength(context, uri)
                if (total >= 0 && offset >= total) return@runCatching null

                // Copy to a temp file first so a cancelled run can't leave a
                // truncated .mp4 that the cache check above would trust.
                val tmp = File(dir, "$photoId.tmp")
                context.contentResolver.openInputStream(original)?.use { stream ->
                    if (!skipFully(stream, offset)) return@runCatching null
                    tmp.outputStream().use { outStream -> stream.copyTo(outStream) }
                } ?: return@runCatching null
                if (tmp.length() == 0L || !tmp.renameTo(out)) {
                    tmp.delete()
                    return@runCatching null
                }
                out
            }.getOrNull()
        }

    private fun contentLength(context: Context, uri: Uri): Long {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    /**
     * Skips exactly [n] bytes. [InputStream.skip] may skip fewer than asked
     * (or zero at EOF), so loop; a single-byte read distinguishes a lazy
     * stream from a truncated one.
     */
    private fun skipFully(stream: InputStream, n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (stream.read() == -1) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }
}
