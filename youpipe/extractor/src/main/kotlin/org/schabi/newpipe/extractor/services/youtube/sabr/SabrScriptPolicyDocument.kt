package org.schabi.newpipe.extractor.services.youtube.sabr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Human-readable delivery document for a signed SABR JavaScript policy.
 *
 * The signature covers the canonical payload returned by
 * [SabrScriptPolicy.serialize], not the JSON representation itself.
 */
class SabrScriptPolicyDocument private constructor() {

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_DOCUMENT_BYTES = 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 1024

        /** Encodes one policy and its detached signature as a single UTF-8 JSON document. */
        @JvmStatic
        fun encode(policy: SabrScriptPolicy, signature: ByteArray): ByteArray {
            validateSignature(signature)
            val document = buildJsonObject {
                put("format", JsonPrimitive(FORMAT_VERSION))
                put("revision", JsonPrimitive(policy.getRevision()))
                put("validFromMs", JsonPrimitive(policy.getValidFromMs()))
                put("validUntilMs", JsonPrimitive(policy.getValidUntilMs()))
                put("source", JsonPrimitive(policy.getSource()))
                put("signature", JsonPrimitive(Base64.getEncoder().encodeToString(signature)))
            }
            val encoded = document.toString().toByteArray(StandardCharsets.UTF_8)
            if (encoded.size > MAX_DOCUMENT_BYTES) {
                throw IllegalArgumentException("SABR policy document exceeded size limit")
            }
            return encoded
        }

        /** Decodes JSON and reconstructs the exact payload which must be signature verified. */
        @JvmStatic
        fun decode(encoded: ByteArray): Parsed {
            if (encoded.isEmpty() || encoded.size > MAX_DOCUMENT_BYTES) {
                throw IllegalArgumentException("Invalid SABR policy document size")
            }
            try {
                val jsonString = String(encoded, StandardCharsets.UTF_8)
                val document = Json.parseToJsonElement(jsonString).jsonObject

                if (document.size != 6 ||
                    !document.containsKey("revision") ||
                    !document.containsKey("validFromMs") ||
                    !document.containsKey("validUntilMs") ||
                    !document.containsKey("source") ||
                    !document.containsKey("signature") ||
                    requireLong(document, "format") != FORMAT_VERSION.toLong()
                ) {
                    throw IllegalArgumentException("Unsupported SABR policy document")
                }
                val source = document["source"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                val encodedSignature = document["signature"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (source == null || encodedSignature == null) {
                    throw IllegalArgumentException("Invalid SABR policy document fields")
                }
                val signature = Base64.getDecoder().decode(encodedSignature)
                validateSignature(signature)
                val policy = SabrScriptPolicy(
                    requireLong(document, "revision"),
                    requireLong(document, "validFromMs"),
                    requireLong(document, "validUntilMs"),
                    source
                )
                return Parsed(policy.serialize(), signature)
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("Malformed SABR policy document", error)
            }
        }

        private fun requireLong(document: JsonObject, field: String): Long {
            val value = document[field]
                ?: throw IllegalArgumentException("SABR policy document field is not an exact integer: $field")
            if (value !is JsonPrimitive) {
                throw IllegalArgumentException("SABR policy document field is not an exact integer: $field")
            }
            if (value.isString) {
                throw IllegalArgumentException("SABR policy document field is not an exact integer: $field")
            }
            value.longOrNull?.let { return it }
            // Fallback for numbers that overflow JSON long parsing path or were written as BigInteger
            val content = value.content
            try {
                val bigInt = java.math.BigInteger(content)
                try {
                    return bigInt.longValueExact()
                } catch (ae: ArithmeticException) {
                    throw IllegalArgumentException("SABR policy document integer is out of range: $field", ae)
                }
            } catch (nfe: NumberFormatException) {
                throw IllegalArgumentException("SABR policy document field is not an exact integer: $field")
            }
        }

        private fun validateSignature(signature: ByteArray) {
            if (signature.isEmpty() || signature.size > MAX_SIGNATURE_BYTES) {
                throw IllegalArgumentException("Invalid SABR policy signature size")
            }
        }
    }

    /** Canonical signed payload and its detached signature. */
    class Parsed internal constructor(
        private val payload: ByteArray,
        private val signature: ByteArray
    ) {
        fun getPayload(): ByteArray = payload.clone()
        fun getSignature(): ByteArray = signature.clone()
    }
}
