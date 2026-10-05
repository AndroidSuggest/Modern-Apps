package com.vayunmathur.taxi.network.lyft

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.ChargeAccount
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Charge-account parsing (ChargeAccountsResponse `u77` → ChargeAccountDTO `c67`)
 *   c67: id=1 (StringValue), kind=2, default=3 (BoolValue), label=5, lastFour=10
 */
internal class LyftChargeAccountsParser(private val session: LyftApiSession) {
    private val json
        get() = session.json

    fun parse(resp: RawResponse): List<ChargeAccount> {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var accounts = runCatching {
            if (isProto) parseProto(resp.bytes) else parseJson(resp.text)
        }.getOrDefault(emptyList())
        if (accounts.isEmpty()) {
            accounts = runCatching {
                if (isProto) parseJson(resp.text) else parseProto(resp.bytes)
            }.getOrDefault(emptyList())
        }
        return accounts
    }

    private fun parseJson(raw: String): List<ChargeAccount> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val arr = root["chargeAccounts"]?.jsonArray
            ?: root["charge_accounts"]?.jsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::toChargeAccountJson) }
    }

    private fun toChargeAccountJson(o: JsonObject): ChargeAccount? {
        val id = o.lyftStr("id") ?: return null
        val lastFour = o.lyftStr("lastFour") ?: o.lyftStr("last_four")
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(o.lyftStr("label"), o.lyftStr("kind"), lastFour),
            isDefault = o["default"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun parseProto(bytes: ByteArray): List<ChargeAccount> {
        val root = ProtoMessage(bytes, 0, bytes.size)
        return root.messages(CHARGE_ACCOUNTS_FIELD).mapNotNull { toChargeAccountProto(it) }
    }

    private fun toChargeAccountProto(m: ProtoMessage): ChargeAccount? {
        val id = m.wrappedString(ChargeAccountFields.ID) ?: return null
        return ChargeAccount(
            id = id,
            chargeToken = null,
            label = accountLabel(
                m.wrappedString(ChargeAccountFields.LABEL),
                m.wrappedString(ChargeAccountFields.KIND),
                m.wrappedString(ChargeAccountFields.LAST_FOUR),
            ),
            isDefault = m.wrappedBool(ChargeAccountFields.IS_DEFAULT) ?: false,
        )
    }

    private fun accountLabel(label: String?, kind: String?, lastFour: String?): String = when {
        !label.isNullOrBlank() -> label
        !kind.isNullOrBlank() && !lastFour.isNullOrBlank() -> "$kind ••$lastFour"
        !lastFour.isNullOrBlank() -> "•• $lastFour"
        !kind.isNullOrBlank() -> kind
        else -> "Card"
    }

    private companion object {
        private const val CHARGE_ACCOUNTS_FIELD = 1
    }

    /** ChargeAccountDTO (`c67`) field tags. */
    private object ChargeAccountFields {
        const val ID = 1
        const val KIND = 2
        const val IS_DEFAULT = 3
        const val LABEL = 5
        const val LAST_FOUR = 10
    }
}
