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
internal val VA_CATEGORIES = setOf(128, 129, 134, 143)
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

/**
 * Epoch the scale timestamps its stored measurements against. DecoderConst has a second,
 * UTC+8-shifted constant, but getBaseTime2000YearSeconds() only returns that one for
 * non-Renpho app IDs — the Renpho SDK init uses this value.
 */
internal const val BASE_TIME_2000_SECONDS = 946684800L

/** QNDecoderImpl.kRatio, fixed for every eight-electrode channel. */
internal const val K_RATIO = 0.1

/** CmdBuilder.buildCmd: `[cmd, totalLen, scaleType, ...payload, checksum]`. */
internal fun buildCmd(cmd: Int, scaleType: Int, vararg payload: Int): ByteArray =
    buildFrame(cmd, scaleType, *payload)

/** Byte 2 is the scale type for most commands, but a sub-command for 0xA0. */
internal fun buildFrame(cmd: Int, arg: Int, vararg payload: Int): ByteArray {
    val out = ByteArray(payload.size + 4)
    out[0] = cmd.toByte()
    out[1] = out.size.toByte()
    out[2] = arg.toByte()
    for (i in payload.indices) out[i + 3] = payload[i].toByte()
    var sum = 0
    for (i in 0 until out.size - 1) sum += out[i].toInt()
    out[out.size - 1] = sum.toByte()
    return out
}

/** CmdBuilder.builderTimeData: seconds since the 2000 epoch, little-endian. */
internal fun timePayload(millis: Long): IntArray {
    val seconds = millis / 1000 - BASE_TIME_2000_SECONDS
    return IntArray(4) { ((seconds shr (it * 8)) and 0xFF).toInt() }
}

/**
 * ScaleBleUtils.checkScaleType, narrowed to the plain BLE scales this app talks to: the
 * category is a marker byte in the advertisement's manufacturer-specific data.
 */
internal fun qnScaleCategory(mfg: ByteArray?): Int {
    if (mfg == null || mfg.size <= 11) return CATEGORY_DEFAULT
    return when (mfg[11].toInt() and 0xFF) {
        0x21 -> 101
        0x30, 0x31 -> 130
        0x50 -> CATEGORY_EIGHT_ELECTRODE
        0x51, 0x52 -> 134
        0x60, 0x65, 0x66 -> 128
        0x61, 0x62 -> 129
        0x70 -> 135
        0x71 -> 142
        0x80 -> 143
        else -> CATEGORY_DEFAULT
    }
}

/** ScaleBleUtils.isUseResistanceEncrypt. */
internal fun qnUsesResistanceEncrypt(category: Int, mfg: ByteArray?): Boolean {
    if (mfg == null) return false
    return when (category) {
        128, 129, 134, 143 -> mfg.size > 15 && ((mfg[15].toInt() shr 2) and 1) == 1
        CATEGORY_DEFAULT, 127, 130, 135, 142 -> mfg.size > 12 && (mfg[12].toInt() and 1) == 1
        else -> false
    }
}

// Copy of MeasureDecoder helpers so we don't depend on the QN SDK.
internal fun twoByteInt(hi: Byte, lo: Byte): Int =
    ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

/**
 * MeasureDecoder.resistanceCrypt: scales that advertise the encryption bit send each
 * impedance byte with bits 3/5 and 0/4 swapped.
 */
internal fun resistanceCrypt(b: Byte, useResistanceEncrypt: Boolean): Int {
    val raw = b.toInt() and 0xFF
    return if (useResistanceEncrypt) swapBit(0, 4, swapBit(3, 5, raw)) else raw
}

internal fun swapBit(a: Int, b: Int, value: Int): Int {
    val bitA = (value shr a) and 1
    if (bitA == ((value shr b) and 1)) return value
    return if (bitA == 0) ((1 shl a) or value) and (1 shl b).inv()
    else ((1 shl b) or value) and (1 shl a).inv()
}

internal fun fourResTwoByte2Int(b1: Byte, b2: Byte, useResistanceEncrypt: Boolean): Int {
    val v = (resistanceCrypt(b1, useResistanceEncrypt) shl 8) or resistanceCrypt(b2, useResistanceEncrypt)
    return if (v >= 60000) 0 else v
}

internal fun eightDouble(b1: Byte, b2: Byte, useResistanceEncrypt: Boolean): Double =
    ((resistanceCrypt(b1, useResistanceEncrypt) shl 8) or resistanceCrypt(b2, useResistanceEncrypt)) * K_RATIO

internal fun decodeWeight(raw: Int, ratio: Double): Double {
    var w = raw.toDouble() / ratio
    while (w > 300.0) w /= 10.0
    return w
}

/** MeasureDecoder.decodeWeightByMultiplication, used by the VA frame layout. */
internal fun decodeWeightByMultiplication(raw: Int, ratio: Double): Double {
    var w = raw * ratio
    while (w > 300.0) w /= 10.0
    return w
}
