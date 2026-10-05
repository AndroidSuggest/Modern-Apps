package com.vayunmathur.things.platform

/**
 * The Qingniu/Renpho scale wire codec behind [ScaleBleManager]: command framing,
 * advertisement parsing and measurement decoding.
 *
 * Split out so ScaleBleManager.kt stays under the FileLength limit. Everything
 * here is pure — stateful behaviour stays on the manager — so these are
 * top-level functions in the same package and existing call sites are unchanged,
 * except for the two that read the manager's resistance-encryption flag, which
 * take it as a parameter.
 */

// CmdBuilder command bytes.
internal const val CMD_CONFIG = 0x13
internal const val CMD_OVER = 0x1F
internal const val CMD_TIME = 0x20
internal const val CMD_START = 0x22
internal const val CMD_USER_SYNC = 0xA0
internal const val CMD_IDENTIFY_WEIGHT = 0xA2

/**
 * VA-class scales (ScaleBleUtils.isVaScale) speak a variant of the protocol: the config
 * frame carries no user data, weight sits at bytes 5..6 of the 0x10 frame rather than
 * 3..4, and nothing is reported at all until a user slot is synced with 0xA0.
 */
internal val VA_CATEGORIES = setOf(CATEGORY_128, CATEGORY_129, CATEGORY_134, CATEGORY_143)
internal const val VA_SUB_REGISTER = 1
internal const val VA_SUB_VISIT = 2
internal const val VA_SUB_DELETE = 4
internal const val VA_VISITOR_INDEX = 0xFE
internal const val VA_VISITOR_KEY_HI = 0xFF
internal const val VA_VISITOR_KEY_LO = 0xEE
/** Delete takes every slot at once; bit(n-1) selects slot n, so 0xFF is all eight. */
internal const val VA_DELETE_ALL_MASK = 0xFF
/** Body-fat algorithm id; only the scale's raw impedance is used, so this is inert. */
internal const val VA_ALGORITHM = 7
/** 1 = Asia reference range, 2 = rest of world. */
internal const val VA_FAT_GRADE = 1

/** BleScaleConfig defaults: kilograms, and the scale's display-light interval. */
internal const val UNIT_KG = 1
internal const val LIGHT_INTERVAL = 16

/** ScaleBleUtils.checkScaleType: eight-electrode body-composition scale. */
internal const val CATEGORY_EIGHT_ELECTRODE = 127

/** ScaleBleUtils.checkScaleType's fallback for a plain BLE scale. */
internal const val CATEGORY_DEFAULT = 100

/** Remaining checkScaleType categories, returned per the marker byte below. */
internal const val CATEGORY_101 = 101
internal const val CATEGORY_128 = 128
internal const val CATEGORY_129 = 129
internal const val CATEGORY_130 = 130
internal const val CATEGORY_134 = 134
internal const val CATEGORY_135 = 135
internal const val CATEGORY_142 = 142
internal const val CATEGORY_143 = 143

/** Advertisement marker bytes selecting the categories above. */
internal const val MARKER_CAT_101 = 0x21
internal const val MARKER_CAT_130_A = 0x30
internal const val MARKER_CAT_130_B = 0x31
internal const val MARKER_CAT_EIGHT = 0x50
internal const val MARKER_CAT_134_A = 0x51
internal const val MARKER_CAT_134_B = 0x52
internal const val MARKER_CAT_128_A = 0x60
internal const val MARKER_CAT_128_B = 0x65
internal const val MARKER_CAT_128_C = 0x66
internal const val MARKER_CAT_129_A = 0x61
internal const val MARKER_CAT_129_B = 0x62
internal const val MARKER_CAT_135 = 0x70
internal const val MARKER_CAT_142 = 0x71
internal const val MARKER_CAT_143 = 0x80

/**
 * Epoch the scale timestamps its stored measurements against. DecoderConst has a second,
 * UTC+8-shifted constant, but getBaseTime2000YearSeconds() only returns that one for
 * non-Renpho app IDs — the Renpho SDK init uses this value.
 */
internal const val BASE_TIME_2000_SECONDS = 946684800L

/** QNDecoderImpl.kRatio, fixed for every eight-electrode channel. */
internal const val K_RATIO = 0.1

/** Unsigned-byte mask, and the width of a byte in bits (for packing/unpacking frames). */
internal const val BYTE_MASK = 0xFF
internal const val BITS_PER_BYTE = 8

