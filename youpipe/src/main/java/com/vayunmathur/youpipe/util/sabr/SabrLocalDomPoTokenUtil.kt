package com.vayunmathur.youpipe.util.sabr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.util.Base64

internal data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterUrl: String,
)

internal fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = JsonUtils.toJsonObject(rawAttestationData).getObject("bgChallenge")
        ?: throw IllegalArgumentException("Missing bgChallenge in attestation data")
    val interpreterUrl = challenge.getObject("interpreterUrl")
        ?.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue").orEmpty()
    return SabrAttChallengeData(
        program = challenge.getString("program").orEmpty(),
        globalName = challenge.getString("globalName").orEmpty(),
        interpreterUrl = if (interpreterUrl.startsWith("//")) {
            "https:$interpreterUrl"
        } else {
            interpreterUrl
        },
    )
}

internal fun buildSabrAttChallengeData(
    challengeData: SabrAttChallengeData,
    interpreterJavascript: String,
): String {
    return buildJsonObject {
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJavascript)
            put(
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                challengeData.interpreterUrl,
            )
        }
        put("program", challengeData.program)
        put("globalName", challengeData.globalName)
    }.toString()
}

internal fun parseSabrIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonUtils.toJsonArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData[0].jsonPrimitive.content) to
        integrityTokenData[1].jsonPrimitive.content.toLong()
}

internal fun stringToSabrU8(value: String): String {
    return newUint8Array(value.toByteArray())
}

internal fun csvU8ToByteArray(value: String): ByteArray {
    if (value.isBlank()) {
        return ByteArray(0)
    }
    return value.split(",").map { it.toUByte().toByte() }.toByteArray()
}

private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteArray(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") {
        it.toUByte().toString()
    } + "])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    val normalized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return Base64.getDecoder().decode(normalized)
}
