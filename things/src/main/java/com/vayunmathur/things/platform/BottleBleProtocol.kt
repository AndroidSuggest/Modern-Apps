package com.vayunmathur.things.platform

import android.util.Log
import java.util.Calendar

/**
 * The WaterH bottle wire codec behind [BleManager]: packet markers, field offsets and the
 * pure encode/decode helpers, plus the water-log drain handlers.
 *
 * Split out so BleManager.kt stays under detekt's TooManyFunctions cap, mirroring how
 * ScaleBleProtocol.kt + ScaleBleConnection.kt split ScaleBleManager. Connection lifecycle
 * and live-sensor merging stay on the manager; everything else here is pure, except the
 * `BleManager` extensions, which read the manager's `internal` drain bookkeeping.
 */

// ASCII markers opening each packet family.
internal const val RP_MARKER_B0 = 0x52 // 'R'
internal const val RP_MARKER_B1 = 0x50 // 'P'
internal const val RT_MARKER_B1 = 0x54 // 'T'
internal const val PT_MARKER_B0 = 0x50 // 'P'
internal const val PT_MARKER_B1 = 0x54 // 'T'
/** Water-log continuation packets carry this in byte 1. */
internal const val PT_CONTINUATION_B1 = 0x06

/** Smallest RP/RT/PT-first packets worth parsing. */
internal const val RP_MIN_SIZE = 6
internal const val RT_MIN_SIZE = 7
internal const val PT_FIRST_MIN_SIZE = 6
/** RT volume/TDS branches read a second word. */
internal const val RT_WORD_MIN_SIZE = 8

/** b5 of an RP packet: reply to a written text/LED signature, never registration. */
internal const val RP_SIGNATURE_RESPONSE = 0x20
internal const val RP_SELECTOR_INDEX = 5
/** Charging states the bottle reports (1 = charging, 2 = charged on cradle). */
internal val CHARGING_STATES = intArrayOf(1, 2)
/** b5 selecting the water-log availability flag. */
internal const val RP_LOG_AVAILABILITY = 0x06
/** b5 selecting the registration state. */
internal const val RP_REGISTRATION_STATE = 0x1C
/** value[6]: 2 = press the bottle button, 6 = registration done. */
internal const val RP_REG_PRESS_BUTTON = 0x02
internal const val RP_REG_DONE = 0x06

/** RP full-snapshot families: b2 selects Vita vs Boost. */
internal const val RP_SNAPSHOT_B2 = 0x00
internal const val RP_VITA_B3 = 0x31
internal const val RP_BOOST_B3 = 0x27
internal const val RP_VITA_MIN_SIZE = 50
internal const val RP_BOOST_MIN_SIZE = 31
/** b3 acknowledging the sync setting; the water logs are requested next. */
internal const val RP_SYNC_ACK_B3 = 0x0F

/** Byte indices into the Vita / Boost snapshots. */
internal const val VITA_TEMP_INDEX = 6
internal const val VITA_BATTERY_INDEX = 9
internal const val VITA_CHARGING_INDEX = 34
internal const val VITA_TDS_OFFSET = 45
internal const val VITA_VOLUME_OFFSET = 49
internal const val BOOST_BATTERY_INDEX = 6
internal const val BOOST_CHARGING_INDEX = 31
internal const val RP_FLAG_INDEX = 6

/** RT selector byte and its values. */
internal const val RT_SELECTOR_INDEX = 5
internal const val RT_VALUE_INDEX = 6
internal const val RT_TEMP_SELECTOR = 0x01
internal const val RT_BATTERY_SELECTOR = 0x02
internal const val RT_CHARGING_SELECTOR = 0x17
internal const val RT_VOLUME_SELECTOR = 0x08
internal const val RT_TDS_SELECTOR = 0x28
internal const val RT_REGISTRATION_SELECTOR = 0x1C
internal const val RT_RECALIBRATE_SELECTOR = 0xA1
/** RT registration result: 3 = user confirmed, 4 = failed. */
internal const val RT_REG_CONFIRMED = 0x03
internal const val RT_REG_FAILED = 0x04
internal const val RT_TDS_OFFSET = 6
internal const val RT_VOLUME_OFFSET = 6

/** PT first packet: byte count at 2, records start at 6; continuations start at 2. */
internal const val PT_COUNT_OFFSET = 2
internal const val PT_FIRST_DATA_OFFSET = 6
internal const val PT_NEXT_DATA_OFFSET = 2
/** Byte 12 of each record is the validity flag; only 0 is a real drink. */
internal const val LOG_RECORD_FLAG_OFFSET = 12
internal const val RECORD_SIZE = 13

/** Log-drain acknowledgement frame head; the drained byte count follows as hex. */
internal const val CMD_DRAIN_ACK_HEAD = "525000040306"
internal const val DRAIN_COUNT_HEX_LEN = 4

/** Bottle capacity the WaterH app divides by for the fill percentage. */
internal const val BOTTLE_CAPACITY_ML = 530f
internal const val PERCENT_SCALE = 100

/** Log-record calendar base year. */
internal const val LOG_RECORD_BASE_YEAR = 2000

/** Sync-frame fixed heads/tails (goal + reminder defaults kept). */
internal const val SYNC_HEAD = "505400140305"
internal const val SYNC_GOAL = "0000"
internal const val SYNC_MID = "0703"
internal const val SYNC_TAIL = "0726"
internal const val SYNC_REMINDER = "00080014003C"
internal const val SYNC_YEAR_MODULO = 100
internal const val SYNC_FIELD_HEX_LEN = 2