/**
 * CmdBuilder.buildCmd frame overhead: `[cmd, totalLen, scaleType, ...payload, checksum]`
 * is three header bytes plus the trailing checksum.
 */
internal const val FRAME_OVERHEAD_BYTES = 4
/** Payload starts after the three header bytes. */
internal const val PAYLOAD_OFFSET = 3
/** CmdBuilder.builderTimeData: seconds since the 2000 epoch, little-endian. */
internal const val TIME_PAYLOAD_BYTES = 4

/** Advertisement manufacturer-data index holding the scale category marker byte. */
internal const val MFG_CATEGORY_INDEX = 11
/** Resistance-encryption flag index: byte 15 on VA scales, byte 12 on classic scales. */
internal const val MFG_ENCRYPT_FLAG_INDEX_VA = 15
internal const val MFG_ENCRYPT_FLAG_INDEX_STD = 12

/**
 * MeasureDecoder.resistanceCrypt swaps bit pairs (3,5) and (0,4) on scales that advertise
 * the encryption bit.
 */
internal const val CRYPT_SWAP_HI_A = 3
internal const val CRYPT_SWAP_HI_B = 5
internal const val CRYPT_SWAP_LO_A = 0
internal const val CRYPT_SWAP_LO_B = 4

/** Four-electrode resistance at or above this means no foot contact; decode as 0. */
internal const val NO_CONTACT_RESISTANCE = 60000

/** MeasureDecoder.decodeWeight: while above this many kg, divide by ten (ratio fix-up). */
internal const val MAX_WEIGHT_KG = 300.0
internal const val WEIGHT_RESCALE_DIVISOR = 10.0

/** CmdBuilder.buildCmd: `[cmd, totalLen, scaleType, ...payload, checksum]`. */
internal fun buildCmd(cmd: Int, scaleType: Int, vararg payload: Int): ByteArray =
    buildFrame(cmd, scaleType, *payload)

/** Byte 2 is the scale type for most commands, but a sub-command for 0xA0. */
internal fun buildFrame(cmd: Int, arg: Int, vararg payload: Int): ByteArray {
    val out = ByteArray(payload.size + FRAME_OVERHEAD_BYTES)
    out[0] = cmd.toByte()
    out[1] = out.size.toByte()
    out[2] = arg.toByte()
    for (i in payload.indices) out[i + PAYLOAD_OFFSET] = payload[i].toByte()
    var sum = 0
    for (i in 0 until out.size - 1) sum += out[i].toInt()
    out[out.size - 1] = sum.toByte()
    return out
}

/** CmdBuilder.builderTimeData: seconds since the 2000 epoch, little-endian. */
internal fun timePayload(millis: Long): IntArray {
    val seconds = millis / 1000 - BASE_TIME_2000_SECONDS
    return IntArray(TIME_PAYLOAD_BYTES) { ((seconds shr (it * BITS_PER_BYTE)) and BYTE_MASK).toInt() }
}

/**
 * ScaleBleUtils.checkScaleType, narrowed to the plain BLE scales this app talks to: the
 * category is a marker byte in the advertisement's manufacturer-specific data.
 */
internal fun qnScaleCategory(mfg: ByteArray?): Int {
    if (mfg == null || mfg.size <= MFG_CATEGORY_INDEX) return CATEGORY_DEFAULT
    return when (mfg[MFG_CATEGORY_INDEX].toInt() and BYTE_MASK) {
        MARKER_CAT_101 -> CATEGORY_101
        MARKER_CAT_130_A, MARKER_CAT_130_B -> CATEGORY_130
        MARKER_CAT_EIGHT -> CATEGORY_EIGHT_ELECTRODE
        MARKER_CAT_134_A, MARKER_CAT_134_B -> CATEGORY_134
        MARKER_CAT_128_A, MARKER_CAT_128_B, MARKER_CAT_128_C -> CATEGORY_128
        MARKER_CAT_129_A, MARKER_CAT_129_B -> CATEGORY_129
        MARKER_CAT_135 -> CATEGORY_135
        MARKER_CAT_142 -> CATEGORY_142
        MARKER_CAT_143 -> CATEGORY_143
        else -> CATEGORY_DEFAULT
    }
}

