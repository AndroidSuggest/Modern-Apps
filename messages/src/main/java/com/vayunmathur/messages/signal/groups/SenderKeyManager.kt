package com.vayunmathur.messages.signal.groups

import java.util.UUID
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalSenderKeyStore
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

class SenderKeyManager(
    private val senderKeyStore: SignalSenderKeyStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
) {
    companion object {
        private const val TAG = "SenderKeyManager"
        private const val SENDER_KEY_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000 // 14 days
    }

    // groupId -> (aci -> list of device IDs)
    private val distributedTo = mutableMapOf<String, MutableMap<String, List<Int>>>()
    private val distributionIds = mutableMapOf<String, UUID>()
    private val distributionCreatedAt = mutableMapOf<String, Long>()

    fun createDistributionMessage(groupId: String): SenderKeyDistributionMessage {
        val selfAddress = SignalProtocolAddress(selfAci, selfDeviceId)
        val now = System.currentTimeMillis()
        val createdAt = distributionCreatedAt[groupId]
        if (createdAt != null && (now - createdAt) > SENDER_KEY_MAX_AGE_MS) {
            resetForGroup(groupId)
        }
        val distributionId = distributionIds.getOrPut(groupId) {
            distributionCreatedAt[groupId] = now
            UUID.randomUUID()
        }
        val builder = GroupSessionBuilder(senderKeyStore)
        return builder.create(selfAddress, distributionId)
    }

    fun needsDistribution(groupId: String, memberAci: String, deviceIds: List<Int>? = null): Boolean {
        val distributed = distributedTo[groupId] ?: return true
        val knownDevices = distributed[memberAci] ?: return true
        if (deviceIds != null) {
            return !knownDevices.containsAll(deviceIds)
        }
        return false
    }

    fun markDistributed(groupId: String, memberAci: String, deviceIds: List<Int>) {
        distributedTo.getOrPut(groupId) { mutableMapOf() }[memberAci] = deviceIds
    }

    fun resetForGroup(groupId: String) {
        val distributionId = distributionIds[groupId]
        if (distributionId != null) {
            val selfAddress = SignalProtocolAddress(selfAci, selfDeviceId)
            senderKeyStore.deleteSenderKey(selfAddress, distributionId)
            kotlinx.coroutines.runBlocking {
                senderKeyStore.deleteSenderKeyInfo(groupId)
            }
        }
        distributedTo.remove(groupId)
        distributionIds.remove(groupId)
        distributionCreatedAt.remove(groupId)
        Log.d(TAG, "Reset sender key distribution for group $groupId")
    }

    fun processDistributionMessage(
        groupId: String,
        senderAci: String,
        senderDeviceId: Int,
        distributionMessage: SenderKeyDistributionMessage,
    ) {
        try {
            val senderAddress = SignalProtocolAddress(senderAci, senderDeviceId)
            val builder = GroupSessionBuilder(senderKeyStore)
            builder.process(senderAddress, distributionMessage)
            Log.d(TAG, "Processed sender key distribution message from $senderAci for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process sender key distribution message", e)
        }
    }

    fun encryptForGroup(
        groupId: String,
        plaintext: ByteArray,
    ): ByteArray? {
        val distributionId = distributionIds[groupId] ?: return null
        val selfAddress = SignalProtocolAddress(selfAci, selfDeviceId)
        return try {
            val cipher = GroupCipher(senderKeyStore, selfAddress)
            cipher.encrypt(distributionId, plaintext).serialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for group $groupId", e)
            null
        }
    }

    fun decryptFromGroup(
        senderAci: String,
        senderDeviceId: Int,
        ciphertext: ByteArray,
    ): ByteArray? {
        val senderAddress = SignalProtocolAddress(senderAci, senderDeviceId)
        return try {
            val cipher = GroupCipher(senderKeyStore, senderAddress)
            cipher.decrypt(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from group", e)
            null
        }
    }
}
