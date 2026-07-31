package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.LinkedHashMap
import java.util.Locale

/**
 * Sanitized diagnostics for local SABR request-shape experiments.
 */
class SabrRequestDumper private constructor() {

    companion object {
        @JvmStatic
        fun summarize(requestBody: ByteArray): String {
            return try {
                summarizeRequest(requestBody)
            } catch (e: Exception) {
                "undecodableRequest(bytes=" + requestBody.size + ')'
            }
        }

        @Throws(SabrProtocolException::class)
        private fun summarizeRequest(requestBody: ByteArray): String {
            val fields = SabrProto.readFields(requestBody)
            var clientAbrState = "null"
            val selectedFormats = mutableListOf<String>()
            val bufferedRanges = mutableListOf<String>()
            var topLevelPlayerTimeMs: Long = -1
            var ustreamerConfigBytes = -1
            val preferredAudioFormats = mutableListOf<String>()
            val preferredVideoFormats = mutableListOf<String>()
            val preferredSubtitleFormats = mutableListOf<String>()
            var streamerContext = "null"
            var field1000Count = 0
            val unknownFields = mutableListOf<String>()

            for (field in fields) {
                when (field.getNumber()) {
                    1 -> clientAbrState = describeClientAbrState(field.getBytes())
                    2 -> selectedFormats.add(describeFormatId(field.getBytes()))
                    3 -> bufferedRanges.add(describeBufferedRange(field.getBytes()))
                    4 -> topLevelPlayerTimeMs = field.getVarint()
                    5 -> ustreamerConfigBytes = field.getBytes().size
                    16 -> preferredAudioFormats.add(describeFormatId(field.getBytes()))
                    17 -> preferredVideoFormats.add(describeFormatId(field.getBytes()))
                    18 -> preferredSubtitleFormats.add(describeFormatId(field.getBytes()))
                    19 -> streamerContext = describeStreamerContext(field.getBytes())
                    1000 -> field1000Count++
                    else -> unknownFields.add(describeUnknownField(field))
                }
            }

            return "bytes=" + requestBody.size +
                "; fields=" + describeFieldCounts(fields) +
                "; clientAbr={" + clientAbrState + '}' +
                "; selected=" + selectedFormats +
                "; ranges=" + bufferedRanges +
                "; topPlayerTimeMs=" + topLevelPlayerTimeMs +
                "; ustreamer=bytes(" + ustreamerConfigBytes + ')' +
                "; prefAudio=" + preferredAudioFormats +
                "; prefVideo=" + preferredVideoFormats +
                "; prefSub=" + preferredSubtitleFormats +
                "; streamer={" + streamerContext + '}' +
                "; field1000=" + field1000Count +
                "; unknown=" + unknownFields
        }

        @Throws(SabrProtocolException::class)
        private fun describeClientAbrState(data: ByteArray): String {
            val values = mutableListOf<String>()
            for (field in SabrProto.readFields(data)) {
                val name = clientAbrStateFieldName(field.getNumber())
                when {
                    field.getNumber() == 35 && field.getWireType() == SabrProto.WIRE_FIXED32 -> {
                        values.add(
                            name + '=' + String.format(
                                Locale.ROOT, "%.3f",
                                java.lang.Float.intBitsToFloat(SabrProto.asFixed32LittleEndian(field.getBytes()))
                            )
                        )
                    }
                    field.getNumber() == 72 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED ->
                        values.add(name + "={" + SabrProto.summarizeFields(field.getBytes()) + '}')
                    field.getNumber() == 79 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED ->
                        values.add(name + "={" + describePlaybackAuthorization(field.getBytes()) + '}')
                    field.getNumber() == 69 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED ->
                        values.add(name + "=present(len=" + field.getBytes().size + ')')
                    field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED ->
                        values.add(name + "=bytes(" + field.getBytes().size + ')')
                    isBoolClientAbrStateField(field.getNumber()) ->
                        values.add(name + '=' + (field.getVarint() != 0L))
                    else -> values.add(name + '=' + field.getVarint())
                }
            }
            return join(values)
        }

        @Throws(SabrProtocolException::class)
        private fun describeBufferedRange(data: ByteArray): String {
            var formatId = "format:null"
            var startTimeMs: Long = -1
            var durationMs: Long = -1
            var startSegmentIndex = -1
            var endSegmentIndex = -1
            var timeRange = "null"
            val unknown = mutableListOf<String>()
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> formatId = describeFormatId(field.getBytes())
                    2 -> startTimeMs = field.getVarint()
                    3 -> durationMs = field.getVarint()
                    4 -> startSegmentIndex = field.getVarint().toInt()
                    5 -> endSegmentIndex = field.getVarint().toInt()
                    6 -> timeRange = describeTimeRange(field.getBytes())
                    else -> unknown.add(describeUnknownField(field))
                }
            }
            return formatId + ":seq=" + startSegmentIndex + '-' + endSegmentIndex +
                ":time=" + startTimeMs + '+' + durationMs +
                ":tr=" + timeRange +
                (if (unknown.isEmpty()) "" else ":unknown=" + unknown)
        }

        @Throws(SabrProtocolException::class)
        private fun describeTimeRange(data: ByteArray): String {
            var startTicks: Long = -1
            var durationTicks: Long = -1
            var timescale = -1
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> startTicks = field.getVarint()
                    2 -> durationTicks = field.getVarint()
                    3 -> timescale = field.getVarint().toInt()
                }
            }
            return "$startTicks+$durationTicks@$timescale"
        }

        @Throws(SabrProtocolException::class)
        private fun describeStreamerContext(data: ByteArray): String {
            var clientInfo = "null"
            var poTokenBytes = -1
            var playbackCookie = "null"
            var field4Bytes = -1
            val contexts = mutableListOf<String>()
            val unsentContexts = mutableListOf<Long>()
            var field7Bytes = -1
            var field8Bytes = -1
            val unknown = mutableListOf<String>()
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> clientInfo = describeClientInfo(field.getBytes())
                    2 -> poTokenBytes = field.getBytes().size
                    3 -> playbackCookie = describePlaybackCookie(field.getBytes())
                    4 -> field4Bytes = field.getBytes().size
                    5 -> contexts.add(describeSabrContext(field.getBytes()))
                    6 -> {
                        if (field.getWireType() == SabrProto.WIRE_VARINT) {
                            unsentContexts.add(field.getVarint())
                        } else {
                            unsentContexts.addAll(readRawVarints(field.getBytes()))
                        }
                    }
                    7 -> field7Bytes = field.getBytes().size
                    8 -> field8Bytes = field.getBytes().size
                    else -> unknown.add(describeUnknownField(field))
                }
            }
            return "client=" + clientInfo +
                ", poToken=bytes(" + poTokenBytes + ')' +
                ", playbackCookie=" + playbackCookie +
                ", field4=bytes(" + field4Bytes + ')' +
                ", contexts=" + contexts +
                ", unsent=" + unsentContexts +
                ", field7=bytes(" + field7Bytes + ')' +
                ", field8=bytes(" + field8Bytes + ')' +
                (if (unknown.isEmpty()) "" else ", unknown=" + unknown)
        }

        @Throws(SabrProtocolException::class)
        private fun describeClientInfo(data: ByteArray): String {
            val values = mutableListOf<String>()
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    16 -> values.add("clientName=" + field.getVarint())
                    17 -> values.add("clientVersion=" + field.getString())
                    18 -> values.add("osName=" + field.getString())
                    19 -> values.add("osVersion=" + field.getString())
                    21 -> values.add("acceptLanguage=" + field.getString())
                    22 -> values.add("acceptRegion=" + field.getString())
                    else -> values.add(describeUnknownField(field))
                }
            }
            return '{' + join(values) + '}'
        }

        @Throws(SabrProtocolException::class)
        private fun describeSabrContext(data: ByteArray): String {
            var type = -1
            var valueBytes = -1
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    type = field.getVarint().toInt()
                } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    valueBytes = field.getBytes().size
                }
            }
            return "type=$type/bytes=$valueBytes"
        }

        private fun describePlaybackCookie(data: ByteArray): String {
            return try {
                "bytes(" + data.size + "):" + SabrPlaybackCookie.decode(data).summarize()
            } catch (e: Exception) {
                "bytes(" + data.size + ")"
            }
        }

        private fun describePlaybackAuthorization(data: ByteArray): String {
            return try {
                var authorizedFormats = 0
                var licenseConstraintBytes = -1
                val unknown = mutableListOf<String>()
                for (field in SabrProto.readFields(data)) {
                    if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                        authorizedFormats++
                    } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                        licenseConstraintBytes = field.getBytes().size
                    } else {
                        unknown.add(describeUnknownField(field))
                    }
                }
                "authorized=$authorizedFormats, licenseConstraint=bytes($licenseConstraintBytes)" +
                    (if (unknown.isEmpty()) "" else ", unknown=$unknown")
            } catch (e: Exception) {
                "bytes(" + data.size + ')'
            }
        }

        private fun describeFormatId(data: ByteArray): String {
            return try {
                var itag = -1
                var lastModified: Long = -1
                var xtagsLength = -1
                for (field in SabrProto.readFields(data)) {
                    if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                        itag = field.getVarint().toInt()
                    } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                        lastModified = field.getVarint()
                    } else if (field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                        xtagsLength = field.getBytes().size
                    }
                }
                if (itag < 0) return "bytes(" + data.size + ')'
                "itag:" + itag +
                    (if (lastModified >= 0) "+lm=$lastModified" else "") +
                    (if (xtagsLength >= 0) "+xtagsLen=$xtagsLength" else "")
            } catch (e: Exception) {
                "bytes(" + data.size + ')'
            }
        }

        private fun describeFieldCounts(fields: List<SabrProto.Field>): String {
            val counts = LinkedHashMap<Int, Int>()
            for (field in fields) {
                counts[field.getNumber()] = (counts[field.getNumber()] ?: 0) + 1
            }
            return counts.entries.map { "${it.key}x${it.value}" }.toString()
        }

        @Throws(SabrProtocolException::class)
        private fun describeUnknownField(field: SabrProto.Field): String =
            if (field.getWireType() == SabrProto.WIRE_VARINT) "${field.getNumber()}=${field.getVarint()}"
            else "${field.getNumber()}=bytes(${field.getBytes().size})"

        @Throws(SabrProtocolException::class)
        private fun readRawVarints(data: ByteArray): List<Long> {
            val values = mutableListOf<Long>()
            var offset = 0
            while (offset < data.size) {
                var result = 0L
                var shift = 0
                while (shift < 64) {
                    if (offset >= data.size) throw SabrProtocolException("Unexpected EOF in packed varint")
                    val current = data[offset++].toInt() and 0xff
                    result = result or ((current and 0x7f).toLong() shl shift)
                    if ((current and 0x80) == 0) {
                        values.add(result)
                        break
                    }
                    shift += 7
                }
                if (shift >= 64) throw SabrProtocolException("Packed varint is too long")
            }
            return values
        }

        private fun clientAbrStateFieldName(fieldNumber: Int): String = when (fieldNumber) {
            13 -> "timeSinceLastManualFormatSelectionMs"
            14 -> "lastManualDirection"
            16 -> "lastManualSelectedResolution"
            17 -> "detailedNetworkType"
            18 -> "clientViewportWidth"
            19 -> "clientViewportHeight"
            20 -> "clientBitrateCapBytesPerSec"
            21 -> "stickyResolution"
            22 -> "clientViewportIsFlexible"
            23 -> "bandwidthEstimate"
            24 -> "minAudioQuality"
            25 -> "maxAudioQuality"
            26 -> "videoQualitySetting"
            27 -> "audioRoute"
            28 -> "playerTimeMs"
            29 -> "timeSinceLastSeek"
            30 -> "dataSaverMode"
            32 -> "networkMeteredState"
            34 -> "visibility"
            35 -> "playbackRate"
            36 -> "elapsedWallTimeMs"
            38 -> "mediaCapabilities"
            39 -> "timeSinceLastActionMs"
            40 -> "enabledTrackTypesBitfield"
            43 -> "maxPacingRate"
            44 -> "playerState"
            46 -> "drcEnabled"
            48 -> "field48"
            50 -> "field50"
            51 -> "field51"
            54 -> "sabrReportRequestCancellationInfo"
            55 -> "field55"
            56 -> "disableStreamingXhr"
            57 -> "field57"
            58 -> "preferVp9"
            59 -> "av1QualityThreshold"
            60 -> "field60"
            61 -> "isPrefetch"
            62 -> "sabrSupportQualityConstraints"
            63 -> "sabrLicenseConstraint"
            64 -> "allowProximaLiveLatency"
            66 -> "sabrForceProxima"
            67 -> "field67"
            68 -> "sabrForceMaxNetworkInterruptionDurationMs"
            69 -> "audioTrackId"
            71 -> "field71"
            72 -> "field72"
            73 -> "field73"
            74 -> "field74"
            75 -> "field75"
            76 -> "enableVoiceBoost"
            79 -> "playbackAuthorization"
            80 -> "field80"
            else -> "field$fieldNumber"
        }

        private fun isBoolClientAbrStateField(fieldNumber: Int): Boolean =
            fieldNumber == 22 || fieldNumber == 30 || fieldNumber == 46 ||
                fieldNumber == 56 || fieldNumber == 58 || fieldNumber == 61 ||
                fieldNumber == 62 || fieldNumber == 71

        private fun join(values: List<String>): String = values.joinToString(", ")
    }
}