/** ScaleBleUtils.isUseResistanceEncrypt. */
internal fun qnUsesResistanceEncrypt(category: Int, mfg: ByteArray?): Boolean {
    if (mfg == null) return false
    return when (category) {
        CATEGORY_128, CATEGORY_129, CATEGORY_134, CATEGORY_143 ->
            mfg.size > MFG_ENCRYPT_FLAG_INDEX_VA &&
                ((mfg[MFG_ENCRYPT_FLAG_INDEX_VA].toInt() shr 2) and 1) == 1
        CATEGORY_DEFAULT, CATEGORY_EIGHT_ELECTRODE, CATEGORY_130, CATEGORY_135, CATEGORY_142 ->
            mfg.size > MFG_ENCRYPT_FLAG_INDEX_STD &&
                (mfg[MFG_ENCRYPT_FLAG_INDEX_STD].toInt() and 1) == 1
        else -> false
    }
}

// Copy of MeasureDecoder helpers so we don't depend on the QN SDK.
internal fun twoByteInt(hi: Byte, lo: Byte): Int =
    ((hi.toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (lo.toInt() and BYTE_MASK)

/**
 * MeasureDecoder.resistanceCrypt: scales that advertise the encryption bit send each
 * impedance byte with bits 3/5 and 0/4 swapped.
 */
internal fun resistanceCrypt(b: Byte, useResistanceEncrypt: Boolean): Int {
    val raw = b.toInt() and BYTE_MASK
    if (!useResistanceEncrypt) return raw
    return swapBit(CRYPT_SWAP_LO_A, CRYPT_SWAP_LO_B, swapBit(CRYPT_SWAP_HI_A, CRYPT_SWAP_HI_B, raw))
}

internal fun swapBit(a: Int, b: Int, value: Int): Int {
    val bitA = (value shr a) and 1
    if (bitA == ((value shr b) and 1)) return value
    return if (bitA == 0) ((1 shl a) or value) and (1 shl b).inv()
    else ((1 shl b) or value) and (1 shl a).inv()
}

internal fun fourResTwoByte2Int(b1: Byte, b2: Byte, useResistanceEncrypt: Boolean): Int {
    val hi = resistanceCrypt(b1, useResistanceEncrypt)
    val lo = resistanceCrypt(b2, useResistanceEncrypt)
    val v = (hi shl BITS_PER_BYTE) or lo
    return if (v >= NO_CONTACT_RESISTANCE) 0 else v
}

internal fun eightDouble(b1: Byte, b2: Byte, useResistanceEncrypt: Boolean): Double {
    val hi = resistanceCrypt(b1, useResistanceEncrypt)
    val lo = resistanceCrypt(b2, useResistanceEncrypt)
    return ((hi shl BITS_PER_BYTE) or lo) * K_RATIO
}

internal fun decodeWeight(raw: Int, ratio: Double): Double {
    var w = raw.toDouble() / ratio
    while (w > MAX_WEIGHT_KG) w /= WEIGHT_RESCALE_DIVISOR
    return w
}

/** MeasureDecoder.decodeWeightByMultiplication, used by the VA frame layout. */
internal fun decodeWeightByMultiplication(raw: Int, ratio: Double): Double {
    var w = raw * ratio
    while (w > MAX_WEIGHT_KG) w /= WEIGHT_RESCALE_DIVISOR
    return w
}

/** Radix for hex dumps of GATT properties and packet bytes. */
internal const val HEX_RADIX = 16

/** UUID.short(): the 4-hex-digit short form starts at index 4 and ends at 8. */
internal const val UUID_SHORT_START = 4
internal const val UUID_SHORT_END = 8

/** Default weight divisor before the 0x12 scale-info frame reports the real one. */
internal const val DEFAULT_WEIGHT_RATIO = 10.0

/** Incoming packet command bytes dispatched in ScaleBleManager.dispatch. */
internal const val PACKET_MEASURE = 16
internal const val PACKET_SCALE_INFO = 18
internal const val PACKET_HW_VERSION = 20
internal const val PACKET_TIME_ACK = 33
internal const val PACKET_STORED = 35
internal const val PACKET_USER_SYNC = 0xA1

/** Sub-command acked with every CMD_OVER frame. */
internal const val OVER_SUB_DEFAULT = 0x10

/** Key bytes are split big-endian: high byte first. */
internal const val KEY_HIGH_SHIFT = 8

/** Smallest 0xA0 result packet worth parsing. */
internal const val USER_SYNC_MIN_SIZE = 5

// VA stored-record layout: total at 3, index at 4, user at 5, little-endian
// timestamp at 6..9, weight at 10..11, impedance at 12..15.
internal const val VA_STORED_MIN_SIZE = 18
internal const val VA_STORED_TIME_OFFSET = 6
internal const val VA_STORED_R50_HI = 12
internal const val VA_STORED_R50_LO = 13
internal const val VA_STORED_R500_HI = 14
internal const val VA_STORED_R500_LO = 15

// VA 0x10 frame: state at 4, weight at 5..6, impedance at 7..10.
internal const val VA_MEASURE_MIN_SIZE = 7
internal const val VA_STATE_SETTLING = 0
internal const val VA_STATE_LOCKED = 1
internal const val VA_STATE_COMPLETE = 2
internal const val VA_STATE_HEART_RATE = 18
internal const val VA_COMPLETE_MIN_SIZE = 11
internal const val VA_R50_HI = 7
internal const val VA_R50_LO = 8
internal const val VA_R500_HI = 9
internal const val VA_R500_LO = 10

/** Identify-weight frame scales kg by 100 into an integer. */
internal const val IDENTIFY_WEIGHT_SCALE = 100

// 0x12 scale-info layout: type at 2, unit flags at 10, identify-weight support at 16.
internal const val SCALE_INFO_MIN_SIZE = 3
internal const val SCALE_INFO_TYPE_INDEX = 2
/** Bit 0 of the flags byte selects lb (ratio 100); otherwise kg (ratio 10). */
internal const val UNIT_LB_FLAG = 0x01
internal const val SCALE_INFO_FULL_MIN_SIZE = 15
internal const val SCALE_INFO_FLAGS_INDEX = 10
internal const val SCALE_INFO_IDENTIFY_INDEX = 16
internal const val IDENTIFY_WEIGHT_BIT = 5
internal const val WEIGHT_RATIO_LB = 100.0
internal const val WEIGHT_RATIO_KG = 10.0
internal const val KG_RATIO_LB = 0.01
internal const val KG_RATIO_KG = 0.1

// Classic 0x10 frame: sub-command at 5, weight at 3..4, impedance at 6..9.
internal const val MEASURE_MIN_SIZE = 6
internal const val MEASURE_SUB_INDEX = 5
internal const val MEASURE_WEIGHT_HI = 3
internal const val MEASURE_WEIGHT_LO = 4
internal const val MEASURE_STREAMING = 0
internal const val MEASURE_STABLE = 1
internal const val MEASURE_STABLE_HR = 2
internal const val MEASURE_STREAM_MID = 17
internal const val MEASURE_STREAM_HIGH = 18
internal const val MEASURE_STABLE_MIN_SIZE = 10
internal const val MEASURE_R50_HI = 6
internal const val MEASURE_R50_LO = 7
internal const val MEASURE_R500_HI = 8
internal const val MEASURE_R500_LO = 9

/** Eight-electrode burst: ten channels across two packets, byte 6 is count:current. */
internal const val EIGHT_MIN_SIZE = 17
internal const val EIGHT_COUNT_INDEX = 6
/** count:current nibbles packed in byte 6. */
internal const val NIBBLE_SHIFT = 4
internal const val NIBBLE_MASK = 0x0F
internal const val EIGHT_LF20_HI = 7
internal const val EIGHT_LF100_HI = 9
internal const val EIGHT_RF20_HI = 11
internal const val EIGHT_RF100_HI = 13
internal const val EIGHT_LH20_HI = 15
internal const val EIGHT_LH100_HI = 7
internal const val EIGHT_RH20_HI = 9
internal const val EIGHT_RH100_HI = 11
internal const val EIGHT_T20_HI = 13
internal const val EIGHT_T100_HI = 15

// Classic stored-record layout: little-endian timestamp at 5..8, weight at 9..10,
// impedance at 11..14.
internal const val CLASSIC_STORED_MIN_SIZE = 15
internal const val CLASSIC_STORED_TIME_OFFSET = 5
internal const val CLASSIC_STORED_WEIGHT_HI = 9
internal const val CLASSIC_STORED_WEIGHT_LO = 10
internal const val STORED_R50_HI = 11
internal const val STORED_R50_LO = 12
internal const val STORED_R500_HI = 13
internal const val STORED_R500_LO = 14

/** Stored-record timestamps: four little-endian bytes, millis per second. */
internal const val TIME_BYTES = 4
internal const val MILLIS_PER_SECOND = 1000L

/** The scale's clock free-runs; drop replays from the future or over a year back. */
internal const val MAX_STORED_AGE_MILLIS = 365L * 24 * 60 * 60 * 1000