/** Radix for hex encode/decode and the two chars per byte. */
internal const val BOTTLE_HEX_RADIX = 16
internal const val HEX_BYTE_LEN = 2

/** Legacy writeCharacteristic boolean mapped onto the API 33+ status codes. */
internal const val WRITE_OK = 0
internal const val WRITE_REJECTED = 1

internal fun beInt(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b[off + 1].toInt() and BYTE_MASK)

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

internal fun toHex(value: Int, padLen: Int): String {
    var h = Integer.toHexString(value)
    while (h.length < padLen) h = "0$h"
    return h
}

internal fun hexToByteArray(hex: String): ByteArray {
    val clean = hex.replace(Regex("\\s+"), "")
    val out = ByteArray(clean.length / HEX_BYTE_LEN)
    for (i in out.indices) {
        out[i] = clean.substring(i * HEX_BYTE_LEN, i * HEX_BYTE_LEN + HEX_BYTE_LEN).toInt(BOTTLE_HEX_RADIX)
            .toByte()
    }
    return out
}

/** Fill level as a percentage, matching the WaterH app's `volume / 530 * 100`. */
internal fun volumePct(raw: Int): Int =
    Math.round(raw / BOTTLE_CAPACITY_ML * PERCENT_SCALE).coerceIn(0, PERCENT_SCALE)

internal fun BleManager.handlePtFirst(value: ByteArray) {
    if (value.size < PT_FIRST_MIN_SIZE || (value[PT_FLAG_INDEX].toInt() and BYTE_MASK) != PT_CONTINUATION_B1) {
        return
    }
    expectedLogs = beInt(value, PT_COUNT_OFFSET) / RECORD_SIZE
    collected.clear()
    parsedRecords = 0
    Log.d(BleManager.TAG, "logs: expecting $expectedLogs records")
    accumulateDrain(value, PT_FIRST_DATA_OFFSET)
}

internal fun BleManager.handlePtContinuation(value: ByteArray) {
    accumulateDrain(value, PT_NEXT_DATA_OFFSET)
}

internal fun BleManager.accumulateDrain(value: ByteArray, start: Int) {
    var i = start
    while (i + RECORD_SIZE <= value.size) {
        parsedRecords++
        // Byte 12 is a validity flag; only 0 is a real drink record (matches the WaterH app,
        // which counts non-zero records separately and never builds a log entry for them).
        if ((value[i + LOG_RECORD_FLAG_OFFSET].toInt() and BYTE_MASK) == 0) {
            collected.add(parseLogRecord(value, i))
        }
        i += RECORD_SIZE
    }
    if (expectedLogs > 0 && parsedRecords >= expectedLogs) {
        Log.d(BleManager.TAG, "logs: drained parsed=$parsedRecords drinks=${collected.size}")
        collected.forEach { DeviceController.onDrinkLog(it) }
        // Acknowledge/clear the drained logs from the bottle so each is counted once.
        enqueueCommand(CMD_DRAIN_ACK_HEAD + toHex(expectedLogs * RECORD_SIZE, DRAIN_COUNT_HEX_LEN))
        expectedLogs = 0
        parsedRecords = 0
        collected.clear()
    }
}

private fun parseLogRecord(b: ByteArray, off: Int): HydrationReading {
    val year = LOG_RECORD_BASE_YEAR + (b[off].toInt() and BYTE_MASK)
    val month = b[off + 1].toInt() and BYTE_MASK
    val day = b[off + 2].toInt() and BYTE_MASK
    val hour = b[off + 3].toInt() and BYTE_MASK
    val min = b[off + 4].toInt() and BYTE_MASK
    val sec = b[off + 5].toInt() and BYTE_MASK
    val amount = beInt(b, off + 6)
    // TDS is a 2-byte big-endian value at [8..9] (confirmed via parseWaterLog bytecode).
    val tds = beInt(b, off + 8)
    // Raw whole-degree temperature; the WaterH app stores it as temp*10 (tenths).
    val temp = b[off + 10].toInt()
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(year, month - 1, day, hour, min, sec)
    return HydrationReading(amount, cal.timeInMillis, tds, temp)
}

internal fun buildSyncCommand(): String {
    val cal = Calendar.getInstance()
    val yy = cal.get(Calendar.YEAR) % SYNC_YEAR_MODULO
    val mo = cal.get(Calendar.MONTH) + 1
    val dd = cal.get(Calendar.DAY_OF_MONTH)
    val hh = cal.get(Calendar.HOUR_OF_DAY)
    val mi = cal.get(Calendar.MINUTE)
    val ss = cal.get(Calendar.SECOND)
    // 505400140305 + goal(2B) + 0703 + YYMMDDHHmmss + 0726 + reminder(6B), defaults kept.
    return SYNC_HEAD + SYNC_GOAL + SYNC_MID +
        toHex(yy, SYNC_FIELD_HEX_LEN) + toHex(mo, SYNC_FIELD_HEX_LEN) +
        toHex(dd, SYNC_FIELD_HEX_LEN) +
        toHex(hh, SYNC_FIELD_HEX_LEN) + toHex(mi, SYNC_FIELD_HEX_LEN) +
        toHex(ss, SYNC_FIELD_HEX_LEN) +
        SYNC_TAIL + SYNC_REMINDER
}
