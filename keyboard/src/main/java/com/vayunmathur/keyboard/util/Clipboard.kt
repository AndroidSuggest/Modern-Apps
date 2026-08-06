package com.vayunmathur.keyboard.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One thing the user copied. [id] is the capture time in milliseconds, which is also what
 * orders the history and names the cached image file.
 *
 * A clip is either text or an image, never both: [imagePath] points at the keyboard's own
 * copy of the image bytes (see [ClipboardStore.capture]) and is null for text clips.
 */
@Serializable
data class ClipItem(
    val id: Long,
    val text: String = "",
    val imagePath: String? = null,
    val mimeType: String? = null,
    /** Blur this everywhere and never write it to disk. */
    val sensitive: Boolean = false,
) {
    val isImage: Boolean get() = imagePath != null

    val imageFile: File? get() = imagePath?.let(::File)

    /** Single-line form for the chip and the list rows. */
    val preview: String get() = text.replace(Regex("\\s+"), " ").trim()

    /** Same content, ignoring when it was captured — what de-duplication compares. */
    fun sameContentAs(other: ClipItem): Boolean = when {
        isImage || other.isImage -> false
        else -> text == other.text
    }
}

/**
 * The keyboard's clipboard history: the last [MAX_ITEMS] things copied, newest first.
 *
 * Images are copied into [imageDir] the moment they are captured rather than kept as a
 * `content://` reference. The URI grant an app hands the clipboard does not outlive that
 * app's clip, so a reference would paste fine for a minute and then silently fail — which
 * is exactly when a clipboard history is worth having.
 *
 * Sensitive clips (see [looksSensitive]) live in memory for the session only; [serialize]
 * leaves them out, so a password never reaches disk.
 */
class ClipboardStore(private val imageDir: File) {

    var items: List<ClipItem> = emptyList()
        private set

    fun restore(stored: String?) {
        items = decode(stored).filter { it.imageFile?.exists() != false }
    }

    /**
     * Record [item]. Returns it when it is genuinely new; re-copying something already in
     * the history just moves it back to the front and returns null, so the strip does not
     * pop up a chip for a clip the user is only re-using.
     */
    fun add(item: ClipItem): ClipItem? {
        val existing = items.firstOrNull { it.sameContentAs(item) }
        if (existing != null) {
            items = listOf(existing) + (items - existing)
            return null
        }
        items = cap(listOf(item) + items, ::discardImage)
        return item
    }

    fun delete(item: ClipItem) {
        if (items.none { it.id == item.id }) return
        items = items.filterNot { it.id == item.id }
        discardImage(item)
    }

    fun clear() {
        items.forEach(::discardImage)
        items = emptyList()
    }

    fun serialize(): String = encode(items)

    /**
     * Turn the system's primary clip into a [ClipItem], copying image bytes into the cache
     * on the way. Returns null for a clip with nothing usable in it.
     */
    suspend fun capture(context: Context, clip: ClipData, inPasswordField: Boolean): ClipItem? {
        val entry = (if (clip.itemCount > 0) clip.getItemAt(0) else null) ?: return null
        val id = System.currentTimeMillis()
        val flagged = inPasswordField || clip.description.isMarkedSensitive()

        val uri = entry.uri
        val mime = uri?.let { context.contentResolver.getType(it) }
        if (uri != null && mime != null && mime.startsWith("image/")) {
            val file = withContext(Dispatchers.IO) { copyImage(context, uri, id, mime) }
                ?: return null
            return ClipItem(id, imagePath = file.path, mimeType = mime, sensitive = flagged)
        }

        val text = entry.coerceToText(context).toString().trim()
        if (text.isEmpty()) return null
        return ClipItem(
            id = id,
            text = text,
            mimeType = ClipDescription.MIMETYPE_TEXT_PLAIN,
            sensitive = flagged || looksSensitive(text),
        )
    }

    private fun copyImage(context: Context, uri: Uri, id: Long, mime: String): File? = runCatching {
        imageDir.mkdirs()
        val file = File(imageDir, "$id.${mime.substringAfterLast('/', "png")}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use(input::copyTo)
        } ?: return null
        file
    }.getOrNull()

    private fun discardImage(item: ClipItem) {
        runCatching { item.imageFile?.delete() }
    }

    companion object {
        /** Roughly a day of copying; beyond this the history is an archive, not a clipboard. */
        const val MAX_ITEMS = 30

        /** Images are cached as real files, so far fewer of them are kept. */
        const val MAX_IMAGES = 10

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Trim to the caps, newest first, handing every dropped clip to [onDropped] so its
         * cached image file goes with it.
         */
        fun cap(items: List<ClipItem>, onDropped: (ClipItem) -> Unit = {}): List<ClipItem> {
            val kept = ArrayList<ClipItem>(MAX_ITEMS)
            var images = 0
            for (item in items) {
                val room = kept.size < MAX_ITEMS && (!item.isImage || images < MAX_IMAGES)
                if (!room) {
                    onDropped(item)
                    continue
                }
                if (item.isImage) images++
                kept.add(item)
            }
            return kept
        }

        /**
         * A guess at whether [text] is a credential, used to blur clips that arrive without
         * the [ClipDescription.EXTRA_IS_SENSITIVE] flag the platform only added in API 33
         * (and that most apps still do not set).
         *
         * Password-shaped means: one unbroken token of 6–64 characters mixing letters with
         * digits or punctuation. URLs and email addresses fit that description too and are
         * copied far more often than passwords, so they are excluded by name. What is left
         * over-blurs occasionally, which costs one tap on the reveal toggle — the opposite
         * mistake shows someone's password on screen.
         */
        fun looksSensitive(text: String): Boolean {
            if (text.length !in 6..64) return false
            if (text.any { it.isWhitespace() }) return false
            if (text.contains("://") || EMAIL.matches(text)) return false
            if (text.none { it.isLetter() }) return false
            return text.any { it.isDigit() } || text.any { !it.isLetterOrDigit() }
        }

        /** Sensitive clips are dropped here rather than at read time: they never hit disk. */
        fun encode(items: List<ClipItem>): String =
            json.encodeToString(items.filterNot { it.sensitive })

        fun decode(stored: String?): List<ClipItem> {
            if (stored.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<ClipItem>>(stored) }
                .getOrDefault(emptyList())
        }

        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        /**
         * Decode [file] down to roughly [maxPx] on its longest side. There is no image
         * loader in this repo, so this is [BitmapFactory]'s own two-pass bounds-then-sample
         * decode — enough for a thumbnail, and it never holds the full-size bitmap.
         */
        fun decodeThumbnail(file: File, maxPx: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()

        private fun ClipDescription.isMarkedSensitive(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
    }
}
