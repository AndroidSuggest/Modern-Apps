package com.vayunmathur.messages.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

object LightspeedDecoder {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    enum class StepType(val value: Int) {
        BLOCK(1),
        LOAD(2),
        STORE(3),
        STORE_ARRAY(4),
        CALL_STORED_PROCEDURE(5),
        CALL_NATIVE_TYPE_OPERATION(6),
        CALL_NATIVE_OPERATION(7),
        LIST(8),
        UNDEFINED(9),
        INFINITY(10),
        NAN(11),
        RETURN(12),
        BOOL_TO_STR(13),
        BLOBS_TO_STRING(14),
        BLOBS_OF_STRING(15),
        TO_BLOB(16),
        I64_OF_FLOAT(17),
        I64_TO_FLOAT(18),
        I64_FROM_STRING(19),
        I64_TO_STRING(20),
        READ_GK(21),
        READ_QE(22),
        IF(23),
        OR(24),
        AND(25),
        NOT(26),
        IS_NULL(27),
        ENFORCE_NOT_NULL(28),
        GENERIC_EQUAL(29),
        I64_EQUAL(30),
        BLOB_EQUAL(31),
        GENERIC_NOT_EQUAL(32),
        I64_NOT_EQUAL(33),
        BLOB_NOT_EQUAL(34),
        GENERIC_GREATER_THAN(35),
        I64_GREATER_THAN(36),
        BLOB_GREATER_THAN(37),
        GENERIC_GREATER_THAN_OR_EQUAL(38),
        I64_GREATER_THAN_OR_EQUAL(39),
        BLOB_GREATER_THAN_OR_EQUAL(40),
        GENERIC_LESS_THAN(41),
        I64_LESS_THAN(42),
        BLOB_LESS_THAN(43),
        GENERIC_LESS_THAN_OR_EQUAL(44),
        I64_LESS_THAN_OR_EQUAL(45),
        BLOB_LESS_THAN_OR_EQUAL(46),
        THROW(47),
        LOG_CONSOLE(48),
        LOGGER_LOG(49),
        NATIVE_OP_ARRAY_CREATE(50),
        NATIVE_OP_ARRAY_APPEND(51),
        NATIVE_OP_ARRAY_GET_SIZE(52),
        NATIVE_OP_MAP_CREATE(53),
        NATIVE_OP_MAP_GET(54),
        NATIVE_OP_MAP_SET(55),
        NATIVE_OP_MAP_KEYS(56),
        NATIVE_OP_MAP_DELETE(57),
        NATIVE_OP_MAP_HAS(58),
        NATIVE_OP_STR_JOIN(59),
        NATIVE_OP_CURRENT_TIME(60),
        NATIVE_OP_JSON_STRINGIFY(61),
        NATIVE_OP_RNG_NUM(62),
        NATIVE_OP_LOCALIZATION_SUPPORTED(63),
        NATIVE_OP_LOCALIZATION_SUPPORTED_V2(64),
        NATIVE_OP_RESOLVE_LOCALIZED(65),
        NATIVE_OP_RESOLVE_LOCALIZED_V2(66),
        ADD(68),
        I64_ADD(69),
        I64_CAST(70),
        READ_JUSTKNOB(71),
        READ_IGGK(72),
        GET_RUN_MODE(73),
        STR_TRIM(74),
        STR_REPLACE(75),
        JOIN(76),
        STR_LIKE(77),
        LENGTH(78),
        IN(79),
        IN_VEC(80),
        SUB(81),
        MUL(82),
        DIV(83),
        MOD(84),
        I64_SUB(85),
        I64_MUL(86),
        I64_DIV(87),
        I64_MOD(88),
        I64_IN(89),
        I64_IN_VEC(90),
        BITWISE_LEFT_SHIFT(91),
        BITWISE_RIGHT_SHIFT(92),
        ARITHMETIC_RIGHT_SHIFT(93),
        BITWISE_AND(94),
        BITWISE_OR(95),
        BITWISE_XOR(96),
        TERNARY(97),
        XOR(98),
        NULLISH_COALESCE(99),
        READ_COLUMN(100),
        READ_COLUMN_REF(101),
        READ_GROUP_COUNT(102),
        COMMENT(103),
        IMPORT(104),
        LOOP(105),
        QUERY_COMPARISON_EQUAL(106),
        QUERY_COMPARISON_NOT_EQUAL(107),
        QUERY_COMPARISON_GREATER_THAN(108),
        QUERY_COMPARISON_GREATER_THAN_OR_EQUAL(109),
        QUERY_COMPARISON_LESS_THAN(110),
        QUERY_COMPARISON_LESS_THAN_OR_EQUAL(111),
        QUERY_MERGE_CONSTRAINTS(112),
        QUERY_FETCH_ROWS(113),
        QUERY_FILTER_ROWS(114),
        QUERY_SORT_ROWS_BY(115),
        QUERY_DELETE_ROWS(116),
        QUERY_SLICE_ROWS(117),
        QUERY_COUNT_ROWS(118),
        QUERY_PEEK_NEXT_ROW_ID(119),
        QUERY_UPDATE_ROWS(120),
        QUERY_INSERT_ROWS(121),
        QUERY_PUT_ROWS(122),
        QUERY_FOREACH_ROW(123),
        QUERY_SELECT_MATCH_ROW(124),
        QUERY_CURSOR_SLICE(125),
        QUERY_GROUP_BY(126),
        ;

