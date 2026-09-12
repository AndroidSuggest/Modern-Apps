package com.vayunmathur.files.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.MaterialTheme

// ---- File type / thumbnail helpers ----

internal val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
internal val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts")
internal val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "mid", "amr")
internal val DOC_EXTS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv", "odt")
internal val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
internal val CODE_EXTS = setOf("kt", "java", "c", "cpp", "h", "py", "js", "ts", "html", "css", "json", "xml", "sh", "rs", "go")

internal val COLOR_IMAGE = Color(0xFF4CAF50)
internal val COLOR_VIDEO = Color(0xFF9C27B0)
internal val COLOR_AUDIO = Color(0xFFFF9800)
internal val COLOR_DOC = Color(0xFF2196F3)
internal val COLOR_ARCHIVE = Color(0xFFB28500)
internal val COLOR_APK = Color(0xFF009688)
internal val COLOR_CODE = Color(0xFF607D8B)

/** Leading visual for a browser item: an image thumbnail, or a type-colored icon. */
@Composable
internal fun FileLeading(item: FileBrowserItem, isSelected: Boolean, sizeDp: Dp) {
    val ext = item.name.substringAfterLast('.', "").lowercase()
    if (!item.isDirectory && item.realFile != null && ext in IMAGE_EXTS) {
        AsyncImage(
            model = item.realFile,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        return
    }
    val folderTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    when {
        item.isDirectory -> IconFolder(tint = folderTint)
        ext in VIDEO_EXTS -> IconVideoCamera(tint = COLOR_VIDEO)
        ext in AUDIO_EXTS -> IconLibraryMusic(tint = COLOR_AUDIO)
        ext in DOC_EXTS -> IconDescription(tint = COLOR_DOC)
        ext in ARCHIVE_EXTS -> IconArchive(tint = COLOR_ARCHIVE)
        ext == "apk" -> IconPackage(tint = COLOR_APK)
        ext in CODE_EXTS -> IconCode(tint = COLOR_CODE)
        ext in IMAGE_EXTS -> IconImage(tint = COLOR_IMAGE)
        else -> IconFile(tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}
