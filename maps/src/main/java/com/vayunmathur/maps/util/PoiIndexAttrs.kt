package com.vayunmathur.maps.util

import java.nio.MappedByteBuffer

/**
 * The attribute-decode half of [PoiIndex]: reading `poi_attrs.bin` records.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. The entry points
 * ([PoiIndex.attributesAt]) stay on the object and delegate to
 * [decodeAttrRecord] here.
 */

internal const val KEY_OPENING_HOURS = 1
internal const val KEY_PHONE = 2
internal const val KEY_WEBSITE = 3
internal const val KEY_HOUSENUMBER = 4
internal const val KEY_STREET = 5
internal const val KEY_CITY = 6
internal const val KEY_POSTCODE = 7
internal const val KEY_CUISINE = 8
internal const val KEY_WHEELCHAIR = 9

/**
 * Walk one record's `u8 key, u16 len, value` fields.
 *
 * A key this build does not know is stepped over using its length rather than
 * abandoning the record, which is the whole reason the values are
 * length-prefixed: a device on an older build must still read the keys it does
 * understand out of a newer file.
 */
internal fun decodeAttrRecord(buf: MappedByteBuffer, from: Int, to: Int): PoiIndex.PoiAttributes? {
    var openingHours: String? = null
    var phone: String? = null
    var website: String? = null
    var houseNumber: String? = null
    var street: String? = null
    var city: String? = null
    var postcode: String? = null
    var cuisine: String? = null
    var wheelchair: String? = null

    var i = from
    while (i + 3 <= to) {
        val key = buf.get(i).toInt() and 0xFF
        val len = buf.getShort(i + 1).toInt() and 0xFFFF
        val start = i + 3
        if (start + len > to) break
        // Only decode the bytes of a key we are going to keep.
        val value: String? = when (key) {
            KEY_OPENING_HOURS, KEY_PHONE, KEY_WEBSITE, KEY_HOUSENUMBER, KEY_STREET,
            KEY_CITY, KEY_POSTCODE, KEY_CUISINE, KEY_WHEELCHAIR ->
                stringAt(buf, start, len)
            else -> null
        }
        when (key) {
            KEY_OPENING_HOURS -> openingHours = value
            KEY_PHONE -> phone = value
            KEY_WEBSITE -> website = value
            KEY_HOUSENUMBER -> houseNumber = value
            KEY_STREET -> street = value
            KEY_CITY -> city = value
            KEY_POSTCODE -> postcode = value
            KEY_CUISINE -> cuisine = value
            KEY_WHEELCHAIR -> wheelchair = value
        }
        i = start + len
    }

    val decoded = PoiIndex.PoiAttributes(
        openingHours, phone, website, houseNumber, street, city, postcode, cuisine, wheelchair,
    )
    return decoded.takeUnless { it.isEmpty }
}

private fun stringAt(buf: MappedByteBuffer, off: Int, len: Int): String? {
    if (len <= 0) return null
    val bytes = ByteArray(len)
    // Absolute reads via a duplicate, so the shared buffer's position is untouched.
    val dup = buf.duplicate()
    dup.position(off)
    dup.get(bytes, 0, len)
    return String(bytes, Charsets.UTF_8)
}
