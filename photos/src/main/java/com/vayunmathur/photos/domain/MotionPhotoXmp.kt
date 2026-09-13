package com.vayunmathur.photos.domain

/**
 * Detects Google motion photos ("MicroVideo") from a photo's XMP packet (as
 * exposed by ExifInterface's TAG_XMP), without touching the file's video bytes.
 *
 * A motion photo is a still JPEG with the clip appended after the image data:
 * `GCamera:MicroVideo="1"` marks the container and `GCamera:MicroVideoOffset`
 * gives the byte offset where the embedded MP4 starts. Returns that offset, or
 * null when the packet is absent, isn't a motion photo, or carries no usable
 * offset. Pure logic — no Android, no IO — so it unit-tests anywhere.
 *
 * Samsung (SEF) motion photos use a different container and are out of scope.
 */
object MotionPhotoXmp {

    fun parseMicroVideoOffset(xmp: String?): Long? {
        if (xmp.isNullOrEmpty()) return null
        if (!attr(xmp, "MicroVideo").equals("1", ignoreCase = true)) return null
        return attr(xmp, "MicroVideoOffset")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }

    // MicroVideo fields appear either as attributes
    // (GCamera:MicroVideoOffset="123") or as child elements
    // (<GCamera:MicroVideoOffset>123</...>); handle both, namespace-agnostic.
    // Same shape as the GPano lookup in PanoXmpParser.
    private fun attr(xmp: String, name: String): String? {
        Regex("""[:\s]$name\s*=\s*["']([^"']*)["']""").find(xmp)?.let { return it.groupValues[1] }
        Regex("""<[^>]*:$name>\s*([^<]*)\s*</""").find(xmp)?.let { return it.groupValues[1].trim() }
        return null
    }
}
