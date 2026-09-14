package com.vayunmathur.maps.util

import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * Load the POI side files out of a single-archive `.mamaps` file.
 *
 * The archive carries the same five payloads as the side files (`poi_index`,
 * `poi_names`, and the optional `poi_attrs` / `poi_spatial` / `poi_name_index`
 * sections, kinds 8-12) after the tile data, each 8-byte aligned and named by
 * the section directory + `MAMA8` footer. This maps the one file once and
 * slices views of it, then runs the same validators the multi-file path runs
 * ([PoiIndex.attrsFromBuffer] and siblings) — only the mapping step differs,
 * so the checks cannot drift apart.
 *
 * Every access the queries make is absolute (`getInt`, `get`, duplicate +
 * position for bulk), so a slice reads exactly as the whole file it came
 * from: all payload offsets are section-relative either way. The single
 * whole-file mapping is held in [PoiIndex.Mapped.archive], which keeps every
 * slice alive; without it a slice could outlive its mapping.
 */
object PoiArchive {
    private const val TAG = "PoiArchive"

    private const val FOOTER_LEN = 32
    private const val ENTRY_LEN = 32
    private const val DIR_HEADER_LEN = 4
    private const val ALIGN = 8L

    /** Section kinds, mirroring `tilecodec::mamaps::archive::ARCHIVE_KIND_*`. */
    const val KIND_INDEX = 8
    const val KIND_NAMES = 9
    const val KIND_ATTRS = 10
    const val KIND_SPATIAL = 11
    const val KIND_WORDS = 12

    /** Tile-header offsets this needs: `build_id` at 16, `file_len` at 24. */
    private const val HEADER_BUILD_ID_OFF = 16
    private const val HEADER_FILE_LEN_OFF = 24

    private data class Section(val offset: Long, val len: Long)

