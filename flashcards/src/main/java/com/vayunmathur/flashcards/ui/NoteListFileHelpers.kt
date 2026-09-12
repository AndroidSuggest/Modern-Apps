package com.vayunmathur.flashcards.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable

internal fun queryFileName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

internal fun isZip(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        } ?: false
    }.getOrDefault(false)