        companion object {
            private val map = entries.associateBy { it.value }
            fun fromValue(v: Int): StepType? = map[v]
        }
    }

    @Serializable
    data class LightSpeedData(
        // The wire sends "name":null on /ls_resp payloads, so this MUST be nullable — a
        // non-nullable String throws JsonDecodingException and the whole response decodes to zero
        // events (that was the #34 "non-empty response → 0 events" bug).
        val name: String? = null,
        val step: JsonElement = JsonNull,
    )

    @Serializable
    data class PublishResponseData(
        @SerialName("request_id") val requestId: Long = 0,
        val payload: String = "",
        val sp: List<String> = emptyList(),
        val target: Int = 0,
    )

    data class DecodedEvent(
        val procedureName: String,
        val args: List<Any?>,
    )

    class Decoder(
        private val dependencies: Map<String, String>,
    ) {
        private val statementRefs = mutableMapOf<Int, Any?>()
        private val events = mutableListOf<DecodedEvent>()

        fun decode(data: JsonElement): Any? {
            if (data !is JsonArray || data.isEmpty()) return primitiveValue(data)

            val stepTypeVal = data[0].jsonPrimitive.intOrNull ?: return null
            val stepType = StepType.fromValue(stepTypeVal) ?: return null
            val stepData = data.subList(1, data.size)

            return when (stepType) {
                StepType.BLOCK -> {
                    for (blockData in stepData) {
                        if (blockData is JsonArray) decode(blockData)
                    }
                    null
                }
                StepType.LOAD -> {
                    val key = stepData[0].jsonPrimitive.intOrNull ?: return null
                    statementRefs[key]
                }
                StepType.STORE -> {
                    val retVal = decode(stepData[1])
                    val key = stepData[0].jsonPrimitive.intOrNull ?: return null
                    statementRefs[key] = retVal
                    null
                }
                StepType.STORE_ARRAY -> {
                    val key = stepData[0].jsonPrimitive.intOrNull ?: return null
                    val value = stepData[1].jsonPrimitive.longOrNull ?: return null
                    statementRefs[key] = value
                    if (data.size > 2) {
                        decode(JsonArray(data.subList(2, data.size)))
                    }
                    null
                }
                StepType.CALL_STORED_PROCEDURE -> {
                    val refName = stepData[0].jsonPrimitive.content
                    val args = stepData.subList(1, stepData.size).map { decode(it) }
                    handleStoredProcedure(refName, args)
                    null
                }
                StepType.UNDEFINED -> null
                StepType.I64_FROM_STRING -> {
                    val strVal = stepData[0].jsonPrimitive.content
                    strVal.toLongOrNull() ?: 0L
                }
                StepType.IF -> {
                    val result = decode(stepData[0])
                    val cond = when (result) {
                        is Long -> result > 0
                        is Boolean -> result
                        else -> false
                    }
                    if (cond) {
                        decode(stepData[1])
                    } else if (stepData.size >= 3 && stepData[2] !is JsonNull) {
                        decode(stepData[2])
                    }
                    null
                }
                StepType.NOT -> {
                    val result = decode(stepData[0])
                    when (result) {
                        is Boolean -> !result
                        is Long -> result == 0L
                        null -> true
                        else -> false
                    }
                }
                StepType.CALL_NATIVE_OPERATION -> {
                    if (stepData.isEmpty()) return null
                    val opCode = stepData[0].jsonPrimitive.intOrNull ?: return null
                    val opArgs = stepData.subList(1, stepData.size)
                    when (opCode) {
                        StepType.NATIVE_OP_CURRENT_TIME.value -> System.currentTimeMillis()
                        StepType.NATIVE_OP_MAP_CREATE.value -> mutableMapOf<String, Any?>()
                        StepType.NATIVE_OP_MAP_GET.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = decode(opArgs[0]) as? Map<String, Any?> ?: return null
                            val key = decode(opArgs[1])?.toString() ?: return null
                            map[key]
                        }
                        StepType.NATIVE_OP_MAP_SET.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = decode(opArgs[0]) as? MutableMap<String, Any?> ?: return null
                            val key = decode(opArgs[1])?.toString() ?: return null
                            val value = decode(opArgs[2])
                            map[key] = value
                            map
                        }
                        StepType.NATIVE_OP_MAP_KEYS.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = decode(opArgs[0]) as? Map<String, Any?> ?: return null
                            map.keys.toList()
                        }
                        StepType.NATIVE_OP_MAP_DELETE.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = decode(opArgs[0]) as? MutableMap<String, Any?> ?: return null
                            val key = decode(opArgs[1])?.toString() ?: return null
                            map.remove(key)
                            map
                        }
                        StepType.NATIVE_OP_MAP_HAS.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val map = decode(opArgs[0]) as? Map<String, Any?> ?: return null
                            val key = decode(opArgs[1])?.toString() ?: return null
                            map.containsKey(key)
                        }
                        StepType.NATIVE_OP_STR_JOIN.value -> {
                            val parts = opArgs.map { decode(it)?.toString() ?: "" }
                            parts.joinToString("")
                        }
                        StepType.NATIVE_OP_JSON_STRINGIFY.value -> {
                            val v = decode(opArgs[0])
                            v?.toString()
                        }
                        StepType.NATIVE_OP_RNG_NUM.value -> (Math.random() * Long.MAX_VALUE).toLong()
                        StepType.NATIVE_OP_ARRAY_CREATE.value -> mutableListOf<Any?>()
                        StepType.NATIVE_OP_ARRAY_APPEND.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val arr = decode(opArgs[0]) as? MutableList<Any?> ?: return null
                            arr.add(decode(opArgs[1]))
                            arr
                        }
                        StepType.NATIVE_OP_ARRAY_GET_SIZE.value -> {
                            @Suppress("UNCHECKED_CAST")
                            val arr = decode(opArgs[0]) as? List<*> ?: return null
                            arr.size
                        }
                        else -> null
                    }
                }
                StepType.CALL_NATIVE_TYPE_OPERATION -> null
                StepType.LIST -> null
                StepType.INFINITY -> Double.POSITIVE_INFINITY
                StepType.NAN -> Double.NaN
                StepType.RETURN -> if (stepData.isNotEmpty()) decode(stepData[0]) else null
                StepType.BOOL_TO_STR -> {
                    val v = decode(stepData[0])
                    v?.toString()
                }
                StepType.BLOBS_TO_STRING -> if (stepData.isNotEmpty()) decode(stepData[0])?.toString() else null
                StepType.BLOBS_OF_STRING -> if (stepData.isNotEmpty()) decode(stepData[0])?.toString() else null
                StepType.I64_OF_FLOAT -> {
                    val v = decode(stepData[0])
                    when (v) {
                        is Double -> v.toLong()
                        is Float -> v.toLong()
                        is Long -> v
                        else -> null
                    }
                }
                StepType.I64_TO_FLOAT -> {
                    val v = decode(stepData[0]) as? Long ?: return null
                    v.toDouble()
                }
                StepType.I64_TO_STRING -> {
                    val v = decode(stepData[0]) as? Long ?: return null
                    v.toString()
                }
                StepType.READ_GK -> null
                StepType.READ_QE -> null
                StepType.OR -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && first > 0 -> first
                        first is Boolean && first -> true
                        second is Long && second > 0 -> second
                        second is Boolean && second -> true
                        else -> second
                    }
                }
                StepType.AND -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && first == 0L -> first
                        first is Boolean && !first -> false
                        else -> second
                    }
                }
                StepType.IS_NULL -> {
                    val v = decode(stepData[0])
                    v == null
                }
                StepType.ENFORCE_NOT_NULL -> decode(stepData[0])
                StepType.GENERIC_EQUAL -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    first == second
                }
                StepType.BLOB_EQUAL -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    first == second
                }
                StepType.GENERIC_NOT_EQUAL -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    first != second
                }
                StepType.I64_NOT_EQUAL -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first != second
                }
                StepType.BLOB_NOT_EQUAL -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    first != second
                }
                StepType.GENERIC_GREATER_THAN,
                StepType.I64_GREATER_THAN,
                StepType.BLOB_GREATER_THAN -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first > second
                }
                StepType.GENERIC_GREATER_THAN_OR_EQUAL,
                StepType.I64_GREATER_THAN_OR_EQUAL,
                StepType.BLOB_GREATER_THAN_OR_EQUAL -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first >= second
                }
                StepType.GENERIC_LESS_THAN,
                StepType.I64_LESS_THAN,
                StepType.BLOB_LESS_THAN -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first < second
                }
                StepType.GENERIC_LESS_THAN_OR_EQUAL,
                StepType.I64_LESS_THAN_OR_EQUAL,
                StepType.BLOB_LESS_THAN_OR_EQUAL -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first <= second
                }
                StepType.THROW -> null
                StepType.LOG_CONSOLE -> null
                StepType.NATIVE_OP_CURRENT_TIME -> System.currentTimeMillis()
                StepType.NATIVE_OP_MAP_CREATE -> mutableMapOf<String, Any?>()
                StepType.NATIVE_OP_MAP_GET -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = decode(stepData[0]) as? Map<String, Any?> ?: return null
                    val key = decode(stepData[1])?.toString() ?: return null
                    map[key]
                }
                StepType.NATIVE_OP_MAP_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = decode(stepData[0]) as? MutableMap<String, Any?> ?: return null
                    val key = decode(stepData[1])?.toString() ?: return null
                    val value = decode(stepData[2])
                    map[key] = value
                    map
                }
                StepType.NATIVE_OP_MAP_KEYS -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = decode(stepData[0]) as? Map<String, Any?> ?: return null
                    map.keys.toList()
                }
                StepType.NATIVE_OP_MAP_DELETE -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = decode(stepData[0]) as? MutableMap<String, Any?> ?: return null
                    val key = decode(stepData[1])?.toString() ?: return null
                    map.remove(key)
                    map
                }
                StepType.NATIVE_OP_MAP_HAS -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = decode(stepData[0]) as? Map<String, Any?> ?: return null
                    val key = decode(stepData[1])?.toString() ?: return null
                    map.containsKey(key)
                }
                StepType.NATIVE_OP_STR_JOIN -> {
                    val parts = stepData.map { decode(it)?.toString() ?: "" }
                    parts.joinToString("")
                }
                StepType.NATIVE_OP_JSON_STRINGIFY -> {
                    val v = decode(stepData[0])
                    v?.toString()
                }
                StepType.NATIVE_OP_RNG_NUM -> (Math.random() * Long.MAX_VALUE).toLong()
                StepType.NATIVE_OP_LOCALIZATION_SUPPORTED,
                StepType.NATIVE_OP_LOCALIZATION_SUPPORTED_V2,
                StepType.NATIVE_OP_RESOLVE_LOCALIZED,
                StepType.NATIVE_OP_RESOLVE_LOCALIZED_V2 -> null
                StepType.NATIVE_OP_ARRAY_CREATE -> mutableListOf<Any?>()
                StepType.NATIVE_OP_ARRAY_APPEND -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = decode(stepData[0]) as? MutableList<Any?> ?: return null
                    arr.add(decode(stepData[1]))
                    arr
                }
                StepType.NATIVE_OP_ARRAY_GET_SIZE -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = decode(stepData[0]) as? List<*> ?: return null
                    arr.size
                }
                StepType.ADD -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && second is Long -> first + second
                        first is Double && second is Double -> first + second
                        first is String && second is String -> first + second
                        first is String -> first + (second?.toString() ?: "")
                        second is String -> (first?.toString() ?: "") + second
                        else -> null
                    }
                }
                StepType.I64_ADD -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first + second
                }
                StepType.I64_CAST -> {
                    val v = decode(stepData[0])
                    when (v) {
                        is Long -> v
                        is Double -> v.toLong()
                        is String -> v.toLongOrNull()
                        else -> null
                    }
                }
                StepType.READ_JUSTKNOB,
                StepType.READ_IGGK,
                StepType.GET_RUN_MODE -> null
                StepType.STR_TRIM -> {
                    val v = decode(stepData[0])?.toString() ?: return null
                    v.trim()
                }
                StepType.STR_REPLACE -> {
                    if (stepData.size < 3) return null
                    val str = decode(stepData[0])?.toString() ?: return null
                    val from = decode(stepData[1])?.toString() ?: return null
                    val to = decode(stepData[2])?.toString() ?: return null
                    str.replace(from, to)
                }
                StepType.JOIN -> {
                    if (stepData.size < 2) return null
                    @Suppress("UNCHECKED_CAST")
                    val arr = decode(stepData[0]) as? List<*> ?: return null
                    val sep = decode(stepData[1])?.toString() ?: ""
                    arr.joinToString(sep) { it?.toString() ?: "" }
                }
                StepType.STR_LIKE -> null
                StepType.LENGTH -> {
                    val v = decode(stepData[0])
                    when (v) {
                        is String -> v.length.toLong()
                        is List<*> -> v.size.toLong()
                        else -> null
                    }
                }
                StepType.IN,
                StepType.IN_VEC -> null
                StepType.SUB -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && second is Long -> first - second
                        first is Double && second is Double -> first - second
                        else -> null
                    }
                }
                StepType.MUL -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && second is Long -> first * second
                        first is Double && second is Double -> first * second
                        else -> null
                    }
                }
                StepType.DIV -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && second is Long && second != 0L -> first / second
                        first is Double && second is Double && second != 0.0 -> first / second
                        else -> null
                    }
                }
                StepType.MOD -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    when {
                        first is Long && second is Long && second != 0L -> first % second
                        first is Double && second is Double && second != 0.0 -> first % second
                        else -> null
                    }
                }
                StepType.I64_SUB -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first - second
                }
                StepType.I64_MUL -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first * second
                }
                StepType.I64_DIV -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    if (second == 0L) null else first / second
                }
                StepType.I64_MOD -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    if (second == 0L) null else first % second
                }
                StepType.I64_IN,
                StepType.I64_IN_VEC -> null
                StepType.BITWISE_LEFT_SHIFT -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first shl second.toInt()
                }
                StepType.BITWISE_RIGHT_SHIFT -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first ushr second.toInt()
                }
                StepType.ARITHMETIC_RIGHT_SHIFT -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first shr second.toInt()
                }
                StepType.BITWISE_AND -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first and second
                }
                StepType.BITWISE_OR -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first or second
                }
                StepType.BITWISE_XOR -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first xor second
                }
                StepType.I64_EQUAL -> {
                    val first = decode(stepData[0]) as? Long ?: return null
                    val second = decode(stepData[1]) as? Long ?: return null
                    first == second
                }
                StepType.TERNARY -> {
                    val cond = decode(stepData[0])
                    val isTrue = when (cond) {
                        is Boolean -> cond
                        is Long -> cond > 0
                        null -> false
                        else -> true
                    }
                    if (isTrue) decode(stepData[1]) else decode(stepData[2])
                }
                StepType.XOR -> {
                    val first = decode(stepData[0])
                    val second = decode(stepData[1])
                    val a = when (first) { is Boolean -> first; is Long -> first > 0; else -> false }
                    val b = when (second) { is Boolean -> second; is Long -> second > 0; else -> false }
                    a xor b
                }
                StepType.NULLISH_COALESCE -> {
                    val first = decode(stepData[0])
                    first ?: decode(stepData[1])
                }
                StepType.TO_BLOB -> {
                    val base64Str = stepData[0].jsonPrimitive.content
                    android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                }
                StepType.LOGGER_LOG -> null
                StepType.READ_COLUMN,
                StepType.READ_COLUMN_REF,
                StepType.READ_GROUP_COUNT -> null
                StepType.COMMENT -> null
                StepType.IMPORT -> null
                StepType.LOOP -> null
                StepType.QUERY_COMPARISON_EQUAL,
                StepType.QUERY_COMPARISON_NOT_EQUAL,
                StepType.QUERY_COMPARISON_GREATER_THAN,
                StepType.QUERY_COMPARISON_GREATER_THAN_OR_EQUAL,
                StepType.QUERY_COMPARISON_LESS_THAN,
                StepType.QUERY_COMPARISON_LESS_THAN_OR_EQUAL,
                StepType.QUERY_MERGE_CONSTRAINTS,
                StepType.QUERY_FETCH_ROWS,
                StepType.QUERY_FILTER_ROWS,
                StepType.QUERY_SORT_ROWS_BY,
                StepType.QUERY_DELETE_ROWS,
                StepType.QUERY_SLICE_ROWS,
                StepType.QUERY_COUNT_ROWS,
                StepType.QUERY_PEEK_NEXT_ROW_ID,
                StepType.QUERY_UPDATE_ROWS,
                StepType.QUERY_INSERT_ROWS,
                StepType.QUERY_PUT_ROWS,
                StepType.QUERY_FOREACH_ROW,
                StepType.QUERY_SELECT_MATCH_ROW,
                StepType.QUERY_CURSOR_SLICE,
                StepType.QUERY_GROUP_BY -> null
            }
        }

        private fun handleStoredProcedure(referenceName: String, args: List<Any?>) {
            val depReference = dependencies[referenceName] ?: return
            events.add(DecodedEvent(depReference, args))
        }

        fun getEvents(): List<DecodedEvent> = events

        private fun primitiveValue(element: JsonElement): Any? {
            if (element is JsonNull) return null
            if (element is JsonPrimitive) {
                if (element.isString) return element.content
                element.longOrNull?.let { return it }
                element.floatOrNull?.let { return it.toDouble() }
                if (element.content == "true") return true
                if (element.content == "false") return false
                return element.content
            }
            return null
        }
    }

    // SP lookup table (from messagix/table/table.go SPTable)
    private val SP_TABLE = mapOf(
        "removeAllRequestsFromAdminApprovalQueue" to "LSRemoveAllRequestsFromAdminApprovalQueue",
        "updateThreadApprovalMode" to "LSUpdateThreadApprovalMode",
        "updateThreadTheme" to "LSUpdateThreadTheme",
        "deleteRtcRoomOnThread" to "LSDeleteRtcRoomOnThread",
        "removeParticipantFromThread" to "LSRemoveParticipantFromThread",
        "moveThreadToArchivedFolder" to "LSMoveThreadToArchivedFolder",
        "setThreadCannotUnsendReason" to "LSSetThreadCannotUnsendReason",
        "clearLocalThreadPictureUrl" to "LSClearLocalThreadPictureUrl",
        "updateInviterId" to "LSUpdateInviterId",
        "addToMemberCount" to "LSAddToMemberCount",
        "updateOrInsertThread" to "LSUpdateOrInsertThread",
        "issueNewTask" to "LSIssueNewTask",
        "deleteThenInsertIGContactInfo" to "LSDeleteThenInsertIGContactInfo",
        "hasMatchingAttachmentCTA" to "LSHasMatchingAttachmentCTA",
        "updateAttachmentCtaAtIndexIgnoringAuthority" to "LSUpdateAttachmentCtaAtIndexIgnoringAuthority",
        "updateAttachmentItemCtaAtIndex" to "LSUpdateAttachmentItemCtaAtIndex",
        "insertAttachmentCta" to "LSInsertAttachmentCta",
        "getFirstAvailableAttachmentCTAID" to "LSGetFirstAvailableAttachmentCTAID",
        "insertAttachmentItem" to "LSInsertAttachmentItem",
        "updateParentFolderReadWatermark" to "LSUpdateParentFolderReadWatermark",
        "markThreadRead" to "LSMarkThreadRead",
        "markThreadReadV2" to "LSMarkThreadReadV2",
        "truncatePresenceDatabase" to "LSTruncatePresenceDatabase",
        "deleteThenInsertContactPresence" to "LSDeleteThenInsertContactPresence",
        "deleteThenInsertIgThreadInfo" to "LSDeleteThenInsertIgThreadInfo",
        "deleteThenInsertMessageRequest" to "LSDeleteThenInsertMessageRequest",
        "replaceOptimisticReaction" to "LSReplaceOptimisticReaction",
        "queryAdditionalGroupThreads" to "LSQueryAdditionalGroupThreads",
        "writeCTAIdToThreadsTable" to "LSWriteCTAIdToThreadsTable",
        "mailboxTaskCompletionApiOnTaskCompletion" to "LSMailboxTaskCompletionApiOnTaskCompletion",
        "updateMessagesOptimisticContext" to "LSUpdateMessagesOptimisticContext",
        "setMessageTextHasLinks" to "LSSetMessageTextHasLinks",
        "syncUpdateThreadName" to "LSSyncUpdateThreadName",
        "setThreadImageURL" to "LSSetThreadImageURL",
        "insertSearchSection" to "LSInsertSearchSection",
        "insertSearchResult" to "LSInsertSearchResult",
        "updateSearchQueryStatus" to "LSUpdateSearchQueryStatus",
        "changeViewerStatus" to "LSChangeViewerStatus",
        "updateParticipantCapabilities" to "LSUpdateParticipantCapabilities",
        "overwriteAllThreadParticipantsAdminStatus" to "LSOverwriteAllThreadParticipantsAdminStatus",
        "updateParticipantSubscribeSourceText" to "LSUpdateParticipantSubscribeSourceText",
        "updateThreadParticipantAdminStatus" to "LSUpdateThreadParticipantAdminStatus",
        "updateThreadInviteLinksInfo" to "LSUpdateThreadInviteLinksInfo",
        "appendDataTraceAddon" to "LSAppendDataTraceAddon",
        "removeAllParticipantsForThread" to "LSRemoveAllParticipantsForThread",
        "applyNewGroupThread" to "LSApplyNewGroupThread",
        "replaceOptimisticThread" to "LSReplaceOptimisticThread",
        "updateOptimisticContextThreadKeys" to "LSUpdateOptimisticContextThreadKeys",
        "replaceOptimsiticMessage" to "LSReplaceOptimsiticMessage",
        "updateTaskQueueName" to "LSUpdateTaskQueueName",
        "updateTaskValue" to "LSUpdateTaskValue",
        "updateDeliveryReceipt" to "LSUpdateDeliveryReceipt",
        "deleteBannersByIds" to "LSDeleteBannersByIds",
        "truncateTablesForSyncGroup" to "LSTruncateTablesForSyncGroup",
        "insertXmaAttachment" to "LSInsertXmaAttachment",
        "insertNewMessageRange" to "LSInsertNewMessageRange",
        "updateExistingMessageRange" to "LSUpdateExistingMessageRange",
        "threadsRangesQuery" to "LSThreadsRangesQuery",
        "updateThreadSnippetFromLastMessage" to "LSUpdateThreadSnippetFromLastMessage",
        "upsertInboxThreadsRange" to "LSUpsertInboxThreadsRange",
        "deleteThenInsertThread" to "LSDeleteThenInsertThread",
        "addParticipantIdToGroupThread" to "LSAddParticipantIdToGroupThread",
        "upsertMessage" to "LSUpsertMessage",
        "clearPinnedMessages" to "LSClearPinnedMessages",
        "mciTraceLog" to "LSMciTraceLog",
        "insertBlobAttachment" to "LSInsertBlobAttachment",
        "updateUnsentMessageCollapsedStatus" to "LSUpdateUnsentMessageCollapsedStatus",
        "executeFirstBlockForSyncTransaction" to "LSExecuteFirstBlockForSyncTransaction",
        "updateThreadsRangesV2" to "LSUpdateThreadsRangesV2",
        "upsertSyncGroupThreadsRange" to "LSUpsertSyncGroupThreadsRange",
        "upsertFolder" to "LSUpsertFolder",
        "upsertFolderSeenTimestamp" to "LSUpsertFolderSeenTimestamp",
        "setHMPSStatus" to "LSSetHMPSStatus",
        "handleRepliesOnUnsend" to "LSHandleRepliesOnUnsend",
        "deleteExistingMessageRanges" to "LSDeleteExistingMessageRanges",
        "writeThreadCapabilities" to "LSWriteThreadCapabilities",
        "upsertSequenceId" to "LSUpsertSequenceId",
        "executeFinallyBlockForSyncTransaction" to "LSExecuteFinallyBlockForSyncTransaction",
        "verifyContactRowExists" to "LSVerifyContactRowExists",
        "taskExists" to "LSTaskExists",
        "removeTask" to "LSRemoveTask",
        "deleteThenInsertMessage" to "LSDeleteThenInsertMessage",
        "deleteThenInsertContact" to "LSDeleteThenInsertContact",
        "updateTypingIndicator" to "LSUpdateTypingIndicator",
        "checkAuthoritativeMessageExists" to "LSCheckAuthoritativeMessageExists",
        "moveThreadToInboxAndUpdateParent" to "LSMoveThreadToInboxAndUpdateParent",
        "updateThreadSnippet" to "LSUpdateThreadSnippet",
        "setMessageDisplayedContentTypes" to "LSSetMessageDisplayedContentTypes",
        "verifyThreadExists" to "LSVerifyThreadExists",
        "updateReadReceipt" to "LSUpdateReadReceipt",
        "setForwardScore" to "LSSetForwardScore",
        "upsertReaction" to "LSUpsertReaction",
        "updateOrInsertReactionV2" to "LSUpdateOrInsertReactionV2",
        "deleteReactionV2" to "LSDeleteReactionV2",
        "deleteThenInsertReactionsV2Detail" to "LSDeleteThenInsertReactionsV2Detail",
        "bumpThread" to "LSBumpThread",
        "updateParticipantLastMessageSendTimestamp" to "LSUpdateParticipantLastMessageSendTimestamp",
        "insertMessage" to "LSInsertMessage",
        "upsertTheme" to "LSUpsertTheme",
        "upsertGradientColor" to "LSUpsertGradientColor",
        "insertStickerAttachment" to "LSInsertStickerAttachment",
        "updateForRollCallMessageDeleted" to "LSUpdateForRollCallMessageDeleted",
        "updateLastSyncCompletedTimestampMsToNow" to "LSUpdateLastSyncCompletedTimestampMsToNow",
        "deleteMessage" to "LSDeleteMessage",
        "handleRepliesOnRemove" to "LSHandleRepliesOnRemove",
        "refreshLastActivityTimestamp" to "LSRefreshLastActivityTimestamp",
        "setPinnedMessage" to "LSSetPinnedMessage",
        "storyContactSyncFromBucket" to "LSStoryContactSyncFromBucket",
        "upsertLiveLocationSharer" to "LSUpsertLiveLocationSharer",
        "deleteLiveLocationSharer" to "LSDeleteLiveLocationSharer",
        "updateSharedAlbumOnMessageRecall" to "LSUpdateSharedAlbumOnMessageRecall",
        "editMessage" to "LSEditMessage",
        "handleRepliesOnMessageEdit" to "LSHandleRepliesOnMessageEdit",
        "updateThreadSnippetFromLastMessageV2" to "LSUpdateThreadSnippetFromLastMessageV2",
        "markOptimisticMessageFailed" to "LSMarkOptimisticMessageFailed",
        "updateSubscriptErrorMessage" to "LSUpdateSubscriptErrorMessage",
        "deleteThenInsertBotProfileInfoCategoryV2" to "LSDeleteThenInsertBotProfileInfoCategoryV2",
        "deleteThenInsertBotProfileInfoV2" to "LSDeleteThenInsertBotProfileInfoV2",
        "handleSyncFailure" to "LSHandleSyncFailure",
        "deleteThread" to "LSDeleteThread",
        "addPollOption" to "LSAddPollOption",
        "addPollOptionV2" to "LSAddPollOptionV2",
        "addPollVote" to "LSAddPollVote",
        "addPollVoteV2" to "LSAddPollVoteV2",
        "addPollForThread" to "LSAddPollForThread",
        "deleteReaction" to "LSDeleteReaction",
        "updateThreadMuteSetting" to "LSUpdateThreadMuteSetting",
        "insertAttachment" to "LSInsertAttachment",
        "updateExtraAttachmentColumns" to "LSUpdateExtraAttachmentColumns",
        "moveThreadToE2EECutoverFolder" to "LSMoveThreadToE2EECutoverFolder",
        "handleFailedTask" to "LSHandleFailedTask",
        "issueNewError" to "LSIssueNewError",
        "removeOptimisticGroupThread" to "LSRemoveOptimisticGroupThread",
        "updateOrInsertEditMessageHistory" to "LSUpdateOrInsertEditMessageHistory",
        "verifyHybridThreadExists" to "LSVerifyHybridThreadExists",
        "updateThreadAuthorityAndMappingWithOTIDFromJID" to "LSUpdateThreadAuthorityAndMappingWithOTIDFromJID",
        "verifyContactParticipantExist" to "LSVerifyContactParticipantExist",
        "verifyCommunityMemberContextualProfileExists" to "LSVerifyCommunityMemberContextualProfileExists",
        "insertCommunityMember" to "LSInsertCommunityMember",
        "updateOrInsertCommunityMember" to "LSUpdateOrInsertCommunityMember",
        "upsertCommunityMemberRanges" to "LSUpsertCommunityMemberRanges",
        "updateSubThreadXMA" to "LSUpdateSubThreadXMA",
        "setNumUnreadSubthreads" to "LSSetNumUnreadSubthreads",
        "hybridThreadDelete" to "LSHybridThreadDelete",
        "resetGroupInvites" to "LSResetGroupInvites",
        "deleteMessageRequest" to "LSDeleteMessageRequest",
        "fillDeanonCacheForE2EEThread" to "LSFillDeanonCacheForE2EEThread",
    )

    /** All known stored-procedure short names (SP_TABLE keys). Used to resolve a page-snapshot
     * payload without relying on that block's own (small) dependency list. */
    fun allDependencyNames(): List<String> = SP_TABLE.keys.toList()

    fun spToDepMap(sp: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (entry in sp) {
            val depName = SP_TABLE[entry] ?: continue
            map[entry] = depName
        }
        return map
    }

    fun decodePublishResponse(payload: String, sp: List<String>): List<DecodedEvent> {
        return try {
            val lsData = json.decodeFromString<LightSpeedData>(payload)
            // Resolve against the FULL SP_TABLE (not just the response's declared sp): a response can
            // call a stored procedure — e.g. insertBlobAttachment for a photo — that its own sp list
            // omits, which would silently drop that row. SP_TABLE is authoritative and superset-safe.
            val dependencies = spToDepMap(allDependencyNames())
            val decoder = Decoder(dependencies)
            decoder.decode(lsData.step)
            decoder.getEvents()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