    /**
     * Map [file] once and build the POI [PoiIndex.Mapped], or null when the
     * file is not a single archive, disagrees with itself, or carries no POI
     * index section. Optional sections absent from the directory degrade
     * exactly as absent files do: no attributes, Morton fallback, scan
     * fallback.
     */
    internal fun openArchive(file: File): PoiIndex.Mapped? {
        if (!file.isFile) return null
        return try {
            val whole = PoiIndex.mapReadOnly(file)
            whole.order(ByteOrder.LITTLE_ENDIAN)
            val sections = parseSections(whole) ?: return null
            val indexBuf = slice(whole, sections, KIND_INDEX) ?: run {
                Log.w(TAG, "archive carries no POI index section")
                return null
            }
            val namesBuf = slice(whole, sections, KIND_NAMES) ?: run {
                Log.w(TAG, "archive carries a POI index but no POI names")
                return null
            }
            if (indexBuf.capacity() == 0 || namesBuf.capacity() == 0) {
                Log.w(TAG, "archive POI index or names section is empty")
                return null
            }
            val count = indexBuf.capacity() / 14
            val attrs = sections[KIND_ATTRS]?.let { PoiIndex.attrsFromBuffer(sliceRaw(whole, it), count) }
            val grid = sections[KIND_SPATIAL]?.let { PoiIndex.spatialFromBuffer(sliceRaw(whole, it), count) }
            val words = sections[KIND_WORDS]?.let { PoiIndex.nameIndexFromBuffer(sliceRaw(whole, it), count) }
            PoiIndex.Mapped(
                index = indexBuf,
                names = namesBuf,
                namesLen = namesBuf.capacity(),
                count = count,
                attrs = attrs?.first,
                attrsBlobStart = attrs?.second ?: 0,
                spatial = grid?.buf,
                cellCount = grid?.cellCount ?: 0,
                lat0E7 = grid?.lat0E7 ?: 0,
                lon0E7 = grid?.lon0E7 ?: 0,
                cellE7 = grid?.cellE7 ?: 0,
                cols = grid?.cols ?: 0,
                nameIdx = words?.first,
                entryCount = words?.second ?: 0,
                archive = whole,
            ).also {
                Log.d(
                    TAG,
                    "Loaded $count POI records from archive, " +
                        "grid=${grid?.cellCount ?: 0} cells, words=${words?.second ?: 0}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map POI sections from archive", e)
            null
        }
    }

    private fun slice(whole: MappedByteBuffer, sections: Map<Int, Section>, kind: Int): MappedByteBuffer? {
        val s = sections[kind] ?: return null
        return sliceRaw(whole, s)
    }

    /**
     * A little-endian view of `[offset, offset + len)`.
     *
     * Absolute gets on the view are section-relative, matching what the
     * validators expect; the view shares the mapping, so no bytes are copied.
     */
    private fun sliceRaw(whole: MappedByteBuffer, s: Section): MappedByteBuffer {
        require(s.offset <= Int.MAX_VALUE && s.len <= Int.MAX_VALUE) { "section past the 2GB mapping cap" }
        val dup = whole.duplicate()
        dup.position(s.offset.toInt())
        dup.limit((s.offset + s.len).toInt())
        // slice() shares the mapping: no bytes are copied, and the parent is
        // pinned by Mapped.archive.
        return (dup.slice() as MappedByteBuffer).also { it.order(ByteOrder.LITTLE_ENDIAN) }
    }

    /**
     * The section directory as kind -> payload extent, or null when corrupt.
     *
     * Mirrors `tilecodec`'s `ArchiveView::parse` container checks — footer
     * magic, directory fit, build-id equality, per-entry bounds/alignment and
     * overlap — so a corrupt container is refused here rather than sliced
     * into. Per-kind magic/version/count agreement stays in the existing
     * validators, which see the same bytes either way.
     */
    private fun parseSections(whole: MappedByteBuffer): Map<Int, Section>? {
        whole.order(ByteOrder.LITTLE_ENDIAN)
        if (whole.capacity() < 128 + FOOTER_LEN) return null
        val buildId = whole.getLong(HEADER_BUILD_ID_OFF)
        val fileLen = whole.getLong(HEADER_FILE_LEN_OFF)
        if (fileLen != whole.capacity().toLong()) {
            Log.w(TAG, "archive declares $fileLen bytes but is ${whole.capacity()}")
            return null
        }
        val footerAt = (whole.capacity() - FOOTER_LEN)
        // "MAMA8\0\0\0": 4D 41 4D 41 38 00 00 00.
        val magic = byteArrayOf(0x4D, 0x41, 0x4D, 0x41, 0x38, 0x00, 0x00, 0x00)
        for (i in magic.indices) {
            if (whole.get(footerAt + i) != magic[i]) {
                Log.d(TAG, "not a single archive (bad MAMA8 magic)")
                return null
            }
        }
        val dirOffset = whole.getLong(footerAt + 8)
        val dirLen = whole.getLong(footerAt + 16)
        val footerBuild = whole.getLong(footerAt + 24)
        if (footerBuild != buildId) {
            Log.w(TAG, "archive footer build disagrees with its header")
            return null
        }
        if (dirOffset < 0 || dirLen < DIR_HEADER_LEN || dirOffset + dirLen > footerAt) return null
        if (dirOffset > Int.MAX_VALUE || dirOffset + dirLen > Int.MAX_VALUE) return null
        val count = whole.getInt(dirOffset.toInt())
        if (count < 0 || count > 32) return null
        val want = DIR_HEADER_LEN + count * ENTRY_LEN
        val aligned = (want + 7) / 8 * 8
        if (dirLen != aligned.toLong()) return null
        val out = LinkedHashMap<Int, Section>()
        var prevKind = -1
        var at = dirOffset.toInt() + DIR_HEADER_LEN
        repeat(count) {
            val kind = whole.get(at).toInt() and 0xFF
            if (whole.get(at + 1) != 0.toByte() || whole.get(at + 2) != 0.toByte() ||
                whole.get(at + 3) != 0.toByte()
            ) {
                return null
            }
            if (whole.getInt(at + 4) != 0) return null
            if (kind <= prevKind) return null
            prevKind = kind
            val offset = whole.getLong(at + 8)
            val len = whole.getLong(at + 16)
            if (len <= 0 || offset % ALIGN != 0L) return null
            if (offset < 0 || offset + len > dirOffset) return null
            out[kind] = Section(offset, len)
            at += ENTRY_LEN
        }
        // Pairwise overlap, same refusal as the tile header's.
        val list = out.values.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (a.offset < b.offset + b.len && b.offset < a.offset + a.len) return null
            }
        }
        return out
    }
}
