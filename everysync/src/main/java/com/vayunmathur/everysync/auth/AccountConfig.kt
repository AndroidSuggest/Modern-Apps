package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.provider.DataType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant reader for [DataType]: unknown enum names (written by a newer app
 * version, e.g. PASSWORDS/NOTES before this build knew them) decode as absent
 * instead of failing the whole [AccountConfig] list — the version gate for
 * forward-compatible account storage.
 */
object TolerantDataTypeSetSerializer : KSerializer<Set<DataType>> {
    private val delegate = SetSerializer(DataType.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<DataType>) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Set<DataType> {
        // JSON path: filter unknown names element-by-element so one future
        // type never drops the stored account. Non-JSON decoders (protobuf,
        // cbor) fall back to strict decode — they version by schema instead.
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element !is kotlinx.serialization.json.JsonArray) return emptySet()
        return element.mapNotNull { item ->
            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            runCatching { DataType.valueOf(name) }.getOrNull()
        }.toSet()
    }
}

/**
 * A configured EverySync account. Persisted as JSON in [AccountStore]; the
 * matching [android.accounts.Account] (type `com.vayunmathur.everysync`) is
 * registered with AccountManager so synced contacts/calendar rows are owned by
 * a real system account — the system providers key rows by (name, type) and
 * some OEMs reject rows whose type has no registered authenticator.
 *
 * Secrets (vault keys) live in the encrypted [TokenStore],
 * keyed by [accountName] — never in this object.
 */
@Serializable
data class AccountConfig(
    /** Unique account name, also the system account name owning the local rows. */
    val accountName: String,
    val providerId: String,
    /**
     * Data types the user enabled for this account. Decodes tolerantly: names
     * from a newer version are dropped rather than failing the account.
     */
    @Serializable(with = TolerantDataTypeSetSerializer::class)
    val enabledTypes: Set<DataType> = emptySet(),
    /** Per-account sync interval override in minutes (null = use global). */
    val intervalMinutes: Int? = null,
    val lastSyncEpochMs: Long = 0L,
    val lastSyncError: String? = null,
)
