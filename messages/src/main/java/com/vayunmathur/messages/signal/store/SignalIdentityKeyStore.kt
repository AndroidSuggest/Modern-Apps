package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class SignalIdentityKeyStore(
    private val db: SignalDatabase,
    private val identityKeyPairBase64: String,
    private val localRegistrationId: Int,
) : IdentityKeyStore {

    override fun getIdentityKeyPair(): IdentityKeyPair {
        return IdentityKeyPair(Base64.Default.decode(identityKeyPairBase64))
    }

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = runBlocking { db.identityKeyDao().get(address.name) }
        runBlocking {
            db.identityKeyDao().insert(
                SignalIdentityKeyEntity(address.name, identityKey.serialize(), TRUST_LEVEL_TRUSTED_UNVERIFIED)
            )
        }
        return if (existing != null && !existing.identityKey.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val entity = runBlocking { db.identityKeyDao().get(address.name) } ?: return true
        return entity.trustLevel == TRUST_LEVEL_TRUSTED_UNVERIFIED ||
            entity.trustLevel == TRUST_LEVEL_TRUSTED_VERIFIED
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val entity = runBlocking { db.identityKeyDao().get(address.name) } ?: return null
        return IdentityKey(entity.identityKey)
    }

    companion object {
        const val TRUST_LEVEL_TRUSTED_UNVERIFIED = "TRUSTED_UNVERIFIED"
        const val TRUST_LEVEL_TRUSTED_VERIFIED = "TRUSTED_VERIFIED"
    }
}
