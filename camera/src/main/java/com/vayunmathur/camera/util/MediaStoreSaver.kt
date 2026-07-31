package com.vayunmathur.camera.util

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Centralizes the DCIM/Camera MediaStore writes shared by photo, video and panorama capture. */
object MediaStoreSaver {

    private val FileStamp = LocalDateTime.Format {
        year(); monthNumber(); day(); char('_'); hour(); minute(); second()
    }

    fun timestamp(): String =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).format(FileStamp)

    fun imageValues(displayName: String): ContentValues = contentValues(displayName, "image/jpeg")

    fun videoValues(displayName: String): ContentValues = contentValues(displayName, "video/mp4")

    private fun contentValues(displayName: String, mimeType: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
    }

    fun saveBitmap(
        resolver: ContentResolver,
        values: ContentValues,
        bitmap: Bitmap,
        quality: Int = 95,
    ): Uri? {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        }
        return uri
    }

    /** Write pre-encoded JPEG [bytes] (e.g. with injected XMP) to the image store. */
    fun saveJpegBytes(
        resolver: ContentResolver,
        values: ContentValues,
        bytes: ByteArray,
    ): Uri? {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
        }
        return uri
    }

    fun saveVideoFile(resolver: ContentResolver, values: ContentValues, file: File): Uri? {
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { input -> input.copyTo(os) }
        }
        return uri
    }
}
