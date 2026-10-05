package com.vayunmathur.communicate.data.signal.e2e

import android.util.Base64
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalE2EKyberPreKey
import com.vayunmathur.communicate.data.signal.SignalE2EKyberUsedBaseKey
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore

/**
 * [KyberPreKeyStore] backed by Room (split from [PersistentSignalProtocolStore] for file length).
 * Behavior identical; wired via interface delegation.
 */
class RoomKyberPreKeyStore(private val db: SignalDatabase) : KyberPreKeyStore {

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val stored = runBlocking { db.e2eKyberPreKeyDao().get(kyberPreKeyId) }
            ?: throw InvalidKeyIdException("no kyber pre-key $kyberPreKeyId")
        return try {
            KyberPreKeyRecord(stored.record)
        } catch (expected: Exception) {
            throw InvalidKeyIdException("unreadable kyber pre-key $kyberPreKeyId: ${expected.message}")
        }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        runBlocking { db.e2eKyberPreKeyDao().getAll() }
            .mapNotNull { runCatching { KyberPreKeyRecord(it.record) }.getOrNull() }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        storeKyberPreKey(kyberPreKeyId, record, lastResort = false)
    }

    fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord, lastResort: Boolean) {
        runBlocking {
            db.e2eKyberPreKeyDao().insert(
                SignalE2EKyberPreKey(id = kyberPreKeyId, record = record.serialize(), lastResort = lastResort),
            )
        }
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        runBlocking { db.e2eKyberPreKeyDao().exists(kyberPreKeyId) }

    /**
     * One-time keys are consumed. Last-resort keys stay, but the
     * (kyberPreKeyId, signedPreKeyId, baseKey) tuple is recorded so a replayed pre-key message is
     * rejected instead of establishing a second session off the same key material.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val stored = runBlocking { db.e2eKyberPreKeyDao().get(kyberPreKeyId) } ?: return
        if (!stored.lastResort) {
            runBlocking {
                db.e2eKyberPreKeyDao().delete(kyberPreKeyId)
                db.e2eKyberUsedBaseKeyDao().deleteForKyberPreKey(kyberPreKeyId)
            }
            return
        }
        val baseKeyB64 = Base64.encodeToString(baseKey.serialize(), Base64.NO_WRAP)
        val seen = runBlocking { db.e2eKyberUsedBaseKeyDao().exists(kyberPreKeyId, signedPreKeyId, baseKeyB64) }
        if (seen) {
            throw ReusedBaseKeyException(
                "kyber pre-key $kyberPreKeyId already used with signed pre-key $signedPreKeyId and this base key",
            )
        }
        runBlocking {
            db.e2eKyberUsedBaseKeyDao().insert(
                SignalE2EKyberUsedBaseKey(
                    kyberPreKeyId = kyberPreKeyId,
                    signedPreKeyId = signedPreKeyId,
                    baseKeyB64 = baseKeyB64,
                ),
            )
        }
    }
}
